package dev.deepstellar.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogScrubTest {
    @Test fun addressesPathsAndDomainsRemoved() {
        val trace = """
            java.lang.IllegalStateException: failed loading https://secret.example.com/account?id=42 for bank.example.org
            	at dev.deepstellar.browser.BrowserEngine.load(BrowserEngine.kt:10)
            	at androidx.core.app.NotificationCompat.build(NotificationCompat.java:1)
            Caused by: java.io.FileNotFoundException: /storage/emulated/0/Download/private.pdf (No such file)
            	... 3 more
        """.trimIndent()
        val out = CrashLog.scrub(trace)
        assertFalse(out.contains("secret.example.com"))
        assertFalse(out.contains("bank.example.org"))
        assertFalse(out.contains("private.pdf"))
        assertTrue(out.contains("[address]"))
        // Stack frames stay readable for debugging.
        assertTrue(out.contains("dev.deepstellar.browser.BrowserEngine.load"))
        assertTrue(out.contains("androidx.core.app.NotificationCompat"))
    }
}
