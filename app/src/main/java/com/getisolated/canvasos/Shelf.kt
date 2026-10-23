package com.getisolated.canvasos

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Top Shelf content for the focused dock app. An app's items come from the first of these that has
 * something:
 * 1. its Android TV Watch Next programs ("Continuer à regarder", [Prefs.watchNext]), which Netflix,
 *    YouTube, Prime Video and most Western apps publish to TvProvider for the Android TV home;
 * 2. TMDB's popular titles on that streaming service in France, with the user's own TMDB key
 *    ([ShelfFeeds.SERVICES]);
 * 3. for two Chinese apps, the service's public web API: B站 popular videos, 斗鱼 live rooms.
 * Apps' preview channels (their recommendation rows) are not read: the owner chose to keep the
 * shelf to what the user was watching, and to the trending titles they asked for with a TMDB key.
 *
 * TvProvider shows one app's programs to another only if the reader holds READ_TV_LISTINGS (a
 * runtime permission TvProvider defines; granted over adb, AGENTS.md step 4) or the system's
 * ACCESS_ALL_EPG_DATA. Without it every query answers the reader's own rows: nothing. It also
 * refuses any selection from such a reader, so the package is picked with the provider's
 * `package` URI parameter.
 */
object Shelf {
    data class Item(val title: String, val meta: String, val image: String)

    /** What the shelf shows for one app, and where it comes from ("Netflix · Continuer à regarder"). */
    data class Content(val source: String, val items: List<Item>)

    /** Not in the SDK's Manifest.permission: TvProvider declares it ("dangerous"). */
    const val READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"

    private const val TAG = "Shelf"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    /** Web and TMDB lists in memory. */
    private const val TTL = 15 * 60_000L
    /** TvProvider answers (cheap to ask again, and they change as soon as something is watched), and failures. */
    private const val SHORT_TTL = 2 * 60_000L
    /** TMDB lists on disk: popularity moves slowly, and every list costs two calls on the user's key. */
    private const val TMDB_TTL = 24 * 3600_000L
    /** Art smaller than this would show as a blur once stretched over the whole screen. */
    private const val MIN_WIDE = 480
    private const val MIN_TALL = 360
    private const val MAX_IMAGE = 12L * 1024 * 1024

    private class Web(val name: String, val fetch: () -> List<Item>)

    private val web = mapOf(
        "com.xiaodianshi.tv.yst" to Web("Bilibili · Tendances", ::bilibili),
        "com.douyu.xl.douyutv" to Web("Douyu · En direct", ::douyu),
    )

    /** An answer kept until [until] (elapsed-independent wall time); a null content means "nothing here". */
    private class Cached(val until: Long, val content: Content?)

    /** TvProvider answers, dropped whenever the home screen comes back ([onHomeResumed]). */
    private val tvCache = HashMap<String, Cached>()
    /** TMDB and web answers. */
    private val remoteCache = HashMap<String, Cached>()

    @Volatile private var tvReadable: Boolean? = null
    /** No TvProvider on this TV (some Chinese firmwares), or it refused: never ask again in this process. */
    @Volatile private var tvMissing = false
    @Volatile private var tvLogged = false

    private fun fresh(map: HashMap<String, Cached>, pkg: String): Cached? = synchronized(map) {
        map[pkg]?.takeIf { System.currentTimeMillis() < it.until }
    }

    private fun keep(map: HashMap<String, Cached>, pkg: String, content: Content?, ttl: Long) = synchronized(map) {
        map[pkg] = Cached(System.currentTimeMillis() + ttl, content)
    }

