package dev.deepstellar.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.webkit.ConsoleMessage
import dev.deepstellar.browser.adblock.AdBlocker
import dev.deepstellar.browser.adblock.DomainUtil
import dev.deepstellar.browser.inspect.ConsoleLog
import dev.deepstellar.browser.inspect.RequestLog
import dev.deepstellar.browser.history.HistoryStore
import java.io.ByteArrayInputStream

/** What a tab needs from the screen hosting it. */
interface BrowserHost {
    fun onTabUpdated(tab: Tab)
    fun onSpecialUrl(tab: Tab, uri: Uri)
    fun onMagnet(uri: String)
    fun onExternalIntent(uri: String)
    fun onDownloadRequested(tab: Tab, url: String, userAgent: String, contentDisposition: String?, mimeType: String?, length: Long)
    fun onLongPress(tab: Tab, result: WebView.HitTestResult): Boolean
    fun onNewWindow(opener: Tab, resultMsg: Message): Boolean
    fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback)
    fun onHideCustomView()
    fun onFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean
    fun onHttpsUpgradeFailed(tab: Tab, httpUrl: String)
    fun onRendererGone(tab: Tab)
}

object BrowserEngine {
    const val ASSET_HOST = "appassets.androidplatform.net"
    const val START_PAGE_PREFIX = "https://$ASSET_HOST/assets/start.html"

    fun startPageUrl(): String {
        val fps = when (Prefs.cpuMode) { 0 -> 60; 1 -> 45; 2 -> 24; else -> 12 }
        val still = if (!Prefs.starfield || Prefs.cpuMode == 3) "1" else "0"
        return "$START_PAGE_PREFIX?fps=$fps&still=$still&theme=${dev.deepstellar.browser.ui.Themes.current}"
    }

    private var defaultUa: String? = null

    /** WebView's UA minus the "; wv" and "Version/4.0" markers, so we look like plain Chrome. */
    fun mobileUa(ctx: Context): String {
        val ua = defaultUa ?: runCatching { WebSettings.getDefaultUserAgent(ctx) }.getOrNull().also { defaultUa = it }
            ?: return NetUtil.UA_MOBILE
        return ua.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
    }

    fun desktopUa(ctx: Context): String {
        val chrome = Regex("Chrome/([\\d.]+)").find(mobileUa(ctx))?.groupValues?.get(1) ?: "130.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
    }

    fun userAgentFor(ctx: Context, tab: Tab) = if (tab.desktop) desktopUa(ctx) else mobileUa(ctx)

