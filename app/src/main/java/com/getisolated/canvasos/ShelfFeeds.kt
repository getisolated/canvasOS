package com.getisolated.canvasos

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * The Top Shelf's parsing, free of Android calls so it runs in unit tests: TvProvider rows (Watch
 * Next, preview channels) and TMDB answers turned into [Shelf.Item]s, and the table of streaming
 * apps TMDB can fill. [Shelf] does the reading and the downloading.
 */
object ShelfFeeds {
    /** A streaming app TMDB can fill: the service's name, and its TMDB watch-provider ids ("9|119" = either). */
    data class Service(val name: String, val providers: String)

    /**
     * TMDB watch-provider ids, for France. Ids à vérifier: they are JustWatch's ids as TMDB serves
     * them, written from memory without a key at hand; one call to
     * `/3/watch/providers/movie?watch_region=FR` lists the real ones. Prime Video shows up under 9 or
     * 119 depending on the country, so both.
     */
    val SERVICES = mapOf(
        "com.netflix.ninja" to Service("Netflix", "8"),
        "com.amazon.amazonvideo.livingroom" to Service("Prime Video", "9|119"),
        "com.disney.disneyplus" to Service("Disney+", "337"),
        "com.apple.atve.androidtv.appletv" to Service("Apple TV", "350"),
        "com.wbd.stream" to Service("Max", "1899"),
        "com.cbs.ott" to Service("Paramount+", "531"),
        "com.canal.android.canal" to Service("Canal+", "381"),
        "com.crunchyroll.crunchyroid" to Service("Crunchyroll", "283"),
    )

    const val TMDB_IMAGE = "https://image.tmdb.org/t/p/w1280"
    private const val TMDB_API = "https://api.themoviedb.org/3"

    /**
     * The week's popular titles of a service in France: `kind` is "movie" or "tv". Subscription
     * titles only (flatrate), so Prime Video's rentals stay out.
     */
    fun discoverUrl(kind: String, providers: String): String =
        "$TMDB_API/discover/$kind?watch_region=FR&with_watch_providers=${URLEncoder.encode(providers, "UTF-8")}" +
            "&with_watch_monetization_types=flatrate&sort_by=popularity.desc&language=fr-FR&include_adult=false&page=1"

    /** Any cheap call that answers 401 to a bad key. */
    const val TMDB_CHECK = "$TMDB_API/authentication"

    /**
     * TMDB hands out two kinds of credentials: the v3 "API key" (32 hex digits, sent as `api_key`)
     * and the v4 "API Read Access Token" (a JWT, sent as a bearer token). People paste either.
     */
    fun isBearer(key: String) = key.startsWith("eyJ") && key.length > 60

    /** [url] with the v3 key added, or as is for a v4 token (which goes in a header instead). */
    fun withKey(url: String, key: String): String =
        if (isBearer(key)) url else url + (if ('?' in url) "&" else "?") + "api_key=" + URLEncoder.encode(key, "UTF-8")

    /** Movies and series merged by popularity; titles without a wide picture are left out. */
    fun tmdb(movies: String?, series: String?, limit: Int = 12): List<Shelf.Item> =
        (titles(movies, tv = false) + titles(series, tv = true))
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { it.image }
            .take(limit)

    private fun titles(json: String?, tv: Boolean): List<Pair<Shelf.Item, Double>> {
        val results = json?.let { runCatching { JSONObject(it).optJSONArray("results") }.getOrNull() } ?: return emptyList()
        return (0 until results.length()).mapNotNull { k ->
            val o = results.optJSONObject(k) ?: return@mapNotNull null
            val backdrop = o.str("backdrop_path")?.takeIf { it.startsWith("/") } ?: return@mapNotNull null
            val title = (o.str(if (tv) "name" else "title") ?: o.str(if (tv) "original_name" else "original_title"))
                ?: return@mapNotNull null
            val year = o.str(if (tv) "first_air_date" else "release_date")?.let(::year)
            // A rating from a handful of votes says nothing.
            val rating = o.optDouble("vote_average", 0.0).takeIf { it > 0 && o.optInt("vote_count", 0) >= 10 }
            val meta = listOfNotNull(
                if (tv) "Série" else "Film",
                year,
                rating?.let { "★ " + String.format(Locale.FRANCE, "%.1f", it) },
            ).joinToString(" · ")
            Shelf.Item(title, meta, TMDB_IMAGE + backdrop) to o.optDouble("popularity", 0.0)
        }
    }

