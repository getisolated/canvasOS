package com.getisolated.canvasos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
 * The home scene the glass stands on: [root] is drawn to sample what is behind the glass, with
 * [hidden] (the header, which holds the glass itself) left out of the sample.
 */
class SceneSource(val root: View, val hidden: () -> List<View>)

/**
 * The home screen's small player, top left ([Prefs.homePlayer], with [Prefs.nowPlaying]): a glass
 * capsule with the track's cover, its title and artist, and six bars that move while it plays.
 * No controls: OK opens the app that plays (the Control Center keeps play, pause and skip). It
 * reads the sessions as the Control Center's player does ([NowPlaying.Reader]), every [POLL_MS]
 * while [start]ed (the home screen in front), and stands aside, gone, while nothing plays or is
 * paused. A title or an artist too long for its line slides across it, faded at both ends, after
 * a pause at its start (Android's own marquee).
 *
 * The cover's inset and corners, and the type, follow iOS's capsule ([NowPlaying.capsuleArt]); the
 * cover is [ART_RATIO] of the height, a little smaller than iOS's. The glass is the Control
 * Center's live glass ([LiveGlass]) over a blurred sample of the scene behind the header, taken
 * again whenever the home picture changes ([refreshGlass]).
 *
 * Focused, a round Paroles button slides out from behind the capsule, to its right ([Prefs.lyrics],
 * and only when LRCLIB has lyrics for the track, [lyricsAvailable]): OK on it shows the lyrics over
 * the home screen, OK again takes them away ([LyricsView]). It goes back in with the focus.
 */
class HomePlayer(ctx: Context, private val io: Executor, private val scene: SceneSource, private val onOpen: (String) -> Unit) : LinearLayout(ctx) {
    private val capsuleH = ctx.dp(HEIGHT_DP)
    private val art = NowPlaying.capsuleArt(capsuleH, ART_RATIO)
    private val reader = NowPlaying.Reader(ctx, art.size, art.size) { post { poll() } }
    /** The blurred scene behind the header, shared by the capsule's and the button's glass. */
    private var sample: GlassSample? = null

    /** The focusable capsule itself. */
    val capsule = Capsule()
    /** The Paroles button, out while the capsule or itself has focus and the track has lyrics. */
    val lyrics = RoundButton(R.drawable.ic_lyrics, "Paroles")

    /** What shows, or null while the capsule is gone. */
    var now: NowPlaying? = null
        private set
    /** Whether readings are wanted at all (the settings); off, the capsule goes at once. */
    private var enabled = false
    /** Whether the Paroles button may come out ([Prefs.lyrics]). */
    var lyricsEnabled = true
    /** The lyrics are on screen: the button reads as pressed. */
    var lyricsOn = false
        set(v) { field = v; lyrics.pressed(v) }
    /** LRCLIB has lyrics for the track on screen ([LyricsView.onAvailability]); without, the button stays in. */
    var lyricsAvailable = false
        set(v) {
            if (field == v) return
            field = v
            // Gone while it had focus: focus steps back onto the capsule.
            if (!v && lyrics.hasFocus()) capsule.requestFocus()
            focusChanged()
        }
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
        addView(capsule, LayoutParams(-2, capsuleH))
        addView(lyrics, LayoutParams(capsuleH, capsuleH).apply { marginStart = ctx.dp(BUTTON_GAP_DP) })
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

    /** Whether the capsule or the button has focus. */
    val hasHeaderFocus get() = capsule.hasFocus() || lyrics.hasFocus()

    /** The focused one of the two, or none: the button comes out or goes back accordingly, once focus has settled. */
    private fun focusChanged() {
        removeCallbacks(settle)
        post(settle)
    }

