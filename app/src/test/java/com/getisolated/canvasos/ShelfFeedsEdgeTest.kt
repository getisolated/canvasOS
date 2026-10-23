package com.getisolated.canvasos

import com.getisolated.canvasos.ShelfFeeds.TvRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the Top Shelf feeds' edge cases: incomplete TMDB and TvProvider rows, duration and type wording, and the streaming-service table. */
class ShelfFeedsEdgeTest {
    // ---- the service table ----

    @Test fun everyServiceIsAPackageWithANameAndPositiveProviderIds() {
        assertTrue(ShelfFeeds.SERVICES.isNotEmpty())
        for ((pkg, s) in ShelfFeeds.SERVICES) {
            assertTrue(pkg, pkg.matches(Regex("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+")))
            assertTrue(pkg, s.name.isNotBlank() && s.name == s.name.trim())
            val ids = s.providers.split('|')
            assertTrue("$pkg: ${s.providers}", ids.isNotEmpty() && ids.all { it.matches(Regex("[1-9]\\d*")) })
            assertEquals("no id twice in one service", ids.size, ids.toSet().size)
        }
    }

    @Test fun noServiceNameOrProviderIdIsSharedByTwoApps() {
        val services = ShelfFeeds.SERVICES.values
        assertEquals(services.size, services.map { it.name }.toSet().size)
        val ids = services.flatMap { it.providers.split('|') }
        assertEquals("a provider id belongs to one service", ids.size, ids.toSet().size)
    }

    @Test fun everyServiceAppIsOnTheDefaultGrid() {
        // A service the grid never lists would have a shelf nobody can reach on a fresh install.
        for (pkg in ShelfFeeds.SERVICES.keys) assertTrue(pkg, pkg in Prefs.DEFAULT_ORDER)
    }

    @Test fun everyServiceMakesAUsableDiscoverUrl() {
        for (s in ShelfFeeds.SERVICES.values) for (kind in listOf("movie", "tv")) {
            val url = ShelfFeeds.discoverUrl(kind, s.providers)
            assertTrue(url, url.startsWith("https://api.themoviedb.org/3/discover/$kind?"))
            assertFalse("the bar of '9|119' is encoded", url.contains('|'))
            assertEquals(1, Regex("with_watch_providers=").findAll(url).count())
            assertTrue(url, "with_watch_monetization_types=flatrate" in url)
        }
        assertTrue(ShelfFeeds.discoverUrl("movie", "8").contains("with_watch_providers=8&"))
    }

    // ---- TMDB answers ----

    @Test fun aTitleWithoutAWideImageOrNameIsLeftOut() {
        val json = """{"results":[
          {"id":1,"title":"Garde","backdrop_path":"/a.jpg","popularity":5},
          {"id":2,"title":"Sans champ image","popularity":9},
          {"id":3,"title":"Image nulle","backdrop_path":null,"popularity":9},
          {"id":4,"title":"Image vide","backdrop_path":"","popularity":9},
          {"id":5,"title":"Image sans barre","backdrop_path":"a.jpg","popularity":9},
          {"id":6,"title":"Image blanche","backdrop_path":"   ","popularity":9},
          {"id":7,"backdrop_path":"/b.jpg","popularity":9},
          {"id":8,"title":"  ","original_title":"","backdrop_path":"/c.jpg","popularity":9},
          "not an object", 3, null
        ]}"""
        assertEquals(listOf("Garde"), ShelfFeeds.tmdb(json, null).map { it.title })
    }

    @Test fun theOriginalTitleStandsInOnlyWhenTheTitleIsMissing() {
        val json = """{"results":[
          {"title":"Titre","original_title":"Original","backdrop_path":"/a.jpg","popularity":3},
          {"title":null,"original_title":"Seul l'original","backdrop_path":"/b.jpg","popularity":2},
          {"title":" ","original_title":"Titre blanc","backdrop_path":"/c.jpg","popularity":1}
        ]}"""
        assertEquals(listOf("Titre", "Seul l'original", "Titre blanc"), ShelfFeeds.tmdb(json, null).map { it.title })
    }

