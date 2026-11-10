package dev.deepstellar.browser.ui

import dev.deepstellar.browser.AppLock
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.deepstellar.browser.MainActivity
import dev.deepstellar.browser.Prefs
import dev.deepstellar.browser.R
import dev.deepstellar.browser.history.HistoryStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var search: EditText
    private lateinit var content: View
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)

        search = EditText(this).apply {
            hint = "Search history"
            setSingleLine()
            setTextColor(Ui.color(context, R.color.starlight))
            setHintTextColor(Ui.color(context, R.color.starlight_dim))
            setBackgroundResource(R.drawable.bg_urlbar)
            setPadding(Ui.dp(context, 18), 0, Ui.dp(context, 18), 0)
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = render()
            })
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, Ui.dp(this@HistoryActivity, 24))
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 46)).apply {
                setMargins(Ui.dp(context, 14), Ui.dp(context, 4), Ui.dp(context, 14), Ui.dp(context, 4))
            })
            addView(ScrollView(context).apply { addView(list) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            visibility = View.INVISIBLE // shown once unlocked
        }
        val bar = scaffold("History", content)
        bar.menu.add("Clear all history").setOnMenuItemClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Clear all history?")
                .setMessage("Every entry is erased from the encrypted file.")
                .setPositiveButton("Clear") { _, _ -> HistoryStore.clear(); render() }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        unlockThen { showUnlocked() }
    }

    /** Asks for fingerprint / face / PIN before showing anything, if the lock is on. */
    private fun unlockThen(onUnlocked: () -> Unit) {
        val wantLock = Prefs.raw.getBoolean("history_lock", true)
        val authenticators = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        val can = BiometricManager.from(this).canAuthenticate(authenticators)
        if (!wantLock || can != BiometricManager.BIOMETRIC_SUCCESS) {
            if (wantLock) toast("Set a screen lock to protect history with fingerprint/PIN")
            onUnlocked()
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onUnlocked()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = finish()
        })
        AppLock.expectExternal() // the PIN screen is a separate system screen
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock history")
                .setSubtitle("Deep Stellar")
                .setAllowedAuthenticators(authenticators)
                .build()
        )
    }

    private fun render() {
        list.removeAllViews()
        if (!HistoryStore.loaded) {
            list.addView(Ui.row(this, "Decrypting…", null))
            list.postDelayed({ render() }, 300)
            return
        }
        val items = HistoryStore.search(search.text.toString())
        if (items.isEmpty()) {
            list.addView(Ui.row(this, if (search.text.isNullOrBlank()) "Nothing here yet" else "No matches",
                "Pages you visit are saved here, encrypted, while history is on."))
            return
        }
        var lastDay = ""
        for (e in items.take(800)) {
            val day = dayLabel(e.time)
            if (day != lastDay) { list.addView(Ui.header(this, day)); lastDay = day }
            list.addView(Ui.row(this, e.title.ifBlank { e.url },
                timeFmt.format(Date(e.time)) + "  " + e.url.removePrefix("https://"),
                onClick = {
                    startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(e.url)))
                    finish()
                },
                trailing = listOf(Ui.iconButton(this, R.drawable.ic_close, "Remove") { HistoryStore.remove(e); render() }),
            ))
        }
    }

    private fun dayLabel(t: Long): String {
        val c = Calendar.getInstance()
        val today = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
        c.timeInMillis = t
        val d = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
        return when (today - d) {
            0 -> "Today"
            1 -> "Yesterday"
            else -> dayFmt.format(Date(t))
        }
    }

    // Re-lock whenever the screen is left (but not during the PIN screen itself).
    private var unlocked = false
    private var needsRelock = false

    override fun onStart() {
        super.onStart()
        if (needsRelock) {
            needsRelock = false
            unlockThen { showUnlocked() }
        }
    }

    override fun onStop() {
        super.onStop()
        if (unlocked && !isChangingConfigurations) {
            unlocked = false
            needsRelock = true
            content.visibility = View.INVISIBLE
            list.removeAllViews()
        }
    }

    private fun showUnlocked() {
        unlocked = true
        content.visibility = View.VISIBLE
        render()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
