package com.getisolated.canvasos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * What the Control Center's player shows ([Prefs.nowPlaying], off by default): the media session
 * that is playing, or else the one that is paused. Read through [MediaSessions] (the approved
 * notification listener): the session's title, artist, album and artwork, its playback state,
 * position and duration. No notification is read for this, and nothing is written anywhere; the
 * player keeps the last reading and the scaled pictures of the track on screen. An app that only
 * gives the address of its artwork (YouTube Music, Spotify and others on TV) has that picture
 * loaded from where the app says, once per track.
 */
class NowPlaying private constructor(
    private val controller: MediaController,
    val pkg: String,
    val title: String,
    val sub: String,
    /** The album, when the app says ([Lyrics] asks with it); null otherwise. */
    val album: String?,
    /** The scaled, square artwork for the one-line and two-line players; the same object for as long as the track does not change. */
    val art: Bitmap?,
    /** The same artwork at the large player's size. */
    val artLarge: Bitmap?,
    private val artKey: String?,
    /** Sound or video is going ([MediaSessions.ACTIVE]): the player shows pause rather than play. */
    val playing: Boolean,
    /** Where playback was at [updatedAt], in ms; -1 when the session does not say. */
    val position: Long,
    /** [SystemClock.elapsedRealtime] of [position], as [PlaybackState.getLastPositionUpdateTime]. */
    val updatedAt: Long,
    /** How fast the position moves on its own: the playback speed while it plays, 0 otherwise. */
    val speed: Float,
    /** The track's length in ms; 0 or less when unknown (a live stream, or an app that does not say). */
    val duration: Long,
) {
    /** Nothing the player's text and artwork show has changed: they are left alone (no relayout, no redraw). */
    fun sameAs(o: NowPlaying?) = o != null && o.pkg == pkg && o.title == title && o.sub == sub && o.artKey == artKey

    val hasDuration get() = duration > 0

    /** The position now, carried forward from the last reading; -1 when unknown. */
    fun positionNow(): Long = positionAt(position, updatedAt, speed, SystemClock.elapsedRealtime(), duration)

    /** This reading as if playback had just been moved to [ms] (shown at once, before the app says so). */
    fun seeked(ms: Long) = NowPlaying(controller, pkg, title, sub, album, art, artLarge, artKey, playing, ms, SystemClock.elapsedRealtime(), speed, duration)

    /** This reading as if play or pause had just been pressed. */
    fun toggled(): NowPlaying {
        val at = positionNow()
        return NowPlaying(controller, pkg, title, sub, album, art, artLarge, artKey, !playing, at, SystemClock.elapsedRealtime(), if (playing) 0f else 1f, duration)
    }

    /** Pauses what plays, plays what is paused; the state is read again, the player's may be 2 s old. */
    fun playPause() = runCatching {
        val playing = controller.playbackState?.state?.let { it in MediaSessions.ACTIVE } == true
        if (playing) controller.transportControls.pause() else controller.transportControls.play()
    }

    fun previous() = runCatching { controller.transportControls.skipToPrevious() }

    fun next() = runCatching { controller.transportControls.skipToNext() }

    /** Apps that cannot seek ignore it (some live streams, some casting receivers). */
    fun seekTo(ms: Long) = runCatching { controller.transportControls.seekTo(ms.coerceAtLeast(0L)) }

    /**
     * Reads the sessions, keeping the scaled artwork of the track on screen, so the 2 s poll does
     * not decode and scale the same picture again. One per Control Center; blocking binder calls,
     * so off the UI thread, and from one thread at a time. An artwork given only as an address is
     * loaded on a thread of its own (a slow server must not hold up play/pause), then [onArt] is
     * told so the player can read the session again at once.
     */
    class Reader(private val ctx: Context, private val artPx: Int, private val largePx: Int, private val onArt: () -> Unit = {}) {
        @Volatile private var artKey: String? = null
        private var artBmp: Bitmap? = null
        private var largeBmp: Bitmap? = null
        private val labels = HashMap<String, String>()
        /** Loads artwork addresses, one at a time; its thread starts with the first one. */
        private val loader = Executors.newSingleThreadExecutor()
        private val lock = Any()
        /** The last loaded artwork, for the track [Loaded.key]; null pictures when it could not be loaded (not tried again). */
        private var loaded: Loaded? = null

        private class Loaded(val key: String, val small: Bitmap?, val large: Bitmap?)

        /** Stops the artwork loader; a load under way finishes on its own and is dropped. */
        fun shutdown() { loader.shutdownNow() }

        fun read(): NowPlaying? {
            val list = MediaSessions.controllers(ctx)
            val i = pick(list.map { c -> runCatching { c.playbackState?.state }.getOrNull() }) ?: return null
            val c = list[i]
            return runCatching {
                val pkg = c.packageName
                val md = c.metadata
                val ps = c.playbackState
                val playing = ps?.state?.let { it in MediaSessions.ACTIVE } == true
                val app = label(pkg)
                val title = md.text(MediaMetadata.METADATA_KEY_TITLE) ?: md.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                val line = md.text(MediaMetadata.METADATA_KEY_ARTIST) ?: md.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: md.text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: md.text(MediaMetadata.METADATA_KEY_ALBUM)
                val src = md?.let {
                    it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                        ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                }
                // Many TV apps send no picture, only where it is: a content:// or https:// address.
                val uri = if (src != null) null else md?.let {
                    listOf(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                        .firstNotNullOfOrNull { k -> it.getString(k)?.trim()?.takeIf(::loadable) }
                }
                // The session's own key and the track: a new picture for the same track (sent once
                // it has loaded) has other dimensions, another address, or comes after none at all.
                val track = "$pkg|${md.text(MediaMetadata.METADATA_KEY_MEDIA_ID)}|$title|$line"
                val key = when {
                    src != null -> "$track|${src.width}x${src.height}"
                    uri != null -> "$track|$uri"
                    else -> null
                }
                if (key != artKey) {
                    artKey = key
                    artBmp = null
                    largeBmp = null
                    if (src != null) {
                        val sq = square(src)
                        artBmp = sq?.let { scale(it, artPx) }
                        largeBmp = sq?.let { scale(it, largePx) }
                    } else if (uri != null && key != null) load(key, uri)
                }
                if (uri != null && artBmp == null) synchronized(lock) {
                    loaded?.takeIf { it.key == key }?.let { artBmp = it.small; largeBmp = it.large }
                }
                // Only a playing session moves on by itself; buffering and the rest hold still.
                val moving = ps?.state == PlaybackState.STATE_PLAYING
                val speed = if (!moving) 0f else ps?.playbackSpeed?.takeIf { it > 0f } ?: 1f
                val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                NowPlaying(c, pkg, title ?: app, subtitle(title != null, line, app, playing), md.text(MediaMetadata.METADATA_KEY_ALBUM), artBmp, largeBmp, "$artKey|${artBmp != null}",
                    playing, ps?.position ?: -1L, ps?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(), speed, duration)
            }.getOrNull()
        }

        /** Loads [uri] for the track [key] on the loader's thread; dropped if the track has changed by then. */
        private fun load(key: String, uri: String) {
            runCatching {
                loader.execute {
                    if (artKey != key) return@execute
                    val sq = runCatching { decode(uri) }.getOrNull()?.let { square(it) }
                    val small = sq?.let { scale(it, artPx) }
                    val large = sq?.let { scale(it, largePx) }
                    synchronized(lock) { loaded = Loaded(key, small, large) }
                    if (small != null && artKey == key) onArt()
                }
            }
        }

        /** The picture at [uri], decoded at no more than about twice the large player's size. */
        private fun decode(uri: String): Bitmap? {
            val bytes = fetch(uri) ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, largePx) }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }

        /**
         * The bytes at [uri]: through the app's content provider (which may refuse: then none), or
         * over the network with short timeouts. Nothing larger than [MAX_ART_BYTES].
         */
        private fun fetch(uri: String): ByteArray? = when (scheme(uri)) {
            "http", "https" -> {
                val c = URL(uri).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 4_000
                    c.readTimeout = 6_000
                    if (c.responseCode !in 200..299 || c.contentLengthLong > MAX_ART_BYTES) null
                    else c.inputStream.use { capped(it) }
                } finally {
                    c.disconnect()
                }
            }
            else -> ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { capped(it) }
        }

        private fun capped(input: InputStream): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_ART_BYTES) return null
            }
            return out.toByteArray()
        }

        private fun label(pkg: String) = labels.getOrPut(pkg) {
            runCatching { ctx.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)
        }

        /** Square from the middle. */
        private fun square(src: Bitmap): Bitmap? = runCatching {
            // A hardware bitmap cannot be cropped on Android 9; a software copy can.
            val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
            val s = minOf(soft.width, soft.height)
            Bitmap.createBitmap(soft, (soft.width - s) / 2, (soft.height - s) / 2, s, s)
        }.getOrNull()

        /**
         * At [px], once per track: drawn at about its own size, the cover stays sharp without a
         * filter pass at every frame. A small cover is not blown up.
         */
        private fun scale(sq: Bitmap, px: Int): Bitmap? = runCatching {
            if (sq.width <= px) sq else Bitmap.createScaledBitmap(sq, px, px, true)
        }.getOrNull()

        private fun MediaMetadata?.text(key: String): String? =
            this?.getText(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * The artwork as a square with rounded corners; without one, a grey square with [note] (the
     * music glyph) in the middle. Ignores the colour filter a player puts on its glyphs.
     */
    class Cover(bitmap: Bitmap?, private val radius: Float, private val note: Drawable?) : Drawable() {
        private val art = bitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        // Not named `shader` inside apply: there it would be the Paint's own (null), and every
        // cover would be drawn as the empty grey square. That is why v1.1's player showed none.
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).also { p ->
            if (art != null) p.shader = art else p.color = Color.argb(110, 128, 128, 136)
        }
        private val w = bitmap?.width?.toFloat() ?: 1f
        private val h = bitmap?.height?.toFloat() ?: 1f
        private val m = Matrix()
        private val rect = RectF()

        override fun onBoundsChange(b: android.graphics.Rect) {
            rect.set(b)
            m.setScale(b.width() / w, b.height() / h)
            m.postTranslate(b.left.toFloat(), b.top.toFloat())
            art?.setLocalMatrix(m)
            if (art == null) note?.let { d ->
                val s = minOf(b.width(), b.height()) / 2
                d.setBounds(b.centerX() - s / 2, b.centerY() - s / 2, b.centerX() + s / 2, b.centerY() + s / 2)
            }
        }

        override fun draw(c: Canvas) {
            c.drawRoundRect(rect, radius, radius, paint)
            if (art == null) note?.draw(c)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /**
     * How a cover sits in a capsule, in px: its side, the room between it and the capsule's edges
     * (the same before the text), and its corner radius. Measured on iOS 26's Now Playing capsule
     * (a 951 × 204 px screenshot): the cover is 144 px (0.706 of the height), 30 px from the edge
     * (0.147, widened to 0.2 at the product owner's request), with 25 px corners (0.174 of its own
     * side); the text starts another inset on. The
     * title's type is 0.19 of the height (a 28 px capital on 204), the artist's 0.147, with about
     * 0.04 of the height between the two lines.
     */
    class CapsuleArt(val size: Int, val inset: Int, val radius: Int, val titlePx: Float, val subPx: Float, val gapPx: Int)

    companion object {
        /** The cover's side, as a fraction of the capsule's height. */
        const val ART_RATIO = 0.706f
        /** The cover's distance from the capsule's edge, as a fraction of the capsule's height. */
        const val ART_INSET_RATIO = 0.2f
        /** The cover's corner radius, as a fraction of its own side. */
        const val ART_CORNER_RATIO = 0.174f
        /** The title's type size, as a fraction of the capsule's height. */
        const val TITLE_RATIO = 0.19f
        /** The artist's type size, as a fraction of the capsule's height. */
        const val SUB_RATIO = 0.147f
        /** The room between the title's line and the artist's, as a fraction of the capsule's height. */
        const val LINE_GAP_RATIO = 0.04f

        /** The cover's and the text's geometry for a capsule [heightPx] tall ([CapsuleArt]); nothing for no height. */
        fun capsuleArt(heightPx: Int, artRatio: Float = ART_RATIO): CapsuleArt {
            if (heightPx <= 0) return CapsuleArt(0, 0, 0, 0f, 0f, 0)
            val size = (heightPx * artRatio).roundToInt().coerceAtLeast(1)
            return CapsuleArt(size, (heightPx * ART_INSET_RATIO).roundToInt(), (size * ART_CORNER_RATIO).roundToInt(),
                heightPx * TITLE_RATIO, heightPx * SUB_RATIO, (heightPx * LINE_GAP_RATIO).roundToInt())
        }

        /** A press on « back » restarts the track past this point, and goes to the previous one before it. */
        const val RESTART_MS = 3_000L
        /** One step of seeking or of the large player's cursor. */
        const val STEP_MS = 10_000L
        /** An artwork address that serves more than this is not a cover. */
        const val MAX_ART_BYTES = 6 * 1024 * 1024

        /** The scheme of [uri], lower case; empty without one. */
        fun scheme(uri: String): String = uri.substringBefore(':', "").lowercase()

        /** An artwork address the player can load: from an app's content provider or resources, or the web. */
        fun loadable(uri: String): Boolean = scheme(uri) in setOf("content", "android.resource", "http", "https")

        /**
         * BitmapFactory's inSampleSize for a [w]×[h] picture shown at [px]: the largest power of two
         * that keeps its short side at [px] or more, so a 3000 px poster is not decoded whole.
         */
        fun sampleSize(w: Int, h: Int, px: Int): Int {
            var s = 1
            val short = minOf(w, h)
            if (px <= 0) return 1
            while (short / (s * 2) >= px) s *= 2
            return s
        }

        /**
         * Where a progress bar's white fill ends, from the left of a bar [width] wide and [thick]
         * thick: at the [cursor] (0 to 1) when there is one, else at what has [played]. Never
         * shorter than its own thickness, so its rounded ends stay round; nothing before playback starts.
         */
        fun barFill(width: Float, played: Float, cursor: Float, thick: Float): Float {
            val at = if (cursor >= 0f) cursor else played
            if (cursor < 0f && played <= 0f) return 0f
            return maxOf(width * at.coerceIn(0f, 1f), thick).coerceAtMost(maxOf(width, 0f))
        }

        /** The session to show among [states] (Android's order, most recent first): the first playing, else the first paused. */
        fun pick(states: List<Int?>): Int? =
            states.indexOfFirst { it in MediaSessions.ACTIVE }.takeIf { it >= 0 }
                ?: states.indexOfFirst { it == PlaybackState.STATE_PAUSED }.takeIf { it >= 0 }

        /**
         * The second line: the artist. Without a title of its own the app's name is the title, so
         * the line says what the app is doing instead.
         */
        fun subtitle(hasTitle: Boolean, line: String?, app: String, playing: Boolean): String = when {
            line != null -> line
            hasTitle -> app
            playing -> "En lecture"
            else -> "En pause"
        }

        /**
         * Where playback is at [now], from a reading of [position] at [updatedAt] moving at
         * [speed] (0 when it does not move), kept within the track when its [duration] is known.
         */
        fun positionAt(position: Long, updatedAt: Long, speed: Float, now: Long, duration: Long): Long {
            if (position < 0) return -1L
            val p = position + if (speed != 0f && now > updatedAt) ((now - updatedAt) * speed).toLong() else 0L
            return if (duration > 0) p.coerceIn(0L, duration) else p.coerceAtLeast(0L)
        }

        /** « Back » restarts the track when it has played more than [RESTART_MS]; otherwise it goes to the previous track. */
        fun restartsOnBack(position: Long) = position > RESTART_MS

        /** [from] moved by [dir] steps of [STEP_MS], kept within the track (or at 0 or more when its length is unknown). */
        fun step(from: Long, dir: Int, duration: Long): Long {
            val to = from.coerceAtLeast(0L) + dir * STEP_MS
            return if (duration > 0) to.coerceIn(0L, duration) else to.coerceAtLeast(0L)
        }

        /** "0:52", "12:05", "1:02:03": elapsed time, whole seconds. */
        fun clock(ms: Long): String {
            val s = (ms.coerceAtLeast(0L) / 1000)
            val h = s / 3600
            val m = s % 3600 / 60
            val sec = s % 60
            fun two(v: Long) = v.toString().padStart(2, '0')
            return if (h > 0) "$h:${two(m)}:${two(sec)}" else "$m:${two(sec)}"
        }

        /**
         * "−2:46": the time left, with a real minus sign. Whole seconds of the length minus whole
         * seconds played, so the two times always add up to the track's length.
         */
        fun remaining(position: Long, duration: Long): String =
            "−" + clock(((duration / 1000) - (position.coerceAtLeast(0L) / 1000)).coerceAtLeast(0L) * 1000)
    }
}
