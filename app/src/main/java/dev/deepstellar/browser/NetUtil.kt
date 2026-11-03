package dev.deepstellar.browser

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

object NetUtil {
    const val UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    const val UA_DESKTOP =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    /** The proxy the whole app should use (Orbot/Tor or none). */
    fun proxy(): Proxy {
        if (!Prefs.torProxy) return Proxy.NO_PROXY
        val (h, p) = splitHostPort(Prefs.proxyHostPort) ?: return Proxy.NO_PROXY
        return Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(h, p))
    }

    fun splitHostPort(s: String): Pair<String, Int>? {
        val t = s.trim().removePrefix("http://")
        val i = t.lastIndexOf(':')
        if (i <= 0) return null
        val port = t.substring(i + 1).toIntOrNull() ?: return null
        return t.substring(0, i) to port
    }

    fun open(url: String, userAgent: String = UA_MOBILE): HttpURLConnection {
        val c = URL(url).openConnection(proxy()) as HttpURLConnection
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", userAgent)
        if (Prefs.sendGpc) {
            c.setRequestProperty("Sec-GPC", "1")
            c.setRequestProperty("DNT", "1")
        }
        return c
    }
}
