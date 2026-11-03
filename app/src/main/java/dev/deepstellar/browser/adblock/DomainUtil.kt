package dev.deepstellar.browser.adblock

object DomainUtil {
    // Common two-part public suffixes. Not exhaustive, good enough for
    // first-party/third-party decisions.
    private val TWO_PART = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "co.nz", "co.jp",
        "ne.jp", "or.jp", "co.kr", "co.in", "com.br", "com.mx", "com.ar", "com.cn", "com.tw",
        "com.hk", "com.sg", "com.tr", "co.za", "com.ua", "co.il", "com.pl", "gc.ca", "qc.ca",
        "github.io", "gitlab.io", "blogspot.com", "herokuapp.com", "cloudfront.net",
        "appspot.com", "azurewebsites.net", "netlify.app", "vercel.app", "pages.dev",
    )

    fun registrable(host: String): String {
        val h = host.lowercase().trimEnd('.')
        val parts = h.split('.')
        if (parts.size <= 2) return h
        val lastTwo = parts.takeLast(2).joinToString(".")
        return if (lastTwo in TWO_PART) parts.takeLast(3).joinToString(".") else lastTwo
    }

    fun isThirdParty(host: String, pageHost: String?): Boolean {
        if (pageHost.isNullOrEmpty()) return false
        return registrable(host) != registrable(pageHost)
    }

    /** host itself, then each parent: a.b.example.com, b.example.com, example.com */
    inline fun forEachParent(host: String, block: (String) -> Boolean): Boolean {
        var h = host
        while (true) {
            if (block(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0 || dot == h.length - 1) return false
            h = h.substring(dot + 1)
            if (!h.contains('.')) return false
        }
    }
}
