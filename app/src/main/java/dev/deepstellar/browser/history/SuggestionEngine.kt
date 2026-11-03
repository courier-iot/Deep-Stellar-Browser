package dev.deepstellar.browser.history

import dev.deepstellar.browser.Prefs
import dev.deepstellar.browser.bookmarks.BookmarkStore

/**
 * Address-bar suggestions from your bookmarks and (if turned on) your encrypted
 * history. Everything is matched on the phone — nothing you type is sent to a
 * search engine until you press Go.
 */
object SuggestionEngine {

    data class Suggestion(val title: String, val url: String, val bookmarked: Boolean, val visits: Int)

    private class Candidate(val url: String, var title: String) {
        var bookmarked = false
        var visits = 0
        var lastVisit = 0L
    }

    val enabled: Boolean get() = Prefs.raw.getBoolean("suggest_enabled", true)

    fun suggest(query: String, limit: Int = 6): List<Suggestion> {
        if (!enabled) return emptyList()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val terms = q.split(Regex("\\s+")).filter { it.isNotEmpty() }

        val byUrl = LinkedHashMap<String, Candidate>()
        for (b in BookmarkStore.all()) {
            byUrl.getOrPut(b.url) { Candidate(b.url, b.title) }.bookmarked = true
        }
        if (HistoryStore.enabled && HistoryStore.loaded && Prefs.raw.getBoolean("suggest_history", true)) {
            HistoryStore.forEachEntry { e ->
                val c = byUrl.getOrPut(e.url) { Candidate(e.url, e.title) }
                c.visits++
                if (e.time > c.lastVisit) c.lastVisit = e.time
                if (c.title.isBlank() && e.title.isNotBlank()) c.title = e.title
            }
        }

        val now = System.currentTimeMillis()
        return byUrl.values.mapNotNull { c ->
            val u = bare(c.url)
            val t = c.title.lowercase()
            // Every word you typed must appear in the address or the title.
            if (!terms.all { u.contains(it) || t.contains(it) }) return@mapNotNull null

            var score = 0
            score += when {
                u.startsWith(q) -> 100                       // "wiki" → wikipedia.org
                wordStart(u, terms[0]) -> 60                  // "news" → news.ycombinator.com, x.com/news
                wordStart(t, terms[0]) -> 45
                else -> 20
            }
            if (c.bookmarked) score += 30
            score += minOf(c.visits, 10) * 3
            if (c.lastVisit > now - 86_400_000L) score += 10
            if (u.indexOf('/') in 0 until u.length - 1) score -= 5 // prefer homepages over deep links
            score to c
        }
            .sortedByDescending { it.first }
            .take(limit)
            .map { (_, c) -> Suggestion(c.title.ifBlank { bare(c.url) }, c.url, c.bookmarked, c.visits) }
    }

    /** "https://www.example.com/a" → "example.com/a" */
    fun bare(url: String): String = url.lowercase()
        .removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

    private fun wordStart(s: String, term: String): Boolean {
        var i = s.indexOf(term)
        while (i >= 0) {
            if (i == 0 || !s[i - 1].isLetterOrDigit()) return true
            i = s.indexOf(term, i + 1)
        }
        return false
    }
}
