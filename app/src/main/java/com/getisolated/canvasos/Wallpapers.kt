package com.getisolated.canvasos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Background pictures, downloaded when they are about to be shown and dropped once old. A refresh
 * (about daily) only reads a source's list of pictures, a few kB of JSON, into a queue; one picture
 * is downloaded each time the home screen or the photo frame moves on ([advance], [next]). The
 * day's dozen used up, they go round the ones already here. Pictures older than [KEEP_DAYS] days
 * and any past [MAX_FILES] are deleted, keeping the newest [MIN_FILES]. Each source has its folder:
 * - [BING]: Bing's daily photos (www.bing.com, French market), the last ~15 days listed.
 * - [ANIME]: the month's most popular anime wallpapers on Wallhaven, SFW only; another Wallhaven
 *   ranking, then 360's anime, when that fails.
 * - [SCENERY], [FRESH], [ART]: picture feeds that answer from Europe without an API key
 *   (Wikimedia Commons, NASA, the Met, the Art Institute of Chicago, Wallhaven, TheCatAPI...),
 *   each read for a set that changes daily; see [WallFeeds]. 360's categories are the last backup.
 * - [MINE]: photos sent from a phone (PhoneServer); never deleted.
 * - [CUSTOM]: links the user added ([Prefs.customWallUrls]). A link may be a
 *   picture, an API that answers with a different picture each time, or a
 *   page / JSON that lists picture addresses.
 */
class Wallpapers(private val ctx: Context, private val source: String = BING) {
    private val dir = File(ctx.filesDir, if (source == BING) "wall" else "wall-$source").apply { mkdirs() }
    private val metaFile = File(dir, "meta.json")
    /**
     * {made, total, taken, items: [{url, caption, name?, id?, page?}], ids, looks}: the day's list.
     * An item with `page` is checked once downloaded ([look]): wide and large enough, no near-copy.
     */
    private val queueFile = File(dir, "queue.json")
    private val showFile = File(dir, "showing")
    private val slotFile = File(dir, "slot")
    private val lock get() = LOCKS.getOrPut(source) { Any() }

    /** Cached files, newest first (Bing files are named by date; others by download time). */
    fun files(): List<File> {
        val all = dir.listFiles { f -> f.name.endsWith(".jpg") } ?: return emptyList()
        return if (source == BING) all.sortedByDescending { it.name } else all.sortedByDescending { it.lastModified() }
    }

    /** The home screen's picture: the one [advance] last moved to, or the newest. */
    fun current(): File? {
        val all = files()
        val name = runCatching { showFile.readText() }.getOrNull()
        return all.firstOrNull { it.name == name } ?: all.firstOrNull()
    }

    /** Blocking. Moves the home screen on once every [minutes] (or now, with [force]); true if its picture changed. */
    fun advance(minutes: Int, force: Boolean = false): Boolean {
        val slot = System.currentTimeMillis() / (minutes.coerceAtLeast(1) * 60_000L)
        if (!force && runCatching { slotFile.readText().toLong() }.getOrNull() == slot) return false
        val before = current()
        val f = next(before) ?: return false
        slotFile.writeText(slot.toString())
        showFile.writeText(f.name)
        return f != before
    }

    /** Blocking. The picture to show after [after]: a new one while the day's list lasts, else the next one here. */
    fun next(after: File?): File? {
        download()?.let { return it }
        val all = files()
        if (all.isEmpty()) return null
        return all[(all.indexOf(after) + 1) % all.size]
    }

    /** "Title — place (© photographer)" for the photo frame caption. */
    fun caption(f: File): String = try {
        JSONObject(metaFile.readText()).optString(f.name)
    } catch (e: Exception) { "" }

    /**
     * Blocking; reads the source's list again if it is older than ~a day, and downloads one picture
     * if there is none yet, so a newly picked source shows something straight away.
     */
    fun refresh(force: Boolean = false) {
        if (source == MINE) return
        // One at a time per source: the home screen, the frame and the phone page may all ask.
        synchronized(lock) {
            val q = readQueue()
            if (force || q == null || System.currentTimeMillis() - q.optLong("made") > STALE) {
                runCatching { queueFile.writeText(list().toString()) }.onFailure { Log.w("Wallpapers", "list failed", it) }
            }
        }
        if (files().isEmpty()) download()
    }

    private fun readQueue(): JSONObject? = runCatching { JSONObject(queueFile.readText()) }.getOrNull()

    private fun list(): JSONObject {
        val now = System.currentTimeMillis()
        val day = (now / 86_400_000L).toInt()
        val items = JSONArray()
        when (source) {
            BING -> listBing(items)
            ANIME -> if (runCatching { listAnime(items, day) }.onFailure { Log.w("Wallpapers", "wallhaven", it) }.isFailure ||
                items.length() == 0) listFeeds(WallFeeds.feeds(ANIME_BACKUP, day), items, day)
            CUSTOM -> listLinks(Prefs(ctx).customWallUrls.lines(), items)
            else -> listFeeds(WallFeeds.feeds(source, day), items, day)
        }
        // Nothing listed (offline, or every feed down): try again in half an hour, not tomorrow.
        val made = if (items.length() == 0 && source != BING && source != CUSTOM) now - STALE + RETRY else now
        return JSONObject().put("made", made).put("taken", 0).put("items", items)
            .put("total", if (source == CUSTOM) Int.MAX_VALUE else PER_DAY)
    }

    private fun item(url: String, caption: String) = JSONObject().put("url", url).put("caption", caption)

    private fun listBing(items: JSONArray) {
        for (idx in listOf(0, 7)) {
            val json = get("https://www.bing.com/HPImageArchive.aspx?format=js&idx=$idx&n=8&mkt=fr-FR").decodeToString()
            val imgs = JSONObject(json).getJSONArray("images")
            for (i in 0 until imgs.length()) {
                val o = imgs.getJSONObject(i)
                val title = o.optString("title")
                val copy = o.optString("copyright")
                items.put(item("https://www.bing.com" + o.getString("urlbase") + "_1920x1080.jpg",
                    if (title.isNotEmpty() && !copy.startsWith(title)) "$title — $copy" else copy)
                    .put("name", o.getString("startdate") + ".jpg"))
            }
        }
    }

