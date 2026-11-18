package dev.deepstellar.browser

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Settings only. Nothing here records what you browse.
 */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        Migrations.run(sp)
    }

    val raw: SharedPreferences get() = sp

    // ---- Tabs ----
    /** Slider positions → tab caps. Last position = unlimited. */
    val TAB_STEPS = intArrayOf(4, 8, 12, 16, 32, 64, Int.MAX_VALUE)
    val maxTabs: Int
        get() = TAB_STEPS[sp.getInt("tab_limit_index", 3).coerceIn(0, TAB_STEPS.size - 1)]

    fun tabLabel(index: Int): String {
        val v = TAB_STEPS[index.coerceIn(0, TAB_STEPS.size - 1)]
        return if (v == Int.MAX_VALUE) "Unlimited" else "$v tabs"
    }

    // ---- RAM ----
    val RAM_STEPS_MB = intArrayOf(256, 512, 768, 1024, 1536, 2048, Int.MAX_VALUE)
    val ramBudgetMb: Int
        get() = RAM_STEPS_MB[sp.getInt("ram_limit_index", 3).coerceIn(0, RAM_STEPS_MB.size - 1)]

    fun ramLabel(index: Int): String {
        val v = RAM_STEPS_MB[index.coerceIn(0, RAM_STEPS_MB.size - 1)]
        return if (v == Int.MAX_VALUE) "Unlimited" else if (v >= 1024) "${v / 1024.0} GB".replace(".0 ", " ") else "$v MB"
    }

    // ---- CPU ----
    /** 0 = Full power, 1 = Balanced, 2 = Eco, 3 = Ultra-eco */
    val cpuMode: Int get() = sp.getInt("cpu_mode", 1).coerceIn(0, 3)
    val CPU_LABELS = arrayOf("Full power", "Balanced", "Eco", "Ultra-eco")

    // ---- Search ----
    val searchEngine: String get() = sp.getString("search_engine", "ddg") ?: "ddg"
    private val searxUrl: String get() = sp.getString("searx_url", "https://searx.be")?.trimEnd('/') ?: "https://searx.be"

    /**
     * Search region: which country's results come first. "none" = no region (the old behaviour).
     * This only changes the search address (and Brave's region cookie). It does not hide your
     * real location from websites; that would need a VPN or Tor.
     */
    private class Region(val ddg: String, val lang: String)

    /** Country code → DuckDuckGo region code + language tag (used by SearXNG). */
    private val REGIONS: Map<String, Region> = mapOf(
        "au" to Region("au-en", "en-AU"),
        "br" to Region("br-pt", "pt-BR"),
        "ca" to Region("ca-en", "en-CA"),
        "cn" to Region("cn-zh", "zh-CN"),
        "fr" to Region("fr-fr", "fr-FR"),
        "de" to Region("de-de", "de-DE"),
        "hk" to Region("hk-tzh", "zh-HK"),
        "in" to Region("in-en", "en-IN"),
        "it" to Region("it-it", "it-IT"),
        "jp" to Region("jp-jp", "ja-JP"),
        "mx" to Region("mx-es", "es-MX"),
        "nl" to Region("nl-nl", "nl-NL"),
        "pl" to Region("pl-pl", "pl-PL"),
        "ru" to Region("ru-ru", "ru-RU"),
        "kr" to Region("kr-kr", "ko-KR"),
        "es" to Region("es-es", "es-ES"),
        "se" to Region("se-sv", "sv-SE"),
        "tw" to Region("tw-tzh", "zh-TW"),
        "gb" to Region("uk-en", "en-GB"),
        "us" to Region("us-en", "en-US"),
    )

    val searchRegion: String get() = (sp.getString("search_region", "none") ?: "none").lowercase()

    fun searchUrl(query: String): String {
        val q = android.net.Uri.encode(query)
        val cc = searchRegion
        val region = REGIONS[cc]
        val ddgDefault = "https://duckduckgo.com/?q=$q&kp=-2&kl=${region?.ddg ?: "wt-wt"}"
        return when (searchEngine) {
            "ddg_lite" -> "https://lite.duckduckgo.com/lite/?q=$q" + (region?.let { "&kl=${it.ddg}" } ?: "")
            "brave" -> {
                setBraveRegion(if (region != null) cc else null)
                "https://search.brave.com/search?q=$q"
            }
            "startpage" -> "https://www.startpage.com/do/search?q=$q"
            "mojeek" -> "https://www.mojeek.com/search?q=$q"
            "searx" -> "$searxUrl/search?q=$q" + (region?.let { "&language=${it.lang}" } ?: "")
            "custom" -> (sp.getString("custom_search_url", "")?.takeIf { it.contains("%s") && it.startsWith("http") }
                ?.replace("%s", q)) ?: ddgDefault
            else -> ddgDefault
        }
    }

    /** Brave picks its region from a first-party cookie, not the address. Wiped on exit like every cookie. */
    private fun setBraveRegion(country: String?) {
        runCatching {
            val cm = android.webkit.CookieManager.getInstance()
            val value = if (country != null) "country=$country; Path=/; Secure" else "country=; Path=/; Secure; Max-Age=0"
            cm.setCookie("https://search.brave.com", value)
        }
    }

    // ---- Privacy ----
    val httpsOnly get() = sp.getBoolean("https_only", true)
    val javascript get() = sp.getBoolean("js_enabled", true)
    val blockThirdPartyCookies get() = sp.getBoolean("block_3p_cookies", true)
    val sendGpc get() = sp.getBoolean("send_gpc", true)
    val secureScreen get() = sp.getBoolean("secure_screen", false)
    val torProxy get() = sp.getBoolean("tor_proxy", false)
    val proxyHostPort: String get() = sp.getString("proxy_host", "127.0.0.1:8118") ?: "127.0.0.1:8118"
    val desktopDefault get() = sp.getBoolean("desktop_default", false)
    val starfield get() = sp.getBoolean("starfield", true)

    // ---- Ad shield ----
    val adblock get() = sp.getBoolean("adblock_enabled", true)
    val adLearning get() = sp.getBoolean("adblock_learning", true)
    val cosmetic get() = sp.getBoolean("adblock_cosmetic", true)
    var allowedSites: Set<String>
        get() = sp.getStringSet("ad_allowed_sites", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("ad_allowed_sites", v).apply()

    // ---- Torrents ----
    val torrentDownKBps: Int get() = sp.getString("torrent_down", "0")?.toIntOrNull() ?: 0
    val torrentUpKBps: Int get() = sp.getString("torrent_up", "0")?.toIntOrNull() ?: 0
    val torrentSeed get() = sp.getBoolean("torrent_seed", false)
}
