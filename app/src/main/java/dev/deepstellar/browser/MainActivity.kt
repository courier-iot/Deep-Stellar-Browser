package dev.deepstellar.browser

import dev.deepstellar.browser.AppLock
import android.Manifest
import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.ImageViewCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.deepstellar.browser.adblock.AdBlocker
import dev.deepstellar.browser.bookmarks.Bookmark
import dev.deepstellar.browser.bookmarks.BookmarkStore
import dev.deepstellar.browser.downloads.DownloadEngine
import dev.deepstellar.browser.torrent.TorrentEngine
import dev.deepstellar.browser.history.HistoryStore
import dev.deepstellar.browser.link.LinkManager
import dev.deepstellar.browser.history.SuggestionEngine
import dev.deepstellar.browser.ui.BookmarksActivity
import dev.deepstellar.browser.ui.HistoryActivity
import dev.deepstellar.browser.inspect.Inspector
import dev.deepstellar.browser.inspect.MarkdownView
import dev.deepstellar.browser.projects.Projects
import dev.deepstellar.browser.ui.Format
import dev.deepstellar.browser.ui.ShortcutDialogs
import dev.deepstellar.browser.ui.InspectorActivity
import dev.deepstellar.browser.ui.QrActivity
import dev.deepstellar.browser.ui.Themes
import dev.deepstellar.browser.ui.SettingsActivity
import dev.deepstellar.browser.ui.TransfersActivity
import dev.deepstellar.browser.ui.Ui

class MainActivity : AppCompatActivity(), BrowserHost {

    private lateinit var tabs: TabManager
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var webContainer: FrameLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var tabCount: TextView
    private lateinit var btnBookmark: ImageButton
    private lateinit var btnShield: ImageButton
    private lateinit var suggestScroll: ScrollView
    private lateinit var suggestList: LinearLayout
    private lateinit var findBar: View
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var lastBackPress = 0L
    private var wiped = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        fileCallback = null
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private lateinit var swipe: SwipeRefreshLayout
    private var barsAtBottom: Boolean? = null

