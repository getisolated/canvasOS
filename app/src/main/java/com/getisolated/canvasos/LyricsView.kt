package com.getisolated.canvasos

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executor

/**
 * The lyrics of the track that plays, as a layer of the home scene (the Paroles button of
 * [HomePlayer] shows and hides it): the wallpaper darkened, then the lines down the screen, the
 * one being sung white and the others dimmed, gliding as playback goes on ([Lyrics], LRCLIB).
 * Left, centred or right ([align], Réglages → Lecture et notifications → Position des paroles).
 * Synced lyrics only: a track with plain text alone counts as having none. The layer never takes
 * focus. The home screen keeps it told of the track and its position ([update]); only the UI
 * thread touches it, the lookup runs on [io].
 */
class LyricsView(ctx: Context, private val io: Executor) : FrameLayout(ctx) {
    /** [Prefs.LYRICS_LEFT], [Prefs.LYRICS_CENTER] or [Prefs.LYRICS_RIGHT]; takes effect at the next track or [show]. */
    var align = Prefs.LYRICS_LEFT
        set(v) { if (field != v) { field = v; relayout() } }
    /**
     * Whether LRCLIB has lyrics for the track on screen: null while a new track is looked up,
     * false when LRCLIB has none or cannot be reached, true otherwise. The home screen shows the
     * Paroles button only on true, and leaves the lyrics on false (not while unknown).
     */
    var onAvailability: ((Boolean?) -> Unit)? = null
    /** Tracks are looked up as they start, lyrics on screen or not ([Prefs.lyrics]); off, nothing is asked of LRCLIB. */
    var lookup = true