    private fun reveal() {
        val out = lyricsEnabled && lyricsAvailable && shown && hasHeaderFocus
        val hidden = -(lyrics.width + context.dp(BUTTON_GAP_DP)).toFloat()
        if (out) {
            if (lyrics.visibility != VISIBLE) { lyrics.visibility = VISIBLE; lyrics.translationX = hidden }
            lyrics.animate().cancel()
            lyrics.animate().translationX(0f).alpha(1f).setDuration(260).setInterpolator(Motion.ease).start()
        } else if (lyrics.visibility == VISIBLE) {
            lyrics.animate().cancel()
            lyrics.animate().translationX(hidden).alpha(0f).setDuration(200).setInterpolator(Motion.ease)
                .withEndAction { if (!hasHeaderFocus) lyrics.visibility = INVISIBLE }.start()
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

    /**
     * The picture behind changed (wallpaper, shelf, the lyrics' shade): the scene behind the
     * header is sampled again and the glass drawn from it. UI thread, a few milliseconds.
     */
    fun refreshGlass() {
        // A fade of the screen is being followed ([glassFollow]): the routine sample waits for its end.
        if (SystemClock.uptimeMillis() < glassBusyUntil) return
        resample { scene.root.draw(it) }
    }

    private var glassBusyUntil = 0L

    /**
     * The screen is about to fade over [ms] on [curve] (a new wallpaper, the shelf, the lyrics'
     * shade): [finalScene] draws it as it will be at the end, and the glass fades to that in step
     * with it, as the Control Center's does, rather than catching up afterwards.
     */
    fun glassFollow(ms: Long, curve: android.animation.TimeInterpolator, finalScene: (android.graphics.Canvas) -> Unit) {
        if (!shown || width == 0) return
        glassBusyUntil = SystemClock.uptimeMillis() + ms
        capsule.glass.follow(ms, curve)
        lyrics.glass.follow(ms, curve)
        resample(finalScene)
    }

    private fun resample(draw: (android.graphics.Canvas) -> Unit) {
        if (!shown || width == 0) { sample = null; return }
        val margin = context.dp(48)
        val r = layoutRect(this, scene.root)
        val region = Rect((r.left - margin).coerceAtLeast(0), (r.top - margin).coerceAtLeast(0),
            (r.right + margin).coerceAtMost(scene.root.width), (r.bottom + margin).coerceAtMost(scene.root.height))
        sample = SceneGlass.sample(scene.root, region, scene.hidden(), draw)
        // What the glass fades to, for the log (`adb logcat -s canvasOSGlass`): the sample's mean brightness. Fades only, not the routine readings.
        sample?.takeIf { glassBusyUntil > SystemClock.uptimeMillis() }?.let { s ->
            val px = IntArray(s.bmp.width * s.bmp.height)
            s.bmp.getPixels(px, 0, s.bmp.width, 0, 0, s.bmp.width, s.bmp.height)
            var sum = 0L
            for (c in px) sum += ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000
            android.util.Log.d("canvasOSGlass", "sample ${s.bmp.width}x${s.bmp.height} lum=${sum / px.size} busyFor=${(glassBusyUntil - SystemClock.uptimeMillis()).coerceAtLeast(0)}ms")
        }
        capsule.invalidate()
        lyrics.invalidate()
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
        val fresh = visibility != VISIBLE
        capsule.bind(n, old, fresh)
        // Gone, or still fading out (the session came back within 200 ms): it fades in from where it is.
        if (fresh || alpha < 1f) {
            visibility = VISIBLE
            animate().cancel()
            animate().alpha(1f).setDuration(250).start()
        }
        // Laid out first, then sampled where it stands: every reading, so the glass never lags the picture behind.
        post { refreshGlass() }
    }

    /** The capsule: cover, two lines and the bars, on live glass; its glass lights and its ink darkens when focused. */
    inner class Capsule : LinearLayout(context) {
        private val cover = ImageView(context)
        private val title = context.text(13f, weight = 600).apply { marquee(this, art.titlePx) }
        private val artist = context.text(11.5f, SUB, weight = 500).apply { marquee(this, art.subPx) }
        val bars = Bars(context)
        val glass = LiveGlass(this, scene.root, capsuleH / 2f) { sample }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = glass
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            contentDescription = "Lecture"
            setPadding(art.inset, 0, context.dp(14), 0)
            addView(cover, LayoutParams(art.size, art.size).apply { marginEnd = art.inset })
            val column = LinearLayout(context).apply {
                orientation = VERTICAL
                addView(title, LayoutParams(-2, -2))
                addView(artist, LayoutParams(-2, -2).apply { topMargin = art.gapPx })
            }
            addView(column, LayoutParams(-2, -2))
            addView(bars, LayoutParams(context.dp(BARS_DP), context.dp(14)).apply { marginStart = context.dp(12) })
            setOnClickListener { now?.let { onOpen(it.pkg) } }
            setOnFocusChangeListener { _, on ->
                glass.light(on)
                title.setTextColor(if (on) INK else Color.WHITE)
                artist.setTextColor(if (on) INK_SUB else SUB)
                bars.ink = on
                tweenScale(if (on) 1.05f else 1f, if (on) 260 else 200, if (on) Motion.spring else Motion.ease) { invalidate() }
                focusChanged()
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // A wider capsule (a longer title) reaches further over the scene: sampled again where it now stands.
            post { refreshGlass() }
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

    /** A round glass button beside the capsule (Paroles): a glyph; lit glass and dark ink when focused, half lit while pressed on. */
    inner class RoundButton(icon: Int, label: String) : FrameLayout(context) {
        private val img = ImageView(context).apply { setImageResource(icon); setColorFilter(Color.WHITE) }
        val glass = LiveGlass(this, scene.root, capsuleH / 2f) { sample }
        private var on = false

        init {
            isFocusable = true
            isClickable = true
            background = glass
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            contentDescription = label
            addView(img, LayoutParams(context.dp(20), context.dp(20), Gravity.CENTER))
            setOnFocusChangeListener { _, f ->
                glass.light(f)
                img.setColorFilter(if (f) INK else Color.WHITE)
                tweenScale(if (f) 1.1f else 1f, if (f) 260 else 200, if (f) Motion.spring else Motion.ease) { invalidate() }
                focusChanged()
            }
        }

        /** Pressed on (the lyrics show): the glyph stays bright and the button reads as active even unfocused. */
        fun pressed(v: Boolean) {
            on = v
            img.alpha = if (v || isFocused) 1f else 0.75f
            contentDescription = if (v) "Paroles (affichées)" else "Paroles"
        }
    }

    /**
     * Six thin bars, mirrored about the middle: each grows up and down from the centre line while
     * the track plays, and rests as a dot, so a paused track shows six dots in a row. Drawn from a
     * clock, not from the sound: canvasOS does not listen to the audio (no RECORD_AUDIO). About 20
     * frames a second, a small view.
     */
    class Bars(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val rect = RectF()
        private val w = ctx.dp(2).toFloat()
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
            val mid = h / 2f
            val t = SystemClock.uptimeMillis() / 1000.0
            val x0 = (width - (COUNT * w + (COUNT - 1) * gap)) / 2f
            for (i in 0 until COUNT) {
                // At rest a dot: the bar is as tall as it is wide.
                val half = if (animating) maxOf(h * levelAt(t, i) / 2f, w / 2f) else w / 2f
                val x = x0 + i * (w + gap)
                rect.set(x, mid - half, x + w, mid + half)
                c.drawRoundRect(rect, w / 2f, w / 2f, paint)
            }
            if (animating && isShown) postDelayed(frame, 50)
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(frame)
            super.onDetachedFromWindow()
        }

        private companion object {
            const val COUNT = 6
        }
    }

    companion object {
        /** The capsule's height, in dp: the corner buttons' row is centred on it. */
        const val HEIGHT_DP = 44
        /** The cover's side as a fraction of the capsule's height: a little smaller than iOS's 0.706. */
        const val ART_RATIO = 0.62f
        /** Between the capsule and the Paroles button. */
        const val BUTTON_GAP_DP = 8
        /** The title and the artist slide once wider than this. */
        const val TEXT_MAX_DP = 220
        /** The bars' width: six 2 dp bars with 2 dp between. */
        const val BARS_DP = 22
        const val POLL_MS = 2_000L
        // Literals, not Color.argb: the unit tests load this companion without Android.
        private const val SUB = 0xCDFFFFFF.toInt()
        private const val INK = 0xE1000000.toInt()
        private const val INK_SUB = 0x96000000.toInt()

        /**
         * The height of bar [i] (0 to 1) at [seconds], a sum of two sines at speeds of their own
         * per bar, so the bars never move together, between about 0.2 and 1. Mirrored pairs
         * (bars 0 and 5, 1 and 4, 2 and 3) move alike, as a spectrum would.
         */
        fun levelAt(seconds: Double, i: Int): Float {
            val k = if (i < 3) i else 5 - i
            val a = sin(seconds * (5.1 + k * 1.3) + k * 2.1)
            val b = sin(seconds * (2.3 + k * 0.7) + k * 0.8)
            return (0.6 + 0.4 * (a * 0.6 + b * 0.4)).toFloat().coerceIn(0.2f, 1f)
        }

        /** [v]'s rectangle in [root]'s coordinates, from the layout alone (not its transforms: a focused view is scaled). */
        fun layoutRect(v: View, root: View): Rect {
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
}
