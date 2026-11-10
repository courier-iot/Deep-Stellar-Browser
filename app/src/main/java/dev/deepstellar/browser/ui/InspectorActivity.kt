package dev.deepstellar.browser.ui

import dev.deepstellar.browser.AppLock
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.deepstellar.browser.R
import dev.deepstellar.browser.adblock.AdBlocker
import dev.deepstellar.browser.adblock.DomainUtil
import dev.deepstellar.browser.inspect.Inspector
import dev.deepstellar.browser.inspect.RequestLog
import dev.deepstellar.browser.inspect.TrackerReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * "Under the hood": who's watching, every network request, the console,
 * the certificate and the page source (with JSON / XML pretty views).
 */
class InspectorActivity : AppCompatActivity() {

    private lateinit var content: FrameLayout
    private lateinit var filterRow: HorizontalScrollView
    private var section = "trackers"
    private var netFilter = "all"
    private var sourceText: String = ""
    private val bg = Executors.newSingleThreadExecutor()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val saveSource = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri ?: return@registerForActivityResult
        runCatching { contentResolver.openOutputStream(uri)!!.use { it.write(sourceText.toByteArray()) } }
            .onSuccess { toast("Saved") }.onFailure { toast("Couldn't save: ${it.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        if (Inspector.tab == null) { finish(); return }
        section = intent.getStringExtra(EXTRA_SECTION) ?: "trackers"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sections = listOf("trackers" to "Who's watching", "network" to "Network", "console" to "Console",
            "certificate" to "Certificate", "source" to "Source")
        root.addView(chipRow(sections, section) { section = it; render() })
        filterRow = chipRow(listOf("all" to "All", "blocked" to "Blocked", "third" to "Third-party", "script" to "Scripts"), netFilter) {
            netFilter = it; render()
        }
        root.addView(filterRow)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = scaffold("Under the hood", root)
        bar.subtitle = DomainUtil.registrable(Inspector.tab?.pageHost ?: "")
        bar.setSubtitleTextColor(Ui.color(this, R.color.starlight_dim))
        bar.menu.add("Refresh").setOnMenuItemClickListener { render(); true }
        bar.menu.add("Save source as file").setOnMenuItemClickListener {
            if (sourceText.isEmpty()) prepareSource()
            AppLock.expectExternal(); saveSource.launch((Inspector.tab?.pageHost ?: "page") + "-source.txt"); true
        }
        render()
    }

    override fun onDestroy() {
        bg.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------ layout helpers

    private fun chipRow(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit): HorizontalScrollView {
        val group = ChipGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            isSingleLine = true
            setPadding(Ui.dp(context, 12), 0, Ui.dp(context, 12), 0)
        }
        val ids = mutableMapOf<Int, String>()
        options.forEach { (key, label) ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = label
                isCheckable = true
                isCheckedIconVisible = false
            }
            ids[chip.id] = key
            group.addView(chip)
            if (key == selected) chip.isChecked = true
        }
        group.setOnCheckedStateChangeListener { _, checked ->
            checked.firstOrNull()?.let { ids[it] }?.let(onPick)
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(group)
        }
    }