    /**
     * Whether the shelf may have something for [pkg]. Cheap, for every focus change: false only when
     * no source applies or each one already answered "nothing" a moment ago. [load] gives the answer.
     */
    fun has(ctx: Context, pkg: String): Boolean {
        val prefs = Prefs(ctx)
        val mode = prefs.shelfMode
        if (mode == Prefs.SHELF_OFF) return false
        // Watch Next only counts while "Continuer à regarder" is on; TMDB and the web sources only
        // while the Top Shelf setting allows them (Réglages → Top Shelf).
        val watchNext = prefs.watchNext
        val tv = if (watchNext) fresh(tvCache, pkg) else null
        val remote = if (mode == Prefs.SHELF_ALL) fresh(remoteCache, pkg) else null
        if (tv?.content != null || remote?.content != null) return true
        val tvMaybe = watchNext && tv == null && canReadTv(ctx)
        val remoteMaybe = mode == Prefs.SHELF_ALL && remote == null &&
            (pkg in web || (pkg in ShelfFeeds.SERVICES && prefs.tmdbKey.isNotEmpty()))
        return tvMaybe || remoteMaybe
    }

    /** The last answer's source name for [pkg], or "" (see [Content.source]). */
    fun sourceName(pkg: String): String =
        (fresh(tvCache, pkg)?.content ?: fresh(remoteCache, pkg)?.content)?.source.orEmpty()

    /** What the shelf shows for [pkg], or null when no source has anything. Blocking: network and TvProvider. */
    fun load(ctx: Context, pkg: String): Content? {
        val prefs = Prefs(ctx)
        val mode = prefs.shelfMode
        if (mode == Prefs.SHELF_OFF) return null
        if (prefs.watchNext) {
            val local = fresh(tvCache, pkg) ?: Cached(0, tv(ctx, pkg)).also { keep(tvCache, pkg, it.content, SHORT_TTL) }
            local.content?.let { return it }
        }
        if (mode != Prefs.SHELF_ALL) return null
        fresh(remoteCache, pkg)?.let { return it.content }
        val remote = try {
            tmdb(ctx, pkg) ?: web[pkg]?.let { w -> w.fetch().takeIf { it.isNotEmpty() }?.let { Content(w.name, it) } }
        } catch (e: Exception) {
            Log.w(TAG, "$pkg failed", e)
            null
        }
        keep(remoteCache, pkg, remote, if (remote != null) TTL else SHORT_TTL)
        return remote
    }

    /**
     * Back on the home screen: what the user just watched may have changed the Watch Next row, and
     * READ_TV_LISTINGS may have been granted in the meantime.
     */
    fun onHomeResumed() {
        synchronized(tvCache) { tvCache.clear() }
        tvReadable = null
    }

    // -------------------------------------------------------------- TvProvider