    /** The disk cache of a TMDB list. */
    fun itemsToJson(items: List<Shelf.Item>): String = JSONArray().apply {
        items.forEach { put(JSONObject().put("title", it.title).put("meta", it.meta).put("image", it.image)) }
    }.toString()

    fun itemsFromJson(json: String): List<Shelf.Item> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { k ->
            val o = a.optJSONObject(k) ?: return@mapNotNull null
            val title = o.str("title") ?: return@mapNotNull null
            val image = o.str("image") ?: return@mapNotNull null
            Shelf.Item(title, o.str("meta").orEmpty(), image)
        }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------- TvProvider

    /**
     * One Watch Next or preview program, as TvProvider holds it (TvContract column names in the
     * comments). Ratios are TvContract's ASPECT_RATIO_* constants, -1 when the app left them out.
     */
    data class TvRow(
        val title: String?,                 // title
        val episodeTitle: String? = null,   // episode_title
        val season: String? = null,         // season_display_number
        val episode: String? = null,        // episode_display_number
        val description: String? = null,    // short_description
        val poster: String? = null,         // poster_art_uri
        val posterRatio: Int = -1,          // poster_art_aspect_ratio
        val thumbnail: String? = null,      // thumbnail_uri
        val thumbnailRatio: Int = -1,       // poster_thumbnail_aspect_ratio
        val type: Int = -1,                 // type (TYPE_MOVIE...)
        val browsable: Boolean = true,      // browsable: 0 once the user removed it from the row
        val positionMs: Long = 0,           // last_playback_position_millis
        val durationMs: Long = 0,           // duration_millis
        val releaseDate: String? = null,    // release_date ("2024" or "2024-03-01")
        val engagedAt: Long = 0,            // last_engagement_time_utc_millis (Watch Next)
        val watchNextType: Int = -1,        // watch_next_type (Watch Next)
        val weight: Int = 0,                // weight (preview programs)
        val channel: Long = -1,             // channel_id (preview programs)
    )

    /** A preview channel: the row an app publishes to the Android TV home ("Recommandé"). */
    data class TvChannel(val id: Long, val name: String?, val browsable: Boolean)

    // TvContract.PreviewPrograms.ASPECT_RATIO_16_9, _3_2 and _4_3: wide enough to fill the screen.
    private val WIDE = setOf(0, 1, 2)
    private const val WATCHLIST = 3        // WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST

    /** The most recently watched first, as the Android TV home orders its Watch Next row. */
    fun watchNext(rows: List<TvRow>, limit: Int = 12): List<Shelf.Item> =
        rows.filter { it.browsable }
            .sortedByDescending { it.engagedAt }
            .mapNotNull { item(it, watchNext = true) }
            .distinctBy { it.title + '\n' + it.meta }
            .take(limit)

    /** "Ma liste" when the app only put titles to watch later there, "Continuer à regarder" otherwise. */
    fun watchNextLabel(rows: List<TvRow>): String =
        if (rows.isNotEmpty() && rows.filter { it.browsable }.all { it.watchNextType == WATCHLIST }) "Ma liste"
        else "Continuer à regarder"

    /**
     * The first of the app's channels that has something to show, with its name: the channels the
     * user (or the TV's own home) made visible, or all of them on a TV whose home never approved
     * any, since nothing there would ever show them otherwise.
     */
    fun preview(channels: List<TvChannel>, rows: List<TvRow>, limit: Int = 12): Pair<String, List<Shelf.Item>>? {
        val shown = channels.filter { it.browsable }.ifEmpty { channels }.sortedBy { it.id }
        val byChannel = rows.filter { it.browsable }.groupBy { it.channel }
        for (ch in shown) {
            val items = byChannel[ch.id].orEmpty()
                .sortedByDescending { it.weight }
                .mapNotNull { item(it, watchNext = false) }
                .distinctBy { it.title + '\n' + it.image }
                .take(limit)
            if (items.isNotEmpty()) return (ch.name?.trim()?.takeIf { it.isNotEmpty() } ?: "À découvrir") to items
        }
        return null
    }