    @Test fun aRatingNeedsTenVotesAndAPositiveAverage() {
        fun meta(votes: Int, avg: String) = ShelfFeeds.tmdb(
            """{"results":[{"title":"T","backdrop_path":"/a.jpg","release_date":"2025-01-01","vote_average":$avg,"vote_count":$votes}]}""", null)[0].meta
        assertEquals("Film · 2025 · ★ 7,0", meta(10, "7.0"))
        assertEquals("Film · 2025", meta(9, "7.0"))
        assertEquals("Film · 2025", meta(1000, "0"))
        assertEquals("Film · 2025", meta(1000, "0.0"))
        assertEquals("Film · 2025 · ★ 10,0", meta(10, "10"))
        assertEquals("the comma is French and the rounding is to one decimal", "Film · 2025 · ★ 6,7", meta(50, "6.666"))
    }

    @Test fun theYearNeedsFourDigits() {
        fun meta(date: String) = ShelfFeeds.tmdb("""{"results":[{"title":"T","backdrop_path":"/a.jpg","release_date":"$date"}]}""", null)[0].meta
        assertEquals("Film · 1999", meta("1999-12-31"))
        assertEquals("Film · 2025", meta("2025"))
        assertEquals("Film", meta(""))
        assertEquals("Film", meta("99"))
        assertEquals("Film", meta("abcd-01-01"))
        assertEquals("Film", meta("20x5"))
    }

    @Test fun seriesReadTheirOwnFieldNames() {
        val series = """{"results":[{"name":"La Série","original_name":"The Show","backdrop_path":"/s.jpg","first_air_date":"2024-09-01","popularity":4},
          {"name":null,"original_name":"Seulement l'original","backdrop_path":"/t.jpg","popularity":3},
          {"title":"Un titre de film","backdrop_path":"/u.jpg","popularity":9}]}"""
        val items = ShelfFeeds.tmdb(null, series)
        assertEquals(listOf("La Série", "Seulement l'original"), items.map { it.title })
        assertEquals("Série · 2024", items[0].meta)
        assertEquals("Série", items[1].meta)
    }

    @Test fun theSameBackdropIsShownOnce() {
        val movies = """{"results":[{"title":"Film","backdrop_path":"/same.jpg","popularity":10}]}"""
        val series = """{"results":[{"name":"Série","backdrop_path":"/same.jpg","popularity":20}]}"""
        // The more popular of the two stays.
        assertEquals(listOf("Série"), ShelfFeeds.tmdb(movies, series).map { it.title })
    }

    @Test fun theLimitCapsTheList() {
        val many = (1..50).joinToString(",", """{"results":[""", "]}") { """{"title":"T$it","backdrop_path":"/$it.jpg","popularity":$it}""" }
        assertEquals(12, ShelfFeeds.tmdb(many, null).size)
        assertEquals(5, ShelfFeeds.tmdb(many, null, limit = 5).size)
        assertEquals(listOf("T50", "T49"), ShelfFeeds.tmdb(many, null, limit = 2).map { it.title })
        assertTrue(ShelfFeeds.tmdb(many, null, limit = 0).isEmpty())
    }

    @Test fun nothingComesOutOfNothing() {
        assertTrue(ShelfFeeds.tmdb(null, null).isEmpty())
        assertTrue(ShelfFeeds.tmdb("", "").isEmpty())
        assertTrue(ShelfFeeds.tmdb("{}", "{}").isEmpty())
        assertTrue(ShelfFeeds.tmdb("""{"results":null}""", """{"results":[]}""").isEmpty())
        assertTrue(ShelfFeeds.tmdb("[]", "null").isEmpty())
    }

    @Test fun anImageAddressUsesTheWideSize() {
        val item = ShelfFeeds.tmdb("""{"results":[{"title":"T","backdrop_path":"/x/y.jpg"}]}""", null)[0]
        assertEquals(ShelfFeeds.TMDB_IMAGE + "/x/y.jpg", item.image)
        assertEquals("https://image.tmdb.org/t/p/w1280", ShelfFeeds.TMDB_IMAGE)
    }

