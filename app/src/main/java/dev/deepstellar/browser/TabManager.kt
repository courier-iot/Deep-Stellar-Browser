package dev.deepstellar.browser

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView

/**
 * Owns all tabs. Enforces the tab cap and supports "suspending" background
 * tabs (destroying their WebView but keeping their back/forward list in RAM)
 * so the CPU/RAM governor can free resources without losing your place.
 */
class TabManager(private val ctx: Context, private val host: BrowserHost) {

    val tabs = mutableListOf<Tab>()
    var current: Tab? = null
        private set
    private var nextId = 1L

    var onCurrentChanged: ((Tab) -> Unit)? = null

    val count get() = tabs.size
    fun canOpenMore(): Boolean = tabs.size < Prefs.maxTabs

    /** Returns null if the tab cap is reached. */
    fun open(url: String, foreground: Boolean = true, desktop: Boolean = Prefs.desktopDefault): Tab? {
        if (!canOpenMore()) return null
        val tab = Tab(nextId++, url, desktop)
        tabs += tab
        if (foreground) {
            val wv = BrowserEngine.create(ctx, tab, host)
            wv.loadUrl(url, extraHeaders())
            switchTo(tab)
        }
        // Background tabs stay suspended (no WebView) until you open them.
        return tab
    }

    /** Opens a blank live tab for window.open / target=_blank and returns its WebView. */
    fun openForWindow(): WebView? {
        if (!canOpenMore()) return null
        val tab = Tab(nextId++, "about:blank", Prefs.desktopDefault)
        tabs += tab
        val wv = BrowserEngine.create(ctx, tab, host)
        switchTo(tab)
        return wv
    }

    fun switchTo(tab: Tab) {
        val prev = current
        if (prev != null && prev !== tab) prev.webView?.onPause()
        current = tab
        tab.lastActive = System.currentTimeMillis()
        ensureLive(tab).onResume()
        onCurrentChanged?.invoke(tab)
    }

    fun ensureLive(tab: Tab): WebView {
        tab.webView?.let { return it }
        val wv = BrowserEngine.create(ctx, tab, host)
        val state = tab.savedState
        if (state != null && wv.restoreState(state) != null) {
            // restored back/forward list; WebView reloads the current entry
        } else {
            wv.loadUrl(tab.url, extraHeaders())
        }
        tab.savedState = null
        return wv
    }

    /** Frees a tab's WebView. Its history stays in memory for when you return. */
    fun suspend(tab: Tab) {
        if (tab === current) return
        val wv = tab.webView ?: return
        val b = Bundle()
        runCatching { wv.saveState(b) }
        tab.savedState = b
        destroyView(wv)
        tab.throttleScript = null
        tab.webView = null
    }

    fun close(tab: Tab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        tabs.removeAt(idx)
        tab.webView?.let { destroyView(it) }
        tab.webView = null
        tab.savedState = null
        if (tab === current) {
            current = null
            if (tabs.isEmpty()) {
                open(BrowserEngine.startPageUrl())
            } else {
                switchTo(tabs[(idx - 1).coerceAtLeast(0).coerceAtMost(tabs.size - 1)])
            }
        }
    }

    /**
     * Used when the screen is rebuilt (e.g. theme change): every tab keeps its
     * back/forward list in memory, its WebView is released, and [adopt] wakes
     * them up again in the new screen.
     */
    fun detachForRecreate(): Pair<List<Tab>, Long?> {
        val cur = current?.id
        tabs.forEach { t ->
            t.webView?.let { wv ->
                val b = android.os.Bundle()
                runCatching { wv.saveState(b) }
                t.savedState = b
                destroyView(wv)
            }
            t.webView = null
            t.throttleScript = null
        }
        val list = tabs.toList()
        tabs.clear()
        current = null
        return list to cur
    }

    fun adopt(list: List<Tab>, currentId: Long?) {
        tabs += list
        nextId = (list.maxOfOrNull { it.id } ?: 0L) + 1
        // Built-in pages carry the theme in their address; point them at the new one.
        list.filter { it.url.startsWith(BrowserEngine.PROJECTS_PAGE_PREFIX) }.forEach {
            it.savedState = null
            it.url = BrowserEngine.projectsPageUrl()
        }
        val target = list.firstOrNull { it.id == currentId } ?: list.firstOrNull() ?: return
        if (target.isStartPage) {
            // Rebuild the start page so it picks up the new theme.
            target.savedState = null
            target.url = BrowserEngine.startPageUrl()
        }
        switchTo(target)
    }

    fun closeAll() {
        tabs.toList().forEach { t ->
            t.webView?.let { destroyView(it) }
            t.webView = null
        }
        tabs.clear()
        current = null
    }

    /** Live tabs other than the visible one, least recently used first. */
    fun liveBackgroundLru(): List<Tab> =
        tabs.filter { it.isLive && it !== current }.sortedBy { it.lastActive }

    fun liveCount(): Int = tabs.count { it.isLive }

    fun recreateAfterCrash(tab: Tab) {
        tab.webView?.let { destroyView(it) }
        tab.webView = null
        tab.savedState = null
        if (tab === current) {
            val wv = ensureLive(tab)
            onCurrentChanged?.invoke(tab)
            wv.onResume()
        }
    }

    private fun destroyView(wv: WebView) {
        (wv.parent as? ViewGroup)?.removeView(wv)
        runCatching {
            wv.stopLoading()
            wv.webChromeClient = null
            wv.setDownloadListener(null)
            wv.destroy()
        }
    }

    companion object {
        fun extraHeaders(): Map<String, String> =
            if (Prefs.sendGpc) mapOf("Sec-GPC" to "1", "DNT" to "1") else emptyMap()
    }
}
