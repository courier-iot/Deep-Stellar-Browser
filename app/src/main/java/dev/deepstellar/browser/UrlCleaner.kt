package dev.deepstellar.browser

import android.net.Uri

/**
 * Cleans links before they load:
 *  1. Unwraps "click-through" redirects (Google, Facebook, DuckDuckGo, YouTube,
 *     Reddit, Steam, LinkedIn …) that log what you tapped before sending you on.
 *  2. Removes tracking parameters (utm_*, fbclid, gclid, …).
 * Only the tracking pieces are removed — the rest of the address is left byte-for-byte.
 */
object UrlCleaner {

    val enabled: Boolean get() = Prefs.raw.getBoolean("strip_trackers", true)

    /** Parameters removed everywhere. */
    private val EXACT = setOf(
        "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "twclid", "ttclid",
        "li_fat_id", "igshid", "igsh", "mc_cid", "mc_eid", "_hsenc", "_hsmi", "hsctatracking", "mkt_tok",
        "oly_anon_id", "oly_enc_id", "rb_clickid", "s_cid", "vero_id", "vero_conv", "wickedid", "__s",
        "_openstat", "zanpid", "ncid", "sr_share", "ref_src", "ref_url", "cmpid", "irclickid", "epik",
        "spm", "scm", "pvid", "algo_pvid", "algo_expid", "_branch_match_id", "_branch_referrer",
    )
    private val PREFIXES = listOf("utm_", "pk_", "mtm_", "piwik_", "matomo_", "hmb_", "ga_", "__hs")

    /** Parameters removed only on specific sites (where they are tracking, not content). */
    private val PER_SITE = mapOf(
        "youtube.com" to setOf("si", "feature", "pp"),
        "youtu.be" to setOf("si", "feature"),
        "amazon.com" to setOf("ref", "ref_", "pf_rd_r", "pf_rd_p", "pd_rd_r", "pd_rd_w", "pd_rd_wg"),
        "twitter.com" to setOf("s", "t", "ref_src"),
        "x.com" to setOf("s", "t", "ref_src"),
        "instagram.com" to setOf("igshid", "igsh", "utm_source"),
        "spotify.com" to setOf("si", "context"),
        "tiktok.com" to setOf("is_from_webapp", "sender_device", "sender_web_id", "_r", "_t"),
        "reddit.com" to setOf("share_id", "rdt"),
        "linkedin.com" to setOf("trk", "trkInfo", "lipi", "midToken", "midSig", "trackingId"),
    )

    fun clean(url: String): String {
        if (!enabled) return url
        var u = unwrap(url)
        u = stripParams(u)
        return u
    }

    // ------------------------------------------------------------ redirects

    private fun unwrap(url: String, depth: Int = 0): String {
        if (depth > 3) return url
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        val host = uri.host?.lowercase() ?: return url
        val path = uri.path ?: ""
        fun q(name: String) = runCatching { uri.getQueryParameter(name) }.getOrNull()

        val target: String? = when {
            (host.startsWith("google.") || host.contains(".google.")) && path == "/url" -> q("q") ?: q("url")
            host == "l.facebook.com" || host == "lm.facebook.com" || host == "l.messenger.com" -> q("u")
            host == "l.instagram.com" -> q("u")
            host.endsWith("duckduckgo.com") && path.startsWith("/l/") -> q("uddg")
            host.endsWith("youtube.com") && path == "/redirect" -> q("q")
            host == "out.reddit.com" -> q("url")
            host == "steamcommunity.com" && path.startsWith("/linkfilter") -> q("url") ?: q("u")
            host.endsWith("linkedin.com") && path.startsWith("/redir/redirect") -> q("url")
            host == "href.li" -> uri.encodedQuery
            host.endsWith("bing.com") && path == "/ck/a" -> null // encoded; leave as is
            else -> null
        }
        if (target != null && (target.startsWith("http://") || target.startsWith("https://"))) {
            return unwrap(target, depth + 1)
        }
        return url
    }

    // ------------------------------------------------------------ parameters

    private fun stripParams(url: String): String {
        val qStart = url.indexOf('?')
        if (qStart < 0) return url
        val hashStart = url.indexOf('#', qStart).let { if (it < 0) url.length else it }
        val base = url.substring(0, qStart)
        val query = url.substring(qStart + 1, hashStart)
        val fragment = url.substring(hashStart)
        if (query.isEmpty()) return url

        val host = runCatching { Uri.parse(base).host?.lowercase() }.getOrNull() ?: ""
        val siteSet = PER_SITE.entries.firstOrNull { (domain, _) -> host == domain || host.endsWith(".$domain") }?.value

        val kept = query.split('&').filter { pair ->
            if (pair.isEmpty()) return@filter false
            val key = Uri.decode(pair.substringBefore('=')).lowercase()
            !(key in EXACT || PREFIXES.any { key.startsWith(it) } || (siteSet != null && key in siteSet))
        }
        if (kept.size == query.split('&').count { it.isNotEmpty() }) return url
        return base + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + fragment
    }
}
