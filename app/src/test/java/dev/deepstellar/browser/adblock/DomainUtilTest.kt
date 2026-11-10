package dev.deepstellar.browser.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainUtilTest {
    @Test fun registrableDomain() {
        assertEquals("example.com", DomainUtil.registrable("a.b.example.com"))
        assertEquals("example.co.uk", DomainUtil.registrable("www.example.co.uk"))
        assertEquals("me.github.io", DomainUtil.registrable("me.github.io"))
        assertEquals("localhost", DomainUtil.registrable("localhost"))
    }

    @Test fun thirdParty() {
        assertFalse(DomainUtil.isThirdParty("cdn.example.com", "www.example.com"))
        assertTrue(DomainUtil.isThirdParty("tracker.net", "www.example.com"))
        assertFalse(DomainUtil.isThirdParty("anything.net", null))
    }
}
