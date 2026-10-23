package com.getisolated.canvasos

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * A picture to pick with one press, its name under it: the backgrounds of [WallpaperActivity].
 * Focused, it grows inside a white ring; picked, a blue badge in its corner shows a check (or a place number).
 * [w] and [h] are the picture's size in dp.
 */
class PickCard @JvmOverloads constructor(ctx: Context, w: Int = 128, h: Int = 72) : LinearLayout(ctx) {
    val image = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = rounded(Color.argb(40, 255, 255, 255), dp(12).toFloat())
        clipRounded(dp(12).toFloat())
    }
    private val frame = FrameLayout(ctx)
    private val ring = View(ctx)
    private val check = ctx.text(14f, Color.WHITE, bold = true).apply {
        text = "✓"
        gravity = Gravity.CENTER
        background = rounded(Color.rgb(10, 132, 255), dp(11).toFloat())
    }
    private val empty = ctx.text(13f, Color.argb(170, 255, 255, 255)).apply { gravity = Gravity.CENTER }
    val label = ctx.text(14f, Color.argb(200, 255, 255, 255), weight = 500).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isFocusable = true
        isClickable = true
        clipChildren = false
        frame.clipChildren = false
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        frame.addView(empty, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(8), 0, dp(8), 0) })
        frame.addView(ring, FrameLayout.LayoutParams(-1, -1))
        frame.addView(check, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
        addView(frame, LayoutParams(dp(w), dp(h)))
        addView(label, LayoutParams(-1, -2).apply { topMargin = dp(7) })
        check.visibility = GONE
        setOnFocusChangeListener { _, f ->
            ring.background = if (f) GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setStroke(dp(3), Color.WHITE)
            } else null
            label.setTextColor(if (f) Color.WHITE else Color.argb(200, 255, 255, 255))
            animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f)
                .translationZ(if (f) dp(10).toFloat() else 0f).setDuration(160).setInterpolator(Motion.ease).start()
        }
    }

    fun selected(on: Boolean) = badge(if (on) "✓" else null)

    /** The corner badge with [text] ("✓", or a dock place "1"…"5"); null hides it. */
    fun badge(text: String?) {
        check.text = text.orEmpty()
        check.visibility = if (text == null) GONE else VISIBLE
    }

    fun emptyText(s: String?) { empty.text = s.orEmpty(); empty.visibility = if (s == null) GONE else VISIBLE }
}