    @Test fun aKeyIsEncodedInTheAddressOnlyForV3() {
        val url = "https://api.themoviedb.org/3/discover/movie?x=1"
        assertEquals("$url&api_key=a%26b", ShelfFeeds.withKey(url, "a&b"))
        assertEquals("https://x/y?api_key=abc", ShelfFeeds.withKey("https://x/y", "abc"))
        assertFalse("a short 'eyJ' string is no token", ShelfFeeds.isBearer("eyJ" + "a".repeat(10)))
        assertFalse("and a long string that does not start with eyJ is no token", ShelfFeeds.isBearer("a".repeat(100)))
        assertTrue(ShelfFeeds.isBearer("eyJ" + "a".repeat(58)))
        assertFalse(ShelfFeeds.isBearer("eyJ" + "a".repeat(57)))
        assertFalse(ShelfFeeds.isBearer(""))
    }

    // ---- the disk cache ----

    @Test fun cachedItemsWithoutATitleOrImageAreDropped() {
        val json = """[{"title":"Ok","meta":"m","image":"https://a/1.jpg"},{"meta":"x","image":"https://a/2.jpg"},{"title":"Sans image"},
          {"title":" ","image":"https://a/3.jpg"},{"title":"Sans meta","image":"https://a/4.jpg"},"junk",7,null,{"title":"Nul","meta":null,"image":"https://a/5.jpg"}]"""
        assertEquals(listOf(
            Shelf.Item("Ok", "m", "https://a/1.jpg"),
            Shelf.Item("Sans meta", "", "https://a/4.jpg"),
            Shelf.Item("Nul", "", "https://a/5.jpg"),
        ), ShelfFeeds.itemsFromJson(json))
    }

    @Test fun theCacheReadsNothingFromTheWrongShape() {
        assertTrue(ShelfFeeds.itemsFromJson("").isEmpty())
        assertTrue(ShelfFeeds.itemsFromJson("{}").isEmpty())
        assertTrue(ShelfFeeds.itemsFromJson("[]").isEmpty())
        assertTrue(ShelfFeeds.itemsFromJson("""{"title":"T","image":"i"}""").isEmpty())
    }

    @Test fun theCacheKeepsQuotesNewlinesAndEmoji() {
        val items = listOf(Shelf.Item("Il dit « \"non\" »\net revient 🎬", "meta \\ avec \"guillemets\"", "https://a/b.jpg?x=1&y=2"))
        assertEquals(items, ShelfFeeds.itemsFromJson(ShelfFeeds.itemsToJson(items)))
        assertEquals("[]", ShelfFeeds.itemsToJson(emptyList()))
    }

    // ---- durations and types ----

    @Test fun lengthsRoundUpToTheMinute() {
        assertEquals("1 min", ShelfFeeds.length(0))
        assertEquals("1 min", ShelfFeeds.length(-5_000))
        assertEquals("1 min", ShelfFeeds.length(60_000))
        assertEquals("2 min", ShelfFeeds.length(60_001))
        assertEquals("59 min", ShelfFeeds.length(59 * 60_000L))
        assertEquals("1 h 00", ShelfFeeds.length(60 * 60_000L))
        assertEquals("1 h 01", ShelfFeeds.length(60 * 60_000L + 1))
        assertEquals("2 h 05", ShelfFeeds.length(125 * 60_000L))
        assertEquals("10 h 00", ShelfFeeds.length(600 * 60_000L))
    }

    @Test fun whatIsLeftNeedsAPositionInsideTheDuration() {
        assertEquals("Il reste 23 min", ShelfFeeds.remaining(20 * 60_000L, 43 * 60_000L))
        assertEquals("Il reste 1 min", ShelfFeeds.remaining(43 * 60_000L - 1, 43 * 60_000L))
        assertEquals("Il reste 1 h 30", ShelfFeeds.remaining(30 * 60_000L, 120 * 60_000L))
        assertNull("not started", ShelfFeeds.remaining(0, 43 * 60_000L))
        assertNull(ShelfFeeds.remaining(-1, 43 * 60_000L))
        assertNull("no duration", ShelfFeeds.remaining(10, 0))
        assertNull(ShelfFeeds.remaining(10, -1))
        assertNull("past the end", ShelfFeeds.remaining(50 * 60_000L, 43 * 60_000L))
        assertNull(ShelfFeeds.remaining(43 * 60_000L, 43 * 60_000L))
    }

