package dev.deepstellar.browser.torrent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.util.Log
import dev.deepstellar.browser.Prefs
import dev.deepstellar.browser.downloads.TransferService
import org.json.JSONArray
import org.json.JSONObject
import org.libtorrent4j.AlertListener
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Built-in BitTorrent client (libtorrent via libtorrent4j).
 *
 *  - Magnet links and .torrent files
 *  - Unfinished torrents are remembered across restarts (optional) and re-checked on resume
 *  - Per-file selection, sequential download (play while downloading)
 *  - Wi-Fi-only and VPN-only rules, a queue, and seeding limits (ratio / time)
 *
 * Torrent traffic does NOT go through Tor/Orbot even when the browser does.
 */
object TorrentEngine {
    private const val TAG = "TorrentEngine"

    class FileEntry(val index: Int, val path: String, val size: Long)

    class Item(val id: Long, var name: String, val source: String) {
        @Volatile var handle: TorrentHandle? = null
        @Volatile var status: String = "Fetching metadata…"
        @Volatile var progress: Float = 0f
        @Volatile var downBps: Long = 0
        @Volatile var upBps: Long = 0
        @Volatile var peers: Int = 0
        @Volatile var wanted: Long = 0
        @Volatile var done: Long = 0
        /** Paused by you. */
        @Volatile var paused: Boolean = false
        /** Waiting its turn in the queue. */
        @Volatile var queued: Boolean = false
        @Volatile var finished: Boolean = false
        @Volatile var failed: Boolean = false
        /** Seeding stopped because the ratio or time limit was reached. */
        @Volatile var seedDone: Boolean = false
        @Volatile var sequential: Boolean = false
        @Volatile var uploaded: Long = 0
        @Volatile var seedSeconds: Long = 0
        /** Indexes of files you chose not to download. */
        @Volatile var skipped: Set<Int> = emptySet()
        @Volatile var files: List<FileEntry> = emptyList()
        @Volatile var fileProgress: LongArray = LongArray(0)
        /** Set while the details screen is open, so per-file progress is only computed when someone is looking. */
        @Volatile var watchFiles: Boolean = false
        var saveDir: File? = null

        val isSeeding get() = finished && Prefs.torrentSeed && !seedDone
        val isActive get() = !failed && !paused && (!finished || isSeeding)
    }

    val items = CopyOnWriteArrayList<Item>()
    private val ids = AtomicLong(System.currentTimeMillis())
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private var session: SessionManager? = null
    @Volatile private var throttled = false
    @Volatile private var sessionHeld = false
    private var pollStarted = false
    @Volatile var holdReason: String? = null
        private set
    private lateinit var app: Context
    private val pendingByName = ConcurrentHashMap<String, Item>()

    val activeCount: Int get() = items.count { it.isActive }

    // ------------------------------------------------------------- settings

    private val remember get() = Prefs.raw.getBoolean("transfers_remember", true)
    private val wifiOnly get() = Prefs.raw.getBoolean("torrent_wifi_only", false)
    private val vpnOnly get() = Prefs.raw.getBoolean("torrent_vpn_only", false)
    private val startPaused get() = Prefs.raw.getBoolean("torrent_start_paused", false)
    private val maxActive get() = Prefs.raw.getString("torrent_max_active", "3")?.toIntOrNull() ?: 3
    private val seedRatio get() = Prefs.raw.getString("torrent_seed_ratio", "0")?.toFloatOrNull() ?: 0f
    private val seedMinutes get() = Prefs.raw.getString("torrent_seed_minutes", "0")?.toLongOrNull() ?: 0L

    // Priorities are looked up by their libtorrent number (0 = don't download, 4 = normal).
    private val P_SKIP: Priority by lazy { Priority.values().first { it.swig() == 0 } }
    private val P_NORMAL: Priority by lazy { Priority.values().firstOrNull { it.swig() == 4 } ?: Priority.values().last() }

    // ------------------------------------------------------------- session