    private fun <T> listOf2(items: List<T>, bind: (T) -> Triple<CharSequence, CharSequence, Int>, onTap: ((T) -> Unit)? = null): View {
        if (items.isEmpty()) return message("Nothing here for this page yet. Reload the page and tap Refresh.")
        val lv = ListView(this).apply {
            divider = null
            adapter = object : BaseAdapter() {
                override fun getCount() = items.size
                override fun getItem(position: Int): Any? = items[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(Ui.dp(context, 16), Ui.dp(context, 8), Ui.dp(context, 16), Ui.dp(context, 8))
                        addView(TextView(context).apply {
                            textSize = 13f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                        })
                        addView(TextView(context).apply {
                            textSize = 11f; typeface = Typeface.MONOSPACE; maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                            setTextColor(Ui.color(context, R.color.starlight_dim))
                        })
                    }
                    val (title, sub, color) = bind(items[position])
                    (row.getChildAt(0) as TextView).apply { text = title; setTextColor(color) }
                    (row.getChildAt(1) as TextView).text = sub
                    return row
                }
            }
            if (onTap != null) setOnItemClickListener { _, _, pos, _ -> onTap(items[pos]) }
        }
        return lv
    }

    private fun message(text: String): View = TextView(this).apply {
        this.text = text
        setTextColor(Ui.color(context, R.color.starlight_dim))
        setPadding(Ui.dp(context, 20), Ui.dp(context, 24), Ui.dp(context, 20), Ui.dp(context, 24))
    }

    private fun textPage(text: CharSequence, mono: Boolean = true): View = ScrollView(this).apply {
        addView(TextView(context).apply {
            this.text = text
            textSize = if (mono) 11.5f else 13f
            if (mono) typeface = Typeface.MONOSPACE
            setTextColor(Ui.color(context, R.color.starlight))
            setTextIsSelectable(true)
            setPadding(Ui.dp(context, 16), Ui.dp(context, 12), Ui.dp(context, 16), Ui.dp(context, 32))
        })
    }

    // ------------------------------------------------------------ sections

    private fun render() {
        content.removeAllViews()
        filterRow.visibility = if (section == "network") View.VISIBLE else View.GONE
        val tab = Inspector.tab ?: return
        when (section) {
            "trackers" -> renderTrackers(tab.net.snapshot())
            "network" -> renderNetwork(tab.net.snapshot())
            "console" -> renderConsole()
            "certificate" -> content.addView(textPage(Inspector.describeCertificate(Inspector.certificate, Inspector.pageUrl)))
            "source" -> renderSource()
        }
    }

    private fun renderTrackers(entries: List<RequestLog.Entry>) {
        val parties = TrackerReport.build(entries)
        val blockedParties = parties.count { it.blocked > 0 }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(Ui.row(this,
            "${parties.size} outside companies contacted · $blockedParties blocked",
            "${entries.size} requests on this page · ${entries.count { it.blocked }} stopped by the shield"))
        box.addView(listOf2(parties, { p ->
            val color = if (p.blocked > 0) Ui.color(this, R.color.nova_pink) else Ui.color(this, R.color.starlight)
            val reasons = if (p.reasons.isEmpty()) "allowed" else "blocked by " + p.reasons.joinToString(", ")
            Triple("${if (p.blocked > 0) "⛔" else "◌"}  ${p.domain}",
                "${p.requests} request${if (p.requests == 1) "" else "s"} · ${p.blocked} blocked · $reasons\n${p.types.sorted().joinToString(" · ")}",
                color)
        }, { p ->
            val already = AdBlocker.isUserBlocked(p.domain)
            MaterialAlertDialogBuilder(this)
                .setTitle(p.domain)
                .setMessage("${p.requests} requests, ${p.blocked} blocked.\nTypes: ${p.types.joinToString(", ")}")
                .setPositiveButton(if (already) "Already on your block list" else "Block everything from here") { _, _ ->
                    if (!already) { AdBlocker.userBlockDomain(p.domain); toast("${p.domain} blocked — reload the page") }
                }
                .setNegativeButton("Close", null)
                .show()
        }), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(box)
    }

    private fun renderNetwork(all: List<RequestLog.Entry>) {
        val items = when (netFilter) {
            "blocked" -> all.filter { it.blocked }
            "third" -> all.filter { it.thirdParty }
            "script" -> all.filter { it.type == "script" }
            else -> all
        }.asReversed()
        content.addView(listOf2(items, { e ->
            val color = when {
                e.blocked -> Ui.color(this, R.color.nova_pink)
                e.thirdParty -> Ui.color(this, R.color.cosmic_blue)
                else -> Ui.color(this, R.color.starlight)
            }
            val tag = if (e.blocked) "BLOCKED" else e.method
            Triple("$tag  ${e.type}  ${e.host}",
                "${timeFmt.format(Date(e.time))}  ${if (e.thirdParty) "3rd-party" else "1st-party"}  ${e.url.substringAfter(e.host).take(160)}",
                color)
        }, { e ->
            val b = MaterialAlertDialogBuilder(this)
                .setTitle("${e.method} · ${e.type}")
                .setMessage("${e.url}\n\n${if (e.thirdParty) "Third-party" else "First-party"} · ${e.reason}")
                .setNeutralButton("Copy URL") { _, _ -> copy(e.url) }
                .setNegativeButton("Close", null)
            if (!e.blocked && !e.mainFrame) {
                b.setPositiveButton("Block as ad") { _, _ ->
                    AdBlocker.userFlagAd(e.url, Inspector.tab?.pageHost)
                    toast("Shield learned from this — reload the page")
                }
            }
            b.show()
        }))
    }

    private fun renderConsole() {
        val items = (Inspector.tab?.console?.snapshot() ?: emptyList()).asReversed()
        content.addView(listOf2(items, { c ->
            val color = when (c.level) {
                "error" -> Ui.color(this, R.color.nova_pink)
                "warning" -> Ui.color(this, R.color.solar_amber)
                else -> Ui.color(this, R.color.starlight)
            }
            Triple("${c.level.uppercase()}  ${c.message}", "${c.source.substringAfterLast('/')}:${c.line}", color)
        }, { c ->
            MaterialAlertDialogBuilder(this)
                .setTitle(c.level.uppercase())
                .setMessage("${c.message}\n\n${c.source}:${c.line}")
                .setPositiveButton("Copy") { _, _ -> copy(c.message) }
                .setNegativeButton("Close", null)
                .show()
        }))
    }

    private fun prepareSource(): Inspector.Kind {
        val src = Inspector.source ?: return Inspector.Kind.TEXT
        val kind = Inspector.detect(src)
        sourceText = when (kind) {
            Inspector.Kind.JSON -> runCatching { Inspector.prettyJson(src.text) }.getOrDefault(src.text)
            Inspector.Kind.XML -> Inspector.prettyXml(src.html)
            Inspector.Kind.TEXT -> src.text
            Inspector.Kind.HTML -> src.html
        }
        return kind
    }

    private fun renderSource() {
        if (Inspector.source == null) {
            content.addView(message("Couldn't read this page's source (JavaScript may be off)."))
            return
        }
        content.addView(message("Formatting…"))
        val palette = Inspector.Palette(
            tag = Ui.color(this, R.color.cosmic_blue),
            attr = Ui.color(this, R.color.nebula_violet),
            string = Ui.color(this, R.color.solar_amber),
            comment = Ui.color(this, R.color.starlight_dim),
            number = Ui.color(this, R.color.nova_pink),
        )
        bg.execute {
            val kind = prepareSource()
            val limit = 250_000
            val shown = if (sourceText.length > limit) sourceText.substring(0, limit) else sourceText
            val styled = Inspector.highlight(shown, kind, palette)
            val label = when (kind) {
                Inspector.Kind.JSON -> "JSON · pretty-printed"
                Inspector.Kind.XML -> "XML · re-indented"
                Inspector.Kind.TEXT -> "Plain text"
                Inspector.Kind.HTML -> "HTML · as rendered (after scripts ran)"
            } + if (sourceText.length > limit) " · first ${limit / 1000}k characters shown, save to get it all" else ""
            runOnUiThread {
                if (isFinishing || section != "source") return@runOnUiThread
                content.removeAllViews()
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                box.addView(Ui.header(this, label))
                box.addView(textPage(styled), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                content.addView(box)
            }
        }
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("copied", text))
        toast("Copied")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_SECTION = "section"
    }
}
