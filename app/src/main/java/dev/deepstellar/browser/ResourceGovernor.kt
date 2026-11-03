package dev.deepstellar.browser

import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import dev.deepstellar.browser.torrent.TorrentEngine
import java.io.File

/**
 * CPU & RAM limiter.
 *
 * Android doesn't let an app hard-cap its own CPU or memory, so the governor
 * enforces the limits the way desktop browsers do:
 *  - RAM: keeps an estimate of browser memory and, when it goes over your budget
 *    (or the system is low), suspends least-recently-used background tabs.
 *  - CPU: the mode caps how many background tabs stay alive, throttles animation
 *    and timers on pages (Eco/Ultra), and slows the torrent engine when the app's
 *    own CPU use goes over the mode's ceiling.
 *
 * Note: web pages render in a separate sandboxed process whose usage Android
 * won't report to the app, so page memory is estimated per live tab.
 */
object ResourceGovernor {

    data class Snapshot(
        val cpuPercent: Int,
        val appPssMb: Int,
        val estimatedMb: Int,
        val budgetMb: Int,
        val liveTabs: Int,
        val totalTabs: Int,
        val suspendedByGovernor: Int,
        val torrentThrottled: Boolean,
    )

    /** Rough cost of one live page in the renderer process. */
    private const val PER_LIVE_TAB_MB = 70

    private var tabs: TabManager? = null
    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("governor").apply { start() }
    private val bg = Handler(worker.looper)

    private val clkTck: Long = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1)
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private var lastTicks = -1L
    private var lastWall = 0L

    @Volatile var last: Snapshot? = null
        private set
    private var suspendedCount = 0
    private var hotTicks = 0
    var listener: ((Snapshot) -> Unit)? = null

    fun attach(tm: TabManager) {
        tabs = tm
        bg.removeCallbacksAndMessages(null)
        bg.post(sample)
    }

    fun detach() {
        tabs = null
        bg.removeCallbacksAndMessages(null)
    }

    /** Background tabs allowed to stay alive in each CPU mode. */
    private fun maxLiveBackground(): Int = when (Prefs.cpuMode) {
        0 -> Int.MAX_VALUE
        1 -> 6
        2 -> 2
        else -> 0
    }

    /** App-process CPU ceiling per mode, percent of all cores. */
    private fun cpuCeiling(): Int = when (Prefs.cpuMode) {
        0 -> 100
        1 -> 60
        2 -> 35
        else -> 20
    }

    private val sample = object : Runnable {
        override fun run() {
            val cpu = readCpuPercent()
            val pssMb = (Debug.getPss() / 1024L).toInt()
            main.post { enforce(cpu, pssMb) }
            bg.postDelayed(this, if (Prefs.cpuMode >= 2) 4000L else 2500L)
        }
    }

    private fun enforce(cpu: Int, pssMb: Int) {
        val tm = tabs ?: return

        // 1) Live background tab cap from CPU mode.
        val bgLive = tm.liveBackgroundLru().toMutableList()
        val cap = maxLiveBackground()
        while (bgLive.size > cap) {
            tm.suspend(bgLive.removeAt(0)); suspendedCount++
        }

        // 2) RAM budget.
        val budget = Prefs.ramBudgetMb
        var estimate = pssMb + tm.liveCount() * PER_LIVE_TAB_MB
        if (budget != Int.MAX_VALUE) {
            while (estimate > budget && bgLive.isNotEmpty()) {
                tm.suspend(bgLive.removeAt(0)); suspendedCount++
                estimate -= PER_LIVE_TAB_MB
            }
        }

        // 3) CPU ceiling → throttle torrents, then shed background tabs.
        val ceiling = cpuCeiling()
        if (cpu > ceiling) hotTicks++ else hotTicks = (hotTicks - 1).coerceAtLeast(0)
        val throttle = hotTicks >= 2
        TorrentEngine.setGovernorThrottle(throttle)
        if (hotTicks >= 3 && bgLive.isNotEmpty()) {
            tm.suspend(bgLive.removeAt(0)); suspendedCount++
        }

        val snap = Snapshot(cpu, pssMb, estimate, budget, tm.liveCount(), tm.count, suspendedCount, throttle)
        last = snap
        listener?.invoke(snap)
    }

    /** onTrimMemory from the system: drop every background tab right away. */
    fun onSystemMemoryPressure() {
        main.post {
            val tm = tabs ?: return@post
            tm.liveBackgroundLru().forEach { tm.suspend(it); suspendedCount++ }
        }
    }

    private fun readCpuPercent(): Int {
        val ticks = runCatching {
            val f = File("/proc/self/stat").readText()
            // Skip "pid (comm)" — comm can contain spaces.
            val rest = f.substring(f.lastIndexOf(')') + 2).split(' ')
            // After the ')' field 3 is index 0, so utime(14) = 11, stime(15) = 12.
            rest[11].toLong() + rest[12].toLong()
        }.getOrDefault(-1L)
        val now = System.nanoTime()
        if (ticks < 0) return 0
        val pct = if (lastTicks >= 0) {
            val cpuSec = (ticks - lastTicks).toDouble() / clkTck
            val wallSec = (now - lastWall) / 1e9
            if (wallSec > 0) (cpuSec / wallSec / cores * 100).toInt().coerceIn(0, 100) else 0
        } else 0
        lastTicks = ticks
        lastWall = now
        return pct
    }
}
