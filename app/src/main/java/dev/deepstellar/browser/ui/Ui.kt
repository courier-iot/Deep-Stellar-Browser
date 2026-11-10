package dev.deepstellar.browser.ui

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import dev.deepstellar.browser.R

object Format {
    fun bytes(b: Long): String {
        if (b < 0) return "?"
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format(java.util.Locale.US, if (v < 10) "%.1f %s" else "%.0f %s", v, units[i])
    }

    fun speed(bps: Long): String = bytes(bps) + "/s"
}

/** Small helpers so lists can be built in code without a pile of layout files. */
object Ui {
    fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    /** Palette color ids are resolved through the active theme, so every theme variant recolors the UI. */
    private val PALETTE = mapOf(
        R.color.void_black to R.attr.dsBg,
        R.color.void_surface to R.attr.dsSurface,
        R.color.void_surface_2 to R.attr.dsSurface2,
        R.color.void_stroke to R.attr.dsStroke,
        R.color.starlight to R.attr.dsText,
        R.color.starlight_dim to R.attr.dsTextDim,
        R.color.nebula_violet to R.attr.dsAccent,
        R.color.cosmic_blue to R.attr.dsAccent2,
        R.color.nova_pink to R.attr.dsAccent3,
    )

    fun color(ctx: Context, id: Int): Int {
        val attr = PALETTE[id] ?: return ContextCompat.getColor(ctx, id)
        val tv = TypedValue()
        return if (ctx.theme.resolveAttribute(attr, tv, true)) tv.data else ContextCompat.getColor(ctx, id)
    }

    fun title(ctx: Context, text: String, sizeSp: Float = 15f): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(color(ctx, R.color.starlight))
        textSize = sizeSp
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    fun sub(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(color(ctx, R.color.starlight_dim))
        textSize = 12f
        typeface = Typeface.MONOSPACE
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    fun header(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text.uppercase()
        setTextColor(color(ctx, R.color.cosmic_blue))
        textSize = 11f
        letterSpacing = 0.25f
        setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 20), dp(ctx, 8))
    }

    fun iconButton(ctx: Context, icon: Int, desc: String, onClick: () -> Unit): ImageButton = ImageButton(ctx).apply {
        setImageResource(icon)
        contentDescription = desc
        val tv = TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        setBackgroundResource(tv.resourceId)
        ImageViewCompat.setImageTintList(this, android.content.res.ColorStateList.valueOf(color(ctx, R.color.starlight_dim)))
        layoutParams = LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44))
        setOnClickListener { onClick() }
    }

    /** A card row: title + subtitle on the left, optional trailing views on the right. */
    fun row(
        ctx: Context,
        titleText: String,
        subText: String?,
        active: Boolean = false,
        onClick: (() -> Unit)? = null,
        onLongClick: (() -> Unit)? = null,
        trailing: List<View> = emptyList(),
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(if (active) R.drawable.bg_card_active else R.drawable.bg_card)
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 6), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(ctx, 14), dp(ctx, 5), dp(ctx, 14), dp(ctx, 5))
            }
            onClick?.let { c -> setOnClickListener { c() } }
            onLongClick?.let { c -> setOnLongClickListener { c(); true } }
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(title(ctx, titleText))
            if (!subText.isNullOrEmpty()) addView(sub(ctx, subText))
        }
        row.addView(texts)
        trailing.forEach { row.addView(it) }
        return row
    }
}
