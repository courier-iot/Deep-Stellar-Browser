package dev.deepstellar.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.deepstellar.browser.MainActivity
import dev.deepstellar.browser.R
import dev.deepstellar.browser.link.LinkManager

/**
 * Linked computers (Deep Stellar Station): the computers you've linked, what was shared, and
 * a note box. Pairing starts by scanning the QR code Station shows.
 */
class LinkActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private val refresh: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, Ui.dp(this@LinkActivity, 24))
        }
        val bar = scaffold("Linked computers", ScrollView(this).apply { addView(list) })
        bar.menu.add("Clear shared items").setOnMenuItemClickListener { LinkManager.clearItems(); true }

        intent.getStringExtra(EXTRA_PAIR)?.let { startPair(it) }
    }

    override fun onStart() {
        super.onStart()
        LinkManager.addListener(refresh)
        LinkManager.connectAll()
        render()
    }

    override fun onStop() {
        LinkManager.removeListener(refresh)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_PAIR)?.let { startPair(it) }
    }

    private fun startPair(qr: String) {
        toast("Linking…")
        LinkManager.pair(qr) { ok, msg -> toast(msg); if (ok) render() }
    }

    private fun render() {
        list.removeAllViews()
        val stations = LinkManager.stations()

        list.addView(Ui.header(this, "Computers"))
        if (stations.isEmpty()) {
            list.addView(Ui.sub(this, "Install Deep Stellar Station on your Linux or Windows computer, open its side panel, " +
                "choose \"Link a phone\", then scan the code here. Both devices need to be on the same Wi-Fi. " +
                "Everything you send is end-to-end encrypted; there's no server or account.").apply {
                setPadding(Ui.dp(context, 18), Ui.dp(context, 4), Ui.dp(context, 18), Ui.dp(context, 8))
            })
        }
        for (s in stations) {
            val status = when {
                s.unknownToComputer -> "The computer no longer knows this phone. Remove it and link again."
                s.online -> "Connected"
                else -> "Not on this network right now"
            } + LinkManager.pending(s.fp).let { if (it > 0) " · $it waiting to send" else "" }
            list.addView(Ui.row(this, s.name, status, active = s.online,
                onClick = { compose(s) },
                onLongClick = { stationMenu(s) },
                trailing = listOf(Ui.iconButton(this, R.drawable.ic_remove, "Remove") { confirmUnlink(s) })))
        }
        list.addView(MaterialButton(this).apply {
            text = "Link a computer"
            setOnClickListener {
                startActivity(Intent(this@LinkActivity, QrActivity::class.java).putExtra(QrActivity.EXTRA_SCAN, true))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(Ui.dp(context, 14), Ui.dp(context, 8), Ui.dp(context, 14), Ui.dp(context, 8))
            }
        })

        val items = LinkManager.items()
        list.addView(Ui.header(this, "Shared"))
        if (items.isEmpty()) {
            list.addView(Ui.sub(this, "Pages and notes sent between this phone and your computers appear here.").apply {
                setPadding(Ui.dp(context, 18), Ui.dp(context, 4), Ui.dp(context, 18), Ui.dp(context, 8))
            })
        }
        for (m in items) {
            val whenText = DateUtils.getRelativeTimeSpanString(m.ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            val dir = if (m.outgoing) "To ${m.peerName}" else "From ${m.peerName}"
            val title = when {
                m.kind == "link" -> m.title.ifBlank { m.url }
                else -> m.text.take(120)
            }
            val sub = (if (m.kind == "link") m.url + "\n" else "") + "$dir · $whenText"
            list.addView(Ui.row(this, title, sub,
                onClick = {
                    if (m.url.isNotEmpty()) {
                        startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(m.url)))
                        finish()
                    } else showText(m.text)
                },
                trailing = listOf(Ui.iconButton(this, R.drawable.ic_close, "Remove") { LinkManager.removeItem(m.id) })))
        }
    }

    private fun compose(s: LinkManager.Station) {
        val box = EditText(this).apply {
            hint = "Note or link for ${s.name}"
            minLines = 3
            setTextColor(Ui.color(context, R.color.starlight))
            setHintTextColor(Ui.color(context, R.color.starlight_dim))
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        val wrap = LinearLayout(this).apply {
            setPadding(Ui.dp(context, 20), Ui.dp(context, 8), Ui.dp(context, 20), 0)
            addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Send to ${s.name}")
            .setView(wrap)
            .setPositiveButton("Send") { _, _ ->
                val t = box.text.toString().trim()
                if (t.isEmpty()) return@setPositiveButton
                if (!t.contains(' ') && (t.startsWith("https://") || t.startsWith("http://"))) LinkManager.sendLink(s.fp, t, "")
                else LinkManager.sendText(s.fp, t)
                toast(if (s.online) "Sent" else "Will send when ${s.name} is on the same Wi-Fi")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun stationMenu(s: LinkManager.Station) {
        MaterialAlertDialogBuilder(this)
            .setTitle(s.name)
            .setMessage("Key fingerprint: ${s.fp}\nCompare it with the one Station shows for this phone's computer.")
            .setPositiveButton("Close", null)
            .setNegativeButton("Remove") { _, _ -> confirmUnlink(s) }
            .show()
    }

    private fun confirmUnlink(s: LinkManager.Station) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove ${s.name}?")
            .setMessage("This phone forgets the computer's key and anything still waiting to send. Remove the phone on the computer too.")
            .setPositiveButton("Remove") { _, _ -> LinkManager.unlink(s.fp) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showText(t: String) {
        MaterialAlertDialogBuilder(this)
            .setMessage(t)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("note", t))
                toast("Copied")
            }
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_PAIR = "pair"
    }
}