    private fun canReadTv(ctx: Context): Boolean {
        if (tvMissing) return false
        tvReadable?.let { return it }
        val ok = ctx.checkSelfPermission(READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED
        if (!ok) logTvOnce("READ_TV_LISTINGS not granted: other apps' Watch Next rows are unavailable")
        tvReadable = ok
        return ok
    }

    private fun logTvOnce(msg: String) {
        if (tvLogged) return
        tvLogged = true
        Log.i(TAG, msg)
    }

    private fun tv(ctx: Context, pkg: String): Content? {
        if (!canReadTv(ctx)) return null
        return try {
            val next = rows(ctx, TvContract.WatchNextPrograms.CONTENT_URI, pkg, WATCH_NEXT_COLUMNS) ?: return null
            ShelfFeeds.watchNext(next).takeIf { it.isNotEmpty() }?.let {
                Content("${appLabel(ctx, pkg)} · ${ShelfFeeds.watchNextLabel(next)}", it)
            }
        } catch (e: SecurityException) {
            tvMissing = true
            logTvOnce("TvProvider refused: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "TvProvider $pkg failed", e)
            null
        }
    }

    /** [base] narrowed to [pkg] with TvProvider's own `package` parameter (TvContract.PARAM_PACKAGE, hidden). */
    private fun query(ctx: Context, base: Uri, pkg: String, columns: Array<String>): Cursor? {
        val uri = base.buildUpon().appendQueryParameter("package", pkg).build()
        val c = try {
            ctx.contentResolver.query(uri, columns, null, null, null)
        } catch (e: IllegalArgumentException) {
            // A firmware's TvProvider without one of these columns: take whatever it has.
            ctx.contentResolver.query(uri, null, null, null, null)
        }
        if (c == null) {
            tvMissing = true
            logTvOnce("no TvProvider on this TV")
        }
        return c
    }

    // Program columns Watch Next shares with preview programs: the same names, read from Watch Next only.
    private val PROGRAM_COLUMNS = arrayOf(
        TvContract.PreviewPrograms.COLUMN_TITLE,
        TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE,
        TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
        TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
        TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
        TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
        TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
        TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
        TvContract.PreviewPrograms.COLUMN_THUMBNAIL_ASPECT_RATIO,
        TvContract.PreviewPrograms.COLUMN_TYPE,
        TvContract.PreviewPrograms.COLUMN_BROWSABLE,
        TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
        TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS,
        TvContract.PreviewPrograms.COLUMN_RELEASE_DATE,
    )
    private val WATCH_NEXT_COLUMNS = PROGRAM_COLUMNS + arrayOf(
        TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
        TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
    )

    private fun rows(ctx: Context, base: Uri, pkg: String, columns: Array<String>): List<ShelfFeeds.TvRow>? =
        query(ctx, base, pkg, columns)?.use { c ->
            val out = ArrayList<ShelfFeeds.TvRow>()
            // A few hundred rows at most; more would only be older entries.
            while (c.moveToNext() && out.size < 400) out += ShelfFeeds.TvRow(
                title = c.str(TvContract.PreviewPrograms.COLUMN_TITLE),
                episodeTitle = c.str(TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE),
                season = c.str(TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER),
                episode = c.str(TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER),
                description = c.str(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION),
                poster = c.str(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI),
                posterRatio = c.long(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, -1).toInt(),
                thumbnail = c.str(TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI),
                thumbnailRatio = c.long(TvContract.PreviewPrograms.COLUMN_THUMBNAIL_ASPECT_RATIO, -1).toInt(),
                type = c.long(TvContract.PreviewPrograms.COLUMN_TYPE, -1).toInt(),
                browsable = c.long(TvContract.PreviewPrograms.COLUMN_BROWSABLE, 1) != 0L,
                positionMs = c.long(TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, 0),
                durationMs = c.long(TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS, 0),
                releaseDate = c.str(TvContract.PreviewPrograms.COLUMN_RELEASE_DATE),
                engagedAt = c.long(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, 0),
                watchNextType = c.long(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, -1).toInt(),
            )
            out
        }

    // Cursor.getInt answers 0 for NULL, which is a real value here (ASPECT_RATIO_16_9, TYPE_MOVIE).
    private fun Cursor.str(col: String): String? =
        getColumnIndex(col).takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }

    private fun Cursor.long(col: String, def: Long): Long =
        getColumnIndex(col).takeIf { it >= 0 && !isNull(it) }?.let { runCatching { getLong(it) }.getOrNull() } ?: def

    private fun appLabel(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: ShelfFeeds.SERVICES[pkg]?.name ?: pkg

    // -------------------------------------------------------------------- TMDB

    private fun tmdbDir(ctx: Context) = File(ctx.cacheDir, "tmdb")

    /** Saves the TMDB key ("" removes it) and forgets every list fetched with the previous one. */
    fun setTmdbKey(ctx: Context, key: String) {
        Prefs(ctx).tmdbKey = key.trim()
        tmdbDir(ctx).deleteRecursively()
        synchronized(remoteCache) { ShelfFeeds.SERVICES.keys.forEach { remoteCache.remove(it) } }
    }

    /** Whether TMDB accepts [key]: true or false, or null when TMDB could not be reached. Blocking. */
    fun checkTmdbKey(key: String): Boolean? = try {
        get(ShelfFeeds.withKey(ShelfFeeds.TMDB_CHECK, key), tmdbHeaders(key))
        true
    } catch (e: HttpError) {
        if (e.code == 401) false else null
    } catch (e: Exception) {
        null
    }

    private fun tmdbHeaders(key: String) =
        if (ShelfFeeds.isBearer(key)) mapOf("Authorization" to "Bearer $key", "Accept" to "application/json")
        else mapOf("Accept" to "application/json")

    private fun tmdb(ctx: Context, pkg: String): Content? {
        val service = ShelfFeeds.SERVICES[pkg] ?: return null
        val key = Prefs(ctx).tmdbKey
        if (key.isEmpty()) return null
        val file = File(tmdbDir(ctx).apply { mkdirs() }, "$pkg.json")
        val stale = if (file.exists()) ShelfFeeds.itemsFromJson(file.readText()) else emptyList()
        var items = stale.takeIf { System.currentTimeMillis() - file.lastModified() < TMDB_TTL }.orEmpty()
        if (items.isEmpty()) {
            fun fetch(kind: String) = try {
                get(ShelfFeeds.withKey(ShelfFeeds.discoverUrl(kind, service.providers), key), tmdbHeaders(key)).decodeToString()
            } catch (e: Exception) {
                // Never the URL: it carries the key.
                Log.w(TAG, "TMDB ${service.name} $kind: ${e.javaClass.simpleName} ${e.message?.replace(key, "…")}")
                null
            }
            items = ShelfFeeds.tmdb(fetch("movie"), fetch("tv"))
            if (items.isNotEmpty()) {
                val tmp = File(file.path + ".part")
                tmp.writeText(ShelfFeeds.itemsToJson(items))
                tmp.renameTo(file)
            } else {
                // Offline, or TMDB down: yesterday's list beats an empty shelf.
                items = stale
            }
        }
        // TMDB's terms ask for its credit, and JustWatch's for the availability data behind with_watch_providers.
        return items.takeIf { it.isNotEmpty() }?.let { Content("${service.name} · Tendances (TMDB, JustWatch)", it) }
    }

    // ------------------------------------------------------------------ pictures

    /**
     * Downloads (or reads from the disk cache) an item's picture, ready to show full screen: web
     * art, or the `content://` / `android.resource://` art an app published to TvProvider.
     */
    fun image(ctx: Context, url: String): Bitmap? {
        val dir = File(ctx.cacheDir, "shelf").apply { mkdirs() }
        val f = File(dir, Integer.toHexString(url.hashCode()) + ".jpg")
        if (!f.exists()) try {
            val scheme = Uri.parse(url).scheme?.lowercase()
            val bytes = when (scheme) {
                "https", "http" -> get(url)
                // Other apps write these rows: never file:// (that would read this app's own files).
                "content", "android.resource" -> ctx.contentResolver.openInputStream(Uri.parse(url))?.use { capped(it) }
                else -> null
            } ?: return null
            val tmp = File(dir, f.name + ".part")
            tmp.writeBytes(bytes)
            tmp.renameTo(f)
            // Keep the cache small: the newest 60 pictures.
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(60)?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "image $url failed: $e")
            return null
        }
        return decode(f)
            ?.let(::trimBars)
            ?.let(::widen)
            ?.let(::darken)
            // Start the GPU upload now, off the frame that first shows it.
            ?.also { it.prepareToDraw() }
    }

    /**
     * Decodes at the smallest power-of-two reduction still about screen sized: art published to
     * TvProvider can be 4K, 33 MB once decoded. Tiny art is refused.
     */
    private fun decode(f: File): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        val w = o.outWidth
        val h = o.outHeight
        if (w <= 0 || h <= 0) return null
        val wide = w >= h
        if (if (wide) w < MIN_WIDE else h < MIN_TALL) return null
        var sample = 1
        if (wide) while (w / (sample * 2) >= 1280) sample *= 2
        else while (h / (sample * 2) >= 720) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    /**
     * Bakes the scrim that keeps the title readable into the picture itself:
     * a full-screen gradient layer on top would cost the TV's GPU three more
     * full-screen passes on every frame of a cross-fade.
     */
    private fun darken(b: Bitmap): Bitmap {
        val out = if (b.isMutable) b else b.copy(Bitmap.Config.ARGB_8888, true).also { b.recycle() }
        ShelfScrim().apply { setBounds(0, 0, out.width, out.height) }.draw(Canvas(out))
        return out
    }

    /**
     * A portrait poster would lose two thirds of itself to the full-screen crop: it is set instead on
     * the right of a wide frame, clear of the title, over a blurred and zoomed copy of itself (as
     * tvOS frames posters). Done once per picture, like the scrim.
     */
    private fun widen(b: Bitmap): Bitmap {
        if (b.width >= b.height * 1.2f) return b
        val w = 1280
        val h = 720
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        // The blur: the middle of the poster shrunk to a few dozen pixels, stretched back with filtering.
        val small = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888)
        val band = b.width * 27 / 48
        val top = ((b.height - band) / 2).coerceAtLeast(0)
        Canvas(small).drawBitmap(b, Rect(0, top, b.width, top + band), Rect(0, 0, 48, 27), p)
        c.drawBitmap(small, null, Rect(0, 0, w, h), p)
        small.recycle()
        c.drawColor(Color.argb(60, 0, 0, 0))
        val ph = h * 0.6f
        val pw = ph * b.width / b.height
        val r = RectF(w * 0.93f - pw, h * 0.07f, w * 0.93f, h * 0.07f + ph)
        c.save()
        c.clipPath(Path().apply { addRoundRect(r, 14f, 14f, Path.Direction.CW) })
        c.drawBitmap(b, null, r, p)
        c.restore()
        b.recycle()
        return out
    }

