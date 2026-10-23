package com.getisolated.canvasos

import com.getisolated.canvasos.ShelfFeeds.TvRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfFeedsTest {
    // Trimmed from the shape of TMDB's /3/discover answers.
    private val movies = """{"page":1,"results":[
      {"id":1,"title":"Le Film","original_title":"The Movie","backdrop_path":"/film.jpg","release_date":"2025-03-14",
       "vote_average":7.83,"vote_count":1200,"popularity":310.5},
      {"id":2,"title":"Sans image","backdrop_path":null,"release_date":"2025-01-01","vote_average":6.0,"vote_count":50,"popularity":900.0},
      {"id":3,"title":"Peu de votes","backdrop_path":"/peu.jpg","release_date":"","vote_average":9.5,"vote_count":3,"popularity":20.0},
      {"id":4,"title":null,"original_title":"Original Only","backdrop_path":"/orig.jpg","release_date":null,"popularity":5.0}
    ],"total_pages":40}"""
    private val series = """{"page":1,"results":[
      {"id":10,"name":"La Série","backdrop_path":"/serie.jpg","first_air_date":"2024-09-01","vote_average":8.1,"vote_count":400,"popularity":500.0},
      {"id":11,"name":"","original_name":"","backdrop_path":"/vide.jpg","popularity":800.0}
    ]}"""

    @Test fun tmdbMergesMoviesAndSeriesByPopularity() {
        val items = ShelfFeeds.tmdb(movies, series)
        assertEquals(listOf("La Série", "Le Film", "Peu de votes", "Original Only"), items.map { it.title })
        assertEquals("Série · 2024 · ★ 8,1", items[0].meta)
        assertEquals("Film · 2025 · ★ 7,8", items[1].meta)
        assertEquals("a rating from three votes is left out, and so is an empty date", "Film", items[2].meta)
        assertEquals("https://image.tmdb.org/t/p/w1280/serie.jpg", items[0].image)
    }

    @Test fun tmdbSurvivesFailuresAndCaps() {
        assertEquals(listOf("La Série"), ShelfFeeds.tmdb(null, series).map { it.title })
        assertTrue(ShelfFeeds.tmdb("<html>502</html>", """{"status_code":7,"status_message":"Invalid API key"}""").isEmpty())
        val many = (1..30).joinToString(",", """{"results":[""", "]}") {
            """{"title":"T$it","backdrop_path":"/$it.jpg","popularity":$it}"""
        }
        val items = ShelfFeeds.tmdb(many, null)
        assertEquals(12, items.size)
        assertEquals("T30", items[0].title)
    }

    @Test fun serviceTableIsWellFormed() {
        val pkgs = setOf(
            "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus",
            "com.apple.atve.androidtv.appletv", "com.wbd.stream", "com.cbs.ott", "com.canal.android.canal",
            "com.crunchyroll.crunchyroid",
        )
        assertEquals(pkgs, ShelfFeeds.SERVICES.keys)
        ShelfFeeds.SERVICES.values.forEach {
            assertTrue(it.name, it.providers.matches(Regex("\\d+(\\|\\d+)*")))
            assertTrue(it.name.isNotBlank())
        }
        assertEquals("8", ShelfFeeds.SERVICES.getValue("com.netflix.ninja").providers)
    }

    @Test fun discoverUrlAndKeys() {
        val url = ShelfFeeds.discoverUrl("tv", "9|119")
        assertTrue(url.startsWith("https://api.themoviedb.org/3/discover/tv?"))
        assertTrue(url, "with_watch_providers=9%7C119" in url)
        assertTrue("watch_region=FR" in url && "language=fr-FR" in url && "sort_by=popularity.desc" in url)
        val v3 = "0123456789abcdef0123456789abcdef"
        assertFalse(ShelfFeeds.isBearer(v3))
        assertEquals("$url&api_key=$v3", ShelfFeeds.withKey(url, v3))
        assertEquals("https://x/3/authentication?api_key=$v3", ShelfFeeds.withKey("https://x/3/authentication", v3))
        val v4 = "eyJhbGciOiJIUzI1NiJ9." + "a".repeat(120) + ".sig"
        assertTrue(ShelfFeeds.isBearer(v4))
        assertEquals("a read token goes in a header, never in the address", url, ShelfFeeds.withKey(url, v4))
    }

    @Test fun diskCacheRoundTrip() {
        val items = listOf(Shelf.Item("Le Film", "Film · 2025", "https://image.tmdb.org/t/p/w1280/a.jpg"), Shelf.Item("« Ça »", "", "/b"))
        assertEquals(items, ShelfFeeds.itemsFromJson(ShelfFeeds.itemsToJson(items)))
        assertTrue(ShelfFeeds.itemsFromJson("not json").isEmpty())
    }

    @Test fun watchNextNewestFirstWithProgress() {
        val rows = listOf(
            TvRow("Ancien", thumbnail = "https://a/old.jpg", thumbnailRatio = 0, engagedAt = 100),
            TvRow("La Série", episodeTitle = "Le retour", season = "2", episode = "5",
                poster = "https://a/poster.jpg", posterRatio = 4, thumbnail = "https://a/still.jpg", thumbnailRatio = 0,
                positionMs = 20 * 60_000L, durationMs = 43 * 60_000L, engagedAt = 300),
            TvRow("Retiré", thumbnail = "https://a/x.jpg", browsable = false, engagedAt = 400),
            TvRow("Sans image", engagedAt = 500),
            TvRow("   ", thumbnail = "https://a/y.jpg", engagedAt = 600),
        )
        val items = ShelfFeeds.watchNext(rows)
        assertEquals(listOf("La Série", "Ancien"), items.map { it.title })
        assertEquals("Saison 2 · Épisode 5 · Le retour · Il reste 23 min", items[0].meta)
        assertEquals("the wide still beats the portrait poster", "https://a/still.jpg", items[0].image)
        assertEquals("Continuer à regarder", ShelfFeeds.watchNextLabel(rows))
        assertEquals("Ma liste", ShelfFeeds.watchNextLabel(listOf(TvRow("A", watchNextType = 3), TvRow("B", watchNextType = 0, browsable = false))))
    }

    @Test fun metaFallsBackToDescriptionThenType() {
        assertEquals("Une famille part en vacances.",
            ShelfFeeds.meta(TvRow("X", description = "  Une famille part\n\t en vacances. "), watchNext = false))
        assertEquals("Film · 2024 · 1 h 52",
            ShelfFeeds.meta(TvRow("X", type = 0, releaseDate = "2024-05-01", durationMs = 112 * 60_000L), watchNext = false))
        assertEquals("", ShelfFeeds.meta(TvRow("X"), watchNext = true))
        assertNull("finished: nothing left", ShelfFeeds.remaining(10, 10))
        assertEquals("1 h 05", ShelfFeeds.length(64 * 60_000L + 1))
        assertEquals("1 min", ShelfFeeds.length(1))
        // A portrait poster is still used when it is all there is.
        assertEquals("https://a/p.jpg", ShelfFeeds.art(TvRow("X", poster = "https://a/p.jpg", posterRatio = 4)))
        assertEquals("https://a/p.jpg", ShelfFeeds.art(TvRow("X", poster = "https://a/p.jpg", thumbnail = "https://a/t.jpg")))
    }

    @Test fun watchNextLeavesOutWhatTheUserRemoved() {
        val rows = listOf(
            TvRow("Retiré", thumbnail = "https://a/1.jpg", engagedAt = 9, browsable = false),
            TvRow("Gardé", thumbnail = "https://a/2.jpg", engagedAt = 1),
        )
        assertEquals(listOf("Gardé"), ShelfFeeds.watchNext(rows).map { it.title })
        assertTrue(ShelfFeeds.watchNext(rows.take(1)).isEmpty())
    }
}
