package dev.deepstellar.browser.ui

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import dev.deepstellar.browser.AppLock
import dev.deepstellar.browser.R

/** Covers the browser until you unlock with fingerprint, face or your screen-lock PIN. */
class LockActivity : AppCompatActivity() {

    private var prompting = false
    private var autoPrompted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Ui.color(context, R.color.void_black))
            setPadding(Ui.dp(context, 32), 0, Ui.dp(context, 32), 0)
        }
        box.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
        }, LinearLayout.LayoutParams(Ui.dp(this, 132), Ui.dp(this, 132)))
        box.addView(TextView(this).apply {
            text = "DEEP STELLAR IS LOCKED"
            letterSpacing = 0.25f
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Ui.color(context, R.color.starlight))
            setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 28))
        })
        box.addView(MaterialButton(this).apply {
            text = "Unlock"
            setOnClickListener { prompt() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(box)

        // Back leaves the app instead of slipping past the lock.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })
    }

    override fun onResume() {
        super.onResume()
        if (!AppLock.locked) { finishQuietly(); return }
        if (!AppLock.canUse(this)) {
            // Screen lock was removed from the phone: nothing to check against.
            AppLock.unlock()
            finishQuietly()
            return
        }
        // Ask once each time the lock screen comes into view; after a cancel, the button asks again.
        if (!autoPrompted) {
            autoPrompted = true
            prompt()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!prompting && !isChangingConfigurations) autoPrompted = false
    }

    private fun prompt() {
        if (prompting) return
        prompting = true
        val bp = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                prompting = false
                AppLock.unlock()
                finishQuietly()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                prompting = false // stay on the lock screen; the Unlock button tries again
            }
        })
        bp.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Deep Stellar")
                .setAllowedAuthenticators(AppLock.AUTHENTICATORS)
                .build()
        )
    }

    private fun finishQuietly() {
        AppLock.lockScreenClosed()
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
