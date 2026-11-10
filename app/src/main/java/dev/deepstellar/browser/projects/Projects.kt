package dev.deepstellar.browser.projects

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.deepstellar.browser.BrowserEngine
import dev.deepstellar.browser.MainActivity
import dev.deepstellar.browser.NetUtil
import dev.deepstellar.browser.Prefs
import dev.deepstellar.browser.R
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * "My projects": a built-in page listing the developer's repositories
 * (assets/projects.json), plus an optional check of GitHub every 6 hours for
 * new repos, new pushes and new releases.
 *
 * The check is off by default (it's a scheduled network request) and only ever
 * calls GitHub's public API — no account, no identifiers. It uses an ETag, so
 * when nothing changed GitHub replies "304 Not Modified" with no data.
 */
object Projects {

    const val CHANNEL = "projects"
    private const val WORK = "projects_check"
    private const val WORK_NOW = "projects_check_now"
    private const val STATE = "projects_state"
    private const val NOTIF_ID = 88
    private const val MAX_RELEASE_LOOKUPS = 8

    data class Config(
        val user: String,
        val title: String,
        val intro: String,
        val storeUrl: String,
        val storeLabel: String,
        val projects: List<Entry>,
        /** Other repos shown as plain title cards even before the first GitHub check. */
        val more: List<String>,
    ) {
        val configured: Boolean get() = user.isNotBlank() && user != "YOUR-GITHUB-USERNAME"
        fun repoUrl(repo: String) = "https://github.com/$user/$repo"
        /** Fallback when a repo's exact name isn't known yet: the profile's repo list filtered to it. */
        fun searchUrl(q: String) = "https://github.com/$user?tab=repositories&q=" + Uri.encode(q)
    }

    /**
     * [repo] is the exact repository name. It may be left blank with [find] keywords instead:
     * the card then links to a filtered repo list until a GitHub check has matched the real name.
     */
    data class Entry(val repo: String, val name: String, val description: String, val tag: String, val find: List<String>)

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun resolve(e: Entry, repos: JSONObject): String? {
        if (e.repo.isNotBlank()) return e.repo
        val names = repos.keys().asSequence().toList()
        // Keywords are tried in the order written, so put the most specific one first.
        for (key in e.find.map { norm(it) }.filter { it.isNotEmpty() }) {
            names.firstOrNull { norm(it).contains(key) }?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------ settings

    val checkEnabled: Boolean get() = Prefs.raw.getBoolean("projects_check", false)
    val notify: Boolean get() = Prefs.raw.getBoolean("projects_notify", true)

    private var cached: Config? = null

    fun config(ctx: Context): Config {
        cached?.let { return it }
        val o = runCatching {
            JSONObject(ctx.assets.open("projects.json").bufferedReader().use { it.readText() })
        }.getOrDefault(JSONObject())
        val arr = o.optJSONArray("projects") ?: JSONArray()
        val list = (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            val find = p.optJSONArray("find")?.let { f -> (0 until f.length()).map { f.getString(it) } } ?: emptyList()
            Entry(p.optString("repo"), p.optString("name", p.optString("repo")), p.optString("description"), p.optString("tag"), find)
        }
        val more = o.optJSONArray("more")?.let { m -> (0 until m.length()).map { m.getString(it) } } ?: emptyList()
        return Config(
            o.optString("githubUser"),
            o.optString("title", "My projects"),
            o.optString("intro"),
            o.optString("storeUrl"),
            o.optString("storeLabel", "Get my apps"),
            list,
            more,
        ).also { cached = it }
    }

    // ------------------------------------------------------------ stored state

    private fun state(): JSONObject = runCatching { JSONObject(Prefs.raw.getString(STATE, "{}")!!) }.getOrDefault(JSONObject())
    private fun saveState(s: JSONObject) = Prefs.raw.edit().putString(STATE, s.toString()).apply()

    // ------------------------------------------------------------ schedule

    fun schedule(ctx: Context, policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE) {
        val wm = WorkManager.getInstance(ctx)
        if (!checkEnabled || !config(ctx).configured) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val req = PeriodicWorkRequestBuilder<ProjectsWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, policy, req)
    }

    fun ensureScheduled(ctx: Context) {
        if (checkEnabled) schedule(ctx, ExistingPeriodicWorkPolicy.KEEP)
    }

    fun checkNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            WORK_NOW, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ProjectsWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    fun setCheckEnabled(ctx: Context, on: Boolean) {
        Prefs.raw.edit().putBoolean("projects_check", on).apply()
        schedule(ctx)
        if (on) checkNow(ctx)
    }

    // ------------------------------------------------------------ the check

    /** Runs on a background thread. Returns a short summary of what changed, or null. */
    fun check(ctx: Context): String? {
        val cfg = config(ctx)
        if (!cfg.configured) return null
        val st = state()
        val repos = st.optJSONObject("repos") ?: JSONObject()
        val firstCheck = !st.optBoolean("baseline", false)

        val conn = NetUtil.open("https://api.github.com/users/${Uri.encode(cfg.user)}/repos?per_page=100&sort=pushed")
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        st.optString("etag").takeIf { it.isNotEmpty() }?.let { conn.setRequestProperty("If-None-Match", it) }

        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                st.put("checkedAt", System.currentTimeMillis()).remove("error")
                saveState(st)
                return null
            }
            if (code != 200) throw IllegalStateException(
                if (code == 403 || code == 429) "GitHub is rate-limiting this network, will try again later" else "GitHub replied $code"
            )
            conn.getHeaderField("ETag")?.let { st.put("etag", it) }
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })

            val newRepos = mutableListOf<String>()
            val changed = mutableListOf<String>()
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val name = r.getString("name")
                val pushed = parseTime(r.optString("pushed_at"))
                val old = repos.optJSONObject(name)
                val entry = (old ?: JSONObject()).apply {
                    put("d", r.optString("description").takeIf { it != "null" } ?: "")
                    put("u", r.optString("html_url"))
                    put("lang", r.optString("language").takeIf { it != "null" } ?: "")
                    put("stars", r.optInt("stargazers_count"))
                    put("fork", r.optBoolean("fork"))
                    put("archived", r.optBoolean("archived"))
                    put("p", pushed)
                }
                if (old == null) {
                    entry.put("first", now)
                    if (firstCheck) {
                        entry.put("seenNew", true).put("seenP", pushed) // baseline: nothing is "new" yet
                    } else if (!entry.optBoolean("fork") && !entry.optBoolean("archived")) {
                        newRepos += name
                    }
                } else if (pushed > old.optLong("pOld", old.optLong("p")) && !firstCheck) {
                    changed += name
                }
                entry.put("pOld", pushed)
                repos.put(name, entry)
            }

            // Look up the latest release only for repos that changed (keeps API use tiny).
            val lookups = (changed + newRepos).distinct().take(MAX_RELEASE_LOOKUPS) +
                if (firstCheck) cfg.projects.mapNotNull { resolve(it, repos) }.take(MAX_RELEASE_LOOKUPS) else emptyList()
            val newReleases = mutableListOf<String>()
            for (name in lookups.distinct()) {
                val e = repos.optJSONObject(name) ?: continue
                val rel = latestRelease(cfg.user, name) ?: continue
                val before = e.optString("r")
                e.put("r", rel.first).put("rt", rel.second)
                if (firstCheck) e.put("seenR", rel.first)
                else if (before != rel.first && rel.first.isNotEmpty()) newReleases += "$name ${rel.first}"
            }

            st.put("repos", repos).put("checkedAt", now).put("baseline", true).remove("error")
            saveState(st)

            if (firstCheck) return null
            val parts = mutableListOf<String>()
            if (newRepos.isNotEmpty()) parts += "New: " + newRepos.joinToString(", ")
            if (newReleases.isNotEmpty()) parts += "Released: " + newReleases.joinToString(", ")
            val pushedOnly = changed.filter { c -> newReleases.none { it.startsWith("$c ") } }
            if (pushedOnly.isNotEmpty()) parts += "Updated: " + pushedOnly.joinToString(", ")
            return parts.joinToString(" · ").ifEmpty { null }
        } catch (e: Exception) {
            st.put("error", e.message ?: "Couldn't reach GitHub")
            saveState(st)
            throw e
        } finally {
            conn.disconnect()
        }
    }

    /** (tag, publishedAtMillis) or null if the repo has no releases. */
    private fun latestRelease(user: String, repo: String): Pair<String, Long>? = runCatching {
        val c = NetUtil.open("https://api.github.com/repos/${Uri.encode(user)}/${Uri.encode(repo)}/releases/latest")
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode != 200) return@runCatching null
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            o.optString("tag_name") to parseTime(o.optString("published_at"))
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private fun parseTime(s: String): Long = runCatching { Instant.parse(s).toEpochMilli() }.getOrDefault(0L)

    // ------------------------------------------------------------ the page

    /** JSON for projects.html. Opening the page marks everything as seen. */
    fun pageJson(ctx: Context): String {
        val cfg = config(ctx)
        val st = state()
        val repos = st.optJSONObject("repos") ?: JSONObject()
        val out = JSONArray()

        fun card(repo: String?, fallbackUrl: String, name: String, desc: String, tag: String, featured: Boolean): JSONObject {
            val live = repo?.let { repos.optJSONObject(it) }
            val o = JSONObject()
                .put("name", name)
                .put("repo", repo ?: "")
                .put("url", live?.optString("u")?.takeIf { it.startsWith("https://github.com/") } ?: fallbackUrl)
                .put("description", desc.ifBlank { live?.optString("d").orEmpty() })
                .put("tag", tag)
                .put("builtIn", featured)
            if (live != null) {
                val pushed = live.optLong("p")
                o.put("pushedAt", pushed)
                    .put("lang", live.optString("lang"))
                    .put("stars", live.optInt("stars"))
                    .put("release", live.optString("r"))
                    .put("releaseAt", live.optLong("rt"))
                    .put("isNew", !featured && !live.optBoolean("seenNew"))
                    .put("updated", live.has("seenP") && pushed > live.optLong("seenP"))
                    .put("newRelease", live.optString("r").isNotEmpty() && live.has("seenR") && live.optString("r") != live.optString("seenR"))
                // Mark as seen for next time.
                live.put("seenNew", true).put("seenP", pushed).put("seenR", live.optString("r"))
            }
            return o
        }

        // 1) Featured projects, in the order written in projects.json.
        val used = HashSet<String>()
        cfg.projects.forEach { e ->
            val repo = resolve(e, repos)
            repo?.let { used += it.lowercase() }
            val fallback = if (repo != null) cfg.repoUrl(repo) else cfg.searchUrl(e.find.firstOrNull() ?: e.name)
            out.put(card(repo, fallback, e.name, e.description, e.tag, true))
        }

        // 2) Everything else as title cards: repos found on GitHub (newest activity first, no forks
        //    or archived ones), then any from the built-in "more" list that haven't been seen yet.
        fun pretty(n: String) = n.replace('-', ' ').replace('_', ' ')
        repos.keys().asSequence().toList()
            .filter { it.lowercase() !in used }
            .mapNotNull { n -> repos.optJSONObject(n)?.let { n to it } }
            .filter { (_, o) -> !o.optBoolean("fork") && !o.optBoolean("archived") }
            .sortedByDescending { (_, o) -> o.optLong("p") }
            .forEach { (n, o) ->
                used += n.lowercase()
                out.put(card(n, cfg.repoUrl(n), pretty(n), o.optString("d"), "", false))
            }
        cfg.more.filter { it.lowercase() !in used }.forEach { n ->
            // If a check has run and the repo isn't there (renamed/deleted/private), don't show a dead link.
            if (!st.optBoolean("baseline", false)) out.put(card(n, cfg.repoUrl(n), pretty(n), "", "", false))
        }

        if (repos.length() > 0) { st.put("repos", repos); saveState(st) }

        return JSONObject()
            .put("title", cfg.title)
            .put("intro", cfg.intro)
            .put("user", cfg.user)
            .put("configured", cfg.configured)
            .put("profileUrl", "https://github.com/${cfg.user}")
            .put("storeUrl", cfg.storeUrl)
            .put("storeLabel", cfg.storeLabel)
            .put("checkEnabled", checkEnabled)
            .put("checkedAt", st.optLong("checkedAt"))
            .put("error", st.optString("error"))
            .put("projects", out)
            .toString()
    }

    // ------------------------------------------------------------ notification

    fun notifyChanges(ctx: Context, summary: String) {
        if (!notify) return
        val open = PendingIntent.getActivity(
            ctx, 3,
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(BrowserEngine.projectsPageUrl())),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_star)
            .setContentTitle(config(ctx).title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n) }
    }
}

/** Every 6 hours (when turned on): check GitHub for new repos, pushes and releases. */
class ProjectsWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = try {
        Projects.check(applicationContext)?.let { Projects.notifyChanges(applicationContext, it) }
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }
}
