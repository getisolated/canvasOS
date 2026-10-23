package com.getisolated.canvasos

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.getisolated.canvasos.tv.Input
import com.getisolated.canvasos.tv.Level
import com.getisolated.canvasos.tv.TvControls
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The Control Center, in one of two looks ([Prefs.ccGlass]): independent liquid-glass controls
 * floating over the home screen (the default everywhere), or the same controls on one plain dark
 * panel when chosen in settings. The focused control turns white; its neighbours are left alone.
 *
 * The controls stand on a grid of 4 × 6 cells, on up to three pages stacked vertically
 * ([CcLayout], [Prefs.ccLayout]): each at its place and at one of its sizes, holes allowed. A
 * control with nothing to do (no inputs, no forecast, nothing playing) stays at its place, idle.
 * Edit mode (a long press on the home screen's Control Center button, or the settings) moves,
 * resizes, removes and adds them.
 *
 * The glass is cheap to draw: the home screen behind the controls is captured once at a quarter
 * of its size and blurred when the Control Center opens (and again only if the screen behind
 * changes), and each control paints its share of that picture as a texture. Nothing is blurred,
 * clipped to a path or offscreen-rendered per frame, which kept the live GPU blur of v1.9 at
 * 15 ms of GPU time per frame on a BRAVIA (MT5895).
 */
class ControlCenter(private val home: HomeActivity, private val host: FrameLayout) {
    private val ctx = home
    private val prefs = Prefs(ctx)
    private val gap = ctx.dp(12)
    private val cell = ctx.dp(47)
    private val width = cell * 4 + gap * 3
    private var glassStyle = prefs.ccGlass(ctx)
    private val surfaces = ArrayList<GlassSurface>()
    private var destroyed = false
    private var refreshing = false

    // ---- the blurred picture of the home screen behind the controls ----
    private val sampleScale = 0.25f
    private var blur: Bitmap? = null
    private var blurShader: BitmapShader? = null
    /** Where [blur] starts, in host coordinates. */
    private var blurX = 0f
    private var blurY = 0f
    private var blurDirty = true
    private val resample: Runnable = object : Runnable {
        // Not in the middle of the player growing or shrinking: a few ms on the UI thread would show.
        override fun run() { if (isOpen && blurDirty) { if (morphing) host.postDelayed(this, 80) else sampleBehind() } }
    }
    /**
     * While the home screen crossfades to a new background ([backgroundChanging]): the blurred
     * picture of how it will look, drawn over [blurShader] at [blurMix], then kept alone.
     */
    private var blurNext: BitmapShader? = null
    private var blurNextBmp: Bitmap? = null
    private var blurNextX = 0f
    private var blurNextY = 0f
    private var blurMix = 0f
    private val blurFade = Tween()
    /** Saturated and lifted, so the glass takes on the colour of what is behind it. */
    private val toneMatrix = ColorMatrix().apply {
        setSaturation(1.5f)
        postConcat(ColorMatrix(floatArrayOf(
            0.88f, 0f, 0f, 0f, 22f,
            0f, 0.88f, 0f, 0f, 22f,
            0f, 0f, 0.88f, 0f, 22f,
            0f, 0f, 0f, 1f, 0f)))
    }

    private val scrim = View(ctx).apply {
        setBackgroundColor(Color.argb(32, 0, 0, 0))
        alpha = 0f
        translationZ = ctx.dp(100).toFloat()
        // Raised only to sit above the tiles. With an outline, Android would also cast a
        // screen-sized shadow under it, redrawn every frame: about 5 ms of GPU time on a BRAVIA.
        outlineProvider = null
    }
    // Glass: layout only, the gaps between controls show the actual wallpaper. Panel: one dark card ([backing]).
    // Pages of the grid ([CcLayout]) stand in it at absolute places.
    private val panel = FrameLayout(ctx).apply {
        translationZ = ctx.dp(101).toFloat()
        outlineProvider = null
        visibility = View.INVISIBLE
        alpha = 1f
    }
    private val panelCard = rounded(Color.argb(228, 30, 30, 34), ctx.dp(28).toFloat())
    private val bigCard = rounded(Color.argb(235, 30, 30, 34), ctx.dp(30).toFloat())

    private fun invalidateSurfaces() { for (s in surfaces) s.owner.invalidate() }

    /** The glass body's colour filter, per wash strength and focus level (cached in 16 steps). */
    private val bodies = HashMap<Int, ColorMatrixColorFilter>()

    private fun body(wash: Int, lit: Float): ColorMatrixColorFilter {
        val step = (lit * 16).roundToInt().coerceIn(0, 16)
        return bodies.getOrPut(wash * 32 + step) {
            val l = step / 16f
            // A black wash at rest, white as it lights up: the same as layers over the toned picture.
            val black = 34f * (1f - l) / 255f
            val white = (10f + wash * l) / 255f
            val k = (1f - black) * (1f - white)
            val o = 255f * white
            ColorMatrixColorFilter(ColorMatrix(toneMatrix).apply {
                postConcat(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, o, 0f, k, 0f, 0f, o, 0f, 0f, k, 0f, o, 0f, 0f, 0f, 1f, 0f)))
            })
        }
    }

    private val brightness: Slider
    // The controls built at the size their place asks for ([widget]); null while not placed yet.
    private var inputModule: GlassButton? = null
    private var networkModule: GlassButton? = null
    private var sleepModule: GlassButton? = null
    private var weatherModule: GlassButton? = null
    private var bluetoothModule: GlassButton? = null
    private var settingsModule: GlassButton? = null
    /** The 4 × 2 Météo card's next three days; empty at 4 × 1. */
    private var weatherDays: List<DayCell> = emptyList()
    /** The player ([Prefs.nowPlaying]): what a media session plays; [emptyPlayer] stands in while nothing does. */
    private var now: NowPlaying? = null
    /** Artwork that came from an address is shown as soon as it has loaded, not at the next 2 s poll. */
    private val nowReader = NowPlaying.Reader(ctx, ctx.dp(84), ctx.dp(BIG_ART_DP)) { host.post { updateNowPlaying() } }
    private var nowReading = false
    /** When the user last pressed something on the player: a reading begun before it is out of date. */
    private var actedAt = 0L
    /** The player stands at 4 × 1 ([Prefs.playerStyle] one): it shows [nowLine] rather than [nowCard]. */
    private var playerOne = false
    /** State 1, one line: artwork, title and artist, play/pause. */
    private val nowLine: GlassButton
    /** State 2, two lines: artwork, title, artist, transport and progress. */
    private val nowCard: PlayerCard
    /** The player while nothing plays: an empty glass card of its size. */
    private val emptyPlayer: EmptyPlayer
    /** State 3, the large player, in place of the whole panel (a long press on OK). */
    private val big: PlayerBig
    /** Holds [big] above the panel; raised itself, so the glass card casts no platform shadow. */
    private val bigHost = FrameLayout(ctx).apply {
        translationZ = ctx.dp(102).toFloat()
        outlineProvider = null
        visibility = View.INVISIBLE
    }
    private var expanded = false
    private val hold = Hold()
    /** The glass frame that grows from the player into the large player (and back), below the panel. */
    private val morph = MorphView()
    private val morphTween = Tween()
    /** The player is growing into the large player, or shrinking back. */
    private var morphing = false
    private val morphFrom = RectF()
    private val morphTo = RectF()
    private val morphNow = RectF()
    private val morphMatrix = Matrix()
    private val bigClip = Rect()
    /** The large player's measured size and its resting scale, from the last time it grew. */
    private var bigW = 0
    private var bigH = 0
    private var bigS = 1f
    private var inputs: List<Input> = emptyList()
    /** The inputs have been read once: until then Source says nothing rather than "Aucune entrée". */
    private var inputsRead = false
    private var inputCurrent: String? = null
    private var inputSummary = ""
    private val io = Executors.newSingleThreadExecutor()
    private val tv = TvControls(ctx)

    // ---- Bluetooth: the profile services are slow, so they get their own thread and a slower clock ----
    private val bt = Executors.newSingleThreadExecutor()
    private var btState = Bluetooth.State.NONE
    private var btDevices: List<Bluetooth.Device> = emptyList()
    /** "Connexion…" while a connect or disconnect is under way, shown in place of the summary. */
    private var btPending: String? = null
    private var btBusy = false
    private var btCheckedAt = 0L
    private var btAsked = false

    // ---- the grid ([CcLayout]): pages of 4 × 6 cells, each control at its place and size ----
    private val span = cell + gap
    private val gridH = cell * CcLayout.ROWS + gap * (CcLayout.ROWS - 1)
    /** Around the grid inside [panel]: room for focused controls to grow into, and the page dots. */
    private val room = ctx.dp(16)
    private var layout = prefs.ccLayout
    private var layoutJson = CcLayout.serialize(layout)
    /** One container per page, all at the grid's place in [panel]; only [page] shows, but while sliding. */
    private val pageViews = ArrayList<FrameLayout>()
    /** Each page's "Page vide" capsule, the only thing to focus on an empty page in edit mode. */
    private val blanks = ArrayList<Blank>()
    private var page = 0
    private val pageTween = Tween()
    private val dots = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
    /** The panel look's dark card, down to the lowest row in use. */
    private val backing = View(ctx)
    /** Each control's view (but the player's), the size it was built at, and the other way round. */
    private val views = HashMap<String, View>()
    private val builtAt = HashMap<String, CcLayout.Size>()
    private val idByView = HashMap<View, String>()
    /** Holds the player's looks ([nowLine], [nowCard], [emptyPlayer]), one shown at a time. */
    private val playerSlot = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    /** The control focus last came from: going back the other way returns to it. */
    private var cameFrom: String? = null
    /** The control last focused in the grid ("" for an empty page's capsule) and its page, for focus after a sheet. */
    private var focusedId: String? = null
    private var focusedPage = 0

    // ---- edit mode: a long press on the home screen's Control Center button, or the settings ----
    private var editing = false
    /** A control being moved: where it was, where it stands now, and its alpha to give back. */
    private class Moving(val id: String, val from: CcLayout.Pos, var at: CcLayout.Pos, val w: Int, val h: Int, val alpha: Float)
    private var moving: Moving? = null
    /** Where OK went down in edit mode: only a press that also ends there opens a sheet. */
    private var okDownOn: View? = null
    private var wiggling: View? = null
    private val wiggle = Tween()
    private val slideStep = Tween()
    private val focusWatch = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, new -> focusMoved(new) }

    private val ticker = object : Runnable {
        override fun run() {
            updateSleep()
            updateNowPlaying()
            refreshBluetooth(force = false)
            host.postDelayed(this, 2_000)
        }
    }

    // Declared before init, which arms it.
    private val sleepWarning = Runnable { warnSleep() }

    var isOpen = false
        private set

    init {
        brightness = Slider(R.drawable.ic_sun, "Luminosité") { v -> tv.setBrightness(v) }
        idByView[brightness] = CcLayout.BRIGHTNESS
        // The player's looks share its place; their keys are handled by playerKey.
        nowLine = GlassButton("Lecture", R.drawable.ic_music, Shape.CAPSULE) { togglePlay() }
        nowLine.trailing(R.drawable.ic_pause)
        nowLine.visibility = View.GONE
        nowLine.onFocus = { on -> if (!on) hold.release(nowLine) }
        nowLine.setOnKeyListener { v, code, e -> playerKey(v, code, e, skips = false) }
        nowCard = PlayerCard()
        nowCard.visibility = View.GONE
        nowCard.setOnKeyListener { v, code, e -> playerKey(v, code, e, skips = true) }
        emptyPlayer = EmptyPlayer()
        for (v in listOf(nowLine, nowCard, emptyPlayer)) playerSlot.addView(v, FrameLayout.LayoutParams(-1, -1))
        idByView[playerSlot] = CcLayout.PLAYER
        panel.addView(backing, FrameLayout.LayoutParams(-1, 0))
        panel.addView(dots, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL).apply {
            leftMargin = (room - ctx.dp(DOT_DP)) / 2
        })
        // The panel clips at its own bounds: a page sliding away goes no further than the room around the grid.
        host.addView(panel, FrameLayout.LayoutParams(width + room * 2, gridH + room * 2, Gravity.TOP or Gravity.END).apply {
            topMargin = ctx.dp(PANEL_TOP) - room; marginEnd = ctx.dp(32) - room
        })
        panel.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (l != oldL || t != oldT || r != oldR || b != oldB) refreshGlass()
        }
        applyLayout()
        big = PlayerBig()
        bigHost.addView(big, FrameLayout.LayoutParams(-2, -2))
        host.addView(bigHost, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = ctx.dp(PANEL_TOP); marginEnd = ctx.dp(32)
        })
        bigHost.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (l != oldL || t != oldT || r != oldR || b != oldB) refreshGlass()
        }
        host.addView(morph, FrameLayout.LayoutParams(-1, -1))
        host.viewTreeObserver.addOnGlobalFocusChangeListener(focusWatch)
        panel.post { refresh() }
        // A timer started before this screen was recreated still owes its one-minute warning.
        armSleepWarning()
        applyStyle()
    }

    /** Picks up a change of [Prefs.ccStyle], or of [Prefs.ccLayout] made in settings or over adb ([Prefs.playerStyle]). */
    fun applyStyle() {
        val saved = prefs.ccLayout
        val json = CcLayout.serialize(saved)
        if (json != layoutJson && moving == null) {
            layout = saved
            layoutJson = json
            applyLayout()
        }
        val glass = prefs.ccGlass(ctx)
        if (glass == glassStyle && panel.tag != null) return
        panel.tag = true
        glassStyle = glass
        backing.background = if (glass) null else panelCard
        big.background = if (glass) big.glass else bigCard
        if (!glass) { endBlurFade(); blur = null; blurShader = null }
        blurDirty = true
        invalidateSurfaces()
    }

    /** Maps [v]'s own coordinates to the host's, through every scale and translation on the way. */
    private fun toHost(v: View, out: Matrix) {
        out.reset()
        var cur: View = v
        while (cur !== host) {
            out.postConcat(cur.matrix)
            val p = cur.parent as? View ?: break
            out.postTranslate((cur.left - p.scrollX).toFloat(), (cur.top - p.scrollY).toFloat())
            cur = p
        }
    }

    /** Each control owns its own background: its share of the blurred screen, a sheen, a lit rim. */
    private inner class GlassSurface(val owner: View, var radius: Float) : Drawable() {
        /** How much white the focused glass takes on; sliders keep it low so their fill reads. */
        var focusWash = 140
        /** Draws nothing: the large player's own glass while [morph] stands in for it. */
        var hidden = false
            set(v) { if (field != v) { field = v; invalidateSelf(); owner.invalidate() } }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val rect = RectF()
        private val ownerToHost = Matrix()
        private val hostToOwner = Matrix()
        private val m = Matrix()
        init { surfaces += this }

        /** 0 = resting, 1 = focused; animated so the light swells in rather than switching. */
        var lit = 0f
            set(value) { field = value; invalidateSelf() }
        private val litTween = Tween()
        private var sheen: Shader? = null
        private var rim: Shader? = null
        private var spot: Shader? = null
        private val r get() = minOf(radius, bounds.width() / 2f, bounds.height() / 2f)

        fun light(on: Boolean) {
            val from = lit
            litTween.start(if (on) 280 else 200) { f -> lit = lerp(from, if (on) 1f else 0f, f) }
        }

        override fun onBoundsChange(b: android.graphics.Rect) {
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0f || h <= 0f) return
            // From the bounds' corner: a background starts at 0, the morph's frame anywhere.
            val x = b.left.toFloat()
            val y = b.top.toFloat()
            // A sheen across the upper half, as light falling on a curved top surface.
            sheen = android.graphics.LinearGradient(x, y, x, y + h,
                intArrayOf(Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                floatArrayOf(0f, 0.55f), Shader.TileMode.CLAMP)
            // Rim: bright where it faces the light (top left), a softer reflection bottom right.
            rim = android.graphics.LinearGradient(x, y, x + w, y + h,
                intArrayOf(Color.WHITE, Color.argb(40, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.argb(170, 255, 255, 255)),
                floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
            // The focus light: a soft glow pooled under the top left of the glass.
            spot = android.graphics.RadialGradient(x + w * 0.3f, y + h * 0.1f, maxOf(w, h) * 0.95f,
                intArrayOf(Color.argb(120, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                null, Shader.TileMode.CLAMP)
        }

        override fun draw(c: Canvas) {
            if (hidden) return
            rect.set(bounds)
            val r = r
            if (!glassStyle) { drawFlat(c, r); return }
            toHost(owner, ownerToHost)
            ownerToHost.invert(hostToOwner)
            // Every full-size pass costs about a millisecond of GPU time per frame on a BRAVIA's
            // Mali, so what can be merged is: three passes when resting, five when lit.
            val shader = blurShader
            if (shader != null) {
                // The blurred screen, mapped so this control shows exactly the part behind it,
                // with the milky wash and the focus brightening folded into its colour filter.
                drawBlur(c, shader, blurX, blurY, r, 255)
                // A background crossfading: the new picture over the old, one more pass while it lasts.
                val next = blurNext
                if (next != null && blurMix > 0f) drawBlur(c, next, blurNextX, blurNextY, r, (255 * blurMix).roundToInt().coerceIn(0, 255))
            } else {
                paint.color = Color.argb((90 + focusWash * lit).toInt().coerceAtMost(255), 48, 52, 58)
                c.drawRoundRect(rect, r, r, paint)
            }
            // A sheen across the top, as light falling on a curved surface; a pooled glow when lit.
            paint.color = Color.WHITE
            paint.shader = sheen
            paint.alpha = (110 + 145 * lit).toInt()
            c.drawRoundRect(rect, r, r, paint)
            if (lit > 0f) {
                paint.shader = spot
                paint.alpha = (255 * lit).toInt()
                c.drawRoundRect(rect, r, r, paint)
            }
            // Rim: bright where it faces the light (top left), a softer reflection bottom right.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ctx.dp(1f + 0.6f * lit).toFloat()
            paint.alpha = (150 + 105 * lit).toInt()
            paint.shader = rim
            val inset = paint.strokeWidth / 2
            rect.inset(inset, inset)
            c.drawRoundRect(rect, r, r, paint)
            paint.shader = null
            paint.alpha = 255
            paint.style = Paint.Style.FILL
        }

        private fun drawBlur(c: Canvas, shader: BitmapShader, x: Float, y: Float, r: Float, alpha: Int) {
            m.setScale(1f / sampleScale, 1f / sampleScale)
            m.postTranslate(x, y)
            m.postConcat(hostToOwner)
            shader.setLocalMatrix(m)
            glassPaint.shader = shader
            glassPaint.colorFilter = body(focusWash, lit)
            glassPaint.alpha = alpha
            c.drawRoundRect(rect, r, r, glassPaint)
            glassPaint.alpha = 255
        }

        /** The panel look: flat translucent controls that turn white when focused. */
        private fun drawFlat(c: Canvas, r: Float) {
            paint.shader = null
            paint.style = Paint.Style.FILL
            val a = lerp(34f, if (focusWash > 0) 255f else 70f, lit).toInt()
            paint.color = Color.argb(a, 255, 255, 255)
            c.drawRoundRect(rect, r, r, paint)
        }

        override fun getOutline(outline: android.graphics.Outline) = outline.setRoundRect(bounds, r)
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun glassFor(view: View, radius: Float): GlassSurface = GlassSurface(view, radius).also {
        view.background = it
        view.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
        // No elevation: through translucent glass the platform shadow shows a hard-edged hole.
    }

    private fun focus(view: View, glass: GlassSurface, on: Boolean, grow: Float = 1.08f) {
        glass.light(on)
        // The focused control lifts towards the viewer; its glass keeps showing what is behind it.
        view.tweenScale(if (on) grow else 1f, if (on) 260 else 200, if (on) Motion.spring else Motion.ease) {
            if (glassStyle) view.invalidate()
        }
    }

    /** The screen behind changed (background, shelf, layout): the glass samples it again. */
    fun refreshGlass() {
        if (destroyed) return
        blurDirty = true
        if (isOpen && glassStyle) {
            host.removeCallbacks(resample)
            host.postDelayed(resample, 80)
        }
    }

    /**
     * The home screen is about to crossfade its background (wallpaper or Top Shelf picture) over
     * [durationMs]; [finalScene] draws the scene as it will look once the fade is over, in the
     * same coordinates as `sceneView.draw(canvas)`. While the panel is open in glass, that scene
     * is blurred once now and the glass crossfades to it along with the screen: no blur per
     * frame, one more texture pass per control while the fade lasts. Closed, the glass is only
     * marked out of date, as by [refreshGlass], which stays the fallback.
     */
    fun backgroundChanging(durationMs: Long, finalScene: (Canvas) -> Unit) {
        if (destroyed) return
        if (!isOpen || !glassStyle) { blurDirty = true; return }
        host.removeCallbacks(resample)
        val s = try { sample(finalScene) } catch (e: Throwable) { null }
        if (s == null) { refreshGlass(); return }
        if (blurShader == null || durationMs <= 0L) { endBlurFade(); keepBlur(s); return }
        // A fade already under way: its picture is where this one starts.
        blurNextBmp?.let { keepBlur(Sample(it, blurNextX, blurNextY), blurNext) }
        blurNextBmp = s.bmp
        blurNext = BitmapShader(s.bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        blurNextX = s.x
        blurNextY = s.y
        blurMix = 0f
        val shader = blurNext
        blurFade.start(durationMs, LinearInterpolator(), end = {
            if (blurNext === shader) { endBlurFade(); keepBlur(s, shader) }
        }) { f -> blurMix = f; invalidateSurfaces() }
    }

    /** Stops a background crossfade of the glass where it is; the picture faded from stays. */
    private fun endBlurFade() {
        blurFade.cancel()
        blurNext = null
        blurNextBmp = null
        blurMix = 0f
    }

    /** [s] becomes the glass's picture. */
    private fun keepBlur(s: Sample, shader: BitmapShader? = null) {
        // Not recycled: a frame still being drawn may hold the previous picture. It is small.
        blur = s.bmp
        blurShader = shader ?: BitmapShader(s.bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        blurX = s.x
        blurY = s.y
        blurDirty = false
        invalidateSurfaces()
    }

    /** A blurred picture of the screen behind the controls, and where it starts in host coordinates. */
    private class Sample(val bmp: Bitmap, val x: Float, val y: Float)

    /**
     * Captures the home screen behind the controls at a quarter of its size and blurs it, on the
     * UI thread: a few milliseconds, once per opening at most.
     */
    private fun sampleBehind() {
        val s = sample { home.sceneView.draw(it) } ?: return
        endBlurFade()
        keepBlur(s)
    }

    /** The region behind the panel (and the large player), drawn by [scene] at a quarter of its size, then blurred. */
    private fun sample(scene: (Canvas) -> Unit): Sample? {
        if (destroyed || !glassStyle || host.width == 0 || panel.width == 0) return null
        val margin = ctx.dp(48)
        // The large player too, which stands where the panel does but may reach further.
        val hasBig = bigHost.width > 0
        val left = (minOf(panel.left, if (hasBig) bigHost.left else panel.left) - margin).coerceAtLeast(0)
        val top = (minOf(panel.top, if (hasBig) bigHost.top else panel.top) - margin).coerceAtLeast(0)
        val right = (maxOf(panel.right, if (hasBig) bigHost.right else panel.right) + margin).coerceAtMost(host.width)
        val bottom = (maxOf(panel.bottom, if (hasBig) bigHost.bottom else panel.bottom) + margin).coerceAtMost(host.height)
        val w = ((right - left) * sampleScale).roundToInt().coerceAtLeast(1)
        val h = ((bottom - top) * sampleScale).roundToInt().coerceAtLeast(1)
        val bmp = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply {
                    drawColor(Color.BLACK)
                    scale(sampleScale, sampleScale)
                    translate(-left.toFloat(), -top.toFloat())
                    scene(this)
                }
            }
        } catch (e: RuntimeException) {
            return null
        }
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        // Three box passes come close to a Gaussian blur.
        repeat(3) { LiquidGlass.boxBlur(px, w, h, 7) }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        bmp.prepareToDraw()
        return Sample(bmp, left.toFloat(), top.toFloat())
    }

    /** ROUND: 1 × 1, icon only. CAPSULE: N × 1. TILE: 2 × 2, glyph over label. CARD: 4 × 2, a capsule's header over [GlassButton.extra]. */
    private enum class Shape { TILE, CAPSULE, ROUND, CARD }

    private fun shapeOf(s: CcLayout.Size) = when {
        s.w == 1 && s.h == 1 -> Shape.ROUND
        s.h == 1 -> Shape.CAPSULE
        s.w == 2 && s.h == 2 -> Shape.TILE
        else -> Shape.CARD
    }

    /**
     * A glass control. [narrow]: a 2 × 1 capsule, with tighter margins and a subtitle that shrinks to
     * fit an IP address; [wrap]: its label may take two lines ("Libérer la / mémoire").
     */
    private inner class GlassButton(
        label: String,
        icon: Int,
        shape: Shape,
        accent: Int = Color.WHITE,
        narrow: Boolean = false,
        wrap: Boolean = false,
        action: () -> Unit,
    ) : LinearLayout(ctx) {
        // Capsules and circles clamp this to half their height.
        private val glass = glassFor(this, ctx.dp(when (shape) { Shape.TILE -> 24; Shape.CARD -> 22; else -> 100 }).toFloat())
        val glyph = ImageView(ctx)
        val title = ctx.text(if (narrow) 12f else 12.5f, weight = 600).apply {
            text = label
            maxLines = if (wrap) 2 else 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val sub = ctx.text(10.5f, Color.argb(205, 255, 255, 255), weight = 500).apply {
            visibility = GONE
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        /** A CARD's content under its header (the weather's next days). */
        val extra = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        /** A new glyph, such as the weather's; it keeps its colour. */
        fun setIcon(res: Int) = glyph.setImageResource(res)
        /** A glyph at the far end (the one-line player's play/pause), white like the title. */
        var trail: ImageView? = null
            private set
        /** Told when focus comes or goes, after the control has lit or dimmed. */
        var onFocus: ((Boolean) -> Unit)? = null
        /**
         * Nothing to do right now (no inputs, no forecast, no Bluetooth): glyph and text dimmed, the
         * glass as it is, so an idle control reads the same everywhere and keeps its place.
         */
        var idle = false
            set(v) {
                if (field == v) return
                field = v
                val a = if (v) IDLE_ALPHA else 1f
                for (x in listOfNotNull(glyph, title, sub, trail, extra)) x.alpha = a
            }

        fun trailing(res: Int): ImageView = (trail ?: ImageView(ctx).also {
            trail = it
            addView(it, LayoutParams(ctx.dp(20), ctx.dp(20)).apply { marginStart = ctx.dp(8) })
        }).apply { setImageResource(res) }

        init {
            id = View.generateViewId()
            contentDescription = label
            orientation = if (shape == Shape.CAPSULE) HORIZONTAL else VERTICAL
            gravity = when (shape) { Shape.CAPSULE -> Gravity.CENTER_VERTICAL; Shape.CARD -> Gravity.TOP; else -> Gravity.CENTER }
            isFocusable = true
            isClickable = true
            if (shape == Shape.CAPSULE) setPadding(ctx.dp(if (narrow) 11 else 13), 0, ctx.dp(if (narrow) 8 else 10), 0)
            if (shape == Shape.CARD) setPadding(ctx.dp(13), ctx.dp(12), ctx.dp(13), ctx.dp(10))
            if (narrow) {
                // An address such as 192.168.100.200 in about 60 dp: the subtitle shrinks rather than cut it.
                sub.setAutoSizeTextTypeUniformWithConfiguration(8, 10, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }
            val size = ctx.dp(when (shape) { Shape.TILE -> 40; Shape.CAPSULE -> if (narrow) 18 else 20; Shape.ROUND -> 21; Shape.CARD -> 20 })
            glyph.setImageResource(icon)
            glyph.setColorFilter(accent)
            val column = LinearLayout(ctx).apply {
                orientation = VERTICAL
                addView(title)
                addView(sub, LayoutParams(-1, if (narrow) ctx.dp(13) else -2).apply { topMargin = ctx.dp(1) })
            }
            val glyphLp = LayoutParams(size, size).apply { if (shape == Shape.CAPSULE || shape == Shape.CARD) marginEnd = ctx.dp(if (narrow) 7 else 9) }
            // Round buttons are icon-only, as on tvOS; the label stays for accessibility.
            when (shape) {
                Shape.TILE -> {
                    addView(glyph, glyphLp)
                    addView(title, LayoutParams(-2, -2).apply { topMargin = ctx.dp(8) })
                }
                Shape.CAPSULE -> {
                    addView(glyph, glyphLp)
                    addView(column, LayoutParams(0, -2, 1f))
                }
                Shape.ROUND -> addView(glyph, glyphLp)
                Shape.CARD -> {
                    val header = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    header.addView(glyph, glyphLp)
                    header.addView(column, LayoutParams(0, -2, 1f))
                    addView(header, LayoutParams(-1, -2))
                    addView(extra, LayoutParams(-1, 0, 1f).apply { topMargin = ctx.dp(6) })
                }
            }
            setOnClickListener { action() }
            setOnFocusChangeListener { _, on ->
                focus(this, glass, on, when (shape) { Shape.TILE -> 1.06f; Shape.CAPSULE -> 1.05f; Shape.ROUND -> 1.1f; Shape.CARD -> 1.04f })
                // Lit glass is bright: dark glyphs keep the focused control readable on any wallpaper.
                val ink = Color.argb(225, 0, 0, 0)
                glyph.setColorFilter(if (on) ink else accent)
                title.setTextColor(if (on) ink else Color.WHITE)
                sub.setTextColor(if (on) Color.argb(170, 0, 0, 0) else Color.argb(205, 255, 255, 255))
                trail?.setColorFilter(if (on) ink else Color.WHITE)
                onFocus?.invoke(on)
            }
        }
    }

    /** One of the 4 × 2 Météo card's next days: its short name, its glyph, its low and high. */
    private inner class DayCell : LinearLayout(ctx) {
        val day = ctx.text(10f, SUB, weight = 600).apply { gravity = Gravity.CENTER; maxLines = 1 }
        val glyph = ImageView(ctx)
        val temps = ctx.text(10f, weight = 500).apply { gravity = Gravity.CENTER; maxLines = 1 }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(day, LayoutParams(-1, -2))
            addView(glyph, LayoutParams(ctx.dp(16), ctx.dp(16)).apply { topMargin = ctx.dp(3); bottomMargin = ctx.dp(3) })
            addView(temps, LayoutParams(-1, -2))
        }

        fun ink(on: Boolean) {
            day.setTextColor(if (on) INK_SUB else SUB)
            temps.setTextColor(if (on) INK else Color.WHITE)
        }
    }

    /** The player while nothing plays: an empty glass card at the player's size, a faint note in its middle; OK only bumps. */
    private inner class EmptyPlayer : FrameLayout(ctx) {
        val glass = glassFor(this, ctx.dp(22).toFloat())
        private val note = ImageView(ctx).apply { setImageResource(R.drawable.ic_music); alpha = 0.3f }

        init {
            isFocusable = true
            isClickable = true
            contentDescription = "Lecture : rien en cours"
            addView(note, LayoutParams(ctx.dp(22), ctx.dp(22), Gravity.CENTER))
            setOnClickListener { Motion.bump(this, 1, 0) }
            setOnFocusChangeListener { _, on ->
                focus(this, glass, on, if (playerOne) 1.05f else 1.04f)
                note.setColorFilter(if (on) INK else Color.WHITE)
            }
        }
    }

    /** An empty page's place-holder in edit mode: a dashed capsule; OK offers to fill the page or delete it. */
    private inner class Blank : TextView(ctx) {
        private val idle = GradientDrawable().apply {
            cornerRadius = cell / 2f
            setColor(Color.argb(20, 255, 255, 255))
            setStroke(ctx.dp(1.5f), Color.argb(150, 255, 255, 255), ctx.dp(6).toFloat(), ctx.dp(4).toFloat())
        }
        private val lit = rounded(Color.argb(235, 255, 255, 255), cell / 2f)

        init {
            text = "Page vide"
            textSize = 12.5f
            typeface = Fonts.inter(ctx, 600)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isFocusable = true
            visibility = View.GONE
            background = idle
            setOnFocusChangeListener { _, on ->
                background = if (on) lit else idle
                setTextColor(if (on) INK else Color.WHITE)
                tweenScale(if (on) 1.05f else 1f, if (on) 260 else 200, if (on) Motion.spring else Motion.ease)
            }
        }
    }
    /**
     * iOS-style capsule slider: the white fill is the value. Left/right change it,
     * and the TV follows while the key is held.
     */
    private inner class Slider(
        icon: Int,
        label: String,
        private val steps: Int = 25,
        private val send: (Int) -> Unit,
    ) : FrameLayout(ctx) {
        // A capsule as tall as a cell, as the controls around it.
        private val corner = cell / 2f
        private val glass = glassFor(this, corner).apply { focusWash = 0 }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fillClip = android.graphics.Path()
        private var darkIcon = false
        private var lastInteraction = 0L
        private val img = ImageView(ctx).apply { setImageResource(icon) }
        private val nameText = ctx.text(12.5f, weight = 600).apply { text = label }
        private val valueText = ctx.text(12.5f, weight = 600)
        private var value = 0
        private var min = 0
        private var max = 100
        private var ready = false
        // Logical target drives hardware writes; displayedValue is visual-only and animates between targets.
        private var displayedValue = 0f
        private var valueAnimator: android.animation.ValueAnimator? = null
        // One write in flight at a time; presses that land meanwhile collapse into the latest value,
        // so holding the key changes the TV as fast as it can take it (BRAVIA: ~45 ms per write).
        private var sending = false
        private var sent = Int.MIN_VALUE

        private fun push() {
            if (sending || value == sent) return
            sending = true
            val v = value
            sent = v
            io.execute {
                send(v)
                host.post { sending = false; push() }
            }
        }

        init {
            isFocusable = true
            clipRounded(corner)
            setWillNotDraw(false)
            contentDescription = label
            addView(img, LayoutParams(ctx.dp(18), ctx.dp(18), Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = ctx.dp(14) })
            addView(nameText, LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = ctx.dp(41) })
            addView(valueText, LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = ctx.dp(15) })
            setOnFocusChangeListener { _, f ->
                focus(this, glass, f, 1.03f)
                draw()
            }
            setOnKeyListener { _, code, e ->
                // No brightness to set (no API on this TV): OK only bumps, as on any idle control.
                if (isConfirm(code)) {
                    if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && !ready) Motion.bump(this, 1, 0)
                    return@setOnKeyListener !ready
                }
                if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
                if (e.action == KeyEvent.ACTION_DOWN && ready) {
                    val dir = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                    val step = ((max - min) / steps).coerceAtLeast(1)
                    val nv = (value + dir * step).coerceIn(min, max)
                    if (nv == value) {
                        if (e.repeatCount == 0) Motion.bump(this, dir, 0)
                    } else {
                        lastInteraction = android.os.SystemClock.uptimeMillis()
                        value = nv
                        valueText.text = value.toString()
                        animateValueTo(value)
                        push()
                    }
                }
                true
            }
            set(null)
        }

        fun set(level: Level?) {
            // An asynchronous read started before a key press must not overwrite its newer target.
            if (ready && (sending || android.os.SystemClock.uptimeMillis() - lastInteraction < 600)) return
            // The panel polls every 2 s; an unchanged reading must not relayout and redraw the panel.
            if (ready && level != null && level.value.coerceIn(level.min, level.max) == value &&
                level.min == min && level.max == max) return
            val wasReady = ready
            val oldTarget = value
            ready = level != null
            if (level != null) {
                min = level.min
                max = level.max
                value = level.value.coerceIn(min, max)
                sent = value
                valueText.text = value.toString()
                if (!wasReady) {
                    displayedValue = value.toFloat()
                    draw()
                } else if (value != oldTarget) animateValueTo(value) else draw()
            } else {
                valueAnimator?.cancel()
                valueAnimator = null
                displayedValue = min.toFloat()
                valueText.text = "—"
                draw()
            }
            alpha = if (ready) 1f else 0.45f
        }

        private fun animateValueTo(target: Int) {
            valueAnimator?.cancel()
            val end = target.toFloat()
            if (displayedValue == end) {
                draw()
                return
            }
            valueAnimator = android.animation.ValueAnimator.ofFloat(displayedValue, end).apply {
                duration = 100L
                interpolator = Motion.ease
                addUpdateListener {
                    displayedValue = it.animatedValue as Float
                    draw()
                }
                start()
            }
        }

        fun finishAnimation() {
            valueAnimator?.cancel()
            valueAnimator = null
            displayedValue = value.toFloat()
            draw()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            draw()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val fraction = if (ready && max > min)
                ((displayedValue - min) / (max - min)).coerceIn(0f, 1f) else 0f
            fillPaint.color = Color.argb(if (isFocused) 255 else 190, 255, 255, 255)
            val save = canvas.save()
            fillClip.reset()
            fillClip.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), corner, corner, android.graphics.Path.Direction.CW)
            canvas.clipPath(fillClip)
            canvas.drawRect(0f, 0f, width * fraction, height.toFloat(), fillPaint)
            canvas.restoreToCount(save)
        }

        private fun draw() {
            if (width <= 0) return
            val fraction = if (ready && max > min)
                ((displayedValue - min) / (max - min)).coerceIn(0f, 1f) else 0f
            val filled = width * fraction
            val dark = Color.argb(230, 0, 0, 0)
            val iconIsDark = filled > ctx.dp(32)
            if (darkIcon != iconIsDark) {
                darkIcon = iconIsDark
                img.setColorFilter(if (darkIcon) dark else Color.WHITE)
            }
            val nameColor = if (filled > ctx.dp(41) + nameText.width) dark else Color.WHITE
            if (nameText.currentTextColor != nameColor) nameText.setTextColor(nameColor)
            val valueColor = if (filled > width - ctx.dp(15)) dark else Color.WHITE
            if (valueText.currentTextColor != valueColor) valueText.setTextColor(valueColor)
            // Only drawing changes. No animated child width or per-frame text relayout.
            invalidate()
        }
    }

    // ---- open / close -------------------------------------------------------------

    /**
     * Opens the panel; [edit] in edit mode, from a long press on the home screen's Control Center
     * button or the settings ([first]: on page 1's first control rather than the one last focused).
     */
    fun open(edit: Boolean = false, first: Boolean = false) {
        if (isOpen) { if (edit && !editing) startEdit(first); return }
        isOpen = true
        editing = edit
        home.pauseShelfRotation()
        panel.animate().cancel()
        scrim.animate().cancel()
        refresh()
        home.setGridBlocked(true)
        // A full-screen pass every frame: only the panel look, which hides less of the screen, uses it.
        if (scrim.parent == null && !glassStyle) host.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        panel.bringToFront()
        // The clock, weather and corner buttons give way to the panel, which takes their corner.
        home.setHeaderShown(false, immediate = true)
        applyLayout()
        val (p, id) = if (first) firstPlaced() else openFocus()
        page = p
        settlePages()
        updateDots()
        // Sampled before the first frame, so the glass never shows up empty.
        if (glassStyle && (blurDirty || blur == null)) sampleBehind()
        panel.visibility = View.VISIBLE
        panel.alpha = 0f
        // It slides down from the top edge.
        panel.translationY = -ctx.dp(24).toFloat()
        panel.scaleX = 0.97f
        panel.scaleY = 0.97f
        panel.pivotX = panel.width - room.toFloat()
        panel.pivotY = room.toFloat()
        scrim.animate().alpha(1f).setDuration(220).start()
        panel.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(320)
            .setInterpolator(Motion.ease).setUpdateListener { if (glassStyle) invalidateSurfaces() }.start()
        if (!focusOn(id, p)) focusFirst()
        if (edit) editHint()
        host.removeCallbacks(ticker)
        host.post(ticker)
    }

    /**
     * Where focus goes on opening: the control focused when the panel last closed, for as long as
     * the app lives and that control is still placed; page 1's first control otherwise.
     */
    private fun openFocus(): Pair<Int, String> {
        val id = lastFocusId
        if (id != null) layout.find(id)?.let { return it.first to id }
        return firstPlaced()
    }

    /** The first control in reading order, page 1 first. */
    private fun firstPlaced(): Pair<Int, String> {
        layout.pages.forEachIndexed { p, items -> CcLayout.first(items)?.let { return p to it.id } }
        return 0 to ""
    }

    /** Remembers the focused control for the next opening; the large player counts as its player. */
    private fun rememberFocus() {
        val id = if (expanded || morphing) CcLayout.PLAYER else idOf(panel.findFocus())
        if (!id.isNullOrEmpty()) lastFocusId = id
    }

    fun close() {
        if (!isOpen) return
        rememberFocus()
        isOpen = false
        if (moving != null) cancelMove()
        if (editing) { editing = false; wiggleOn(null); for (b in blanks) b.visibility = View.GONE }
        host.removeCallbacks(ticker)
        host.removeCallbacks(progressTick)
        hold.cancel()
        val wasBig = expanded || morphing
        stopMorph()
        if (wasBig) {
            expanded = false
            big.cancelCursor()
            bigHost.animate().alpha(0f).setDuration(180).setUpdateListener(null)
                .withEndAction { if (!expanded) bigHost.visibility = View.INVISIBLE }.start()
        }
        brightness.finishAnimation()
        scrim.animate().alpha(0f).setDuration(180).start()
        home.setHeaderShown(true)
        panel.animate().alpha(0f).translationY(-ctx.dp(24).toFloat()).scaleX(0.97f).scaleY(0.97f).setDuration(200)
            .setUpdateListener { if (glassStyle) invalidateSurfaces() }
            .withEndAction {
                host.removeView(scrim)
                panel.visibility = View.INVISIBLE
            }.start()
        home.restoreGridFocus()
        home.resumeShelfRotation()
    }

    fun destroy() {
        destroyed = true
        isOpen = false
        host.removeCallbacks(ticker)
        host.removeCallbacks(resample)
        host.removeCallbacks(sleepWarning)
        runCatching { host.viewTreeObserver.removeOnGlobalFocusChangeListener(focusWatch) }
        panel.animate().cancel()
        scrim.animate().cancel()
        bigHost.animate().cancel()
        pageTween.cancel()
        slideStep.cancel()
        wiggle.cancel()
        hold.cancel()
        stopMorph()
        endBlurFade()
        host.removeCallbacks(progressTick)
        blur = null
        blurShader = null
        nowReader.shutdown()
        io.shutdown()
        bt.shutdownNow()
    }

    /** Focus back on the panel after a sheet: the control it was on, else the first one. */
    fun refocus() {
        if (expanded) { (big.findFocus() ?: big.firstFocus()).requestFocus(); return }
        val id = focusedId
        val p = if (id == "") focusedPage else id?.let { layout.find(it)?.first }
        if (id != null && p != null && focusOn(id, p)) return
        focusFirst()
    }

    /**
     * Back (or Menu) while open: a control being moved goes back to its place, then edit mode ends;
     * otherwise the large player's cursor goes first, then the large player itself, then the panel.
     */
    fun back() {
        when {
            moving != null -> cancelMove()
            editing -> endEdit()
            !expanded -> close()
            !big.cancelCursor() -> collapse()
        }
    }

    fun refresh() {
        if (destroyed) return
        updateNetwork()
        updateSettings()
        updateSleep()
        updateWeather()
        updateNowPlaying()
        updateBluetooth()
        // On opening, always; otherwise (the home screen coming back to the front) only to find the adapter.
        if (isOpen || btCheckedAt == 0L) refreshBluetooth(force = true)
        if (refreshing) return
        refreshing = true
        io.execute {
            val b = tv.brightness()
            val list = tv.inputs()
            val cur = tv.currentInput()
            val summary = tv.inputSummary(list)
            host.post {
                refreshing = false
                if (destroyed) return@post
                // Without a brightness API the slider stays, dimmed, with "—" (Slider.set).
                brightness.set(b)
                inputs = list
                inputsRead = true
                inputCurrent = list.firstOrNull { it.uri == cur }?.name
                inputSummary = summary
                updateInput()
            }
        }
    }

    /**
     * The Réseau control. The address is the subtitle by itself: "Connecté · 192.168.1.20" does not
     * fit the capsule, and what a computer needs to reach the TV (AGENTS.md steps 0 and 1) is the address.
     */
    private fun updateNetwork() {
        val m = networkModule ?: return
        m.title.put((if (Net.isWired(ctx)) "Ethernet" else "Wi-Fi") + (Net.ssid(ctx)?.let { " · $it" } ?: ""))
        m.sub.put(if (!Net.isConnected(ctx)) "Déconnecté" else Net.lanAddress(ctx) ?: "Connecté")
        m.sub.visibility = View.VISIBLE
    }

    /** The Réglages capsule (2 × 1) says when an update is waiting; the round button has no room to. */
    private fun updateSettings() {
        val m = settingsModule ?: return
        val t = Updater.pending(prefs)?.let { "canvasOS $it disponible" }.orEmpty()
        m.sub.put(t)
        m.sub.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * The Source control: the current input, or how many there are. A box (or a TV whose inputs
     * cannot be read) has none to switch to: the control stays, idle, and says so.
     */
    private fun updateInput() {
        val m = inputModule ?: return
        val none = inputsRead && inputs.isEmpty()
        m.idle = none
        val narrow = builtAt[CcLayout.INPUT]?.w == 2
        val t = when {
            !inputsRead -> ""
            none -> "Aucune entrée"
            narrow -> inputCurrent ?: inputs.singleOrNull()?.name ?: "${inputs.size} entrées"
            else -> inputCurrent ?: inputSummary
        }
        m.sub.put(t)
        m.sub.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun toast(text: String, long: Boolean = false) =
        Toast.makeText(ctx, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    // ---- the grid ---------------------------------------------------------------------

    /** A span of [n] cells, gaps between them included. */
    private fun len(n: Int) = n * cell + (n - 1) * gap

    /**
     * Puts every placed control on its page at its place and size, building those whose size
     * changed; takes off those no longer placed. Pages come and go with the layout.
     */
    private fun applyLayout() {
        val n = layout.pages.size
        while (pageViews.size < n) addPageView()
        while (pageViews.size > n) {
            panel.removeView(pageViews.removeAt(pageViews.size - 1))
            blanks.removeAt(blanks.size - 1)
        }
        val placed = HashSet<String>()
        layout.pages.forEachIndexed { p, items ->
            for (it in items) {
                put(slot(it), p, it)
                placed += it.id
            }
        }
        for ((id, v) in views) if (id !in placed) (v.parent as? ViewGroup)?.removeView(v)
        if (CcLayout.PLAYER !in placed) (playerSlot.parent as? ViewGroup)?.removeView(playerSlot)
        layout.pages.forEachIndexed { p, items ->
            blanks[p].visibility = if (editing && items.isEmpty()) View.VISIBLE else View.GONE
        }
        page = page.coerceIn(0, n - 1)
        settlePages()
        updateDots()
        (backing.layoutParams as FrameLayout.LayoutParams).let { lp ->
            val h = len(CcLayout.usedRows(layout)) + room * 2
            if (lp.height != h) { lp.height = h; backing.layoutParams = lp }
        }
    }

    private fun addPageView() {
        val pv = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
        panel.addView(pv, FrameLayout.LayoutParams(width, gridH).apply { leftMargin = room; topMargin = room })
        val blank = Blank()
        pv.addView(blank, FrameLayout.LayoutParams(width, cell))
        pageViews += pv
        blanks += blank
    }

    /** [v] on page [p] at [it]'s cells. */
    private fun put(v: View, p: Int, it: CcLayout.Item) {
        val pv = pageViews[p]
        val w = len(it.w)
        val h = len(it.h)
        val x = it.x * span
        val y = it.y * span
        if (v.parent !== pv) {
            (v.parent as? ViewGroup)?.removeView(v)
            pv.addView(v, FrameLayout.LayoutParams(w, h).apply { leftMargin = x; topMargin = y })
        } else {
            val lp = v.layoutParams as FrameLayout.LayoutParams
            if (lp.width != w || lp.height != h || lp.leftMargin != x || lp.topMargin != y) {
                lp.width = w; lp.height = h; lp.leftMargin = x; lp.topMargin = y
                v.layoutParams = lp
            }
        }
    }

    /** The view standing in [it]'s place: built (again) at its size, the player in its matching look. */
    private fun slot(it: CcLayout.Item): View {
        if (it.id == CcLayout.PLAYER) { setPlayerLook(it.h == 1); return playerSlot }
        val size = it.size
        val have = views[it.id]
        if (have != null && builtAt[it.id] == size) return have
        if (have != null) {
            // Its glass goes with it: the surface list is walked on every invalidation.
            surfaces.removeAll { s -> s.owner === have }
            (have.parent as? ViewGroup)?.removeView(have)
            idByView.remove(have)
        }
        builtAt[it.id] = size
        val v = widget(it.id, size)
        views[it.id] = v
        idByView[v] = it.id
        when (it.id) {
            CcLayout.NETWORK -> updateNetwork()
            CcLayout.SETTINGS -> updateSettings()
            CcLayout.SLEEP -> updateSleep()
            CcLayout.WEATHER -> updateWeather()
            CcLayout.BLUETOOTH -> updateBluetooth()
            CcLayout.INPUT -> updateInput()
        }
        return v
    }

    /** The view of the control at an existing place, by id. */
    private fun slotOf(id: String): View? = if (id == CcLayout.PLAYER) playerSlot else views[id]

    /** Builds control [id] at [s]: its shape follows the size (round, capsule, tile or card). */
    private fun widget(id: String, s: CcLayout.Size): View {
        val shape = shapeOf(s)
        val narrow = shape == Shape.CAPSULE && s.w == 2
        return when (id) {
            CcLayout.POWER -> GlassButton("Éteindre", R.drawable.ic_power, shape, narrow = narrow) {
                confirm("Éteindre", "Le téléviseur s'éteint complètement. Rallumez-le avec le bouton marche/arrêt de la télécommande.") { Cleaner.shutdown(ctx) }
            }
            CcLayout.NETWORK -> GlassButton("Réseau", R.drawable.ic_wifi, shape, accent = Color.rgb(48, 222, 239), narrow = narrow) {
                close()
                runCatching { ctx.startActivity(tv.wifiIntent()) }
            }.also { networkModule = it }
            CcLayout.SLEEP -> GlassButton("Veille", R.drawable.ic_moon, shape, narrow = narrow) { needAdb { close(); sleepSheet() } }
                .also { sleepModule = it }
            CcLayout.MEMORY -> GlassButton("Libérer la mémoire", R.drawable.ic_memory, shape, narrow = narrow, wrap = true) { cleanNow() }
            CcLayout.PHOTO -> GlassButton("Photo suivante", R.drawable.ic_photo, shape, narrow = narrow, wrap = true) {
                // Only the outcome: "Photo suivante…" would be one toast too many for a quick change.
                ctx.nextWallpaper { if (it.isNotEmpty() && !it.endsWith("…")) toast(it) }
            }
            CcLayout.FRAME -> GlassButton("Cadre photo", R.drawable.ic_screensaver, shape, narrow = narrow, wrap = true) {
                close()
                ctx.openFrame()
            }
            CcLayout.SETTINGS -> GlassButton("Réglages", R.drawable.ic_gear, shape, narrow = narrow) {
                close()
                ctx.startActivity(Intent(ctx, SettingsActivity::class.java))
            }.also { settingsModule = it }
            CcLayout.BRIGHTNESS -> brightness
            CcLayout.INPUT -> GlassButton("Source", R.drawable.ic_input, shape, narrow = narrow) {
                if (inputs.isEmpty()) views[CcLayout.INPUT]?.let { v -> Motion.bump(v, 1, 0) } else pickInput()
            }.also { inputModule = it }
            CcLayout.WEATHER -> GlassButton("Météo", R.drawable.ic_wx_partly_cloudy, shape, narrow = narrow) { weatherPressed() }.also { m ->
                weatherModule = m
                weatherDays = if (shape != Shape.CARD) emptyList() else List(3) { DayCell() }.onEach {
                    m.extra.addView(it, LinearLayout.LayoutParams(0, -2, 1f))
                }
                val days = weatherDays
                m.onFocus = { on -> for (d in days) d.ink(on) }
            }
            CcLayout.BLUETOOTH -> GlassButton("Bluetooth", R.drawable.ic_bluetooth, shape, narrow = narrow) { bluetoothPressed() }
                .also { bluetoothModule = it }
            else -> View(ctx)
        }
    }

    /** The player's look for its size: one line at 4 × 1, the card at 4 × 2. */
    private fun setPlayerLook(one: Boolean) {
        emptyPlayer.glass.radius = ctx.dp(if (one) 100 else 22).toFloat()
        if (one == playerOne && playerSlot.tag != null) return
        playerSlot.tag = true
        val had = playerSlot.hasFocus()
        if (expanded) collapse(animate = false)
        playerOne = one
        // Shown again from scratch in the other look.
        val n = now
        now = null
        showNow(n)
        if (had) playerLook().requestFocus()
    }

    /** The player's view on show now: its look, or the empty card. */
    private fun playerLook(): View = listOf(nowLine, nowCard).firstOrNull { it.visibility == View.VISIBLE } ?: emptyPlayer

    /** Every page but [page] hidden, and nothing left mid-slide. */
    private fun settlePages() {
        pageTween.cancel()
        pageViews.forEachIndexed { p, pv ->
            pv.translationY = 0f
            pv.alpha = 1f
            pv.visibility = if (p == page) View.VISIBLE else View.INVISIBLE
        }
    }

    /**
     * Page [p] slides in from below ([dir] 1) or above (-1) while the page shown slides out the
     * other way, the whole grid moving by its height. Only render properties change on the way;
     * the glass is drawn again each frame, as when the panel opens, since it maps through the
     * page's translation.
     */
    private fun slideTo(p: Int, dir: Int) {
        if (p == page || p !in pageViews.indices) return
        settlePages()
        val from = pageViews[page]
        val to = pageViews[p]
        page = p
        updateDots()
        if (!isOpen) { settlePages(); return }
        val d = (gridH + gap).toFloat()
        to.visibility = View.VISIBLE
        pageTween.start(PAGE_MS, Motion.ease, end = {
            from.visibility = View.INVISIBLE
            from.translationY = 0f
            from.alpha = 1f
            if (glassStyle) invalidateSurfaces()
        }) { f ->
            from.translationY = -dir * d * f
            from.alpha = 1f - f
            to.translationY = dir * d * (1f - f)
            to.alpha = f
            if (glassStyle) invalidateSurfaces()
        }
    }

    /** Small vertical dots at the panel's left edge, one per page focus can reach, while there are two or more. */
    private fun updateDots() {
        val reachable = layout.pages.indices.filter { navItems(it).isNotEmpty() }
        if (reachable.size < 2) { dots.visibility = View.GONE; return }
        dots.visibility = View.VISIBLE
        while (dots.childCount < reachable.size) dots.addView(View(ctx).apply { background = rounded(Color.WHITE, ctx.dp(DOT_DP) / 2f) },
            LinearLayout.LayoutParams(ctx.dp(DOT_DP), ctx.dp(DOT_DP)).apply { topMargin = ctx.dp(3); bottomMargin = ctx.dp(3) })
        while (dots.childCount > reachable.size) dots.removeViewAt(dots.childCount - 1)
        reachable.forEachIndexed { i, p -> dots.getChildAt(i).alpha = if (p == page) 1f else 0.35f }
    }

    /** The control [v] belongs to ("" for an empty page's capsule); null outside the grid. */
    private fun idOf(v: View?): String? {
        var cur: View? = v
        while (cur != null && cur !== panel) {
            idByView[cur]?.let { return it }
            if (cur is Blank) return ""
            cur = cur.parent as? View
        }
        return null
    }

    /** The places focus moves among on page [p]: an empty page in edit mode has its capsule. */
    private fun navItems(p: Int): List<CcLayout.Item> {
        val items = layout.pages.getOrNull(p) ?: return emptyList()
        return if (items.isEmpty() && editing) listOf(CcLayout.Item("", 0, 0, CcLayout.COLS, 1)) else items
    }

    /** What takes focus for [id] on page [p]: the player's look on show, an empty page's capsule. */
    private fun target(id: String, p: Int): View? = when (id) {
        "" -> blanks.getOrNull(p)?.takeIf { it.visibility == View.VISIBLE }
        CcLayout.PLAYER -> if (playerSlot.parent != null) playerLook() else null
        else -> views[id]?.takeIf { it.parent != null }
    }

    /** Focus on [id], on page [p] (sliding there first); false when it is not there. */
    private fun focusOn(id: String, p: Int): Boolean {
        if (p !in pageViews.indices) return false
        if (p != page) slideTo(p, if (p > page) 1 else -1)
        return target(id, p)?.requestFocus() == true
    }

    private fun focusFirst() {
        val (p, id) = firstPlaced()
        focusOn(id, p)
    }

    /** Focus moved: remembered for after a sheet, and in edit mode the focused control wiggles. */
    private fun focusMoved(v: View?) {
        if (!isOpen || v == null) return
        val id = idOf(v) ?: return
        focusedId = id
        focusedPage = page
        if (editing) wiggleOn(if (id == "") null else slotOf(id), strong = moving?.id == id)
    }

    /**
     * Arrows in the panel, once the focused control has not used them (the slider and the two-line
     * player keep Left and Right): the nearest control that way on the page, holes skipped
     * ([CcLayout.neighbour]); past the last row, the next page that has a control slides up and
     * focus lands in the same columns ([CcLayout.entry]); nothing that way, a bump.
     */
    private fun navigate(code: Int): Boolean {
        val dx = when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
        val dy = when (code) { KeyEvent.KEYCODE_DPAD_UP -> -1; KeyEvent.KEYCODE_DPAD_DOWN -> 1; else -> 0 }
        if (dx == 0 && dy == 0) return false
        val f = panel.findFocus()
        val id = idOf(f)
        val from = navItems(page).firstOrNull { it.id == id }
        if (f == null || from == null) { focusFirst(); return true }
        CcLayout.neighbour(navItems(page), from, dx, dy, cameFrom)?.let {
            cameFrom = from.id
            focusOn(it.id, page)
            return true
        }
        if (dy != 0) {
            var p = page + dy
            while (p in layout.pages.indices && navItems(p).isEmpty()) p += dy
            val to = if (p in layout.pages.indices) CcLayout.entry(navItems(p), from, dy > 0) else null
            if (to != null) {
                cameFrom = null
                focusOn(to.id, p)
                return true
            }
        }
        Motion.bump(f, dx, dy)
        return true
    }

    /** Arrows that reached the activity unused while the panel is open: see [navigate]. */
    fun navKey(e: KeyEvent): Boolean {
        if (!isOpen || expanded || morphing || e.action != KeyEvent.ACTION_DOWN) return false
        return navigate(e.keyCode)
    }

    /**
     * Keys in edit mode, before the focused control sees them: arrows move focus (or the control
     * being moved), and OK, pressed and let go on the same control, opens its sheet (or drops the
     * control being moved). The controls' own actions do not run while editing.
     */
    fun preKey(e: KeyEvent): Boolean {
        if (!isOpen || !editing || expanded || morphing) return false
        val code = e.keyCode
        val arrow = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
            code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
        if (!arrow && !isConfirm(code)) return false
        val down = e.action == KeyEvent.ACTION_DOWN
        if (arrow) {
            if (down) { if (moving != null) nudge(code) else navigate(code) }
            return true
        }
        val f = panel.findFocus()
        if (down) {
            if (e.repeatCount == 0) okDownOn = f
        } else if (f != null && okDownOn === f) {
            okDownOn = null
            if (moving != null) drop() else idOf(f)?.let { editSheet(it) }
        }
        return true
    }

    // ---- edit mode --------------------------------------------------------------------

    private fun startEdit(first: Boolean) {
        editing = true
        applyLayout()
        if (first) focusFirst()
        focusMoved(panel.findFocus())
        editHint()
    }

    private fun editHint() = toast("OK sur une commande pour la déplacer, changer sa taille ou la retirer. Retour pour terminer.", long = true)

    private fun endEdit() {
        if (moving != null) cancelMove()
        editing = false
        wiggleOn(null)
        applyLayout()
        refocus()
    }

    /** The focused control wiggles a little while editing, as the home grid's tiles do; more while it is being moved. */
    private fun wiggleOn(v: View?, strong: Boolean = false) {
        val old = wiggling
        if (old === v && !strong) return
        wiggle.cancel()
        old?.rotation = 0f
        wiggling = v
        if (v == null) return
        // Degrees: a wide control turns less, or its ends would swing by several dp.
        val amp = (if (v.width > cell * 3) 0.6f else 1.3f) * (if (strong) 1.8f else 1f)
        fun loop() {
            wiggle.start(WIGGLE_MS, LinearInterpolator(), end = { if (wiggling === v) loop() }) { f ->
                v.rotation = amp * kotlin.math.sin(f * 2 * Math.PI).toFloat()
            }
        }
        loop()
    }

    private fun nameOf(id: String) = when (id) {
        CcLayout.POWER -> "Éteindre"
        CcLayout.SLEEP -> "Veille"
        CcLayout.NETWORK -> "Réseau"
        CcLayout.MEMORY -> "Libérer la mémoire"
        CcLayout.PHOTO -> "Photo suivante"
        CcLayout.FRAME -> "Cadre photo"
        CcLayout.SETTINGS -> "Réglages"
        CcLayout.BRIGHTNESS -> "Luminosité"
        CcLayout.INPUT -> "Source"
        CcLayout.PLAYER -> "Lecteur"
        CcLayout.WEATHER -> "Météo"
        CcLayout.BLUETOOTH -> "Bluetooth"
        else -> id
    }

    private fun sizeName(s: CcLayout.Size) = "${s.w} × ${s.h}"

    /** The sheet of a control in edit mode ("" for an empty page's capsule). */
    private fun editSheet(id: String) {
        if (id == "") { pageSheet(page); return }
        val (p, item) = layout.find(id) ?: return
        val sheet = Sheet(host).title(nameOf(id)).message("Page ${p + 1} · ${sizeName(item.size)}")
        sheet.button("Déplacer") { startMove(id) }
        CcLayout.nextSize(id, item.size)?.let { next -> sheet.button("Taille : ${sizeName(next)}") { resize(id, next) } }
        sheet.button("Retirer") { removeWidget(id) }
        layoutButtons(sheet, p)
        home.showSheet(sheet)
    }

    /** An empty page's sheet in edit mode. */
    private fun pageSheet(p: Int) {
        val sheet = Sheet(host).title("Page vide").message("Page ${p + 1} : ajoutez-y une commande, ou supprimez-la.")
        if (layout.pages.size > 1) sheet.button("Supprimer cette page") { removePage(p) }
        layoutButtons(sheet, p)
        home.showSheet(sheet)
    }

    private fun layoutButtons(sheet: Sheet, p: Int) {
        if (layout.unplaced.isNotEmpty()) sheet.button("Ajouter une commande…") { addSheet(p) }
        if (layout.pages.size < CcLayout.MAX_PAGES) sheet.button("Ajouter une page") { addPage() }
        sheet.button("Réinitialiser le panneau") { resetSheet() }
        sheet.button("Terminer") { endEdit() }
        sheet.button("Annuler") {}
    }

    /** [l] becomes the layout, saved at once, and the grid follows. */
    private fun save(l: CcLayout.Layout) {
        layout = l
        layoutJson = CcLayout.serialize(l)
        prefs.ccLayout = l
        applyLayout()
    }

    private fun startMove(id: String) {
        val (p, it) = layout.find(id) ?: return
        val v = slotOf(id) ?: return
        val at = CcLayout.Pos(p, it.x, it.y)
        moving = Moving(id, at, at, it.w, it.h, v.alpha)
        focusedId = id
        // Over its neighbours while it passes them.
        v.bringToFront()
        wiggleOn(v, strong = true)
    }

    /** The control being moved, one cell; on to the next or previous page past the last or first row. */
    private fun nudge(code: Int) {
        val m = moving ?: return
        val v = slotOf(m.id) ?: return
        val dx = when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
        val dy = when (code) { KeyEvent.KEYCODE_DPAD_UP -> -1; KeyEvent.KEYCODE_DPAD_DOWN -> 1; else -> 0 }
        val to = CcLayout.nudge(layout.pages.size, m.at, m.w, m.h, dx, dy)
        if (to == null) { Motion.bump(v, dx, dy); return }
        val turn = to.page != m.at.page
        m.at = to
        put(v, to.page, CcLayout.Item(m.id, to.x, to.y, m.w, m.h))
        v.bringToFront()
        if (turn) slideTo(to.page, dy) else {
            // Glides the one cell rather than jumping; only its own glass is drawn again on the way.
            val ox = -dx * span.toFloat()
            val oy = -dy * span.toFloat()
            slideStep.start(MOVE_MS, Motion.ease) { f ->
                v.translationX = ox * (1f - f)
                v.translationY = oy * (1f - f)
                if (glassStyle) invalidateSurfaces()
            }
        }
        // Where it would cover another control it shows faded: OK cannot drop it there.
        val fits = CcLayout.fits(layout.pages[to.page], to.x, to.y, m.w, m.h, ignore = m.id)
        v.alpha = if (fits) m.alpha else m.alpha * 0.45f
        if (panel.findFocus() == null || idOf(panel.findFocus()) != m.id) target(m.id, to.page)?.requestFocus()
        if (wiggling !== v) wiggleOn(v, strong = true)
    }

    /** OK while moving: dropped where it stands if it fits there; a bump otherwise. */
    private fun drop() {
        val m = moving ?: return
        val v = slotOf(m.id)
        val l = CcLayout.move(layout, m.id, m.at.page, m.at.x, m.at.y)
        if (l == null) {
            v?.let { Motion.bump(it, 0, 1) }
            toast("Une autre commande occupe cette place.")
            return
        }
        moving = null
        slideStep.cancel()
        v?.alpha = m.alpha
        v?.translationX = 0f
        v?.translationY = 0f
        save(l)
        // Back to the gentle wiggle of a control that is only focused.
        wiggleOn(null)
        if (!focusOn(m.id, m.at.page)) focusFirst()
        wiggleOn(slotOf(m.id))
    }

    /** Back while moving: the control returns to where it was. */
    private fun cancelMove() {
        val m = moving ?: return
        moving = null
        slideStep.cancel()
        slotOf(m.id)?.let { it.alpha = m.alpha; it.translationX = 0f; it.translationY = 0f }
        applyLayout()
        wiggleOn(null)
        if (isOpen && !focusOn(m.id, m.from.page)) focusFirst()
        if (editing && isOpen) wiggleOn(slotOf(m.id))
    }

    /** The next size, its top left corner where it is; a bump when it does not fit there. */
    private fun resize(id: String, size: CcLayout.Size) {
        val l = CcLayout.resize(layout, id, size)
        if (l == null) {
            host.postDelayed({ slotOf(id)?.let { Motion.bump(it, 1, 0) } }, 200)
            toast("Pas assez de place ici pour cette taille : déplacez d'abord la commande.", long = true)
            return
        }
        focusedId = id
        save(l)
    }

    private fun removeWidget(id: String) {
        val (p, item) = layout.find(id) ?: return
        val l = CcLayout.remove(layout, id)
        if (l == null) { toast("Le Centre de contrôle garde au moins une commande."); return }
        val next = CcLayout.nearest(l.pages[p], item)
        focusedId = next?.id ?: ""
        focusedPage = p
        save(l)
    }

    /** The controls not placed; the chosen one takes the first free place that fits ([CcLayout.add]). */
    private fun addSheet(p: Int) {
        val sheet = Sheet(host).title("Ajouter une commande")
            .message("Elle prend la première place libre de cette page, sinon d'une autre page, sinon d'une nouvelle page.")
        for (id in layout.unplaced) sheet.button(nameOf(id)) { add(id, p) }
        sheet.button("Annuler") {}
        home.showSheet(sheet)
    }

    private fun add(id: String, p: Int) {
        val r = CcLayout.add(layout, id, p)
        if (r == null) { toast("Plus de place : retirez une commande ou réduisez-en une.", long = true); return }
        focusedId = id
        save(r.first)
    }

    private fun addPage() {
        val l = CcLayout.addPage(layout) ?: return
        focusedId = ""
        focusedPage = l.pages.size - 1
        save(l)
    }

    private fun removePage(p: Int) {
        val l = CcLayout.removePage(layout, p) ?: return
        val q = (p - 1).coerceAtLeast(0)
        focusedId = CcLayout.first(l.pages[q])?.id ?: ""
        focusedPage = q
        save(l)
    }

    private fun resetSheet() {
        home.showSheet(Sheet(host).title("Réinitialiser le Centre de contrôle ?")
            .message("Les commandes reprennent leur place et leur taille d'origine, sur deux pages.")
            .button("Réinitialiser") {
                prefs.resetCcLayout()
                val l = prefs.ccLayout
                layout = l
                layoutJson = CcLayout.serialize(l)
                focusedId = CcLayout.first(l.pages[0])?.id
                focusedPage = 0
                applyLayout()
            }
            .button("Annuler") {})
    }

    private fun pickInput() {
        if (inputs.isEmpty()) return
        // The list replaces the panel instead of stacking on top of it.
        close()
        val sheet = Sheet(host).title("Source").message(null)
        for (i in inputs) sheet.button(i.name, R.drawable.ic_input) {
            io.execute { tv.setInput(i) }
        }
        sheet.button("Afficher sur l'accueil…") { pinInputs() }
        sheet.button("Annuler") {}
        home.showSheet(sheet)
    }

    /** Inputs shown as tiles on the home grid; one press there switches to them. */
    private fun pinInputs() {
        val sheet = Sheet(host).title("Afficher sur l'accueil")
            .message("Chaque source cochée devient une icône sur l'accueil : une pression suffit pour y basculer.")
        for (i in inputs) {
            val on = home.isPinned(i)
            sheet.button(if (on) "✓  ${i.name}" else i.name, R.drawable.ic_input) { home.pinInput(i, !on) }
        }
        sheet.button("OK") {}
        home.showSheet(sheet)
    }

    /**
     * The Météo control, from the forecast the home screen keeps ([Weather.cached]); a quick
     * preference read, safe on the UI thread. Idle while the weather is off or has no forecast;
     * at 4 × 2 it adds the next three days.
     */
    private fun updateWeather() {
        val m = weatherModule ?: return
        val on = prefs.weather
        val f = if (on) Weather.cached(ctx) else null
        val place = prefs.weatherPlace
        // Unchanged text and glyph are left alone, so reopening does not relayout and redraw the glass.
        m.title.put(if (place.isEmpty() || !on) "Météo" else "Météo · $place")
        m.sub.put(when {
            !on -> "Météo désactivée"
            f != null -> Weather.summary(f)
            !Net.isConnected(ctx) -> "Hors connexion"
            else -> "Pas encore de prévisions"
        })
        m.sub.visibility = View.VISIBLE
        m.idle = f == null
        val icon = if (f != null) Weather.icon(f) else R.drawable.ic_wx_partly_cloudy
        if (m.tag != icon) { m.setIcon(icon); m.tag = icon }
        if (weatherDays.isEmpty()) return
        val days = if (f != null) Weather.upcoming(f).drop(1).take(weatherDays.size) else emptyList()
        weatherDays.forEachIndexed { i, cell ->
            val d = days.getOrNull(i)
            cell.visibility = if (d == null) View.INVISIBLE else View.VISIBLE
            if (d == null) return@forEachIndexed
            cell.day.put(shortDay(d.date))
            cell.temps.put("${Weather.degrees(d.min)} / ${Weather.degrees(d.max)}")
            val g = Weather.icon(Weather.kind(d.code))
            if (cell.glyph.tag != g) { cell.glyph.setImageResource(g); cell.glyph.tag = g }
        }
    }

    /** "Sam.", a day's short name in French, for the 4 × 2 Météo card. */
    private fun shortDay(date: String): String = runCatching {
        java.time.LocalDate.parse(date).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.FRANCE)
            .replaceFirstChar { it.titlecase(java.util.Locale.FRANCE) }
    }.getOrDefault(date)

    /** OK on Météo: the forecast; while off, the settings page where it is switched on; offline, a bump. */
    private fun weatherPressed() {
        when {
            !prefs.weather -> { close(); home.openSettings(SettingsActivity.PAGE_LOOK) }
            Weather.cached(ctx) == null -> weatherModule?.let { Motion.bump(it, 1, 0) }
            else -> weatherSheet()
        }
    }

    /** Five days of forecast, read from the kept answer: nothing is fetched here. */
    private fun weatherSheet() {
        val f = Weather.cached(ctx)
        close()
        val place = prefs.weatherPlace
        val sheet = Sheet(host).title(if (place.isEmpty()) "Météo" else "Météo à $place")
        // Open-Meteo's data is CC BY 4.0: credited wherever the forecast is shown in full.
        val credit = "Prévisions : Open-Meteo.com"
        val where = if (prefs.weatherAuto) "\nLieu détecté d'après l'adresse IP du téléviseur ; choisissez une autre ville depuis la page téléphone (onglet Réglages)." else ""
        if (f == null) {
            sheet.message("Les prévisions s'afficheront ici dès que le téléviseur les aura reçues ; il lui faut pour cela une connexion à Internet.\n$credit")
        } else {
            sheet.message(credit + where)
            Weather.upcoming(f).take(5).forEach { sheet.line(Weather.dayLine(it)) }
        }
        sheet.button("OK") {}
        home.showSheet(sheet)
    }

    /**
     * The Lecture capsule: the sessions are read off the UI thread (on open, on return home, then
     * every 2 s while open) and the capsule changes only when what it shows changed.
     */
    private fun updateNowPlaying() {
        if (destroyed) return
        // Not read at all while off, or while the player is not in the panel.
        if (!prefs.nowPlaying || playerSlot.parent == null) { showNow(null); return }
        if (nowReading) return
        nowReading = true
        val started = SystemClock.elapsedRealtime()
        io.execute {
            val n = runCatching { nowReader.read() }.getOrNull()
            host.post {
                nowReading = false
                if (destroyed) return@post
                // Begun before a press or just after it, it may not know of it yet: what the press showed stays until the next reading.
                if (started < actedAt + ACT_GRACE_MS && n != null && n.pkg == now?.pkg) return@post
                showNow(if (prefs.nowPlaying) n else null)
            }
        }
    }

    // ---- Bluetooth ------------------------------------------------------------------

    /** The Bluetooth capsule's subtitle, from what the last refresh found: formatting only, safe on the UI thread. */
    private fun updateBluetooth() {
        val m = bluetoothModule ?: return
        val state = btState
        val text = btPending ?: when (state) {
            // No adapter (or none found yet): the control stays, idle, rather than leave a hole.
            Bluetooth.State.NONE -> "Indisponible"
            Bluetooth.State.NEEDS_PERMISSION -> "Autorisation requise"
            Bluetooth.State.OFF -> "Désactivé"
            else -> Bluetooth.summary(btDevices)
        }
        // Unchanged text is left alone, so the 2 s ticker does not relayout and redraw the glass.
        m.sub.put(text)
        m.sub.visibility = View.VISIBLE
        m.idle = btPending == null && state != Bluetooth.State.READY
    }

    /**
     * Reads the paired devices on the Bluetooth thread. The profile services take a moment to bind
     * and are not cheap, so the 2 s ticker only gets here every [BT_REFRESH_MS]; opening the panel
     * ([force]) always does.
     */
    private fun refreshBluetooth(force: Boolean) {
        if (destroyed) return
        val now = SystemClock.elapsedRealtime()
        if (btBusy || (!force && now - btCheckedAt < BT_REFRESH_MS)) return
        btBusy = true
        btCheckedAt = now
        bt.execute {
            var state = Bluetooth.State.NONE
            var list = emptyList<Bluetooth.Device>()
            try {
                state = Bluetooth.state(ctx)
                if (state == Bluetooth.State.READY) list = Bluetooth.devices(ctx)
            } catch (e: Throwable) {
                // A broken Bluetooth stack must not take the Control Center with it.
            }
            host.post {
                btBusy = false
                if (destroyed) return@post
                btState = state
                btDevices = list
                updateBluetooth()
            }
        }
    }

    private fun showNow(n: NowPlaying?) {
        val old = now
        now = n
        val shown: View = if (playerOne) nowLine else nowCard
        if (n == null) {
            if (expanded) collapse(animate = false)
            host.removeCallbacks(progressTick)
            // The player keeps its place, as an empty card: nothing moves, focus stays on it.
            showPlayerLook(emptyPlayer)
            return
        }
        // Text and artwork only when they changed: the 2 s poll must not relayout and redraw the glass.
        if (!n.sameAs(old) || shown.visibility != View.VISIBLE) {
            nowLine.title.text = n.title
            nowLine.contentDescription = n.title
            nowLine.sub.text = n.sub
            nowLine.sub.visibility = View.VISIBLE
            nowCard.bind(n)
            big.bind(n)
            if (old == null || old.art !== n.art || shown.visibility != View.VISIBLE) {
                nowLine.glyph.setImageDrawable(NowPlaying.Cover(n.art, ctx.dp(8).toFloat(), note()))
                val lp = nowLine.glyph.layoutParams as LinearLayout.LayoutParams
                if (lp.width != ctx.dp(35)) {
                    lp.width = ctx.dp(35)
                    lp.height = ctx.dp(35)
                    lp.marginEnd = ctx.dp(9)
                    nowLine.glyph.layoutParams = lp
                    // Concentric with the capsule's end: (47 - 35) / 2 = 6 dp all around.
                    nowLine.setPadding(ctx.dp(6), 0, ctx.dp(14), 0)
                }
            }
        }
        showPlayerLook(shown)
        showPlayback()
    }

    /** [v] (a look of the player, or the empty card) shown in the player's place; focus goes with it. */
    private fun showPlayerLook(v: View) {
        val all = listOf(nowLine, nowCard, emptyPlayer)
        if (v.visibility == View.VISIBLE && all.none { it !== v && it.visibility == View.VISIBLE }) return
        val had = playerSlot.hasFocus()
        v.visibility = View.VISIBLE
        if (had) v.requestFocus()
        for (o in all) if (o !== v) o.visibility = View.GONE
    }

    private fun note() = ctx.getDrawable(R.drawable.ic_music)

    /** The play/pause glyphs and the progress, from [now]; then again in a second while a bar is on screen. */
    private fun showPlayback() {
        val n = now ?: return
        val glyph = if (n.playing) R.drawable.ic_pause else R.drawable.ic_play
        for (v in listOf(nowLine.trail, nowCard.play, big.play.glyph)) {
            if (v != null && v.tag != glyph) { v.setImageResource(glyph); v.tag = glyph }
        }
        showProgress()
    }

    private val progressTick = Runnable { showProgress() }

    private fun showProgress() {
        host.removeCallbacks(progressTick)
        val n = now ?: return
        val pos = n.positionNow()
        val card = !playerOne && nowCard.visibility == View.VISIBLE
        if (card) nowCard.progress(n, pos)
        if (expanded) big.progress(n, pos)
        // Next when the elapsed second changes, not on a clock of its own: the time never lags.
        if (isOpen && (card || expanded) && n.hasDuration && pos >= 0) {
            val wait = if (n.speed > 0f) ((1000 - pos % 1000) / n.speed).toLong() + 20 else 1000L
            host.postDelayed(progressTick, wait.coerceIn(100L, 1000L))
        }
    }

    // ---- the player's keys and actions ---------------------------------------------------------

    /**
     * Keys on the one-line and two-line players. OK plays or pauses; held, the player swells a
     * little while it is held, then grows into the large player. On the two-line one ([skips]), Left goes back (to the start of the track, or the
     * previous track within its first 3 s) and Right to the next track; held, they seek 10 s at a
     * time. A press and a hold are told apart by time, not by repeat events, which some remotes
     * never send. Up and Down move through the panel as usual.
     */
    private fun playerKey(v: View, code: Int, e: KeyEvent, skips: Boolean): Boolean {
        val dir = when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
        if (dir == 0 && !isConfirm(code)) return false
        if (dir != 0 && !skips) return false
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (e.repeatCount == 0) {
                if (dir == 0) {
                    val wait = ViewConfiguration.getLongPressTimeout().toLong()
                    swell(v, true, wait)
                    hold.down(v, code, wait) {
                        expand()
                        // Nothing to grow into (the session has just ended): the player settles back.
                        if (!expanded) swell(v, false)
                    }
                } else hold.down(v, code, SEEK_HOLD_MS, SEEK_EVERY_MS) { seekBy(dir) }
            }
        } else if (e.action == KeyEvent.ACTION_UP && hold.up(v, code)) {
            when {
                dir == 0 -> { swell(v, false); togglePlay() }
                else -> {
                    Motion.bump(v, dir, 0)
                    if (dir < 0) back(now) else skipNext()
                }
            }
        }
        return true
    }

    /** Sends [call] to the session off the UI thread, shows [change] at once, and reads the session again after [readAfter] ms. */
    private fun act(change: ((NowPlaying) -> NowPlaying)?, readAfter: Long, call: (NowPlaying) -> Unit) {
        val n = now ?: return
        actedAt = SystemClock.elapsedRealtime()
        if (change != null) { now = change(n); showPlayback() }
        io.execute { call(n) }
        host.postDelayed({ updateNowPlaying() }, readAfter)
    }

    private fun togglePlay() = act({ it.toggled() }, 500) { it.playPause() }

    private fun skipNext() = act(null, 700) { it.next() }

    /** « Back »: to the start of the track, or to the previous track when it has only just begun. */
    private fun back(n: NowPlaying?) {
        if (n == null) return
        if (NowPlaying.restartsOnBack(n.positionNow())) seekTo(0L) else act(null, 700) { it.previous() }
    }

    /** One step of 10 s from where playback is now (or was last sent: steps add up while a key is held). */
    private fun seekBy(dir: Int) {
        val n = now ?: return
        val from = n.positionNow()
        if (from < 0) return
        seekTo(NowPlaying.step(from, dir, n.duration))
    }

    private fun seekTo(ms: Long) = act({ it.seeked(ms) }, 900) { it.seekTo(ms) }

    /** The focused player's own zoom ([GlassButton] capsule, [PlayerCard]). */
    private fun playerGrow(v: View) = if (v === nowLine) 1.05f else 1.04f

    /** OK held on the player: it swells a little over [ms], the time a long press takes; let go, it settles back. */
    private fun swell(v: View, on: Boolean, ms: Long = 160L) {
        val base = playerGrow(v)
        v.tweenScale(if (on) base * HOLD_GROW else base, ms, if (on) LinearInterpolator() else Motion.ease) {
            if (glassStyle) v.invalidate()
        }
    }

    /** The player's corner radius at its own size: half its height for the capsule. */
    private fun playerRadius(v: View) = if (v === nowLine) v.height / 2f else ctx.dp(22).toFloat()

    /** Where [v] is drawn now, in host coordinates, every scale on the way included. */
    private fun frameOf(v: View, out: RectF) {
        toHost(v, morphMatrix)
        out.set(0f, 0f, v.width.toFloat(), v.height.toFloat())
        morphMatrix.mapRect(out)
    }

    /**
     * Where the large player will stand, in host coordinates: measured now, before the layout
     * that follows its new content (a live stream has no timeline), so the frame is right from
     * the first step.
     */
    private fun bigFrame(out: RectF) {
        val any = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        big.measure(any, any)
        bigW = big.measuredWidth.coerceAtLeast(1)
        bigH = big.measuredHeight.coerceAtLeast(1)
        val lp = bigHost.layoutParams as FrameLayout.LayoutParams
        val right = (host.width - lp.marginEnd).toFloat()
        val top = lp.topMargin.toFloat()
        bigS = bigScaleFor(top, bigH)
        out.set(right - bigW * bigS, top, right, top + bigH * bigS)
    }

    /** The large player's resting scale: it shrinks from its top right corner to fit a short screen. */
    private fun bigScaleFor(top: Float, h: Int): Float {
        if (host.height <= 0 || h <= 0) return 1f
        val room = host.height - ctx.dp(16) - top
        return if (h <= room || room <= 0f) 1f else (room / h).coerceIn(0.6f, 1f)
    }

    /** The large player's own glass, or its dark card, drawn or left to [morph]. */
    private fun bigBackdrop(show: Boolean) {
        big.glass.hidden = !show
        bigCard.alpha = if (show) 255 else 0
        big.invalidate()
    }

    /**
     * The large player's content at [frame]: scaled with the frame's width from its top right
     * corner, and cut at the frame's height, so it never spills out of the glass growing round it.
     */
    private fun placeBig(frame: RectF) {
        val right = (host.width - (bigHost.layoutParams as FrameLayout.LayoutParams).marginEnd).toFloat()
        val top = (bigHost.layoutParams as FrameLayout.LayoutParams).topMargin.toFloat()
        val k = (frame.width() / bigW).coerceAtLeast(0.01f)
        bigHost.pivotX = bigW.toFloat()
        bigHost.pivotY = 0f
        bigHost.scaleX = k
        bigHost.scaleY = k
        bigHost.translationX = frame.right - right
        bigHost.translationY = frame.top - top
        bigClip.set(0, 0, bigW, kotlin.math.ceil(frame.height() / k).toInt().coerceAtMost(bigH))
        big.clipBounds = bigClip
    }

    /**
     * Runs the frame from [from] to [to] in [MORPH_MS], its corners from [fromR] to [toR]; [step]
     * sets what fades with it, from the raw time (0 to 1). Only render properties change on the
     * way (scale, translation, alpha, a clip): nothing is laid out or blurred per frame.
     */
    private fun runMorph(from: RectF, fromR: Float, to: RectF, toR: Float, step: (Float) -> Unit, end: () -> Unit) {
        morphing = true
        morphFrom.set(from)
        morphTo.set(to)
        morph.visibility = View.VISIBLE
        bigBackdrop(false)
        morphTween.start(MORPH_MS, LinearInterpolator(), end = { morphing = false; end() }) { t ->
            val g = Motion.ease.getInterpolation(t)
            morphNow.set(lerp(morphFrom.left, morphTo.left, g), lerp(morphFrom.top, morphTo.top, g),
                lerp(morphFrom.right, morphTo.right, g), lerp(morphFrom.bottom, morphTo.bottom, g))
            morph.frame(morphNow, lerp(fromR, toR, g))
            placeBig(morphNow)
            step(t)
        }
    }

    /** Ends a growing or shrinking frame at once, the large player back at its resting place. */
    private fun stopMorph() {
        morphTween.cancel()
        if (!morphing && morph.visibility != View.VISIBLE) return
        morphing = false
        morph.visibility = View.GONE
        settleBig()
    }

    private fun settleBig() {
        bigHost.translationX = 0f
        bigHost.translationY = 0f
        bigHost.pivotX = bigW.toFloat()
        bigHost.pivotY = 0f
        bigHost.scaleX = bigS
        bigHost.scaleY = bigS
        big.clipBounds = null
        bigBackdrop(true)
    }

    private fun clamp01(v: Float) = v.coerceIn(0f, 1f)

    /**
     * State 3: the player grows, in place, into the large player while the rest of the panel
     * fades out; focus on the timeline (on play/pause when the track has no known length).
     */
    private fun expand() {
        val n = now ?: return
        if (expanded || !isOpen) return
        val player: View = if (playerOne) nowLine else nowCard
        expanded = true
        big.bind(n)
        // The timeline shown or not before the large player is measured.
        showPlayback()
        if (glassStyle && (blurDirty || blur == null)) sampleBehind()
        val from = RectF()
        val fromR: Float
        if (morphing) {
            from.set(morphNow)
            fromR = morph.radius
        } else {
            frameOf(player, from)
            fromR = playerRadius(player) * from.width() / player.width.coerceAtLeast(1)
        }
        val contentFrom = if (bigHost.visibility == View.VISIBLE) bigHost.alpha else 0f
        stopMorph()
        val to = RectF()
        bigFrame(to)
        bigHost.animate().cancel()
        panel.animate().cancel()
        bigHost.visibility = View.VISIBLE
        bigHost.alpha = contentFrom
        big.firstFocus().requestFocus()
        val panelFrom = panel.alpha
        runMorph(from, fromR, to, ctx.dp(30) * bigS, { t ->
            panel.alpha = panelFrom * (1f - clamp01(t / 0.3f))
            bigHost.alpha = lerp(contentFrom, 1f, Motion.ease.getInterpolation(clamp01((t - 0.25f) / 0.6f)))
        }) {
            settleBig()
            morph.visibility = View.GONE
            bigHost.alpha = 1f
            panel.alpha = 0f
            if (expanded) panel.visibility = View.INVISIBLE
        }
    }

    /**
     * Back from state 3: the large player shrinks back into the player and the panel fades in
     * again, focus on the player. Without [animate] (the player has gone), it only fades.
     */
    private fun collapse(animate: Boolean = true) {
        if (!expanded) return
        expanded = false
        big.cancelCursor()
        hold.cancel()
        val player: View = if (playerOne) nowLine else nowCard
        val from = RectF()
        val fromR: Float
        if (morphing) {
            from.set(morphNow)
            fromR = morph.radius
        } else {
            frameOf(big, from)
            fromR = ctx.dp(30) * bigS
        }
        stopMorph()
        panel.animate().cancel()
        panel.visibility = View.VISIBLE
        val hasPlayer = animate && player.visibility == View.VISIBLE && player.width > 0
        if (hasPlayer) {
            // Already at its focused zoom, so the frame lands where the player will rest.
            (player.getTag(R.id.motion_focus) as? Tween)?.cancel()
            player.scaleX = playerGrow(player)
            player.scaleY = player.scaleX
            player.requestFocus()
        } else focusOn(CcLayout.PLAYER, layout.find(CcLayout.PLAYER)?.first ?: page) || run { focusFirst(); true }
        if (!hasPlayer) {
            panel.animate().alpha(1f).setDuration(240).setInterpolator(Motion.ease).setUpdateListener(null).start()
            bigHost.animate().alpha(0f).setDuration(180).setUpdateListener(null)
                .withEndAction { if (!expanded) bigHost.visibility = View.INVISIBLE }.start()
        } else {
            bigHost.animate().cancel()
            val to = RectF()
            frameOf(player, to)
            val toR = playerRadius(player) * to.width() / player.width
            val panelFrom = panel.alpha
            val contentFrom = bigHost.alpha
            runMorph(from, fromR, to, toR, { t ->
                bigHost.alpha = contentFrom * (1f - clamp01(t / 0.4f))
                panel.alpha = lerp(panelFrom, 1f, clamp01((t - 0.35f) / 0.65f))
            }) {
                morph.visibility = View.GONE
                bigHost.visibility = View.INVISIBLE
                settleBig()
                bigHost.alpha = 1f
                panel.alpha = 1f
            }
        }
        showProgress()
    }

    /**
     * One key held at a time: after [down], an action may run once held long enough, then again
     * at intervals; [up] says whether it was a short press on the same control. A key that went
     * down elsewhere (the OK that opened the large player) is not a press here.
     */
    private inner class Hold {
        private var code = 0
        private var owner: View? = null
        private var steps = 0
        private var every = 0L
        private var action: (() -> Unit)? = null
        private val tick = object : Runnable {
            override fun run() {
                steps++
                action?.invoke()
                if (every > 0 && code != 0) host.postDelayed(this, every)
            }
        }

        /** [code] went down on [v]: [action] runs after [after] ms held, then every [every] ms (0: once); none, nothing runs while held. */
        fun down(v: View, code: Int, after: Long = 0L, every: Long = 0L, action: (() -> Unit)? = null) {
            cancel()
            owner = v
            this.code = code
            this.every = every
            this.action = action
            if (action != null) host.postDelayed(tick, after)
        }

        /** [code] came up on [v]: true for a short press there (down on [v] too, and nothing ran while held). */
        fun up(v: View, code: Int): Boolean {
            val short = owner === v && this.code == code && steps == 0
            cancel()
            return short
        }

        /** [v] lost focus: whatever it was holding stops. */
        fun release(v: View) { if (owner === v) cancel() }

        fun cancel() {
            host.removeCallbacks(tick)
            code = 0
            owner = null
            steps = 0
            action = null
        }
    }

    /** The two times under a progress bar: elapsed at the left, what is left at the right. */
    private inner class Times(size: Float) : LinearLayout(ctx) {
        // Not wrap_content: new text in a view of fixed width redraws it without a relayout.
        val elapsed = ctx.text(size, SUB, weight = 500).apply { gravity = Gravity.START; maxLines = 1 }
        val left = ctx.text(size, SUB, weight = 500).apply { gravity = Gravity.END; maxLines = 1 }

        init {
            orientation = HORIZONTAL
            addView(elapsed, LayoutParams(0, -2, 1f))
            addView(left, LayoutParams(0, -2, 1f))
        }

        fun show(at: Long, duration: Long) {
            elapsed.put(NowPlaying.clock(at))
            left.put(NowPlaying.remaining(at, duration))
        }

        fun ink(on: Boolean) {
            val c = if (on) INK_SUB else SUB
            elapsed.setTextColor(c)
            left.setTextColor(c)
        }
    }

    private fun TextView.put(t: String) { if (text.toString() != t) text = t }

    /**
     * A progress bar: one white, rounded fill over a dim track, up to what has played, or up to
     * the large player's cursor while there is one ([NowPlaying.barFill]).
     */
    private inner class Bar(private val rest: Float, private val lit: Float) : View(ctx) {
        private var fraction = 0f
        /** Where the cursor stands (0 to 1), or below 0 without one. */
        private var cursor = -1f
        /** Dark, on a lit (white) control. */
        var ink = false
            set(v) { if (field != v) { field = v; invalidate() } }
        private var thick = rest
        private val thickTween = Tween()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()

        fun set(f: Float, c: Float = -1f) {
            val nf = f.coerceIn(0f, 1f)
            // Within half a pixel nothing would move: no redraw.
            if (c == cursor && kotlin.math.abs(nf - fraction) * width < 0.5f) return
            fraction = nf
            cursor = c
            invalidate()
        }

        /** The large player's timeline thickens while focused. */
        fun light(on: Boolean) {
            val from = thick
            thickTween.start(180) { f -> thick = lerp(from, if (on) lit else rest, f); invalidate() }
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = thick
            val top = (height - h) / 2f
            val v = if (ink) 0 else 255
            p.color = Color.argb(if (ink) 45 else 70, v, v, v)
            r.set(0f, top, w, top + h)
            c.drawRoundRect(r, h / 2, h / 2, p)
            val end = NowPlaying.barFill(w, fraction, cursor, h)
            if (end > 0f) {
                p.color = Color.argb(if (ink) 200 else 255, v, v, v)
                r.set(0f, top, end, top + h)
                c.drawRoundRect(r, h / 2, h / 2, p)
            }
        }
    }

    /** State 2, the two-line player: one focusable glass card. */
    private inner class PlayerCard : LinearLayout(ctx) {
        private val glass = glassFor(this, ctx.dp(22).toFloat())
        private val art = ImageView(ctx)
        private val title = ctx.text(12.5f, weight = 600).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        private val artist = ctx.text(11f, SUB, weight = 500).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        private val rew = ImageView(ctx).apply { setImageResource(R.drawable.ic_fast_rewind) }
        val play = ImageView(ctx).apply { setImageResource(R.drawable.ic_pause) }
        private val fwd = ImageView(ctx).apply { setImageResource(R.drawable.ic_fast_forward) }
        private val bar = Bar(ctx.dp(4).toFloat(), ctx.dp(4).toFloat())
        private val times = Times(9.5f)
        private var shownArt: Bitmap? = null
        private var artSet = false

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            contentDescription = "Lecture"
            val pad = ctx.dp(10)
            setPadding(pad, pad, ctx.dp(12), pad)
            addView(art, LayoutParams(ctx.dp(84), ctx.dp(84)))
            val col = LinearLayout(ctx).apply { orientation = VERTICAL }
            col.addView(title)
            col.addView(artist, LayoutParams(-1, -2).apply { topMargin = ctx.dp(2) })
            val row = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
            row.addView(rew, LayoutParams(ctx.dp(18), ctx.dp(18)))
            row.addView(play, LayoutParams(ctx.dp(21), ctx.dp(21)).apply { marginStart = ctx.dp(14); marginEnd = ctx.dp(14) })
            row.addView(fwd, LayoutParams(ctx.dp(18), ctx.dp(18)))
            col.addView(row, LayoutParams(-1, ctx.dp(22)).apply { topMargin = ctx.dp(5) })
            col.addView(bar, LayoutParams(-1, ctx.dp(6)).apply { topMargin = ctx.dp(5) })
            col.addView(times, LayoutParams(-1, -2).apply { topMargin = ctx.dp(2) })
            addView(col, LayoutParams(0, -2, 1f).apply { marginStart = ctx.dp(11) })
            setOnClickListener { togglePlay() }
            setOnFocusChangeListener { _, on ->
                focus(this, glass, on, 1.04f)
                // Lit glass is bright: dark text and glyphs, as on the other controls.
                title.setTextColor(if (on) INK else Color.WHITE)
                artist.setTextColor(if (on) INK_SUB else SUB)
                for (g in listOf(rew, play, fwd)) g.setColorFilter(if (on) INK else Color.WHITE)
                bar.ink = on
                times.ink(on)
                if (!on) hold.release(this)
            }
        }

        fun bind(n: NowPlaying) {
            title.text = n.title
            artist.text = n.sub
            contentDescription = n.title
            if (!artSet || shownArt !== n.art) {
                artSet = true
                shownArt = n.art
                art.setImageDrawable(NowPlaying.Cover(n.art, ctx.dp(12).toFloat(), note()))
            }
        }

        fun progress(n: NowPlaying, pos: Long) {
            val known = n.hasDuration && pos >= 0
            val vis = if (known) View.VISIBLE else View.INVISIBLE
            if (bar.visibility != vis) { bar.visibility = vis; times.visibility = vis }
            if (!known) return
            bar.set(pos.toFloat() / n.duration)
            times.show(pos, n.duration)
        }
    }

    /**
     * Focus on the large player's round controls, as on tvOS: a faint white disc barely larger
     * than the glyph, edged with a thin white ring. The glyph stays white.
     */
    private class FocusRing(private val radius: Float, stroke: Float) : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = stroke }
        /** 0 = resting (nothing drawn), 1 = focused. */
        var level = 0f
            set(v) { field = v; invalidateSelf() }

        override fun draw(c: Canvas) {
            if (level <= 0f) return
            val x = bounds.exactCenterX()
            val y = bounds.exactCenterY()
            fill.alpha = (40 * level).toInt()
            c.drawCircle(x, y, radius, fill)
            edge.alpha = (220 * level).toInt()
            c.drawCircle(x, y, radius - edge.strokeWidth / 2, edge)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** A round control of the large player: its glyph in a thin white ring while focused. */
    private inner class RoundGlyph(icon: Int, glyphDp: Int, label: String) : FrameLayout(ctx) {
        val glyph = ImageView(ctx).apply { setImageResource(icon) }
        private val ring = FocusRing(ctx.dp(glyphDp / 2 + 9).toFloat(), ctx.dp(1.5f).toFloat())
        private val tween = Tween()

        init {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = label
            background = ring
            addView(glyph, LayoutParams(ctx.dp(glyphDp), ctx.dp(glyphDp), Gravity.CENTER))
            setOnFocusChangeListener { _, on ->
                val from = ring.level
                tween.start(if (on) 220 else 160) { f -> ring.level = lerp(from, if (on) 1f else 0f, f) }
                tweenScale(if (on) 1.06f else 1f, if (on) 260 else 200, if (on) Motion.spring else Motion.ease)
                if (!on) hold.release(this)
            }
        }
    }

    /**
     * The large player's artwork, focusable: it only grows while focused (no ring, no border),
     * and OK opens the app that plays.
     */
    private inner class ArtButton : ImageView(ctx) {
        init {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = "Ouvrir l'app"
            setOnFocusChangeListener { _, on ->
                tweenScale(if (on) 1.06f else 1f, if (on) 260 else 200, if (on) Motion.spring else Motion.ease)
                if (!on) hold.release(this)
            }
        }
    }

    /** The glass (or dark card) frame drawn between the player and the large player while one grows into the other. */
    private inner class MorphView : View(ctx) {
        private val glass = GlassSurface(this, 0f)
        private val card = GradientDrawable().apply { setColor(Color.argb(235, 30, 30, 34)) }
        private val box = Rect()
        var radius = 0f
            private set

        init {
            setWillNotDraw(false)
            // Just below the panel: the player fades out over it, the large player's content fades in above it.
            translationZ = ctx.dp(100).toFloat() + ctx.dp(1) / 2f
            outlineProvider = null
            visibility = GONE
            isFocusable = false
        }

        fun frame(f: RectF, r: Float) {
            f.roundOut(box)
            radius = r
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            if (glassStyle) {
                glass.radius = radius
                glass.bounds = box
                glass.draw(c)
            } else {
                card.cornerRadius = radius
                card.bounds = box
                card.draw(c)
            }
        }
    }

    /**
     * State 3, the large player: artwork, title and artist, the timeline and the three controls,
     * in place of the panel. Focus moves one element at a time. On the timeline, Left and Right
     * move a cursor 10 s at a time without touching playback, OK seeks there and Back drops it.
     */
    private inner class PlayerBig : LinearLayout(ctx) {
        val glass = glassFor(this, ctx.dp(30).toFloat())
        private val art = ArtButton()
        private val title = ctx.text(15f, weight = 600).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        private val artist = ctx.text(13f, SUB, weight = 500).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        private val timeline = Bar(ctx.dp(5).toFloat(), ctx.dp(9).toFloat())
        private val times = Times(11f)
        private val rew = RoundGlyph(R.drawable.ic_fast_rewind, 28, "Retour")
        val play = RoundGlyph(R.drawable.ic_pause, 32, "Lecture ou pause")
        private val fwd = RoundGlyph(R.drawable.ic_fast_forward, 28, "Suivant")
        /** Where OK on the timeline would seek to, in ms; below 0 without a cursor. */
        private var cursor = -1L
        private var shownArt: Bitmap? = null
        private var artSet = false

        init {
            orientation = VERTICAL
            // The focused artwork grows past its box, over the padding.
            clipChildren = false
            clipToPadding = false
            val pad = ctx.dp(18)
            setPadding(pad, pad, pad, ctx.dp(14))
            addView(art, LayoutParams(ctx.dp(BIG_ART_DP), ctx.dp(BIG_ART_DP)))
            addView(title, LayoutParams(ctx.dp(BIG_ART_DP), -2).apply { topMargin = ctx.dp(14) })
            addView(artist, LayoutParams(ctx.dp(BIG_ART_DP), -2).apply { topMargin = ctx.dp(3) })
            timeline.id = View.generateViewId()
            timeline.isFocusable = true
            timeline.contentDescription = "Position"
            addView(timeline, LayoutParams(ctx.dp(BIG_ART_DP), ctx.dp(20)).apply { topMargin = ctx.dp(10) })
            addView(times, LayoutParams(ctx.dp(BIG_ART_DP), -2))
            val row = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
            row.addView(rew, LayoutParams(ctx.dp(54), ctx.dp(54)))
            row.addView(play, LayoutParams(ctx.dp(58), ctx.dp(58)).apply { marginStart = ctx.dp(18); marginEnd = ctx.dp(18) })
            row.addView(fwd, LayoutParams(ctx.dp(54), ctx.dp(54)))
            addView(row, LayoutParams(ctx.dp(BIG_ART_DP), -2).apply { topMargin = ctx.dp(8) })
            rew.nextFocusRightId = play.id
            play.nextFocusLeftId = rew.id
            play.nextFocusRightId = fwd.id
            fwd.nextFocusLeftId = play.id
            timeline.nextFocusDownId = play.id
            timeline.nextFocusUpId = art.id
            art.nextFocusDownId = timeline.id
            for (c in listOf(rew, play, fwd)) {
                c.nextFocusUpId = timeline.id
                c.setOnKeyListener { v, code, e -> controlKey(v, code, e) }
            }
            timeline.setOnKeyListener { v, code, e -> timelineKey(v, code, e) }
            art.setOnKeyListener { v, code, e -> artKey(v, code, e) }
            timeline.setOnFocusChangeListener { _, on ->
                timeline.light(on)
                if (!on) { hold.release(timeline); cancelCursor() }
            }
        }

        /** Where focus starts: the timeline, or play/pause when the track has no known length. */
        fun firstFocus(): View = if (timeline.visibility == View.VISIBLE) timeline else play

        fun bind(n: NowPlaying) {
            title.text = n.title
            artist.text = n.sub
            art.contentDescription = "Ouvrir ${n.title}"
            if (!artSet || shownArt !== n.artLarge) {
                artSet = true
                shownArt = n.artLarge
                art.setImageDrawable(NowPlaying.Cover(n.artLarge, ctx.dp(16).toFloat(), note()))
            }
        }

        fun progress(n: NowPlaying, pos: Long) {
            val known = n.hasDuration && pos >= 0
            val vis = if (known) View.VISIBLE else View.GONE
            if (timeline.visibility != vis) {
                if (!known && timeline.hasFocus()) play.requestFocus()
                timeline.visibility = vis
                times.visibility = vis
            }
            if (!known) return
            val at = if (cursor >= 0) cursor else pos
            timeline.set(pos.toFloat() / n.duration, if (cursor >= 0) cursor.toFloat() / n.duration else -1f)
            times.show(at, n.duration)
        }

        /** Drops the timeline's cursor; false when there was none. */
        fun cancelCursor(): Boolean {
            if (cursor < 0) return false
            cursor = -1L
            showProgress()
            return true
        }

        private fun stepCursor(dir: Int, bump: Boolean) {
            val n = now ?: return
            if (!n.hasDuration) return
            val from = if (cursor >= 0) cursor else n.positionNow().coerceAtLeast(0L)
            val to = NowPlaying.step(from, dir, n.duration)
            if (to == cursor && bump) Motion.bump(timeline, dir, 0)
            cursor = to
            showProgress()
        }

        private fun timelineKey(v: View, code: Int, e: KeyEvent): Boolean {
            val down = e.action == KeyEvent.ACTION_DOWN
            val dir = when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
            when {
                dir != 0 -> {
                    if (down && e.repeatCount == 0) {
                        stepCursor(dir, bump = true)
                        hold.down(v, code, CURSOR_HOLD_MS, CURSOR_EVERY_MS) { stepCursor(dir, bump = false) }
                    } else if (!down) hold.up(v, code)
                }
                isConfirm(code) -> {
                    if (down && e.repeatCount == 0) hold.down(v, code)
                    else if (!down && hold.up(v, code) && cursor >= 0) {
                        val to = cursor
                        cursor = -1L
                        seekTo(to)
                    }
                }
                code == KeyEvent.KEYCODE_DPAD_UP -> if (down) art.requestFocus()
                else -> return false
            }
            return true
        }

        /** The artwork: OK opens the app that plays; Down goes to the timeline; nothing lies above or beside it. */
        private fun artKey(v: View, code: Int, e: KeyEvent): Boolean {
            val down = e.action == KeyEvent.ACTION_DOWN
            when {
                isConfirm(code) -> {
                    if (down) { if (e.repeatCount == 0) hold.down(v, code) }
                    else if (hold.up(v, code) && !openPlayingApp()) Motion.bump(v, 1, 0)
                }
                code == KeyEvent.KEYCODE_DPAD_DOWN -> if (down) firstFocus().requestFocus()
                code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (down && e.repeatCount == 0) Motion.bump(v,
                        if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else 0,
                        if (code == KeyEvent.KEYCODE_DPAD_UP) -1 else 0)
                else -> return false
            }
            return true
        }

        private fun controlKey(v: View, code: Int, e: KeyEvent): Boolean {
            val down = e.action == KeyEvent.ACTION_DOWN
            // The row's ends and its bottom: nothing further in the large player, and nothing behind it may take focus.
            val edge = when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> v === rew
                KeyEvent.KEYCODE_DPAD_RIGHT -> v === fwd
                KeyEvent.KEYCODE_DPAD_DOWN -> true
                else -> false
            }
            // Up from the controls: the timeline, or the artwork when there is no timeline.
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                if (down) (if (timeline.visibility == View.VISIBLE) timeline else art).requestFocus()
                return true
            }
            if (edge) {
                if (down && e.repeatCount == 0) Motion.bump(v,
                    if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else 0,
                    if (code == KeyEvent.KEYCODE_DPAD_UP) -1 else if (code == KeyEvent.KEYCODE_DPAD_DOWN) 1 else 0)
                return true
            }
            if (!isConfirm(code)) return false
            if (down) {
                if (e.repeatCount == 0) hold.down(v, code)
            } else if (hold.up(v, code)) when (v) {
                rew -> back(now)
                fwd -> skipNext()
                else -> togglePlay()
            }
            return true
        }
    }

    /**
     * OK on the large player's artwork: the player and the panel close, then the app that owns the
     * session opens, as its tile would (its TV activity first). False when it has none to open.
     */
    private fun openPlayingApp(): Boolean {
        val n = now ?: return false
        val pm = ctx.packageManager
        val intent = runCatching { pm.getLeanbackLaunchIntentForPackage(n.pkg) ?: pm.getLaunchIntentForPackage(n.pkg) }.getOrNull()
            ?: return false
        close()
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return true
    }

    private fun bluetoothPressed() {
        when {
            btState == Bluetooth.State.NONE -> bluetoothModule?.let { Motion.bump(it, 1, 0) }
            // Checked afresh: it may have been granted over adb since the last refresh.
            !Bluetooth.hasPermission(ctx) -> if (btAsked) bluetoothDenied() else {
                btAsked = true
                // The panel stays open behind Android's dialog; it shows the result when it returns.
                home.requestBluetoothPermission()
            }
            // Granted since the last refresh (over adb): read the devices first, the list is empty.
            btState == Bluetooth.State.NEEDS_PERMISSION -> refreshBluetooth(force = true)
            else -> { close(); bluetoothSheet() }
        }
    }

    /** From [HomeActivity.onRequestPermissionsResult]. */
    fun onBluetoothPermission(granted: Boolean) {
        if (destroyed) return
        refreshBluetooth(force = true)
        if (!granted) bluetoothDenied()
    }

    /** Android's dialog was refused or never appeared (some firmwares skip it): the same permission can come through local adb. */
    private fun bluetoothDenied() {
        close()
        home.showSheet(Sheet(host).title("Bluetooth non autorisé")
            .message("canvasOS a besoin de l'autorisation « Appareils à proximité » pour afficher vos appareils Bluetooth et en connecter un. Elle ne sert à rien d'autre : le téléviseur ne cherche aucun appareil.")
            .button("Autoriser avec le débogage local") { needAdb { grantBluetooth() } }
            .button("Annuler") {})
    }

    private fun grantBluetooth() {
        bt.execute {
            LocalAdb.shell(ctx, "pm grant ${ctx.packageName} ${Bluetooth.PERMISSION}", 10_000)
            host.post { refreshBluetooth(force = true) }
        }
    }

    /**
     * The paired devices. Audio ones connect or disconnect on a press; controllers, remotes and
     * the rest show their state only, as a controller connects itself when it is switched on.
     */
    private fun bluetoothSheet() {
        val devices = btDevices
        // The sheet has no scrolling: five devices and its two buttons fill the screen.
        val shown = devices.take(BT_SHEET_MAX)
        val sheet = Sheet(host).title("Bluetooth")
        sheet.message(when {
            btState == Bluetooth.State.OFF -> "Le Bluetooth du téléviseur est désactivé. Activez-le dans les réglages du téléviseur."
            devices.isEmpty() -> "Aucun appareil associé. Associez-en un depuis les réglages Bluetooth du téléviseur."
            devices.any { it.kind == Bluetooth.Kind.CONTROLLER && !it.connected } -> "Allumez la manette : elle se reconnecte seule."
            else -> null
        })
        for (d in shown.filter { it.kind == Bluetooth.Kind.AUDIO }) {
            sheet.button(Bluetooth.status(d), R.drawable.ic_bluetooth) { toggleBluetooth(d) }
        }
        for (d in shown.filter { it.kind != Bluetooth.Kind.AUDIO }) sheet.line(Bluetooth.status(d))
        if (devices.size > shown.size) sheet.line("… et ${devices.size - shown.size} autres appareils associés")
        sheet.button("Associer un appareil…") { pairBluetooth() }
        sheet.button("Annuler") {}
        home.showSheet(sheet)
    }

    /** Opens the TV's own Bluetooth settings: pairing (and the PIN, if any) is theirs. */
    private fun pairBluetooth() {
        runCatching { ctx.startActivity(Bluetooth.settingsIntent(ctx, tv.settingsIntent())) }
    }

    /**
     * Connects or disconnects [d]. The call returns at once and the link follows a few seconds
     * later, so the capsule reads "Connexion…" until the new state is seen (or 12 s pass).
     */
    private fun toggleBluetooth(d: Bluetooth.Device) {
        val connecting = !d.connected
        btPending = if (connecting) "Connexion…" else "Déconnexion…"
        btBusy = true
        updateBluetooth()
        bt.execute {
            val asked = try {
                if (connecting) Bluetooth.connect(ctx, d) else Bluetooth.disconnect(ctx, d)
            } catch (e: Throwable) { false }
            var list: List<Bluetooth.Device>? = null
            try {
                var waited = 0
                while (asked && waited < 8) {
                    Thread.sleep(1_500)
                    waited++
                    list = Bluetooth.devices(ctx)
                    if (list.firstOrNull { it.address == d.address }?.connected == connecting) break
                }
            } catch (e: Throwable) {
                // Interrupted by destroy(): nothing left to update.
            }
            host.post {
                btBusy = false
                btPending = null
                if (destroyed) return@post
                list?.let { btDevices = it }
                btCheckedAt = SystemClock.elapsedRealtime()
                updateBluetooth()
                if (!asked) bluetoothUnavailable(d, connecting)
            }
        }
    }

    /** The firmware keeps its Bluetooth connect and disconnect from apps: the TV's own settings can still do it. */
    private fun bluetoothUnavailable(d: Bluetooth.Device, connecting: Boolean) {
        if (!home.hasWindowFocus()) return
        home.showSheet(Sheet(host).title(if (connecting) "Connexion impossible" else "Déconnexion impossible")
            .message("Ce téléviseur ne laisse pas canvasOS ${if (connecting) "connecter" else "déconnecter"} « ${d.label} ». Faites-le depuis les réglages Bluetooth du téléviseur.")
            .button("Réglages Bluetooth") { pairBluetooth() }
            .button("OK") {})
    }

    /** The Veille capsule's subtitle: time left on the sleep timer, nothing when none is running. */
    private fun updateSleep() {
        val left = SleepTimer.remainingMs(ctx)
        val sub = (sleepModule ?: return).sub
        val text = if (left > 0) "Dans " + SleepTimer.span(left) else ""
        // Polled every 2 s: an unchanged subtitle must not relayout and redraw the glass.
        if (sub.text.toString() != text) sub.text = text
        sub.visibility = if (left > 0) View.VISIBLE else View.GONE
    }

    /** The Veille capsule: sleep now, or after a delay. Behind [needAdb], as the sleep key is sent through local adb. */
    private fun sleepSheet() {
        val left = SleepTimer.remainingMs(ctx)
        val sheet = Sheet(host).title("Veille").message(null)
        sheet.button("Maintenant") {
            // Going to sleep by hand ends the timer: it would otherwise put the TV back to sleep after the next wake.
            stopSleepTimer(announce = false)
            Cleaner.sleep(ctx)
        }
        for (minutes in listOf(15, 30, 60, 90)) {
            sheet.button("Dans " + SleepTimer.span(minutes * 60_000L)) { startSleepTimer(minutes) }
        }
        if (left > 0) sheet.button("Annuler le minuteur (reste ${SleepTimer.span(left)})") { stopSleepTimer() }
        sheet.button("Annuler") {}
        home.showSheet(sheet)
    }

    private fun startSleepTimer(minutes: Int) {
        SleepTimer.schedule(ctx, minutes)
        armSleepWarning()
        updateSleep()
        Toast.makeText(ctx, "Veille dans " + SleepTimer.span(minutes * 60_000L), Toast.LENGTH_SHORT).show()
    }

    private fun stopSleepTimer(announce: Boolean = true) {
        SleepTimer.cancel(ctx)
        host.removeCallbacks(sleepWarning)
        updateSleep()
        if (announce) Toast.makeText(ctx, "Minuteur de veille annulé", Toast.LENGTH_SHORT).show()
    }

    /** Schedules the one-minute warning for the timer in force; nothing when there is none. */
    private fun armSleepWarning() {
        host.removeCallbacks(sleepWarning)
        val left = SleepTimer.remainingMs(ctx)
        if (left > 0) host.postDelayed(sleepWarning, (left - SleepTimer.WARN_MS).coerceAtLeast(0L))
    }

    /** Only when this home screen is what the user is looking at: an app in front is left alone. */
    private fun warnSleep() {
        val left = SleepTimer.remainingMs(ctx)
        if (destroyed || left <= 0 || !home.hasWindowFocus()) return
        // Cancelling comes first: a press by mistake then keeps the TV on instead of switching it off.
        val sheet = Sheet(host).title("Veille dans une minute")
            .message("Le téléviseur va se mettre en veille. Annulez le minuteur pour continuer.")
            .button("Annuler le minuteur") { stopSleepTimer() }
            .button("OK") {}
        home.showSheet(sheet)
        // The sheet must not wait around for the next wake-up once the TV has gone to sleep.
        host.postDelayed({ sheet.dismiss() }, left)
    }

    private fun cleanNow() {
        toast("Libération de la mémoire…")
        Thread {
            val r = Cleaner.clean(ctx.applicationContext)
            host.post {
                // "312 Mo libérés · 4 apps fermées · 2 en cours d'utilisation épargnées", or "(cache uniquement)" without adb.
                if (!destroyed) toast(r.summary(), long = true)
            }
        }.start()
    }

    private fun needAdb(action: () -> Unit) {
        if (prefs.adbAuthorized) action() else home.showSheet(Sheet(host).title("Autoriser le débogage local")
            .message("Appuyez sur Commencer : le téléviseur affiche « Autoriser le débogage ? ». Cochez « Toujours autoriser », puis choisissez « Autoriser ».")
            .button("Commencer") { home.authorizeAdb() }
            .button("Annuler") {})
    }

    private fun confirm(title: String, msg: String, action: () -> Unit) = needAdb {
        home.showSheet(Sheet(host).title("$title ?").message(msg)
            .button(title) { action() }
            .button("Annuler") {})
    }

    private companion object {
        /** Top edge of the panel and of the large player: the header's corner, which they replace. */
        private const val PANEL_TOP = 30
        /** The large player's artwork, and the width of everything under it. */
        const val BIG_ART_DP = 220
        /** Left or Right held this long on the two-line player starts seeking, then one step this often. */
        const val SEEK_HOLD_MS = 450L
        const val SEEK_EVERY_MS = 400L
        /** Left or Right held on the large player's timeline: the cursor runs on after this, one step this often. */
        const val CURSOR_HOLD_MS = 400L
        const val CURSOR_EVERY_MS = 200L
        /** A reading begun less than this after a press may not show it yet (a seek held down sends one every 400 ms). */
        const val ACT_GRACE_MS = 400L
        val SUB = Color.argb(170, 255, 255, 255)
        val INK = Color.argb(225, 0, 0, 0)
        val INK_SUB = Color.argb(150, 0, 0, 0)

        /** How much more the player swells while OK is held, over the long-press delay. */
        const val HOLD_GROW = 1.04f
        /** The player growing into the large player, or shrinking back. */
        const val MORPH_MS = 350L
        /**
         * The control ([CcLayout] id) focused when a panel last closed, for as long as the process
         * lives (a recreated home screen keeps it); null before the first close.
         */
        var lastFocusId: String? = null
        /** A page of the grid sliding in, a control being moved one cell, one turn of the edit-mode wiggle. */
        const val PAGE_MS = 300L
        const val MOVE_MS = 140L
        const val WIGGLE_MS = 300L
        /** The page dots' size. */
        const val DOT_DP = 5
        /** Glyph and text of a control with nothing to do (no inputs, no forecast…). */
        const val IDLE_ALPHA = 0.45f

        /** OK on a remote, or Enter on a keyboard (View's own confirm keys). */
        fun isConfirm(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

        /** The profile services are not cheap: the panel's 2 s ticker reads them this often at most. */
        const val BT_REFRESH_MS = 10_000L
        /** Devices listed in the sheet; it does not scroll. */
        const val BT_SHEET_MAX = 5
    }
}
