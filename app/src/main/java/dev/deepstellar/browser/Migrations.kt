package dev.deepstellar.browser

import android.content.SharedPreferences
import android.util.Log

/**
 * Settings upgrades between versions.
 *
 * Every saved format (settings, site settings, shortcuts, the Projects page state…) can change
 * over time. Instead of each feature guessing what an old install looks like, each change gets
 * a numbered step here. Steps run once, in order, at startup — also after restoring a backup
 * made by an older version.
 *
 * To change a stored format: add a step with the next number and bump [CURRENT].
 */
object Migrations {
    const val CURRENT = 2
    private const val KEY = "settings_version"
    private const val TAG = "Migrations"

    fun run(sp: SharedPreferences) {
        val from = sp.getInt(KEY, 0)
        if (from >= CURRENT) return
        for (v in (from + 1)..CURRENT) {
            try {
                step(v, sp)
            } catch (e: Exception) {
                // A failed step must never stop the browser from opening.
                Log.w(TAG, "Step $v failed: ${e.javaClass.simpleName}")
            }
        }
        sp.edit().putInt(KEY, CURRENT).apply()
    }

    private fun step(v: Int, sp: SharedPreferences) {
        val ed = sp.edit()
        when (v) {
            // 1: "Keep me logged in" was removed.
            1 -> ed.remove("keep_sites")
            // 2: the tab, RAM and CPU sliders store positions; clamp anything out of range
            //    (an older build or a hand-edited backup could hold a bad value).
            2 -> {
                fun clamp(key: String, max: Int, def: Int) {
                    if (sp.contains(key)) {
                        val value = runCatching { sp.getInt(key, def) }.getOrDefault(def)
                        if (value !in 0..max) ed.putInt(key, def)
                    }
                }
                clamp("tab_limit_index", Prefs.TAB_STEPS.size - 1, 3)
                clamp("ram_limit_index", Prefs.RAM_STEPS_MB.size - 1, 3)
                clamp("cpu_mode", 3, 1)
            }
        }
        ed.apply()
    }
}
