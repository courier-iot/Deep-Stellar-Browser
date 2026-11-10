package dev.deepstellar.browser

import android.content.Context
import android.os.SystemClock
import java.io.File

/**
 * Crash-loop protection.
 *
 * If the browser crashes within [EARLY_MS] of starting, three times in a row, the next
 * start is in safe mode: the parts most likely to be at fault (downloaded filter lists,
 * advanced rules, page privacy scripts, restoring transfers) are skipped, and the browser
 * offers to reset them. Staying open for [STABLE_MS] clears the count.
 *
 * The count is a tiny file, not a setting, so a broken settings file can't hide it.
 */
object SafeMode {
    private const val EARLY_MS = 20_000L
    private const val STABLE_MS = 10_000L
    private const val THRESHOLD = 3

    private lateinit var counter: File

    /** True for this whole run once safe mode was entered. */
    @Volatile var active = false
        private set

    var crashesBefore = 0
        private set

    fun init(context: Context) {
        counter = File(context.filesDir, "early_crashes")
        crashesBefore = read()
        active = crashesBefore >= THRESHOLD
    }

    private fun read(): Int = runCatching { counter.readText().trim().toInt() }.getOrDefault(0)

    /** Called from the crash handler. */
    fun onCrash() {
        if (!::counter.isInitialized) return
        if (SystemClock.uptimeMillis() - startedAt > EARLY_MS) return
        runCatching { counter.writeText((read() + 1).toString()) }
    }

    private val startedAt = SystemClock.uptimeMillis()

    /** Called by the browser screen once it has been open a while without crashing. */
    fun markStable() {
        if (!::counter.isInitialized) return
        runCatching { counter.delete() }
    }

    const val STABLE_AFTER_MS = STABLE_MS

    /** Leave safe mode for the next start (after the user reset something or chose to continue). */
    fun exit() {
        markStable()
    }
}