    private fun listAnime(items: JSONArray, day: Int) {
        // A different page of the month's ranking each day keeps the set changing.
        val json = get("https://wallhaven.cc/api/v1/search?categories=010&purity=100&sorting=toplist" +
            "&topRange=1M&atleast=1920x1080&ratios=16x9&page=${1 + day % 5}").decodeToString()
        WallFeeds.wallhaven(json).forEach { items.put(item(it.url, it.caption).put("name", it.id + ".jpg")) }
    }

    /**
     * A link that answers with a picture is listed [PER_LINK] times (random-picture APIs give a new
     * one each time). Anything else is searched for picture addresses, JSON included. Feeds list each
     * picture at several sizes (Wallhaven's thumbnails, 360's seven resolutions); those share an id,
     * and small ones and near-copies of one already taken are skipped when downloading ([take]).
     */
    private fun listLinks(lines: List<String>, items: JSONArray) {
        val links = lines.map { it.trim() }.filter { it.startsWith("http://") || it.startsWith("https://") }
        for (link in links) linkItems(link).forEach { items.put(it) }
    }

    /** The items of one link read as [listLinks] does; none if it can't be read. */
    private fun linkItems(link: String): List<JSONObject> {
        val host = runCatching { URL(link).host }.getOrDefault(link)
        val caption = CAPTIONS.entries.firstOrNull { host.endsWith(it.key) }?.value ?: host
        val first = runCatching { fetch(link) }.getOrNull() ?: return emptyList()
        if (first.second?.startsWith("image/") == true || looksLikeImage(first.first)) {
            return List(PER_LINK) { item(link, caption) }
        }
        val text = first.first.decodeToString().replace("\\/", "/")
        return WallFeeds.IMAGE_URL.findAll(text).map { it.value }.distinct().take(PER_DAY * 4).map { u ->
            item(u, caption).put("page", true).apply { WallFeeds.pictureId(u)?.let { put("id", it) } }
        }.toList()
    }

    /**
     * A feed source's day: its links ([WallFeeds.feeds]) are read in order, each on its own, so one
     * that is down, blocked or lists nothing only hands over to the next. The first two that give
     * pictures are taken in turn, so a day mixes them; later ones are read only while the list is
     * still short (feeds whose pictures are mostly too small), and are queued after them.
     */
    private fun listFeeds(links: List<String>, items: JSONArray, day: Int) {
        val lists = ArrayList<List<JSONObject>>()
        for (link in links) {
            if (lists.size >= 2 && lists.sumOf { it.size } >= PER_DAY * 3 / 2) break
            val got = runCatching { readFeed(link, day) }.onFailure { Log.w("Wallpapers", "feed failed: $link", it) }.getOrNull()
            if (!got.isNullOrEmpty()) lists += got
        }
        WallFeeds.interleave(lists).forEach { items.put(it) }
    }

    /**
     * One feed's items. An API's pictures are named by their id, so one already here isn't fetched
     * again on a later day, and are checked once downloaded (`page`, see [take]). Its feed counts only
     * if one of its first pictures can actually be fetched: some image servers answer an app with a
     * bot check (the Art Institute's, from Europe), which a good list would hide until download time.
     */
    private fun readFeed(link: String, day: Int): List<JSONObject> {
        val pics = when {
            WallFeeds.isMet(link) -> listMet(link, day)
            WallFeeds.isApod(link) -> listApod(link)
            else -> {
                val read = WallFeeds.reader(link) ?: return linkItems(link)
                read(get(link).decodeToString())
            }
        }
        if (pics.take(2).none { reachable(it.url) }) return emptyList()
        return pics.map { item(it.url, it.caption).put("id", it.id).put("name", it.id + ".jpg").put("page", true) }
    }

    /**
     * The Met's search gives object ids only: each object is read for its picture and credits, a
     * day's worth at most, and for half a minute at most (the source's lock is held meanwhile).
     */
    private fun listMet(link: String, day: Int): List<FeedPic> {
        val out = ArrayList<FeedPic>()
        val until = System.currentTimeMillis() + 30_000
        for (id in WallFeeds.metPick(WallFeeds.metIds(get(link).decodeToString()), day, MET_READS)) {
            if (out.size >= PER_DAY || System.currentTimeMillis() > until) break
            // Withdrawn objects answer 404; upright and copyrighted ones give null.
            runCatching { WallFeeds.metObject(get(WallFeeds.metObjectUrl(id)).decodeToString()) }.getOrNull()?.let { out += it }
        }
        return out
    }

    /** APOD's day, with each picture's credit when NASA's media list answers (the pictures are listed without). */
    private fun listApod(link: String): List<FeedPic> {
        val json = get(link).decodeToString()
        val credits = WallFeeds.apodCreditsUrl(json)?.let { url ->
            runCatching { WallFeeds.apodCredits(get(url).decodeToString()) }.onFailure { Log.w("Wallpapers", "apod credits", it) }.getOrNull()
        }
        return WallFeeds.apod(json, credits.orEmpty())
    }

    /** True if [url] answers with something other than a page (a bot check is one); only the first kB is asked for. */
    private fun reachable(url: String) =
        runCatching { fetch(url, probe = true) }.map { it.second?.startsWith("text/") != true }.getOrDefault(false)

    /** Blocking. Downloads the next picture of the day's list; null once it is used up or none works. */
    private fun download(): File? = synchronized(lock) {
        val q = readQueue() ?: return null
        val items = q.optJSONArray("items") ?: return null
        val meta = runCatching { JSONObject(metaFile.readText()) }.getOrElse { JSONObject() }
        var got: File? = null
        var tries = 0
        while (got == null && items.length() > 0 && q.optInt("taken") < q.optInt("total") && tries++ < 16) {
            val o = items.remove(0) as JSONObject
            got = runCatching { take(o, q, meta) }.onFailure { Log.w("Wallpapers", "download failed", it) }.getOrNull()
        }
        if (got != null) {
            q.put("taken", q.optInt("taken") + 1)
            prune()
            val keep = files().map { it.name }.toSet()
            meta.keys().asSequence().toList().forEach { if (it !in keep) meta.remove(it) }
            metaFile.writeText(meta.toString())
        }
        queueFile.writeText(q.toString())
        got
    }

