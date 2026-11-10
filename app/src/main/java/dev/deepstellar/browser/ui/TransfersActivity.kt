package dev.deepstellar.browser.ui

import dev.deepstellar.browser.AppLock
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import dev.deepstellar.browser.R
import dev.deepstellar.browser.downloads.DownloadEngine
import dev.deepstellar.browser.torrent.TorrentEngine
import java.io.File

class TransfersActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var shownKeys = emptyList<String>()
    private val binders = mutableMapOf<String, () -> Unit>()

    private val pickTorrent = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            val f = File(cacheDir, "import-${System.currentTimeMillis()}.torrent")
            contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
            TorrentEngine.addTorrentFile(this, f)
        }.onFailure { toast("Couldn't open that file") }
    }

    private val refresh = object : Runnable {
        override fun run() {
            val keys = DownloadEngine.items.map { "d${it.id}" } + TorrentEngine.items.map { "t${it.id}" }
            if (keys != shownKeys) rebuild() else binders.values.forEach { it() }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, Ui.dp(this@TransfersActivity, 24))
        }
        val bar = scaffold(getString(R.string.transfers), ScrollView(this).apply { addView(list) })
        bar.menu.add("Add magnet link").setOnMenuItemClickListener { askMagnet(); true }
        bar.menu.add("Open .torrent file").setOnMenuItemClickListener {
            AppLock.expectExternal(); pickTorrent.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")); true
        }
        bar.menu.add("Clear finished").setOnMenuItemClickListener {
            DownloadEngine.clearFinished()
            TorrentEngine.items.filter { it.failed || (it.finished && !it.isActive) }.forEach { TorrentEngine.remove(it) }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun rebuild() {
        list.removeAllViews()
        binders.clear()
        val dls = DownloadEngine.items.toList()
        val trs = TorrentEngine.items.toList()
        shownKeys = dls.map { "d${it.id}" } + trs.map { "t${it.id}" }

        list.addView(Ui.header(this, "Downloads"))
        if (dls.isEmpty()) list.addView(Ui.row(this, "No downloads", "Files are saved to Download/DeepStellar. Finished downloads are never recorded; unfinished ones can be resumed after a restart."))
        dls.forEach { addDownload(it) }

        list.addView(Ui.header(this, "Torrents"))
        if (trs.isEmpty()) list.addView(Ui.row(this, "No torrents", "Tap a magnet link, or use the menu ⋮ to add one. Tap Details on a torrent to pick files or play while downloading."))
        trs.forEach { addTorrent(it) }
    }

    private fun card(): Triple<LinearLayout, TextView, TextView> {
        val title = Ui.title(this, "")
        val sub = Ui.sub(this, "")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(Ui.dp(context, 16), Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(Ui.dp(context, 14), Ui.dp(context, 5), Ui.dp(context, 14), Ui.dp(context, 5))
            }
            addView(title)
            addView(sub)
        }
        return Triple(card, title, sub)
    }

    private fun progressBar(): LinearProgressIndicator = LinearProgressIndicator(this).apply {
        max = 1000
        trackCornerRadius = Ui.dp(context, 2)
        setIndicatorColor(Ui.color(context, R.color.nebula_violet))
        trackColor = Ui.color(context, R.color.void_surface_2)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 2))
        }
    }

    private fun actionRow(vararg buttons: Pair<String, () -> Unit>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEach { (label, action) ->
            addView(TextView(context).apply {
                text = label.uppercase()
                setTextColor(Ui.color(context, R.color.cosmic_blue))
                textSize = 12f
                letterSpacing = 0.1f
                setPadding(0, Ui.dp(context, 10), Ui.dp(context, 20), Ui.dp(context, 8))
                setOnClickListener { action() }
            })
        }
    }

    private fun addDownload(d: DownloadEngine.Item) {
        val (card, title, sub) = card()
        val bar = progressBar()
        card.addView(bar)
        val actions = LinearLayout(this)
        card.addView(actions)
        var lastState: DownloadEngine.State? = null

        val bind: () -> Unit = {
            title.text = d.fileName
            val pct = if (d.total > 0) (d.done * 1000 / d.total).toInt() else 0
            bar.isIndeterminate = d.state == DownloadEngine.State.RUNNING && d.total <= 0
            if (!bar.isIndeterminate) bar.progress = pct
            sub.text = when (d.state) {
                DownloadEngine.State.RUNNING -> "${Format.bytes(d.done)} / ${Format.bytes(d.total)} · ${Format.speed(d.speedBps)}"
                DownloadEngine.State.QUEUED -> "Waiting…"
                DownloadEngine.State.PAUSED -> "Paused at ${Format.bytes(d.done)}"
                DownloadEngine.State.DONE -> "Saved · ${Format.bytes(d.total)}"
                DownloadEngine.State.FAILED -> "Failed: ${d.error}"
                DownloadEngine.State.CANCELLED -> "Cancelled"
            }
            if (d.state != lastState) {
                lastState = d.state
                actions.removeAllViews()
                actions.addView(when (d.state) {
                    DownloadEngine.State.RUNNING, DownloadEngine.State.QUEUED ->
                        actionRow("Pause" to { DownloadEngine.pause(d) }, "Cancel" to { DownloadEngine.cancel(d) })
                    DownloadEngine.State.PAUSED, DownloadEngine.State.FAILED ->
                        actionRow("Resume" to { DownloadEngine.resume(d) }, "Cancel" to { DownloadEngine.cancel(d) })
                    DownloadEngine.State.DONE ->
                        actionRow("Open" to { open(d) }, "Remove from list" to { DownloadEngine.remove(d) })
                    DownloadEngine.State.CANCELLED ->
                        actionRow("Remove from list" to { DownloadEngine.remove(d) })
                })
            }
        }
        bind()
        binders["d${d.id}"] = bind
        list.addView(card)
    }

    private fun addTorrent(t: TorrentEngine.Item) {
        val (card, title, sub) = card()
        val bar = progressBar()
        card.addView(bar)
        val actions = LinearLayout(this)
        card.addView(actions)
        var lastPaused: Boolean? = null
        val details = { startActivity(Intent(this, TorrentDetailActivity::class.java).putExtra(TorrentDetailActivity.EXTRA_ID, t.id)) }
        card.setOnClickListener { details() }

        val bind: () -> Unit = {
            title.text = t.name
            bar.isIndeterminate = t.handle == null && !t.failed
            if (!bar.isIndeterminate) bar.progress = (t.progress * 1000).toInt()
            sub.text = if (t.handle == null) t.status else
                "${t.status} · ${(t.progress * 100).toInt()}% of ${Format.bytes(t.wanted)} · ↓${Format.speed(t.downBps)} ↑${Format.speed(t.upBps)} · ${t.peers} peers"
            if (t.paused != lastPaused) {
                lastPaused = t.paused
                actions.removeAllViews()
                actions.addView(
                    if (t.paused) actionRow("Resume" to { TorrentEngine.resume(t) }, "Details" to details, "Remove" to { confirmRemove(t) })
                    else actionRow("Pause" to { TorrentEngine.pause(t) }, "Details" to details, "Remove" to { confirmRemove(t) })
                )
            }
        }
        bind()
        binders["t${t.id}"] = bind
        list.addView(card)
    }

    private fun open(d: DownloadEngine.Item) {
        val uri = d.resultUri ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, d.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            toast("No app can open ${d.mimeType}")
        }
    }

    private fun confirmRemove(t: TorrentEngine.Item) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove torrent?")
            .setMessage(t.name)
            .setPositiveButton("Remove, keep files") { _, _ -> TorrentEngine.remove(t) }
            .setNeutralButton("Remove and delete files") { _, _ -> TorrentEngine.remove(t, deleteFiles = true) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askMagnet() {
        val input = EditText(this).apply {
            hint = "magnet:?xt=urn:btih:…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Add magnet link")
            .setView(LinearLayout(this).apply {
                setPadding(Ui.dp(context, 20), Ui.dp(context, 8), Ui.dp(context, 20), 0)
                addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            .setPositiveButton("Add") { _, _ ->
                val m = input.text.toString().trim()
                if (m.startsWith("magnet:", ignoreCase = true)) TorrentEngine.addMagnet(this, m) else toast("That isn't a magnet link")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
