package com.getisolated.canvasos

import com.getisolated.canvasos.Weather.Kind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Guards the WMO weather code table (label, icon, day and night) and how Open-Meteo, geocoding and cache answers are read when they are incomplete. */
class WeatherCodesTest {
    private val today = LocalDate.of(2026, 10, 22)   // a Thursday

    /** Open-Meteo's documented codes: code to (label, icon by day, icon by night). */
    private val table: Map<Int, Triple<String, Kind, Kind>> = buildMap {
        put(0, Triple("Ensoleillé", Kind.SUNNY, Kind.CLEAR_NIGHT))
        put(1, Triple("Peu nuageux", Kind.PARTLY_CLOUDY, Kind.PARTLY_CLOUDY_NIGHT))
        put(2, Triple("Nuageux", Kind.PARTLY_CLOUDY, Kind.PARTLY_CLOUDY_NIGHT))
        put(3, Triple("Couvert", Kind.CLOUDY, Kind.CLOUDY))
        for (c in listOf(45, 48)) put(c, Triple("Brouillard", Kind.FOG, Kind.FOG))
        for (c in listOf(51, 53, 55)) put(c, Triple("Bruine", Kind.RAIN, Kind.RAIN))
        for (c in listOf(56, 57, 66, 67)) put(c, Triple("Pluie verglaçante", Kind.RAIN, Kind.RAIN))
        put(61, Triple("Pluie faible", Kind.RAIN, Kind.RAIN))
        put(63, Triple("Pluie", Kind.RAIN, Kind.RAIN))
        put(65, Triple("Forte pluie", Kind.RAIN, Kind.RAIN))
        put(71, Triple("Neige faible", Kind.SNOW, Kind.SNOW))
        for (c in listOf(73, 77)) put(c, Triple("Neige", Kind.SNOW, Kind.SNOW))
        put(75, Triple("Forte neige", Kind.SNOW, Kind.SNOW))
        for (c in listOf(80, 81)) put(c, Triple("Averses", Kind.RAIN, Kind.RAIN))
        put(82, Triple("Fortes averses", Kind.RAIN, Kind.RAIN))
        for (c in listOf(85, 86)) put(c, Triple("Averses de neige", Kind.SNOW, Kind.SNOW))
        put(95, Triple("Orage", Kind.THUNDER, Kind.THUNDER))
        for (c in listOf(96, 99)) put(c, Triple("Orage et grêle", Kind.THUNDER, Kind.THUNDER))
    }

    @Test fun everyDocumentedCodeHasItsLabelAndItsIconByDayAndByNight() {
        assertEquals(28, table.size)
        for ((code, t) in table) {
            val (label, day, night) = t
            assertEquals("label $code by day", label, Weather.label(code, true))
            assertEquals("kind $code by day", day, Weather.kind(code, true))
            assertEquals("kind $code by night", night, Weather.kind(code, false))
            // Only a clear sky is worded differently at night.
            assertEquals("label $code by night", if (code == 0) "Dégagé" else label, Weather.label(code, false))
        }
    }

    @Test fun theDefaultIsDaytime() {
        for (code in table.keys) {
            assertEquals(Weather.label(code, true), Weather.label(code))
            assertEquals(Weather.kind(code, true), Weather.kind(code))
        }
    }

    @Test fun everyCodeFromZeroTo99MapsToSomethingShowable() {
        for (code in 0..99) for (isDay in listOf(true, false)) {
            assertTrue("label $code", Weather.label(code, isDay).isNotBlank())
            assertNotNull("kind $code", Weather.kind(code, isDay))
            Weather.icon(Weather.kind(code, isDay))
        }
    }

