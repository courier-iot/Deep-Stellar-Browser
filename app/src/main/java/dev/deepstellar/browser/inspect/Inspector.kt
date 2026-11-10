package dev.deepstellar.browser.inspect

import android.graphics.Typeface
import android.net.http.SslCertificate
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.webkit.WebView
import dev.deepstellar.browser.Tab
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest
import java.text.DateFormat

/**
 * Hands the current tab's data to the Under-the-hood screen. Memory only.
 */
object Inspector {

    data class Source(val url: String, val contentType: String, val html: String, val text: String)

    var tab: Tab? = null
    var source: Source? = null
    var certificate: SslCertificate? = null
    var pageUrl: String = ""

    private const val CAPTURE_JS =
        "(function(){var d=document;var t=d.body?d.body.innerText:'';" +
        "var h=d.documentElement?d.documentElement.outerHTML:'';" +
        "return JSON.stringify({ct:d.contentType||'',html:h.slice(0,800000),text:t.slice(0,800000)});})()"

    /** Grabs the page's (rendered) source, then calls [done] on the main thread. */
    fun capture(tab: Tab, done: () -> Unit) {
        this.tab = tab
        pageUrl = tab.url
        val wv: WebView? = tab.webView
        certificate = wv?.certificate
        source = null
        if (wv == null) { done(); return }
        wv.evaluateJavascript(CAPTURE_JS) { result ->
            source = runCatching {
                val inner = JSONTokener(result).nextValue() as String
                val o = JSONObject(inner)
                Source(tab.url, o.optString("ct"), o.optString("html"), o.optString("text"))
            }.getOrNull()
            done()
        }
    }

    fun clear() {
        tab = null; source = null; certificate = null
    }

    // ------------------------------------------------------------ pretty views

    enum class Kind { HTML, JSON, XML, TEXT }

    fun detect(src: Source): Kind {
        val ct = src.contentType.lowercase()
        val t = src.text.trimStart()
        return when {
            ct.contains("json") -> Kind.JSON
            (t.startsWith("{") || t.startsWith("[")) && runCatching { parseJson(t) }.isSuccess -> Kind.JSON
            ct.contains("xml") && !ct.contains("xhtml") -> Kind.XML
            ct.startsWith("text/plain") -> Kind.TEXT
            else -> Kind.HTML
        }
    }

    private fun parseJson(t: String): Any = JSONTokener(t.trim()).nextValue()

    fun prettyJson(text: String): String = when (val v = parseJson(text)) {
        is JSONObject -> v.toString(2)
        is JSONArray -> v.toString(2)
        else -> text
    }

    /** Re-indents XML (one tag per line). */
    fun prettyXml(xml: String): String {
        val sb = StringBuilder()
        var depth = 0
        val tokens = Regex("(<[^>]+>)|([^<]+)").findAll(xml)
        for (m in tokens) {
            val tok = m.value.trim()
            if (tok.isEmpty()) continue
            when {
                tok.startsWith("</") -> { depth = (depth - 1).coerceAtLeast(0); line(sb, depth, tok) }
                tok.startsWith("<?") || tok.startsWith("<!") || tok.endsWith("/>") -> line(sb, depth, tok)
                tok.startsWith("<") -> { line(sb, depth, tok); depth++ }
                else -> line(sb, depth, tok)
            }
        }
        return sb.toString()
    }

    private fun line(sb: StringBuilder, depth: Int, s: String) {
        repeat(depth) { sb.append("  ") }
        sb.append(s).append('\n')
    }

    // ------------------------------------------------------------ highlighting

    data class Palette(val tag: Int, val attr: Int, val string: Int, val comment: Int, val number: Int)

    private val HTML_RULES = listOf(
        Regex("<!--[\\s\\S]*?-->") to "comment",
        Regex("</?[A-Za-z][\\w:-]*|/?>") to "tag",
        Regex("\\s[\\w:@.-]+(?==)") to "attr",
        Regex("\"[^\"\\n]*\"|'[^'\\n]*'") to "string",
    )
    private val JSON_RULES = listOf(
        Regex("\"(?:[^\"\\\\]|\\\\.)*\"(?=\\s*:)") to "attr",
        Regex("\"(?:[^\"\\\\]|\\\\.)*\"") to "string",
        Regex("\\b(?:true|false|null)\\b|-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b") to "number",
    )

    fun highlight(text: String, kind: Kind, p: Palette): CharSequence {
        val sb = SpannableStringBuilder(text)
        val rules = when (kind) {
            Kind.JSON -> JSON_RULES
            Kind.HTML, Kind.XML -> HTML_RULES
            Kind.TEXT -> return sb
        }
        val taken = BooleanArray(text.length)
        for ((re, what) in rules) {
            val color = when (what) {
                "tag" -> p.tag; "attr" -> p.attr; "string" -> p.string; "comment" -> p.comment; else -> p.number
            }
            for (m in re.findAll(text)) {
                val r = m.range
                if (r.isEmpty() || taken[r.first]) continue
                for (i in r) taken[i] = true
                sb.setSpan(ForegroundColorSpan(color), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (what == "tag") sb.setSpan(StyleSpan(Typeface.BOLD), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    // ------------------------------------------------------------ certificate

    fun describeCertificate(cert: SslCertificate?, url: String): String {
        if (!url.startsWith("https://")) return "This page was loaded over plain HTTP — there is no certificate and the connection is not encrypted."
        cert ?: return "No certificate information available for this page."
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val sb = StringBuilder()
        sb.append("ISSUED TO\n")
        sb.append("  Common name   ").append(cert.issuedTo.cName.orEmpty()).append('\n')
        sb.append("  Organization  ").append(cert.issuedTo.oName.orEmpty().ifBlank { "—" }).append('\n')
        sb.append("\nISSUED BY\n")
        sb.append("  Common name   ").append(cert.issuedBy.cName.orEmpty()).append('\n')
        sb.append("  Organization  ").append(cert.issuedBy.oName.orEmpty().ifBlank { "—" }).append('\n')
        sb.append("\nVALIDITY\n")
        sb.append("  From  ").append(cert.validNotBeforeDate?.let { df.format(it) } ?: "?").append('\n')
        sb.append("  Until ").append(cert.validNotAfterDate?.let { df.format(it) } ?: "?").append('\n')
        val days = cert.validNotAfterDate?.let { ((it.time - System.currentTimeMillis()) / 86_400_000L) }
        if (days != null) sb.append("  ").append(if (days >= 0) "$days days left" else "EXPIRED").append('\n')
        cert.x509Certificate?.let { x ->
            sb.append("\nDETAILS\n")
            sb.append("  Key           ").append(x.publicKey.algorithm).append('\n')
            sb.append("  Signature     ").append(x.sigAlgName).append('\n')
            sb.append("  Serial        ").append(x.serialNumber.toString(16).uppercase()).append('\n')
            val sha = MessageDigest.getInstance("SHA-256").digest(x.encoded)
            sb.append("  SHA-256\n  ").append(sha.joinToString(":") { "%02X".format(it) }.chunked(48).joinToString("\n  ")).append('\n')
        }
        return sb.toString()
    }
}