    @Test fun everyTvContractTypeHasAFrenchWord() {
        val words = (0..12).map { ShelfFeeds.typeLabel(it) }
        assertTrue(words.toString(), words.all { !it.isNullOrBlank() })
        assertEquals("every type has its own word", 13, words.toSet().size)
        assertEquals("Film", ShelfFeeds.typeLabel(0))
        assertEquals("Série", ShelfFeeds.typeLabel(1))
        assertEquals("Épisode", ShelfFeeds.typeLabel(3))
        assertEquals("Jeu", ShelfFeeds.typeLabel(12))
        for (t in listOf(-1, 13, 14, 99, Int.MAX_VALUE, Int.MIN_VALUE)) assertNull(t.toString(), ShelfFeeds.typeLabel(t))
    }

    // ---- one row as an item ----

    @Test fun aLongDescriptionIsCutOnOneLine() {
        val long = "mot ".repeat(100)
        val meta = ShelfFeeds.meta(TvRow("X", description = long), watchNext = false)
        assertEquals(160, meta.length)
        assertTrue(meta.endsWith("…"))
        assertFalse(meta.contains("\n"))
        // Exactly at the limit nothing is cut.
        val exact = "a".repeat(160)
        assertEquals(exact, ShelfFeeds.meta(TvRow("X", description = exact), watchNext = false))
        assertEquals(160, ShelfFeeds.meta(TvRow("X", description = "a".repeat(161)), watchNext = false).length)
    }

    @Test fun aDescriptionOnlyStandsInWhenThereIsNothingBetter() {
        assertEquals("Saison 1 · Épisode 2", ShelfFeeds.meta(TvRow("X", season = "1", episode = "2", description = "Résumé"), watchNext = false))
        assertEquals("Résumé", ShelfFeeds.meta(TvRow("X", season = " ", episode = "", description = "Résumé"), watchNext = false))
        assertEquals("Film", ShelfFeeds.meta(TvRow("X", type = 0, description = "   "), watchNext = false))
        assertEquals("", ShelfFeeds.meta(TvRow("X", description = "\n\t "), watchNext = false))
    }

    @Test fun anEpisodeTitleThatRepeatsTheShowTitleIsLeftOut() {
        assertEquals("Saison 3", ShelfFeeds.meta(TvRow("La Série", episodeTitle = "La Série", season = "3"), watchNext = false))
        assertEquals("Saison 3 · Pilote", ShelfFeeds.meta(TvRow("La Série", episodeTitle = " Pilote ", season = "3"), watchNext = false))
    }

    @Test fun theTimeLeftOnlyShowsInWatchNext() {
        val r = TvRow("X", episode = "4", positionMs = 10 * 60_000L, durationMs = 50 * 60_000L)
        assertEquals("Épisode 4 · Il reste 40 min", ShelfFeeds.meta(r, watchNext = true))
        assertEquals("Épisode 4", ShelfFeeds.meta(r, watchNext = false))
    }

    @Test fun typeYearAndLengthFillInForAnAppThatSaysNothingElse() {
        assertEquals("Film · 2024 · 1 h 52", ShelfFeeds.meta(TvRow("X", type = 0, releaseDate = "2024-05-01", durationMs = 112 * 60_000L), watchNext = false))
        assertEquals("Série · 2020", ShelfFeeds.meta(TvRow("X", type = 1, releaseDate = "2020"), watchNext = false))
        assertEquals("under a minute is not worth a word", "Vidéo", ShelfFeeds.meta(TvRow("X", type = 4, durationMs = 59_999), watchNext = false))
        assertEquals("1 min", ShelfFeeds.meta(TvRow("X", durationMs = 60_000), watchNext = false))
        assertEquals("", ShelfFeeds.meta(TvRow("X", type = 99, releaseDate = "bientôt"), watchNext = false))
    }

