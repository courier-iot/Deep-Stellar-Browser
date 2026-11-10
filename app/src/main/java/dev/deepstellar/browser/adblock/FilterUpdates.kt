package dev.deepstellar.browser.adblock

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.deepstellar.browser.Prefs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Automatic filter-list updates: off, weekly, every 2 weeks, or monthly.
 * Off by default. When on, the download goes through the same Tor/Orbot
 * setting as the browser (via NetUtil) and only fetches the two public lists —
 * nothing about you is sent.
 */
object FilterUpdates {
    private const val WORK = "filter_updates"

    /** Days between updates; 0 = off. */
    val intervalDays: Long get() = Prefs.raw.getString("filter_auto", "0")?.toLongOrNull() ?: 0L
    private val wifiOnly get() = Prefs.raw.getBoolean("filter_wifi_only", true)
    val lastUpdate: Long get() = Prefs.raw.getLong("filter_last_update", 0L)
    val lastError: String? get() = Prefs.raw.getString("filter_last_error", null)

    /** At app start: make sure a schedule exists (e.g. after a backup restore) without resetting its timer. */
    fun ensureScheduled(ctx: Context) {
        if (intervalDays > 0) schedule(ctx, ExistingPeriodicWorkPolicy.KEEP)
    }

    fun schedule(ctx: Context, policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE) {
        val wm = WorkManager.getInstance(ctx)
        val days = intervalDays
        if (days <= 0) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val req = PeriodicWorkRequestBuilder<FilterUpdateWorker>(days, TimeUnit.DAYS)
            .setConstraints(constraints)
            // First run when the current lists are due (right away if they've never been updated).
            .setInitialDelay(firstDelayMs(days), TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(WORK, policy, req)
    }

    private fun firstDelayMs(days: Long): Long {
        val last = lastUpdate
        if (last == 0L) return 0L
        return (days * 86_400_000L - (System.currentTimeMillis() - last)).coerceAtLeast(0L)
    }

    /** Records the outcome of any update, manual or scheduled. */
    fun record(ok: Boolean) {
        val ed = Prefs.raw.edit()
        if (ok) ed.putLong("filter_last_update", System.currentTimeMillis()).remove("filter_last_error")
        else ed.putString("filter_last_error", "Couldn't reach the list servers" + if (Prefs.torProxy) " (is Orbot running?)" else "")
        ed.apply()
    }
}

class FilterUpdateWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        if (!Prefs.adblock) return Result.success() // shield is off; nothing to update
        val latch = CountDownLatch(1)
        var ok = false
        AdBlocker.updateLists { success, _ ->
            ok = success
            latch.countDown()
        }
        if (!latch.await(10, TimeUnit.MINUTES)) return Result.retry()
        FilterUpdates.record(ok)
        return if (ok) Result.success() else if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
