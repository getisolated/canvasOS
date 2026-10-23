package com.getisolated.canvasos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executor
import kotlin.math.sin

/**
 * The picture the home screen shows behind its header, for glass baked over it: the wallpaper,
 * or the Top Shelf's picture while the shelf is up. [root] is the view the home scene is drawn
 * in; [w] × [h] the screen.
 */
class SceneSource(val root: View, val w: Int, val h: Int, val picture: () -> Bitmap?)

/**
 * The home screen's small player, top left ([Prefs.homePlayer], with [Prefs.nowPlaying]): a glass
 * capsule with the track's cover, its title and artist, and three bars that move while it plays.
 * No controls: OK opens the app that plays (the Control Center keeps play, pause and skip). It
 * reads the sessions as the Control Center's player does ([NowPlaying.Reader]), every [POLL_MS]
 * while [start]ed (the home screen in front), and stands aside, gone, while nothing plays or is
 * paused. A title or an artist too long for its line slides across it, faded at both ends, after
 * a pause at its start (Android's own marquee).
 *
 * The cover's size, inset and corners, and the type, follow iOS's capsule ([NowPlaying.capsuleArt]).
 * The glass is Liquid Glass baked over the home picture ([LiquidGlass.bake]), as the dock's, again
 * when the capsule's width or the picture changes.
 *
 * Focused, a round Paroles button slides out from behind the capsule, to its right ([Prefs.lyrics]):
 * OK on it shows the lyrics ([LyricsView]).
 */
class HomePlayer(ctx: Context, private val io: Executor, private val scene: SceneSource, private val onOpen: (String) -> Unit) : LinearLayout(ctx) {
    private val height = ctx.dp(HEIGHT_DP)
    private val art = NowPlaying.capsuleArt(height)
    private val reader = NowPlaying.Reader(ctx, art.size, art.size) { post { poll() } }

    /** The focusable capsule itself. */
    val capsule = Capsule()
    /** The Paroles button, out only while the capsule or itself has focus. */
    val lyrics = RoundButton(R.drawable.ic_lyrics, "Paroles")

    /** What shows, or null while the capsule is gone. */
    var now: NowPlaying? = null
        private set
    /** Whether readings are wanted at all (the settings); off, the capsule goes at once. */
    private var enabled = false
    /** Whether the Paroles button may come out ([Prefs.lyrics]). */
    var lyricsEnabled = true
    private var started = false
    private var reading = false
    /** The capsule had focus as it went: the home screen puts focus back on its grid. */
    var onLost: (() -> Unit)? = null
    /** Down from the capsule or the button. */
    var onDown: (() -> Unit)? = null
    /** Right from the button (or from the capsule while the button is in): the corner buttons. */
    var onRight: (() -> Unit)? = null
    /** OK on the Paroles button. */
    var onLyrics: (() -> Unit)? = null
    /** Every reading, as it lands ([LyricsView] follows the track and its position). */
    var onReading: ((NowPlaying?) -> Unit)? = null
    private val tick = Runnable { poll() }
    private val settle = Runnable { reveal() }