    private fun take(o: JSONObject, q: JSONObject, meta: JSONObject): File? {
        val ids = q.optJSONArray("ids") ?: JSONArray().also { q.put("ids", it) }
        val id = o.optString("id").ifEmpty { null }
        if (id != null && (0 until ids.length()).any { ids.getString(it) == id }) return null
        val given = o.optString("name").ifEmpty { null }
        if (given != null && File(dir, given).exists()) return null
        val bytes = fetch(o.getString("url")).first
        if (o.optBoolean("page")) {
            val look = look(bytes, minWidth = 1280) ?: return null
            val looks = q.optJSONArray("looks") ?: JSONArray().also { q.put("looks", it) }
            if ((0 until looks.length()).any { java.lang.Long.bitCount(looks.getLong(it) xor look) <= 6 }) return null
            looks.put(look)
        }
        id?.let { ids.put(it) }
        val name = given ?: ("u-" + hash(bytes) + ".jpg")
        if (File(dir, name).exists() || !write(bytes, name)) return null
        meta.put(name, o.optString("caption"))
        return File(dir, name)
    }

    /** Old pictures go: past [KEEP_DAYS] days (keeping the newest [MIN_FILES]) or past [MAX_FILES]; never the one on screen. */
    private fun prune() {
        val showing = runCatching { showFile.readText() }.getOrNull()
        val old = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        files().forEachIndexed { i, f ->
            if (f.name != showing && (i >= MAX_FILES || (i >= MIN_FILES && f.lastModified() < old))) f.delete()
        }
    }

    /** A photo sent from a phone, for [MINE]. Returns its file name, or null if it isn't a picture. */
    fun add(bytes: ByteArray): String? {
        val name = "my-" + hash(bytes) + ".jpg"
        return if (File(dir, name).exists() || write(bytes, name)) name else null
    }

    private fun write(bytes: ByteArray, name: String): Boolean {
        val bmp = fitScreen(bytes) ?: return false
        val tmp = File(dir, "$name.part")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        return tmp.renameTo(File(dir, name))
    }

    fun delete(name: String) {
        if (name.contains('/') || !name.endsWith(".jpg")) return
        File(dir, name).delete()
    }

