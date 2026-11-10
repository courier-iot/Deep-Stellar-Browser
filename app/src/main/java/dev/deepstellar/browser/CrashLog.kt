package dev.deepstellar.browser

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash reports that never leave the phone unless you share one yourself.
 *
 * Each report holds only: app version, Android version, device model, and the
 * stack trace. Web addresses and long hex/number strings inside error messages
 * are replaced with placeholders, so a report can't reveal what you were browsing.
 * The newest 10 are kept; turning the setting off deletes them.
 */
object CrashLog {
    private const val MAX = 10
    private lateinit var dir: File

    val enabled: Boolean get() = Prefs.raw.getBoolean("crash_log", true)

    data class Report(val file: File, val time: Long, val title: String) {
        fun text(): String = runCatching { file.readText() }.getOrDefault("")
    }

    fun install(context: Context) {
        dir = File(context.filesDir, "crashes")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { if (enabled) write(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(thread: Thread, error: Throwable) {
        dir.mkdirs()
        val now = System.currentTimeMillis()
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val text = buildString {
            append("Deep Stellar crash report\n")
            append("Time:     ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(now))).append('\n')
            append("App:      ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("Android:  ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Device:   ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("Thread:   ").append(thread.name).append("\n\n")
            append(scrub(sw.toString()))
        }
        File(dir, "crash-$now.txt").writeText(text)
        list().drop(MAX).forEach { it.file.delete() }
    }

    /** Removes anything that could identify pages or files. */
    private val ADDRESS = Regex("(?i)\\b(?:https?|wss?|ftp|file|content|magnet|data|blob|intent):[^\\s\"'<>)]*")
    private val DOMAIN = Regex("(?i)\\b(?:[a-z0-9-]+\\.)+(?:com|net|org|io|dev|app|co|uk|de|ca|info|xyz|me|tv|gov|edu)\\b")
    private val PATH = Regex("/storage/[^\\s:]+|/data/user/[^\\s:]+|/sdcard/[^\\s:]+")
    private val HEX = Regex("\\b[0-9a-fA-F]{32,}\\b")

    fun scrub(s: String): String = s.lines().joinToString("\n") { raw ->
        val line = raw.replace(ADDRESS, "[address]").replace(PATH, "[path]").replace(HEX, "[hex]")
        val t = line.trimStart()
        if (t.startsWith("at ") || t.startsWith("...")) {
            line // stack frames: class names stay readable
        } else {
            // "java.lang.Foo: message" / "Caused by: java.lang.Foo: message" — scrub domains in the message only
            val head = if (t.startsWith("Caused by: ")) line.indexOf("Caused by: ") + 11 else 0
            val colon = line.indexOf(": ", head)
            if (colon < 0) line else line.substring(0, colon + 2) + line.substring(colon + 2).replace(DOMAIN, "[domain]")
        }
    }

    fun list(): List<Report> {
        if (!::dir.isInitialized) return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("crash-") && it.name.endsWith(".txt") }
            .mapNotNull { f ->
                val t = f.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: return@mapNotNull null
                // First line after the header block is "java.lang.SomeException: message"
                val line = runCatching { f.readLines().drop(7).firstOrNull { it.isNotBlank() } }.getOrNull().orEmpty()
                val cls = line.substringBefore(": ").substringAfterLast('.')
                val msg = line.substringAfter(": ", "").trim()
                val title = if (cls.isBlank()) "Crash" else if (msg.isEmpty()) cls else "$cls: $msg"
                Report(f, t, title.take(120))
            }
            .sortedByDescending { it.time }
    }

    fun clear() {
        if (::dir.isInitialized) dir.listFiles()?.forEach { it.delete() }
    }
}