    @Test fun codesNobodyDocumentedAreUnknownButNeverBreakAScreen() {
        val unknown = (-5..200).filter { it !in table }
        for (code in unknown) assertEquals("code $code", "Temps inconnu", Weather.label(code))
        // Far from every family the icon is a plain cloud.
        for (code in listOf(-1, 4, 10, 44, 46, 47, 49, 50, 68, 69, 70, 78, 79, 83, 84, 87, 90, 94, 100, 200, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals("code $code", Kind.CLOUDY, Weather.kind(code, true))
            assertEquals("code $code at night", Kind.CLOUDY, Weather.kind(code, false))
        }
        // Inside a family, an undocumented code still gets the family's icon.
        assertEquals(Kind.RAIN, Weather.kind(62))
        assertEquals(Kind.SNOW, Weather.kind(72))
        assertEquals(Kind.THUNDER, Weather.kind(97))
    }

    @Test fun everyKindHasItsOwnIcon() {
        val icons = Kind.values().map { Weather.icon(it) }
        assertEquals(Kind.values().size, icons.toSet().size)
        assertTrue(icons.all { it != 0 })
        assertEquals(Weather.icon(Kind.CLEAR_NIGHT), Weather.icon(Weather.Forecast(8.0, 0, false, emptyList())))
        assertEquals(Weather.icon(Kind.SUNNY), Weather.icon(Weather.Forecast(8.0, 0, true, emptyList())))
    }

    @Test fun everyKindIsReachableFromSomeCode() {
        val reached = HashSet<Kind>()
        for (code in 0..99) for (isDay in listOf(true, false)) reached += Weather.kind(code, isDay)
        assertEquals(Kind.values().toSet(), reached)
    }

    @Test fun labelsAreShortEnoughForTheCapsule() {
        for (code in 0..99) assertTrue("label $code", Weather.label(code).length <= 18)
    }

    // ---- reading the answers when something is missing ----

    private fun forecast(current: String = """"temperature_2m":14.0,"weather_code":3,"is_day":0""", daily: String? = null): String {
        val d = daily ?: """"daily":{"time":["2026-10-22","2026-10-23"],"weather_code":[80,81],"temperature_2m_max":[15.3,13.5],"temperature_2m_min":[9.0,9.9]}"""
        return """{"current":{$current},$d}"""
    }

    @Test fun aMissingDayFlagMeansDaytime() {
        assertTrue(Weather.parse(forecast(current = """"temperature_2m":14.0,"weather_code":3"""))!!.isDay)
        assertTrue(Weather.parse(forecast(current = """"temperature_2m":14.0,"weather_code":3,"is_day":1"""))!!.isDay)
        assertFalse(Weather.parse(forecast())!!.isDay)
    }

    @Test fun anAnswerLackingASectionOrANumberIsNoForecast() {
        assertNull(Weather.parse("""{"daily":{"time":[],"weather_code":[],"temperature_2m_max":[],"temperature_2m_min":[]}}"""))
        assertNull(Weather.parse("""{"current":{"temperature_2m":14.0,"weather_code":3}}"""))
        assertNull(Weather.parse(forecast(current = """"weather_code":3""")))
        assertNull(Weather.parse(forecast(current = """"temperature_2m":14.0""")))
        assertNull(Weather.parse(forecast(current = """"temperature_2m":"warm","weather_code":3""")))
        assertNull(Weather.parse(forecast(daily = """"daily":{"time":["2026-10-22"]}""")))
        assertNull(Weather.parse(""))
        assertNull(Weather.parse("[]"))
        assertNull(Weather.parse("null"))
    }

    @Test fun aForecastWithNoDaysStillShowsTheCurrentWeather() {
        val f = Weather.parse(forecast(daily = """"daily":{"time":[],"weather_code":[],"temperature_2m_max":[],"temperature_2m_min":[]}"""))!!
        assertTrue(f.days.isEmpty())
        assertEquals("14° · Couvert", Weather.summary(f, today))
        assertEquals("14° · Couvert", Weather.now(f))
        assertTrue(Weather.upcoming(f, today).isEmpty())
    }

    @Test fun daysBeyondAShortArrayAreDroppedNotGuessed() {
        val f = Weather.parse(forecast(daily = """"daily":{"time":["2026-10-22","2026-10-23","2026-10-24"],"weather_code":[80,81],"temperature_2m_max":[15.3,13.5,14.0],"temperature_2m_min":[9.0,9.9,8.0]}"""))!!
        assertEquals(listOf("2026-10-22", "2026-10-23"), f.days.map { it.date })
    }

    @Test fun aDayMissingOnlyItsHighOrItsLowIsDropped() {
        val f = Weather.parse(forecast(daily = """"daily":{"time":["2026-10-22","2026-10-23","2026-10-24"],"weather_code":[80,81,61],"temperature_2m_max":[15.3,null,14.0],"temperature_2m_min":[9.0,9.9,null]}"""))!!
        assertEquals(listOf("2026-10-22"), f.days.map { it.date })
    }

    @Test fun upcomingStartsAtTodayAndSkipsThePast() {
        val f = Weather.Forecast(10.0, 3, true, listOf(
            Weather.Day("2026-10-20", 3, 1.0, 2.0), Weather.Day("2026-10-21", 3, 1.0, 2.0),
            Weather.Day("2026-10-22", 3, 1.0, 2.0), Weather.Day("2026-10-23", 3, 1.0, 2.0)))
        assertEquals(listOf("2026-10-22", "2026-10-23"), Weather.upcoming(f, today).map { it.date })
        assertEquals(listOf("2026-10-23"), Weather.upcoming(f, today.plusDays(1)).map { it.date })
        assertTrue(Weather.upcoming(f, today.plusDays(2)).isEmpty())
        assertEquals(4, Weather.upcoming(f, today.minusDays(30)).size)
    }

    @Test fun dayNamesAreFrenchAndCapitalised() {
        // 22 October 2026 is a Thursday, so the 29th is the next one and is not "Demain".
        val names = (22..29).map { Weather.dayName("2026-10-$it", today) }
        assertEquals(listOf("Aujourd'hui", "Demain", "Samedi", "Dimanche", "Lundi", "Mardi", "Mercredi", "Jeudi"), names)
        assertEquals("Vendredi", Weather.dayName("2026-10-30", today))
        assertEquals("a day already past is named, not 'Hier'", "Mercredi", Weather.dayName("2026-10-21", today))
    }

    @Test fun anUnreadableDateIsShownAsItIs() {
        assertEquals("bientôt", Weather.dayName("bientôt", today))
        assertEquals("", Weather.dayName("", today))
        assertEquals("2026-13-45", Weather.dayName("2026-13-45", today))
        assertEquals("22/10/2026", Weather.dayName("22/10/2026", today))
    }

    @Test fun halvesRoundUpAndZeroHasNoSign() {
        assertEquals("1°", Weather.degrees(0.5))
        assertEquals("0°", Weather.degrees(-0.5))
        assertEquals("-1°", Weather.degrees(-1.5))
        assertEquals("3°", Weather.degrees(2.5))
        assertEquals("100°", Weather.degrees(99.99))
        assertEquals("0°", Weather.degrees(0.0))
        assertEquals("0°", Weather.degrees(-0.0))
        assertEquals("-40°", Weather.degrees(-40.0))
    }

    @Test fun aDayLineUsesTheDaytimeWordsAndBothTemperatures() {
        assertEquals("Aujourd'hui · Ensoleillé · -2° / 7°", Weather.dayLine(Weather.Day("2026-10-22", 0, -2.0, 7.0), today))
        assertEquals("bientôt · Temps inconnu · 0° / 0°", Weather.dayLine(Weather.Day("bientôt", 4, 0.0, 0.0), today))
    }

    @Test fun placesWithoutNameOrCoordinatesAreSkipped() {
        val json = """{"results":[
          {"name":"","latitude":1.0,"longitude":2.0},
          {"name":"Sans latitude","longitude":2.0},
          {"name":"Latitude nulle","latitude":null,"longitude":2.0},
          {"name":"Sans longitude","latitude":1.0},
          {"name":"Nulle part"},
          "not an object",
          {"name":"Paris","latitude":48.85,"longitude":2.35,"admin1":"Paris","country":"France"},
          {"name":"Île-de-France","latitude":48.5,"longitude":2.5,"admin1":"Île-de-France","country":"Île-de-France"},
          {"name":"Origine","latitude":0.0,"longitude":0.0,"admin1":"France","country":"France"}
        ]}"""
        val places = Weather.parsePlaces(json)
        assertEquals(listOf("Paris", "Île-de-France", "Origine"), places.map { it.name })
        assertEquals("a region named like the place is left out", "France", places[0].detail)
        assertEquals("", places[1].detail)
        assertEquals("a region and a country that are the same appear once", "France", places[2].detail)
        assertEquals("0 is a place, not a missing coordinate", 0.0, places[2].lat, 0.0)
    }

    @Test fun noPlacesFromAnythingElse() {
        assertTrue(Weather.parsePlaces("""{"results":[]}""").isEmpty())
        assertTrue(Weather.parsePlaces("""{"results":null}""").isEmpty())
        assertTrue(Weather.parsePlaces("""{"results":"x"}""").isEmpty())
        assertTrue(Weather.parsePlaces("").isEmpty())
        assertTrue(Weather.parsePlaces("[]").isEmpty())
    }

    @Test fun theGuessedPlaceNeedsASuccessACityAndBothCoordinates() {
        assertEquals(Weather.Place("Lyon", 45.76, 4.84), Weather.parseLocation("""{"status":"success","city":" Lyon ","lat":45.76,"lon":4.84}"""))
        assertEquals("zero is a coordinate", Weather.Place("Null Island", 0.0, 0.0),
            Weather.parseLocation("""{"status":"success","city":"Null Island","lat":0,"lon":0}"""))
        assertNull(Weather.parseLocation("""{"city":"Lyon","lat":45.76,"lon":4.84}"""))
        assertNull(Weather.parseLocation("""{"status":"success","city":"Lyon","lon":4.84}"""))
        assertNull(Weather.parseLocation("""{"status":"success","city":"Lyon","lat":45.76}"""))
        assertNull(Weather.parseLocation("""{"status":"success","city":"Lyon","lat":null,"lon":4.84}"""))
        assertNull(Weather.parseLocation("""{"status":"success","city":null,"lat":45.76,"lon":4.84}"""))
        assertNull(Weather.parseLocation("""{"status":"success","city":"   ","lat":45.76,"lon":4.84}"""))
        assertNull(Weather.parseLocation(""))
        assertNull(Weather.parseLocation("not json"))
    }

    @Test fun urlsKeepFourDecimalsAndEncodeTheSearch() {
        assertEquals("https://api.open-meteo.com/v1/forecast?latitude=-33.8688&longitude=151.2093" +
            "&current=temperature_2m,weather_code,is_day&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&timezone=auto&forecast_days=5", Weather.forecastUrl(-33.86882, 151.20929))
        assertTrue(Weather.forecastUrl(0.0, 0.0).contains("latitude=0.0000&longitude=0.0000"))
        assertEquals("https://geocoding-api.open-meteo.com/v1/search?name=a%2Bb%20c%26d&count=5&language=fr&format=json", Weather.searchUrl("a+b c&d"))
        assertTrue(Weather.searchUrl("").contains("name=&count=5"))
        assertTrue(Weather.searchUrl("Zoé").contains("name=Zo%C3%A9&"))
    }

    @Test fun aCacheWithoutItsTimeOrItsForecastIsNoCache() {
        val ok = Weather.encodeCache(1_000L, 1.5, 2.5, forecast())
        assertNotNull(Weather.decodeCache(ok))
        // Without one of its fields (JSONObject keeps no key order, so the key is removed, not cut out of the text).
        assertNull(Weather.decodeCache(JSONObject(ok).apply { remove("at") }.toString()))
        assertNull(Weather.decodeCache(JSONObject(ok).apply { remove("lat") }.toString()))
        assertNull(Weather.decodeCache(JSONObject(ok).apply { remove("data") }.toString()))
        assertNull(Weather.decodeCache("""{"at":1,"lat":1.0,"lon":2.0,"data":{"error":true}}"""))
        assertNull(Weather.decodeCache("""{"at":1,"lat":1.0,"lon":2.0}"""))
        assertNull(Weather.decodeCache("not json"))
        assertNull(Weather.decodeCache("[]"))
    }

    @Test fun twoPlacesAreTheSameWithinAMillionthOfADegree() {
        val c = Weather.decodeCache(Weather.encodeCache(0L, 48.85341, 2.3488, forecast()))!!
        assertTrue(Weather.samePlace(c, 48.85341 + 1e-7, 2.3488 - 1e-7))
        assertFalse(Weather.samePlace(c, 48.85341 + 1e-5, 2.3488))
        assertFalse(Weather.samePlace(c, 48.85341, 2.3488 + 1e-5))
        assertFalse(Weather.samePlace(c, 48.85341, null))
        assertFalse(Weather.samePlace(c, null, 2.3488))
    }

    @Test fun aForecastIsKeptLongerThanItIsRefreshed() {
        assertTrue(Weather.STALE_MS > Weather.REFRESH_MS)
        assertEquals(30 * 60_000L, Weather.REFRESH_MS)
    }
}
