package dev.deepstellar.browser.adblock

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.deepstellar.browser.NetUtil
import dev.deepstellar.browser.Prefs
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Ad & tracker shield.
 *  1. Domain filter lists (bundled seed list + downloadable public lists).
 *  2. Your personal block list (things you've flagged).
 *  3. The self-learning classifier for third-party requests nobody has listed yet.
 *  4. Cosmetic hiding of common ad containers.
 */
object AdBlocker {
    private const val TAG = "AdBlocker"

    enum class Verdict { ALLOW, BLOCK_LIST, BLOCK_RULE, BLOCK_USER, BLOCK_LEARNED }

    val LIST_SOURCES = listOf(
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
    )

    /** Full-syntax rule lists (EasyList format), used when "Advanced filter rules" is on. */
    val RULE_SOURCES = listOf(
        "https://easylist.to/easylist/easylist.txt",
        "https://easylist.to/easylist/easyprivacy.txt",
    )
    private val advanced get() = Prefs.raw.getBoolean("adblock_rules", true)
    private val rulesFile get() = File(appContext.filesDir, "filters_abp.txt")
    val ruleCount: Int get() = FilterEngine.ruleCount

    @Volatile private var listDomains: Set<String> = emptySet()
    @Volatile private var userDomains: Set<String> = emptySet()
    private lateinit var appContext: Context
    private lateinit var learner: AdLearner
    private val io = Executors.newSingleThreadExecutor()
    private val sinceSave = AtomicInteger(0)

    val sessionBlocked = AtomicInteger(0)
    val sessionLearnedBlocks = AtomicInteger(0)
    val listSize: Int get() = listDomains.size
    val trainedExamples: Long get() = if (::learner.isInitialized) learner.trainedExamples else 0
    val learnerWarm: Boolean get() = ::learner.isInitialized && learner.isWarm

    private val filtersFile get() = File(appContext.filesDir, "filters.txt")
    private val userFile get() = File(appContext.filesDir, "user_block.txt")

    fun init(context: Context) {
        appContext = context.applicationContext
        io.execute {
            learner = AdLearner(File(appContext.filesDir, "ad_model.bin"))
            reloadLists()
        }
    }

    private fun reloadLists() {
        val set = HashSet<String>(200_000)
        runCatching {
            appContext.assets.open("blocklist.txt").bufferedReader().useLines { lines ->
                lines.forEach { parseLine(it)?.let(set::add) }
            }
        }
        if (filtersFile.exists()) runCatching {
            filtersFile.bufferedReader().useLines { lines -> lines.forEach { parseLine(it)?.let(set::add) } }
        }
        listDomains = set
        userDomains = if (userFile.exists()) userFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() else emptySet()
        Log.i(TAG, "Loaded ${set.size} list domains, ${userDomains.size} user domains")
        if (advanced) FilterEngine.load(rulesFile) else FilterEngine.load(File(appContext.filesDir, "none"))
    }

    /** Accepts hosts format, plain domains, and ABP "||domain^" rules. */
    private fun parseLine(raw: String): String? {
        var l = raw.trim()
        if (l.isEmpty() || l.startsWith("#") || l.startsWith("!") || l.startsWith("[")) return null
        if (l.startsWith("@@")) return null
        if (l.startsWith("||")) {
            val end = l.indexOfFirst { it == '^' || it == '/' || it == '$' }
            l = if (end > 2) l.substring(2, end) else l.substring(2)
            return l.takeIf { it.contains('.') && !it.contains('*') }?.lowercase()
        }
        val parts = l.split(Regex("\\s+"))
        val host = when {
            parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" || parts[0] == "::") -> parts[1]
            parts.size == 1 -> parts[0]
            else -> return null
        }
        if (host == "localhost" || host == "0.0.0.0" || !host.contains('.') || host.contains('*')) return null
        return host.lowercase()
    }

    fun isSiteAllowed(pageHost: String?): Boolean =
        pageHost != null && DomainUtil.registrable(pageHost) in Prefs.allowedSites

    /**
     * Called from WebView's network thread for every subresource.
     */
    fun check(url: Uri, pageHost: String?, isMainFrame: Boolean, type: String = "other"): Verdict {
        if (!Prefs.adblock || isMainFrame) return Verdict.ALLOW
        val host = url.host?.lowercase() ?: return Verdict.ALLOW
        if (isSiteAllowed(pageHost)) return Verdict.ALLOW

        val user = userDomains
        if (DomainUtil.forEachParent(host) { it in user }) return countBlock(Verdict.BLOCK_USER)

        val thirdParty = DomainUtil.isThirdParty(host, pageHost)
        val listed = DomainUtil.forEachParent(host) { it in listDomains }

        if (!::learner.isInitialized) return if (listed) countBlock(Verdict.BLOCK_LIST) else Verdict.ALLOW

        val urlStr = url.toString()
        if (!listed && FilterEngine.ready &&
            FilterEngine.shouldBlock(urlStr.lowercase(), host, pageHost?.lowercase(), thirdParty, FilterEngine.typeBit(type))
        ) {
            if (Prefs.adLearning && Random.nextInt(4) == 0) {
                learner.train(learner.features(urlStr, host, pageHost, thirdParty), 1f, 0.05f)
                maybeSave()
            }
            return countBlock(Verdict.BLOCK_RULE)
        }
        if (listed) {
            if (Prefs.adLearning && Random.nextInt(4) == 0) {
                learner.train(learner.features(urlStr, host, pageHost, thirdParty), 1f, 0.05f)
                maybeSave()
            }
            return countBlock(Verdict.BLOCK_LIST)
        }

        if (!Prefs.adLearning) return Verdict.ALLOW
        val f = learner.features(urlStr, host, pageHost, thirdParty)
        if (!thirdParty) {
            // First-party content is the "not an ad" teacher signal (sampled).
            if (Random.nextInt(5) == 0) {
                learner.train(f, 0f, 0.03f)
                maybeSave()
            }
            return Verdict.ALLOW
        }
        if (learner.isWarm && learner.score(f) >= LEARNED_THRESHOLD) {
            sessionLearnedBlocks.incrementAndGet()
            return countBlock(Verdict.BLOCK_LEARNED)
        }
        return Verdict.ALLOW
    }

    private fun countBlock(v: Verdict): Verdict {
        sessionBlocked.incrementAndGet()
        return v
    }

    private fun maybeSave() {
        if (sinceSave.incrementAndGet() >= 500) {
            sinceSave.set(0)
            io.execute { learner.save() }
        }
    }

    // ---------------- Your feedback ----------------

    /** "Block as ad" on an image/link/frame: teach the model hard and add the host to your list. */
    fun userFlagAd(url: String, pageHost: String?) {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: return
        io.execute {
            if (::learner.isInitialized) {
                val f = learner.features(url, host, pageHost, DomainUtil.isThirdParty(host, pageHost))
                repeat(8) { learner.train(f, 1f, 0.4f) }
                learner.save()
            }
            // Only add third-party hosts to the hard list; first-party paths are left to the model.
            if (DomainUtil.isThirdParty(host, pageHost)) {
                userDomains = userDomains + host
                runCatching { userFile.appendText(host + "\n") }
            }
        }
    }

    /** Adds a whole domain (and its subdomains) to your personal block list. */
    fun userBlockDomain(domain: String) = io.execute {
        val d = domain.lowercase().trim()
        if (d.isEmpty() || d in userDomains) return@execute
        userDomains = userDomains + d
        runCatching { userFile.appendText(d + "\n") }
    }

    fun isUserBlocked(domain: String): Boolean = domain.lowercase() in userDomains

    /** "This site broke" → stop filtering it, and nudge the model away from its resources. */
    fun toggleSiteAllowed(pageHost: String): Boolean {
        val reg = DomainUtil.registrable(pageHost)
        val cur = Prefs.allowedSites.toMutableSet()
        val nowAllowed = if (reg in cur) { cur.remove(reg); false } else { cur.add(reg); true }
        Prefs.allowedSites = cur
        return nowAllowed
    }

    fun clearUserList() = io.execute {
        userDomains = emptySet()
        userFile.delete()
    }

    /** Re-reads the lists from disk (after a setting changed). */
    fun reload() = io.execute { reloadLists() }

    fun resetLearning() = io.execute { if (::learner.isInitialized) learner.reset() }

    fun saveNow() = io.execute { if (::learner.isInitialized) learner.save() }

    /** Downloads the public filter lists. Calls back on a background thread. */
    fun updateLists(done: (ok: Boolean, count: Int) -> Unit) = io.execute {
        val sb = StringBuilder()
        var anyOk = false
        for (src in LIST_SOURCES) {
            runCatching {
                val conn = NetUtil.open(src)
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.inputStream.bufferedReader().use { r -> r.lineSequence().forEach { line ->
                    parseLine(line)?.let { sb.append(it).append('\n') }
                } }
                conn.disconnect()
                anyOk = true
            }.onFailure { Log.w(TAG, "List $src failed: ${it.message}") }
        }
        // Full-syntax rule lists are kept as downloaded (minus comments and page-element rules).
        if (advanced) {
            val rules = StringBuilder()
            var rulesOk = false
            for (src in RULE_SOURCES) {
                runCatching {
                    val conn = NetUtil.open(src)
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 45_000
                    conn.inputStream.bufferedReader().use { r -> r.lineSequence().forEach { line ->
                        if (line.isNotEmpty() && line[0] != '!' && line[0] != '[' && !line.contains("##") && !line.contains("#@#") && !line.contains("#?#")) {
                            rules.append(line).append('\n')
                        }
                    } }
                    conn.disconnect()
                    rulesOk = true
                    anyOk = true
                }.onFailure { Log.w(TAG, "Rules $src failed: ${it.message}") }
            }
            if (rulesOk) runCatching { rulesFile.writeText(rules.toString()) }
        }
        if (anyOk) {
            if (sb.isNotEmpty()) runCatching { filtersFile.writeText(sb.toString()) }
            reloadLists()
        }
        done(anyOk, listDomains.size)
    }

    // ---------------- Cosmetic filtering ----------------

    private const val COSMETIC_CSS =
        "ins.adsbygoogle,.adsbygoogle,[id^='google_ads_'],[id^='div-gpt-ad'],[id*='gpt-ad-']," +
        "[data-ad-slot],[data-ad-client],[data-google-query-id],.ad-slot,.ad-banner,.ad-container," +
        ".ad-wrapper,.adbox,.ad-unit,.ad-placeholder,.advertisement,.sponsored-content," +
        "[id^='taboola-'],.trc_related_container,.OUTBRAIN,[data-widget-id^='outbrain']," +
        "[aria-label='Advertisement'],[aria-label='advertisement'],iframe[src*='doubleclick.net']," +
        "iframe[src*='googlesyndication.com'],iframe[id^='google_ads_iframe']" +
        "{display:none!important;visibility:hidden!important;height:0!important;min-height:0!important}"

    /** JS that installs the cosmetic stylesheet once per document. */
    val cosmeticJs: String =
        "(function(){try{if(document.getElementById('__ds_cos'))return;" +
        "var s=document.createElement('style');s.id='__ds_cos';" +
        "s.textContent=\"$COSMETIC_CSS\";" +
        "(document.head||document.documentElement).appendChild(s);}catch(e){}})();"

    private const val LEARNED_THRESHOLD = 0.88f
}
