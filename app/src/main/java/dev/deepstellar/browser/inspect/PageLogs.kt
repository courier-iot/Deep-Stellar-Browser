package dev.deepstellar.browser.inspect

import android.net.Uri
import dev.deepstellar.browser.adblock.AdBlocker
import dev.deepstellar.browser.adblock.DomainUtil

/**
 * Per-tab, per-page logs for the "Under the hood" screen.
 * Memory only — cleared on every new page and when the tab closes.
 */
class RequestLog {
    data class Entry(
        val time: Long,
        val method: String,
        val url: String,
        val host: String,
        val type: String,
        val thirdParty: Boolean,
        val mainFrame: Boolean,
        val verdict: AdBlocker.Verdict,
    ) {
        val blocked get() = verdict != AdBlocker.Verdict.ALLOW
        val reason: String get() = when (verdict) {
            AdBlocker.Verdict.ALLOW -> "allowed"
            AdBlocker.Verdict.BLOCK_LIST -> "filter list"
            AdBlocker.Verdict.BLOCK_USER -> "your block list"
            AdBlocker.Verdict.BLOCK_LEARNED -> "learning model"
        }
    }

    private val entries = ArrayList<Entry>()

    @Synchronized fun add(e: Entry) {
        if (entries.size >= MAX) entries.removeAt(0)
        entries += e
    }

    @Synchronized fun clear() = entries.clear()
    @Synchronized fun snapshot(): List<Entry> = entries.toList()

    companion object {
        private const val MAX = 2000

        /** Best-effort resource type from the Accept header and file extension. */
        fun guessType(uri: Uri, accept: String?, mainFrame: Boolean): String {
            if (mainFrame) return "document"
            val path = (uri.path ?: "").lowercase()
            val ext = path.substringAfterLast('.', "").take(5)
            return when {
                ext in setOf("js", "mjs") -> "script"
                ext == "css" -> "style"
                ext in setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif") -> "image"
                ext in setOf("woff", "woff2", "ttf", "otf") -> "font"
                ext in setOf("mp4", "webm", "m3u8", "mp3", "m4a", "ts") -> "media"
                ext == "json" -> "data"
                accept == null -> "other"
                accept.startsWith("text/css") -> "style"
                accept.startsWith("image/") -> "image"
                accept.contains("text/html") -> "frame"
                accept.contains("application/json") -> "data"
                else -> "other"
            }
        }
    }
}

class ConsoleLog {
    data class Entry(val level: String, val message: String, val source: String, val line: Int)

    private val entries = ArrayList<Entry>()

    @Synchronized fun add(e: Entry) {
        if (entries.size >= 500) entries.removeAt(0)
        entries += e
    }

    @Synchronized fun clear() = entries.clear()
    @Synchronized fun snapshot(): List<Entry> = entries.toList()
}

/** Groups a page's requests by the company/domain they went to. */
object TrackerReport {
    data class Party(val domain: String, val requests: Int, val blocked: Int, val reasons: Set<String>, val types: Set<String>)

    fun build(entries: List<RequestLog.Entry>): List<Party> =
        entries.filter { it.thirdParty }
            .groupBy { DomainUtil.registrable(it.host) }
            .map { (domain, list) ->
                Party(
                    domain,
                    list.size,
                    list.count { it.blocked },
                    list.filter { it.blocked }.map { it.reason }.toSet(),
                    list.map { it.type }.toSet(),
                )
            }
            .sortedWith(compareByDescending<Party> { it.blocked > 0 }.thenByDescending { it.requests })
}