    private fun settingsPack(): SettingsPack {
        var down = Prefs.torrentDownKBps * 1024
        var up = Prefs.torrentUpKBps * 1024
        var conns = 200
        if (throttled || Prefs.cpuMode >= 2) {
            // CPU governor / Eco mode: cap rates and connections.
            val cap = if (Prefs.cpuMode >= 3) 256 * 1024 else 1024 * 1024
            down = if (down == 0) cap else minOf(down, cap)
            up = if (up == 0) cap / 4 else minOf(up, cap / 4)
            conns = if (Prefs.cpuMode >= 3) 30 else 60
        }
        return SettingsPack()
            .downloadRateLimit(down)
            .uploadRateLimit(up)
            .connectionsLimit(conns)
            .anonymousMode(true)
    }

    private fun ensureSession(): SessionManager {
        session?.let { return it }
        val s = SessionManager()
        s.addListener(object : AlertListener {
            override fun types(): IntArray = intArrayOf(AlertType.ADD_TORRENT.swig())
            override fun alert(alert: Alert<*>) {
                if (alert is AddTorrentAlert) onAdded(alert.handle())
            }
        })
        s.start(SessionParams(settingsPack()))
        session = s
        sessionHeld = false
        if (!pollStarted) {
            pollStarted = true
            exec.scheduleWithFixedDelay({ runCatching { poll() } }, 1, 1, TimeUnit.SECONDS)
        }
        return s
    }

    fun setGovernorThrottle(on: Boolean) {
        if (on == throttled) return
        throttled = on
        applySettings()
    }

    fun applySettings() {
        exec.execute { session?.let { runCatching { it.applySettings(settingsPack()) } } }
    }

    // ------------------------------------------------------------- where files go

    /** Folders torrents can be saved to: the public Downloads folder plus each storage's app folder (e.g. an SD card). */
    fun saveLocations(ctx: Context): List<Pair<String, String>> {
        val out = mutableListOf("public" to "Download/DeepStellar/Torrents")
        ctx.getExternalFilesDirs(null).filterNotNull().forEachIndexed { i, dir ->
            val label = if (i == 0) "App folder on internal storage" else "SD card / storage ${i + 1} (app folder)"
            out += File(dir, "Torrents").absolutePath to label
        }
        return out
    }

