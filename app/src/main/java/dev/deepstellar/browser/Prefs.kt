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
        // "Keep me logged in" was removed; forget any list an earlier build saved.
        if (sp.contains("keep_sites")) sp.edit().remove("keep_sites").apply()
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

    fun searchUrl(query: String): String {
        val q = android.net.Uri.encode(query)
        return when (searchEngine) {
            "ddg_lite" -> "https://lite.duckduckgo.com/lite/?q=$q"
            "brave" -> "https://search.brave.com/search?q=$q"
            "startpage" -> "https://www.startpage.com/do/search?q=$q"
            "mojeek" -> "https://www.mojeek.com/search?q=$q"
            "searx" -> "$searxUrl/search?q=$q"
            "custom" -> (sp.getString("custom_search_url", "")?.takeIf { it.contains("%s") && it.startsWith("http") }
                ?.replace("%s", q)) ?: "https://duckduckgo.com/?q=$q&kp=-2&kl=wt-wt"
            else -> "https://duckduckgo.com/?q=$q&kp=-2&kl=wt-wt"
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
