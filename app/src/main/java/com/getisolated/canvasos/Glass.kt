package com.getisolated.canvasos

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Liquid Glass baked on the CPU. Android 12 has no runtime shaders, but the
 * home background is a still picture, so the glass can be computed once per
 * picture and then shown as a plain bitmap.
 */
object LiquidGlass {

    /**
     * Bakes a glass slab covering [rect] (screen pixels) over [source], which the
     * screen shows centre-cropped, with [overlay] drawn on top of it in screen
     * coordinates. Inside, the picture is softly blurred and brightened; towards
     * the rim it is pulled in from beyond the edge, the way a thick rounded edge
     * refracts; the rim itself is a thin line lit from the top left and tinted
     * by what is behind it.
     */
    fun bake(source: Bitmap, screenW: Int, screenH: Int, rect: Rect, radius: Float, overlay: Drawable? = null): Bitmap {
        val w = rect.width()
        val h = rect.height()
        val zone = min(h * 0.26f, radius * 1.4f)    // refracting band along the rim
        val pull = zone * 0.9f                       // how far the rim reaches outside
        val blurR = 7
        val margin = (pull + blurR * 2 + 2).toInt()

        // The part of the screen around the slab, as the user sees it.
        val x0 = rect.left - margin
        val y0 = rect.top - margin
        val cw = w + margin * 2
        val ch = h + margin * 2
        val region = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(region)
        canvas.drawColor(Color.BLACK)
        canvas.translate(-x0.toFloat(), -y0.toFloat())
        val s = max(screenW / source.width.toFloat(), screenH / source.height.toFloat())
        val m = Matrix().apply {
            setScale(s, s)
            postTranslate((screenW - source.width * s) / 2, (screenH - source.height * s) / 2)
        }
        canvas.drawBitmap(source, m, Paint(Paint.FILTER_BITMAP_FLAG))
        overlay?.let { it.setBounds(0, 0, screenW, screenH); it.draw(canvas) }
        val src = IntArray(cw * ch)
        region.getPixels(src, 0, cw, 0, 0, cw, ch)
        region.recycle()
        repeat(2) { boxBlur(src, cw, ch, blurR) }

        val out = IntArray(w * h)
        val hw = w / 2f
        val hh = h / 2f
        val rimW = 2.2f
        for (j in 0 until h) {
            val py = j + 0.5f
            for (i in 0 until w) {
                val px = i + 0.5f
                // Signed distance to the rounded rectangle (negative inside).
                val qx = abs(px - hw) - (hw - radius)
                val qy = abs(py - hh) - (hh - radius)
                val ox = max(qx, 0f)
                val oy = max(qy, 0f)
                val sdf = sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f) - radius
                val a = (0.5f - sdf).coerceIn(0f, 1f)
                if (a <= 0f) continue
                val depth = -sdf
                var nx: Float
                var ny: Float
                if (qx > 0f && qy > 0f) {
                    val l = sqrt(ox * ox + oy * oy).coerceAtLeast(1e-3f)
                    nx = ox / l; ny = oy / l
                } else if (qx > qy) { nx = 1f; ny = 0f } else { nx = 0f; ny = 1f }
                if (px < hw) nx = -nx
                if (py < hh) ny = -ny

                val t = (1f - depth / zone).coerceIn(0f, 1f)
                val off = t * t * pull
                val c = sample(src, cw, ch, px + nx * off + margin, py + ny * off + margin)
                var r = Color.red(c).toFloat()
                var g = Color.green(c).toFloat()
                var b = Color.blue(c).toFloat()
                // A little milky and a little more saturated than what is behind.
                val luma = r * 0.3f + g * 0.59f + b * 0.11f
                r = luma + (r - luma) * 1.2f; g = luma + (g - luma) * 1.2f; b = luma + (b - luma) * 1.2f
                r = r * 0.86f + 30f; g = g * 0.86f + 30f; b = b * 0.86f + 30f

                // Specular rim: strongest where it faces the light (top left), a softer
                // reflection on the opposite side, tinted by the colour behind it.
                val facing = -(nx * 0.45f + ny * 0.9f)
                val light = 0.3f + 0.7f * max(facing, 0f) + 0.35f * max(-facing, 0f)
                if (depth < rimW) {
                    val k = (1f - depth / rimW).coerceIn(0f, 1f) * (0.35f + 0.6f * light)
                    r += (255f - r) * k; g += (255f - g) * k; b += (255f - b) * k
                }
                // Soft inner glow just inside the lit edge.
                if (depth < zone) {
                    val k = (1f - depth / zone) * 0.16f * max(facing, 0f)
                    r += (255f - r) * k; g += (255f - g) * k; b += (255f - b) * k
                }
                out[j * w + i] = Color.argb((a * 255).toInt(), clamp(r), clamp(g), clamp(b))
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun clamp(v: Float) = v.toInt().coerceIn(0, 255)

    private fun sample(px: IntArray, w: Int, h: Int, x: Float, y: Float): Int =
        px[y.toInt().coerceIn(0, h - 1) * w + x.toInt().coerceIn(0, w - 1)]

    /** Separable box blur in place; three channels, opaque output. */
    fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        pass(px, tmp, w, h, r, horizontal = true)
        pass(tmp, px, w, h, r, horizontal = false)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = r * 2 + 1
        for (line in 0 until lines) {
            fun at(k: Int): Int {
                val p = k.coerceIn(0, len - 1)
                return if (horizontal) src[line * w + p] else src[p * w + line]
            }
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) { val c = at(k); sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
            for (k in 0 until len) {
                val idx = if (horizontal) line * w + k else k * w + line
                dst[idx] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = at(k + r + 1)
                val sub = at(k - r)
                sr += ((add shr 16) and 255) - ((sub shr 16) and 255)
                sg += ((add shr 8) and 255) - ((sub shr 8) and 255)
                sb += (add and 255) - (sub and 255)
            }
        }
    }
}

/** A blurred picture of part of the home scene at [scale], and where it starts in the scene's coordinates. */
class GlassSample(val bmp: Bitmap, val x: Float, val y: Float, val scale: Float)

/**
 * Samples the home scene behind a region, for [LiveGlass]: the scene drawn at a quarter of its
 * size, with [hidden] views left out (the glass itself must not see itself), then blurred. On
 * the UI thread, a few milliseconds for a corner of the screen.
 */
object SceneGlass {
    const val SCALE = 0.25f

