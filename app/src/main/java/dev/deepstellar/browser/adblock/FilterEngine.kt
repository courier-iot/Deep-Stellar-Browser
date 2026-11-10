package dev.deepstellar.browser.adblock

import android.util.Log
import java.io.File

/**
 * Full filter rules (the EasyList / Adblock Plus syntax), on top of the simple
 * domain lists. This is what catches ads served from the same domain as the
 * content, e.g. "/ads/banner-*.js" or "||example.com/track?$third-party".
 *
 * Supported: || and | anchors, * wildcards, ^ separators, @@ exceptions, and the
 * options third-party, domain=, and resource types (script, image, stylesheet,
 * xmlhttprequest, subdocument, font, media, other). Rules that need features a
 * WebView can't provide (csp, redirect, removeparam, popup…) are skipped.
 * Page-element (cosmetic) rules are not handled here.
 *
 * Rules are indexed by a keyword so each request is only compared against a
 * handful of candidates.
 */
object FilterEngine {
    private const val TAG = "FilterEngine"

    const val T_SCRIPT = 1
    const val T_IMAGE = 2
    const val T_STYLE = 4
    const val T_XHR = 8
    const val T_FRAME = 16
    const val T_FONT = 32
    const val T_MEDIA = 64
    const val T_OTHER = 128

    fun typeBit(type: String): Int = when (type) {
        "script" -> T_SCRIPT
        "image" -> T_IMAGE
        "style" -> T_STYLE
        "data" -> T_XHR
        "frame" -> T_FRAME
        "font" -> T_FONT
        "media" -> T_MEDIA
        else -> T_OTHER
    }

    private class Rule(
        val pattern: String,
        /** 0 = any, 1 = third-party only, 2 = first-party only */
        val party: Int,
        val types: Int,
        val notTypes: Int,
        val domains: Array<String>?,
        val notDomains: Array<String>?,
    ) {
        private var compiled: Regex? = null
        private var broken = false

        fun regex(): Regex? {
            compiled?.let { return it }
            if (broken) return null
            return try {
                toRegex(pattern).also { compiled = it }
            } catch (e: Exception) {
                broken = true
                null
            }
        }
    }

    private class RuleSet {
        val domains = HashSet<String>()
        val byToken = HashMap<String, ArrayList<Rule>>()
        val generic = ArrayList<Rule>()
        var count = 0
    }

    @Volatile private var blocks = RuleSet()
    @Volatile private var exceptions = RuleSet()

    val ruleCount: Int get() = blocks.count + exceptions.count
    val ready: Boolean get() = blocks.count > 0

    private val TYPE_NAMES = mapOf(
        "script" to T_SCRIPT, "image" to T_IMAGE, "stylesheet" to T_STYLE, "css" to T_STYLE,
        "xmlhttprequest" to T_XHR, "xhr" to T_XHR, "subdocument" to T_FRAME, "frame" to T_FRAME,
        "font" to T_FONT, "media" to T_MEDIA, "other" to T_OTHER, "object" to T_OTHER,
        "websocket" to T_OTHER, "ping" to T_OTHER, "beacon" to T_OTHER,
    )

    // ------------------------------------------------------------ loading

    /**
     * Loads [file]. With a [cache], the parsed rules are kept in a compact binary file and
     * re-used on later starts until [file] changes, which is several times faster than
     * re-reading ~80,000 text rules on every launch.
     */
    fun load(file: File, cache: File? = null) {
        if (cache != null && file.exists() && readCache(file, cache)) return
        val b = RuleSet()
        val x = RuleSet()
        if (file.exists()) {
            try {
                file.bufferedReader().useLines { lines -> lines.forEach { parse(it, b, x) } }
            } catch (e: Exception) {
                Log.w(TAG, "load failed: ${e.message}")
            }
        }
        blocks = b
        exceptions = x
        Log.i(TAG, "Loaded ${b.count} block rules (${b.domains.size} plain domains), ${x.count} exceptions")
        if (cache != null) {
            if (file.exists() && b.count > 0) writeCache(file, cache) else cache.delete()
        }
    }

    // ------------------------------------------------------------ binary cache

    private const val CACHE_MAGIC = 0x44534652 // "DSFR"
    private const val CACHE_VERSION = 1

    private fun writeCache(source: File, cache: File) {
        runCatching {
            val tmp = File(cache.parentFile, cache.name + ".tmp")
            java.io.DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { out ->
                out.writeInt(CACHE_MAGIC)
                out.writeInt(CACHE_VERSION)
                out.writeLong(source.length())
                out.writeLong(source.lastModified())
                writeSet(out, blocks)
                writeSet(out, exceptions)
            }
            if (!tmp.renameTo(cache)) tmp.delete()
        }.onFailure { cache.delete() }
    }