    private fun saveRoot(): File {
        val chosen = Prefs.raw.getString("torrent_dir", "public") ?: "public"
        if (chosen != "public") {
            val f = File(chosen)
            if (f.exists() || f.mkdirs()) return f
        }
        // Android 11+: apps may create files in the public Download folder directly.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pub = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DeepStellar/Torrents")
            if (pub.exists() || pub.mkdirs()) return pub
        }
        return File(app.getExternalFilesDir(null) ?: app.filesDir, "Torrents").apply { mkdirs() }
    }

    // ------------------------------------------------------------- remembered torrents

    private fun storeDir(): File = File(app.filesDir, "torrents").apply { mkdirs() }
    private fun metaFile(item: Item) = File(storeDir(), "${item.id}.torrent")
    private fun indexFile() = File(storeDir(), "index.json")

    private fun saveIndex() {
        if (!::app.isInitialized) return
        exec.execute {
            runCatching {
                if (!remember) { indexFile().delete(); return@runCatching }
                val arr = JSONArray()
                // Finished torrents are only kept while they're still seeding.
                items.filter { !it.failed && (!it.finished || it.isSeeding) }.forEach { t ->
                    arr.put(
                        JSONObject()
                            .put("id", t.id).put("name", t.name)
                            .put("magnet", if (t.source.startsWith("magnet:", true)) t.source else "")
                            .put("dir", t.saveDir?.absolutePath ?: "")
                            .put("paused", t.paused).put("sequential", t.sequential)
                            .put("finished", t.finished).put("uploaded", t.uploaded)
                            .put("skipped", JSONArray(t.skipped.toList()))
                    )
                }
                val tmp = File(storeDir(), "index.json.tmp")
                tmp.writeText(arr.toString())
                tmp.renameTo(indexFile())
                // Drop metadata files that no longer belong to a remembered torrent.
                val keep = items.map { "${it.id}.torrent" }.toSet()
                storeDir().listFiles()?.filter { it.name.endsWith(".torrent") && it.name !in keep }?.forEach { it.delete() }
            }
        }
    }

    /** Called once when the browser opens: bring back unfinished torrents from last time. */
    fun restore(context: Context) {
        app = context.applicationContext
        if (items.isNotEmpty() || !remember) return
        val saved = runCatching { JSONArray(indexFile().readText()) }.getOrNull() ?: return
        if (saved.length() == 0) return
        for (i in 0 until saved.length()) {
            val o = saved.getJSONObject(i)
            val magnet = o.optString("magnet")
            val item = Item(o.getLong("id"), o.optString("name", "Torrent"), magnet.ifEmpty { "saved" }).apply {
                paused = o.optBoolean("paused")
                sequential = o.optBoolean("sequential")
                uploaded = o.optLong("uploaded")
                val sk = o.optJSONArray("skipped")
                skipped = if (sk == null) emptySet() else (0 until sk.length()).map { sk.getInt(it) }.toSet()
                o.optString("dir").takeIf { it.isNotEmpty() }?.let { saveDir = File(it) }
                status = if (paused) "Paused" else "Checking files…"
            }
            items.add(item)
            val meta = metaFile(item)
            exec.execute {
                try {
                    when {
                        meta.exists() -> startDownload(ensureSession(), item, TorrentInfo.bdecode(meta.readBytes()))
                        magnet.isNotEmpty() && !item.paused -> fetchMagnet(item, magnet)
                        magnet.isNotEmpty() -> item.status = "Paused (metadata not fetched yet)"
                        else -> { item.failed = true; item.status = "Couldn't restore this torrent" }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "restore failed", t)
                    item.failed = true
                    item.status = "Failed: ${t.message}"
                }
            }
        }
        if (items.any { it.isActive }) TransferService.kick(app)
    }

    /** Panic wipe / turning the setting off: forget every remembered torrent. */
    fun forgetAll(context: Context) {
        runCatching { File(context.filesDir, "torrents").deleteRecursively() }
    }

    // ------------------------------------------------------------- adding

    private fun newItem(name: String, source: String): Item {
        val item = Item(ids.incrementAndGet(), name, source)
        item.paused = startPaused
        items.add(0, item)
        TransferService.kick(app)
        return item
    }

    private fun fail(item: Item, what: String, t: Throwable) {
        Log.w(TAG, what, t)
        item.failed = true
        item.status = "Failed: ${t.message}"
    }

    private fun fetchMagnet(item: Item, magnet: String) {
        val s = ensureSession()
        item.status = "Fetching metadata…"
        val tmp = File(app.cacheDir, "magnet").apply { mkdirs() }
        val bytes = s.fetchMagnet(magnet, 90, tmp)
        if (bytes == null) {
            item.failed = true
            item.status = "No peers had the metadata (timed out)"
            return
        }
        begin(s, item, bytes)
    }

    /** Keep the metadata (so the torrent can be resumed after a restart) and start it. */
    private fun begin(s: SessionManager, item: Item, bytes: ByteArray) {
        if (remember) runCatching { metaFile(item).writeBytes(bytes) }
        startDownload(s, item, TorrentInfo.bdecode(bytes))
    }

    fun addMagnet(context: Context, magnet: String): Item {
        app = context.applicationContext
        val dn = Regex("[?&]dn=([^&]+)").find(magnet)?.groupValues?.get(1)
        val item = newItem(dn?.let { android.net.Uri.decode(it) } ?: "Magnet link", magnet)
        saveIndex()
        exec.execute {
            try { fetchMagnet(item, magnet) } catch (t: Throwable) { fail(item, "magnet failed", t) }
        }
        return item
    }

    fun addTorrentFile(context: Context, file: File): Item {
        app = context.applicationContext
        val item = newItem(file.name.removeSuffix(".torrent"), file.absolutePath)
        exec.execute {
            try {
                begin(ensureSession(), item, file.readBytes())
            } catch (t: Throwable) {
                fail(item, "torrent file failed", t)
            } finally {
                file.delete()
            }
        }
        return item
    }

    /** A link to a .torrent file: fetch it (through the browser's proxy settings) and start it. */
    fun addTorrentUrl(context: Context, url: String, userAgent: String): Item {
        app = context.applicationContext
        val name = android.webkit.URLUtil.guessFileName(url, null, "application/x-bittorrent").removeSuffix(".torrent")
        val item = newItem(name, url)
        exec.execute {
            try {
                val conn = dev.deepstellar.browser.NetUtil.open(url, userAgent)
                android.webkit.CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                begin(ensureSession(), item, bytes)
            } catch (t: Throwable) {
                fail(item, "torrent url failed", t)
            }
        }
        return item
    }

    private fun priorities(count: Int, skipped: Set<Int>): Array<Priority> =
        Array(count) { i -> if (i in skipped) P_SKIP else P_NORMAL }

    private fun startDownload(s: SessionManager, item: Item, ti: TorrentInfo) {
        item.name = ti.name()
        item.status = if (item.paused) "Paused" else "Starting…"
        val n = ti.numFiles()
        val fs = ti.files()
        item.files = (0 until n).map { FileEntry(it, fs.filePath(it), fs.fileSize(it)) }
        val dir = item.saveDir ?: saveRoot()
        item.saveDir = dir
        pendingByName[ti.name()] = item
        val pri = if (item.skipped.isEmpty()) null else priorities(n, item.skipped)
        s.download(ti, dir, null, pri, null, torrent_flags_t.from_int(0))
        saveIndex()
    }

    private fun onAdded(th: TorrentHandle) {
        // libtorrent4j 2.x exposes this as getName(), i.e. a Kotlin property.
        val name = runCatching { th.name }.getOrNull() ?: return
        val item = pendingByName.remove(name) ?: items.firstOrNull { it.handle == null && it.name == name } ?: return
        item.handle = th
        runCatching {
            if (item.sequential) th.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            if (item.paused) th.pause()
        }
    }

    // ------------------------------------------------------------- control

    fun pause(item: Item) = exec.execute {
        item.paused = true
        item.handle?.let { runCatching { it.pause() } }
        item.status = "Paused"
        saveIndex()
    }

    fun resume(item: Item) = exec.execute {
        item.paused = false
        item.queued = false
        item.seedDone = false
        val h = item.handle
        if (h != null) {
            runCatching { h.resume() }
        } else if (item.source.startsWith("magnet:", true) && !item.failed) {
            // Restored while paused, before its metadata was ever fetched.
            try { fetchMagnet(item, item.source) } catch (t: Throwable) { fail(item, "magnet failed", t) }
        }
        TransferService.kick(app)
        saveIndex()
    }

    /** [deleteFiles] also removes what was downloaded so far. */
    fun remove(item: Item, deleteFiles: Boolean = false) = exec.execute {
        items.remove(item)
        item.handle?.let { h -> session?.let { s -> runCatching { s.remove(h) } } }
        item.handle = null
        if (::app.isInitialized) runCatching { metaFile(item).delete() }
        if (deleteFiles) {
            val dir = item.saveDir
            if (dir != null) runCatching {
                item.files.forEach { f -> File(dir, f.path).delete() }
                // Remove the torrent's own folder if it is now empty.
                item.files.mapNotNull { File(dir, it.path).parentFile }.distinct()
                    .sortedByDescending { it.absolutePath.length }
                    .forEach { d -> if (d != dir && d.isDirectory && d.list()?.isEmpty() == true) d.delete() }
            }
        }
        saveIndex()
    }

    fun setSequential(item: Item, on: Boolean) = exec.execute {
        item.sequential = on
        item.handle?.let { h ->
            runCatching { if (on) h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) else h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) }
        }
        saveIndex()
    }

    /** Choose which files to download. */
    fun setSkipped(item: Item, skipped: Set<Int>) {
        item.skipped = skipped // right away, so the screen redraws with the new choice
        exec.execute { applySkipped(item, skipped) }
    }

    private fun applySkipped(item: Item, skipped: Set<Int>) {
        item.handle?.let { h -> runCatching { h.prioritizeFiles(priorities(item.files.size, skipped)) } }
        if (item.finished && skipped.size < item.files.size) item.finished = false // newly wanted files
        saveIndex()
    }

    fun trackers(item: Item): List<String> =
        runCatching { item.handle?.trackers()?.map { it.url() } }.getOrNull() ?: emptyList()

    fun addTracker(item: Item, url: String) = exec.execute {
        item.handle?.let { h -> runCatching { h.addTracker(AnnounceEntry(url)) } }
    }

    fun find(id: Long): Item? = items.firstOrNull { it.id == id }

    // ------------------------------------------------------------- status loop

    private fun networkHold(): String? {
        if (!wifiOnly && !vpnOnly) return null
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Waiting for a network"
        if (vpnOnly && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "Waiting for VPN"
        if (wifiOnly && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return "Waiting for Wi-Fi"
        return null
    }

    private fun poll() {
        val s = session ?: return

        // 1) Network rules: pause the whole engine while they aren't met.
        val hold = networkHold()
        holdReason = hold
        if (hold != null && !sessionHeld) { runCatching { s.pause() }; sessionHeld = true }
        if (hold == null && sessionHeld) { runCatching { s.resume() }; sessionHeld = false }

        // 2) Queue: only so many torrents download at once (oldest first).
        val limit = maxActive
        var running = 0
        for (item in items.reversed()) {
            val h = item.handle ?: continue
            if (item.paused || item.failed || item.finished) continue
            if (limit <= 0 || running < limit) {
                running++
                if (item.queued) { runCatching { h.resume() }; item.queued = false }
            } else if (!item.queued) {
                runCatching { h.pause() }
                item.queued = true
            }
        }

        // 3) Progress, finishing, seeding limits.
        var changed = false
        for (item in items) {
            val h = item.handle ?: continue
            runCatching {
                val st = h.status()
                item.progress = st.progress()
                item.downBps = st.downloadPayloadRate().toLong()
                item.upBps = st.uploadPayloadRate().toLong()
                item.peers = st.numPeers()
                item.wanted = st.totalWanted()
                item.done = st.totalWantedDone()
                item.uploaded += item.upBps
                if (item.watchFiles) item.fileProgress = h.fileProgress()

                if (!item.finished && st.isFinished) {
                    item.finished = true
                    changed = true
                    if (!Prefs.torrentSeed) h.pause()
                }
                if (item.isSeeding && !item.paused) {
                    item.seedSeconds++
                    val ratio = if (item.done > 0) item.uploaded.toFloat() / item.done else 0f
                    val ratioHit = seedRatio > 0f && ratio >= seedRatio
                    val timeHit = seedMinutes > 0 && item.seedSeconds >= seedMinutes * 60
                    if (ratioHit || timeHit) {
                        item.seedDone = true
                        changed = true
                        h.pause()
                    }
                }
                item.status = when {
                    item.paused -> "Paused"
                    hold != null -> hold
                    item.queued -> "Queued"
                    item.finished && item.seedDone -> "Complete · seeding limit reached"
                    item.isSeeding -> "Seeding"
                    item.finished -> "Complete"
                    else -> st.state().toString().lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
                }
            }
        }
        if (changed) saveIndex()
    }

    /** Stops the engine entirely (called when the app exits with nothing running). */
    fun shutdown() {
        exec.execute {
            runCatching { session?.stop() }
            session = null
        }
    }
}
