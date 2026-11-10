package dev.deepstellar.browser.ui

import android.app.Activity
import dev.deepstellar.browser.Prefs
import dev.deepstellar.browser.R

/**
 * Four deep-space looks. Every screen calls [apply] before super.onCreate().
 *  - nebula     violet & blue (the original)
 *  - horizon    Event Horizon: pure black for OLED, accretion-disk orange
 *  - supernova  warm coral & gold
 *  - aurora     green & teal
 */
object Themes {
    val KEYS = listOf("nebula", "horizon", "supernova", "aurora")

    val current: String
        get() = Prefs.raw.getString("theme", "nebula")?.takeIf { it in KEYS } ?: "nebula"

    fun styleFor(key: String): Int = when (key) {
        "horizon" -> R.style.Theme_DeepStellar_Horizon
        "supernova" -> R.style.Theme_DeepStellar_Supernova
        "aurora" -> R.style.Theme_DeepStellar_Aurora
        else -> R.style.Theme_DeepStellar
    }

    /** Returns the key applied, so screens can tell later whether it changed. */
    fun apply(activity: Activity): String {
        val key = current
        activity.setTheme(styleFor(key))
        return key
    }
}
