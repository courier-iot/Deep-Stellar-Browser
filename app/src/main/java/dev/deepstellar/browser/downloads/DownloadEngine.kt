package dev.deepstellar.browser.downloads

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import dev.deepstellar.browser.NetUtil
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

/**
 * Built-in download manager with pause / resume (HTTP Range) / cancel.
 * Finished files go to Download/DeepStellar, or wherever you picked.
 *
 * Finished downloads are never recorded. Unfinished ones are remembered across
 * restarts (optional setting) so they can be resumed; they come back paused,
 * because the cookies they started with were wiped.
 */
object DownloadEngine {

    enum class State { QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELLED }

    class Item(
        val id: Long,
        val url: String,
        var fileName: String,
        val mimeType: String,
        val userAgent: String,
        val referer: String?,
        /** Where you chose to save it ("ask where to save"), or null for Download/DeepStellar. */
        val destUri: Uri? = null,
    ) {
        @Volatile var state: State = State.QUEUED
        @Volatile var total: Long = -1
        @Volatile var done: Long = 0
        @Volatile var speedBps: Long = 0
        @Volatile var error: String? = null
        @Volatile var resultUri: Uri? = null
        @Volatile internal var future: Future<*>? = null
        val isActive get() = state == State.QUEUED || state == State.RUNNING
    }

    private val ids = AtomicLong(System.currentTimeMillis())
    private val remember get() = dev.deepstellar.browser.Prefs.raw.getBoolean("transfers_remember", true)
    val items = CopyOnWriteArrayList<Item>()
    private val pool = Executors.newFixedThreadPool(3)
    private lateinit var app: Context

    val activeCount: Int get() = items.count { it.isActive }