    @SuppressLint("SetJavaScriptEnabled")
    fun applySettings(ctx: Context, wv: WebView, tab: Tab) {
        val s = wv.settings
        s.javaScriptEnabled = Prefs.javascript
        s.domStorageEnabled = true // needed by most sites; wiped on exit
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setGeolocationEnabled(false)
        s.mediaPlaybackRequiresUserGesture = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportZoom(true)
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.safeBrowsingEnabled = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.userAgentString = userAgentFor(ctx, tab)
        s.textZoom = Prefs.raw.getInt("text_zoom", 100)
        // Ultra-eco: skip images unless the user taps through.
        s.blockNetworkImage = Prefs.cpuMode == 3 && Prefs.raw.getBoolean("ultra_no_images", false)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, Prefs.raw.getBoolean("force_dark", false))
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, !Prefs.blockThirdPartyCookies)
    }

    /** Called when the user flips desktop mode on a tab. */
    fun setDesktop(ctx: Context, tab: Tab, desktop: Boolean) {
        tab.desktop = desktop
        val wv = tab.webView ?: return
        wv.settings.userAgentString = userAgentFor(ctx, tab)
        wv.settings.loadWithOverviewMode = true
        wv.settings.useWideViewPort = true
        wv.reload()
    }

    // ---------------------------------------------------------------- CPU throttle

    /**
     * Injected at document start in Eco / Ultra-eco modes. Caps animation frame rate
     * and stretches very fast repeating timers — the same trick desktop browsers use
     * on background tabs, applied to the page you're looking at.
     */
    private fun throttleJs(fps: Int, minIntervalMs: Int): String =
        "(function(){if(window.__dsThr)return;window.__dsThr=1;" +
        "var gap=${1000 / fps},minI=$minIntervalMs,oS=window.setTimeout.bind(window),oC=window.clearTimeout.bind(window)," +
        "oI=window.setInterval.bind(window),oR=window.requestAnimationFrame.bind(window),last=0;" +
        "window.requestAnimationFrame=function(cb){var now=performance.now(),wait=Math.max(0,gap-(now-last));" +
        "return oS(function(){oR(function(t){last=t;cb(t);});},wait);};" +
        "window.cancelAnimationFrame=function(id){oC(id);};" +
        "window.setInterval=function(f,d){var a=Array.prototype.slice.call(arguments,2);" +
        "return oI.apply(null,[f,Math.max(+d||0,minI)].concat(a));};" +
        "})();"

    fun applyThrottle(tab: Tab) {
        val wv = tab.webView ?: return
        tab.throttleScript?.remove()
        tab.throttleScript = null
        val js = when (Prefs.cpuMode) {
            2 -> throttleJs(30, 100)
            3 -> throttleJs(15, 400)
            else -> return
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                tab.throttleScript = WebViewCompat.addDocumentStartJavaScript(wv, js, setOf("*"))
            }
        }
    }

    private fun fallbackThrottle(view: WebView, tab: Tab) {
        if (tab.throttleScript != null) return
        val js = when (Prefs.cpuMode) {
            2 -> throttleJs(30, 100)
            3 -> throttleJs(15, 400)
            else -> return
        }
        view.evaluateJavascript(js, null)
    }

    // ---------------------------------------------------------------- creation

    @SuppressLint("ClickableViewAccessibility")
    fun create(ctx: Context, tab: Tab, host: BrowserHost): WebView {
        val wv = WebView(ctx)
        wv.setBackgroundColor(dev.deepstellar.browser.ui.Ui.color(ctx, R.color.void_black))
        wv.isVerticalScrollBarEnabled = true
        applySettings(ctx, wv, tab)

        val assets = WebViewAssetLoader.Builder()
            .setDomain(ASSET_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
            // Start-page settings and your shortcuts, served from memory (other sites can't read it: no CORS).
            .addPathHandler("/data/", WebViewAssetLoader.PathHandler { path ->
                if (path == "start.json") {
                    WebResourceResponse("application/json", "utf-8",
                        ByteArrayInputStream(StartShortcuts.json().toByteArray(Charsets.UTF_8)))
                } else null
            })
            .build()

        wv.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return when (uri.scheme?.lowercase()) {
                    "dsb" -> { host.onSpecialUrl(tab, uri); true }
                    "magnet" -> { host.onMagnet(uri.toString()); true }
                    "http", "https" -> {
                        if (!request.isForMainFrame) return false
                        val original = uri.toString()
                        var target = original
                        // 1) Unwrap tracking redirects and strip tracking parameters.
                        val cleaned = UrlCleaner.clean(original)
                        if (cleaned != original && !(request.isRedirect && cleaned == tab.lastCleaned)) {
                            // (the isRedirect check stops a loop if a server insists on re-adding them)
                            target = cleaned
                            tab.lastCleaned = cleaned
                        }
                        // 2) HTTPS-only upgrade.
                        val targetHost = Uri.parse(target).host
                        if (target.startsWith("http://") && Prefs.httpsOnly && targetHost != tab.httpAllowedHost) {
                            target = "https://" + target.removePrefix("http://")
                        }
                        if (target != original) {
                            view.loadUrl(target, TabManager.extraHeaders())
                            true
                        } else false
                    }
                    "about", "data", "blob", "javascript" -> false
                    else -> { host.onExternalIntent(uri.toString()); true }
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (uri.host == ASSET_HOST) return assets.shouldInterceptRequest(uri)
                if (request.isForMainFrame) {
                    // New page: reset the inspector logs and judge first/third party against it.
                    tab.net.clear()
                    tab.console.clear()
                    tab.pageHost = uri.host
                }
                val verdict = AdBlocker.check(uri, tab.pageHost, request.isForMainFrame)
                val reqHost = uri.host ?: ""
                tab.net.add(
                    RequestLog.Entry(
                        time = System.currentTimeMillis(),
                        method = request.method ?: "GET",
                        url = uri.toString().take(600),
                        host = reqHost,
                        type = RequestLog.guessType(uri, request.requestHeaders?.get("Accept"), request.isForMainFrame),
                        thirdParty = DomainUtil.isThirdParty(reqHost, tab.pageHost),
                        mainFrame = request.isForMainFrame,
                        verdict = verdict,
                    )
                )
                if (verdict != AdBlocker.Verdict.ALLOW) {
                    tab.blockedOnPage++
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                tab.url = url
                tab.pageHost = Uri.parse(url).host
                tab.blockedOnPage = 0
                tab.readerMode = false
                tab.progress = 0
                host.onTabUpdated(tab)
                fallbackThrottle(view, tab)
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                injectCosmetic(view, tab)
            }

            override fun onPageFinished(view: WebView, url: String) {
                tab.url = url
                tab.progress = 100
                injectCosmetic(view, tab)
                HistoryStore.record(url, view.title ?: tab.title)
                host.onTabUpdated(tab)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                val u = request.url
                // HTTPS-only upgrade failed → offer to load the HTTP version.
                if (Prefs.httpsOnly && u.scheme == "https" && u.host != tab.httpAllowedHost &&
                    (error.errorCode == WebViewClient.ERROR_CONNECT || error.errorCode == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE ||
                        error.errorCode == WebViewClient.ERROR_HOST_LOOKUP || error.errorCode == WebViewClient.ERROR_TIMEOUT)
                ) {
                    host.onHttpsUpgradeFailed(tab, u.buildUpon().scheme("http").build().toString())
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                // Never silently accept a bad certificate.
                handler.cancel()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                host.onRendererGone(tab)
                return true
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                tab.progress = newProgress
                host.onTabUpdated(tab)
            }

            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                tab.console.add(
                    ConsoleLog.Entry(
                        msg.messageLevel()?.name?.lowercase() ?: "log",
                        msg.message() ?: "",
                        msg.sourceId() ?: "",
                        msg.lineNumber(),
                    )
                )
                return true // keep page logs out of the system log
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                tab.title = title ?: ""
                view.url?.let { HistoryStore.updateTitle(it, tab.title) }
                host.onTabUpdated(tab)
            }

            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) =
                host.onShowCustomView(view, callback)

            override fun onHideCustomView() = host.onHideCustomView()

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                // Pop-ups that weren't triggered by a tap are dropped.
                if (!isUserGesture) return false
                return host.onNewWindow(tab, resultMsg)
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams,
            ): Boolean = host.onFileChooser(filePathCallback, fileChooserParams)

            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                // Camera / microphone / MIDI etc. are refused by default.
                request.deny()
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: android.webkit.GeolocationPermissions.Callback?,
            ) {
                callback?.invoke(origin, false, false)
            }
        }

        wv.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            host.onDownloadRequested(tab, url, userAgent ?: mobileUa(ctx), contentDisposition, mimetype, contentLength)
        }

        wv.setOnLongClickListener { v ->
            val r = (v as WebView).hitTestResult
            host.onLongPress(tab, r)
        }

        tab.webView = wv
        applyThrottle(tab)
        return wv
    }

    private fun injectCosmetic(view: WebView, tab: Tab) {
        if (Prefs.adblock && Prefs.cosmetic && !AdBlocker.isSiteAllowed(tab.pageHost) && !tab.isStartPage) {
            view.evaluateJavascript(AdBlocker.cosmeticJs, null)
        }
    }
}
