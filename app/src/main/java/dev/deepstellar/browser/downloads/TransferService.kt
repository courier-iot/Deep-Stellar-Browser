package dev.deepstellar.browser.downloads

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.deepstellar.browser.DeepStellarApp
import dev.deepstellar.browser.R
import dev.deepstellar.browser.torrent.TorrentEngine
import dev.deepstellar.browser.ui.Format
import dev.deepstellar.browser.ui.TransfersActivity

/**
 * Keeps downloads and torrents running while the app is in the background.
 * Starts itself when work begins and stops itself when everything is idle.
 */
class TransferService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var idleTicks = 0

    private val tick = object : Runnable {
        override fun run() {
            val dl = DownloadEngine.activeCount
            val tr = TorrentEngine.activeCount
            if (dl == 0 && tr == 0) {
                if (++idleTicks >= 3) {
                    ServiceCompat.stopForeground(this@TransferService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return
                }
            } else idleTicks = 0
            post(dl, tr)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        post(DownloadEngine.activeCount, TorrentEngine.activeCount, start = true)
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_NOT_STICKY
    }

    private fun post(dl: Int, tr: Int, start: Boolean = false) {
        val speed = DownloadEngine.items.sumOf { it.speedBps } + TorrentEngine.items.sumOf { it.downBps }
        val text = buildList {
            if (dl > 0) add("$dl download${if (dl == 1) "" else "s"}")
            if (tr > 0) add("$tr torrent${if (tr == 1) "" else "s"}")
        }.joinToString(" · ").ifEmpty { "Finishing up" } + " · " + Format.speed(speed)

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, TransfersActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, DeepStellarApp.CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Deep Stellar transfers")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setColor(ContextCompat.getColor(this, R.color.nebula_violet))
            .build()

        if (start) {
            ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            runCatching { getSystemService(android.app.NotificationManager::class.java)?.notify(NOTIF_ID, n) }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIF_ID = 42

        fun kick(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
            }
        }
    }
}
