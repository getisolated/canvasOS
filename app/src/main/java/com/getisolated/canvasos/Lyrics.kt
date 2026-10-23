package com.getisolated.canvasos

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * The lyrics of the track that plays, from LRCLIB (https://lrclib.net), a free, open database
 * with no key. Asked only when the user presses the Paroles button ([Prefs.lyrics]): the track's
 * title, artist, album and length go to lrclib.net, nothing else, and nothing is sent without
 * that press. What comes back is kept in memory for the last few tracks, never written anywhere.
 * Synced lyrics come as LRC lines ("[01:02.34] text"); some tracks only have plain text.
 */
object Lyrics {
    private const val TAG = "canvasOSLyrics"
    private const val BASE = "https://lrclib.net/api"
    /** LRCLIB asks clients to say who they are. */
    private const val AGENT = "canvasOS/${BuildConfig.VERSION_NAME} (https://github.com/getisolated/canvasOS)"
    private const val KEEP = 8

    class Line(val ms: Long, val text: String)

    /**
     * What LRCLIB has for a track: timed [lines] when they are synced, else the [plain] text; an
     * [instrumental] track has neither. [found] false: LRCLIB has nothing for it.
     */
    class Result(val lines: List<Line>, val plain: String?, val instrumental: Boolean, val found: Boolean) {
        val synced get() = lines.isNotEmpty()
        companion object {
            val NONE = Result(emptyList(), null, false, false)
        }
    }

    private val cache = object : LinkedHashMap<String, Result>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result>?) = size > KEEP
    }

    /** The cache key of a track: the same track asked twice costs one call. */
    fun key(title: String, artist: String, durationMs: Long) = "$title|$artist|${durationMs / 1000}"

    /** What is kept for the track, if it was fetched already (any thread). */
    fun cached(title: String, artist: String, durationMs: Long): Result? = synchronized(cache) { cache[key(title, artist, durationMs)] }

    /**
     * Fetches the lyrics of the track (blocking, off the UI thread): the exact match first, then
     * a search by title and artist for a track of about the same length. Null when LRCLIB could
     * not be reached (offline); [Result.NONE] when it has nothing.
     */
    fun fetch(title: String, artist: String, album: String?, durationMs: Long): Result? {
        val k = key(title, artist, durationMs)
        synchronized(cache) { cache[k] }?.let { return it }
        val r = runCatching { lookup(title, artist, album, durationMs) }
            .onFailure { Log.w(TAG, "lyrics lookup failed: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return null
        synchronized(cache) { cache[k] = r }
        return r
    }

    private fun lookup(title: String, artist: String, album: String?, durationMs: Long): Result? {
        val q = StringBuilder("$BASE/get?track_name=${enc(title)}&artist_name=${enc(artist)}")
        if (!album.isNullOrBlank()) q.append("&album_name=${enc(album)}")
        if (durationMs > 0) q.append("&duration=${durationMs / 1000}")
        val (code, body) = get(q.toString()) ?: return null
        if (code == 200 && body != null) return parseTrack(JSONObject(body))
        if (code != 404) return null
        // No exact match: the closest track by title and artist, the same length give or take a few seconds.
        val (sc, sb) = get("$BASE/search?track_name=${enc(title)}&artist_name=${enc(artist)}") ?: return null
        if (sc != 200 || sb == null) return null
        return best(JSONArray(sb), durationMs) ?: Result.NONE
    }

    /** The search result to show for a track of [durationMs]: synced within 5 s, else any synced, else plain. */
    fun best(results: JSONArray, durationMs: Long): Result? {
        var pick: Result? = null
        var pickScore = Int.MIN_VALUE
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val r = parseTrack(o)
            val d = (o.optDouble("duration", -1.0) * 1000).toLong()
            val close = durationMs > 0 && d > 0 && abs(d - durationMs) <= 5_000
            val score = (if (r.synced) 2 else if (r.plain != null) 1 else 0) * 2 + (if (close) 1 else 0)
            if (score > pickScore && (r.synced || r.plain != null || r.instrumental)) { pick = r; pickScore = score }
        }
        return pick
    }

    /** One LRCLIB track object into a [Result]. */
    fun parseTrack(o: JSONObject): Result {
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        val lines = synced?.let { parseLrc(it) }.orEmpty()
        return Result(lines, plain?.trim(), o.optBoolean("instrumental", false), true)
    }

    /**
     * LRC text into timed lines, in time order. A line may carry several time tags ("[00:12.00]
     * [01:30.00] chorus"); hundredths or thousandths both read; tags without text keep an empty
     * line, a gap in the song. Metadata tags ("[ar:…]") are skipped.
     */
    fun parseLrc(lrc: String): List<Line> {
        val out = ArrayList<Line>()
        val tag = Regex("""\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        for (raw in lrc.lineSequence()) {
            val line = raw.trimEnd()
            val tags = tag.findAll(line).toList()
            if (tags.isEmpty()) continue
            val text = line.substring(tags.last().range.last + 1).trim()
            for (m in tags) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.toLong() }
                out += Line(min * 60_000 + sec * 1000 + ms, text)
            }
        }
        out.sortBy { it.ms }
        return out
    }

    /** The index of the line sung at [positionMs]: the last one that started by then; -1 before the first. */
    fun lineAt(lines: List<Line>, positionMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var at = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].ms <= positionMs) { at = mid; lo = mid + 1 } else hi = mid - 1
        }
        return at
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** The status and body of [url]; null when the server could not be reached. */
    private fun get(url: String): Pair<Int, String?>? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 5_000
            c.readTimeout = 8_000
            c.setRequestProperty("User-Agent", AGENT)
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            val body = if (code == 200) c.inputStream.bufferedReader().use { it.readText() } else null
            code to body
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }
}
