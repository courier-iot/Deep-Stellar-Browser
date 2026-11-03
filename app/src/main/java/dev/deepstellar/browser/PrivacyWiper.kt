package dev.deepstellar.browser

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import java.io.File

/**
 * Wipes everything the web engine might have written to disk:
 * cookies, cache, local/session storage, IndexedDB, service workers, form data,
 * HTTP auth. Runs at launch (in case of a crash last time) and at exit.
 *
 * Deep Stellar itself never writes a history list anywhere.
 */
object PrivacyWiper {

    fun wipeWebData(context: Context, webView: WebView? = null) {
        runCatching {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.removeSessionCookies(null)
            cm.flush()
        }
        runCatching { WebStorage.getInstance().deleteAllData() }
        runCatching {
            WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
        }
        runCatching {
            webView?.clearCache(true)
            webView?.clearHistory()
            webView?.clearFormData()
            webView?.clearSslPreferences()
        }
        // The engine is live here, so only plain cache folders are touched.
        wipeDiskCaches(context, includeEngineProfile = false)
    }

    /**
     * Deletes cache folders directly. [includeEngineProfile] must only be true
     * before any WebView has been created in this process (i.e. at app start).
     */
    fun wipeDiskCaches(context: Context, includeEngineProfile: Boolean = true) {
        runCatching { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
        if (!includeEngineProfile) return
        runCatching { context.codeCacheDir.listFiles()?.forEach { it.deleteRecursively() } }
        // WebView keeps its profile under app_webview. Only storage-type folders
        // are removed; the engine recreates them.
        val dataRoot = context.filesDir.parentFile ?: return
        val webviewDir = File(dataRoot, "app_webview")
        listOf(
            "Default/Cache", "Default/Code Cache", "Default/GPUCache", "Default/Service Worker",
            "Default/Local Storage", "Default/Session Storage", "Default/IndexedDB",
            "Default/databases", "Default/Web Data", "Default/Web Data-journal",
            "Default/History", "Default/History-journal", "Default/Visited Links",
            "Default/Cookies", "Default/Cookies-journal",
        ).forEach { rel -> runCatching { File(webviewDir, rel).deleteRecursively() } }
    }
}
