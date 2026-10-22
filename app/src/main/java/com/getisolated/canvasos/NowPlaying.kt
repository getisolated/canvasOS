package com.getisolated.canvasos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState

/**
 * What the Control Center's "Lecture" capsule shows ([Prefs.nowPlaying], off by default): the
 * media session that is playing, or else the one that is paused. Read through [MediaSessions]
 * (the approved notification listener): the session's title, artist, album and artwork, and its
 * playback state. No notification is read for this, and nothing is written anywhere; the capsule
 * keeps the last reading and one scaled picture.
 */
class NowPlaying private constructor(
    private val controller: MediaController,
    val pkg: String,
    val title: String,
    val sub: String,
    /** The scaled, square artwork; the same object for as long as the track does not change. */
    val art: Bitmap?,
    private val artKey: String?,
) {
    /** Nothing the capsule shows has changed: it is left alone (no relayout, no redraw). */
    fun sameAs(o: NowPlaying?) = o != null && o.pkg == pkg && o.title == title && o.sub == sub && o.artKey == artKey

    /** Pauses what plays, plays what is paused; the state is read again, the capsule's may be 2 s old. */
    fun playPause() = runCatching {
        val playing = controller.playbackState?.state?.let { it in MediaSessions.ACTIVE } == true
        if (playing) controller.transportControls.pause() else controller.transportControls.play()
    }

    fun previous() = runCatching { controller.transportControls.skipToPrevious() }

    fun next() = runCatching { controller.transportControls.skipToNext() }

    /**
     * Reads the sessions, keeping the scaled artwork of the track on screen, so the 2 s poll does
     * not decode and scale the same picture again. One per Control Center; blocking binder calls,
     * so off the UI thread, and from one thread at a time.
     */
    class Reader(private val ctx: Context, private val artPx: Int) {
        private var artKey: String? = null
        private var artBmp: Bitmap? = null
        private val labels = HashMap<String, String>()

        fun read(): NowPlaying? {
            val list = MediaSessions.controllers(ctx)
            val i = pick(list.map { c -> runCatching { c.playbackState?.state }.getOrNull() }) ?: return null
            val c = list[i]
            return runCatching {
                val pkg = c.packageName
                val md = c.metadata
                val playing = c.playbackState?.state?.let { it in MediaSessions.ACTIVE } == true
                val app = label(pkg)
                val title = md.text(MediaMetadata.METADATA_KEY_TITLE) ?: md.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                val line = md.text(MediaMetadata.METADATA_KEY_ARTIST) ?: md.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: md.text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: md.text(MediaMetadata.METADATA_KEY_ALBUM)
                val src = md?.let {
                    it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                        ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                }
                // The session's own key and the track: a new picture for the same track (sent once
                // it has loaded) has other dimensions or comes after none at all.
                val key = src?.let { "$pkg|${md.text(MediaMetadata.METADATA_KEY_MEDIA_ID)}|$title|$line|${it.width}x${it.height}" }
                if (key != artKey) {
                    artKey = key
                    artBmp = src?.let { scale(it) }
                }
                NowPlaying(c, pkg, title ?: app, subtitle(title != null, line, app, playing), artBmp, artKey)
            }.getOrNull()
        }

        private fun label(pkg: String) = labels.getOrPut(pkg) {
            runCatching { ctx.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)
        }

        /** Square from the middle, at [artPx]: a 320 px cover becomes a few KB kept for the capsule. */
        private fun scale(src: Bitmap): Bitmap? = runCatching {
            // A hardware bitmap cannot be cropped on Android 9; a software copy can.
            val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
            val s = minOf(soft.width, soft.height)
            val sq = Bitmap.createBitmap(soft, (soft.width - s) / 2, (soft.height - s) / 2, s, s)
            Bitmap.createScaledBitmap(sq, artPx, artPx, true)
        }.getOrNull()

        private fun MediaMetadata?.text(key: String): String? =
            this?.getText(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** The artwork as a disc. Ignores the colour filter the capsule puts on its glyph. */
    class Disc(bitmap: Bitmap) : Drawable() {
        private val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = this@Disc.shader }
        private val w = bitmap.width.toFloat()
        private val h = bitmap.height.toFloat()
        private val m = Matrix()

        override fun onBoundsChange(b: android.graphics.Rect) {
            m.setScale(b.width() / w, b.height() / h)
            m.postTranslate(b.left.toFloat(), b.top.toFloat())
            shader.setLocalMatrix(m)
        }

        override fun draw(c: Canvas) {
            val b = bounds
            c.drawCircle(b.exactCenterX(), b.exactCenterY(), minOf(b.width(), b.height()) / 2f, paint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        /** The session to show among [states] (Android's order, most recent first): the first playing, else the first paused. */
        fun pick(states: List<Int?>): Int? =
            states.indexOfFirst { it in MediaSessions.ACTIVE }.takeIf { it >= 0 }
                ?: states.indexOfFirst { it == PlaybackState.STATE_PAUSED }.takeIf { it >= 0 }

        /**
         * The capsule's second line: "▶ Artist" or "‖ Artist". Without a title of its own the app's
         * name is the title, so the line says what the app is doing instead. ‖ rather than ⏸: Inter
         * has no ⏸, and many TVs would draw it with their colour emoji font.
         */
        fun subtitle(hasTitle: Boolean, line: String?, app: String, playing: Boolean): String {
            val mark = if (playing) "▶" else "‖"
            val what = when {
                line != null -> line
                hasTitle -> app
                playing -> "En lecture"
                else -> "En pause"
            }
            return "$mark  $what"
        }
    }
}
