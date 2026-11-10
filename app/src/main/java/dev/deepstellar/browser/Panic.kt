package dev.deepstellar.browser

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.webkit.CookieManager
import android.webkit.WebStorage
import dev.deepstellar.browser.downloads.DownloadEngine
import dev.deepstellar.browser.downloads.TransferService
import dev.deepstellar.browser.history.HistoryStore
import dev.deepstellar.browser.torrent.TorrentEngine

/**
 * Panic wipe: stops every download and torrent, erases cookies, cache and site
 * storage (and history, if you chose that), removes the browser from Recents and
 * ends the app process so nothing stays in memory.
 *
 * It runs without opening the browser, so it works from the lock screen and
 * skips the app lock. Files already saved to Downloads are not deleted.
 */
object Panic {

    val alsoHistory: Boolean get() = Prefs.raw.getBoolean("panic_history", false)

    fun trigger(ctx: Context) {
        val app = ctx.applicationContext
        // 1) Transfers
        runCatching { DownloadEngine.items.filter { it.isActive }.forEach { DownloadEngine.cancel(it) } }
        runCatching { DownloadEngine.wipePartials(app) }
        runCatching { TorrentEngine.items.toList().forEach { TorrentEngine.remove(it) } }
        runCatching { TorrentEngine.shutdown() }
        runCatching { app.stopService(Intent(app, TransferService::class.java)) }

        // 2) History (optional)
        if (alsoHistory) runCatching { HistoryStore.clearNow() }

        // 3) Web data
        runCatching {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.removeSessionCookies(null)
            cm.flush()
        }
        runCatching { WebStorage.getInstance().deleteAllData() }
        runCatching { PrivacyWiper.wipeDiskCaches(app, includeEngineProfile = false) }

        // 4) Out of Recents
        runCatching { app.getSystemService(ActivityManager::class.java)?.appTasks?.forEach { it.finishAndRemoveTask() } }

        // 5) Delete the engine's storage folders and end the process. The next launch wipes again.
        val h = Handler(Looper.getMainLooper())
        h.postDelayed({ runCatching { PrivacyWiper.wipeDiskCaches(app, includeEngineProfile = true) } }, 500)
        h.postDelayed({ Process.killProcess(Process.myPid()) }, 900)
    }
}

/** Quick Settings tile. Add it from the Quick Settings edit screen (or Settings → Panic wipe). */
class PanicTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Panic wipe"
            updateTile()
        }
    }

    override fun onClick() {
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            updateTile()
        }
        Panic.trigger(this)
    }
}
