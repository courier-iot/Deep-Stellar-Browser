package dev.deepstellar.browser.torrent

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import dev.deepstellar.browser.Prefs
import dev.deepstellar.browser.downloads.TransferService
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Built-in BitTorrent client (libtorrent via libtorrent4j).
 * Handles magnet links and .torrent files. The torrent list is memory-only.
 *
 * Note: torrent traffic does NOT go through Tor/Orbot even when the browser does.
 */
object TorrentEngine {
    private const val TAG = "TorrentEngine"

    class Item(val id: Long, var name: String, val source: String) {
        @Volatile var handle: TorrentHandle? = null
        @Volatile var status: String = "Fetching metadata…"
        @Volatile var progress: Float = 0f
        @Volatile var downBps: Long = 0
        @Volatile var upBps: Long = 0
        @Volatile var peers: Int = 0
        @Volatile var wanted: Long = 0
        @Volatile var done: Long = 0
        @Volatile var paused: Boolean = false
        @Volatile var finished: Boolean = false
        @Volatile var failed: Boolean = false
        var saveDir: File? = null
        val isActive get() = !failed && !paused && (!finished || Prefs.torrentSeed)
    }

    val items = CopyOnWriteArrayList<Item>()
    private val ids = AtomicLong(1)
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private var session: SessionManager? = null
    @Volatile private var throttled = false
    private lateinit var app: Context

    val activeCount: Int get() = items.count { it.isActive }

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
        exec.scheduleWithFixedDelay({ poll() }, 1, 1, TimeUnit.SECONDS)
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

    // ------------------------------------------------------------- adding

    private fun saveRoot(): File {
        // Android 11+: apps may create files in the public Download folder directly.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pub = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DeepStellar/Torrents")
            if (pub.exists() || pub.mkdirs()) return pub
        }
        return File(app.getExternalFilesDir(null) ?: app.filesDir, "Torrents").apply { mkdirs() }
    }

    fun addMagnet(context: Context, magnet: String): Item {
        app = context.applicationContext
        val dn = Regex("[?&]dn=([^&]+)").find(magnet)?.groupValues?.get(1)
        val item = Item(ids.getAndIncrement(), dn?.let { android.net.Uri.decode(it) } ?: "Magnet link", magnet)
        items.add(0, item)
        TransferService.kick(app)
        exec.execute {
            try {
                val s = ensureSession()
                val tmp = File(app.cacheDir, "magnet").apply { mkdirs() }
                val bytes = s.fetchMagnet(magnet, 90, tmp)
                if (bytes == null) {
                    item.failed = true
                    item.status = "No peers had the metadata (timed out)"
                    return@execute
                }
                startDownload(s, item, TorrentInfo.bdecode(bytes))
            } catch (t: Throwable) {
                Log.w(TAG, "magnet failed", t)
                item.failed = true
                item.status = "Failed: ${t.message}"
            }
        }
        return item
    }

    fun addTorrentFile(context: Context, file: File): Item {
        app = context.applicationContext
        val item = Item(ids.getAndIncrement(), file.name.removeSuffix(".torrent"), file.absolutePath)
        items.add(0, item)
        TransferService.kick(app)
        exec.execute {
            try {
                val s = ensureSession()
                startDownload(s, item, TorrentInfo(file))
            } catch (t: Throwable) {
                Log.w(TAG, "torrent file failed", t)
                item.failed = true
                item.status = "Failed: ${t.message}"
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
        val item = Item(ids.getAndIncrement(), name, url)
        items.add(0, item)
        TransferService.kick(app)
        exec.execute {
            try {
                val conn = dev.deepstellar.browser.NetUtil.open(url, userAgent)
                android.webkit.CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                val s = ensureSession()
                startDownload(s, item, TorrentInfo.bdecode(bytes))
            } catch (t: Throwable) {
                Log.w(TAG, "torrent url failed", t)
                item.failed = true
                item.status = "Failed: ${t.message}"
            }
        }
        return item
    }

    private fun startDownload(s: SessionManager, item: Item, ti: TorrentInfo) {
        item.name = ti.name()
        item.status = "Starting…"
        val dir = saveRoot()
        item.saveDir = dir
        pendingByName[ti.name()] = item
        s.download(ti, dir, null, null, null, torrent_flags_t.from_int(0))
    }

    private val pendingByName = java.util.concurrent.ConcurrentHashMap<String, Item>()

    private fun onAdded(th: TorrentHandle) {
        val name = runCatching { th.name() }.getOrNull() ?: return
        val item = pendingByName.remove(name) ?: items.firstOrNull { it.handle == null && it.name == name } ?: return
        item.handle = th
    }

    // ------------------------------------------------------------- control

    fun pause(item: Item) = exec.execute {
        item.paused = true
        item.handle?.let { runCatching { it.pause() } }
        item.status = "Paused"
    }

    fun resume(item: Item) = exec.execute {
        item.paused = false
        item.handle?.let { runCatching { it.resume() } }
        TransferService.kick(app)
    }

    fun remove(item: Item) = exec.execute {
        items.remove(item)
        item.handle?.let { h -> session?.let { s -> runCatching { s.remove(h) } } }
        item.handle = null
    }

    // ------------------------------------------------------------- status

    private fun poll() {
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
                if (!item.finished && st.isFinished) {
                    item.finished = true
                    if (!Prefs.torrentSeed) {
                        h.pause()
                    }
                }
                item.status = when {
                    item.paused -> "Paused"
                    item.finished && Prefs.torrentSeed -> "Seeding"
                    item.finished -> "Complete"
                    else -> st.state().toString().lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
                }
            }
        }
    }

    /** Stops the engine entirely (called when the app exits). */
    fun shutdown() {
        exec.execute {
            runCatching { session?.stop() }
            session = null
        }
    }
}