    private val shade = View(ctx).apply { setBackgroundColor(Color.argb(SHADE_ALPHA, 0, 0, 0)) }
    private val status = ctx.text(26f, DIM_TEXT, weight = 500).apply { gravity = Gravity.CENTER; visibility = GONE }
    /** The lines, one TextView each, moved together so the current one sits at [FOCUS_Y] of the lane. */
    private val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    /**
     * Holds the column and clips it. It measures the column without a height limit: a FrameLayout
     * would cap it at its own height, and the lines past the screen would come out 0 px tall
     * (the lyrics then stopped moving once the song reached them).
     */
    private val lane = object : FrameLayout(ctx) {
        /** The top [FADE_TOP_DP] of the lane fades the lines out as they leave upward; the bottom cuts clean. */
        private val fade = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }
        private var fadePx = 0f

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            fadePx = context.dp(FADE_TOP_DP).toFloat()
            fade.shader = android.graphics.LinearGradient(0f, 0f, 0f, fadePx, 0, -1, android.graphics.Shader.TileMode.CLAMP)
        }

        override fun dispatchDraw(canvas: android.graphics.Canvas) {
            if (fadePx <= 0f) { super.dispatchDraw(canvas); return }
            val n = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            super.dispatchDraw(canvas)
            canvas.drawRect(0f, 0f, width.toFloat(), fadePx, fade)
            canvas.restoreToCount(n)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            column.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            setMeasuredDimension(w, h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            column.layout(0, 0, column.measuredWidth, column.measuredHeight)
        }
    }.apply { clipChildren = true; addView(column, LayoutParams(-1, -2)) }
    private val glide = Tween()
    private var lines: List<TextView> = emptyList()
    private var result: Lyrics.Result? = null
    private var trackKey: String? = null
    /** When LRCLIB could not be reached for the track on screen; it is asked again after [RETRY_MS]. */
    private var unreachableAt = 0L
    private var loggedAt = 0L
    private var now: NowPlaying? = null
    private var current = -1
    private val tick = Runnable { follow() }

    val shown get() = visibility == VISIBLE

    init {
        visibility = GONE
        alpha = 0f
        val side = (resources.displayMetrics.widthPixels * 0.0583f).toInt()
        addView(shade, LayoutParams(-1, -1))
        addView(lane, LayoutParams(-1, -1).apply { leftMargin = side; rightMargin = side; topMargin = ctx.dp(LANE_TOP_DP) })
        addView(status, LayoutParams(-1, -2, Gravity.CENTER))
    }

    /** Fades the layer in and follows the song from where it is. */
    fun show() {
        if (shown) return
        visibility = VISIBLE
        animate().cancel()
        animate().alpha(1f).setDuration(300).start()
        follow()
    }

    /** Fades the layer out; what it showed stays for the next time. */
    fun hide() {
        if (!shown) return
        removeCallbacks(tick)
        animate().cancel()
        animate().alpha(0f).setDuration(220).withEndAction { if (!shown) return@withEndAction; visibility = GONE }.start()
        visibility = INVISIBLE
    }

    /** The layer's brightness while it shows: dimmed while the user is down in the grid, full with the capsule. */
    fun dim(to: Float) {
        if (!shown) return
        animate().cancel()
        animate().alpha(to).setDuration(250).start()
    }

    /**
     * The track read now ([HomePlayer.onReading], every 2 s, and when the layer shows): a new
     * track is looked up, the position followed. Null (nothing plays) leaves the lines as they are;
     * the home screen hides the layer then.
     */
    fun update(n: NowPlaying?) {
        now = n
        if (n == null) { removeCallbacks(tick); return }
        if (!lookup) return
        val key = Lyrics.key(n.title, n.sub, n.duration)
        val retry = unreachableAt != 0L && android.os.SystemClock.elapsedRealtime() - unreachableAt > RETRY_MS
        if (key != trackKey || retry) {
            trackKey = key
            unreachableAt = 0L
            bind(null, loading = true)
            onAvailability?.invoke(null)
            io.execute {
                val r = Lyrics.fetch(n.title, n.sub, n.album, n.duration)
                post {
                    if (trackKey != key) return@post
                    if (r == null) unreachableAt = android.os.SystemClock.elapsedRealtime()
                    android.util.Log.d("canvasOSLyrics", "lookup ${n.title}: ${if (r == null) "unreachable" else if (r.synced) "${r.lines.size} synced lines" else if (r.found) "unsynced" else "none"}")
                    bind(r, loading = false)
                    onAvailability?.invoke(r != null && r.synced)
                    follow()
                }
            }
        }
        follow()
    }

    /** Whether LRCLIB had synced lyrics for the track on screen. */
    val available: Boolean get() = result?.synced == true

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
            !r.synced -> "Pas de paroles synchronisées pour ce titre"
            else -> null
        }
        status.text = msg ?: ""
        status.visibility = if (msg != null) VISIBLE else GONE
        if (msg != null || r == null) return
        val texts = r.lines.map { it.text }
        val g = gravity()
        column.gravity = g
        lines = texts.map { t ->
            context.text(LINE_SP, DIM_TEXT, weight = 700).apply {
                text = t.ifBlank { "♪" }
                maxWidth = (resources.displayMetrics.widthPixels * 0.62f).toInt()
                gravity = g
                setLineSpacing(0f, 1.1f)
                setShadowLayer(context.dp(6).toFloat(), 0f, 0f, Color.argb(90, 0, 0, 0))
                alpha = if (t.isBlank()) 0.5f else 1f
            }
        }
        lines.forEach { column.addView(it, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = context.dp(LINE_GAP_DP) }) }
        column.post { place(0, animate = false) }
    }

    private fun gravity() = when (align) {
        Prefs.LYRICS_CENTER -> Gravity.CENTER_HORIZONTAL
        Prefs.LYRICS_RIGHT -> Gravity.END
        else -> Gravity.START
    }

    /** The alignment changed: the lines are laid out again from what is kept. */
    private fun relayout() {
        val r = result ?: return
        bind(r, loading = false)
        follow()
    }

    /** The line sung now, from the playback position carried forward; then again in [FOLLOW_MS] while the layer shows. */
    private fun follow() {
        removeCallbacks(tick)
        if (!shown) return
        val r = result ?: return
        val n = now ?: return
        if (!r.synced) return
        val pos = n.positionNow()
        val i = if (pos >= 0) Lyrics.lineAt(r.lines, pos) else -1
        if (android.os.SystemClock.elapsedRealtime() - loggedAt > 5_000) {
            loggedAt = android.os.SystemClock.elapsedRealtime()
            android.util.Log.d("canvasOSLyrics", "follow pos=$pos line=$i/${r.lines.size} playing=${n.playing} speed=${n.speed} dur=${n.duration} ty=${column.translationY}")
        }
        if (i != current) highlight(i, animate = true)
        postDelayed(tick, FOLLOW_MS)
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
        if (lane.height == 0 || v.height == 0) { column.post { place(i, false) }; return }
        val target = lane.height * FOCUS_Y - (v.top + v.height / 2f)
        val from = column.translationY
        if (!animate) { glide.cancel(); column.translationY = target; return }
        glide.start(GLIDE_MS, Motion.ease) { f -> column.translationY = lerp(from, target, f) }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        glide.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        /** How dark the wallpaper goes under the lines (0 to 255). */
        const val SHADE_ALPHA = 165
        const val LINE_SP = 34f
        const val LINE_GAP_DP = 14
        /** Under the capsule: the lane starts here. */
        const val LANE_TOP_DP = 84
        /** Lines leaving upward fade out over this much of the lane's top, about one line. */
        const val FADE_TOP_DP = 50
        /** Where the current line sits, as a fraction of the lane's height. */
        const val FOCUS_Y = 0.4f
        const val FOLLOW_MS = 200L
        /** LRCLIB out of reach: asked again for the same track after this long. */
        const val RETRY_MS = 15_000L
        const val GLIDE_MS = 420L
        private const val DIM_TEXT = 0x66FFFFFF
    }
}
