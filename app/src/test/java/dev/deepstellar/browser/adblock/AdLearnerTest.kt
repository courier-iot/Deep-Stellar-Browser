package dev.deepstellar.browser.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.io.File

class AdLearnerTest {
    @Test fun learnsAndSurvivesRestart() {
        val dir = Files.createTempDirectory("al").toFile()
        val file = File(dir, "model.bin")
        val m = AdLearner(file)
        val ad = m.features("https://ads.tracker.net/pixel?uid=1&cb=2", "ads.tracker.net", "site.test", true)
        val ok = m.features("https://site.test/article/space.html", "site.test", "site.test", false)
        repeat(300) { m.train(ad, 1f, 0.1f); m.train(ok, 0f, 0.1f) }
        assertTrue(m.score(ad) > 0.9f)
        assertTrue(m.score(ok) < 0.1f)
        m.save()

        val again = AdLearner(file)
        assertEquals(m.trainedExamples, again.trainedExamples)
        assertTrue(again.score(ad) > 0.9f)
        dir.deleteRecursively()
    }
}
