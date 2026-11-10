package dev.deepstellar.browser.adblock

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FilterEngineTest {

    private lateinit var dir: File

    private val rules = """
        ! comment
        [Adblock Plus 2.0]
        ||ads.example.com^
        ||cdn.example.org/track?
        /ads/banner-*.js
        -ad-banner.
        ||pixel.net^${'$'}third-party
        ||widgets.example^${'$'}script
        &adunit=
        @@||cdn.example.org/track?allowed
        ||news.example^${'$'}domain=other.site
        example.com##.ad-box
        ||weird.example^${'$'}csp=script-src 'none'
    """.trimIndent()

    @Before fun setUp() {
        dir = Files.createTempDirectory("fe").toFile()
        val f = File(dir, "rules.txt")
        f.writeText(rules)
        FilterEngine.load(f, File(dir, "rules.cache"))
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun blocked(url: String, page: String? = "site.test", third: Boolean = true, type: String = "other") =
        FilterEngine.shouldBlock(url.lowercase(), hostOf(url), page, third, FilterEngine.typeBit(type))

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').substringBefore('?').lowercase()

    @Test fun plainDomainAndSubdomains() {
        assertTrue(blocked("https://ads.example.com/x.js"))
        assertTrue(blocked("https://a.b.ads.example.com/x.js"))
        assertFalse(blocked("https://notads.example.com/x.js"))
    }

    @Test fun pathRulesAndWildcards() {
        assertTrue(blocked("https://site.org/ads/banner-300.js"))
        assertTrue(blocked("https://x.com/img/top-ad-banner.png"))
        assertTrue(blocked("https://a.com/x?y=1&adunit=3"))
        assertFalse(blocked("https://site.org/ads/logo.png"))
    }

    @Test fun anchoredDomainWithPath() {
        assertTrue(blocked("https://sub.cdn.example.org/track?id=1"))
        assertFalse(blocked("https://notcdn.example.org/track?id=1"))
    }

    @Test fun exceptionsWin() {
        assertFalse(blocked("https://cdn.example.org/track?allowed=1"))
    }

    @Test fun thirdPartyOption() {
        assertTrue(blocked("https://pixel.net/p.gif", third = true))
        assertFalse(blocked("https://pixel.net/p.gif", third = false))
    }

    @Test fun typeOption() {
        assertTrue(blocked("https://widgets.example/w.js", type = "script"))
        assertFalse(blocked("https://widgets.example/w.png", type = "image"))
    }

    @Test fun domainOption() {
        assertTrue(blocked("https://news.example/x", page = "other.site"))
        assertFalse(blocked("https://news.example/x", page = "site.test"))
    }

    @Test fun unsupportedAndCosmeticRulesSkipped() {
        assertFalse(blocked("https://weird.example/x"))
        assertFalse(blocked("https://example.com/.ad-box"))
    }

    @Test fun cacheGivesSameResults() {
        val before = FilterEngine.ruleCount
        // Second load must come from the cache and behave identically.
        FilterEngine.load(File(dir, "rules.txt"), File(dir, "rules.cache"))
        assertEquals(before, FilterEngine.ruleCount)
        assertTrue(File(dir, "rules.cache").exists())
        pathRulesAndWildcards()
        exceptionsWin()
        thirdPartyOption()
    }

    @Test fun changedSourceInvalidatesCache() {
        val f = File(dir, "rules.txt")
        f.writeText("||only.example^\n")
        f.setLastModified(f.lastModified() + 5_000)
        FilterEngine.load(f, File(dir, "rules.cache"))
        assertTrue(blocked("https://only.example/a"))
        assertFalse(blocked("https://ads.example.com/x.js"))
    }
}
