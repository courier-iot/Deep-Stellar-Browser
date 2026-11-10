package dev.deepstellar.browser.backup

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.deepstellar.browser.DeepStellarApp
import dev.deepstellar.browser.Prefs
import dev.deepstellar.browser.R
import dev.deepstellar.browser.history.HistoryStore
import dev.deepstellar.browser.ui.SettingsActivity
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Automatic encrypted backups to a folder you choose.
 *
 * Setup derives the backup key from your password ONCE. That derived key (plus its
 * salt) is stored encrypted by a hardware-backed Android Keystore key, so scheduled
 * runs can encrypt without asking you. The files are ordinary .dsbackup files:
 * they open on any phone with your password, and the password itself is never stored.
 */
object AutoBackup {

    private const val WORK = "auto_backup"
    private const val WORK_NOW = "auto_backup_now"
    private const val KS_ALIAS = "deep_stellar_autobackup_v1"
    private const val PREFIX = "deep-stellar-auto-"
    private const val SUFFIX = ".dsbackup"
    private const val NOTIF_ID = 77

    // ---- settings
    val enabled get() = Prefs.raw.getBoolean("auto_backup_enabled", false)
    private val days get() = Prefs.raw.getString("auto_backup_freq", "1")?.toLongOrNull() ?: 1L
    private val keep get() = Prefs.raw.getString("auto_backup_keep", "5")?.toIntOrNull() ?: 5
    private val chargingOnly get() = Prefs.raw.getBoolean("auto_backup_charging", false)
    private val withHistory get() = Prefs.raw.getBoolean("auto_backup_history", false)
    private val withModel get() = Prefs.raw.getBoolean("auto_backup_model", true)
    val folder: Uri? get() = Prefs.raw.getString("auto_backup_folder", null)?.let { Uri.parse(it) }
    val lastSuccess: Long get() = Prefs.raw.getLong("auto_backup_last", 0L)
    val lastError: String? get() = Prefs.raw.getString("auto_backup_error", null)

    private fun keyFile(ctx: Context) = File(ctx.filesDir, "autobackup.key")

    // ================================================================ setup / teardown

    /** Slow (PBKDF2) — call off the main thread. */
    fun enable(ctx: Context, password: CharArray, folderUri: Uri) {
        val (raw, salt, iterations) = BackupManager.deriveForAuto(password)
        val blob = ByteBuffer.allocate(16 + 4 + raw.size).put(salt).putInt(iterations).put(raw).array()
        raw.fill(0)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        keyFile(ctx).writeBytes(cipher.iv + cipher.doFinal(blob))
        blob.fill(0)
        Prefs.raw.edit()
            .putString("auto_backup_folder", folderUri.toString())
            .putBoolean("auto_backup_enabled", true)
            .remove("auto_backup_error")
            .commit()
        schedule(ctx)
    }

    fun changeFolder(ctx: Context, folderUri: Uri) {
        Prefs.raw.edit().putString("auto_backup_folder", folderUri.toString()).remove("auto_backup_error").apply()
        schedule(ctx)
    }

    /** Turns it off and destroys the stored key. Existing backup files are left alone. */
    fun disable(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
        keyFile(ctx).delete()
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(KS_ALIAS)) ks.deleteEntry(KS_ALIAS)
        }
        folder?.let { uri ->
            runCatching {
                ctx.contentResolver.releasePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        Prefs.raw.edit().putBoolean("auto_backup_enabled", false).remove("auto_backup_folder").remove("auto_backup_error").apply()
    }

    /** (Re)applies frequency and the charging condition. */
    fun schedule(ctx: Context) {
        if (!enabled) return
        val constraints = Constraints.Builder()
            .setRequiresCharging(chargingOnly)
            .setRequiresStorageNotLow(true)
            .build()
        val req = PeriodicWorkRequestBuilder<AutoBackupWorker>(days, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun runNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            WORK_NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<AutoBackupWorker>().build(),
        )
    }

    // ================================================================ one run

    /** Returns null on success, or a short reason. */
    fun perform(ctx: Context): String? {
        if (!enabled) return "Automatic backups are off"
        val tree = folder ?: return "No backup folder chosen"
        val dir = DocumentFile.fromTreeUri(ctx, tree)
        if (dir == null || !dir.exists() || !dir.canWrite()) return "Backup folder is missing or not writable — choose it again"

        // Unwrap the stored key.
        val stored = keyFile(ctx).takeIf { it.exists() }?.readBytes() ?: return "Backup key missing — set automatic backups up again"
        val blob = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, stored, 0, 12))
            cipher.doFinal(stored, 12, stored.size - 12)
        } catch (e: Exception) {
            return "Backup key unreadable — set automatic backups up again"
        }
        val bb = ByteBuffer.wrap(blob)
        val salt = ByteArray(16).also { bb.get(it) }
        val iterations = bb.int
        val raw = ByteArray(blob.size - 20).also { bb.get(it) }
        blob.fill(0)

        // History loads in the background when the app process starts; give it a moment.
        if (withHistory && HistoryStore.enabled) {
            var waited = 0
            while (!HistoryStore.loaded && waited < 8000) { Thread.sleep(200); waited += 200 }
        }

        return try {
            val bytes = BackupManager.exportWithKey(ctx, raw, salt, iterations, withHistory, withModel)
            val name = PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + SUFFIX
            val file = dir.createFile("application/octet-stream", name) ?: return "Couldn't create a file in the backup folder"
            ctx.contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) } ?: return "Couldn't write the backup file"
            prune(dir)
            null
        } catch (e: Exception) {
            "Backup failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            raw.fill(0)
        }
    }

    /** Keeps only the newest [keep] automatic backups (manual ones are never touched). */
    private fun prune(dir: DocumentFile) {
        val autos = dir.listFiles()
            .filter { f -> f.isFile && f.name?.let { it.startsWith(PREFIX) && it.endsWith(SUFFIX) } == true }
            .sortedByDescending { it.name }
        autos.drop(keep).forEach { runCatching { it.delete() } }
    }

    fun recordResult(ctx: Context, error: String?) {
        val ed = Prefs.raw.edit()
        if (error == null) ed.putLong("auto_backup_last", System.currentTimeMillis()).remove("auto_backup_error")
        else ed.putString("auto_backup_error", error)
        ed.apply()
        if (error != null) notifyFailure(ctx, error)
    }

    private fun notifyFailure(ctx: Context, error: String) {
        val open = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, DeepStellarApp.CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Automatic backup didn't run")
            .setContentText(error)
            .setStyle(NotificationCompat.BigTextStyle().bigText(error))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n) }
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KS_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }
}

/** Runs one automatic backup. Scheduled by WorkManager, so it works with the app closed. */
class AutoBackupWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val error = AutoBackup.perform(applicationContext)
        AutoBackup.recordResult(applicationContext, error)
        // Folder/key problems won't fix themselves by retrying; transient I/O errors might.
        return when {
            error == null -> Result.success()
            error.startsWith("Backup failed") && runAttemptCount < 2 -> Result.retry()
            else -> Result.failure()
        }
    }
}