    private fun writeSet(out: java.io.DataOutputStream, set: RuleSet) {
        out.writeInt(set.count)
        out.writeInt(set.domains.size)
        set.domains.forEach { out.writeUTF(it) }
        out.writeInt(set.generic.size)
        set.generic.forEach { writeRule(out, it) }
        out.writeInt(set.byToken.size)
        for ((token, rules) in set.byToken) {
            out.writeUTF(token)
            out.writeInt(rules.size)
            rules.forEach { writeRule(out, it) }
        }
    }

    private fun writeRule(out: java.io.DataOutputStream, r: Rule) {
        out.writeUTF(r.pattern)
        out.writeByte(r.party)
        out.writeInt(r.types)
        out.writeInt(r.notTypes)
        writeList(out, r.domains)
        writeList(out, r.notDomains)
    }

    private fun writeList(out: java.io.DataOutputStream, a: Array<String>?) {
        if (a == null) { out.writeInt(-1); return }
        out.writeInt(a.size)
        a.forEach { out.writeUTF(it) }
    }

    /** True if the cache matched [source] and was loaded. Any problem → false, and the text is parsed instead. */
    private fun readCache(source: File, cache: File): Boolean {
        if (!cache.exists()) return false
        return try {
            java.io.DataInputStream(cache.inputStream().buffered(1 shl 16)).use { inp ->
                if (inp.readInt() != CACHE_MAGIC || inp.readInt() != CACHE_VERSION) return false
                if (inp.readLong() != source.length() || inp.readLong() != source.lastModified()) return false
                val b = readSet(inp)
                val x = readSet(inp)
                blocks = b
                exceptions = x
                Log.i(TAG, "Loaded ${b.count} block rules and ${x.count} exceptions from cache")
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "cache unreadable, re-parsing: ${e.javaClass.simpleName}")
            cache.delete()
            false
        }
    }

    private fun readSet(inp: java.io.DataInputStream): RuleSet {
        val set = RuleSet()
        set.count = inp.readInt()
        repeat(inp.readInt()) { set.domains += inp.readUTF() }
        repeat(inp.readInt()) { set.generic += readRule(inp) }
        repeat(inp.readInt()) {
            val token = inp.readUTF()
            val n = inp.readInt()
            val list = ArrayList<Rule>(n)
            repeat(n) { list += readRule(inp) }
            set.byToken[token] = list
        }
        return set
    }

    private fun readRule(inp: java.io.DataInputStream): Rule {
        val pattern = inp.readUTF()
        val party = inp.readByte().toInt()
        val types = inp.readInt()
        val notTypes = inp.readInt()
        return Rule(pattern, party, types, notTypes, readList(inp), readList(inp))
    }

    private fun readList(inp: java.io.DataInputStream): Array<String>? {
        val n = inp.readInt()
        if (n < 0) return null
        return Array(n) { inp.readUTF() }
    }

    private fun parse(raw: String, blocks: RuleSet, exceptions: RuleSet) {
        var line = raw.trim()
        if (line.length < 4 || line[0] == '!' || line[0] == '[') return
        // Page-element rules: ##, #@#, #?#, #$#
        if (line.contains("##") || line.contains("#@#") || line.contains("#?#") || line.contains("#$#")) return

        val isException = line.startsWith("@@")
        if (isException) line = line.substring(2)
        if (line.startsWith("/") && line.endsWith("/")) return // raw regex rule

        var party = 0
        var types = 0
        var notTypes = 0
        var domains: Array<String>? = null
        var notDomains: Array<String>? = null

        val dollar = line.lastIndexOf('$')
        if (dollar >= 0 && dollar < line.length - 1 && !line.substring(dollar + 1).contains('/')) {
            val opts = line.substring(dollar + 1).split(',')
            line = line.substring(0, dollar)
            for (o0 in opts) {
                val o = o0.trim().lowercase()
                when {
                    o == "third-party" || o == "3p" -> party = 1
                    o == "~third-party" || o == "first-party" || o == "1p" || o == "~3p" -> party = 2
                    o.startsWith("domain=") -> {
                        val inc = ArrayList<String>()
                        val exc = ArrayList<String>()
                        o.substring(7).split('|').forEach { d ->
                            if (d.startsWith("~")) exc += d.substring(1) else if (d.isNotEmpty()) inc += d
                        }
                        if (inc.isNotEmpty()) domains = inc.toTypedArray()
                        if (exc.isNotEmpty()) notDomains = exc.toTypedArray()
                    }
                    o == "important" || o == "match-case" || o == "all" || o.isEmpty() -> {}
                    o.startsWith("~") && TYPE_NAMES.containsKey(o.substring(1)) -> notTypes = notTypes or TYPE_NAMES.getValue(o.substring(1))
                    TYPE_NAMES.containsKey(o) -> types = types or TYPE_NAMES.getValue(o)
                    // Unknown or unsupported option (csp, redirect, removeparam, popup…):
                    // skipping the rule is safer than applying it wrongly.
                    else -> return
                }
            }
        }
        line = line.lowercase()
        if (line.isEmpty() || line == "*" || line == "||" || line == "|") return

        val target = if (isException) exceptions else blocks

        // Fast path: "||domain.com^" with no options.
        if (party == 0 && types == 0 && notTypes == 0 && domains == null && notDomains == null &&
            line.startsWith("||") && line.endsWith("^")
        ) {
            val d = line.substring(2, line.length - 1)
            if (d.isNotEmpty() && d.all { it.isLetterOrDigit() || it == '.' || it == '-' } && d.contains('.')) {
                target.domains += d
                target.count++
                return
            }
        }

        val rule = Rule(line, party, types, notTypes, domains, notDomains)
        val token = bestToken(line)
        if (token == null) target.generic += rule else target.byToken.getOrPut(token) { ArrayList(1) } += rule
        target.count++
    }

