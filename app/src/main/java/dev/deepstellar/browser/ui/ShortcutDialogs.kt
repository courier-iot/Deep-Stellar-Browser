package dev.deepstellar.browser.ui

import android.app.Activity
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.deepstellar.browser.StartShortcuts

/** Add / edit / remove dialogs for start-page shortcuts, shared by the browser and Settings. */
object ShortcutDialogs {

    /** [index] null = add a new one. [prefillUrl]/[prefillName] seed the add dialog. */
    fun edit(
        activity: Activity,
        index: Int?,
        prefillUrl: String = "",
        prefillName: String = "",
        onDone: () -> Unit,
    ) {
        val existing = index?.let { StartShortcuts.all().getOrNull(it) }
        val name = EditText(activity).apply {
            hint = "Name"
            setSingleLine()
            setText(existing?.name ?: prefillName)
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        val url = EditText(activity).apply {
            hint = "Address (example.com)"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.url ?: prefillUrl)
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(activity, 22)
            setPadding(p, Ui.dp(activity, 8), p, 0)
            addView(name)
            addView(url)
        }
        val b = MaterialAlertDialogBuilder(activity)
            .setTitle(if (existing == null) "Add shortcut" else "Edit shortcut")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val u = StartShortcuts.normaliseUrl(url.text.toString())
                if (u == null) {
                    toast(activity, "That doesn't look like a web address")
                    return@setPositiveButton
                }
                val n = name.text.toString().trim().ifEmpty { StartShortcuts.defaultName(u) }.take(40)
                val s = StartShortcuts.Shortcut(n, u)
                if (index == null) {
                    if (!StartShortcuts.add(s)) toast(activity, "You can have up to ${StartShortcuts.MAX} shortcuts")
                } else {
                    StartShortcuts.update(index, s)
                }
                onDone()
            }
            .setNegativeButton("Cancel", null)
        if (index != null) b.setNeutralButton("Remove") { _, _ -> StartShortcuts.remove(index); onDone() }
        b.show()
    }

    /** List of your shortcuts with edit / move / remove. */
    fun manage(activity: Activity, onChanged: () -> Unit) {
        val list = StartShortcuts.all()
        val labels = list.map { "${it.name}\n${it.url.removePrefix("https://")}" }.toTypedArray()
        val b = MaterialAlertDialogBuilder(activity)
            .setTitle("Your shortcuts")
            .setPositiveButton("Add") { _, _ -> edit(activity, null) { onChanged(); manage(activity, onChanged) } }
            .setNegativeButton("Close", null)
        if (list.isEmpty()) {
            b.setMessage("No shortcuts yet. Add one here, tap ＋ on the start page, or use Menu → Add to start page on any site.")
        } else {
            b.setItems(labels) { _, i ->
                MaterialAlertDialogBuilder(activity)
                    .setTitle(list[i].name)
                    .setItems(arrayOf("Edit", "Move up", "Move down", "Remove")) { _, which ->
                        when (which) {
                            0 -> edit(activity, i) { onChanged(); manage(activity, onChanged) }
                            1 -> { StartShortcuts.move(i, -1); onChanged(); manage(activity, onChanged) }
                            2 -> { StartShortcuts.move(i, +1); onChanged(); manage(activity, onChanged) }
                            3 -> { StartShortcuts.remove(i); onChanged(); manage(activity, onChanged) }
                        }
                    }
                    .show()
            }
        }
        b.show()
    }

    private fun toast(a: Activity, s: String) = Toast.makeText(a, s, Toast.LENGTH_SHORT).show()
}