    // Camera / microphone / location for a site: the Android permission step.
    private var pendingSiteAnswer: ((Boolean) -> Unit)? = null
    private var pendingSiteAnyIsEnough = false
    private val sitePermLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val ok = if (pendingSiteAnyIsEnough) res.values.any { it } else res.values.all { it }
        pendingSiteAnswer?.invoke(ok)
        pendingSiteAnswer = null
        if (!ok) toast("Android permission denied, so the site can't use it")
    }

    // "Ask where to save each download"
    private class PendingSave(val url: String, val ua: String, val cd: String?, val mime: String?, val referer: String?, val name: String)
    private var pendingSave: PendingSave? = null
    private val saveAsLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val p = pendingSave
        pendingSave = null
        if (uri != null && p != null) {
            val item = DownloadEngine.enqueue(this, p.url, p.ua, p.cd, p.mime, p.referer, destUri = uri, fileName = p.name)
            snackTransfers("Downloading ${item.fileName}")
        }
    }

    // ================================================================ lifecycle

    private var appliedTheme = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedTheme = Themes.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        webContainer = findViewById(R.id.webContainer)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        tabCount = findViewById(R.id.btnTabs)
        btnBookmark = findViewById(R.id.btnBookmark)
        btnShield = findViewById(R.id.btnShield)
        suggestScroll = findViewById(R.id.suggestScroll)
        suggestList = findViewById(R.id.suggestList)
        findBar = findViewById(R.id.findBar)
        findInput = findViewById(R.id.findInput)
        findCount = findViewById(R.id.findCount)
        wireFindBar()

        swipe = findViewById(R.id.swipe)
        swipe.setColorSchemeColors(Ui.color(this, R.color.nebula_violet), Ui.color(this, R.color.cosmic_blue))
        swipe.setProgressBackgroundColorSchemeColor(Ui.color(this, R.color.void_surface_2))
        // Only pull-to-refresh when the page itself is scrolled to the top.
        swipe.setOnChildScrollUpCallback { _, _ -> (tabs.current?.webView?.scrollY ?: 0) > 0 }
        swipe.setOnRefreshListener {
            tabs.current?.webView?.reload()
            swipe.postDelayed({ swipe.isRefreshing = false }, 700)
        }
        arrangeBars()

        val chrome = findViewById<View>(R.id.chrome)
        ViewCompat.setOnApplyWindowInsetsListener(chrome) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }

        tabs = TabManager(this, this)
        tabs.onCurrentChanged = { showTab(it) }

        wireToolbar()
        applyGlobalSettings()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })

        val carried = carriedTabs
        carriedTabs = null
        if (carried != null && carried.first.isNotEmpty()) {
            tabs.adopt(carried.first, carried.second)
        } else if (!handleIntent(intent)) {
            tabs.open(BrowserEngine.startPageUrl())
        }
        ResourceGovernor.attach(tabs)

        // Unfinished downloads and torrents from last time (if "remember" is on).
        if (!SafeMode.active) {
            DownloadEngine.restore(this)
            TorrentEngine.restore(this)
        }

        if (SafeMode.active && !safeModeShown) { safeModeShown = true; showSafeModeDialog() }
        else mainHandler.postDelayed({ if (!isFinishing) SafeMode.markStable() }, SafeMode.STABLE_AFTER_MS)
        checkWebViewVersion()
    }

    // ================================================================ safe mode / WebView check

    private fun showSafeModeDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Safe mode")
            .setMessage(
                "Deep Stellar closed unexpectedly ${SafeMode.crashesBefore} times in a row as it started, so it opened " +
                    "without downloaded filter lists, privacy page scripts, and unfinished downloads and torrents.\n\n" +
                    "Pick a fix, or continue to try again normally next time. Crash reports are in Settings."
            )
            .setPositiveButton("Reset filter lists") { _, _ ->
                AdBlocker.resetDownloadedLists()
                SafeMode.exit()
                toast("Downloaded lists removed. Restart Deep Stellar to leave safe mode.")
            }
            .setNeutralButton("Forget unfinished transfers") { _, _ ->
                DownloadEngine.forgetAll(applicationContext)
                TorrentEngine.forgetAll(applicationContext)
                SafeMode.exit()
                toast("Done. Restart Deep Stellar to leave safe mode.")
            }
            .setNegativeButton("Continue") { _, _ -> SafeMode.exit() }
            .setCancelable(false)
            .show()
    }

    /** Some protections need a recent Android System WebView. Say so once per WebView version. */
    private fun checkWebViewVersion() {
        val pkg = runCatching { androidx.webkit.WebViewCompat.getCurrentWebViewPackage(this) }.getOrNull() ?: return
        val version = pkg.versionName ?: return
        val major = version.substringBefore('.').toIntOrNull() ?: return
        val missing = listOf(
            WebViewFeature.DOCUMENT_START_SCRIPT to "fingerprint and WebRTC protection run late",
            WebViewFeature.WEB_MESSAGE_LISTENER to "in-page files can't be downloaded",
            WebViewFeature.PROXY_OVERRIDE to "Tor (Orbot) routing is unavailable",
        ).filter { !WebViewFeature.isFeatureSupported(it.first) }.map { it.second }
        if (major >= MIN_WEBVIEW && missing.isEmpty()) return
        if (Prefs.raw.getString("webview_warned", "") == version) return
        Prefs.raw.edit().putString("webview_warned", version).apply()
        val detail = if (missing.isEmpty()) "Some newer protections may not work." else "Right now: " + missing.joinToString("; ") + "."
        MaterialAlertDialogBuilder(this)
            .setTitle("Android System WebView is out of date")
            .setMessage("Deep Stellar shows pages with Android System WebView (version $major on this phone). $detail\n\nUpdate \"Android System WebView\" from your app store to get every protection.")
            .setPositiveButton("OK", null)
            .show()
    }

    /** Address bar at the top (default) or at the bottom, above the navigation buttons. */
    private fun arrangeBars() {
        val bottom = Prefs.raw.getBoolean("bar_bottom", false)
        if (barsAtBottom == bottom) return
        barsAtBottom = bottom
        val chrome = findViewById<LinearLayout>(R.id.chrome)
        val top = findViewById<View>(R.id.topBar)
        val nav = findViewById<View>(R.id.bottomBar)
        val order = if (bottom) listOf(swipe, suggestScroll, findBar, progress, top, nav)
        else listOf(top, findBar, progress, suggestScroll, swipe, nav)
        chrome.removeAllViews()
        order.forEach { chrome.addView(it) }
    }

    private fun switchTabBy(delta: Int) {
        val list = tabs.tabs
        if (list.size < 2) return
        val i = list.indexOf(tabs.current)
        tabs.switchTo(list[((i + delta) % list.size + list.size) % list.size])
    }

    private fun closeTab(t: Tab) {
        val restorable = !t.isStartPage
        tabs.close(t)
        updateChrome()
        if (restorable) {
            Snackbar.make(webContainer, "Tab closed", Snackbar.LENGTH_LONG)
                .setAction("Undo") { if (tabs.reopenClosed() == null) toast("Tab limit reached") else updateChrome() }
                .setActionTextColor(Ui.color(this, R.color.cosmic_blue))
                .show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (Themes.current != appliedTheme) {
            // New theme: keep every tab (and its back history) in memory and rebuild the screen.
            carriedTabs = tabs.detachForRecreate()
            webContainer.removeAllViews()
            recreate()
            return
        }
        applyGlobalSettings()
        arrangeBars()
        tabs.current?.webView?.onResume()
        dev.deepstellar.browser.link.LinkManager.connectAll() // linked computers on this Wi-Fi
        // Start-page sections or shortcuts changed in Settings → refresh open start pages.
        val sig = StartShortcuts.json()
        if (startSig != null && sig != startSig) reloadStartPages()
        startSig = sig
        updateChrome()
    }

    private var startSig: String? = null

    private fun reloadStartPages() {
        startSig = StartShortcuts.json()
        tabs.tabs.filter { it.isStartPage }.forEach { it.webView?.reload() }
    }

    override fun onPause() {
        tabs.current?.webView?.onPause()
        AdBlocker.saveNow()
        HistoryStore.flush()
        super.onPause()
    }

    override fun onDestroy() {
        ResourceGovernor.detach()
        if (isFinishing && !wiped) wipeEverything()
        super.onDestroy()
    }

    // ================================================================ intents

    private fun handleIntent(intent: Intent?): Boolean {
        intent ?: return false
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.dataString ?: return false
                if (data.startsWith("magnet:")) {
                    onMagnet(data)
                    return tabs.count > 0
                }
                openUrlFromOutside(data)
                return true
            }
            Intent.ACTION_WEB_SEARCH -> {
                val q = intent.getStringExtra(SearchManager.QUERY) ?: return false
                openUrlFromOutside(Prefs.searchUrl(q))
                return true
            }
        }
        return false
    }

    private fun openUrlFromOutside(rawUrl: String) {
        val url = UrlCleaner.clean(rawUrl)
        if (tabs.open(url) == null) {
            // Tab cap reached: load in the current tab instead.
            tabs.current?.webView?.loadUrl(url, TabManager.extraHeaders())
            toast("Tab limit reached — opened in this tab")
        }
    }

    // ================================================================ toolbar

    @SuppressLint("ClickableViewAccessibility")
    private fun wireToolbar() {
        urlBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                navigate(urlBar.text.toString())
                true
            } else false
        }
        urlBar.setOnFocusChangeListener { _, focused ->
            val t = tabs.current ?: return@setOnFocusChangeListener
            if (focused) {
                urlBar.setText(if (t.isStartPage) "" else t.url)
                urlBar.selectAll()
            } else {
                hideSuggestions()
                updateChrome()
            }
        }
        urlBar.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (urlBar.hasFocus()) showSuggestions(s?.toString().orEmpty())
            }
        })

        // Swipe left / right on the address bar to move between tabs.
        val swipeMin = Ui.dp(this, 56)
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (urlBar.hasFocus() || e1 == null) return false
                val dx = e2.x - e1.x
                if (kotlin.math.abs(dx) < swipeMin || kotlin.math.abs(velocityX) < kotlin.math.abs(velocityY)) return false
                switchTabBy(if (dx < 0) 1 else -1)
                return true
            }
        })
        urlBar.setOnTouchListener { _, ev ->
            val flung = gestures.onTouchEvent(ev)
            flung && ev.actionMasked == MotionEvent.ACTION_UP // swallow the tap that would otherwise open the keyboard
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { tabs.current?.webView?.takeIf { it.canGoBack() }?.goBack() }
        findViewById<View>(R.id.btnForward).setOnClickListener { tabs.current?.webView?.takeIf { it.canGoForward() }?.goForward() }
        findViewById<View>(R.id.btnHome).setOnClickListener { load(BrowserEngine.startPageUrl()) }
        findViewById<View>(R.id.btnReload).setOnClickListener {
            val wv = tabs.current?.webView ?: return@setOnClickListener
            if ((tabs.current?.progress ?: 100) < 100) wv.stopLoading() else wv.reload()
        }
        btnBookmark.setOnClickListener { toggleBookmark() }
        btnBookmark.setOnLongClickListener { startActivity(Intent(this, BookmarksActivity::class.java)); true }
        tabCount.setOnClickListener { showTabsSheet() }
        tabCount.setOnLongClickListener { newTab(); true }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenuSheet() }
        btnShield.setOnClickListener { showShieldSheet() }
    }

    // ================================================================ find in page

    private fun wireFindBar() {
        findInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val wv = tabs.current?.webView ?: return
                val q = s?.toString().orEmpty()
                if (q.isEmpty()) { wv.clearMatches(); findCount.text = "" } else wv.findAllAsync(q)
            }
        })
        findInput.setOnEditorActionListener { _, _, _ -> tabs.current?.webView?.findNext(true); true }
        findViewById<View>(R.id.findNext).setOnClickListener { tabs.current?.webView?.findNext(true) }
        findViewById<View>(R.id.findPrev).setOnClickListener { tabs.current?.webView?.findNext(false) }
        findViewById<View>(R.id.findClose).setOnClickListener { closeFind() }
    }

    private fun openFind() {
        val wv = tabs.current?.webView ?: return
        wv.setFindListener { active, total, done ->
            if (done) findCount.text = if (total == 0) "0/0" else "${active + 1}/$total"
        }
        findBar.visibility = View.VISIBLE
        findInput.setText("")
        findInput.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeFind() {
        tabs.current?.webView?.let { it.clearMatches(); it.setFindListener(null) }
        findBar.visibility = View.GONE
        findCount.text = ""
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(findInput.windowToken, 0)
        findInput.clearFocus()
    }

    // ================================================================ under the hood / markdown / QR

    private fun openInspector(tab: Tab, section: String = "trackers") {
        Inspector.capture(tab) {
            startActivity(Intent(this, InspectorActivity::class.java).putExtra(InspectorActivity.EXTRA_SECTION, section))
        }
    }

    private fun renderMarkdown(tab: Tab) {
        tab.webView?.evaluateJavascript(MarkdownView.script(this)) { r ->
            if (r?.trim('"') == "ok") tab.readerMode = true // back / menu reloads the raw file
            else toast("Couldn't render this file")
        }
    }

    private fun openQr(url: String?, scan: Boolean = false) {
        val i = Intent(this, QrActivity::class.java)
        if (url != null) i.putExtra(QrActivity.EXTRA_TEXT, url)
        if (scan) i.putExtra(QrActivity.EXTRA_SCAN, true)
        startActivity(i)
    }

    // ================================================================ reader mode

    private fun toggleReader(tab: Tab) {
        val wv = tab.webView ?: return
        if (tab.readerMode) {
            tab.readerMode = false
            wv.reload()
            return
        }
        wv.evaluateJavascript(Reader.script(this)) { result ->
            when (result?.trim('"')) {
                "ok" -> tab.readerMode = true
                "none" -> toast("No article found on this page")
                else -> toast("Reader mode couldn't read this page")
            }
        }
    }

    // ================================================================ suggestions

    private fun showSuggestions(typed: String) {
        val current = tabs.current?.url
        // While the full current URL is still selected (just focused), don't suggest.
        if (typed.isBlank() || typed == current) { hideSuggestions(); return }
        val results = SuggestionEngine.suggest(typed)
        if (results.isEmpty()) { hideSuggestions(); return }
        suggestList.removeAllViews()
        for (s in results) {
            val badge = when {
                s.bookmarked -> "★  "
                s.visits > 0 -> "◷  "
                else -> ""
            }
            val fill = Ui.iconButton(this, R.drawable.ic_forward, "Edit this address") {
                // Copy into the bar to tweak it instead of going straight there.
                urlBar.setText(s.url)
                urlBar.setSelection(urlBar.text.length)
            }.apply { rotation = -135f } // ↖ arrow
            suggestList.addView(Ui.row(this, badge + s.title, SuggestionEngine.bare(s.url),
                onClick = { navigate(s.url) }, trailing = listOf(fill)))
        }
        suggestScroll.visibility = View.VISIBLE
    }

    private fun hideSuggestions() {
        if (suggestScroll.visibility != View.GONE) {
            suggestScroll.visibility = View.GONE
            suggestList.removeAllViews()
        }
    }

    private fun navigate(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        hideSuggestions()
        hideKeyboard()
        when {
            text.startsWith("magnet:", ignoreCase = true) -> onMagnet(text)
            looksLikeUrl(text) -> load(if (text.contains("://")) text else "https://$text")
            else -> load(Bangs.resolve(text) ?: Prefs.searchUrl(text))
        }
    }

    private fun looksLikeUrl(t: String): Boolean {
        if (t.contains(' ')) return false
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) return true
        if (t.startsWith("localhost")) return true
        return Regex("^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/.*)?$").matches(t)
    }

    private fun load(url: String) {
        val tab = tabs.current ?: return
        tabs.ensureLive(tab).loadUrl(UrlCleaner.clean(url), TabManager.extraHeaders())
    }

    private fun newTab(url: String = BrowserEngine.startPageUrl(), foreground: Boolean = true): Boolean {
        if (tabs.open(url, foreground) == null) {
            toast("Tab limit reached (${Prefs.tabLabel(Prefs.TAB_STEPS.indexOf(Prefs.maxTabs))}). Change it in Settings.")
            return false
        }
        updateChrome()
        return true
    }

    private fun showTab(tab: Tab) {
        if (::findBar.isInitialized && findBar.visibility == View.VISIBLE) closeFind()
        val wv = tab.webView ?: return
        if (wv.parent !== webContainer) {
            webContainer.removeAllViews()
            (wv.parent as? ViewGroup)?.removeView(wv)
            webContainer.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        updateChrome()
    }

    private fun updateChrome() {
        val t = tabs.current ?: return
        if (!urlBar.hasFocus()) {
            urlBar.setText(when {
                t.isStartPage -> ""
                t.isBuiltInPage -> t.title.ifBlank { "Projects" }
                else -> t.url.removePrefix("https://")
            })
        }
        progress.progress = t.progress
        progress.visibility = if (t.progress in 1..99) View.VISIBLE else View.INVISIBLE
        tabCount.text = if (tabs.count > 99) "∞" else tabs.count.toString()
        tabCount.contentDescription = "${tabs.count} tab${if (tabs.count == 1) "" else "s"} open. Tap for tabs, hold for a new tab"
        btnShield.contentDescription = "Ad shield, ${t.blockedOnPage} blocked on this page"
        val marked = !t.isStartPage && BookmarkStore.contains(t.url)
        btnBookmark.contentDescription = if (marked) "Remove bookmark" else "Bookmark this page"
        ImageViewCompat.setImageTintList(
            btnBookmark,
            ColorStateList.valueOf(Ui.color(this, if (marked) R.color.nebula_violet else R.color.starlight)),
        )
        val shieldColor = when {
            !Prefs.adblock -> R.color.starlight_dim
            AdBlocker.isSiteAllowed(t.pageHost) -> R.color.solar_amber
            else -> R.color.nebula_violet
        }
        ImageViewCompat.setImageTintList(btnShield, ColorStateList.valueOf(Ui.color(this, shieldColor)))
    }

    private fun toggleBookmark() {
        val t = tabs.current ?: return
        if (t.isStartPage) { startActivity(Intent(this, BookmarksActivity::class.java)); return }
        if (BookmarkStore.contains(t.url)) {
            BookmarkStore.remove(t.url)
            toast("Bookmark removed")
        } else {
            BookmarkStore.add(Bookmark(t.title.ifBlank { t.url }, t.url))
            toast("Bookmarked ✦")
        }
        updateChrome()
    }

    // ================================================================ settings

    private fun applyGlobalSettings() {
        if (Prefs.secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            val pc = ProxyController.getInstance()
            if (Prefs.torProxy) {
                val cfg = ProxyConfig.Builder()
                    .addProxyRule(Prefs.proxyHostPort.trim().let { if (it.contains("://")) it else "http://$it" })
                    .build() // no direct fallback: if Orbot is off, pages fail instead of leaking
                pc.setProxyOverride(cfg, ContextCompat.getMainExecutor(this)) {}
            } else {
                pc.clearProxyOverride(ContextCompat.getMainExecutor(this)) {}
            }
        }

        if (::tabs.isInitialized) {
            tabs.tabs.forEach { t ->
                t.webView?.let { wv ->
                    BrowserEngine.applySettings(this, wv, t)
                    BrowserEngine.applyThrottle(t)
                    BrowserEngine.applyPageScripts(t)
                }
            }
        }
        TorrentEngine.applySettings()
        if (::swipe.isInitialized) swipe.isEnabled = Prefs.raw.getBoolean("pull_refresh", true)
    }

    // ================================================================ sheets

    private fun sheet(build: (LinearLayout, BottomSheetDialog) -> Unit) {
        val dlg = BottomSheetDialog(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(this@MainActivity, 8), 0, Ui.dp(this@MainActivity, 24))
        }
        build(list, dlg)
        dlg.setContentView(ScrollView(this).apply { addView(list) })
        dlg.show()
    }

    private fun showTabsSheet() = sheet { list, dlg ->
        val cap = if (Prefs.maxTabs == Int.MAX_VALUE) "∞" else Prefs.maxTabs.toString()
        list.addView(Ui.header(this, "Tabs · ${tabs.count} / $cap · ${tabs.liveCount()} in memory"))
        tabs.tabs.toList().forEach { t ->
            val close = Ui.iconButton(this, R.drawable.ic_close, "Close tab") {
                dlg.dismiss(); closeTab(t)
            }
            val sub = buildString {
                append(if (t.isStartPage) "start page" else t.url.removePrefix("https://"))
                if (!t.isLive) append("  · sleeping")
                if (t.desktop) append("  · desktop")
            }
            list.addView(Ui.row(this, t.displayTitle(), sub, active = t === tabs.current,
                onClick = { tabs.switchTo(t); dlg.dismiss() }, trailing = listOf(close)))
        }
        list.addView(Ui.row(this, "＋  New tab", null, onClick = { dlg.dismiss(); newTab() }))
        if (tabs.count > 1) {
            list.addView(Ui.row(this, "Close all other tabs", null, onClick = {
                tabs.tabs.filter { it !== tabs.current }.forEach { tabs.close(it) }
                dlg.dismiss(); updateChrome()
            }))
        }
    }

    private fun showMenuSheet() = sheet { list, dlg ->
        val t = tabs.current
        list.addView(Ui.header(this, "Deep Stellar"))
        fun item(title: String, sub: String? = null, action: () -> Unit) {
            list.addView(Ui.row(this, title, sub, onClick = { dlg.dismiss(); action() }))
        }
        item("New tab") { newTab() }
        if (tabs.closedCount > 0) {
            item("Reopen closed tab", "${tabs.closedCount} can be brought back") {
                if (tabs.reopenClosed() == null) toast("Tab limit reached") else updateChrome()
            }
        }
        if (t != null && !t.isBuiltInPage) {
            item("Site settings", "JavaScript, text size, camera, microphone, location for ${SitePrefs.site(t.pageHost) ?: "this site"}") { showSiteSheet(t) }
            item("Print / save as PDF") { printPage(t) }
        }
        if (t != null && !t.isStartPage) {
            item("Find in page") { openFind() }
            item(if (t.readerMode) "Exit reader mode" else "Reader mode", "Just the article, in your theme") { toggleReader(t) }
            if (MarkdownView.looksLikeMarkdown(t.url)) {
                item("Render Markdown", "Show this .md file formatted") { renderMarkdown(t) }
            }
            item("Under the hood", "Who's watching · network · console · certificate · source") { openInspector(t) }
            item("QR code for this page", "Show it to another device, or scan one") { openQr(t.url) }
            item("Add to start page", "Pin this site to Your shortcuts") {
                ShortcutDialogs.edit(this, null, prefillUrl = t.url,
                    prefillName = t.title.ifBlank { StartShortcuts.defaultName(t.url) }.take(40)) { reloadStartPages() }
            }
        } else {
            item("Scan a QR code", "Open a link, magnet or text from a code") { openQr(null, scan = true) }
        }
        if (t != null) {
            item("Desktop site", if (t.desktop) "On for this tab" else "Off for this tab") {
                // If this site has its own desktop setting, keep the two in step.
                if (SitePrefs.desktop(t.pageHost) != null) SitePrefs.setDesktop(t.pageHost, !t.desktop)
                BrowserEngine.setDesktop(this, t, !t.desktop)
            }
        }
        item("Bookmarks", "Open, import, export") { startActivity(Intent(this, BookmarksActivity::class.java)) }
        if (HistoryStore.enabled) {
            item("History", "Encrypted on this device") { startActivity(Intent(this, HistoryActivity::class.java)) }
        }
        item("Downloads & torrents", transfersSummary()) { startActivity(Intent(this, TransfersActivity::class.java)) }
        item("Ad shield", "${AdBlocker.sessionBlocked.get()} blocked this session") { showShieldSheet() }
        item("Resource monitor", "CPU: ${Prefs.CPU_LABELS[Prefs.cpuMode]} · RAM: ${Prefs.ramLabel(Prefs.RAM_STEPS_MB.indexOf(Prefs.ramBudgetMb))}") { showResourceSheet() }
        if (t != null && !t.isStartPage) {
            item("Share page") {
                AppLock.expectExternal(); startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t.url), "Share"))
            }
            item("Copy link") { copy(t.url) }
            if (LinkManager.hasStations) item("Send to computer", "To a linked Deep Stellar Station, end-to-end encrypted") { sendToStation(t) }
        }
        item("Linked computers", if (LinkManager.hasStations) "${LinkManager.stations().count { it.online }} of ${LinkManager.stations().size} connected" else "Link Deep Stellar Station on Linux or Windows") {
            startActivity(Intent(this, dev.deepstellar.browser.ui.LinkActivity::class.java))
        }
        item("Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }
        item("Exit & wipe", "Close all tabs and erase cookies, cache and site data") { exitAndWipe() }
    }

    private fun sendToStation(t: Tab) {
        val list = LinkManager.stations()
        fun go(st: LinkManager.Station) {
            LinkManager.sendLink(st.fp, t.url, t.title)
            toast(if (st.online) "Sent to ${st.name}" else "Will send when ${st.name} is on the same Wi-Fi")
        }
        if (list.size == 1) { go(list[0]); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Send to")
            .setItems(list.map { it.name + if (it.online) "" else " (away)" }.toTypedArray()) { _, i -> go(list[i]) }
            .show()
    }

    private fun transfersSummary(): String {
        val d = DownloadEngine.activeCount
        val tr = TorrentEngine.activeCount
        return if (d + tr == 0) "Nothing running" else "$d downloading · $tr torrents"
    }

    private fun showShieldSheet() = sheet { list, dlg ->
        val t = tabs.current
        list.addView(Ui.header(this, "Ad shield"))
        list.addView(Ui.row(this, "${t?.blockedOnPage ?: 0} blocked on this page", "${AdBlocker.sessionBlocked.get()} this session · ${AdBlocker.sessionLearnedBlocks.get()} caught by learning"))
        val model = if (AdBlocker.learnerWarm) "active" else "warming up"
        list.addView(Ui.row(this, "Learning model: $model", "${AdBlocker.trainedExamples} examples learned · ${AdBlocker.listSize} listed domains"))
        val host = t?.pageHost
        if (host != null && t?.isStartPage == false) {
            val allowed = AdBlocker.isSiteAllowed(host)
            list.addView(Ui.row(this,
                if (allowed) "Turn shield back on for this site" else "Site broken? Allow ads on this site",
                host, onClick = {
                    val now = AdBlocker.toggleSiteAllowed(host)
                    toast(if (now) "Shield off for $host" else "Shield on for $host")
                    dlg.dismiss(); updateChrome(); t?.webView?.reload()
                }))
        }
        if (t != null && !t.isStartPage) {
            list.addView(Ui.row(this, "See who's watching →", "Every outside company this page contacted, and what was stopped",
                onClick = { dlg.dismiss(); openInspector(t) }))
        }
        list.addView(Ui.row(this, "Tip", "Long-press any ad image or link → \"Block as ad\" to teach the shield."))
    }

    private fun showResourceSheet() {
        val dlg = BottomSheetDialog(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(this@MainActivity, 8), 0, Ui.dp(this@MainActivity, 24))
        }
        fun render(s: ResourceGovernor.Snapshot?) {
            body.removeAllViews()
            body.addView(Ui.header(this, "Resource monitor"))
            if (s == null) { body.addView(Ui.row(this, "Measuring…", null)); return }
            val budget = if (s.budgetMb == Int.MAX_VALUE) "no limit" else "${s.budgetMb} MB budget"
            body.addView(Ui.row(this, "Memory ≈ ${s.estimatedMb} MB", "$budget · app process ${s.appPssMb} MB · pages estimated per live tab"))
            body.addView(Ui.row(this, "Browser CPU ${s.cpuPercent}%", "Mode: ${Prefs.CPU_LABELS[Prefs.cpuMode]}${if (s.torrentThrottled) " · torrents throttled" else ""}"))
            body.addView(Ui.row(this, "${s.liveTabs} of ${s.totalTabs} tabs in memory", "${s.suspendedByGovernor} tab suspensions so far — sleeping tabs reload when opened"))
            body.addView(Ui.row(this, "Change limits", "Settings → Performance", onClick = {
                dlg.dismiss(); startActivity(Intent(this, SettingsActivity::class.java))
            }))
        }
        render(ResourceGovernor.last)
        ResourceGovernor.listener = { s -> if (dlg.isShowing) render(s) }
        dlg.setOnDismissListener { ResourceGovernor.listener = null }
        dlg.setContentView(ScrollView(this).apply { addView(body) })
        dlg.show()
    }

    // ================================================================ BrowserHost

    override fun onTabUpdated(tab: Tab) {
        if (tab === tabs.current) updateChrome()
    }

    override fun onSpecialUrl(tab: Tab, uri: Uri) {
        when (uri.host) {
            "search" -> uri.getQueryParameter("q")?.let { tab.webView?.loadUrl(Prefs.searchUrl(it), TabManager.extraHeaders()) }
            "bookmarks" -> startActivity(Intent(this, BookmarksActivity::class.java))
            "shortcut-add" -> ShortcutDialogs.edit(this, null) { reloadStartPages() }
            "projects" -> tab.webView?.loadUrl(BrowserEngine.projectsPageUrl())
            "projects-check-on" -> {
                Projects.setCheckEnabled(applicationContext, true)
                askNotificationsOnce()
                toast("Checking GitHub every 6 hours")
                tab.webView?.postDelayed({ if (tab.url.startsWith(BrowserEngine.PROJECTS_PAGE_PREFIX)) tab.webView?.reload() }, 4000)
            }
            "projects-check" -> {
                Projects.checkNow(applicationContext)
                toast("Checking GitHub…")
                tab.webView?.postDelayed({ if (tab.url.startsWith(BrowserEngine.PROJECTS_PAGE_PREFIX)) tab.webView?.reload() }, 4000)
            }
        }
    }

    override fun onMagnet(uri: String) {
        if (Prefs.torProxy) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Torrent outside Tor")
                .setMessage("BitTorrent traffic can't go through Tor. Your real IP will be visible to peers. Start anyway?")
                .setPositiveButton("Start") { _, _ -> startMagnet(uri) }
                .setNegativeButton("Cancel", null)
                .show()
        } else startMagnet(uri)
    }

    private fun startMagnet(uri: String) {
        askNotificationsOnce()
        val item = TorrentEngine.addMagnet(this, uri)
        snackTransfers("Torrent added: ${item.name}")
    }

    override fun onExternalIntent(uri: String) {
        try {
            val intent = if (uri.startsWith("intent:")) Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // intent:// links often carry a web fallback
            val fallback = runCatching {
                Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
            }.getOrNull()
            if (fallback != null && fallback.startsWith("http")) load(fallback) else toast("No app can open this link")
        } catch (e: Exception) {
            toast("Can't open this link")
        }
    }

    override fun onDownloadRequested(tab: Tab, url: String, userAgent: String, contentDisposition: String?, mimeType: String?, length: Long) {
        val isTorrent = mimeType == "application/x-bittorrent" ||
            url.substringBefore('?').endsWith(".torrent", ignoreCase = true) ||
            (contentDisposition?.contains(".torrent", ignoreCase = true) == true)
        if (url.startsWith("blob:")) {
            // The file only exists inside the page: read it out, then save it like any download.
            toast("Preparing file…")
            BrowserEngine.fetchBlob(tab, url, mimeType, this)
            return
        }
        askNotificationsOnce()
        if (isTorrent) {
            val item = TorrentEngine.addTorrentUrl(this, url, userAgent)
            snackTransfers("Torrent added: ${item.name}")
        } else {
            beginDownload(tab.url, url, userAgent, contentDisposition, mimeType, length, null)
        }
    }

    private fun beginDownload(referer: String?, url: String, userAgent: String, contentDisposition: String?, mimeType: String?, length: Long, name: String?) {
        if (Prefs.raw.getBoolean("download_ask", false)) {
            val guessed = name ?: android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
            pendingSave = PendingSave(url, userAgent, contentDisposition, mimeType, referer, guessed)
            AppLock.expectExternal()
            saveAsLauncher.launch(guessed)
            return
        }
        val item = DownloadEngine.enqueue(this, url, userAgent, contentDisposition, mimeType, referer, fileName = name)
        val size = if (length > 0) " (${Format.bytes(length)})" else ""
        snackTransfers("Downloading ${item.fileName}$size")
    }

    override fun onBlobDownload(tab: Tab, file: java.io.File?, mimeType: String?, error: String?) {
        if (file == null || error != null) {
            toast("Couldn't save this file: ${error ?: "the page didn't hand it over"}")
            return
        }
        askNotificationsOnce()
        val name = android.webkit.URLUtil.guessFileName("https://local/download", null, mimeType)
        beginDownload(tab.url, Uri.fromFile(file).toString(), BrowserEngine.userAgentFor(this, tab), null, mimeType, file.length(), name)
    }

    // ================================================================ site permissions

    override fun onSitePermission(tab: Tab, siteHost: String?, kinds: List<String>, answer: (Boolean) -> Unit) {
        val site = SitePrefs.site(siteHost)
        if (site == null || isFinishing) { answer(false); return }
        val states = kinds.map { SitePrefs.permission(site, it) }
        if (states.any { it == SitePrefs.BLOCK }) { answer(false); return }
        if (states.all { it == SitePrefs.ALLOW }) { withAndroidPermissions(kinds, answer); return }

        val what = kinds.joinToString(" and ") {
            when (it) { SitePrefs.CAMERA -> "camera"; SitePrefs.MIC -> "microphone"; else -> "location" }
        }
        val remember = android.widget.CheckBox(this).apply { text = "Remember for $site" }
        val box = LinearLayout(this).apply {
            setPadding(Ui.dp(context, 20), Ui.dp(context, 4), Ui.dp(context, 20), 0)
            addView(remember)
        }
        var answered = false
        MaterialAlertDialogBuilder(this)
            .setTitle("$site wants to use your $what")
            .setView(box)
            .setPositiveButton("Allow") { _, _ ->
                answered = true
                if (remember.isChecked) {
                    kinds.forEach { SitePrefs.setPermission(site, it, SitePrefs.ALLOW) }
                    tabs.tabs.forEach { BrowserEngine.applyPageScripts(it) }
                }
                withAndroidPermissions(kinds, answer)
            }
            .setNegativeButton("Block") { _, _ ->
                answered = true
                if (remember.isChecked) kinds.forEach { SitePrefs.setPermission(site, it, SitePrefs.BLOCK) }
                answer(false)
            }
            .setOnDismissListener { if (!answered) answer(false) }
            .show()
    }

    /** The site is allowed; now make sure Android has given the browser itself the permission. */
    private fun withAndroidPermissions(kinds: List<String>, answer: (Boolean) -> Unit) {
        val needed = kinds.flatMap {
            when (it) {
                SitePrefs.CAMERA -> listOf(Manifest.permission.CAMERA)
                SitePrefs.MIC -> listOf(Manifest.permission.RECORD_AUDIO)
                else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        val locationOnly = kinds.all { it == SitePrefs.LOCATION }
        if (missing.isEmpty() || (locationOnly && missing.size < needed.size)) { answer(true); return }
        pendingSiteAnswer?.invoke(false)
        pendingSiteAnswer = answer
        pendingSiteAnyIsEnough = locationOnly
        AppLock.expectExternal()
        sitePermLauncher.launch(missing.toTypedArray())
    }

    private fun showSiteSheet(tab: Tab) {
        val host = tab.pageHost
        val site = SitePrefs.site(host) ?: return
        sheet { list, dlg ->
            list.addView(Ui.header(this, site))
            fun again() { dlg.dismiss(); showSiteSheet(tab) }
            fun reload() { tab.webView?.let { BrowserEngine.applySettings(this, it, tab); it.reload() } }
            fun row(title: String, sub: String?, action: () -> Unit) = list.addView(Ui.row(this, title, sub, onClick = action))

            // JavaScript: follow global → off → on → follow global
            val js = SitePrefs.javascript(host)
            row("JavaScript: " + when (js) { null -> "default (${if (Prefs.javascript) "on" else "off"})"; true -> "always on here"; false -> "off here" },
                "Tap to change. Off stops most tracking scripts but breaks many sites.") {
                SitePrefs.setJavascript(host, when (js) { null -> false; false -> true; true -> null })
                reload(); again()
            }

            val desk = SitePrefs.desktop(host)
            row("Desktop site: " + when (desk) { null -> "follow the tab"; true -> "always here"; false -> "never here" }, "Tap to change") {
                SitePrefs.setDesktop(host, when (desk) { null -> true; true -> false; false -> null })
                reload(); again()
            }

            // Text size
            val base = Prefs.raw.getInt("text_zoom", 100)
            val zoom = SitePrefs.zoom(host)
            fun setZoom(v: Int?) {
                SitePrefs.setZoom(host, v?.coerceIn(50, 300))
                tab.webView?.settings?.textZoom = SitePrefs.zoom(host) ?: base
                again()
            }
            val minus = Ui.iconButton(this, R.drawable.ic_remove, "Smaller text") { setZoom((zoom ?: base) - 10) }
            val plus = Ui.iconButton(this, R.drawable.ic_add, "Larger text") { setZoom((zoom ?: base) + 10) }
            list.addView(Ui.row(this, "Text size: ${zoom ?: base}%" + if (zoom == null) " (default)" else " here",
                "− and + change it for this site · tap to reset", onClick = { setZoom(null) }, trailing = listOf(minus, plus)))

            // Permissions: ask → allow → block → ask
            fun perm(kind: String, label: String) {
                val v = SitePrefs.permission(host, kind)
                row("$label: " + when (v) { SitePrefs.ALLOW -> "allowed"; SitePrefs.BLOCK -> "blocked"; else -> "ask each time" }, "Tap to change") {
                    SitePrefs.setPermission(host, kind, when (v) { SitePrefs.ASK -> SitePrefs.ALLOW; SitePrefs.ALLOW -> SitePrefs.BLOCK; else -> SitePrefs.ASK })
                    tabs.tabs.forEach { BrowserEngine.applyPageScripts(it) }
                    again()
                }
            }
            perm(SitePrefs.CAMERA, "Camera")
            perm(SitePrefs.MIC, "Microphone")
            perm(SitePrefs.LOCATION, "Location")

            val shieldOff = AdBlocker.isSiteAllowed(host)
            row(if (shieldOff) "Ad shield: off here" else "Ad shield: on", "Tap to change") {
                AdBlocker.toggleSiteAllowed(host ?: site)
                updateChrome(); tab.webView?.reload(); again()
            }
            row("Reset this site's settings", null) {
                SitePrefs.clear(site)
                tabs.tabs.forEach { BrowserEngine.applyPageScripts(it) }
                reload(); dlg.dismiss()
            }
        }
    }

    private fun printPage(tab: Tab) {
        val wv = tab.webView ?: return
        val pm = getSystemService(android.print.PrintManager::class.java)
        if (pm == null) { toast("Printing isn't available on this device"); return }
        val name = tab.title.ifBlank { "Deep Stellar page" }.take(60)
        AppLock.expectExternal()
        // "Save as PDF" is one of the printers Android offers on the next screen.
        pm.print(name, wv.createPrintDocumentAdapter(name), android.print.PrintAttributes.Builder().build())
    }

    override fun onLongPress(tab: Tab, result: WebView.HitTestResult): Boolean {
        val extra = result.extra ?: return false
        return when (result.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> { linkMenu(tab, extra, null); true }
            WebView.HitTestResult.IMAGE_TYPE -> { linkMenu(tab, null, extra); true }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val wv = tab.webView ?: return false
                val h = Handler(Looper.getMainLooper()) { msg ->
                    linkMenu(tab, msg.data.getString("url"), extra); true
                }
                wv.requestFocusNodeHref(h.obtainMessage())
                true
            }
            else -> false
        }
    }

    private fun linkMenu(tab: Tab, link: String?, image: String?) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        fun add(l: String, a: () -> Unit) { labels += l; actions += a }

        // Start-page tiles: manage your shortcuts in place.
        if (tab.isStartPage && link != null && (link.startsWith("http://") || link.startsWith("https://"))) {
            val i = StartShortcuts.indexOf(link)
            if (i >= 0) {
                add("Edit shortcut") { ShortcutDialogs.edit(this, i) { reloadStartPages() } }
                add("Remove from start page") { StartShortcuts.remove(i); reloadStartPages() }
            } else {
                add("Add to my shortcuts") {
                    ShortcutDialogs.edit(this, null, prefillUrl = link, prefillName = StartShortcuts.defaultName(link)) { reloadStartPages() }
                }
            }
        }

        if (link != null) {
            add("Open in new tab") { newTab(link) }
            add("Open in background tab") { if (newTab(link, foreground = false)) toast("Opened in background") }
            add("Copy link") { copy(link) }
            add("Share link") {
                AppLock.expectExternal(); startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), "Share"))
            }
            add("Download link") { onDownloadRequested(tab, link, BrowserEngine.userAgentFor(this, tab), null, null, -1) }
        }
        if (image != null) {
            add("Open image in new tab") { newTab(image) }
            add("Download image") { onDownloadRequested(tab, image, BrowserEngine.userAgentFor(this, tab), null, null, -1) }
        }
        val target = image ?: link
        if (target != null && target.startsWith("http")) {
            add("Block as ad ✕") {
                AdBlocker.userFlagAd(target, tab.pageHost)
                toast("Shield learned from this. Reloading…")
                tab.webView?.reload()
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle((link ?: image ?: "").take(120))
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    override fun onNewWindow(opener: Tab, resultMsg: Message): Boolean {
        val wv = tabs.openForWindow()
        if (wv == null) {
            toast("Tab limit reached")
            return false
        }
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        transport.webView = wv
        resultMsg.sendToTarget()
        updateChrome()
        return true
    }

    override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) { callback.onCustomViewHidden(); return }
        customView = view
        customViewCallback = callback
        fullscreenContainer.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fullscreenContainer.visibility = View.VISIBLE
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onHideCustomView() {
        val v = customView ?: return
        fullscreenContainer.removeView(v)
        fullscreenContainer.visibility = View.GONE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        return try {
            AppLock.expectExternal(); fileLauncher.launch(params.createIntent())
            true
        } catch (e: Exception) {
            fileCallback = null
            false
        }
    }

    override fun onHttpsUpgradeFailed(tab: Tab, httpUrl: String) {
        val host = Uri.parse(httpUrl).host ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("No secure connection")
            .setMessage("$host doesn't seem to support HTTPS. Loading it over plain HTTP means anyone on the network can see and change the page.")
            .setPositiveButton("Load insecure") { _, _ ->
                tab.httpAllowedHost = host
                tab.webView?.loadUrl(httpUrl)
            }
            .setNegativeButton("Stay safe", null)
            .show()
    }

    override fun onRendererGone(tab: Tab) {
        mainHandler.post {
            tabs.recreateAfterCrash(tab)
            if (tab === tabs.current) toast("Page crashed and was reloaded")
        }
    }

    // ================================================================ back / exit

    private fun onBack() {
        if (customView != null) { onHideCustomView(); return }
        if (findBar.visibility == View.VISIBLE) { closeFind(); return }
        tabs.current?.let { if (it.readerMode) { toggleReader(it); return } }
        if (urlBar.hasFocus()) { urlBar.clearFocus(); hideKeyboard(); return }
        val wv = tabs.current?.webView
        if (wv != null && wv.canGoBack()) { wv.goBack(); return }
        if (tabs.count > 1) { tabs.current?.let { closeTab(it) }; return }
        val now = System.currentTimeMillis()
        if (now - lastBackPress < 2000) exitAndWipe()
        else { lastBackPress = now; toast("Press back again to exit & wipe") }
    }

    private fun exitAndWipe() {
        wipeEverything()
        finishAndRemoveTask()
    }

    private fun wipeEverything() {
        if (wiped) return
        wiped = true
        PrivacyWiper.wipeWebData(this, tabs.current?.webView)
        tabs.closeAll()
        webContainer.removeAllViews()
        AdBlocker.saveNow()
        if (DownloadEngine.activeCount == 0 && TorrentEngine.activeCount == 0) TorrentEngine.shutdown()
    }

    // ================================================================ helpers

    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !Prefs.raw.getBoolean("asked_notif", false)
        ) {
            Prefs.raw.edit().putBoolean("asked_notif", true).apply()
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun snackTransfers(text: String) {
        Snackbar.make(webContainer, text, Snackbar.LENGTH_LONG)
            .setAction("View") { startActivity(Intent(this, TransfersActivity::class.java)) }
            .setActionTextColor(Ui.color(this, R.color.cosmic_blue))
            .show()
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("link", text))
        if (Build.VERSION.SDK_INT < 33) toast("Copied")
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(urlBar.windowToken, 0)
        urlBar.clearFocus()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        /** Oldest Android System WebView major version every feature has been checked against. */
        private const val MIN_WEBVIEW = 110
        private var safeModeShown = false

        /** Tabs handed from the old screen to the new one during a theme change (memory only). */
        private var carriedTabs: Pair<List<Tab>, Long?>? = null
    }
}
