package dev.deepstellar.browser.bookmarks

import android.text.Html

/**
 * The Netscape Bookmark File format — the one every major browser
 * (Firefox, Chrome, Brave, Edge, Vivaldi, Safari, DuckDuckGo, Tor Browser)
 * imports and exports. Folders are kept as "Parent/Child" paths.
 */
object NetscapeBookmarks {

    fun export(items: List<Bookmark>): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
        sb.append("<!-- This is an automatically generated file.\n     It will be read and overwritten.\n     DO NOT EDIT! -->\n")
        sb.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        sb.append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n")

        // Build a folder tree from the "A/B" paths.
        class Node(val name: String) {
            val children = linkedMapOf<String, Node>()
            val links = mutableListOf<Bookmark>()
        }
        val root = Node("")
        for (b in items) {
            var n = root
            b.folder.split('/').filter { it.isNotBlank() }.forEach { part ->
                n = n.children.getOrPut(part) { Node(part) }
            }
            n.links += b
        }

        fun write(n: Node, depth: Int) {
            val ind = "    ".repeat(depth)
            for (b in n.links) {
                sb.append(ind).append("<DT><A HREF=\"").append(esc(b.url)).append("\" ADD_DATE=\"")
                    .append(b.added).append("\">").append(esc(b.title.ifBlank { b.url })).append("</A>\n")
            }
            for (c in n.children.values) {
                sb.append(ind).append("<DT><H3>").append(esc(c.name)).append("</H3>\n")
                sb.append(ind).append("<DL><p>\n")
                write(c, depth + 1)
                sb.append(ind).append("</DL><p>\n")
            }
        }
        write(root, 1)
        sb.append("</DL><p>\n")
        return sb.toString()
    }

    private val TOKEN = Regex(
        "<DT>\\s*<H3[^>]*>(.*?)</H3>|<DT>\\s*<A\\s([^>]*)>(.*?)</A>|<DL[^>]*>|</DL>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val HREF = Regex("HREF\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val ADD_DATE = Regex("ADD_DATE\\s*=\\s*\"(\\d+)\"", RegexOption.IGNORE_CASE)

    fun parse(html: String): List<Bookmark> {
        val out = mutableListOf<Bookmark>()
        val stack = ArrayDeque<Pair<String, Int>>() // folder name, <DL> depth it opened at
        var pendingFolder: String? = null
        var depth = 0
        for (m in TOKEN.findAll(html)) {
            val tok = m.value
            when {
                m.groups[1] != null -> pendingFolder = unesc(m.groupValues[1]).trim()
                m.groups[2] != null -> {
                    val attrs = m.groupValues[2]
                    val url = HREF.find(attrs)?.groupValues?.get(1)?.let { unesc(it) } ?: continue
                    if (!(url.startsWith("http://") || url.startsWith("https://"))) continue
                    val added = ADD_DATE.find(attrs)?.groupValues?.get(1)?.toLongOrNull() ?: (System.currentTimeMillis() / 1000)
                    out += Bookmark(unesc(m.groupValues[3]).trim(), url, stack.joinToString("/") { it.first }, added)
                }
                tok.startsWith("</", ignoreCase = true) -> {
                    if (stack.lastOrNull()?.second == depth) stack.removeLast()
                    if (depth > 0) depth--
                }
                else -> { // <DL>
                    depth++
                    // The first <DL> is the root list; later ones open the folder just named.
                    if (pendingFolder != null) {
                        stack.addLast(pendingFolder!! to depth)
                        pendingFolder = null
                    }
                }
            }
        }
        return out
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun unesc(s: String): String =
        Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()
}
