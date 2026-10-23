package com.getisolated.canvasos

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executor

/**
 * The lyrics of the track that plays, over the home screen (the Paroles button of [HomePlayer]):
 * the cover, title and artist top left, then the lines down the left of the screen, the one
 * being sung white and the others dimmed, gliding up as playback goes on ([Lyrics], LRCLIB).
 * Lyrics that are not synced stand still: Up and Down move through them. Back closes it; so does
 * the end of playback. Only the UI thread touches it; the lookup runs on [io].
 */
class LyricsView(ctx: Context, private val io: Executor, private val onClose: () -> Unit) : FrameLayout(ctx) {
    private val cover = ImageView(ctx)
    private val title = ctx.text(22f, weight = 700).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val artist = ctx.text(17f, DIM_TEXT, weight = 500).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val status = ctx.text(26f, DIM_TEXT, weight = 500).apply { gravity = Gravity.CENTER; visibility = GONE }
    /** The lines, one TextView each, moved together so the current one sits at [FOCUS_Y] of the screen. */
    private val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val lane = FrameLayout(ctx).apply { clipChildren = true; addView(column, LayoutParams(-1, -2)) }
    private val glide = Tween()
    private var lines: List<TextView> = emptyList()
    private var result: Lyrics.Result? = null
    private var trackKey: String? = null
    private var now: NowPlaying? = null
    private var current = -1
    private val tick = Runnable { follow() }

    init {
        setBackgroundColor(Color.argb(205, 0, 0, 0))
        isFocusable = true
        isClickable = true
        // Above the Control Center's host (101 dp) and under sheets (200 dp) and banners (300 dp); no shadow.
        translationZ = ctx.dp(150).toFloat()
        outlineProvider = null
        val side = (resources.displayMetrics.widthPixels * 0.0583f).toInt()
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(cover, LinearLayout.LayoutParams(ctx.dp(COVER_DP), ctx.dp(COVER_DP)).apply { marginEnd = ctx.dp(14) })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(artist, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(2) })
            }, LinearLayout.LayoutParams(ctx.dp(520), -2))
        }
        addView(head, LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { leftMargin = side; topMargin = ctx.dp(26) })
        addView(lane, LayoutParams(-1, -1).apply { leftMargin = side; rightMargin = side })
        addView(status, LayoutParams(-1, -2, Gravity.CENTER))
        setOnKeyListener { _, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> { step(-1); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { step(1); true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
                else -> false
            }
        }
    }

    /**
     * The track read now ([HomePlayer.onReading], every 2 s, and once on opening): a new track
     * is looked up, the position followed; nothing playing closes the view.
     */
    fun update(n: NowPlaying?) {
        if (n == null) { close(); return }
        now = n
        val key = Lyrics.key(n.title, n.sub, n.duration)
        if (key != trackKey) {
            trackKey = key
            title.text = n.title
            artist.text = n.sub
            cover.setImageDrawable(NowPlaying.Cover(n.artLarge ?: n.art, context.dp(10).toFloat(), context.getDrawable(R.drawable.ic_music)))
            bind(null, loading = true)
            io.execute {
                val r = Lyrics.fetch(n.title, n.sub, n.album, n.duration)
                post { if (trackKey == key) bind(r, loading = false) }
            }
        }
        follow()
    }

    /** Lays the lines out for [r]; null while [loading], or unreachable once loaded. */
    private fun bind(r: Lyrics.Result?, loading: Boolean) {
        result = r
        column.removeAllViews()
        lines = emptyList()
        current = -1
        val msg = when {
            loading -> "Paroles…"
            r == null -> "Paroles indisponibles : LRCLIB est injoignable"
            r.instrumental -> "Instrumental"
            !r.found || (!r.synced && r.plain.isNullOrBlank()) -> "Pas de paroles pour ce titre"
            else -> null
        }
        status.text = msg ?: ""
        status.visibility = if (msg != null) VISIBLE else GONE
        if (msg != null || r == null) return
        val texts = if (r.synced) r.lines.map { it.text } else r.plain!!.lines()
        lines = texts.map { t ->
            context.text(LINE_SP, DIM_TEXT, weight = 700).apply {
                text = t.ifBlank { "♪" }
                maxWidth = (resources.displayMetrics.widthPixels * 0.62f).toInt()
                setLineSpacing(0f, 1.1f)
                setShadowLayer(context.dp(6).toFloat(), 0f, 0f, Color.argb(90, 0, 0, 0))
                alpha = if (t.isBlank()) 0.5f else 1f
            }
        }
        lines.forEach { column.addView(it, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = context.dp(LINE_GAP_DP) }) }
        // Unsynced: the first line is the one to read; synced: before the first line, nothing is lit yet.
        if (!r.synced) highlight(0, animate = false) else column.post { place(0, animate = false) }
    }

    /** The line sung now, from the playback position carried forward; then again in [FOLLOW_MS]. */
    private fun follow() {
        removeCallbacks(tick)
        val r = result ?: return
        val n = now ?: return
        if (r.synced) {
            val pos = n.positionNow()
            val i = if (pos >= 0) Lyrics.lineAt(r.lines, pos) else -1
            if (i != current) highlight(i, animate = true)
            if (n.playing) postDelayed(tick, FOLLOW_MS)
        }
    }

    /** Up or Down through unsynced lyrics (synced ones follow the song). */
    private fun step(dir: Int) {
        val r = result ?: return
        if (r.synced || lines.isEmpty()) return
        highlight((current + dir).coerceIn(0, lines.size - 1), animate = true)
    }

    private fun highlight(i: Int, animate: Boolean) {
        lines.getOrNull(current)?.setTextColor(DIM_TEXT)
        current = i
        lines.getOrNull(i)?.setTextColor(Color.WHITE)
        place(i.coerceAtLeast(0), animate)
    }

    /** Slides the column so line [i]'s middle sits at [FOCUS_Y] of the lane. */
    private fun place(i: Int, animate: Boolean) {
        val v = lines.getOrNull(i) ?: return
        val target = lane.height * FOCUS_Y - (v.top + v.height / 2f)
        val from = column.translationY
        if (!animate || lane.height == 0) { glide.cancel(); column.translationY = target; return }
        glide.start(GLIDE_MS, Motion.ease) { f -> column.translationY = lerp(from, target, f) }
    }

    fun close() {
        removeCallbacks(tick)
        glide.cancel()
        onClose()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        glide.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        const val COVER_DP = 64
        const val LINE_SP = 34f
        const val LINE_GAP_DP = 14
        /** Where the current line sits, as a fraction of the screen's height. */
        const val FOCUS_Y = 0.46f
        const val FOLLOW_MS = 150L
        const val GLIDE_MS = 420L
        private const val DIM_TEXT = 0x66FFFFFF
    }
}
