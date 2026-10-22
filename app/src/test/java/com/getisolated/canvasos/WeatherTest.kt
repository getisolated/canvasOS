package com.getisolated.canvasos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** Samples are the real answers of each service for Paris (22 October 2026, 21:45), geocoding trimmed. */
class WeatherTest {
    private val forecast = """{"latitude":48.853382,"longitude":2.355208,"generationtime_ms":0.3489255905151367,"utc_offset_seconds":7200,"timezone":"Europe/Paris","timezone_abbreviation":"GMT+2","elevation":46.0,"current_units":{"time":"iso8601","interval":"seconds","temperature_2m":"°C","weather_code":"wmo code","is_day":""},"current":{"time":"2026-10-22T21:45","interval":900,"temperature_2m":14.0,"weather_code":3,"is_day":0},"daily_units":{"time":"iso8601","weather_code":"wmo code","temperature_2m_max":"°C","temperature_2m_min":"°C"},"daily":{"time":["2026-10-22","2026-10-23","2026-10-24","2026-10-25","2026-10-26"],"weather_code":[80,81,80,61,61],"temperature_2m_max":[15.3,13.5,15.0,17.3,15.7],"temperature_2m_min":[9.0,9.9,9.2,10.2,8.6]}}"""

    private val geocoding = """{"results":[{"id":2988507,"name":"Paris","latitude":48.85341,"longitude":2.3488,"elevation":42.0,"feature_code":"PPLC","country_code":"FR","admin1_id":3012874,"admin2_id":2968815,"timezone":"Europe/Paris","population":2138551,"postcodes":["75001","75020"],"country_id":3017382,"country":"France","admin1":"Île-de-France","admin2":"Département de Paris","admin3":"Paris","admin4":"Paris"},{"id":4717560,"name":"Paris","latitude":33.66094,"longitude":-95.55551,"elevation":183.0,"feature_code":"PPLA2","country_code":"US","timezone":"America/Chicago","population":24782,"country_id":6252001,"country":"États-Unis","admin1":"Texas","admin2":"Comté de Lamar"},{"id":1,"name":"Monaco","latitude":43.73,"longitude":7.42,"country":"Monaco","admin1":null}],"generationtime_ms":0.14638901}"""

    private val today = LocalDate.of(2026, 10, 22)

    @Test fun readsTheForecast() {
        val f = Weather.parse(forecast)
        assertNotNull(f)
        f!!
        assertEquals(14.0, f.temp, 0.0)
        assertEquals(3, f.code)
        assertFalse("is_day 0 is night", f.isDay)
        assertEquals(5, f.days.size)
        assertEquals(Weather.Day("2026-10-22", 80, 9.0, 15.3), f.days[0])
        assertEquals(Weather.Day("2026-10-26", 61, 8.6, 15.7), f.days[4])
    }

    @Test fun linesAsTheScreensShowThem() {
        val f = Weather.parse(forecast)!!
        assertEquals("14°", Weather.degrees(f.temp))
        assertEquals("14° · Couvert", Weather.now(f))
        assertEquals("14° · Couvert · 9° / 15°", Weather.summary(f, today))
        val lines = Weather.upcoming(f, today).map { Weather.dayLine(it, today) }
        assertEquals(listOf(
            "Aujourd'hui · Averses · 9° / 15°",
            "Demain · Averses · 10° / 14°",
            "Samedi · Averses · 9° / 15°",
            "Dimanche · Pluie faible · 10° / 17°",
            "Lundi · Pluie faible · 9° / 16°",
        ), lines)
    }

    @Test fun aForecastFromBeforeMidnightStartsWithToday() {
        val f = Weather.parse(forecast)!!
        val tomorrow = today.plusDays(1)
        assertEquals(4, Weather.upcoming(f, tomorrow).size)
        assertEquals("14° · Couvert · 10° / 14°", Weather.summary(f, tomorrow))
        assertEquals("Aujourd'hui", Weather.dayName("2026-10-23", tomorrow))
    }

    @Test fun missingDaysAndBrokenAnswersAreDropped() {
        val partial = forecast.replace("\"weather_code\":[80,81,80,61,61]", "\"weather_code\":[80,81,null,61,61]")
        assertEquals(4, Weather.parse(partial)!!.days.size)
        assertNull(Weather.parse("""{"error":true,"reason":"Latitude must be in range of -90 to 90°."}"""))
        assertNull(Weather.parse("<html>502</html>"))
    }

