package dev.deepstellar.browser.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * backup-v1.dsbackup was made by an independent implementation of the format
 * (password "stellar-test-1"). Every future version must still open it.
 */
class BackupFormatTest {

    private fun fixture(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("backup-v1.dsbackup")!!.use { it.readBytes() }

    @Test fun opensVersion1Backup() {
        val c = BackupManager.open(fixture(), "stellar-test-1".toCharArray())
        assertEquals(3, c.prefCount)
        assertEquals(2, c.bookmarks)
        assertEquals(2, c.blockedDomains)
        assertEquals(1, c.historyEntries)
        assertEquals(1794300000000L, c.created)
    }

    @Test fun wrongPasswordRefused() {
        try {
            BackupManager.open(fixture(), "wrong-password".toCharArray())
            fail("should not open")
        } catch (e: BackupManager.WrongPasswordException) {
            // expected
        }
    }

    @Test fun tamperedHeaderRefused() {
        val b = fixture()
        b[20] = (b[20].toInt() xor 1).toByte() // inside the salt, which is authenticated
        try {
            BackupManager.open(b, "stellar-test-1".toCharArray())
            fail("should not open")
        } catch (e: Exception) {
            assertTrue(e is BackupManager.WrongPasswordException)
        }
    }

    @Test fun notABackup() {
        try {
            BackupManager.open(ByteArray(64), "x".toCharArray())
            fail("should not open")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