    /** Live-stream covers are often letterboxed; cut the black bars so the picture fills the screen. */
    private fun trimBars(b: Bitmap): Bitmap {
        fun dark(y: Int): Boolean {
            var max = 0
            for (k in 0 until 32) {
                val c = b.getPixel(k * (b.width - 1) / 31, y)
                max = maxOf(max, (c shr 16) and 255, (c shr 8) and 255, c and 255)
            }
            return max < 28
        }
        var top = 0
        while (top < b.height / 4 && dark(top)) top++
        var bottom = b.height
        while (bottom > b.height * 3 / 4 && dark(bottom - 1)) bottom--
        if (top < 4 && b.height - bottom < 4) return b
        return Bitmap.createBitmap(b, 0, top, b.width, bottom - top)
    }

    // --------------------------------------------------------------------- web

    private fun bilibili(): List<Item> {
        val list = JSONObject(get("https://api.bilibili.com/x/web-interface/popular?ps=12&pn=1").decodeToString())
            .getJSONObject("data").getJSONArray("list")
        return (0 until list.length()).map { list.getJSONObject(it) }.map { v ->
            Item(
                v.optString("title"),
                "${v.getJSONObject("owner").optString("name")} · ${count(v.getJSONObject("stat").optLong("view"))} vues",
                v.optString("pic").replace("http://", "https://") + "@1280w_720h_1c.jpg",
            )
        }
    }