    @Test fun roundingNeverShowsMinusZero() {
        assertEquals("0°", Weather.degrees(-0.4))
        assertEquals("-3°", Weather.degrees(-2.6))
        assertEquals("16°", Weather.degrees(15.5))
    }

    @Test fun codesMapToLabelsAndIcons() {
        assertEquals("Ensoleillé", Weather.label(0, true))
        assertEquals("Dégagé", Weather.label(0, false))
        assertEquals(Weather.Kind.SUNNY, Weather.kind(0, true))
        assertEquals(Weather.Kind.CLEAR_NIGHT, Weather.kind(0, false))
        assertEquals(Weather.Kind.PARTLY_CLOUDY, Weather.kind(2, true))
        assertEquals(Weather.Kind.PARTLY_CLOUDY_NIGHT, Weather.kind(1, false))
        assertEquals(Weather.Kind.CLOUDY, Weather.kind(3, false))
        assertEquals("Brouillard", Weather.label(48))
        assertEquals(Weather.Kind.FOG, Weather.kind(45))
        assertEquals(Weather.Kind.RAIN, Weather.kind(55))
        assertEquals(Weather.Kind.RAIN, Weather.kind(81))
        assertEquals("Neige", Weather.label(73))
        assertEquals(Weather.Kind.SNOW, Weather.kind(86))
        assertEquals("Orage", Weather.label(95))
        assertEquals(Weather.Kind.THUNDER, Weather.kind(99))
        assertEquals(Weather.Kind.CLOUDY, Weather.kind(42))
        // Every code Open-Meteo documents has a label of its own.
        val documented = listOf(0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99)
        assertTrue(documented.none { Weather.label(it) == "Temps inconnu" })
    }

    @Test fun findsPlacesWithWhatTellsThemApart() {
        val places = Weather.parsePlaces(geocoding)
        assertEquals(3, places.size)
        assertEquals(Weather.Place("Paris", 48.85341, 2.3488, "Île-de-France, France"), places[0])
        assertEquals("Texas, États-Unis", places[1].detail)
        assertEquals("a null region and a country named like the place are left out", "", places[2].detail)
        assertTrue(Weather.parsePlaces("""{"generationtime_ms":0.097990036}""").isEmpty())
        assertTrue(Weather.parsePlaces("not json").isEmpty())
    }

    @Test fun guessesThePlaceFromIpApi() {
        assertEquals(Weather.Place("Nancy", 48.6822, 6.1862), Weather.parseLocation("""{"status":"success","city":"Nancy","lat":48.6822,"lon":6.1862}"""))
        assertNull(Weather.parseLocation("""{"status":"fail","message":"private range"}"""))
        assertNull(Weather.parseLocation("""{"status":"success","city":"","lat":0,"lon":0}"""))
    }

    @Test fun urlsUseDotsWhateverTheLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertEquals("https://api.open-meteo.com/v1/forecast?latitude=48.8534&longitude=-2.3488" +
                "&current=temperature_2m,weather_code,is_day&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                "&timezone=auto&forecast_days=5", Weather.forecastUrl(48.85341, -2.3488))
        } finally {
            Locale.setDefault(saved)
        }
        assertEquals("https://geocoding-api.open-meteo.com/v1/search?name=Saint-%C3%89tienne%20du%20Rouvray&count=5&language=fr&format=json",
            Weather.searchUrl(" Saint-Étienne du Rouvray "))
    }

    @Test fun theCacheKeepsWhenAndWhere() {
        val s = Weather.encodeCache(1_761_162_300_000L, 48.85341, 2.3488, forecast)
        val c = Weather.decodeCache(s)!!
        assertEquals(1_761_162_300_000L, c.at)
        assertEquals(Weather.parse(forecast), c.forecast)
        assertTrue(Weather.samePlace(c, 48.85341, 2.3488))
        assertFalse(Weather.samePlace(c, 45.75, 4.85))
        assertFalse(Weather.samePlace(c, null, null))
        assertNull(Weather.decodeCache(""))
        assertNull(Weather.decodeCache("{}"))
    }
}
