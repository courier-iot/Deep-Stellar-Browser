package dev.deepstellar.browser

import org.json.JSONArray
import org.json.JSONObject

/**
 * Your own start-page shortcuts, plus the switches for the two start-page
 * sections (Recommended and Your shortcuts). Kept in settings, so they're part
 * of encrypted backups. No icons are fetched — tiles show the first letter,
 * so the start page never contacts those sites.
 */
object StartShortcuts {

    data class Shortcut(val name: String, val url: String)

    const val MAX = 32
    private const val KEY = "start_shortcuts"

    val showRecommended: Boolean get() = Prefs.raw.getBoolean("start_show_rec", true)
    val showMine: Boolean get() = Prefs.raw.getBoolean("start_show_mine", true)

    fun all(): List<Shortcut> = runCatching {
        val arr = JSONArray(Prefs.raw.getString(KEY, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Shortcut(o.optString("n"), o.getString("u")) } }
    }.getOrDefault(emptyList())

    private fun save(list: List<Shortcut>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("n", it.name).put("u", it.url)) }
        Prefs.raw.edit().putString(KEY, arr.toString()).apply()
    }

    fun indexOf(url: String): Int = all().indexOfFirst { it.url == url }

    /** Returns false if the list is full. */
    fun add(s: Shortcut): Boolean {
        val l = all().toMutableList()
        if (l.size >= MAX) return false
        l.removeAll { it.url == s.url }
        l += s
        save(l)
        return true
    }

    fun update(index: Int, s: Shortcut) {
        val l = all().toMutableList()
        if (index !in l.indices) return
        l[index] = s
        save(l)
    }

    fun remove(index: Int) {
        val l = all().toMutableList()
        if (index !in l.indices) return
        l.removeAt(index)
        save(l)
    }

    fun move(index: Int, delta: Int) {
        val l = all().toMutableList()
        val to = index + delta
        if (index !in l.indices || to !in l.indices) return
        java.util.Collections.swap(l, index, to)
        save(l)
    }

    /** What the start page reads from https://appassets.androidplatform.net/data/start.json */
    fun json(): String {
        val mine = JSONArray()
        all().forEach { mine.put(JSONObject().put("name", it.name).put("url", it.url)) }
        return JSONObject()
            .put("showRec", showRecommended)
            .put("showMine", showMine)
            .put("max", MAX)
            .put("mine", mine)
            .toString()
    }

    /** Normalises what someone typed into an address; null if it isn't a web address. */
    fun normaliseUrl(input: String): String? {
        val t = input.trim()
        if (t.isEmpty() || t.contains(' ')) return null
        val u = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) t else "https://$t"
        return u.takeIf { (it.startsWith("https://", true) || it.startsWith("http://", true)) && it.substringAfter("://").contains('.') }
    }

    /** A tidy default name from an address: "news.ycombinator.com/x" → "Ycombinator". */
    fun defaultName(url: String): String {
        val host = android.net.Uri.parse(url).host?.removePrefix("www.") ?: return url
        val core = dev.deepstellar.browser.adblock.DomainUtil.registrable(host).substringBefore('.')
        return core.replaceFirstChar { it.uppercase() }
    }
}
