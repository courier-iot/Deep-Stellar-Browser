package dev.deepstellar.browser

import android.os.Bundle
import android.webkit.WebView
import androidx.webkit.ScriptHandler

/**
 * One browser tab. Everything here lives in memory only and disappears when
 * the tab is closed or the app exits.
 */
class Tab(val id: Long, var url: String, var desktop: Boolean) {
    var title: String = ""
    var webView: WebView? = null
    /** Back/forward list kept in RAM while the tab is suspended to save memory. */
    var savedState: Bundle? = null
    var lastActive: Long = System.currentTimeMillis()
    var progress: Int = 100

    @Volatile var pageHost: String? = null
    @Volatile var blockedOnPage: Int = 0

    /** Host the user agreed to load over plain HTTP despite HTTPS-only mode. */
    var httpAllowedHost: String? = null
    var throttleScript: ScriptHandler? = null
    /** Privacy scripts (WebRTC, fingerprint, referrer) installed in this tab, and what they were built from. */
    var pageScript: ScriptHandler? = null
    var pageScriptSig: String? = null
    /** Last address a per-site setting forced a reload for (stops reload loops). */
    var siteFixUrl: String? = null
    /** Last URL the tracker cleaner produced (guards against redirect loops). */
    var lastCleaned: String? = null
    /** Under-the-hood logs for the current page (memory only). */
    val net = dev.deepstellar.browser.inspect.RequestLog()
    val console = dev.deepstellar.browser.inspect.ConsoleLog()

    /** True while reader mode is showing. */
    var readerMode: Boolean = false

    val isLive: Boolean get() = webView != null
    val isStartPage: Boolean get() = url.startsWith(BrowserEngine.START_PAGE_PREFIX)

    /** Pages that ship inside the app (start page, Projects). */
    val isBuiltInPage: Boolean get() = url.startsWith("https://" + BrowserEngine.ASSET_HOST + "/")

    fun displayTitle(): String = when {
        isStartPage -> "New tab"
        title.isNotBlank() -> title
        else -> url
    }
}
