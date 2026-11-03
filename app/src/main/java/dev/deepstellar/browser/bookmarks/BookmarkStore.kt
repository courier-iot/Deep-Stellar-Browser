package dev.deepstellar.browser.bookmarks

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Bookmark(val title: String, val url: String, val folder: String = "", val added: Long = System.currentTimeMillis() / 1000)

/**
 * Bookmarks are the only browsing-related thing Deep Stellar saves, and only
 * because you explicitly ask it to.
 */
object BookmarkStore {
    private lateinit var file: File
    private val list = mutableListOf<Bookmark>()

    fun init(context: Context) {
        file = File(context.filesDir, "bookmarks.json")
        load()
    }

    @Synchronized fun all(): List<Bookmark> = list.toList()

    @Synchronized fun contains(url: String) = list.any { it.url == url }

    @Synchronized fun add(b: Bookmark) {
        if (list.none { it.url == b.url && it.folder == b.folder }) {
            list += b
            save()
        }
    }

    @Synchronized fun remove(url: String) {
        if (list.removeAll { it.url == url }) save()
    }

    /** Adds imported bookmarks, skipping duplicates. Returns how many were new. */
    @Synchronized fun merge(items: List<Bookmark>): Int {
        var added = 0
        val seen = list.map { it.folder + "\u0000" + it.url }.toHashSet()
        for (b in items) {
            if (seen.add(b.folder + "\u0000" + b.url)) { list += b; added++ }
        }
        if (added > 0) save()
        return added
    }

    @Synchronized fun clear() { list.clear(); save() }

    private fun load() {
        list.clear()
        if (!file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += Bookmark(o.optString("t"), o.getString("u"), o.optString("f"), o.optLong("a"))
            }
        }
    }

    private fun save() {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.title).put("u", it.url).put("f", it.folder).put("a", it.added)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}
