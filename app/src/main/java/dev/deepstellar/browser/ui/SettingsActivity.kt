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
import android.content.ComponentName
import android.content.Intent
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import dev.deepstellar.browser.MainActivity
import dev.deepstellar.browser.adblock.AdBlocker
import dev.deepstellar.browser.backup.AutoBackup
import dev.deepstellar.browser.AppLock
import dev.deepstellar.browser.Bangs
import dev.deepstellar.browser.CrashLog
import dev.deepstellar.browser.SitePrefs
import dev.deepstellar.browser.downloads.DownloadEngine
import dev.deepstellar.browser.torrent.TorrentEngine
import androidx.core.content.ContextCompat
import dev.deepstellar.browser.adblock.FilterUpdates
import dev.deepstellar.browser.backup.BackupManager
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.deepstellar.browser.history.HistoryStore

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
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

    // ---- encrypted backup state (memory only, wiped right after use)
    private var pendingPassword: CharArray? = null
    private var pendingHistory = false
    private var pendingModel = true
    private var pendingRestoreBytes: ByteArray? = null

    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pw = pendingPassword
        pendingPassword = null
        if (uri == null || pw == null) { pw?.fill(' '); return@registerForActivityResult }
        val ctx = requireContext().applicationContext
        toast("Encrypting…")
        Thread {
            val result = runCatching {
                val bytes = BackupManager.export(ctx, pw, pendingHistory, pendingModel)
                ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                bytes.size
            }
            pw.fill(' ')
            activity?.runOnUiThread {
                result.fold(
                    { n -> toast("Backup saved (${Format.bytes(n.toLong())}). Keep the password safe — it can't be recovered.") },
                    { e -> toast("Backup failed: ${e.message}") },
                )
            }
        }.start()
    }

    private var pendingAutoPassword: CharArray? = null

    private val pickAutoFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val pw = pendingAutoPassword
        pendingAutoPassword = null
        if (uri == null) { pw?.fill(' '); return@registerForActivityResult }
        val ctx = requireContext().applicationContext
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        if (pw == null) {
            // Just changing the folder.
            AutoBackup.changeFolder(ctx, uri)
            refreshAutoBackup()
            toast("Backup folder changed")
            return@registerForActivityResult
        }
        toast("Setting up…")
        Thread {
            val r = runCatching { AutoBackup.enable(ctx, pw, uri) }
            pw.fill(' ')
            activity?.runOnUiThread {
                r.fold({
                    findPreference<androidx.preference.SwitchPreferenceCompat>("auto_backup_enabled")?.isChecked = true
                    AutoBackup.runNow(ctx) // first backup right away
                    refreshAutoBackup()
                    toast("Automatic backups are on — making the first one now")
                }, { e -> toast("Couldn't set up: ${e.message}") })
            }
        }.start()
    }

    private val pickBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        pendingRestoreBytes = runCatching {
            requireContext().contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        }.getOrNull()
        if (pendingRestoreBytes == null) toast("Couldn't read that file") else askRestorePassword()
    }

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
                FilterUpdates.record(ok)
                activity?.runOnUiThread {
                    p.summary = if (ok) "Updated just now · $count domains blocked" else "Couldn't reach the list servers — try again"
                }
            }
        }
        findPreference<Preference>("update_lists")?.summary = filterSummary()
        listOf("filter_auto", "filter_wifi_only").forEach { key ->
            findPreference<Preference>(key)?.setOnPreferenceChangeListener { _, _ ->
                view?.post { FilterUpdates.schedule(requireContext().applicationContext) }
                true
            }
        }

        // ---- app lock
        findPreference<androidx.preference.SwitchPreferenceCompat>("app_lock")?.setOnPreferenceChangeListener { _, v ->
            if (v == true && !AppLock.canUse(requireContext())) {
                toast("Set a screen lock (PIN, pattern or fingerprint) on your phone first")
                false
            } else {
                if (v == true) {
                    AppLock.onEnabled()
                    toast("Locked next time you open Deep Stellar")
                }
                view?.post { activity?.recreate() } // refresh the Recents-preview setting
                true
            }
        }

        // ---- projects page
        findPreference<Preference>("projects_check")?.setOnPreferenceChangeListener { _, v ->
            view?.post { dev.deepstellar.browser.projects.Projects.setCheckEnabled(requireContext().applicationContext, v == true) }
            true
        }

        // ---- search shortcuts
        click("bangs_manage") { manageBangs() }

        // ---- per-site settings
        click("site_settings") { manageSites() }

        // ---- advanced filter rules
        findPreference<Preference>("adblock_rules")?.setOnPreferenceChangeListener { _, v ->
            view?.post {
                AdBlocker.reload()
                if (v == true && AdBlocker.ruleCount == 0) toast("Tap Update filter lists to download the rules")
            }
            true
        }

        // ---- downloads & torrents
        findPreference<Preference>("transfers_remember")?.setOnPreferenceChangeListener { _, v ->
            if (v == false) {
                val ctx = requireContext().applicationContext
                DownloadEngine.forgetAll(ctx)
                TorrentEngine.forgetAll(ctx)
            }
            true
        }
        val dirPref = findPreference<Preference>("torrent_dir_pick")
        fun dirSummary() {
            val cur = Prefs.raw.getString("torrent_dir", "public")
            dirPref?.summary = TorrentEngine.saveLocations(requireContext()).firstOrNull { it.first == cur }?.second ?: "Download/DeepStellar/Torrents"
        }
        dirSummary()
        click("torrent_dir_pick") {
            val options = TorrentEngine.saveLocations(requireContext())
            val cur = Prefs.raw.getString("torrent_dir", "public")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Save torrents to")
                .setSingleChoiceItems(options.map { it.second }.toTypedArray(), options.indexOfFirst { it.first == cur }.coerceAtLeast(0)) { d, i ->
                    Prefs.raw.edit().putString("torrent_dir", options[i].first).apply()
                    dirSummary()
                    d.dismiss()
                    if (i > 0) toast("Files in an app folder are deleted if you uninstall Deep Stellar")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // ---- start page
        click("manage_shortcuts") { p ->
            ShortcutDialogs.manage(requireActivity()) { p.summary = shortcutsSummary() }
        }
        findPreference<Preference>("manage_shortcuts")?.summary = shortcutsSummary()

        // ---- panic tile
        click("panic_tile") { addPanicTile() }

        // ---- crash reports
        findPreference<androidx.preference.SwitchPreferenceCompat>("crash_log")?.setOnPreferenceChangeListener { _, v ->
            if (v == false) CrashLog.clear()
            view?.post { refreshCrashes() }
            true
        }
        click("crash_reports") { showCrashes() }
        refreshCrashes()

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
        findPreference<androidx.preference.ListPreference>("theme")?.setOnPreferenceChangeListener { _, _ ->
            // Repaint this screen right away; the browser repaints when you go back to it.
            view?.post { activity?.recreate() }
            true
        }
        click("backup_create") { askBackupOptions() }

        // ---- automatic backups
        findPreference<androidx.preference.SwitchPreferenceCompat>("auto_backup_enabled")?.setOnPreferenceChangeListener { _, v ->
            if (v == true) {
                askAutoBackupPassword()
            } else {
                confirm("Turn off automatic backups?",
                    "The stored backup key is destroyed. Backup files already in your folder are kept and still open with your password.") {
                    AutoBackup.disable(requireContext().applicationContext)
                    findPreference<androidx.preference.SwitchPreferenceCompat>("auto_backup_enabled")?.isChecked = false
                    refreshAutoBackup()
                }
            }
            false // the switch flips only after setup / confirmation
        }
        click("auto_backup_status") {
            AutoBackup.runNow(requireContext().applicationContext)
            toast("Backing up…")
            view?.postDelayed({ refreshAutoBackup() }, 4000)
        }
        click("auto_backup_folder_pick") { AppLock.expectExternal(); pickAutoFolder.launch(AutoBackup.folder) }
        listOf("auto_backup_freq", "auto_backup_charging").forEach { key ->
            findPreference<Preference>(key)?.setOnPreferenceChangeListener { _, _ ->
                view?.post { AutoBackup.schedule(requireContext().applicationContext) }
                true
            }
        }
        refreshAutoBackup()
        click("backup_restore") { AppLock.expectExternal(); pickBackup.launch(arrayOf("*/*")) }

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

    // ------------------------------------------------------------ backup dialogs

    private fun passwordField(hint: String) = EditText(requireContext()).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }

    private fun dialogBox(vararg views: View) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        val p = Ui.dp(context, 22)
        setPadding(p, Ui.dp(context, 8), p, 0)
        views.forEach { addView(it) }
    }

    private fun askBackupOptions() {
        val pw1 = passwordField("Password (8+ characters)")
        val pw2 = passwordField("Repeat password")
        val history = CheckBox(requireContext()).apply {
            text = "Include history"
            isChecked = false
            isEnabled = HistoryStore.enabled
            if (!HistoryStore.enabled) text = "Include history (history is off)"
        }
        val model = CheckBox(requireContext()).apply { text = "Include learned ad model (~1 MB)"; isChecked = true }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Encrypted backup")
            .setView(dialogBox(pw1, pw2, history, model))
            .setPositiveButton("Choose where to save") { _, _ ->
                val a = pw1.text.toString()
                val b = pw2.text.toString()
                when {
                    a.length < 8 -> toast("Use at least 8 characters")
                    a != b -> toast("Passwords don't match")
                    else -> {
                        pendingPassword = a.toCharArray()
                        pendingHistory = history.isChecked
                        pendingModel = model.isChecked
                        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        AppLock.expectExternal(); createBackup.launch("deep-stellar-$stamp.dsbackup")
                    }
                }
                pw1.text.clear(); pw2.text.clear()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askAutoBackupPassword() {
        val pw1 = passwordField("Password (8+ characters)")
        val pw2 = passwordField("Repeat password")
        val note = TextView(requireContext()).apply {
            text = "Every automatic backup opens with this password. It isn't stored — only a key made from it, locked in this phone's secure hardware. If you forget it, the backups can't be opened."
            textSize = 12f
            setTextColor(Ui.color(context, R.color.starlight_dim))
            setPadding(0, Ui.dp(context, 10), 0, 0)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Automatic backups")
            .setView(dialogBox(pw1, pw2, note))
            .setPositiveButton("Next: choose folder") { _, _ ->
                val a = pw1.text.toString()
                val b = pw2.text.toString()
                pw1.text.clear(); pw2.text.clear()
                when {
                    a.length < 8 -> toast("Use at least 8 characters")
                    a != b -> toast("Passwords don't match")
                    else -> {
                        pendingAutoPassword = a.toCharArray()
                        toast("Pick or create a folder for backups")
                        AppLock.expectExternal(); pickAutoFolder.launch(null)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshAutoBackup()
        refreshCrashes()
    }

    private fun refreshAutoBackup() {
        val status = findPreference<Preference>("auto_backup_status") ?: return
        val err = AutoBackup.lastError
        val last = AutoBackup.lastSuccess
        val df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        status.summary = when {
            !AutoBackup.enabled -> "Off"
            err != null -> "⚠ $err"
            last == 0L -> "No backup yet · tap to run one now"
            else -> "Last backup ${df.format(Date(last))} · tap to run one now"
        }
        findPreference<Preference>("auto_backup_folder_pick")?.summary = AutoBackup.folder?.let { uri ->
            runCatching { androidx.documentfile.provider.DocumentFile.fromTreeUri(requireContext(), uri)?.name }.getOrNull()
                ?: uri.lastPathSegment
        } ?: "Not chosen"
    }

    private fun manageBangs() {
        val custom = Bangs.custom()
        val all = Bangs.all().toList()
        val labels = all.map { (k, v) -> "!$k${if (k in custom) "  (yours)" else ""}\n${v.removePrefix("https://")}" }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Search shortcuts")
            .setItems(labels) { _, i ->
                val (k, _) = all[i]
                if (k in custom) {
                    confirm("Remove !$k?", "Your shortcut will be deleted.") { Bangs.setCustom(k, null); manageBangs() }
                } else toast("Built-in. Add your own with the same keyword to replace it.")
            }
            .setPositiveButton("Add") { _, _ -> addBang() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun addBang() {
        val key = EditText(requireContext()).apply { hint = "Keyword, e.g. cb"; setSingleLine() }
        val url = EditText(requireContext()).apply {
            hint = "Address with %s, e.g. https://codeberg.org/explore/repos?q=%s"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add shortcut")
            .setView(dialogBox(key, url))
            .setPositiveButton("Save") { _, _ ->
                val k = key.text.toString().trim().lowercase().removePrefix("!")
                val u = url.text.toString().trim()
                when {
                    !Regex("^[a-z0-9]{1,12}$").matches(k) -> toast("Keyword: 1–12 letters or digits")
                    !(u.startsWith("https://") || u.startsWith("http://")) || !u.contains("%s") -> toast("The address must start with https:// and contain %s")
                    else -> { Bangs.setCustom(k, u); toast("!$k added") }
                }
                manageBangs()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun manageSites() {
        val sites = SitePrefs.allSites()
        val b = MaterialAlertDialogBuilder(requireContext()).setTitle("Site settings").setNegativeButton("Close", null)
        if (sites.isEmpty()) {
            b.setMessage("No sites have their own settings yet. On any site: Menu → Site settings.")
        } else {
            b.setItems(sites.map { "${it.first}\n${it.second}" }.toTypedArray()) { _, i ->
                confirm("Reset ${sites[i].first}?", "Its permissions and settings go back to the defaults.") {
                    SitePrefs.clear(sites[i].first)
                    manageSites()
                }
            }
            b.setNeutralButton("Reset all") { _, _ ->
                confirm("Reset every site?", "All per-site permissions and settings are removed.") { SitePrefs.clearAll() }
            }
        }
        b.show()
    }

    private fun shortcutsSummary(): String {
        val n = dev.deepstellar.browser.StartShortcuts.all().size
        return if (n == 0) "None yet · tap to add" else "$n shortcut${if (n == 1) "" else "s"} · tap to add, edit, reorder or remove"
    }

    private fun addPanicTile() {
        val ctx = requireContext()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val sbm = ctx.getSystemService(android.app.StatusBarManager::class.java)
            sbm?.requestAddTileService(
                ComponentName(ctx, dev.deepstellar.browser.PanicTileService::class.java),
                "Panic wipe",
                android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_panic),
                ContextCompat.getMainExecutor(ctx),
            ) { result ->
                when (result) {
                    android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> toast("Panic wipe tile added")
                    android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> toast("The tile is already in Quick Settings")
                    else -> {}
                }
            }
        } else {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Add the Panic wipe tile")
                .setMessage("Swipe down twice from the top of the screen, tap the pencil (edit) button, then drag \"Panic wipe\" into your tiles.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun filterSummary(): String {
        val df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
        val last = FilterUpdates.lastUpdate
        val err = FilterUpdates.lastError
        val rules = if (AdBlocker.ruleCount > 0) " + ${AdBlocker.ruleCount} rules" else ""
        return "${AdBlocker.listSize} domains$rules · " + when {
            err != null -> "⚠ $err · tap to retry"
            last > 0 -> "updated ${df.format(Date(last))} · tap to update now"
            else -> "tap to download StevenBlack + AdGuard lists"
        }
    }

    private fun refreshCrashes() {
        val n = CrashLog.list().size
        findPreference<Preference>("crash_reports")?.summary =
            if (n == 0) "None — nice" else "$n saved · tap to view, share or delete"
    }

    private fun showCrashes() {
        val reports = CrashLog.list()
        if (reports.isEmpty()) { toast("No crash reports"); return }
        val df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        val labels = reports.map { "${df.format(Date(it.time))}\n${it.title}" }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Crash reports")
            .setItems(labels) { _, i -> showCrash(reports[i]) }
            .setNeutralButton("Delete all") { _, _ -> CrashLog.clear(); refreshCrashes(); toast("Deleted") }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showCrash(r: CrashLog.Report) {
        val text = r.text()
        val body = android.widget.ScrollView(requireContext()).apply {
            addView(TextView(context).apply {
                this.text = text
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                setTextColor(Ui.color(context, R.color.starlight))
                val p = Ui.dp(context, 18)
                setPadding(p, Ui.dp(context, 8), p, 0)
            })
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(r.title)
            .setView(body)
            .setPositiveButton("Share") { _, _ ->
                AppLock.expectExternal()
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "Deep Stellar crash report")
                        .putExtra(Intent.EXTRA_TEXT, text),
                    "Share crash report",
                ))
            }
            .setNeutralButton("Delete") { _, _ -> r.file.delete(); refreshCrashes() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun askRestorePassword() {
        val pw = passwordField("Backup password")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Unlock backup")
            .setView(dialogBox(pw))
            .setPositiveButton("Unlock") { _, _ ->
                val chars = pw.text.toString().toCharArray()
                pw.text.clear()
                val bytes = pendingRestoreBytes ?: return@setPositiveButton
                toast("Decrypting…")
                Thread {
                    val result = runCatching { BackupManager.open(bytes, chars) }
                    chars.fill(' ')
                    activity?.runOnUiThread {
                        result.fold({ confirmRestore(it) }, { e ->
                            toast(if (e is BackupManager.WrongPasswordException) e.message ?: "Wrong password" else "Can't open: ${e.message}")
                        })
                    }
                }.start()
            }
            .setNegativeButton("Cancel") { _, _ -> pendingRestoreBytes = null }
            .show()
    }

    private fun confirmRestore(c: BackupManager.Contents) {
        val date = java.text.DateFormat.getDateTimeInstance().format(Date(c.created))
        val summary = buildString {
            append("Made $date\n\n")
            append("• ${c.prefCount} settings\n")
            append("• ${c.bookmarks} bookmarks\n")
            append("• ${c.blockedDomains} domains on your block list\n")
            append(if (c.hasModel) "• learned ad model\n" else "")
            append(if (c.historyEntries > 0) "• ${c.historyEntries} history entries (re-encrypted with this phone's key)\n" else "")
            append("\nThis replaces what's on this phone. The browser restarts afterwards.")
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Restore this backup?")
            .setMessage(summary)
            .setPositiveButton("Restore & restart") { _, _ ->
                val ctx = requireContext().applicationContext
                Thread {
                    val r = runCatching { BackupManager.restore(ctx, c) }
                    activity?.runOnUiThread {
                        r.fold({
                            pendingRestoreBytes = null
                            val restart = Intent.makeRestartActivityTask(ComponentName(ctx, MainActivity::class.java))
                            ctx.startActivity(restart)
                            Runtime.getRuntime().exit(0)
                        }, { e -> toast("Restore failed: ${e.message}") })
                    }
                }.start()
            }
            .setNegativeButton("Cancel") { _, _ -> pendingRestoreBytes = null }
            .show()
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
