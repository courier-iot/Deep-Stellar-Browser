package dev.deepstellar.browser

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import dev.deepstellar.browser.ui.LockActivity

/**
 * Optional app lock (off by default): fingerprint, face or screen-lock PIN to open
 * the browser. Locks on a cold start and after you've been away longer than the
 * chosen delay. While it's on, the Recents preview is blanked (Android 13+).
 *
 * Leaving briefly for something the browser itself opened — a file picker, a share
 * sheet, the camera permission — doesn't count as leaving.
 */
object AppLock : Application.ActivityLifecycleCallbacks {

    const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    val enabled: Boolean get() = Prefs.raw.getBoolean("app_lock", false)
    private val timeoutMs: Long get() = (Prefs.raw.getString("app_lock_after", "0")?.toLongOrNull() ?: 0L) * 1000L

    @Volatile var locked = true
        private set
    private var started = 0
    private var leftAt = 0L
    private var lockScreenUp = false
    private var expectReturnUntil = 0L

    fun install(app: Application) {
        locked = enabled // cold start locks
        app.registerActivityLifecycleCallbacks(this)
    }

    fun canUse(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /** Turning the setting on from inside the app: you're already in, so don't lock right now. */
    fun onEnabled() { locked = false }

    fun unlock() {
        locked = false
        lockScreenUp = false
    }

    fun lockScreenClosed() { lockScreenUp = false }

    /** Call right before the app opens a picker / share sheet / system dialog. */
    fun expectExternal() {
        expectReturnUntil = System.currentTimeMillis() + 10 * 60_000L
    }

    // ------------------------------------------------------------ lifecycle

    override fun onActivityCreated(a: Activity, b: Bundle?) = applyRecentsPreview(a)

    override fun onActivityStarted(a: Activity) {
        if (a is LockActivity) return
        if (started == 0 && leftAt > 0L) {
            val away = System.currentTimeMillis() - leftAt
            val expected = expectReturnUntil > leftAt
            if (enabled && !expected && away >= timeoutMs) locked = true
            leftAt = 0L
            expectReturnUntil = 0L
        }
        started++
    }

    override fun onActivityResumed(a: Activity) {
        applyRecentsPreview(a)
        if (a is LockActivity) return
        if (enabled && locked && !lockScreenUp) {
            lockScreenUp = true
            a.startActivity(Intent(a, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
            a.overridePendingTransition(0, 0)
        }
    }

    override fun onActivityStopped(a: Activity) {
        if (a is LockActivity) return
        started = (started - 1).coerceAtLeast(0)
        if (started == 0 && !a.isChangingConfigurations && !lockScreenUp) leftAt = System.currentTimeMillis()
    }

    override fun onActivityPaused(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}

    private fun applyRecentsPreview(a: Activity) {
        if (Build.VERSION.SDK_INT >= 33) a.setRecentsScreenshotEnabled(!enabled)
    }
}
