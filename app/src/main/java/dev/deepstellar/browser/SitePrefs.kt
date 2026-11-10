package dev.deepstellar.browser

import dev.deepstellar.browser.adblock.DomainUtil
import org.json.JSONObject

/**
 * Per-site choices: camera / microphone / location permission, JavaScript on or off,
 * desktop mode and text zoom. Keyed by the site's main domain (so news.example.com
 * and example.com share one entry). Stored in settings, so it's part of encrypted backups.
 */
object SitePrefs {
    const val ASK = 0
    const val ALLOW = 1
    const val BLOCK = 2

    const val CAMERA = "cam"
    const val MIC = "mic"
    const val LOCATION = "loc"

    private const val KEY = "site_prefs"
    @Volatile private var cache: JSONObject? = null

    private fun root(): JSONObject {
        cache?.let { return it }
        val o = runCatching { JSONObject(Prefs.raw.getString(KEY, "{}")!!) }.getOrDefault(JSONObject())
        cache = o
        return o
    }

    private fun save(o: JSONObject) {
        cache = o
        Prefs.raw.edit().putString(KEY, o.toString()).apply()
    }

    /** Call after a backup restore or "clear all". */
    fun invalidate() { cache = null }

    fun site(host: String?): String? = host?.takeIf { it.isNotBlank() }?.let { DomainUtil.registrable(it) }

    private fun entry(host: String?): JSONObject? = site(host)?.let { root().optJSONObject(it) }

    @Synchronized
    private fun edit(host: String?, block: (JSONObject) -> Unit) {
        val s = site(host) ?: return
        val r = root()
        val e = r.optJSONObject(s) ?: JSONObject()
        block(e)
        if (e.length() == 0) r.remove(s) else r.put(s, e)
        save(r)
    }

    // ---- permissions
    fun permission(host: String?, kind: String): Int = entry(host)?.optInt(kind, ASK) ?: ASK
    fun setPermission(host: String?, kind: String, value: Int) = edit(host) { if (value == ASK) it.remove(kind) else it.put(kind, value) }

    // ---- JavaScript: null = follow the global setting
    fun javascript(host: String?): Boolean? = entry(host)?.takeIf { it.has("js") }?.optBoolean("js")
    fun setJavascript(host: String?, value: Boolean?) = edit(host) { if (value == null) it.remove("js") else it.put("js", value) }

    // ---- desktop mode: null = follow the tab
    fun desktop(host: String?): Boolean? = entry(host)?.takeIf { it.has("desktop") }?.optBoolean("desktop")
    fun setDesktop(host: String?, value: Boolean?) = edit(host) { if (value == null) it.remove("desktop") else it.put("desktop", value) }

    // ---- text zoom in percent: null = global text size
    fun zoom(host: String?): Int? = entry(host)?.takeIf { it.has("zoom") }?.optInt("zoom")
    fun setZoom(host: String?, value: Int?) = edit(host) { if (value == null) it.remove("zoom") else it.put("zoom", value) }

    /** Sites that may use real-time connections (WebRTC) directly: those allowed camera or microphone. */
    fun webRtcAllowedSites(): List<String> {
        val r = root()
        return r.keys().asSequence().filter { k ->
            val e = r.optJSONObject(k)
            e != null && (e.optInt(CAMERA) == ALLOW || e.optInt(MIC) == ALLOW)
        }.toList()
    }

    fun allSites(): List<Pair<String, String>> {
        val r = root()
        return r.keys().asSequence().sorted().map { k ->
            val e = r.optJSONObject(k) ?: JSONObject()
            val parts = mutableListOf<String>()
            fun perm(kind: String, label: String) {
                when (e.optInt(kind)) { ALLOW -> parts += "$label allowed"; BLOCK -> parts += "$label blocked" }
            }
            perm(CAMERA, "camera"); perm(MIC, "microphone"); perm(LOCATION, "location")
            if (e.has("js")) parts += if (e.optBoolean("js")) "JavaScript on" else "JavaScript off"
            if (e.has("desktop")) parts += if (e.optBoolean("desktop")) "desktop site" else "mobile site"
            if (e.has("zoom")) parts += "text ${e.optInt("zoom")}%"
            k to parts.joinToString(" · ")
        }.toList()
    }

    @Synchronized fun clear(site: String) {
        val r = root()
        r.remove(site)
        save(r)
    }

    @Synchronized fun clearAll() = save(JSONObject())
}