    fun enqueue(
        context: Context,
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
        referer: String?,
        destUri: Uri? = null,
        fileName: String? = null,
    ): Item {
        app = context.applicationContext
        val mime = mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(url))
            ?: "application/octet-stream"
        val name = fileName ?: URLUtil.guessFileName(url, contentDisposition, mime)
        val item = Item(ids.incrementAndGet(), url, name, mime, userAgent, referer, destUri)
        items.add(0, item)
        start(item)
        saveIndex()
        return item
    }

    fun pause(item: Item) {
        if (item.state != State.RUNNING && item.state != State.QUEUED) return
        item.state = State.PAUSED
        item.future?.cancel(true)
        saveIndex()
    }

    fun resume(item: Item) {
        if (item.state == State.PAUSED || item.state == State.FAILED) start(item)
    }

    fun cancel(item: Item) {
        item.state = State.CANCELLED
        item.future?.cancel(true)
        partFile(item).delete()
        saveIndex()
    }

    fun remove(item: Item) {
        if (item.isActive || item.state == State.PAUSED || item.state == State.FAILED) cancel(item)
        items.remove(item)
        saveIndex()
    }

    /** Clears the in-memory list (files already saved are kept). */
    fun clearFinished() {
        items.filter { it.state == State.FAILED }.forEach { partFile(it).delete() }
        items.removeIf { it.state == State.DONE || it.state == State.CANCELLED || it.state == State.FAILED }
        saveIndex()
    }

    private fun partDir(): File = partDirFor(app).apply { mkdirs() }
    private fun partDirFor(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "partial")
    private const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000
    private fun partFile(item: Item) = File(partDir(), "${item.id}.part")

    private fun start(item: Item) {
        item.state = State.QUEUED
        item.error = null
        item.future = pool.submit { run(item) }
        TransferService.kick(app)
    }

    private fun run(item: Item) {
        if (item.state == State.CANCELLED || item.state == State.PAUSED) return
        item.state = State.RUNNING
        try {
            if (item.url.startsWith("data:")) {
                saveDataUrl(item)
            } else if (item.url.startsWith("file:")) {
                takeLocalFile(item)
            } else {
                httpDownload(item)
            }
            if (item.state == State.RUNNING) {
                publish(item)
                item.state = State.DONE
            }
        } catch (e: InterruptedException) {
            // paused or cancelled
        } catch (e: Exception) {
            if (item.state == State.RUNNING) {
                item.state = State.FAILED
                item.error = e.message ?: e.javaClass.simpleName
            }
        } finally {
            item.speedBps = 0
            saveIndex()
        }
    }

    /** A file a page built itself, already copied to the browser's private temp folder. */
    private fun takeLocalFile(item: Item) {
        val src = File(Uri.parse(item.url).path ?: throw IllegalStateException("Bad file"))
        val allowed = File(app.cacheDir, "blobs").canonicalPath
        require(src.canonicalPath.startsWith(allowed + File.separator)) { "Not a browser temp file" }
        if (!src.exists()) throw IllegalStateException("The file is gone — download it again")
        val part = partFile(item)
        if (!src.renameTo(part)) { src.copyTo(part, overwrite = true); src.delete() }
        item.total = part.length()
        item.done = item.total
    }

    private fun saveDataUrl(item: Item) {
        val comma = item.url.indexOf(',')
        require(comma > 0) { "Bad data URL" }
        val meta = item.url.substring(5, comma)
        val payload = item.url.substring(comma + 1)
        val bytes = if (meta.endsWith(";base64")) Base64.decode(payload, Base64.DEFAULT)
        else Uri.decode(payload).toByteArray()
        partFile(item).writeBytes(bytes)
        item.total = bytes.size.toLong()
        item.done = item.total
    }

    private fun httpDownload(item: Item) {
        val part = partFile(item)
        var existing = if (part.exists()) part.length() else 0L

        val conn = NetUtil.open(item.url, item.userAgent)
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        CookieManager.getInstance().getCookie(item.url)?.let { conn.setRequestProperty("Cookie", it) }
        item.referer?.let { if (it.startsWith("http")) conn.setRequestProperty("Referer", it) }
        if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")

        val code = conn.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        if (code == HttpURLConnection.HTTP_OK && existing > 0) {
            // Server ignored the Range header → start over.
            existing = 0
            part.delete()
        }
        val len = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        item.total = if (len >= 0) len + existing else -1
        item.done = existing

        // Prefer the server's filename if the page didn't provide one.
        conn.getHeaderField("Content-Disposition")?.let { cd ->
            val guessed = URLUtil.guessFileName(item.url, cd, item.mimeType)
            if (guessed.isNotBlank()) item.fileName = guessed
        }

        RandomAccessFile(part, "rw").use { raf ->
            raf.seek(existing)
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                var windowStart = System.nanoTime()
                var windowBytes = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted || item.state != State.RUNNING) throw InterruptedException()
                    val n = input.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    item.done += n
                    windowBytes += n
                    val now = System.nanoTime()
                    if (now - windowStart >= 1_000_000_000L) {
                        item.speedBps = windowBytes * 1_000_000_000L / (now - windowStart)
                        windowStart = now
                        windowBytes = 0
                    }
                }
            }
        }
        conn.disconnect()
    }

    /** Moves the finished file into the public Download/DeepStellar folder. */
    private fun publish(item: Item) {
        val part = partFile(item)
        item.destUri?.let { dest ->
            // "Ask where to save": write straight to the place you picked.
            app.contentResolver.openOutputStream(dest, "wt")?.use { out -> part.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("Couldn't write to the chosen location")
            part.delete()
            item.resultUri = dest
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, item.fileName)
            put(MediaStore.Downloads.MIME_TYPE, item.mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DeepStellar")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = app.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            // Fallback: keep it in the app's own folder.
            val dest = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), item.fileName)
            part.copyTo(dest, overwrite = true)
            part.delete()
            item.resultUri = Uri.fromFile(dest)
            return
        }
        resolver.openOutputStream(uri)?.use { out -> part.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        part.delete()
        item.resultUri = uri
    }

    // ------------------------------------------------------------ remembered (unfinished) downloads

    private fun indexFile(ctx: Context) = File(ctx.filesDir, "downloads.json")

    @Synchronized
    private fun saveIndex() {
        if (!::app.isInitialized) return
        runCatching {
            if (!remember) { indexFile(app).delete(); return }
            val arr = org.json.JSONArray()
            items.filter { it.state != State.DONE && it.state != State.CANCELLED && !it.url.startsWith("data:") && !it.url.startsWith("file:") }.forEach { d ->
                arr.put(
                    org.json.JSONObject()
                        .put("id", d.id).put("url", d.url).put("name", d.fileName).put("mime", d.mimeType)
                        .put("ua", d.userAgent).put("ref", d.referer ?: "").put("dest", d.destUri?.toString() ?: "")
                        .put("total", d.total)
                        .put("t", d.id.coerceAtMost(System.currentTimeMillis()))
                )
            }
            indexFile(app).writeText(arr.toString())
        }
    }

    /** Called once when the browser opens: bring back unfinished downloads, paused. */
    fun restore(context: Context) {
        app = context.applicationContext
        if (items.isNotEmpty() || !remember) return
        val arr = runCatching { org.json.JSONArray(indexFile(app).readText()) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            // Storage cap: unfinished downloads older than two weeks are dropped (and their temp file deleted).
            if (now - o.optLong("t", now) > MAX_AGE_MS) {
                runCatching { File(partDirFor(app), "${o.getLong("id")}.part").delete() }
                continue
            }
            val item = Item(
                o.getLong("id"), o.getString("url"), o.optString("name", "download"), o.optString("mime", "application/octet-stream"),
                o.optString("ua"), o.optString("ref").ifEmpty { null },
                o.optString("dest").takeIf { it.isNotEmpty() }?.let { Uri.parse(it) },
            )
            item.state = State.PAUSED
            item.total = o.optLong("total", -1)
            item.done = partFile(item).takeIf { it.exists() }?.length() ?: 0L
            items.add(item)
        }
    }

    /** At launch: delete temp files that don't belong to a remembered download. */
    fun wipePartials(context: Context) {
        val keep = HashSet<String>()
        if (dev.deepstellar.browser.Prefs.raw.getBoolean("transfers_remember", true)) runCatching {
            val arr = org.json.JSONArray(indexFile(context).readText())
            for (i in 0 until arr.length()) keep += "${arr.getJSONObject(i).getLong("id")}.part"
        }
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "partial")
        dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }

    /** Panic wipe / turning the setting off: forget everything unfinished. */
    fun forgetAll(context: Context) {
        runCatching { indexFile(context).delete() }
        File(context.getExternalFilesDir(null) ?: context.filesDir, "partial").listFiles()?.forEach { it.delete() }
    }
}
