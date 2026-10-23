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
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
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
    private val resample = Runnable { if (isOpen && blurDirty) sampleBehind() }
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
    // Glass: layout only, the gaps between controls show the actual wallpaper. Panel: one dark card.
    private val panel = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
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

    private val memButton: GlassButton
    private val brightness: Slider
    private val inputModule: GlassButton
    /** The player ([Prefs.nowPlaying]): what a media session plays; gone while nothing plays or is paused. */
    private var now: NowPlaying? = null
    private val nowReader = NowPlaying.Reader(ctx, ctx.dp(84), ctx.dp(BIG_ART_DP))
    private var nowReading = false
    /** When the user last pressed something on the player: a reading begun before it is out of date. */
    private var actedAt = 0L
    /** [Prefs.playerStyle] is [Prefs.PLAYER_ONE]: the panel shows [nowLine] rather than [nowCard]. */
    private var playerOne = prefs.playerStyle == Prefs.PLAYER_ONE
    /** State 1, one line: artwork, title and artist, play/pause. */
    private val nowLine: GlassButton
    /** State 2, two lines: artwork, title, artist, transport and progress. */
    private val nowCard: PlayerCard
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
    private val networkModule: GlassButton
    private val sleepModule: GlassButton
    private val weatherModule: GlassButton
    private val bluetoothModule: GlassButton
    private var inputs: List<Input> = emptyList()
    private val status = ctx.text(11f, Color.argb(190, 255, 255, 255))
    private val focusables = ArrayList<View>()
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
    /** Below 1 only when the panel is taller than the screen; see [fitScale]. */
    private var restScale = 1f

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
        // tvOS layout: a square power tile beside two capsules, then a row of round buttons.
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        val power = GlassButton("Éteindre", R.drawable.ic_power, Shape.TILE) {
            confirm("Éteindre", "Le téléviseur s'éteint complètement. Rallumez-le avec le bouton marche/arrêt de la télécommande.") { Cleaner.shutdown(ctx) }
        }
        // The tile is narrower than it is tall so the capsules have room for an IP address (about
        // 55 to 75 dp at their subtitle size) and "Dans 27 min": at half the panel each had 54 dp.
        top.addView(power, LinearLayout.LayoutParams(0, cell * 2 + gap, 0.8f).apply { marginEnd = gap })
        val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        networkModule = GlassButton("Réseau", R.drawable.ic_wifi, Shape.CAPSULE, accent = Color.rgb(48, 222, 239)) {
            close()
            runCatching { ctx.startActivity(tv.wifiIntent()) }
        }
        right.addView(networkModule, LinearLayout.LayoutParams(-1, cell))
        sleepModule = GlassButton("Veille", R.drawable.ic_moon, Shape.CAPSULE) { needAdb { close(); sleepSheet() } }
        right.addView(sleepModule, LinearLayout.LayoutParams(-1, cell).apply { topMargin = gap })
        top.addView(right, LinearLayout.LayoutParams(0, -2, 1.2f))
        panel.addView(top)

        val utilities = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        fun utility(label: String, icon: Int, action: () -> Unit): GlassButton =
            GlassButton(label, icon, Shape.ROUND, action = action).also {
                utilities.addView(it, LinearLayout.LayoutParams(cell, cell).apply {
                    if (utilities.childCount > 0) marginStart = (this@ControlCenter.width - cell * 4) / 3
                })
            }
        memButton = utility("Libérer la mémoire", R.drawable.ic_memory) { cleanNow() }
        utility("Photo suivante", R.drawable.ic_photo) { ctx.nextWallpaper { status.text = it; status.visibility = View.VISIBLE } }
        utility("Cadre photo", R.drawable.ic_screensaver) {
            close()
            ctx.openFrame()
        }
        utility("Réglages", R.drawable.ic_gear) {
            close()
            ctx.startActivity(Intent(ctx, SettingsActivity::class.java))
        }
        panel.addView(utilities, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })

        brightness = Slider(R.drawable.ic_sun, "Luminosité") { v -> tv.setBrightness(v) }.also { focusables += it }
        panel.addView(brightness, LinearLayout.LayoutParams(-1, ctx.dp(38)).apply { topMargin = gap })
        inputModule = GlassButton("Source", R.drawable.ic_input, Shape.CAPSULE) { pickInput() }
        panel.addView(inputModule, LinearLayout.LayoutParams(-1, cell).apply { topMargin = gap })
        // The player, one of two looks ([Prefs.playerStyle]); its keys are handled by playerKey.
        nowLine = GlassButton("Lecture", R.drawable.ic_music, Shape.CAPSULE) { togglePlay() }
        nowLine.trailing(R.drawable.ic_pause)
        nowLine.visibility = View.GONE
        nowLine.onFocus = { on -> if (!on) hold.release(nowLine) }
        nowLine.setOnKeyListener { v, code, e -> playerKey(v, code, e, skips = false) }
        panel.addView(nowLine, LinearLayout.LayoutParams(-1, cell).apply { topMargin = gap })
        nowCard = PlayerCard()
        nowCard.visibility = View.GONE
        nowCard.setOnKeyListener { v, code, e -> playerKey(v, code, e, skips = true) }
        panel.addView(nowCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
        weatherModule = GlassButton("Météo", R.drawable.ic_wx_partly_cloudy, Shape.CAPSULE) { weatherSheet() }
        panel.addView(weatherModule, LinearLayout.LayoutParams(-1, cell).apply { topMargin = gap })
        // Hidden until the first refresh finds a Bluetooth adapter: a TV without one never shows it.
        bluetoothModule = GlassButton("Bluetooth", R.drawable.ic_bluetooth, Shape.CAPSULE) { bluetoothPressed() }
        bluetoothModule.visibility = View.GONE
        panel.addView(bluetoothModule, LinearLayout.LayoutParams(-1, cell).apply { topMargin = gap })
        panel.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(10) })
        // The host clips the panel to its bounds; the padding is room for focused controls to grow into.
        val room = ctx.dp(16)
        panel.setPadding(room, room, room, room)
        host.addView(panel, FrameLayout.LayoutParams(width + room * 2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = ctx.dp(66) - room; marginEnd = ctx.dp(32) - room
        })
        panel.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (l != oldL || t != oldT || r != oldR || b != oldB) refreshGlass()
        }
        big = PlayerBig()
        bigHost.addView(big, FrameLayout.LayoutParams(-2, -2))
        host.addView(bigHost, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = ctx.dp(66); marginEnd = ctx.dp(32)
        })
        bigHost.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (l != oldL || t != oldT || r != oldR || b != oldB) refreshGlass()
        }
        panel.post { refresh() }
        // A timer started before this screen was recreated still owes its one-minute warning.
        armSleepWarning()
        // Report cleanup without expanding the button and moving every control below it.
        status.setShadowLayer(ctx.dp(3).toFloat(), 0f, 0f, Color.BLACK)
        applyStyle()
    }

    /** Picks up a change of [Prefs.ccStyle] or [Prefs.playerStyle] made in settings. */
    fun applyStyle() {
        val one = prefs.playerStyle == Prefs.PLAYER_ONE
        if (one != playerOne) {
            playerOne = one
            // Shown again from scratch in the other look.
            val n = now
            now = null
            nowLine.visibility = View.GONE
            nowCard.visibility = View.GONE
            showNow(n)
        }
        val glass = prefs.ccGlass(ctx)
        if (glass == glassStyle && panel.tag != null) return
        panel.tag = true
        glassStyle = glass
        panel.background = if (glass) null else panelCard
        big.background = if (glass) big.glass else bigCard
        if (!glass) { blur = null; blurShader = null }
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
    private inner class GlassSurface(val owner: View, val radius: Float) : Drawable() {
        /** How much white the focused glass takes on; sliders keep it low so their fill reads. */
        var focusWash = 140
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
            // A sheen across the upper half, as light falling on a curved top surface.
            sheen = android.graphics.LinearGradient(0f, 0f, 0f, h,
                intArrayOf(Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                floatArrayOf(0f, 0.55f), Shader.TileMode.CLAMP)
            // Rim: bright where it faces the light (top left), a softer reflection bottom right.
            rim = android.graphics.LinearGradient(0f, 0f, w, h,
                intArrayOf(Color.WHITE, Color.argb(40, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.argb(170, 255, 255, 255)),
                floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
            // The focus light: a soft glow pooled under the top left of the glass.
            spot = android.graphics.RadialGradient(w * 0.3f, h * 0.1f, maxOf(w, h) * 0.95f,
                intArrayOf(Color.argb(120, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                null, Shader.TileMode.CLAMP)
        }

        override fun draw(c: Canvas) {
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
                m.setScale(1f / sampleScale, 1f / sampleScale)
                m.postTranslate(blurX, blurY)
                m.postConcat(hostToOwner)
                shader.setLocalMatrix(m)
                glassPaint.shader = shader
                glassPaint.colorFilter = body(focusWash, lit)
                c.drawRoundRect(rect, r, r, glassPaint)
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

    fun backgroundChanging(durationMs: Long, finalScene: (Canvas) -> Unit) { refreshGlass() }

    /**
     * Captures the home screen behind the controls at a quarter of its size and blurs it, on the
     * UI thread: a few milliseconds, once per opening at most.
     */
    private fun sampleBehind() {
        if (destroyed || !glassStyle || host.width == 0 || panel.width == 0) return
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
                    home.sceneView.draw(this)
                }
            }
        } catch (e: RuntimeException) {
            return
        }
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        // Three box passes come close to a Gaussian blur.
        repeat(3) { LiquidGlass.boxBlur(px, w, h, 7) }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        bmp.prepareToDraw()
        // Not recycled: a frame still being drawn may hold the previous picture. It is small.
        blur = bmp
        blurShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        blurX = left.toFloat()
        blurY = top.toFloat()
        blurDirty = false
        invalidateSurfaces()
    }

    private enum class Shape { TILE, CAPSULE, ROUND }

    private inner class GlassButton(
        label: String,
        icon: Int,
        shape: Shape,
        accent: Int = Color.WHITE,
        action: () -> Unit,
    ) : LinearLayout(ctx) {
        // Capsules and circles clamp this to half their height.
        private val glass = glassFor(this, ctx.dp(if (shape == Shape.TILE) 24 else 100).toFloat())
        val glyph = ImageView(ctx)
        val title = ctx.text(12.5f, weight = 600).apply {
            text = label
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val sub = ctx.text(10.5f, Color.argb(205, 255, 255, 255), weight = 500).apply {
            visibility = GONE
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        /** A new glyph, such as the weather's; it keeps its colour. */
        fun setIcon(res: Int) = glyph.setImageResource(res)
        /** A glyph at the far end (the one-line player's play/pause), white like the title. */
        var trail: ImageView? = null
            private set
        /** Told when focus comes or goes, after the control has lit or dimmed. */
        var onFocus: ((Boolean) -> Unit)? = null

        fun trailing(res: Int): ImageView = (trail ?: ImageView(ctx).also {
            trail = it
            addView(it, LayoutParams(ctx.dp(20), ctx.dp(20)).apply { marginStart = ctx.dp(8) })
        }).apply { setImageResource(res) }

        init {
            id = View.generateViewId()
            contentDescription = label
            focusables += this
            orientation = if (shape == Shape.CAPSULE) HORIZONTAL else VERTICAL
            gravity = if (shape == Shape.CAPSULE) Gravity.CENTER_VERTICAL else Gravity.CENTER
            isFocusable = true
            isClickable = true
            if (shape == Shape.CAPSULE) setPadding(ctx.dp(13), 0, ctx.dp(10), 0)
            val size = ctx.dp(when (shape) { Shape.TILE -> 40; Shape.CAPSULE -> 20; Shape.ROUND -> 21 })
            addView(glyph.apply { setImageResource(icon); setColorFilter(accent) },
                LayoutParams(size, size).apply { if (shape == Shape.CAPSULE) marginEnd = ctx.dp(9) })
            // Round buttons are icon-only, as on tvOS; the label stays for accessibility.
            when (shape) {
                Shape.TILE -> addView(title, LayoutParams(-2, -2).apply { topMargin = ctx.dp(8) })
                Shape.CAPSULE -> addView(LinearLayout(ctx).apply {
                    orientation = VERTICAL
                    addView(title)
                    addView(sub, LayoutParams(-1, -2).apply { topMargin = ctx.dp(1) })
                }, LayoutParams(0, -2, 1f))
                Shape.ROUND -> {}
            }
            setOnClickListener { action() }
            setOnFocusChangeListener { _, on ->
                focus(this, glass, on, when (shape) { Shape.TILE -> 1.06f; Shape.CAPSULE -> 1.05f; Shape.ROUND -> 1.1f })
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
        private val corner = ctx.dp(19).toFloat()
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

    fun open() {
        if (isOpen) return
        isOpen = true
        home.pauseShelfRotation()
        panel.animate().cancel()
        scrim.animate().cancel()
        refresh()
        home.setGridBlocked(true)
        // A full-screen pass every frame: only the panel look, which hides less of the screen, uses it.
        if (scrim.parent == null && !glassStyle) host.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        panel.bringToFront()
        // Sampled before the first frame, so the glass never shows up empty.
        if (glassStyle && (blurDirty || blur == null)) sampleBehind()
        panel.visibility = View.VISIBLE
        panel.alpha = 0f
        panel.translationX = ctx.dp(24).toFloat()
        restScale = fitScale()
        panel.scaleX = 0.97f * restScale
        panel.scaleY = 0.97f * restScale
        panel.pivotX = panel.width - panel.paddingRight.toFloat()
        panel.pivotY = panel.paddingTop.toFloat()
        scrim.animate().alpha(1f).setDuration(220).start()
        panel.animate().alpha(1f).translationX(0f).scaleX(restScale).scaleY(restScale).setDuration(320)
            .setInterpolator(Motion.ease).setUpdateListener { if (glassStyle) invalidateSurfaces() }.start()
        memButton.requestFocus()
        host.removeCallbacks(ticker)
        host.post(ticker)
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        host.removeCallbacks(ticker)
        host.removeCallbacks(progressTick)
        hold.cancel()
        if (expanded) {
            expanded = false
            big.cancelCursor()
            bigHost.animate().alpha(0f).setDuration(180).setUpdateListener(null)
                .withEndAction { if (!expanded) bigHost.visibility = View.INVISIBLE }.start()
        }
        brightness.finishAnimation()
        scrim.animate().alpha(0f).setDuration(180).start()
        panel.animate().alpha(0f).translationX(ctx.dp(24).toFloat()).scaleX(0.97f * restScale).scaleY(0.97f * restScale).setDuration(200)
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
        panel.animate().cancel()
        scrim.animate().cancel()
        bigHost.animate().cancel()
        hold.cancel()
        host.removeCallbacks(progressTick)
        blur = null
        blurShader = null
        io.shutdown()
        bt.shutdownNow()
    }

    fun refocus() {
        if (expanded) { big.play.requestFocus(); return }
        (focusables.firstOrNull { it.isFocused } ?: memButton).requestFocus()
    }

    /** Back (or Menu) while open: the large player's cursor goes first, then the large player itself, then the panel. */
    fun back() {
        if (!expanded) close() else if (!big.cancelCursor()) collapse()
    }

    fun refresh() {
        if (destroyed) return
        // Only when nothing more pressing is said: the update hint comes second.
        status.text = if (!prefs.adbAuthorized) "Débogage local non autorisé : extinction, veille et nettoyage complet indisponibles"
        else Updater.pending(prefs)?.let { "canvasOS $it disponible : Réglages → Mise à jour" }.orEmpty()
        status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
        // The address is the subtitle by itself: "Connecté · 192.168.1.20" does not fit the capsule,
        // and what a computer needs to reach the TV (AGENTS.md steps 0 and 1) is the address.
        networkModule.title.text = (if (Net.isWired(ctx)) "Ethernet" else "Wi-Fi") + (Net.ssid(ctx)?.let { " · $it" } ?: "")
        networkModule.sub.text = if (!Net.isConnected(ctx)) "Déconnecté" else Net.lanAddress(ctx) ?: "Connecté"
        networkModule.sub.visibility = View.VISIBLE
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
                brightness.set(b)
                brightness.visibility = if (b == null) View.GONE else View.VISIBLE
                inputs = list
                inputModule.sub.visibility = View.VISIBLE
                inputModule.sub.text = list.firstOrNull { it.uri == cur }?.name ?: summary
            }
        }
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
     * The Météo capsule, from the forecast the home screen keeps ([Weather.cached]); a quick
     * preference read, safe on the UI thread. Gone while the weather is off.
     */
    private fun updateWeather() {
        val on = prefs.weather
        weatherModule.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) return
        val f = Weather.cached(ctx)
        val place = prefs.weatherPlace
        // Unchanged text and glyph are left alone, so reopening does not relayout and redraw the glass.
        fun TextView.show(t: String) { if (text.toString() != t) text = t }
        weatherModule.title.show(if (place.isEmpty()) "Météo" else "Météo · $place")
        weatherModule.sub.show(when {
            f != null -> Weather.summary(f)
            !Net.isConnected(ctx) -> "Hors connexion"
            else -> "Pas encore de prévisions"
        })
        weatherModule.sub.visibility = View.VISIBLE
        val icon = if (f != null) Weather.icon(f) else R.drawable.ic_wx_partly_cloudy
        if (weatherModule.tag != icon) { weatherModule.setIcon(icon); weatherModule.tag = icon }
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
        if (!prefs.nowPlaying) { showNow(null); return }
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

    /**
     * The panel's resting scale: 1 unless its capsules stack taller than the screen (about 540 dp
     * on a 1080p TV that runs at 320 dpi, once the weather and Bluetooth capsules are both shown),
     * in which case it shrinks to fit, from its top right corner, rather than cut off the last one.
     */
    private fun fitScale(): Float {
        if (host.height <= 0 || panel.height <= 0) return 1f
        val top = panel.top + panel.paddingTop
        val content = panel.height - panel.paddingTop - panel.paddingBottom
        val room = host.height - ctx.dp(16) - top
        return if (content <= room || room <= 0) 1f else (room.toFloat() / content).coerceIn(0.7f, 1f)
    }

    // ---- Bluetooth ------------------------------------------------------------------

    /** The Bluetooth capsule's subtitle, from what the last refresh found: formatting only, safe on the UI thread. */
    private fun updateBluetooth() {
        val state = btState
        bluetoothModule.visibility = if (state == Bluetooth.State.NONE) View.GONE else View.VISIBLE
        if (state == Bluetooth.State.NONE) return
        val text = btPending ?: when (state) {
            Bluetooth.State.NEEDS_PERMISSION -> "Autorisation requise"
            Bluetooth.State.OFF -> "Désactivé"
            else -> Bluetooth.summary(btDevices)
        }
        // Unchanged text is left alone, so the 2 s ticker does not relayout and redraw the glass.
        if (bluetoothModule.sub.text.toString() != text) bluetoothModule.sub.text = text
        bluetoothModule.sub.visibility = View.VISIBLE
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
            if (expanded) collapse()
            host.removeCallbacks(progressTick)
            if (nowLine.visibility == View.GONE && nowCard.visibility == View.GONE) return
            // Focus must not vanish with the player.
            if (nowLine.hasFocus() || nowCard.hasFocus()) inputModule.requestFocus()
            nowLine.visibility = View.GONE
            nowCard.visibility = View.GONE
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
            shown.visibility = View.VISIBLE
        }
        showPlayback()
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
     * Keys on the one-line and two-line players. OK plays or pauses; held, it opens the large
     * player. On the two-line one ([skips]), Left goes back (to the start of the track, or the
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
                if (dir == 0) hold.down(v, code, ViewConfiguration.getLongPressTimeout().toLong()) { expand() }
                else hold.down(v, code, SEEK_HOLD_MS, SEEK_EVERY_MS) { seekBy(dir) }
            }
        } else if (e.action == KeyEvent.ACTION_UP && hold.up(v, code)) {
            when {
                dir == 0 -> togglePlay()
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

    /** State 3: the panel fades out and the large player takes its place, focus on play/pause. */
    private fun expand() {
        val n = now ?: return
        if (expanded || !isOpen) return
        expanded = true
        big.bind(n)
        if (glassStyle && (blurDirty || blur == null)) sampleBehind()
        bigHost.animate().cancel()
        bigHost.visibility = View.VISIBLE
        bigHost.alpha = 0f
        val s = bigScale()
        bigHost.pivotX = bigHost.width.toFloat()
        bigHost.pivotY = 0f
        bigHost.scaleX = 0.96f * s
        bigHost.scaleY = 0.96f * s
        big.play.requestFocus()
        showPlayback()
        bigHost.animate().alpha(1f).scaleX(s).scaleY(s).setDuration(280).setInterpolator(Motion.ease)
            .setUpdateListener { if (glassStyle) big.invalidate() }.start()
        panel.animate().alpha(0f).setDuration(180).setUpdateListener(null)
            .withEndAction { if (expanded) panel.visibility = View.INVISIBLE }.start()
    }

    /** Back from state 3: the panel fades in again, focus on its player. */
    private fun collapse() {
        if (!expanded) return
        expanded = false
        big.cancelCursor()
        hold.cancel()
        panel.visibility = View.VISIBLE
        panel.animate().alpha(1f).setDuration(240).setInterpolator(Motion.ease).setUpdateListener(null).start()
        val player: View = if (playerOne) nowLine else nowCard
        if (player.visibility == View.VISIBLE) player.requestFocus() else memButton.requestFocus()
        bigHost.animate().alpha(0f).setDuration(180).setUpdateListener(null)
            .withEndAction { if (!expanded) bigHost.visibility = View.INVISIBLE }.start()
        showProgress()
    }

    /** As [fitScale], for the large player: it shrinks from its top right corner to fit a short screen. */
    private fun bigScale(): Float {
        if (host.height <= 0 || bigHost.height <= 0) return 1f
        val room = host.height - ctx.dp(16) - bigHost.top
        return if (bigHost.height <= room || room <= 0) 1f else (room.toFloat() / bigHost.height).coerceIn(0.6f, 1f)
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

    /** A progress bar: what has played over a dim track; the large player's cursor as a knob and a bright stretch. */
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
            val played = w * fraction
            p.color = Color.argb(if (ink) 200 else if (cursor >= 0f) 150 else 225, v, v, v)
            if (fraction > 0f) {
                r.set(0f, top, maxOf(played, h), top + h)
                c.drawRoundRect(r, h / 2, h / 2, p)
            }
            if (cursor >= 0f) {
                val x = w * cursor.coerceIn(0f, 1f)
                p.color = Color.argb(255, v, v, v)
                r.set(minOf(x, played), top, maxOf(x, played), top + h)
                c.drawRect(r, p)
                c.drawCircle(x, height / 2f, h / 2 + ctx.dp(4), p)
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
            focusables += this
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

    /** A round control of the large player: its glyph on a white disc while focused. */
    private inner class RoundGlyph(icon: Int, glyphDp: Int, label: String) : FrameLayout(ctx) {
        val glyph = ImageView(ctx).apply { setImageResource(icon) }
        private val disc = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.WHITE)
            alpha = 0
        }
        private val tween = Tween()

        init {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = label
            background = disc
            addView(glyph, LayoutParams(ctx.dp(glyphDp), ctx.dp(glyphDp), Gravity.CENTER))
            setOnFocusChangeListener { _, on ->
                val from = disc.alpha
                tween.start(if (on) 220 else 160) { f -> disc.alpha = lerp(from.toFloat(), if (on) 255f else 0f, f).toInt() }
                tweenScale(if (on) 1.1f else 1f, if (on) 260 else 200, if (on) Motion.spring else Motion.ease)
                glyph.setColorFilter(if (on) INK else Color.WHITE)
                if (!on) hold.release(this)
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
        private val art = ImageView(ctx)
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
            for (c in listOf(rew, play, fwd)) {
                c.nextFocusUpId = timeline.id
                c.setOnKeyListener { v, code, e -> controlKey(v, code, e) }
            }
            timeline.setOnKeyListener { v, code, e -> timelineKey(v, code, e) }
            timeline.setOnFocusChangeListener { _, on ->
                timeline.light(on)
                if (!on) { hold.release(timeline); cancelCursor() }
            }
        }

        fun bind(n: NowPlaying) {
            title.text = n.title
            artist.text = n.sub
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
                code == KeyEvent.KEYCODE_DPAD_UP -> if (down && e.repeatCount == 0) Motion.bump(v, 0, -1)
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
                KeyEvent.KEYCODE_DPAD_UP -> timeline.visibility != View.VISIBLE
                else -> false
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

    private fun bluetoothPressed() {
        when {
            btState == Bluetooth.State.NONE -> {}
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
        val sub = sleepModule.sub
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
        status.text = "Libération de la mémoire…"
        status.visibility = View.VISIBLE
        Thread {
            val r = Cleaner.clean(ctx.applicationContext)
            host.post {
                // "312 Mo libérés · 4 apps fermées · 2 en cours d'utilisation épargnées", or "(cache uniquement)" without adb.
                status.text = r.summary()
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

        /** OK on a remote, or Enter on a keyboard (View's own confirm keys). */
        fun isConfirm(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

        /** The profile services are not cheap: the panel's 2 s ticker reads them this often at most. */
        const val BT_REFRESH_MS = 10_000L
        /** Devices listed in the sheet; it does not scroll. */
        const val BT_SHEET_MAX = 5
    }
}