    private fun isTokenChar(c: Char) = (c in 'a'..'z') || (c in '0'..'9') || c == '%'

    /**
     * The longest keyword in the rule that is guaranteed to appear as a whole keyword in any
     * matching address (so it can't be next to a wildcard or hang off the rule's open ends).
     */
    private fun bestToken(pattern: String): String? {
        var body = pattern
        val anchoredStart = body.startsWith("|")
        body = body.trimStart('|')
        val anchoredEnd = body.endsWith("|")
        if (anchoredEnd) body = body.dropLast(1)

        var best: String? = null
        var i = 0
        val n = body.length
        while (i < n) {
            if (!isTokenChar(body[i])) { i++; continue }
            val start = i
            while (i < n && isTokenChar(body[i])) i++
            val end = i
            val before = if (start == 0) (if (anchoredStart) '/' else '*') else body[start - 1]
            val after = if (end == n) (if (anchoredEnd) '/' else '*') else body[end]
            if (before == '*' || after == '*') continue
            val len = end - start
            if (len >= 3 && (best == null || len > best!!.length)) best = body.substring(start, end)
        }
        return best
    }

    private fun toRegex(pattern: String): Regex {
        var p = pattern
        val sb = StringBuilder()
        when {
            p.startsWith("||") -> { sb.append("^[a-z][a-z0-9+.-]*://(?:[^/?#]*\\.)?"); p = p.substring(2) }
            p.startsWith("|") -> { sb.append('^'); p = p.substring(1) }
        }
        val endAnchor = p.endsWith("|")
        if (endAnchor) p = p.dropLast(1)
        for (c in p) {
            when (c) {
                '*' -> sb.append(".*")
                '^' -> sb.append("(?:[^a-z0-9_.%-]|$)")
                '.', '?', '+', '(', ')', '[', ']', '{', '}', '\\', '$', '|' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        if (endAnchor) sb.append('$')
        return Regex(sb.toString())
    }

    // ------------------------------------------------------------ matching

    /** True if a block rule matches and no exception rule does. All strings lower-case. */
    fun shouldBlock(url: String, host: String, pageHost: String?, thirdParty: Boolean, type: Int): Boolean {
        val b = blocks
        if (b.count == 0) return false
        val tokens = tokenize(url)
        if (!matches(b, url, host, pageHost, thirdParty, type, tokens)) return false
        return !matches(exceptions, url, host, pageHost, thirdParty, type, tokens)
    }

    private fun tokenize(url: String): List<String> {
        val out = ArrayList<String>(24)
        var i = 0
        val n = minOf(url.length, 1200)
        while (i < n) {
            if (!isTokenChar(url[i])) { i++; continue }
            val start = i
            while (i < n && isTokenChar(url[i])) i++
            if (i - start >= 3) out += url.substring(start, i)
        }
        return out
    }

    private fun matches(
        set: RuleSet, url: String, host: String, pageHost: String?, thirdParty: Boolean, type: Int, tokens: List<String>,
    ): Boolean {
        if (set.domains.isNotEmpty() && DomainUtil.forEachParent(host) { it in set.domains }) return true
        for (t in tokens) {
            val bucket = set.byToken[t] ?: continue
            for (r in bucket) if (ruleMatches(r, url, pageHost, thirdParty, type)) return true
        }
        for (r in set.generic) if (ruleMatches(r, url, pageHost, thirdParty, type)) return true
        return false
    }

    private fun ruleMatches(r: Rule, url: String, pageHost: String?, thirdParty: Boolean, type: Int): Boolean {
        if (r.party == 1 && !thirdParty) return false
        if (r.party == 2 && thirdParty) return false
        // A rule limited to certain types only applies when we actually know the request's type.
        if (r.types != 0 && (type == T_OTHER && (r.types and T_OTHER) == 0 || (r.types and type) == 0)) return false
        if (r.notTypes != 0 && (r.notTypes and type) != 0) return false
        if (r.domains != null || r.notDomains != null) {
            val page = pageHost ?: return false
            r.notDomains?.let { list -> if (list.any { page == it || page.endsWith(".$it") }) return false }
            r.domains?.let { list -> if (list.none { page == it || page.endsWith(".$it") }) return false }
        }
        val re = r.regex() ?: return false
        return re.containsMatchIn(url)
    }
}
