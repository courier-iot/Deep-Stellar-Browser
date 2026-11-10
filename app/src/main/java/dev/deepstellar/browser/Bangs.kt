package dev.deepstellar.browser

import android.net.Uri
import org.json.JSONObject

/**
 * Search shortcuts ("bangs"), resolved on the phone — no search engine sees the redirect.
 *
 *   !gh rust        → GitHub search for "rust"
 *   gh rust         → same, if "keywords without !" is on (keywords of 2+ letters)
 *   !w black hole   → Wikipedia
 *   !aw example.com → that page on the Wayback Machine
 *
 * Built-ins can be overridden or extended with your own in Settings.
 */
object Bangs {

    /** keyword → URL template, %s is replaced by the (encoded) query. */
    val BUILT_IN: Map<String, String> = linkedMapOf(
        "w" to "https://en.wikipedia.org/w/index.php?search=%s",
        "gh" to "https://github.com/search?q=%s",
        "yt" to "https://www.youtube.com/results?search_query=%s",
        "r" to "https://www.reddit.com/search/?q=%s",
        "so" to "https://stackoverflow.com/search?q=%s",
        "mdn" to "https://developer.mozilla.org/en-US/search?q=%s",
        "osm" to "https://www.openstreetmap.org/search?query=%s",
        "aw" to "https://web.archive.org/web/*/%s",
        "ddg" to "https://duckduckgo.com/?q=%s",
        "b" to "https://search.brave.com/search?q=%s",
        "sp" to "https://www.startpage.com/do/search?q=%s",
        "arch" to "https://wiki.archlinux.org/index.php?search=%s",
        "fd" to "https://search.f-droid.org/?q=%s",
        "npm" to "https://www.npmjs.com/search?q=%s",
        "pypi" to "https://pypi.org/search/?q=%s",
        "wa" to "https://www.wolframalpha.com/input?i=%s",
        "imdb" to "https://www.imdb.com/find/?q=%s",
    )

    val enabled: Boolean get() = Prefs.raw.getBoolean("bangs_enabled", true)
    private val bare: Boolean get() = Prefs.raw.getBoolean("bangs_bare", false)

    fun custom(): Map<String, String> = runCatching {
        val o = JSONObject(Prefs.raw.getString("bangs_custom", "{}")!!)
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    fun setCustom(keyword: String, template: String?) {
        val o = runCatching { JSONObject(Prefs.raw.getString("bangs_custom", "{}")!!) }.getOrDefault(JSONObject())
        if (template == null) o.remove(keyword) else o.put(keyword, template)
        Prefs.raw.edit().putString("bangs_custom", o.toString()).apply()
    }

    fun all(): Map<String, String> = BUILT_IN + custom()

    /** Returns the address to open, or null if [input] isn't a shortcut. */
    fun resolve(input: String): String? {
        if (!enabled) return null
        val text = input.trim()
        val space = text.indexOf(' ')
        if (space <= 0 || space == text.length - 1) return null
        var key = text.substring(0, space).lowercase()
        val query = text.substring(space + 1).trim()
        val explicit = key.startsWith("!")
        if (explicit) key = key.substring(1)
        if (!explicit && !bare) return null
        // One-letter keywords need the "!" so ordinary sentences ("r u there") aren't hijacked.
        if (!explicit && key.length < 2) return null
        val template = all()[key] ?: return null
        // The Wayback Machine wants the address as-is, everything else wants it encoded.
        val q = if (template.contains("web.archive.org/web/")) query else Uri.encode(query)
        return template.replace("%s", q)
    }
}
