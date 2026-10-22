package com.getisolated.canvasos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The weather beside the home-screen clock and in the Control Center, from Open-Meteo
 * (open-meteo.com: no key, free for non-commercial use, data under CC BY 4.0, credited in the
 * forecast sheet). The place is chosen on the phone page or sent with CONFIG; until then it is
 * guessed once from the TV's public address (ip-api.com) and marked as guessed
 * ([Prefs.weatherAuto]). The services see the TV's address and the place, nothing else.
 *
 * Only the home screen fetches, from its minute ticker while it is in front, at most once every
 * [REFRESH_MS]; the answer is kept in [Prefs.weatherCache], which the Control Center reads too.
 * Reading the answers is pure (org.json only) and unit-tested in WeatherTest.
 */
object Weather {
    private const val TAG = "canvasOSWeather"

    /** A forecast this recent is not fetched again. */
    const val REFRESH_MS = 30 * 60_000L

    /** After a failed attempt the next waits this long; the home screen asks every minute. */
    private const val RETRY_MS = 10 * 60_000L

    /** An older forecast is not shown at all: a TV offline since yesterday shows no weather rather than yesterday's. */
    const val STALE_MS = 3 * 3600_000L

    const val LOCATE_URL = "http://ip-api.com/json/?fields=status,city,lat,lon&lang=fr"

    /** The drawings in res/drawable/ic_wx_*.xml. */
    enum class Kind { SUNNY, CLEAR_NIGHT, PARTLY_CLOUDY, PARTLY_CLOUDY_NIGHT, CLOUDY, FOG, RAIN, SNOW, THUNDER }

    /** One day of the forecast; [date] is ISO ("2026-10-22"), in the place's own time zone. */
    data class Day(val date: String, val code: Int, val min: Double, val max: Double)

    /** [code] is a WMO weather code, as Open-Meteo gives it. */
    data class Forecast(val temp: Double, val code: Int, val isDay: Boolean, val days: List<Day>)

    /** [detail] tells places of the same name apart in a search ("Île-de-France, France"); not stored. */
    data class Place(val name: String, val lat: Double, val lon: Double, val detail: String = "")

    /** What [Prefs.weatherCache] holds: when, for where, and Open-Meteo's answer. */
    data class Cached(val at: Long, val lat: Double, val lon: Double, val forecast: Forecast)

    // ------------------------------------------------------------------ pure

    fun forecastUrl(lat: Double, lon: Double) =
        "https://api.open-meteo.com/v1/forecast?latitude=${coord(lat)}&longitude=${coord(lon)}" +
            "&current=temperature_2m,weather_code,is_day&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&timezone=auto&forecast_days=5"

    // Locale.US: a French TV would otherwise write "48,8534".
    private fun coord(v: Double) = String.format(Locale.US, "%.4f", v)

    fun searchUrl(name: String) = "https://geocoding-api.open-meteo.com/v1/search?name=" +
        URLEncoder.encode(name.trim(), "UTF-8").replace("+", "%20") + "&count=5&language=fr&format=json"

    /** Open-Meteo's forecast answer, or null when it is not one. */
    fun parse(json: String): Forecast? = runCatching {
        val o = JSONObject(json)
        val cur = o.getJSONObject("current")
        val d = o.getJSONObject("daily")
        val time = d.getJSONArray("time")
        val codes = d.getJSONArray("weather_code")
        val max = d.getJSONArray("temperature_2m_max")
        val min = d.getJSONArray("temperature_2m_min")
        val days = (0 until time.length()).mapNotNull { i ->
            // A day the models have no value for yet comes as null.
            if (codes.isNull(i) || max.isNull(i) || min.isNull(i)) null
            else Day(time.getString(i), codes.getInt(i), min.getDouble(i), max.getDouble(i))
        }
        Forecast(cur.getDouble("temperature_2m"), cur.getInt("weather_code"), cur.optInt("is_day", 1) != 0, days)
    }.getOrNull()

    /** The geocoding answer: up to five places, the most likely first; empty when nothing matches. */
    fun parsePlaces(json: String): List<Place> = runCatching {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        (0 until results.length()).mapNotNull { i ->
            val r = results.optJSONObject(i) ?: return@mapNotNull null
            val name = r.str("name")
            if (name.isEmpty() || r.isNull("latitude") || r.isNull("longitude")) return@mapNotNull null
            val detail = listOf(r.str("admin1"), r.str("country")).filter { it.isNotEmpty() && it != name }.distinct().joinToString(", ")
            Place(name, r.getDouble("latitude"), r.getDouble("longitude"), detail)
        }
    }.getOrDefault(emptyList())

    /** ip-api.com's answer: the city of the TV's public address, or null. */
    fun parseLocation(json: String): Place? = runCatching {
        val o = JSONObject(json)
        val city = o.str("city")
        if (o.str("status") != "success" || city.isEmpty() || o.isNull("lat") || o.isNull("lon")) null
        else Place(city, o.getDouble("lat"), o.getDouble("lon"))
    }.getOrNull()