    /** [draw] draws the scene; by default as it is, or as it will be at the end of a fade ([LiveGlass.follow]). */
    fun sample(scene: View, region: Rect, hidden: List<View>, draw: (Canvas) -> Unit = { scene.draw(it) }): GlassSample? {
        if (scene.width == 0 || region.isEmpty) return null
        val w = (region.width() * SCALE).toInt().coerceAtLeast(1)
        val h = (region.height() * SCALE).toInt().coerceAtLeast(1)
        // Alpha, not visibility: hiding a view takes the focus from its descendants (the capsule's).
        val was = hidden.map { it.alpha }
        hidden.forEach { it.alpha = 0f }
        val bmp = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply {
                    drawColor(Color.BLACK)
                    scale(SCALE, SCALE)
                    translate(-region.left.toFloat(), -region.top.toFloat())
                    draw(this)
                }
            }
        } catch (e: RuntimeException) {
            return null
        } finally {
            hidden.forEachIndexed { i, v -> v.alpha = was[i] }
        }
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) { LiquidGlass.boxBlur(px, w, h, 7) }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        bmp.prepareToDraw()
        return GlassSample(bmp, region.left.toFloat(), region.top.toFloat(), SCALE)
    }
}

/**
 * The Control Center's live glass for a view outside the panel: its share of a blurred sample of
 * the scene ([SceneGlass]), toned and washed, a sheen across the top, a lit rim, and a pooled
 * glow that swells in when focused ([light]). Without a sample yet, a flat translucent shape.
 * The same drawing as the panel's controls, so the two read as one material.
 */
