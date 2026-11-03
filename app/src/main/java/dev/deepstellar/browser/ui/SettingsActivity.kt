package dev.deepstellar.browser.ui

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.deepstellar.browser.BuildConfig
import dev.deepstellar.browser.Prefs
import dev.deepstellar.browser.PrivacyWiper
import dev.deepstellar.browser.R
import dev.deepstellar.browser.adblock.AdBlocker
import dev.deepstellar.browser.history.HistoryStore
import dev.deepstellar.browser.torrent.TorrentEngine

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = FrameLayout(this).apply { id = View.generateViewId() }
        scaffold(getString(R.string.settings), container)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(container.id, SettingsFragment()).commit()
        }
    }

    override fun onPause() {
        super.onPause()
        TorrentEngine.applySettings()
    }
}

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        slider("tab_limit_index") { i ->
            "${Prefs.tabLabel(i)} — opening more is blocked"
        }
        slider("cpu_mode") { i ->
            Prefs.CPU_LABELS[i.coerceIn(0, 3)] + " — " + when (i) {
                0 -> "no throttling"
                1 -> "up to 6 background tabs stay awake"
                2 -> "2 background tabs awake, pages capped at 30 fps, torrents ≤ 1 MB/s"
                else -> "background tabs sleep, pages capped at 15 fps, torrents ≤ 256 KB/s, still start page"
            }
        }
        slider("ram_limit_index") { i ->
            "${Prefs.ramLabel(i)} — background tabs sleep when the estimate goes over"
        }

        click("update_lists") { p ->
            p.summary = "Downloading…"
            AdBlocker.updateLists { ok, count ->
                activity?.runOnUiThread {
                    p.summary = if (ok) "Updated · $count domains blocked" else "Couldn't reach the list servers — try again"
                }
            }
        }
        findPreference<Preference>("update_lists")?.summary =
            "${AdBlocker.listSize} domains · tap to download StevenBlack + AdGuard lists"

        click("reset_learning") {
            confirm("Reset learning?", "The shield forgets everything it learned and starts over.") {
                AdBlocker.resetLearning(); toast("Learning reset")
            }
        }
        findPreference<Preference>("reset_learning")?.summary =
            "${AdBlocker.trainedExamples} examples learned" + if (AdBlocker.learnerWarm) " · active" else " · warming up"

        findPreference<androidx.preference.SwitchPreferenceCompat>("history_enabled")?.setOnPreferenceChangeListener { _, v ->
            if (v == true) {
                HistoryStore.enable()
                true
            } else {
                confirm("Turn off history?", "The encrypted history file is deleted and its key destroyed. This can't be undone.") {
                    findPreference<androidx.preference.SwitchPreferenceCompat>("history_enabled")?.isChecked = false
                    HistoryStore.disableAndDestroy()
                    toast("History erased")
                }
                false // only switch off after you confirm
            }
        }
        click("history_open") { startActivity(android.content.Intent(requireContext(), HistoryActivity::class.java)) }

        click("clear_user_blocks") { AdBlocker.clearUserList(); toast("Your blocked domains were cleared") }
        click("clear_allowed") { Prefs.allowedSites = emptySet(); toast("Shield is back on everywhere") }
        click("wipe_now") {
            PrivacyWiper.wipeWebData(requireContext())
            toast("Site data wiped")
        }
        findPreference<Preference>("about")?.summary =
            "Version ${BuildConfig.VERSION_NAME} · free & open source · no telemetry, no history"
    }

    private fun slider(key: String, label: (Int) -> String) {
        val p = findPreference<SeekBarPreference>(key) ?: return
        p.summary = label(p.value)
        p.setOnPreferenceChangeListener { pref, v ->
            pref.summary = label(v as Int)
            true
        }
    }

    private fun click(key: String, action: (Preference) -> Unit) {
        findPreference<Preference>(key)?.setOnPreferenceClickListener { action(it); true }
    }

    private fun confirm(title: String, msg: String, yes: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title).setMessage(msg)
            .setPositiveButton("OK") { _, _ -> yes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()
}