    /** Whether the capsule is on screen (focusable). */
    val shown get() = visibility == VISIBLE

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        visibility = GONE
        alpha = 0f
        // The button is drawn under the capsule, so it slides out from behind it.
        isChildrenDrawingOrderEnabled = true
        addView(capsule, LayoutParams(-2, height))
        addView(lyrics, LayoutParams(height, height).apply { marginStart = ctx.dp(BUTTON_GAP_DP) })
        lyrics.visibility = INVISIBLE
        lyrics.alpha = 0f
        lyrics.setOnClickListener { onLyrics?.invoke() }
        lyrics.setOnKeyListener { v, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { capsule.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { onRight?.invoke(); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { onDown?.invoke(); true }
                KeyEvent.KEYCODE_DPAD_UP -> { Motion.bump(v, 0, -1); true }
                else -> false
            }
        }
        capsule.setOnKeyListener { v, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (lyrics.visibility == VISIBLE) lyrics.requestFocus() else onRight?.invoke(); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { onDown?.invoke(); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { Motion.bump(v, -1, 0); true }
                KeyEvent.KEYCODE_DPAD_UP -> { Motion.bump(v, 0, -1); true }
                else -> false
            }
        }
    }

    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int) = childCount - 1 - drawingPosition

    /** Focus on the capsule, for the home screen's navigation. */
    fun focusCapsule() = capsule.requestFocus()

    /** The focused one of the two, or none: the button comes out or goes back accordingly, once focus has settled. */
    private fun focusChanged() {
        removeCallbacks(settle)
        post(settle)
    }

    private fun reveal() {
        val out = lyricsEnabled && shown && (capsule.hasFocus() || lyrics.hasFocus())
        val hidden = -(lyrics.width + context.dp(BUTTON_GAP_DP)).toFloat()
        if (out) {
            if (lyrics.visibility != VISIBLE) { lyrics.visibility = VISIBLE; lyrics.translationX = hidden }
            lyrics.animate().cancel()
            lyrics.animate().translationX(0f).alpha(1f).setDuration(260).setInterpolator(Motion.ease).start()
        } else if (lyrics.visibility == VISIBLE) {
            lyrics.animate().cancel()
            lyrics.animate().translationX(hidden).alpha(0f).setDuration(200).setInterpolator(Motion.ease)
                .withEndAction { if (!(capsule.hasFocus() || lyrics.hasFocus())) lyrics.visibility = INVISIBLE }.start()
        }
    }

    /** Reads now and then every [POLL_MS]; [on] false (the setting is off) hides the capsule instead. */
    fun start(on: Boolean) {
        enabled = on
        started = true
        removeCallbacks(tick)
        if (!on) { show(null); return }
        poll()
    }

    /** The home screen left the front: no more readings, the bars at rest. */
    fun stop() {
        started = false
        removeCallbacks(tick)
        capsule.bars.animating = false
    }

    fun shutdown() {
        stop()
        reader.shutdown()
    }

    /** The picture behind changed (wallpaper, shelf): the glass is baked again. */
    fun refreshGlass() {
        capsule.glass.refresh()
        lyrics.glass.refresh()
    }

    /** A reading off the UI thread, then the capsule follows it; the next one in [POLL_MS]. */
    fun poll() {
        removeCallbacks(tick)
        if (!started || !enabled) return
        if (!reading) {
            reading = true
            io.execute {
                val n = runCatching { reader.read() }.getOrNull()
                post {
                    reading = false
                    if (started && enabled) show(n)
                }
            }
        }
        postDelayed(tick, POLL_MS)
    }

    private fun show(n: NowPlaying?) {
        val old = now
        now = n
        onReading?.invoke(n)
        if (n == null) {
            if (visibility == VISIBLE) {
                val had = hasFocus()
                animate().cancel()
                animate().alpha(0f).setDuration(200).withEndAction { if (now == null) { visibility = GONE; lyrics.visibility = INVISIBLE; lyrics.alpha = 0f } }.start()
                if (had) onLost?.invoke()
            }
            capsule.bars.animating = false
            return
        }
        capsule.bind(n, old, visibility != VISIBLE)
        // Gone, or still fading out (the session came back within 200 ms): it fades in from where it is.
        if (visibility != VISIBLE || alpha < 1f) {
            visibility = VISIBLE
            animate().cancel()
            animate().alpha(1f).setDuration(250).start()
        }
        refreshGlass()
    }

    /** The capsule: cover, two lines and the bars, on baked glass; white with dark ink when focused. */
    inner class Capsule : LinearLayout(context) {
        private val cover = ImageView(context)
        private val title = context.text(13f, weight = 600).apply { marquee(this, art.titlePx) }
        private val artist = context.text(11.5f, SUB, weight = 500).apply { marquee(this, art.subPx) }
        val bars = Bars(context)
        private val idle = GradientDrawable().apply {
            setColor(Color.argb(150, 22, 22, 26))
            cornerRadius = height / 2f
            setStroke(context.dp(1), Color.argb(50, 255, 255, 255))
        }
        private val lit = rounded(Color.WHITE, height / 2f)
        val glass = BakedGlass(this, io, scene, height / 2f) { if (!isFocused) background = it }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = idle
            clipRounded(height / 2f)
            contentDescription = "Lecture"
            setPadding(art.inset, 0, context.dp(14), 0)
            addView(cover, LayoutParams(art.size, art.size).apply { marginEnd = art.inset })
            val column = LinearLayout(context).apply {
                orientation = VERTICAL
                addView(title, LayoutParams(-2, -2))
                addView(artist, LayoutParams(-2, -2).apply { topMargin = art.gapPx })
            }
            addView(column, LayoutParams(-2, -2))
            addView(bars, LayoutParams(context.dp(14), context.dp(14)).apply { marginStart = context.dp(12) })
            setOnClickListener { now?.let { onOpen(it.pkg) } }
            setOnFocusChangeListener { _, on ->
                background = if (on) lit else glass.drawable ?: idle
                title.setTextColor(if (on) INK else Color.WHITE)
                artist.setTextColor(if (on) INK_SUB else SUB)
                bars.ink = on
                tweenScale(if (on) 1.05f else 1f, if (on) 260 else 200, if (on) Motion.spring else Motion.ease)
                focusChanged()
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            glass.refresh()
        }

        fun bind(n: NowPlaying, old: NowPlaying?, fresh: Boolean) {
            // Text and cover only when they changed: a marquee restarts with its text, and nothing redraws every 2 s.
            if (!n.sameAs(old) || fresh) {
                put(title, n.title)
                put(artist, n.sub)
                contentDescription = "Lecture : ${n.title}, ${n.sub}"
                if (old == null || old.art !== n.art || fresh) {
                    cover.setImageDrawable(NowPlaying.Cover(n.art, art.radius.toFloat(), context.getDrawable(R.drawable.ic_music)))
                }
            }
            bars.animating = n.playing
        }

        /** One line at [px], sliding when too long for [TEXT_MAX_DP], faded at both ends, after Android's pause at its start. */
        private fun marquee(t: TextView, px: Float) {
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
            t.maxWidth = t.context.dp(TEXT_MAX_DP)
            t.isSingleLine = true
            t.ellipsize = TextUtils.TruncateAt.MARQUEE
            t.marqueeRepeatLimit = -1
            t.isHorizontalFadingEdgeEnabled = true
            t.setFadingEdgeLength(t.context.dp(14))
            t.isSelected = true
        }

        /** New text restarts its marquee from the start (and its pause), which a reselect does. */
        private fun put(t: TextView, s: String) {
            if (t.text.toString() == s) return
            t.text = s
            t.isSelected = false
            t.isSelected = true
        }
    }

    /** A round glass button beside the capsule (Paroles): a glyph, white with dark ink when focused. */
    inner class RoundButton(icon: Int, label: String) : FrameLayout(context) {
        private val img = ImageView(context).apply { setImageResource(icon); setColorFilter(Color.WHITE) }
        private val idle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(150, 22, 22, 26))
            setStroke(context.dp(1), Color.argb(50, 255, 255, 255))
        }
        private val lit = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        val glass = BakedGlass(this, io, scene, height / 2f) { if (!isFocused) background = it }

        init {
            isFocusable = true
            isClickable = true
            background = idle
            clipRounded(height / 2f)
            contentDescription = label
            addView(img, LayoutParams(context.dp(20), context.dp(20), Gravity.CENTER))
            setOnFocusChangeListener { _, on ->
                background = if (on) lit else glass.drawable ?: idle
                img.setColorFilter(if (on) INK else Color.WHITE)
                tweenScale(if (on) 1.1f else 1f, if (on) 260 else 200, if (on) Motion.spring else Motion.ease)
                focusChanged()
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            glass.refresh()
        }
    }

    /**
     * Three bars, as beside the playing track in Apple Music: they rise and fall while the track
     * plays, and rest low while it is paused. Drawn from a clock, not from the sound: canvasOS
     * does not listen to the audio (no RECORD_AUDIO). About 20 frames a second, a 14 dp view.
     */
    class Bars(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val rect = RectF()
        private val w = ctx.dp(3).toFloat()
        private val gap = ctx.dp(2).toFloat()
        private val frame = Runnable { invalidate() }

        var ink = false
            set(v) { field = v; paint.color = if (v) INK else Color.WHITE; invalidate() }

        var animating = false
            set(v) {
                if (field == v) return
                field = v
                removeCallbacks(frame)
                invalidate()
            }

        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            val t = SystemClock.uptimeMillis() / 1000.0
            for (i in 0 until 3) {
                val level = if (animating) levelAt(t, i) else REST
                val x = (width - (3 * w + 2 * gap)) / 2f + i * (w + gap)
                rect.set(x, h - h * level, x + w, h)
                c.drawRoundRect(rect, w / 2f, w / 2f, paint)
            }
            if (animating && isShown) postDelayed(frame, 50)
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(frame)
            super.onDetachedFromWindow()
        }

        private companion object {
            const val REST = 0.3f
        }
    }

    /**
     * Liquid Glass baked for one view over the home picture ([LiquidGlass.bake]), as the dock's:
     * again, off the UI thread, whenever the view's size or the picture changes; [onReady] gets
     * each slab as a drawable. The view's place is read from its layout, not from its transforms
     * (a focused view is scaled).
     */
    class BakedGlass(private val view: View, private val io: Executor, private val scene: SceneSource, private val radius: Float, private val onReady: (Drawable) -> Unit) {
        /** The last slab baked, to put back when focus leaves. */
        var drawable: Drawable? = null
            private set
        private var key: String? = null
        private var baking = false
        private var again = false

        fun refresh() {
            val w = view.width
            val h = view.height
            val pic = scene.picture()
            if (w <= 0 || h <= 0 || pic == null || pic.isRecycled) return
            val rect = layoutRect(view, scene.root)
            val k = "${System.identityHashCode(pic)}|$rect"
            if (k == key) return
            key = k
            if (baking) { again = true; return }
            bake(pic, rect, k)
        }

        private fun bake(pic: Bitmap, rect: Rect, k: String) {
            baking = true
            io.execute {
                val bmp = runCatching { LiquidGlass.bake(pic, scene.w, scene.h, rect, radius).also { it.prepareToDraw() } }.getOrNull()
                view.post {
                    baking = false
                    if (bmp != null && k == key) {
                        drawable = BitmapDrawable(view.resources, bmp)
                        onReady(drawable!!)
                    }
                    if (again) { again = false; key = null; refresh() }
                }
            }
        }

        /** [v]'s rectangle in [root]'s coordinates, from the layout alone. */
        private fun layoutRect(v: View, root: View): Rect {
            var x = 0
            var y = 0
            var at: View? = v
            while (at != null && at !== root) {
                x += at.left
                y += at.top
                at = at.parent as? View
            }
            return Rect(x, y, x + v.width, y + v.height)
        }
    }

    companion object {
        /** The capsule's height, in dp: the corner buttons' row is centred on it. */
        const val HEIGHT_DP = 44
        /** Between the capsule and the Paroles button. */
        const val BUTTON_GAP_DP = 8
        /** The title and the artist slide once wider than this. */
        const val TEXT_MAX_DP = 220
        const val POLL_MS = 2_000L
        // Literals, not Color.argb: the unit tests load this companion without Android.
        private const val SUB = 0xCDFFFFFF.toInt()
        private const val INK = 0xE1000000.toInt()
        private const val INK_SUB = 0x96000000.toInt()

        /**
         * The height of bar [i] (0 to 1) at [seconds], a sum of two sines at speeds of their own
         * per bar, so the three never move together, between about 0.2 and 1.
         */
        fun levelAt(seconds: Double, i: Int): Float {
            val a = sin(seconds * (5.1 + i * 1.3) + i * 2.1)
            val b = sin(seconds * (2.3 + i * 0.7) + i * 0.8)
            return (0.6 + 0.4 * (a * 0.6 + b * 0.4)).toFloat().coerceIn(0.2f, 1f)
        }
    }
}