    // Android's org.json reads a JSON null as the string "null".
    private fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key).trim()

    /** The condition in a few French words, short enough for the Control Center's capsule. */
    fun label(code: Int, isDay: Boolean = true): String = when (code) {
        0 -> if (isDay) "Ensoleillé" else "Dégagé"
        1 -> "Peu nuageux"
        2 -> "Nuageux"
        3 -> "Couvert"
        45, 48 -> "Brouillard"
        51, 53, 55 -> "Bruine"
        56, 57, 66, 67 -> "Pluie verglaçante"
        61 -> "Pluie faible"
        63 -> "Pluie"
        65 -> "Forte pluie"
        71 -> "Neige faible"
        73, 77 -> "Neige"
        75 -> "Forte neige"
        80, 81 -> "Averses"
        82 -> "Fortes averses"
        85, 86 -> "Averses de neige"
        95 -> "Orage"
        96, 99 -> "Orage et grêle"
        else -> "Temps inconnu"
    }

    fun kind(code: Int, isDay: Boolean = true): Kind = when (code) {
        0 -> if (isDay) Kind.SUNNY else Kind.CLEAR_NIGHT
        1, 2 -> if (isDay) Kind.PARTLY_CLOUDY else Kind.PARTLY_CLOUDY_NIGHT
        45, 48 -> Kind.FOG
        in 51..67, in 80..82 -> Kind.RAIN
        in 71..77, 85, 86 -> Kind.SNOW
        in 95..99 -> Kind.THUNDER
        else -> Kind.CLOUDY
    }

    fun icon(k: Kind): Int = when (k) {
        Kind.SUNNY -> R.drawable.ic_wx_sunny
        Kind.CLEAR_NIGHT -> R.drawable.ic_wx_clear_night
        Kind.PARTLY_CLOUDY -> R.drawable.ic_wx_partly_cloudy
        Kind.PARTLY_CLOUDY_NIGHT -> R.drawable.ic_wx_partly_cloudy_night
        Kind.CLOUDY -> R.drawable.ic_wx_cloudy
        Kind.FOG -> R.drawable.ic_wx_fog
        Kind.RAIN -> R.drawable.ic_wx_rain
        Kind.SNOW -> R.drawable.ic_wx_snow
        Kind.THUNDER -> R.drawable.ic_wx_thunderstorm
    }

    fun icon(f: Forecast) = icon(kind(f.code, f.isDay))

    /** "14°"; rounding never gives "-0°". */
    fun degrees(t: Double) = "${t.roundToInt()}°"

    /** The days from [today] on: a forecast fetched before midnight no longer starts with yesterday. */
    fun upcoming(f: Forecast, today: LocalDate = LocalDate.now()): List<Day> {
        val from = today.toString()
        return f.days.filter { it.date >= from }
    }

    /** "14° · Nuageux", under the large clock. */
    fun now(f: Forecast) = "${degrees(f.temp)} · ${label(f.code, f.isDay)}"

    /** "14° · Nuageux · 9° / 16°", the Control Center's capsule: now, then today's low and high. */
    fun summary(f: Forecast, today: LocalDate = LocalDate.now()) =
        now(f) + (upcoming(f, today).firstOrNull()?.let { " · ${degrees(it.min)} / ${degrees(it.max)}" } ?: "")

    /** "Aujourd'hui", "Demain", then the day's name: "Samedi". */
    fun dayName(date: String, today: LocalDate = LocalDate.now()): String {
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
        return when (d) {
            today -> "Aujourd'hui"
            today.plusDays(1) -> "Demain"
            else -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.FRANCE).replaceFirstChar { it.titlecase(Locale.FRANCE) }
        }
    }

    /** "Demain · Averses · 9° / 13°", one line of the forecast sheet. */
    fun dayLine(d: Day, today: LocalDate = LocalDate.now()) =
        "${dayName(d.date, today)} · ${label(d.code)} · ${degrees(d.min)} / ${degrees(d.max)}"

    fun encodeCache(at: Long, lat: Double, lon: Double, raw: String): String =
        JSONObject().put("at", at).put("lat", lat).put("lon", lon).put("data", JSONObject(raw)).toString()

    fun decodeCache(s: String): Cached? = runCatching {
        if (s.isEmpty()) return null
        val o = JSONObject(s)
        val f = parse(o.getJSONObject("data").toString()) ?: return null
        Cached(o.getLong("at"), o.getDouble("lat"), o.getDouble("lon"), f)
    }.getOrNull()

    fun samePlace(c: Cached, lat: Double?, lon: Double?) =
        lat != null && lon != null && abs(c.lat - lat) < 1e-6 && abs(c.lon - lon) < 1e-6

    // --------------------------------------------------------------- Android

    /** The place as the screens show it: "Paris", "Paris (détecté)", or "" when there is none yet. */
    fun placeLabel(p: Prefs): String =
        if (p.weatherPlace.isEmpty()) "" else p.weatherPlace + if (p.weatherAuto) " (détecté)" else ""

    // The cache is read every minute and on each Control Center opening; parsed once per new answer.
    @Volatile private var memo: Pair<String, Cached?>? = null

    private fun cache(p: Prefs): Cached? {
        val s = p.weatherCache
        memo?.let { if (it.first == s) return it.second }
        return decodeCache(s).also { memo = s to it }
    }

    /** The kept forecast, or null: weather off, nothing fetched yet, fetched for another place, or too old. */
    fun cached(ctx: Context): Forecast? {
        val p = Prefs(ctx)
        if (!p.weather) return null
        val c = cache(p) ?: return null
        if (!samePlace(c, p.weatherLat, p.weatherLon)) return null
        return c.forecast.takeIf { System.currentTimeMillis() - c.at in 0..STALE_MS }
    }

    private fun due(p: Prefs): Boolean {
        val c = cache(p) ?: return true
        // A clock set back since then counts as due too.
        return !samePlace(c, p.weatherLat, p.weatherLon) || System.currentTimeMillis() - c.at !in 0 until REFRESH_MS
    }

    private val busy = AtomicBoolean(false)
    @Volatile private var lastTry = 0L

    /**
     * Fetches a new forecast when the kept one is due, on a thread of its own; [done] runs there once
     * a new one is stored. The home screen calls this every minute while it is in front: at most one
     * fetch every [REFRESH_MS], none while offline, and after a failure none for [RETRY_MS].
     */
    fun refreshIfDue(ctx: Context, done: () -> Unit) {
        val app = ctx.applicationContext
        val p = Prefs(app)
        if (!p.weather || !due(p)) return
        val now = SystemClock.elapsedRealtime()
        if (lastTry != 0L && now - lastTry < RETRY_MS) return
        if (!Net.isConnected(app) || !busy.compareAndSet(false, true)) return
        lastTry = now
        Thread {
            try {
                if (fetch(app)) {
                    lastTry = 0L
                    done()
                }
            } finally {
                busy.set(false)
            }
        }.start()
    }

    /** Blocking. Guesses the place first when there is none. */
    private fun fetch(ctx: Context): Boolean {
        val p = Prefs(ctx)
        if (p.weatherLat == null || p.weatherLon == null) {
            val guess = locate() ?: return false
            // Chosen on the phone page meanwhile: that one stands.
            if (p.weatherLat == null) setPlace(ctx, guess, auto = true)
        }
        val lat = p.weatherLat ?: return false
        val lon = p.weatherLon ?: return false
        val raw = get(forecastUrl(lat, lon)) ?: return false
        if (parse(raw) == null) {
            Log.w(TAG, "unexpected forecast answer: ${raw.take(200)}")
            return false
        }
        p.weatherCache = encodeCache(System.currentTimeMillis(), lat, lon, raw)
        return true
    }

    /** Places matching [name], the most likely first; null when Open-Meteo could not be reached. Blocking. */
    fun geocode(name: String): List<Place>? = get(searchUrl(name))?.let { parsePlaces(it) }

    /** The city of the TV's public address. Blocking. */
    fun locate(): Place? = get(LOCATE_URL)?.let { parseLocation(it) }.also {
        Log.i(TAG, if (it != null) "place guessed from the public address: ${it.name}" else "could not guess the place")
    }

    /** A place chosen on the phone page or by CONFIG ([auto] false), or guessed. The home screen fetches its forecast at its next tick. */
    fun setPlace(ctx: Context, place: Place, auto: Boolean) {
        val p = Prefs(ctx)
        p.weatherLat = place.lat
        p.weatherLon = place.lon
        p.weatherPlace = place.name
        p.weatherAuto = auto
        lastTry = 0L
    }

    private fun get(url: String): String? {
        val host = runCatching { URL(url).host }.getOrDefault(url)
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", WallFeeds.AGENT)
            try {
                if (c.responseCode !in 200..299) {
                    Log.w(TAG, "$host: HTTP ${c.responseCode}")
                    null
                } else c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "$host: $e")
            null
        }
    }

    /**
     * CONFIG's `weather` and `weather_city` (AGENTS.md section 8), called last by [ConfigReceiver]:
     * a city is looked up over the network, so the broadcast then finishes from a thread once it is
     * known (`am broadcast` waits for it). Appends `weather=` and `weather_place=` to the result.
     */
    fun config(r: BroadcastReceiver, c: Context, i: Intent) {
        val p = Prefs(c)
        if (i.hasExtra("weather")) p.weather = i.getBooleanExtra("weather", true)
        fun echo() = " weather=${p.weather} weather_place=\"${placeLabel(p)}\""
        val city = i.getStringExtra("weather_city")?.trim()?.takeIf { it.isNotEmpty() }
        if (city == null) {
            r.resultData = (r.resultData ?: "") + echo()
            return
        }
        val pending = r.goAsync()
        Thread {
            try {
                val found = geocode(city)
                val place = found?.firstOrNull()
                if (place != null) setPlace(c, place, auto = false)
                else Log.w(TAG, "weather_city \"$city\": ${if (found == null) "Open-Meteo unreachable" else "no such place"}; place left as it was")
                pending.resultData = (pending.resultData ?: "") + echo()
            } finally {
                pending.finish()
            }
        }.start()
    }
}