    /** A row as a shelf item, or null without a title or a picture. */
    fun item(r: TvRow, watchNext: Boolean): Shelf.Item? {
        val title = r.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val image = art(r) ?: return null
        return Shelf.Item(title, meta(r, watchNext, title), image)
    }

    /**
     * The picture: wide art first, whichever of the two the app marked wide; then whatever there
     * is ([Shelf.image] sets a portrait poster into a wide frame).
     */
    fun art(r: TvRow): String? {
        val poster = r.poster?.trim()?.takeIf { it.isNotEmpty() }
        val thumb = r.thumbnail?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            poster != null && r.posterRatio in WIDE -> poster
            thumb != null && r.thumbnailRatio in WIDE -> thumb
            else -> poster ?: thumb
        }
    }

    /**
     * "Saison 2 · Épisode 5 · Le titre · Il reste 23 min" for something under way; the app's own
     * description otherwise; and failing that what it is ("Film · 2024 · 1 h 52").
     */
    fun meta(r: TvRow, watchNext: Boolean, title: String = r.title.orEmpty()): String {
        val parts = ArrayList<String>()
        val season = r.season?.trim()?.takeIf { it.isNotEmpty() }
        val episode = r.episode?.trim()?.takeIf { it.isNotEmpty() }
        season?.let { parts += "Saison $it" }
        episode?.let { parts += "Épisode $it" }
        r.episodeTitle?.trim()?.takeIf { it.isNotEmpty() && it != title }?.let { parts += it }
        if (watchNext) remaining(r.positionMs, r.durationMs)?.let { parts += it }
        if (parts.isEmpty()) oneLine(r.description)?.let { return it }
        if (parts.isEmpty()) {
            typeLabel(r.type)?.let { parts += it }
            r.releaseDate?.let(::year)?.let { parts += it }
            if (r.durationMs >= 60_000) parts += length(r.durationMs)
        }
        return parts.joinToString(" · ")
    }

    fun remaining(positionMs: Long, durationMs: Long): String? {
        if (positionMs <= 0 || durationMs <= 0 || positionMs >= durationMs) return null
        return "Il reste " + length(durationMs - positionMs)
    }

    /** "23 min", "1 h 05", rounded up to the minute. */
    fun length(ms: Long): String {
        val min = ((ms + 59_999) / 60_000).coerceAtLeast(1)
        return if (min < 60) "$min min" else "%d h %02d".format(min / 60, min % 60)
    }

    fun typeLabel(type: Int): String? = when (type) {
        0 -> "Film"            // TYPE_MOVIE
        1 -> "Série"           // TYPE_TV_SERIES
        2 -> "Saison"          // TYPE_TV_SEASON
        3 -> "Épisode"         // TYPE_TV_EPISODE
        4 -> "Vidéo"           // TYPE_CLIP
        5 -> "Événement"       // TYPE_EVENT
        6 -> "Chaîne"          // TYPE_CHANNEL
        7 -> "Morceau"         // TYPE_TRACK
        8 -> "Album"           // TYPE_ALBUM
        9 -> "Artiste"         // TYPE_ARTIST
        10 -> "Playlist"       // TYPE_PLAYLIST
        11 -> "Radio"          // TYPE_STATION
        12 -> "Jeu"            // TYPE_GAME
        else -> null
    }

    /** A description on one line, short enough for the meta line (the view ellipsizes the rest). */
    private fun oneLine(s: String?): String? =
        s?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.length > 160) it.take(159).trimEnd() + "…" else it }

    private fun year(date: String): String? = date.trim().take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }

    /** org.json answers "null" for a JSON null; this answers null, and null for blank text too. */
    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }
}
