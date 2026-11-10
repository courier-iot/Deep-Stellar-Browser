package dev.deepstellar.browser.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import dev.deepstellar.browser.AppLock
import dev.deepstellar.browser.R
import dev.deepstellar.browser.torrent.TorrentEngine
import java.io.File

/**
 * One torrent: choose files, download in order (so video can play early),
 * open finished files, see and add trackers.
 */
class TorrentDetailActivity : AppCompatActivity() {

    private var item: TorrentEngine.Item? = null
    private lateinit var status: TextView
    private lateinit var filesBox: LinearLayout
    private lateinit var trackersBox: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val fileLabels = mutableMapOf<Int, TextView>()
    private var builtForCount = -1

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        val t = TorrentEngine.find(intent.getLongExtra(EXTRA_ID, -1))
        if (t == null) { finish(); return }
        item = t

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, Ui.dp(context, 32))
        }
        status = Ui.sub(this, "").apply {
            maxLines = 4
            setPadding(Ui.dp(context, 20), Ui.dp(context, 4), Ui.dp(context, 20), Ui.dp(context, 8))
        }
        box.addView(status)

        // Sequential download
        val seq = MaterialSwitch(this).apply {
            text = "Download in order (play video before it finishes)"
            isChecked = t.sequential
            setTextColor(Ui.color(context, R.color.starlight))
            setPadding(Ui.dp(context, 20), Ui.dp(context, 6), Ui.dp(context, 16), Ui.dp(context, 6))
            setOnCheckedChangeListener { _, on -> TorrentEngine.setSequential(t, on) }
        }
        box.addView(seq)

        box.addView(Ui.header(this, "Files"))
        val pickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(context, 20), 0, Ui.dp(context, 20), Ui.dp(context, 4))
        }
        pickRow.addView(link("Select all") { TorrentEngine.setSkipped(t, emptySet()); builtForCount = -1 })
        pickRow.addView(link("Select none") { TorrentEngine.setSkipped(t, t.files.map { it.index }.toSet()); builtForCount = -1 })
        box.addView(pickRow)
        filesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(filesBox)

        box.addView(Ui.header(this, "Trackers"))
        trackersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(trackersBox)
        box.addView(LinearLayout(this).apply {
            setPadding(Ui.dp(context, 20), 0, Ui.dp(context, 20), 0)
            addView(link("Add tracker") { askTracker(t) })
        })

        box.addView(Ui.header(this, "Saved in"))
        box.addView(Ui.sub(this, t.saveDir?.absolutePath ?: "Decided when the torrent starts").apply {
            maxLines = 4
            setTextIsSelectable(true)
            setPadding(Ui.dp(context, 20), 0, Ui.dp(context, 20), 0)
        })

        val bar = scaffold(t.name, ScrollView(this).apply { addView(box) })
        bar.menu.add("Pause / resume").setOnMenuItemClickListener {
            if (t.paused) TorrentEngine.resume(t) else TorrentEngine.pause(t); true
        }
    }

    override fun onResume() {
        super.onResume()
        item?.watchFiles = true
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        item?.watchFiles = false
        super.onPause()
    }

    private fun link(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label.uppercase()
        textSize = 12f
        letterSpacing = 0.1f
        setTextColor(Ui.color(context, R.color.cosmic_blue))
        setPadding(0, Ui.dp(context, 10), Ui.dp(context, 24), Ui.dp(context, 10))
        setOnClickListener { onClick() }
    }

    private fun refresh() {
        val t = item ?: return
        if (TorrentEngine.find(t.id) == null) { finish(); return }
        status.text = if (t.handle == null) t.status else
            "${t.status} · ${(t.progress * 100).toInt()}% of ${Format.bytes(t.wanted)}\n" +
            "↓ ${Format.speed(t.downBps)}   ↑ ${Format.speed(t.upBps)}   ${t.peers} peers   uploaded ${Format.bytes(t.uploaded)}"

        // Files (rebuilt only when the list or the selection changes)
        if (builtForCount != t.files.size) {
            builtForCount = t.files.size
            filesBox.removeAllViews()
            fileLabels.clear()
            if (t.files.isEmpty()) {
                filesBox.addView(Ui.row(this, "Waiting for the file list…", "It arrives with the torrent's metadata."))
            }
            t.files.forEach { f ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(Ui.dp(context, 10), Ui.dp(context, 2), Ui.dp(context, 16), Ui.dp(context, 2))
                }
                val cb = CheckBox(this).apply {
                    isChecked = f.index !in t.skipped
                    setOnCheckedChangeListener { _, on ->
                        TorrentEngine.setSkipped(t, if (on) t.skipped - f.index else t.skipped + f.index)
                    }
                }
                val texts = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { open(t, f) }
                }
                texts.addView(Ui.title(this, f.path.substringAfterLast('/'), 14f))
                val sub = Ui.sub(this, Format.bytes(f.size))
                texts.addView(sub)
                fileLabels[f.index] = sub
                row.addView(cb)
                row.addView(texts)
                filesBox.addView(row)
            }
        }
        val prog = t.fileProgress
        t.files.forEach { f ->
            val got = prog.getOrNull(f.index) ?: 0L
            val pct = if (f.size > 0) (got * 100 / f.size).toInt() else 100
            fileLabels[f.index]?.text = when {
                f.index in t.skipped -> "${Format.bytes(f.size)} · skipped"
                pct >= 100 -> "${Format.bytes(f.size)} · done · tap to open"
                else -> "${Format.bytes(f.size)} · $pct%" + if (t.sequential && pct > 2) " · tap to play" else ""
            }
        }

        // Trackers
        val trackers = TorrentEngine.trackers(t)
        if (trackersBox.childCount != maxOf(trackers.size, 1)) {
            trackersBox.removeAllViews()
            if (trackers.isEmpty()) trackersBox.addView(Ui.row(this, "No trackers", "This torrent finds peers through DHT only."))
            trackers.forEach { trackersBox.addView(Ui.row(this, it.substringAfter("://").substringBefore('/'), it)) }
        }
    }

    private fun open(t: TorrentEngine.Item, f: TorrentEngine.FileEntry) {
        val dir = t.saveDir ?: return
        val file = File(dir, f.path)
        if (!file.exists()) { toast("Nothing downloaded for this file yet"); return }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
            AppLock.expectExternal()
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            toast("Couldn't open it here — find it in your Files app under ${dir.name}")
        }
    }

    private fun askTracker(t: TorrentEngine.Item) {
        val input = EditText(this).apply {
            hint = "udp://tracker.example.org:1337/announce"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Add tracker")
            .setView(LinearLayout(this).apply {
                setPadding(Ui.dp(context, 20), Ui.dp(context, 8), Ui.dp(context, 20), 0)
                addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            .setPositiveButton("Add") { _, _ ->
                val u = input.text.toString().trim()
                if (Regex("^(udp|https?|wss?)://\\S+$").matches(u)) TorrentEngine.addTracker(t, u) else toast("That isn't a tracker address")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_ID = "id"
    }
}
