package dev.deepstellar.browser.backup

import android.content.Context
import android.util.Base64
import dev.deepstellar.browser.Prefs
import dev.deepstellar.browser.bookmarks.Bookmark
import dev.deepstellar.browser.bookmarks.BookmarkStore
import dev.deepstellar.browser.history.HistoryEntry
import dev.deepstellar.browser.history.HistoryStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-encrypted backup file (.dsbackup). Nothing touches a cloud.
 *
 * File layout:  "DSBK" | version(1) | PBKDF2 iterations(4) | salt(16) | IV(12) | AES-256-GCM(gzip(JSON))
 * The header is authenticated too, so any tampering makes the restore fail.
 * Key = PBKDF2-HMAC-SHA256(password, salt, 210 000 iterations).
 */
object BackupManager {

    private val MAGIC = byteArrayOf('D'.code.toByte(), 'S'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val VERSION: Byte = 1
    private const val ITERATIONS = 210_000
    private val SKIP_PREFS = setOf("asked_notif")

    class WrongPasswordException : Exception("Wrong password, or the file was changed")

    data class Contents(
        val created: Long,
        val prefCount: Int,
        val bookmarks: Int,
        val blockedDomains: Int,
        val hasModel: Boolean,
        val historyEntries: Int,
        internal val json: JSONObject,
    )

    // ------------------------------------------------------------ export

    /** Manual backup: a fresh salt, key derived from the typed password. */
    fun export(context: Context, password: CharArray, includeHistory: Boolean, includeModel: Boolean): ByteArray {
        val salt = random(16)
        val key = deriveKey(password, salt, ITERATIONS)
        return exportWithKey(context, key.encoded, salt, ITERATIONS, includeHistory, includeModel)
    }

    /**
     * Encrypts with an already-derived key. Automatic backups use this, so they
     * never need the password — yet the file still opens with it, because the
     * header carries the same salt and iteration count the key came from.
     */
    fun exportWithKey(
        context: Context,
        rawKey: ByteArray,
        salt: ByteArray,
        iterations: Int,
        includeHistory: Boolean,
        includeModel: Boolean,
    ): ByteArray {
        val root = JSONObject()
        root.put("app", "Deep Stellar Browsing")
        root.put("format", 1)
        root.put("created", System.currentTimeMillis())

        val prefs = JSONArray()
        for ((k, v) in Prefs.raw.all) {
            if (k in SKIP_PREFS || k.startsWith("auto_backup_") || v == null) continue
            val o = JSONObject().put("k", k)
            when (v) {
                is Boolean -> o.put("t", "b").put("v", v)
                is Int -> o.put("t", "i").put("v", v)
                is Long -> o.put("t", "l").put("v", v)
                is Float -> o.put("t", "f").put("v", v.toDouble())
                is String -> o.put("t", "s").put("v", v)
                is Set<*> -> o.put("t", "ss").put("v", JSONArray(v.filterIsInstance<String>()))
                else -> continue
            }
            prefs.put(o)
        }
        root.put("prefs", prefs)

        val bm = JSONArray()
        BookmarkStore.all().forEach { bm.put(JSONObject().put("t", it.title).put("u", it.url).put("f", it.folder).put("a", it.added)) }
        root.put("bookmarks", bm)

        val userFile = File(context.filesDir, "user_block.txt")
        root.put("userBlock", if (userFile.exists()) userFile.readText() else "")

        if (includeModel) {
            val model = File(context.filesDir, "ad_model.bin")
            if (model.exists()) root.put("model", Base64.encodeToString(model.readBytes(), Base64.NO_WRAP))
        }

        if (includeHistory && HistoryStore.enabled && HistoryStore.loaded) {
            val h = JSONArray()
            HistoryStore.all().forEach { h.put(JSONObject().put("u", it.url).put("t", it.title).put("w", it.time)) }
            root.put("history", h)
        }

        val plain = gzip(root.toString().toByteArray(Charsets.UTF_8))
        val iv = random(12)
        val header = ByteBuffer.allocate(4 + 1 + 4 + 16 + 12).apply {
            put(MAGIC); put(VERSION); putInt(iterations); put(salt); put(iv)
        }.array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(rawKey, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    // ------------------------------------------------------------ import

    /** Decrypts and reads the backup without changing anything yet. */
    fun open(bytes: ByteArray, password: CharArray): Contents {
        require(bytes.size > 37) { "Not a Deep Stellar backup" }
        val bb = ByteBuffer.wrap(bytes)
        val magic = ByteArray(4).also { bb.get(it) }
        require(magic.contentEquals(MAGIC)) { "Not a Deep Stellar backup" }
        val version = bb.get()
        require(version == VERSION) { "Backup was made by a newer version" }
        val iterations = bb.int
        require(iterations in 10_000..5_000_000) { "Damaged backup" }
        val salt = ByteArray(16).also { bb.get(it) }
        val iv = ByteArray(12).also { bb.get(it) }
        val header = bytes.copyOfRange(0, 37)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        val plain = try {
            cipher.doFinal(bytes, 37, bytes.size - 37)
        } catch (e: AEADBadTagException) {
            throw WrongPasswordException()
        }
        val json = JSONObject(String(gunzip(plain), Charsets.UTF_8))
        return Contents(
            created = json.optLong("created"),
            prefCount = json.optJSONArray("prefs")?.length() ?: 0,
            bookmarks = json.optJSONArray("bookmarks")?.length() ?: 0,
            blockedDomains = json.optString("userBlock").lines().count { it.isNotBlank() },
            hasModel = json.has("model"),
            historyEntries = json.optJSONArray("history")?.length() ?: 0,
            json = json,
        )
    }

    /**
     * Replaces settings, bookmarks, your block list, and (if present) the ad model
     * and history with the backup's. The caller restarts the app afterwards.
     */
    fun restore(context: Context, c: Contents) {
        val json = c.json

        // Settings (this phone's automatic-backup setup is device-specific and kept as is)
        val keepLocal = Prefs.raw.all.filterKeys { it.startsWith("auto_backup_") }
        val ed = Prefs.raw.edit().clear()
        for ((k, v) in keepLocal) when (v) {
            is Boolean -> ed.putBoolean(k, v)
            is Long -> ed.putLong(k, v)
            is Int -> ed.putInt(k, v)
            is String -> ed.putString(k, v)
        }
        json.optJSONArray("prefs")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val k = o.getString("k")
                when (o.getString("t")) {
                    "b" -> ed.putBoolean(k, o.getBoolean("v"))
                    "i" -> ed.putInt(k, o.getInt("v"))
                    "l" -> ed.putLong(k, o.getLong("v"))
                    "f" -> ed.putFloat(k, o.getDouble("v").toFloat())
                    "s" -> ed.putString(k, o.getString("v"))
                    "ss" -> {
                        val a = o.getJSONArray("v")
                        ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet())
                    }
                }
            }
        }
        val history = json.optJSONArray("history")
        if (history != null && history.length() > 0) ed.putBoolean("history_enabled", true)
        ed.commit()

        // Bookmarks
        val bm = json.optJSONArray("bookmarks") ?: JSONArray()
        BookmarkStore.replaceAll((0 until bm.length()).map {
            val o = bm.getJSONObject(it)
            Bookmark(o.optString("t"), o.getString("u"), o.optString("f"), o.optLong("a"))
        })

        // Personal block list
        File(context.filesDir, "user_block.txt").writeText(json.optString("userBlock"))

        // Learned ad model
        if (json.has("model")) {
            File(context.filesDir, "ad_model.bin").writeBytes(Base64.decode(json.getString("model"), Base64.NO_WRAP))
        }

        // History is re-encrypted with THIS phone's hardware key.
        if (history != null && history.length() > 0) {
            HistoryStore.replaceAllNow((0 until history.length()).map {
                val o = history.getJSONObject(it)
                HistoryEntry(o.getString("u"), o.optString("t"), o.getLong("w"))
            })
        }
    }

    // ------------------------------------------------------------ helpers

    /** Used by automatic backup setup: derive once, then store the result (hardware-wrapped). */
    fun deriveForAuto(password: CharArray): Triple<ByteArray, ByteArray, Int> {
        val salt = random(16)
        return Triple(deriveKey(password, salt, ITERATIONS).encoded, salt, ITERATIONS)
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun random(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }

    private fun gzip(b: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(b) }
        return bos.toByteArray()
    }

    private fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
}