    private fun douyu(): List<Item> {
        val list = JSONObject(get("https://www.douyu.com/japi/weblist/apinc/allpage/6/1").decodeToString())
            .getJSONObject("data").getJSONArray("rl")
        return (0 until list.length()).map { list.getJSONObject(it) }
            .filter { it.optString("rs16").isNotEmpty() }
            .take(12)
            .map { r ->
                Item(
                    r.optString("rn"),
                    "${r.optString("nn")} · ${r.optString("c2name")} · popularité ${count(r.optLong("ol"))}",
                    // "/dy4" is the 1280×720 JPEG rendition of the AVIF cover.
                    r.optString("rs16").substringBeforeLast('/') + "/dy4",
                )
            }
    }

    private fun count(n: Long) = when {
        n >= 1_000_000 -> "%.1f M".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1f k".format(n / 1_000.0)
        else -> n.toString()
    }

    private class HttpError(val code: Int) : IOException("HTTP $code")

    private fun get(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", UA)
        // B站's API answers with an empty body when sent a Referer from outside its pages.
        if ("douyu" in url) c.setRequestProperty("Referer", "https://www.douyu.com/")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            if (c.responseCode != 200) throw HttpError(c.responseCode)
            return c.inputStream.use { capped(it) }
        } finally {
            c.disconnect()
        }
    }

    /** Reads at most [MAX_IMAGE] bytes: a picture link is anyone's to write, in TvProvider. */
    private fun capped(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_IMAGE) throw IOException("larger than ${MAX_IMAGE / 1024 / 1024} MB")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
