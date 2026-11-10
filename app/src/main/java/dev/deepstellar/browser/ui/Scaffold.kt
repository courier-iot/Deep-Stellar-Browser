package dev.deepstellar.browser.ui

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import dev.deepstellar.browser.R

/** Toolbar + content, edge-to-edge safe. Returns the toolbar so screens can add actions. */
fun AppCompatActivity.scaffold(title: String, content: View): MaterialToolbar {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Ui.color(this@scaffold, R.color.void_black))
    }
    val bar = MaterialToolbar(this).apply {
        this.title = title
        setTitleTextColor(Ui.color(this@scaffold, R.color.starlight))
        setNavigationIcon(R.drawable.ic_back)
        navigationIcon?.setTint(Ui.color(this@scaffold, R.color.starlight))
        setNavigationOnClickListener { finish() }
        setBackgroundColor(Ui.color(this@scaffold, R.color.void_black))
    }
    root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(b.left, b.top, b.right, b.bottom)
        insets
    }
    setContentView(root)
    return bar
}
