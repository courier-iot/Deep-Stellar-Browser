package dev.deepstellar.browser.history

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import dev.deepstellar.browser.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class HistoryEntry(val url: String, var title: String, var time: Long)

/**
 * OPTIONAL encrypted history. Off by default.
 *
 * - The file is AES-256-GCM encrypted. The key lives in the Android Keystore
 *   (hardware-backed on most phones) and can't be exported, so the file is
 *   unreadable if copied off the device or pulled from a backup.
 * - It's decrypted into memory once at launch and re-encrypted on every save.
 * - Turning the option off deletes the file AND destroys the key.
 */
object HistoryStore {
    private const val TAG = "HistoryStore"
    private const val KEY_ALIAS = "deep_stellar_history_v1"
    private const val MAX_ENTRIES = 5000

    private lateinit var file: File
    private val entries = ArrayList<HistoryEntry>() // newest first
    private val io = Executors.newSingleThreadScheduledExecutor()
    private var pendingSave: ScheduledFuture<*>? = null
    @Volatile var loaded = false
        private set

    val enabled: Boolean get() = Prefs.raw.getBoolean("history_enabled", false)

    fun init(context: Context) {
        file = File(context.filesDir, "history.enc")
        if (enabled) io.execute { load() }
    }

    // ------------------------------------------------------------ reading

    @Synchronized fun all(): List<HistoryEntry> = entries.map { it.copy() }

    /** Walks entries without copying them (used by address-bar suggestions). */
    @Synchronized fun forEachEntry(block: (HistoryEntry) -> Unit) = entries.forEach(block)

    @Synchronized fun search(q: String): List<HistoryEntry> {
        if (q.isBlank()) return all()
        val needle = q.trim().lowercase()
        return entries.filter { it.url.lowercase().contains(needle) || it.title.lowercase().contains(needle) }.map { it.copy() }
    }

    // ------------------------------------------------------------ writing

    fun record(url: String, title: String) {
        if (!enabled || !loaded) return
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return
        if (Prefs.torProxy && Prefs.raw.getBoolean("history_pause_tor", true)) return
        synchronized(this) {
            val now = System.currentTimeMillis()
            val top = entries.firstOrNull()
            if (top != null && top.url == url) {
                top.time = now
                if (title.isNotBlank()) top.title = title
            } else {
                entries.add(0, HistoryEntry(url, title, now))
                if (entries.size > MAX_ENTRIES) entries.subList(MAX_ENTRIES, entries.size).clear()
            }
        }
        scheduleSave()
    }

    /** Titles often arrive after the page finishes loading. */
    fun updateTitle(url: String, title: String) {
        if (!enabled || !loaded || title.isBlank()) return
        synchronized(this) {
            entries.firstOrNull { it.url == url }?.title = title
        }
        scheduleSave()
    }

    @Synchronized fun remove(entry: HistoryEntry) {
        entries.removeAll { it.url == entry.url && it.time == entry.time }
        scheduleSave()
    }

    @Synchronized fun clear() {
        entries.clear()
        scheduleSave()
    }

    /** Panic wipe: empty the history and delete the file immediately (keeps the feature on). */
    fun clearNow() {
        pendingSave?.cancel(false)
        synchronized(this) { entries.clear() }
        if (::file.isInitialized) file.delete()
    }

    /** Backup restore: replace everything and write it (encrypted with this phone's key) right now. */
    fun replaceAllNow(list: List<HistoryEntry>) {
        pendingSave?.cancel(false)
        synchronized(this) {
            entries.clear()
            entries.addAll(list.sortedByDescending { it.time }.take(MAX_ENTRIES))
        }
        loaded = true
        save()
    }

    /** Called when the setting is switched on. */
    fun enable() = io.execute { load() }

    /** Called when the setting is switched off: wipe the file and destroy the key. */
    fun disableAndDestroy() {
        synchronized(this) { entries.clear() }
        loaded = false
        io.execute {
            pendingSave?.cancel(false)
            file.delete()
            runCatching {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
            }
        }
    }

    /** Flush immediately (app going to background). */
    fun flush() {
        if (!enabled || !loaded) return
        io.execute { save() }
    }

    // ------------------------------------------------------------ crypto + disk

    private fun scheduleSave() {
        synchronized(this) {
            pendingSave?.cancel(false)
            pendingSave = io.schedule(Runnable { save() }, 2, TimeUnit.SECONDS)
        }
    }

    private fun retentionCutoff(): Long {
        val days = Prefs.raw.getString("history_days", "30")?.toIntOrNull() ?: 30
        return if (days <= 0) 0L else System.currentTimeMillis() - days * 86_400_000L
    }

    private fun load() {
        val list = ArrayList<HistoryEntry>()
        if (file.exists()) {
            try {
                val blob = file.readBytes()
                val iv = blob.copyOfRange(0, 12)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                val json = String(cipher.doFinal(blob, 12, blob.size - 12), Charsets.UTF_8)
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list += HistoryEntry(o.getString("u"), o.optString("t"), o.getLong("w"))
                }
            } catch (e: Exception) {
                // Key gone (e.g. device restored) or file damaged: start fresh rather than crash.
                Log.w(TAG, "History unreadable, starting fresh: ${e.javaClass.simpleName}")
                file.delete()
            }
        }
        val cutoff = retentionCutoff()
        synchronized(this) {
            entries.clear()
            entries.addAll(list.filter { it.time >= cutoff })
        }
        loaded = true
    }

    private fun save() {
        if (!enabled || !loaded) return
        val snapshot = synchronized(this) {
            val cutoff = retentionCutoff()
            entries.removeAll { it.time < cutoff }
            entries.map { it.copy() }
        }
        try {
            val arr = JSONArray()
            snapshot.forEach { arr.put(JSONObject().put("u", it.url).put("t", it.title).put("w", it.time)) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ct = cipher.doFinal(arr.toString().toByteArray(Charsets.UTF_8))
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(cipher.iv + ct)
            tmp.renameTo(file)
        } catch (e: Exception) {
            Log.w(TAG, "History save failed: ${e.javaClass.simpleName}")
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }
}