    @Test fun artPrefersWideThenWhateverThereIs() {
        val wide = "https://a/wide.jpg"
        val tall = "https://a/tall.jpg"
        assertEquals(wide, ShelfFeeds.art(TvRow("X", poster = wide, posterRatio = 0, thumbnail = tall, thumbnailRatio = 4)))
        assertEquals(wide, ShelfFeeds.art(TvRow("X", poster = tall, posterRatio = 4, thumbnail = wide, thumbnailRatio = 1)))
        assertEquals("both wide: the poster", wide, ShelfFeeds.art(TvRow("X", poster = wide, posterRatio = 2, thumbnail = tall, thumbnailRatio = 0)))
        assertEquals("neither says: the poster", tall, ShelfFeeds.art(TvRow("X", poster = tall, thumbnail = wide)))
        assertEquals(wide, ShelfFeeds.art(TvRow("X", thumbnail = wide)))
        assertEquals(wide, ShelfFeeds.art(TvRow("X", poster = " $wide ")))
        assertEquals("a blank poster is no poster", wide, ShelfFeeds.art(TvRow("X", poster = "  ", thumbnail = wide)))
        assertNull(ShelfFeeds.art(TvRow("X")))
        assertNull(ShelfFeeds.art(TvRow("X", poster = " ", thumbnail = "")))
    }

    @Test fun anItemNeedsATitleAndAPicture() {
        assertNull(ShelfFeeds.item(TvRow(null, thumbnail = "https://a/1.jpg"), watchNext = false))
        assertNull(ShelfFeeds.item(TvRow("   ", thumbnail = "https://a/1.jpg"), watchNext = false))
        assertNull(ShelfFeeds.item(TvRow("Titre"), watchNext = false))
        val item = ShelfFeeds.item(TvRow("  Titre  ", thumbnail = "https://a/1.jpg", description = "Résumé"), watchNext = false)!!
        assertEquals(Shelf.Item("Titre", "Résumé", "https://a/1.jpg"), item)
    }

    // ---- Watch Next rows ----

    @Test fun watchNextDropsRepeatsAndCapsAtTheLimit() {
        val rows = (1..30).map { TvRow("Titre $it", thumbnail = "https://a/$it.jpg", engagedAt = it.toLong()) } +
            TvRow("Titre 30", thumbnail = "https://a/dup.jpg", engagedAt = 29)
        val items = ShelfFeeds.watchNext(rows)
        assertEquals(12, items.size)
        assertEquals("Titre 30", items[0].title)
        assertEquals("the same title and line, seen twice, is shown once", 1, items.count { it.title == "Titre 30" })
        assertEquals(3, ShelfFeeds.watchNext(rows, limit = 3).size)
        assertTrue(ShelfFeeds.watchNext(emptyList()).isEmpty())
    }

    @Test fun watchNextKeepsTwoEpisodesOfTheSameShow() {
        val rows = listOf(
            TvRow("Série", season = "1", episode = "1", thumbnail = "https://a/1.jpg", engagedAt = 2),
            TvRow("Série", season = "1", episode = "2", thumbnail = "https://a/2.jpg", engagedAt = 1),
        )
        assertEquals(listOf("Saison 1 · Épisode 1", "Saison 1 · Épisode 2"), ShelfFeeds.watchNext(rows).map { it.meta })
    }

    @Test fun theWatchNextLabelNeedsEveryVisibleRowToBeAWatchlistEntry() {
        assertEquals("Continuer à regarder", ShelfFeeds.watchNextLabel(emptyList()))
        assertEquals("Ma liste", ShelfFeeds.watchNextLabel(listOf(TvRow("A", watchNextType = 3))))
        assertEquals("Continuer à regarder", ShelfFeeds.watchNextLabel(listOf(TvRow("A", watchNextType = 3), TvRow("B", watchNextType = 0))))
        assertEquals("Continuer à regarder", ShelfFeeds.watchNextLabel(listOf(TvRow("A", watchNextType = -1))))
        assertEquals("rows the user hid do not count", "Ma liste",
            ShelfFeeds.watchNextLabel(listOf(TvRow("A", watchNextType = 3), TvRow("B", watchNextType = 1, browsable = false))))
    }
}
