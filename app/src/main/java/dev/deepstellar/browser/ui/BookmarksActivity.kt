package dev.deepstellar.browser.ui

import dev.deepstellar.browser.AppLock
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.deepstellar.browser.MainActivity
import dev.deepstellar.browser.R
import dev.deepstellar.browser.bookmarks.BookmarkStore
import dev.deepstellar.browser.bookmarks.NetscapeBookmarks
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BookmarksActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout

    private val importer = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        Thread {
            val result = runCatching {
                val html = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                BookmarkStore.merge(NetscapeBookmarks.parse(html))
            }
            runOnUiThread {
                result.fold(
                    { n -> toast("Imported $n new bookmark${if (n == 1) "" else "s"}"); render() },
                    { e -> toast("Couldn't read that file: ${e.message}") },
                )
            }
        }.start()
    }

    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)!!.use {
                it.write(NetscapeBookmarks.export(BookmarkStore.all()).toByteArray(Charsets.UTF_8))
            }
        }.onSuccess { toast("Exported ${BookmarkStore.all().size} bookmarks") }
            .onFailure { toast("Export failed: ${it.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, Ui.dp(this@BookmarksActivity, 24))
        }
        val bar = scaffold(getString(R.string.bookmarks), ScrollView(this).apply { addView(list) })
        bar.menu.add("Import").setOnMenuItemClickListener {
            AppLock.expectExternal(); importer.launch(arrayOf("text/html", "text/plain", "application/octet-stream", "*/*")); true
        }
        bar.menu.add("Export").setOnMenuItemClickListener {
            val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            AppLock.expectExternal(); exporter.launch("deep-stellar-bookmarks-$stamp.html"); true
        }
        render()
    }

    private fun render() {
        list.removeAllViews()
        val all = BookmarkStore.all()
        list.addView(Ui.header(this, "${all.size} bookmarks · import/export works with Firefox, Chrome, Brave, Tor, DuckDuckGo…"))
        if (all.isEmpty()) {
            list.addView(Ui.row(this, "No bookmarks yet", "Tap ★ on any page, or Import from another browser (menu ⋮ above)."))
            return
        }
        all.groupBy { it.folder }.toSortedMap().forEach { (folder, items) ->
            if (folder.isNotEmpty()) list.addView(Ui.header(this, folder))
            items.forEach { b ->
                list.addView(Ui.row(this, b.title.ifBlank { b.url }, b.url.removePrefix("https://"),
                    onClick = {
                        startActivity(Intent(this, MainActivity::class.java)
                            .setAction(Intent.ACTION_VIEW).setData(Uri.parse(b.url)))
                        finish()
                    },
                    onLongClick = {
                        MaterialAlertDialogBuilder(this)
                            .setTitle(b.title.ifBlank { b.url })
                            .setItems(arrayOf("Delete")) { _, _ -> BookmarkStore.remove(b.url); render() }
                            .show()
                    },
                    trailing = listOf(Ui.iconButton(this, R.drawable.ic_close, "Delete") {
                        BookmarkStore.remove(b.url); render()
                    }),
                ))
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