class LiveGlass(private val owner: View, private val root: View, var radius: Float, private val sample: () -> GlassSample?) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val ownerToRoot = Matrix()
    private val rootToOwner = Matrix()
    private val m = Matrix()
    private var sheen: Shader? = null
    private var rim: Shader? = null
    private var spot: Shader? = null
    private var shader: BitmapShader? = null
    private var shaderOf: Bitmap? = null
    /** The sample before the current one, drawn under it while [mix] rises: a new picture fades in rather than snaps. */
    private var prevShader: BitmapShader? = null
    private var prevOf: GlassSample? = null
    private var mix = 1f
    private val mixTween = Tween()
    /** How the next sample fades in: set by [follow] to match a fade of the screen, else [MIX_MS] and the ease. */
    private var nextMs = MIX_MS
    private var nextCurve: android.animation.TimeInterpolator = Motion.ease

    /**
     * The next sample is of the screen as it will be after a fade of [ms] on [curve] (the
     * wallpaper's crossfade, the shelf's, the lyrics' shade): the glass fades to it in step.
     */
    fun follow(ms: Long, curve: android.animation.TimeInterpolator) {
        nextMs = ms
        nextCurve = curve
    }
    private val litTween = Tween()
    /** 0 = resting, 1 = focused. */
    var lit = 0f
        set(value) { field = value; invalidateSelf() }
    private val r get() = minOf(radius, bounds.width() / 2f, bounds.height() / 2f)

    fun light(on: Boolean) {
        val from = lit
        litTween.start(if (on) 280 else 200) { f -> lit = lerp(from, if (on) 1f else 0f, f) }
    }

    override fun onBoundsChange(b: Rect) {
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val x = b.left.toFloat()
        val y = b.top.toFloat()
        sheen = LinearGradient(x, y, x, y + h, intArrayOf(Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255)), floatArrayOf(0f, 0.55f), Shader.TileMode.CLAMP)
        rim = LinearGradient(x, y, x + w, y + h,
            intArrayOf(Color.WHITE, Color.argb(40, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.argb(170, 255, 255, 255)),
            floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
        spot = RadialGradient(x + w * 0.3f, y + h * 0.1f, maxOf(w, h) * 0.95f, intArrayOf(Color.argb(120, 255, 255, 255), Color.argb(0, 255, 255, 255)), null, Shader.TileMode.CLAMP)
    }

    /** Maps the owner's own coordinates to the root's, through every scale and translation on the way. */
    private fun toRoot(out: Matrix) {
        out.reset()
        var cur: View = owner
        while (cur !== root) {
            out.postConcat(cur.matrix)
            val p = cur.parent as? View ?: break
            out.postTranslate((cur.left - p.scrollX).toFloat(), (cur.top - p.scrollY).toFloat())
            cur = p
        }
    }

    override fun draw(c: Canvas) {
        rect.set(bounds)
        val r = r
        val s = sample()
        if (s != null) {
            if (shaderOf !== s.bmp) {
                // A new sample: the one on screen stays under it and fades out. One arriving while a
                // fade still runs (the shelf's picture and the shelf itself, a moment apart) starts
                // a fresh fade from the sample that was reaching full strength.
                if (shaderOf != null) {
                    mixTween.cancel()
                    prevShader = shader
                    prevOf = lastSample
                    mix = 0f
                    val from = 0f
                    val ms = nextMs
                    val curve = nextCurve
                    nextMs = MIX_MS
                    nextCurve = Motion.ease
                    if (ms <= 0L) mix = 1f
                    else mixTween.start(ms, curve) { f -> mix = lerp(from, 1f, f); invalidateSelf(); owner.invalidate() }
                }
                shaderOf = s.bmp
                shader = BitmapShader(s.bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            lastSample = s
            toRoot(ownerToRoot)
            ownerToRoot.invert(rootToOwner)
            glassPaint.colorFilter = body(lit)
            val p = prevOf
            val ps = prevShader
            if (p != null && ps != null && mix < 1f) {
                drawSample(c, ps, p, r, 255)
                drawSample(c, shader!!, s, r, (255 * mix).toInt().coerceIn(0, 255))
            } else {
                prevOf = null
                prevShader = null
                drawSample(c, shader!!, s, r, 255)
            }
        } else {
            paint.shader = null
            paint.color = Color.argb((90 + WASH * lit).toInt().coerceAtMost(255), 48, 52, 58)
            c.drawRoundRect(rect, r, r, paint)
        }
        paint.color = Color.WHITE
        paint.shader = sheen
        paint.alpha = (110 + 145 * lit).toInt()
        c.drawRoundRect(rect, r, r, paint)
        if (lit > 0f) {
            paint.shader = spot
            paint.alpha = (255 * lit).toInt()
            c.drawRoundRect(rect, r, r, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = owner.dp(1f + 0.6f * lit).toFloat()
        paint.alpha = (150 + 105 * lit).toInt()
        paint.shader = rim
        val inset = paint.strokeWidth / 2
        rect.inset(inset, inset)
        c.drawRoundRect(rect, r, r, paint)
        paint.shader = null
        paint.alpha = 255
        paint.style = Paint.Style.FILL
    }

    private var lastSample: GlassSample? = null

    private fun drawSample(c: Canvas, sh: BitmapShader, s: GlassSample, r: Float, alpha: Int) {
        m.setScale(1f / s.scale, 1f / s.scale)
        m.postTranslate(s.x, s.y)
        m.postConcat(rootToOwner)
        sh.setLocalMatrix(m)
        glassPaint.shader = sh
        glassPaint.alpha = alpha
        c.drawRoundRect(rect, r, r, glassPaint)
        glassPaint.alpha = 255
    }

    override fun getOutline(outline: android.graphics.Outline) = outline.setRoundRect(bounds, r)
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** How much white the focused glass takes on. */
        private const val WASH = 140
        /** A new sample fades in over this long. */
        private const val MIX_MS = 320L
        private val toneMatrix = ColorMatrix().apply {
            setSaturation(1.5f)
            postConcat(ColorMatrix(floatArrayOf(0.88f, 0f, 0f, 0f, 22f, 0f, 0.88f, 0f, 0f, 22f, 0f, 0f, 0.88f, 0f, 22f, 0f, 0f, 0f, 1f, 0f)))
        }
        private val bodies = HashMap<Int, ColorMatrixColorFilter>()

        /** The blurred picture's tone: a black wash at rest, white as it lights up, in 17 steps. */
        private fun body(lit: Float): ColorMatrixColorFilter {
            val step = (lit * 16).toInt().coerceIn(0, 16)
            return bodies.getOrPut(step) {
                val l = step / 16f
                val black = 34f * (1f - l) / 255f
                val white = (10f + WASH * l) / 255f
                val k = (1f - black) * (1f - white)
                val o = 255f * white
                ColorMatrixColorFilter(ColorMatrix(toneMatrix).apply {
                    postConcat(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, o, 0f, k, 0f, 0f, o, 0f, 0f, k, 0f, o, 0f, 0f, 0f, 1f, 0f)))
                })
            }
        }
    }
}