    private fun hash(b: ByteArray) =
        java.security.MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }.take(16)

    private fun looksLikeImage(b: ByteArray) = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        .also { BitmapFactory.decodeByteArray(b, 0, b.size, it) }.outWidth > 0

    /**
     * GET with the content type, following redirects across http and https (HttpURLConnection won't).
     * A [probe] asks for the first kB only and returns no bytes, just whether and what it answered.
     */
    private fun fetch(url: String, probe: Boolean = false): Pair<ByteArray, String?> {
        var u = url
        repeat(5) {
            val c = open(u)
            c.instanceFollowRedirects = false
            if (probe) c.setRequestProperty("Range", "bytes=0-1023")
            val code = c.responseCode
            if (code in 300..399) {
                u = URL(URL(u), c.getHeaderField("Location") ?: error("redirect without Location")).toString()
                c.disconnect()
                return@repeat
            }
            if (code !in 200..299) error("HTTP $code")
            if (probe) return ByteArray(0) to c.contentType.also { c.disconnect() }
            if (c.contentLengthLong > MAX_DOWNLOAD) error("too large")
            val bytes = c.inputStream.use { it.readBytes() }
            return bytes to c.contentType
        }
        error("too many redirects")
    }

    private fun open(url: String): HttpURLConnection {
        val u = URL(url)
        val c = u.openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", WallFeeds.agent(u.host))
        // The Art Institute asks API clients to name themselves in a header of its own.
        if (u.host.endsWith("artic.edu")) c.setRequestProperty("AIC-User-Agent", WallFeeds.AGENT)
        return c
    }

    /**
     * A 64-bit fingerprint of a picture (each cell of an 8x8 grey thumbnail brighter or darker than
     * average), the same for one picture at any size; null if it is no picture, narrower than [minWidth]
     * or upright (a portrait cropped to the screen loses most of it).
     */
    private fun look(bytes: ByteArray, minWidth: Int): Long? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < minWidth || bounds.outWidth * 5 < bounds.outHeight * 6) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 64) sample *= 2
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        // Of the middle 16:9, the part that is kept: feeds also offer the picture cropped to 16:10 or 4:3.
        val w = minOf(b.width, b.height * 16 / 9)
        val h = minOf(b.height, b.width * 9 / 16)
        val t = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        Canvas(t).drawBitmap(b, Rect((b.width - w) / 2, (b.height - h) / 2, (b.width + w) / 2, (b.height + h) / 2),
            Rect(0, 0, 8, 8), Paint(Paint.FILTER_BITMAP_FLAG))
        val grey = IntArray(64) { i -> t.getPixel(i % 8, i / 8).let { Color.red(it) * 3 + Color.green(it) * 6 + Color.blue(it) } }
        t.recycle()
        b.recycle()
        val avg = grey.average()
        var bits = 0L
        for (i in 0 until 64) if (grey[i] > avg) bits = bits or (1L shl i)
        return bits
    }

    /** Decodes a large picture straight down to 1920x1080, cropping to 16:9 rather than stretching. */
    private fun fitScreen(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1920 && bounds.outHeight / (sample * 2) >= 1080) sample *= 2
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        if (b.width == 1920 && b.height == 1080) return b
        // The middle 16:9 of it: portrait paintings and phone-shaped art keep their proportions.
        val w = minOf(b.width, b.height * 16 / 9)
        val h = minOf(b.height, b.width * 9 / 16)
        val src = Rect((b.width - w) / 2, (b.height - h) / 2, (b.width + w) / 2, (b.height + h) / 2)
        val out = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(b, src, Rect(0, 0, 1920, 1080), Paint(Paint.FILTER_BITMAP_FLAG))
        b.recycle()
        return out
    }

    /** GET of a small API answer; throws on an error status (a rate limit's 429 included). */
    private fun get(url: String): ByteArray = open(url).inputStream.use { it.readBytes() }

    fun decode(f: File, full: Boolean = false): Bitmap? = try {
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
            inPreferredConfig = if (full) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
            inMutable = true
        })
    } catch (e: OutOfMemoryError) { null }

    /** A photo dressed for the home screen: darker at the bottom and top-left so tiles and clock stay legible. */
    fun forHome(f: File): Bitmap? = decode(f)?.also { b ->
        val c = Canvas(b)
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        val p = Paint()
        p.shader = LinearGradient(0f, h * 0.35f, 0f, h, Color.TRANSPARENT, Color.argb(215, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = android.graphics.RadialGradient(w * 0.12f, h * 0.38f, w * 0.45f,
            Color.argb(90, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
    }

    companion object {
        const val MAX_FILES = 16
        private const val MIN_FILES = 4
        private const val KEEP_DAYS = 3
        /** New pictures a day from a source with a list; random-picture links give [PER_LINK] each. */
        private const val PER_DAY = 12
        const val BING = "daily"
        const val ANIME = "anime"
        const val MINE = "mine"
        const val CUSTOM = "custom"
        const val SCENERY = "scenery"
        const val FRESH = "fresh"
        const val ART = "art"
        /** The anime source's backup feeds, when Wallhaven's monthly ranking can't be read. */
        internal const val ANIME_BACKUP = "anime-backup"
        val SOURCES = listOf(BING, SCENERY, FRESH, ANIME, ART, MINE, CUSTOM)

        /** Hosts to the captions the photo frame shows under their pictures (API feeds write their own). */
        private val CAPTIONS = mapOf("360.cn" to "Fonds d'écran 360", "wallhaven.cc" to "Wallhaven", "alcy.cc" to "alcy.cc",
            "clevelandart.org" to "Cleveland Museum of Art · Domaine public", "picsum.photos" to "Lorem Picsum · Unsplash",
            "wikimedia.org" to "Wikimedia Commons", "apod.nasa.gov" to "NASA APOD", "api.nasa.gov" to "NASA APOD",
            "metmuseum.org" to "The Met · Domaine public", "artic.edu" to "Art Institute of Chicago · Domaine public",
            "dog.ceo" to "Dog CEO", "thecatapi.com" to "TheCatAPI")

        /** A day's list is read again after this long; an empty one after [RETRY]. */
        private const val STALE = 20 * 3_600_000L
        private const val RETRY = 30 * 60_000L
        /** The Met objects read at most for a day's list (each is one small request). */
        private const val MET_READS = 24

        /**
         * Keeps one picture (the gallery's preview) of each downloaded source that is not [keep], so
         * trying sources out doesn't pile up files. Photos and custom links are left alone.
         */
        fun trimExcept(ctx: Context, keep: String) {
            for (s in SOURCES) if (s != keep && s != MINE && s != CUSTOM) Wallpapers(ctx, s).files().drop(1).forEach { it.delete() }
        }
        private val LOCKS = java.util.concurrent.ConcurrentHashMap<String, Any>()
        private const val PER_LINK = 6
        private const val MAX_DOWNLOAD = 15L * 1024 * 1024

        fun label(source: String) = when (source) {
            ANIME -> "Anime"
            SCENERY -> "Paysages"
            FRESH -> "Animaux mignons"
            ART -> "Chefs-d'œuvre"
            MINE -> "Mes photos"
            CUSTOM -> "Lien personnalisé"
            else -> "Photos du jour"
        }

        /** The picture source behind a [Prefs.background] value; gradient themes fall back to Bing. */
        fun sourceFor(background: String) = if (background in SOURCES) background else BING
        fun isPhoto(background: String) = background in SOURCES

        /** Cheap, smooth blur: shrink, box-blur three times, let the view scale it back up. */
        fun blurred(src: Bitmap, darken: Float = 0.45f): Bitmap {
            val w = 96
            val h = (w * src.height / src.width.toFloat()).toInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            repeat(3) { boxBlur(px, w, h, 4) }
            for (i in px.indices) {
                val c = px[i]
                px[i] = Color.rgb(
                    (Color.red(c) * (1 - darken)).toInt(),
                    (Color.green(c) * (1 - darken)).toInt(),
                    (Color.blue(c) * (1 - darken)).toInt(),
                )
            }
            small.setPixels(px, 0, w, 0, 0, w, h)
            return small
        }

        /** Dark vertical gradient used before any wallpaper is cached. */
        fun fallback(): Bitmap {
            val b = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
            Paint().apply {
                shader = LinearGradient(0f, 0f, 32f, 18f, Color.rgb(38, 44, 66), Color.rgb(10, 10, 14), Shader.TileMode.CLAMP)
                Canvas(b).drawRect(0f, 0f, 32f, 18f, this)
            }
            return b
        }

        private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
            val tmp = IntArray(px.size)
            pass(px, tmp, w, h, r, true)
            pass(tmp, px, w, h, r, false)
        }

        private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val len = if (horizontal) w else h
            val lines = if (horizontal) h else w
            for (line in 0 until lines) {
                for (i in 0 until len) {
                    var rs = 0; var gs = 0; var bs = 0; var n = 0
                    for (k in -r..r) {
                        val j = (i + k).coerceIn(0, len - 1)
                        val c = if (horizontal) src[line * w + j] else src[j * w + line]
                        rs += (c shr 16) and 0xff; gs += (c shr 8) and 0xff; bs += c and 0xff; n++
                    }
                    val out = (0xff shl 24) or ((rs / n) shl 16) or ((gs / n) shl 8) or (bs / n)
                    if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
                }
            }
        }
    }
}

/** A picture a feed lists: its address, the photo frame's caption, and an id that stays the same from day to day. */
data class FeedPic(val url: String, val caption: String, val id: String)

/**
 * The picture feeds of [Wallpapers.SCENERY], [Wallpapers.FRESH], [Wallpapers.ART] and the anime
 * backup: which links to read on a given day, and each API's answer read into [FeedPic]s. All of them
 * answer from Europe without an API key. Pure (no Android, no network), so it is unit-tested;
 * [Wallpapers] does the fetching. Where an API tells a picture's size, small, upright and panoramic
 * pictures are left out here, before any download; the others are checked once downloaded.
 */
object WallFeeds {
    /** How the app names itself to the sites that ask API clients to (Wikimedia, the Art Institute). */
    const val AGENT = "canvasOS/1.0 (Android TV launcher; https://canvasos.pages.dev)"
    private const val BROWSER = "Mozilla/5.0 (Linux; Android 12) canvasOS"

    /** The User-Agent for [host]; every other site keeps getting the one it always had. */
    fun agent(host: String) = if (host.endsWith("wikimedia.org") || host.endsWith("artic.edu")) AGENT else BROWSER

    /**
     * A source's links for [day], in the order they are tried ([Wallpapers] mixes the first two that
     * work): the two main feeds, which one leads changing daily, then backups; 360's always last, as
     * it is slow or unreachable from Europe.
     */
    fun feeds(source: String, day: Int): List<String> {
        fun q360(cid: Int) = "http://wallpaper.apc.360.cn/index.php?c=WallPaper&a=getAppsByCategory&cid=$cid" +
            "&start=${day % 20 * 12}&count=12"
        return when (source) {
            Wallpapers.SCENERY -> listOf(potdUrl(day), apodUrl(day)).rotate(day) + APOD_API + PICSUM +
                listOf(q360(9), q360(36)).rotate(day)
            Wallpapers.FRESH -> listOf(animalsUrl(day), CATS).rotate(day) + DOGS + listOf(q360(15), q360(14)).rotate(day)
            Wallpapers.ART -> listOf(metUrl(day), articUrl(day)).rotate(day) +
                ("https://openaccess-api.clevelandart.org/api/artworks/?q=landscape&type=Painting&cc0=1&has_image=1" +
                    "&limit=30&skip=${day % 10 * 30}") +
                ("https://wallhaven.cc/api/v1/search?q=painting&categories=100&purity=100&atleast=1920x1080" +
                    "&ratios=16x9&sorting=toplist&page=${1 + day % 5}")
            Wallpapers.ANIME_BACKUP -> listOf(animeUrl(day), q360(26))
            else -> emptyList()
        }
    }

    private fun List<String>.rotate(day: Int) = if (isEmpty()) this else drop(day % size) + take(day % size)

    /**
     * How to read a feed link's answer; null for a plain link (a picture, or a page searched for
     * picture addresses) and for the two feeds that take two steps, the Met ([isMet]) and APOD ([isApod]).
     */
    fun reader(link: String): ((String) -> List<FeedPic>)? {
        val u = runCatching { URL(link) }.getOrNull() ?: return null
        return when {
            u.host == "commons.wikimedia.org" -> ::potd
            u.host == "api.nasa.gov" -> ::apodApi
            u.host == "api.artic.edu" -> ::artic
            u.host == "wallhaven.cc" && u.path.startsWith("/api/") -> ::wallhaven
            u.host == "dog.ceo" -> ::dogs
            u.host == "api.thecatapi.com" -> ::cats
            else -> null
        }
    }

    // Wikimedia Commons

    /**
     * Commons' pictures of the day: the last week's, and today's date in each of the 20 years before,
     * so most of the set is new every day.
     */
    fun potdUrl(day: Int): String {
        val today = LocalDate.ofEpochDay(day.toLong())
        val dates = (0L until 7L).map { today.minusDays(it) } + (1L..20L).map { today.minusYears(it) }
        return "https://commons.wikimedia.org/w/api.php?action=query&format=json&formatversion=2" +
            "&generator=images&gimlimit=max&prop=imageinfo&iiprop=url%7Csize%7Cmime%7Cextmetadata&iiurlwidth=1920" +
            "&iiextmetadatalanguage=fr&iiextmetadatafilter=ObjectName%7CImageDescription%7CArtist%7CLicenseShortName" +
            "&titles=" + dates.joinToString("%7C") { "Template:Potd/$it" }
    }

    /** Commons' answer: each picture as a 1920 px wide copy, with its description (French when there is one), author and licence. */
    fun potd(json: String): List<FeedPic> {
        val query = JSONObject(json).optJSONObject("query") ?: return emptyList()
        // formatversion=2 lists pages in an array, the older format in an object keyed by page id.
        val pages = query.optJSONArray("pages")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
            ?: query.optJSONObject("pages")?.let { o -> o.keys().asSequence().mapNotNull { o.optJSONObject(it) }.toList() }
            ?: return emptyList()
        val out = ArrayList<FeedPic>()
        for (page in pages) {
            val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            // Maps (SVG), animations and videos are pictures of the day too.
            if (info.text("mime") !in PHOTOS || !fits(info.optInt("width"), info.optInt("height"), 1600)) continue
            val url = info.text("thumburl").ifEmpty { info.text("url") }
            if (url.isEmpty()) continue
            val meta = info.optJSONObject("extmetadata")
            fun field(key: String) = plain(meta?.optJSONObject(key)?.text("value").orEmpty())
            val title = page.text("title")
            val what = field("ImageDescription").replace(LABEL, "").ifEmpty { field("ObjectName") }
                .ifEmpty { title.substringAfter(':').substringBeforeLast('.') }
            val license = field("LicenseShortName").let { if (it.equals("Public domain", ignoreCase = true)) "Domaine public" else it }
            // The photo frame shows two lines; the author and licence must stay in them (CC BY-SA asks for both).
            val work = listOf(shorten(what, 64), shorten(field("Artist"), 30)).filter { it.isNotEmpty() }.joinToString(" — ")
            out += FeedPic(url, listOf(work, "Wikimedia Commons", license).filter { it.isNotEmpty() }.joinToString(" · "),
                "wm-" + md5(title).take(12))
        }
        return out
    }

    // NASA

    /**
     * NASA's Astronomy Picture of the Day, which moved to science.nasa.gov in 2026: a page of its
     * archive (the last twenty years or so) that changes daily.
     */
    fun apodUrl(day: Int) = "https://science.nasa.gov/wp-json/wp/v2/image-article?categories=22766&per_page=20" +
        "&page=${1 + day * 37 % 400}&_fields=id,date,title,featured_image_url,featured_media"

    fun isApod(link: String) = link.startsWith("https://science.nasa.gov/wp-json/")

    /** The second, optional step: the pictures' credits, which only their media entries carry. */
    fun apodCreditsUrl(json: String): String? {
        val list = JSONArray(json)
        val ids = (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optInt("featured_media") }.filter { it > 0 }
        if (ids.isEmpty()) return null
        return "https://science.nasa.gov/wp-json/wp/v2/media?include=${ids.joinToString(",")}&per_page=${ids.size}" +
            "&_fields=id,nasa_hds_core_meta_image_credit"
    }

    /** Media id to credit ("Aldo Zanetti"; older ones are HTML with an "Image Credit:" label). */
    fun apodCredits(json: String): Map<Int, String> {
        val list = JSONArray(json)
        val out = HashMap<Int, String>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val credit = plain(o.text("nasa_hds_core_meta_image_credit")).replace(CREDIT_LABEL, "").replace(" ,", ",")
            if (credit.isNotEmpty()) out[o.optInt("id")] = shorten(credit, 40)
        }
        return out
    }

    /**
     * science.nasa.gov's answer. A picture's full size is in its address (w, h), so small and upright
     * ones are skipped; the others are asked for at 1920x1080 at most from NASA's resizing server.
     */
    fun apod(json: String, credits: Map<Int, String> = emptyMap()): List<FeedPic> {
        val list = JSONArray(json)
        val out = ArrayList<FeedPic>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val title = plain(o.optJSONObject("title")?.text("rendered").orEmpty())
            val image = o.text("featured_image_url")
            if (!title.startsWith("APOD") || image.isEmpty()) continue
            val w = param(image, "w")
            val h = param(image, "h")
            if (w != null && h != null && !fits(w, h, 1440)) continue
            val base = image.substringBefore('?').replace("/content/dam/science/", "/dynamicimage/assets/science/")
            val date = o.text("date").take(10).ifEmpty { o.text("id") }
            val by = credits[o.optInt("featured_media")]
            out += FeedPic("$base?w=1920&h=1080&fit=clip",
                shorten(title.replace(APOD_DATE, "").ifEmpty { title }, 80) + (if (by == null) " — NASA APOD" else " — $by · NASA APOD"),
                "apod-$date")
        }
        return out
    }

    /**
     * NASA's APOD API, a backup only: since APOD moved, it answers every day with NASA's logo, which is
     * skipped. Its DEMO_KEY allows a few calls an hour per address; a refusal (429) fails this feed alone.
     */
    const val APOD_API = "https://api.nasa.gov/planetary/apod?api_key=DEMO_KEY&count=12&thumbs=true"

    fun apodApi(json: String): List<FeedPic> {
        val list = JSONArray(json)
        val out = ArrayList<FeedPic>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val url = o.text("hdurl").ifEmpty { o.text("url") }
            if (o.text("media_type") != "image" || url.isEmpty() || "nasa-logo" in url) continue
            val by = plain(o.text("copyright"))
            val title = shorten(plain(o.text("title")), 90)
            out += FeedPic(url, if (by.isEmpty()) "$title — NASA APOD" else "$title — © ${shorten(by, 40)} · NASA APOD",
                "apod-" + o.text("date"))
        }
        return out
    }

    // The Metropolitan Museum of Art

    /** One search a day: (department, words); 11 is European Paintings, 1 the American Wing. */
    private val MET_SEARCHES = listOf(11 to "landscape", 1 to "landscape", 11 to "river", 11 to "mountains",
        1 to "river", 11 to "forest", 11 to "sea", 11 to "garden")

    /** The Met's open access search (v1.1: the v1 search was retired in October 2026); ids only. */
    fun metUrl(day: Int): String {
        val (department, words) = MET_SEARCHES[day % MET_SEARCHES.size]
        return "https://collectionapi.metmuseum.org/public/collection/v1.1/search?hasImages=true&isPublicDomain=true" +
            "&departmentId=$department&q=$words&limit=200"
    }

    fun isMet(link: String) = link.startsWith("https://collectionapi.metmuseum.org/")

    fun metObjectUrl(id: Int) = "https://collectionapi.metmuseum.org/public/collection/v1/objects/$id"

    fun metIds(json: String): List<Int> {
        val ids = JSONObject(json).optJSONArray("objectIDs") ?: return emptyList()
        return (0 until ids.length()).map { ids.optInt(it) }.filter { it > 0 }
    }

    /** The [n] ids to read on [day]: a stretch of the results that moves on each time this search comes round. */
    fun metPick(ids: List<Int>, day: Int, n: Int): List<Int> {
        if (ids.isEmpty()) return emptyList()
        val start = day / MET_SEARCHES.size * n % ids.size
        return (0 until minOf(n, ids.size)).map { ids[(start + it) % ids.size] }
    }

    /** One object, or null if it isn't public domain (the search doesn't filter on it), has no picture, or is upright. */
    fun metObject(json: String): FeedPic? {
        val o = JSONObject(json)
        val url = o.text("primaryImage")
        if (!o.optBoolean("isPublicDomain") || url.isEmpty()) return null
        // No pixel size is given, but the work's is: a painting's photo has the canvas's proportions.
        val sizes = o.optJSONArray("measurements")
        val all = (0 until (sizes?.length() ?: 0)).mapNotNull { sizes?.optJSONObject(it) }
        val m = (all.firstOrNull { it.text("elementName") == "Overall" } ?: all.firstOrNull())?.optJSONObject("elementMeasurements")
        val w = m?.optDouble("Width") ?: Double.NaN
        val h = m?.optDouble("Height") ?: Double.NaN
        if (w > 0 && h > 0 && (w < h * 1.25 || w > h * 2.4)) return null
        return FeedPic(url, credit(o.text("title"), o.text("artistDisplayName"), o.text("objectDate"), "The Met"),
            "met-" + o.optInt("objectID"))
    }

    // The Art Institute of Chicago

    private val ARTIC_SEARCHES = listOf("landscape", "seascape", "river", "mountains", "garden", "snow", "sunset", "impressionism")

    fun articUrl(day: Int): String {
        val words = ARTIC_SEARCHES[day % ARTIC_SEARCHES.size]
        return "https://api.artic.edu/api/v1/artworks/search?q=$words&query%5Bterm%5D%5Bis_public_domain%5D=true" +
            "&fields=id,title,artist_title,artist_display,date_display,image_id,thumbnail,is_public_domain" +
            "&limit=30&page=${1 + day / ARTIC_SEARCHES.size % 5}"
    }

    /** The Institute's answer; its pictures come through IIIF at 1686 px wide, the most it serves. */
    fun artic(json: String): List<FeedPic> {
        val root = JSONObject(json)
        val iiif = root.optJSONObject("config")?.text("iiif_url").orEmpty().ifEmpty { "https://www.artic.edu/iiif/2" }
        val data = root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<FeedPic>()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val image = o.text("image_id")
            if (image.isEmpty() || !o.optBoolean("is_public_domain", true)) continue
            // "thumbnail" describes the full picture: its size, not a small copy's.
            val w = o.optJSONObject("thumbnail")?.optInt("width") ?: 0
            val h = o.optJSONObject("thumbnail")?.optInt("height") ?: 0
            if (w > 0 && h > 0 && !fits(w, h, 1686)) continue
            val artist = o.text("artist_title").ifEmpty { o.text("artist_display").lineSequence().first().substringBefore(" (") }
            out += FeedPic("$iiif/$image/full/1686,/0/default.jpg",
                credit(o.text("title"), artist, o.text("date_display"), "Art Institute of Chicago"), "aic-" + o.optInt("id"))
        }
        return out
    }

    // Wallhaven, TheCatAPI, Dog CEO

    /** Cute animals: Wallhaven's most favourited for a word that changes daily, SFW and 16:9 only. */
    private val ANIMALS = listOf("kittens", "puppies", "cute animals", "fox", "bunny", "owl", "cat", "dog")

    fun animalsUrl(day: Int) = "https://wallhaven.cc/api/v1/search?q=" + URLEncoder.encode(ANIMALS[day % ANIMALS.size], "UTF-8") +
        "&categories=100&purity=100&ratios=16x9&atleast=1920x1080&sorting=favorites&page=${1 + day / ANIMALS.size % 5}"

    /** The anime backup: Wallhaven's all-time favourites rather than the month's ranking. */
    fun animeUrl(day: Int) = "https://wallhaven.cc/api/v1/search?categories=010&purity=100&ratios=16x9&atleast=1920x1080" +
        "&sorting=favorites&page=${1 + day % 20}"

    fun wallhaven(json: String): List<FeedPic> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<FeedPic>()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val id = o.text("id")
            val path = o.text("path")
            // Some originals are 10000 px wide; not worth the download or the memory.
            if (id.isEmpty() || path.isEmpty() || o.optLong("file_size") > 8_000_000) continue
            out += FeedPic(path, "Wallhaven · wallhaven.cc/w/$id", "wh-$id")
        }
        return out
    }

    /** Ten random cats (the most without a key), with their sizes. */
    const val CATS = "https://api.thecatapi.com/v1/images/search?limit=10&mime_types=jpg&size=full"

    fun cats(json: String): List<FeedPic> {
        val list = JSONArray(json)
        val out = ArrayList<FeedPic>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val url = o.text("url")
            if (url.isEmpty() || !fits(o.optInt("width"), o.optInt("height"), 1280)) continue
            out += FeedPic(url, "TheCatAPI", "cat-" + safe(o.text("id").ifEmpty { md5(url).take(12) }))
        }
        return out
    }

    /** Random dogs, fifty: few are large enough to fill a TV. */
    const val DOGS = "https://dog.ceo/api/breeds/image/random/50"

    /** Most come from the Stanford Dogs set (ImageNet's "n02…" files, about 500 px wide); those are left out. */
    fun dogs(json: String): List<FeedPic> {
        val list = JSONObject(json).optJSONArray("message") ?: return emptyList()
        return (0 until list.length()).map { list.optString(it) }
            .filter { it.startsWith("http") && !IMAGENET.containsMatchIn(it) }
            .map { FeedPic(it, "Dog CEO", "dog-" + safe(it.substringAfter("/breeds/").substringBeforeLast('.'))) }
    }

    const val PICSUM = "https://picsum.photos/1920/1080"

    // Shared

    /** Picture addresses in a page or a JSON answer of any shape (custom links, 360's and Cleveland's feeds). */
    val IMAGE_URL = Regex("""https?://[^\s"'<>()\\]+?\.(?:jpe?g|png|webp)(?:\?[^\s"'<>()\\]*)?""", RegexOption.IGNORE_CASE)

    /**
     * The id in a picture's file name ("wallhaven-yqkl97.jpg" and "yqkl97.jpg" are both "yqkl97"), or
     * null for a name that is no id, such as IIIF's "default.jpg", which every picture there shares.
     */
    fun pictureId(url: String): String? {
        // Cleveland's are "1916.1044_web" and "1916.1044_print".
        val stem = url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.').substringAfterLast('-').substringBefore('_')
        return stem.takeIf { it.length >= 6 && it.any(Char::isDigit) }
    }

    /** The first two lists taken in turn, then the others after them. */
    fun <T> interleave(lists: List<List<T>>): List<T> {
        val a = lists.getOrElse(0) { emptyList() }
        val b = lists.getOrElse(1) { emptyList() }
        val out = ArrayList<T>()
        for (i in 0 until maxOf(a.size, b.size)) {
            a.getOrNull(i)?.let { out += it }
            b.getOrNull(i)?.let { out += it }
        }
        lists.drop(2).forEach { out += it }
        return out
    }

    /** The text of an HTML snippet: tags dropped, entities decoded, white space collapsed. */
    fun plain(html: String): String {
        val text = html.replace(BREAK, " ").replace(TAG, "")
        return ENTITY.replace(text) { entity(it.groupValues[1]) ?: it.value }.replace(SPACE, " ").trim()
    }

    /** [s] in at most [max] characters: its first sentence if that fits, else cut at a word, with "…". */
    fun shorten(s: String, max: Int): String {
        if (s.length <= max) return s
        val sentence = s.indexOf(". ")
        if (sentence in 20 until max) return s.substring(0, sentence)
        val cut = s.lastIndexOf(' ', max - 1).takeIf { it > max / 2 } ?: (max - 1)
        return s.substring(0, cut).trimEnd(' ', ',', ';', ':', '(', '—', '-') + "…"
    }

    /** "Title — Artist (date) · Museum · Domaine public". */
    private fun credit(title: String, artist: String, date: String, museum: String): String {
        val by = listOf(plain(artist), plain(date).let { if (it.isEmpty()) "" else "($it)" }).filter { it.isNotEmpty() }.joinToString(" ")
        val work = listOf(shorten(plain(title), 70), shorten(by, 50)).filter { it.isNotEmpty() }.joinToString(" — ")
        return listOf(work, museum, "Domaine public").filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** Wide enough (at least [minWidth] px), and of a shape a 16:9 crop doesn't ruin: 5:4 to 12:5. */
    private fun fits(w: Int, h: Int, minWidth: Int) = w >= minWidth && h > 0 && w * 4 >= h * 5 && w * 5 <= h * 12

    private fun param(url: String, key: String) =
        url.substringAfter('?', "").split('&').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.toIntOrNull()

    /** A string field, "" when missing or null (Android's org.json would give "null"). */
    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Ids name the pictures' files. */
    private fun safe(s: String) = s.replace(UNSAFE, "_").take(60)

    private fun entity(name: String): String? {
        val code = when {
            name.startsWith("#x", ignoreCase = true) -> name.drop(2).toIntOrNull(16)
            name.startsWith("#") -> name.drop(1).toIntOrNull()
            else -> return NAMED[name]
        }
        return code?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }
    }

    private val PHOTOS = setOf("image/jpeg", "image/png", "image/tiff", "image/webp")
    private val LABEL = Regex("""^description\s*:\s*""", RegexOption.IGNORE_CASE)
    private val APOD_DATE = Regex("""^APOD:\s*\d{4}\s+\p{L}+\s+\d{1,2}\s*[-–—]\s*""")
    private val CREDIT_LABEL = Regex("""^(?:image\s+)?credit(?:\s*(?:&|and)\s*copyright)?\s*:\s*""", RegexOption.IGNORE_CASE)
    private val IMAGENET = Regex("""/n\d{8}_\d+\.jpe?g$""", RegexOption.IGNORE_CASE)
    private val UNSAFE = Regex("[^A-Za-z0-9_-]")
    private val BREAK = Regex("""<(?:br|/?p|/?div|/?li|/?tr|/?td)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#?[A-Za-z0-9]+);")
    /** White space, the no-break space included (Commons' descriptions are full of them). */
    private val SPACE = Regex("[\\s\\u00a0]+")
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "hellip" to "…", "ndash" to "–", "mdash" to "—", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "laquo" to "«", "raquo" to "»")
}

/** The soft multi-colour gradients tvOS 26 uses behind the home grid. */
object Backgrounds {
    /** key -> (label, colour blobs as x, y, radius (fractions of width), colour). */
    val THEMES: LinkedHashMap<String, Pair<String, List<Blob>>> = linkedMapOf(
        "green" to ("Vert" to listOf(
            Blob(0.10f, 0.35f, 0.75f, 0xFF0B7A2E.toInt()),
            Blob(0.48f, -0.05f, 0.70f, 0xFF02B43C.toInt()),
            Blob(1.00f, 0.20f, 0.55f, 0xFF0F7F8A.toInt()),
            Blob(1.05f, 0.80f, 0.45f, 0xFF3C2B8F.toInt()),
        )),
        "blue" to ("Bleu" to listOf(
            Blob(0.10f, 0.30f, 0.75f, 0xFF0B3C8C.toInt()),
            Blob(0.50f, -0.05f, 0.70f, 0xFF1E6FE0.toInt()),
            Blob(1.00f, 0.20f, 0.55f, 0xFF16A3C4.toInt()),
            Blob(1.05f, 0.85f, 0.45f, 0xFF5B2EA6.toInt()),
        )),
        "purple" to ("Violet" to listOf(
            Blob(0.10f, 0.30f, 0.75f, 0xFF4A1C8C.toInt()),
            Blob(0.50f, -0.05f, 0.70f, 0xFF8E3BE0.toInt()),
            Blob(1.00f, 0.20f, 0.55f, 0xFFD24C9A.toInt()),
            Blob(1.05f, 0.85f, 0.45f, 0xFF2D3FA8.toInt()),
        )),
        "orange" to ("Orange" to listOf(
            Blob(0.10f, 0.30f, 0.75f, 0xFF9A3412.toInt()),
            Blob(0.50f, -0.05f, 0.70f, 0xFFF0802A.toInt()),
            Blob(1.00f, 0.20f, 0.55f, 0xFFE0466A.toInt()),
            Blob(1.05f, 0.85f, 0.45f, 0xFF6B2FA0.toInt()),
        )),
        "graphite" to ("Graphite" to listOf(
            Blob(0.10f, 0.30f, 0.75f, 0xFF2A2D34.toInt()),
            Blob(0.50f, -0.05f, 0.70f, 0xFF4A505C.toInt()),
            Blob(1.00f, 0.20f, 0.55f, 0xFF36404F.toInt()),
            Blob(1.05f, 0.85f, 0.45f, 0xFF2B2A40.toInt()),
        )),
    )

    data class Blob(val x: Float, val y: Float, val r: Float, val color: Int)

    /** Rendered small; the ImageView's bilinear upscale keeps it perfectly smooth. */
    fun render(key: String): Bitmap {
        val blobs = (THEMES[key] ?: THEMES.getValue("green")).second
        val w = 480
        val h = 270
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(6, 10, 10))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (bl in blobs) {
            p.shader = android.graphics.RadialGradient(bl.x * w, bl.y * w, bl.r * w,
                intArrayOf(bl.color, Color.argb(140, Color.red(bl.color), Color.green(bl.color), Color.blue(bl.color)), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        }
        // Darken the bottom so the lower rows sit on near-black, as in tvOS.
        p.shader = LinearGradient(0f, h * 0.45f, 0f, h.toFloat(),
            Color.TRANSPARENT, Color.argb(235, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        return b
    }
}
