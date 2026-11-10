package dev.deepstellar.browser

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import dev.deepstellar.browser.adblock.AdBlocker
import dev.deepstellar.browser.bookmarks.BookmarkStore
import dev.deepstellar.browser.downloads.DownloadEngine
import dev.deepstellar.browser.history.HistoryStore

class DeepStellarApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        CrashLog.install(this)
        // Wipe before the WebView engine starts, in case the last session crashed.
        PrivacyWiper.wipeDiskCaches(this)
        DownloadEngine.wipePartials(this) // orphaned half-downloads from a previous run
        BookmarkStore.init(this)
        HistoryStore.init(this) // only decrypts if you've turned history on
        AdBlocker.init(this)
        runCatching { dev.deepstellar.browser.adblock.FilterUpdates.ensureScheduled(this) }
        AppLock.install(this)

        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_TRANSFERS, "Downloads & torrents", NotificationManager.IMPORTANCE_LOW)
        )
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            ResourceGovernor.onSystemMemoryPressure()
        }
    }

    companion object {
        const val CHANNEL_TRANSFERS = "transfers"
        lateinit var instance: DeepStellarApp
            private set
    }
}
