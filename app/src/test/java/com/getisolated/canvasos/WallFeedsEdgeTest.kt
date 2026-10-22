package com.getisolated.canvasos

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL
import java.time.LocalDate

/** Guards the wallpaper feeds' edge cases: empty or odd answers, size and shape limits at their exact boundaries, URL builders over many days, and text helpers. */
class WallFeedsEdgeTest {
    private val day = LocalDate.of(2026, 10, 22).toEpochDay().toInt()

    // ---- garbage in, nothing (or a JSONException the feed loop catches) out ----

    private val garbage = listOf(
        "", " ", "null", "42", "\"text\"", "true", "[]", "{}", "[[]]", "[1,null,\"x\",{},[]]", "{\"a\":1}", "<html>rate limited</html>",
        "{\"data\":\"x\"}", "{\"data\":null}", "{\"data\":[1,null,\"x\",{},[]]}", "{\"results\":[{}]}", "{\"query\":{}}", "{\"query\":{\"pages\":\"x\"}}",
        "{\"query\":{\"pages\":[1,null,{}]}}", "{\"query\":{\"pages\":{\"1\":null,\"2\":5,\"3\":{}}}}", "{\"message\":\"x\"}", "{\"message\":[1,null,{},\"ftp://x/y.jpg\"]}",
        "{\"objectIDs\":\"x\"}", "{\"objectIDs\":[null,\"a\",-3,0]}",
    )

    /** The parsers read whatever a site sends; a bad answer may fail with a JSONException (the feed loop catches it) and with nothing else. */
    private fun <T> readOrNull(block: () -> T): T? = try { block() } catch (e: JSONException) { null }

    @Test fun everyListParserSurvivesGarbageAndFindsNothingInIt() {
        val parsers: Map<String, (String) -> List<FeedPic>> = mapOf(
            "potd" to WallFeeds::potd, "apod" to { s: String -> WallFeeds.apod(s) }, "apodApi" to WallFeeds::apodApi, "artic" to WallFeeds::artic,
            "wallhaven" to WallFeeds::wallhaven, "cats" to WallFeeds::cats, "dogs" to WallFeeds::dogs,
        )
        for ((name, parse) in parsers) for (g in garbage) {
            val got = readOrNull { parse(g) } ?: continue
            assertTrue("$name on <$g> gave $got", got.isEmpty())
        }
    }

    @Test fun theSmallParsersSurviveGarbageToo() {
        for (g in garbage) {
            readOrNull { WallFeeds.apodCredits(g) }?.let { assertTrue(g, it.isEmpty()) }
            readOrNull { WallFeeds.apodCreditsUrl(g) }?.let { assertNull(g, it) }
            readOrNull { WallFeeds.metIds(g) }?.let { assertTrue(g, it.isEmpty()) }
            readOrNull { WallFeeds.metObject(g) }?.let { assertNull(g, it) }
        }
    }

    @Test fun anEmptyAnswerOfTheRightShapeIsEmpty() {
        assertTrue(WallFeeds.potd("""{"query":{"pages":[]}}""").isEmpty())
        assertTrue(WallFeeds.potd("""{"query":{"pages":{}}}""").isEmpty())
        assertTrue(WallFeeds.apod("[]").isEmpty())
        assertTrue(WallFeeds.apodApi("[]").isEmpty())
        assertTrue(WallFeeds.artic("""{"data":[]}""").isEmpty())
        assertTrue(WallFeeds.wallhaven("""{"data":[]}""").isEmpty())
        assertTrue(WallFeeds.cats("[]").isEmpty())
        assertTrue(WallFeeds.dogs("""{"message":[]}""").isEmpty())
        assertTrue(WallFeeds.metIds("""{"objectIDs":[]}""").isEmpty())
        assertTrue(WallFeeds.apodCredits("[]").isEmpty())
        assertNull(WallFeeds.apodCreditsUrl("[]"))
    }

    // ---- Commons ----

    private fun commons(width: Int, height: Int, mime: String = "image/jpeg", extra: String = """"thumburl":"https://thumb.wikimedia.org/a.jpg"""") =
        """{"query":{"pages":[{"title":"File:Pic.jpg","imageinfo":[{"width":$width,"height":$height,"mime":"$mime",$extra}]}]}}"""

    @Test fun commonsSizeAndShapeLimitsAreExact() {
        assertEquals(1, WallFeeds.potd(commons(1600, 1280)).size)      // 5:4, the squarest allowed
        assertEquals(0, WallFeeds.potd(commons(1600, 1281)).size)
        assertEquals(0, WallFeeds.potd(commons(1599, 1000)).size)      // one pixel too narrow
        assertEquals(1, WallFeeds.potd(commons(1600, 1000)).size)
        assertEquals(1, WallFeeds.potd(commons(2400, 1000)).size)      // 12:5, the widest allowed
        assertEquals(0, WallFeeds.potd(commons(2400, 999)).size)
        assertEquals(0, WallFeeds.potd(commons(1920, 0)).size)
        assertEquals(0, WallFeeds.potd(commons(0, 0)).size)
    }

    @Test fun commonsKeepsPhotosAndSkipsEverythingElse() {
        for (mime in listOf("image/jpeg", "image/png", "image/tiff", "image/webp")) assertEquals(mime, 1, WallFeeds.potd(commons(2000, 1200, mime)).size)
        for (mime in listOf("image/gif", "image/svg+xml", "video/webm", "application/ogg", "image/x-xcf", "")) assertEquals(mime, 0, WallFeeds.potd(commons(2000, 1200, mime)).size)
    }

    @Test fun commonsNeedsSomeAddress() {
        assertEquals(0, WallFeeds.potd(commons(2000, 1200, extra = """"extmetadata":{}""")).size)
        assertEquals(0, WallFeeds.potd(commons(2000, 1200, extra = """"thumburl":null,"url":null""")).size)
        assertEquals("https://u/x.jpg", WallFeeds.potd(commons(2000, 1200, extra = """"thumburl":"","url":"https://u/x.jpg"""")).single().url)
    }

    @Test fun aCommonsPageWithoutImageInfoIsSkippedWithoutStoppingTheOthers() {
        val json = """{"query":{"pages":[{"title":"File:A.jpg"},{"title":"File:B.jpg","imageinfo":[]},
          {"title":"File:C.jpg","imageinfo":[{"width":2000,"height":1200,"mime":"image/jpeg","thumburl":"https://t/c.jpg"}]}]}}"""
        assertEquals(listOf("https://t/c.jpg"), WallFeeds.potd(json).map { it.url })
    }

    @Test fun commonsIdsFollowTheTitleAndNothingElse() {
        fun id(title: String, url: String) = WallFeeds.potd(
            """{"query":{"pages":[{"title":"$title","imageinfo":[{"width":2000,"height":1200,"mime":"image/jpeg","thumburl":"$url"}]}]}}""").single().id
        assertEquals(id("File:A.jpg", "https://t/1.jpg"), id("File:A.jpg", "https://t/2.jpg"))
        assertNotEquals(id("File:A.jpg", "https://t/1.jpg"), id("File:B.jpg", "https://t/1.jpg"))
        assertTrue(id("File:É.jpg", "https://t/1.jpg").matches(Regex("wm-[0-9a-f]{12}")))
    }

    @Test fun aLongCommonsDescriptionAndArtistStayInTheFramesTwoLines() {
        val json = """{"query":{"pages":[{"title":"File:Pic.jpg","imageinfo":[{"width":2000,"height":1200,"mime":"image/jpeg","thumburl":"https://t/p.jpg",
          "extmetadata":{"ImageDescription":{"value":"${"Un très long texte de description. ".repeat(10)}"},"Artist":{"value":"${"Nom Très Long ".repeat(10)}"},
          "LicenseShortName":{"value":"CC BY 4.0"}}}]}]}}"""
        val caption = WallFeeds.potd(json).single().caption
        val (work, source, licence) = caption.split(" · ")
        assertEquals("Wikimedia Commons", source)
        assertEquals("CC BY 4.0", licence)
        val (what, who) = work.split(" — ")
        assertTrue(what, what.length <= 64)
        assertTrue(who, who.length <= 30)
        assertTrue("the licence is never cut", caption.endsWith("CC BY 4.0"))
    }

    @Test fun commonsDescriptionBeginningWithTheWordDescriptionLosesIt() {
        val json = commons(2000, 1200, extra = """"thumburl":"https://t/p.jpg","extmetadata":{"ImageDescription":{"value":"DESCRIPTION :  Le port"}}""")
        assertEquals("Le port · Wikimedia Commons", WallFeeds.potd(json).single().caption)
    }

    // ---- NASA ----

    private fun apodEntry(image: String, title: String = "APOD: 2024 May 1 – Titre", id: Int = 7, date: String = "2024-05-01T00:00:00") =
        """[{"id":$id,"date":"$date","title":{"rendered":"$title"},"featured_media":1,"featured_image_url":"$image"}]"""

    @Test fun apodSizeLimitsAreExactAndMissingSizesMeanKeep() {
        val base = "https://assets.science.nasa.gov/content/dam/science/cds/apod/a.jpg"
        assertEquals(1, WallFeeds.apod(apodEntry("$base?w=1440&h=1152")).size)     // 5:4
        assertEquals(1, WallFeeds.apod(apodEntry("$base?w=1440&h=1080")).size)
        assertEquals(0, WallFeeds.apod(apodEntry("$base?w=1439&h=1000")).size)
        assertEquals(0, WallFeeds.apod(apodEntry("$base?w=1440&h=1153")).size)
        assertEquals(1, WallFeeds.apod(apodEntry("$base?w=3000&h=1250")).size)     // 12:5
        assertEquals(0, WallFeeds.apod(apodEntry("$base?w=3000&h=1249")).size)
        assertEquals("no size at all: the download decides", 1, WallFeeds.apod(apodEntry(base)).size)
        assertEquals("one of the two is not enough to judge", 1, WallFeeds.apod(apodEntry("$base?w=100")).size)
        assertEquals(1, WallFeeds.apod(apodEntry("$base?h=100")).size)
        assertEquals("a size that is not a number counts as missing", 1, WallFeeds.apod(apodEntry("$base?w=big&h=wide")).size)
    }

    @Test fun apodAsksNasasResizerForAScreenSizedCopy() {
        val pic = WallFeeds.apod(apodEntry("https://assets.science.nasa.gov/content/dam/science/cds/apod/a.jpg?w=4000&h=3000&fit=clip&crop=faces")).single()
        assertEquals("https://assets.science.nasa.gov/dynamicimage/assets/science/cds/apod/a.jpg?w=1920&h=1080&fit=clip", pic.url)
        // An address that is already on the resizer keeps its path.
        val again = WallFeeds.apod(apodEntry("https://assets.science.nasa.gov/dynamicimage/assets/science/cds/apod/b.jpg?w=4000&h=3000")).single()
        assertEquals("https://assets.science.nasa.gov/dynamicimage/assets/science/cds/apod/b.jpg?w=1920&h=1080&fit=clip", again.url)
    }

    @Test fun apodIdsUseTheDateOrTheEntryIdWhenThereIsNone() {
        val image = "https://assets.science.nasa.gov/a.jpg"
        assertEquals("apod-2024-05-01", WallFeeds.apod(apodEntry(image)).single().id)
        assertEquals("apod-2024-05-01", WallFeeds.apod(apodEntry(image, date = "2024-05-01")).single().id)
        assertEquals("apod-7", WallFeeds.apod(apodEntry(image, date = "")).single().id)
    }

    @Test fun apodSkipsEntriesThatAreNotPicturesOfTheDay() {
        val image = "https://assets.science.nasa.gov/a.jpg"
        assertTrue(WallFeeds.apod(apodEntry(image, title = "Mars Rover News")).isEmpty())
        assertTrue(WallFeeds.apod(apodEntry(image, title = "apod: lowercase")).isEmpty())
        assertTrue(WallFeeds.apod(apodEntry("")).isEmpty())
        assertEquals(1, WallFeeds.apod(apodEntry(image, title = "APOD")).size)
    }

    @Test fun apodTitleWithoutADatePrefixIsKeptWhole() {
        assertEquals("APOD: Sans date — NASA APOD", WallFeeds.apod(apodEntry("https://x/a.jpg", title = "APOD: Sans date")).single().caption)
    }

    @Test fun apodCreditsLoseTheirLabelAndAreCut() {
        val credits = WallFeeds.apodCredits("""[
          {"id":1,"nasa_hds_core_meta_image_credit":"Image Credit: Jane Doe"},
          {"id":2,"nasa_hds_core_meta_image_credit":"Credit &amp; Copyright: <a href=\"x\">John Roe</a>"},
          {"id":3,"nasa_hds_core_meta_image_credit":"Image Credit and Copyright: A. Name"},
          {"id":4,"nasa_hds_core_meta_image_credit":"${"Very Long Name ".repeat(10)}"},
          {"id":5,"nasa_hds_core_meta_image_credit":null},
          {"id":6},
          {"id":7,"nasa_hds_core_meta_image_credit":"   "},
          "junk"]""")
        assertEquals(mapOf(1 to "Jane Doe", 2 to "John Roe", 3 to "A. Name"), credits.filterKeys { it <= 3 })
        assertTrue(credits.getValue(4).length <= 40)
        assertEquals(setOf(1, 2, 3, 4), credits.keys)
    }

    @Test fun theCreditsRequestListsTheMediaIdsAndSkipsMissingOnes() {
        val json = """[{"featured_media":5},{"featured_media":0},{"title":"x"},{"featured_media":9}]"""
        assertEquals("https://science.nasa.gov/wp-json/wp/v2/media?include=5,9&per_page=2&_fields=id,nasa_hds_core_meta_image_credit", WallFeeds.apodCreditsUrl(json))
    }

    @Test fun apodApiNeedsAnImageWithAnAddress() {
        val json = """[{"media_type":"image","title":"A","url":""},{"media_type":"image","title":"B"},{"media_type":"video","title":"C","url":"https://v/c"},
          {"media_type":"image","title":"D","url":"https://i/d.jpg","date":"2020-01-01"},{"title":"E","url":"https://i/e.jpg"}]"""
        assertEquals(listOf("https://i/d.jpg"), WallFeeds.apodApi(json).map { it.url })
        assertEquals("apod-2020-01-01", WallFeeds.apodApi(json).single().id)
        assertEquals("D — NASA APOD", WallFeeds.apodApi(json).single().caption)
    }

    // ---- the Met ----

    private fun met(measure: String?, pd: Boolean = true, title: String = "Paysage", artist: String = "", date: String = ""): String {
        val measurements = if (measure == null) "" else ""","measurements":$measure"""
        return """{"objectID":9,"isPublicDomain":$pd,"primaryImage":"https://i.met/9.jpg","title":"$title","artistDisplayName":"$artist","objectDate":"$date"$measurements}"""
    }

    private fun overall(w: Number, h: Number) = """[{"elementName":"Overall","elementMeasurements":{"Width":$w,"Height":$h}}]"""

    @Test fun metProportionLimitsAreExact() {
        assertNotNull(WallFeeds.metObject(met(overall(50, 40))))         // 1.25:1, the squarest allowed
        assertNull(WallFeeds.metObject(met(overall(49.9, 40))))
        assertNotNull(WallFeeds.metObject(met(overall(96, 40))))         // 2.4:1, the widest allowed
        assertNull(WallFeeds.metObject(met(overall(96.1, 40))))
        assertNull("a panorama", WallFeeds.metObject(met(overall(300, 40))))
        assertNull("upright", WallFeeds.metObject(met(overall(30, 60))))
    }

    @Test fun metWithoutUsableMeasurementsIsKept() {
        assertNotNull(WallFeeds.metObject(met(null)))
        assertNotNull(WallFeeds.metObject(met("[]")))
        assertNotNull(WallFeeds.metObject(met("""[{"elementName":"Overall"}]""")))
        assertNotNull(WallFeeds.metObject(met("""[{"elementName":"Overall","elementMeasurements":{"Width":50}}]""")))
        assertNotNull(WallFeeds.metObject(met("""[{"elementName":"Overall","elementMeasurements":null}]""")))
        assertNotNull(WallFeeds.metObject(met(overall(0, 0))))
    }

    @Test fun metPrefersTheOverallMeasurementAndFallsBackToTheFirst() {
        val frameFirst = """[{"elementName":"Frame","elementMeasurements":{"Width":30,"Height":60}},
          {"elementName":"Overall","elementMeasurements":{"Width":60,"Height":40}}]"""
        assertNotNull("the overall size is the picture's, not the frame's", WallFeeds.metObject(met(frameFirst)))
        val noOverall = """[{"elementName":"Frame","elementMeasurements":{"Width":30,"Height":60}},{"elementName":"Mat","elementMeasurements":{"Width":60,"Height":40}}]"""
        assertNull("without an overall one, the first", WallFeeds.metObject(met(noOverall)))
    }

    @Test fun metCreditsTitleArtistAndDateAsTheyExist() {
        assertEquals("Paysage · The Met · Domaine public", WallFeeds.metObject(met(null))!!.caption)
        assertEquals("Paysage — Corot · The Met · Domaine public", WallFeeds.metObject(met(null, artist = "Corot"))!!.caption)
        assertEquals("Paysage — (1840) · The Met · Domaine public", WallFeeds.metObject(met(null, date = "1840"))!!.caption)
        assertEquals("Paysage — Corot (1840) · The Met · Domaine public", WallFeeds.metObject(met(null, artist = "Corot", date = "1840"))!!.caption)
        assertEquals("met-9", WallFeeds.metObject(met(null))!!.id)
    }

    @Test fun metIdsKeepOnlyPositiveNumbers() {
        assertEquals(listOf(5, 7), WallFeeds.metIds("""{"objectIDs":[5,0,-1,7]}"""))
        assertEquals(listOf(5), WallFeeds.metIds("""{"objectIDs":[5,"x",null]}"""))
    }

    @Test fun metPickNeverRepeatsAnIdAndWrapsAround() {
        val ids = (1..10).toList()
        for (d in 0..200) for (n in listOf(1, 3, 10, 24)) {
            val pick = WallFeeds.metPick(ids, d, n)
            assertEquals("day $d n $n", minOf(n, ids.size), pick.size)
            assertEquals("day $d n $n", pick.size, pick.toSet().size)
            assertTrue(ids.containsAll(pick))
        }
        assertTrue(WallFeeds.metPick(ids, 3, 0).isEmpty())
        assertEquals(listOf(7), WallFeeds.metPick(listOf(7), 99, 5))
        assertEquals("the same day gives the same ids", WallFeeds.metPick(ids, 42, 4), WallFeeds.metPick(ids, 42, 4))
    }

    // ---- the Art Institute ----

    private fun artic(entry: String, config: String = "") = """{"data":[$entry]$config}"""

    @Test fun articUsesTheDefaultIiifServerWhenNoneIsGiven() {
        val pic = WallFeeds.artic(artic("""{"id":1,"title":"T","artist_title":"A","image_id":"abc","is_public_domain":true}""")).single()
        assertEquals("https://www.artic.edu/iiif/2/abc/full/1686,/0/default.jpg", pic.url)
        val custom = WallFeeds.artic(artic("""{"id":1,"title":"T","image_id":"abc"}""", ""","config":{"iiif_url":"https://cdn.example/iiif/2"}""")).single()
        assertEquals("https://cdn.example/iiif/2/abc/full/1686,/0/default.jpg", custom.url)
        val blank = WallFeeds.artic(artic("""{"id":1,"title":"T","image_id":"abc"}""", ""","config":{"iiif_url":""}""")).single()
        assertTrue(blank.url.startsWith("https://www.artic.edu/iiif/2/"))
    }

    @Test fun articPublicDomainIsTheDefaultButFalseExcludes() {
        assertEquals(1, WallFeeds.artic(artic("""{"id":1,"title":"T","image_id":"a"}""")).size)
        assertEquals(0, WallFeeds.artic(artic("""{"id":1,"title":"T","image_id":"a","is_public_domain":false}""")).size)
        assertEquals(0, WallFeeds.artic(artic("""{"id":1,"title":"T","image_id":"","is_public_domain":true}""")).size)
        assertEquals(0, WallFeeds.artic(artic("""{"id":1,"title":"T","image_id":null}""")).size)
    }

    @Test fun articSizeLimitsAreExactAndNoThumbnailMeansKeep() {
        fun n(w: Int, h: Int) = WallFeeds.artic(artic("""{"id":1,"title":"T","image_id":"a","thumbnail":{"width":$w,"height":$h}}""")).size
        assertEquals(1, n(1686, 1348))
        assertEquals(0, n(1686, 1349))
        assertEquals(0, n(1685, 1000))
        assertEquals(1, n(4044, 1685))      // 12:5
        assertEquals(0, n(4044, 1684))
        assertEquals(1, WallFeeds.artic(artic("""{"id":1,"title":"T","image_id":"a","thumbnail":null}""")).size)
        assertEquals(1, WallFeeds.artic(artic("""{"id":1,"title":"T","image_id":"a"}""")).size)
        assertEquals("an unsized thumbnail is not a reason to drop it", 1, n(0, 0))
    }

    @Test fun articArtistComesFromTheShortFieldThenTheFirstLineOfTheLongOne() {
        fun caption(fields: String) = WallFeeds.artic(artic("""{"id":1,"title":"T","image_id":"a",$fields}""")).single().caption
        assertEquals("T — Monet · Art Institute of Chicago · Domaine public", caption(""""artist_title":"Monet","artist_display":"Claude Monet (French, 1840-1926)""""))
        assertEquals("T — Claude Monet · Art Institute of Chicago · Domaine public", caption(""""artist_title":null,"artist_display":"Claude Monet (French, 1840-1926)""""))
        assertEquals("T — Claude Monet · Art Institute of Chicago · Domaine public", caption(""""artist_display":"Claude Monet\nFrench, 1840-1926""""))
        assertEquals("T · Art Institute of Chicago · Domaine public", caption(""""artist_display":"""""))
    }

    // ---- Wallhaven, cats, dogs ----

    @Test fun wallhavenFileSizeLimitIsExact() {
        fun n(size: Long) = WallFeeds.wallhaven("""{"data":[{"id":"abc123","path":"https://w/abc123.jpg","file_size":$size}]}""").size
        assertEquals(1, n(8_000_000))
        assertEquals(0, n(8_000_001))
        assertEquals(1, n(0))
        assertEquals("no size given: kept", 1, WallFeeds.wallhaven("""{"data":[{"id":"abc123","path":"https://w/abc123.jpg"}]}""").size)
    }

    @Test fun wallhavenNeedsAnIdAndAPath() {
        val json = """{"data":[{"id":"","path":"https://w/a.jpg"},{"path":"https://w/b.jpg"},{"id":"cccccc","path":""},{"id":"dddddd"},{"id":null,"path":null},
          {"id":"eeeeee","path":"https://w/e.jpg"}]}"""
        assertEquals(listOf("wh-eeeeee"), WallFeeds.wallhaven(json).map { it.id })
    }

    @Test fun catIdsAreFilesafeAndBoundedAndFallBackToTheAddressHash() {
        fun id(entry: String) = WallFeeds.cats("[$entry]").single().id
        assertEquals("cat-abc_DEF-12", id("""{"id":"abc.DEF-12","url":"https://c/1.jpg","width":2000,"height":1200}"""))
        assertEquals("cat-" + "x".repeat(60), id("""{"id":"${"x".repeat(100)}","url":"https://c/1.jpg","width":2000,"height":1200}"""))
        assertTrue(id("""{"url":"https://c/1.jpg","width":2000,"height":1200}""").matches(Regex("cat-[0-9a-f]{12}")))
        assertTrue(id("""{"id":"","url":"https://c/1.jpg","width":2000,"height":1200}""").matches(Regex("cat-[0-9a-f]{12}")))
        assertNotEquals(id("""{"url":"https://c/1.jpg","width":2000,"height":1200}"""), id("""{"url":"https://c/2.jpg","width":2000,"height":1200}"""))
    }

    @Test fun catSizeLimitsAreExactAndAnUnsizedCatIsDropped() {
        fun n(w: Int, h: Int) = WallFeeds.cats("""[{"id":"a","url":"https://c/1.jpg","width":$w,"height":$h}]""").size
        assertEquals(1, n(1280, 1024))
        assertEquals(0, n(1280, 1025))
        assertEquals(0, n(1279, 800))
        assertEquals(0, n(0, 0))
        assertEquals(0, WallFeeds.cats("""[{"id":"a","url":"https://c/1.jpg"}]""").size)
    }

    @Test fun dogsKeepOnlyHttpAddressesThatAreNotImageNetFiles() {
        val json = """{"message":["https://images.dog.ceo/breeds/hound-afghan/n02088094_1003.jpg","http://images.dog.ceo/breeds/pug/pug1.jpg",
          "ftp://x/y.jpg","/relative.jpg","","https://images.dog.ceo/breeds/akita/N02085620_1.JPG","https://images.dog.ceo/breeds/akita/akita_n02085620_1.jpg"]}"""
        assertEquals(listOf("http://images.dog.ceo/breeds/pug/pug1.jpg", "https://images.dog.ceo/breeds/akita/akita_n02085620_1.jpg"),
            WallFeeds.dogs(json).map { it.url })
    }

    @Test fun dogIdsAreFilesafeAndBounded() {
        val long = "https://images.dog.ceo/breeds/" + "a".repeat(100) + "/x.jpg"
        val ids = WallFeeds.dogs("""{"message":["$long","https://images.dog.ceo/breeds/bulldog-french/f_1.jpg"]}""").map { it.id }
        assertEquals("dog-" + "a".repeat(60), ids[0])
        assertEquals("dog-bulldog-french_f_1", ids[1])
    }

    // ---- the feed lists and their URLs ----

    private val sources = listOf(Wallpapers.SCENERY, Wallpapers.FRESH, Wallpapers.ART, Wallpapers.ANIME_BACKUP)

    @Test fun everySourceListsDistinctWebAddressesEveryDayOfTheYear() {
        for (source in sources) for (d in day..day + 366) {
            val feeds = WallFeeds.feeds(source, d)
            assertTrue("$source day $d", feeds.size >= 2)
            assertEquals("$source day $d has a repeat", feeds.size, feeds.toSet().size)
            feeds.forEach { assertTrue(it, it.startsWith("https://") || it.startsWith("http://")) }
        }
    }

    @Test fun theMainFeedsTradePlacesEveryDayButAreAlwaysThere() {
        for (source in listOf(Wallpapers.SCENERY, Wallpapers.FRESH, Wallpapers.ART)) {
            val hostsByDay = (0 until 8).map { WallFeeds.feeds(source, day + it).take(2).map { u -> URL(u).host } }
            for (hosts in hostsByDay) assertEquals(2, hosts.toSet().size)
            assertEquals("$source: both orders occur", 2, hostsByDay.map { it.first() }.toSet().size)
            assertEquals("$source: the same two hosts every day", 1, hostsByDay.map { it.toSet() }.toSet().size)
            // The order is the day's, so two days far apart with the same parity agree.
            assertEquals(hostsByDay[0], hostsByDay[2])
        }
    }

    @Test fun unknownSourcesHaveNoFeeds() {
        for (s in listOf("", "daily", "mine", "custom", "anime", "SCENERY", "scenery ")) assertTrue(s, WallFeeds.feeds(s, day).isEmpty())
    }

    private fun intParam(url: String, name: String) = Regex("[?&]$name=(\\d+)").find(url)!!.groupValues[1].toInt()

    @Test fun pagedUrlsStayInTheirRangeForAYearsOfDays() {
        for (d in 0..3000) {
            assertTrue(WallFeeds.apodUrl(d), intParam(WallFeeds.apodUrl(d), "page") in 1..400)
            assertTrue(WallFeeds.articUrl(d), intParam(WallFeeds.articUrl(d), "page") in 1..5)
            assertTrue(WallFeeds.animalsUrl(d), intParam(WallFeeds.animalsUrl(d), "page") in 1..5)
            assertTrue(WallFeeds.animeUrl(d), intParam(WallFeeds.animeUrl(d), "page") in 1..20)
            assertTrue(WallFeeds.metUrl(d), intParam(WallFeeds.metUrl(d), "departmentId") in setOf(1, 11))
            val start = intParam(WallFeeds.feeds(Wallpapers.SCENERY, d).last(), "start")
            assertTrue("day $d start $start", start in 0..228 && start % 12 == 0)
        }
    }

    @Test fun theUrlsOfOneDayAreTheSameEveryTimeAndDifferFromTheNextDaysWhereTheyShould() {
        assertEquals(WallFeeds.potdUrl(day), WallFeeds.potdUrl(day))
        assertNotEquals(WallFeeds.potdUrl(day), WallFeeds.potdUrl(day + 1))
        assertNotEquals(WallFeeds.apodUrl(day), WallFeeds.apodUrl(day + 1))
        assertNotEquals(WallFeeds.metUrl(day), WallFeeds.metUrl(day + 1))
        assertNotEquals(WallFeeds.animalsUrl(day), WallFeeds.animalsUrl(day + 1))
    }

    @Test fun commonsDatesSurviveTheLeapDay() {
        val leap = LocalDate.of(2024, 2, 29).toEpochDay().toInt()
        val titles = WallFeeds.potdUrl(leap).substringAfter("&titles=").split("%7C")
        assertEquals(27, titles.size)
        assertTrue("Template:Potd/2024-02-29" in titles)
        assertTrue("Template:Potd/2023-02-28" in titles)      // not a 29th: the date falls back
        assertTrue("Template:Potd/2004-02-29" in titles)
        assertEquals("no date twice", titles.size, titles.toSet().size)
    }

    @Test fun commonsAsksForADayWhateverTheEpochIs() {
        for (d in listOf(0, 1, 365, 10_000, 20_000, 30_000)) assertEquals("day $d", 27, WallFeeds.potdUrl(d).substringAfter("&titles=").split("%7C").size)
    }

    // ---- routing ----

    @Test fun eachReaderParsesItsOwnSite() {
        fun read(link: String, body: String) = WallFeeds.reader(link)!!(body)
        assertEquals("dog-", read(WallFeeds.DOGS, """{"message":["https://images.dog.ceo/breeds/pug/p.jpg"]}""").single().id.take(4))
        assertEquals("cat-a", read(WallFeeds.CATS, """[{"id":"a","url":"https://c/1.jpg","width":2000,"height":1200}]""").single().id)
        assertEquals("wh-abcdef", read(WallFeeds.animalsUrl(day), """{"data":[{"id":"abcdef","path":"https://w/a.jpg"}]}""").single().id)
        assertEquals("aic-5", read(WallFeeds.articUrl(day), """{"data":[{"id":5,"title":"T","image_id":"x"}]}""").single().id)
        assertEquals("apod-2020-01-01", read(WallFeeds.APOD_API, """[{"media_type":"image","title":"T","url":"https://i/a.jpg","date":"2020-01-01"}]""").single().id)
        assertTrue(read(WallFeeds.potdUrl(day), commons(2000, 1200)).single().id.startsWith("wm-"))
    }

    @Test fun aLinkThatIsNotAFeedHasNoReader() {
        assertNull(WallFeeds.reader(""))
        assertNull(WallFeeds.reader("not a url"))
        assertNull(WallFeeds.reader("https://example.org/pic.jpg"))
        assertNull(WallFeeds.reader("https://wallhaven.cc/"))
        assertNull(WallFeeds.reader("https://wallhaven.cc/search?q=x"))
        assertNull(WallFeeds.reader("https://dog.ceo.evil.example/api/"))
        assertNull(WallFeeds.reader("https://www.bing.com/HPImageArchive.aspx"))
    }

    @Test fun wikimediaAndTheArtInstituteIdentifyThemselvesAndEveryoneElseDoesNot() {
        for (host in listOf("commons.wikimedia.org", "upload.wikimedia.org", "thumb.wikimedia.org", "api.artic.edu", "www.artic.edu"))
            assertEquals(host, WallFeeds.AGENT, WallFeeds.agent(host))
        for (host in listOf("", "wikimedia.org.example.com", "artic.edu.example.com", "wallhaven.cc", "science.nasa.gov", "collectionapi.metmuseum.org"))
            assertNotEquals(host, WallFeeds.AGENT, WallFeeds.agent(host))
        assertTrue(WallFeeds.AGENT.startsWith("canvasOS/"))
    }

    @Test fun theTwoStepFeedsAreRecognisedByTheirAddress() {
        assertTrue(WallFeeds.isMet(WallFeeds.metUrl(day)))
        assertTrue(WallFeeds.isMet(WallFeeds.metObjectUrl(436558)))
        assertFalse(WallFeeds.isMet("http://collectionapi.metmuseum.org/x"))
        assertFalse(WallFeeds.isMet("https://metmuseum.org/"))
        assertTrue(WallFeeds.isApod(WallFeeds.apodUrl(day)))
        assertFalse(WallFeeds.isApod(WallFeeds.APOD_API))
        assertFalse(WallFeeds.isApod("https://science.nasa.gov/image"))
        assertEquals("https://collectionapi.metmuseum.org/public/collection/v1/objects/12", WallFeeds.metObjectUrl(12))
    }

    // ---- picture ids ----

    @Test fun aPictureIdNeedsSixCharactersAndADigit() {
        assertEquals("abc123", WallFeeds.pictureId("https://x/abc123.jpg"))
        assertEquals("abcde1", WallFeeds.pictureId("https://x/abcde1.jpg"))
        assertNull(WallFeeds.pictureId("https://x/abc12.jpg"))
        assertNull(WallFeeds.pictureId("https://x/abcdef.jpg"))
        assertNull(WallFeeds.pictureId(""))
        assertNull(WallFeeds.pictureId("https://x/"))
        assertEquals("abc123", WallFeeds.pictureId("abc123.jpg"))
        assertEquals("abc123", WallFeeds.pictureId("https://x/a-b-c-abc123.jpg?size=full"))
        assertEquals("abc123", WallFeeds.pictureId("https://x/abc123_big.jpg"))
    }

    @Test fun twoAddressesOfOneWallhavenPictureShareAnId() {
        assertEquals(WallFeeds.pictureId("https://w.wallhaven.cc/full/9o/wallhaven-9o2mwd.jpg"), WallFeeds.pictureId("https://th.wallhaven.cc/lg/9o/9o2mwd.jpg"))
    }

    // ---- picture addresses in text ----

    @Test fun imageAddressesAreFoundWhateverTheirCaseAndQuery() {
        val found = WallFeeds.IMAGE_URL.findAll("""a https://x.org/A.JPG b http://x.org/b.jpeg?w=1&h=2 c https://x.org/c.WebP d https://x.org/d.png""").map { it.value }.toList()
        assertEquals(listOf("https://x.org/A.JPG", "http://x.org/b.jpeg?w=1&h=2", "https://x.org/c.WebP", "https://x.org/d.png"), found)
    }

    @Test fun nonPicturesAndSchemelessAddressesAreNotFound() {
        val text = """https://x.org/a.gif https://x.org/b.svg https://x.org/c.html //x.org/d.jpg x.org/e.jpg ftp://x.org/f.jpg"""
        assertEquals(emptyList<String>(), WallFeeds.IMAGE_URL.findAll(text).map { it.value }.toList())
    }

    @Test fun anAddressStopsAtQuotesBracketsAndSpaces() {
        val found = WallFeeds.IMAGE_URL.findAll("""<img src="https://x.org/a.jpg"> ('https://x.org/b.png') <https://x.org/c.webp> https://x.org/d.jpg, e""").map { it.value }.toList()
        assertEquals(listOf("https://x.org/a.jpg", "https://x.org/b.png", "https://x.org/c.webp", "https://x.org/d.jpg"), found)
    }

    // ---- text helpers ----

    @Test fun plainDecodesEntitiesOnceAndKeepsWhatItDoesNotKnow() {
        assertEquals("a & b", WallFeeds.plain("a &amp; b"))
        assertEquals("&lt;", WallFeeds.plain("&amp;lt;"))
        assertEquals("’ ’ ’", WallFeeds.plain("&#8217; &#x2019; &#X2019;"))
        assertEquals("&bogus; &#99999999999; &#xZZ;", WallFeeds.plain("&bogus; &#99999999999; &#xZZ;"))
        assertEquals("« a »", WallFeeds.plain("&laquo;&nbsp;a&nbsp;&raquo;"))
        assertEquals("1 < 2", WallFeeds.plain("1 &lt; 2"))
        assertEquals("AT&T", WallFeeds.plain("AT&T"))
    }

    @Test fun plainDropsTagsTurnsBlocksIntoSpacesAndCollapsesWhiteSpace() {
        assertEquals("", WallFeeds.plain(""))
        assertEquals("", WallFeeds.plain(" \n\t "))
        assertEquals("a b", WallFeeds.plain("a<br>b"))
        assertEquals("a b", WallFeeds.plain("a<BR/>b"))
        assertEquals("a b c", WallFeeds.plain("<ul><li>a</li><li>b</li><li>c</li></ul>"))
        assertEquals("ab", WallFeeds.plain("a<b></b>b"))
        assertEquals("a b", WallFeeds.plain("a<!-- hidden -->   \n b"))
        assertEquals("text", WallFeeds.plain("<span class=\"x\">text</span>"))
    }

    @Test fun shortenKeepsTextThatFitsAndCutsTheRestWithAnEllipsis() {
        assertEquals("", WallFeeds.shorten("", 10))
        assertEquals("exactly ten", WallFeeds.shorten("exactly ten", 11))
        assertEquals("abcdefghi…", WallFeeds.shorten("abcdefghijklmnopqrstuvwxyz", 10))
        assertEquals("one two three…", WallFeeds.shorten("one two three four five six", 15))
        assertEquals("trailing punctuation goes before the ellipsis", "alpha, beta…", WallFeeds.shorten("alpha, beta, gamma, delta", 13))
    }

    @Test fun shortenPrefersAFirstSentenceOnlyWhenItIsLongEnoughAndFits() {
        val s = "This sentence is long enough. And then some more text follows it."
        assertEquals("This sentence is long enough", WallFeeds.shorten(s, 40))
        assertEquals("a first sentence that does not fit in the limit is not used", "This sentence is long…", WallFeeds.shorten(s, 28))
        val short = WallFeeds.shorten("Short. And a much longer second sentence follows here.", 30)
        assertTrue(short, short.length <= 30 && short.endsWith("…"))
    }

    @Test fun shortenNeverExceedsTheLimit() {
        val texts = listOf("a".repeat(500), "word ".repeat(100), "Une phrase. ".repeat(50), "x, ".repeat(100), "é".repeat(77), "a b ".repeat(30))
        for (t in texts) for (max in listOf(1, 2, 5, 10, 20, 30, 64, 80)) {
            val cut = WallFeeds.shorten(t, max)
            assertTrue("max $max gave ${cut.length}: $cut", cut.length <= max)
        }
    }

    @Test fun interleaveHandlesEmptyAndUnevenLists() {
        assertEquals(listOf(1, 2), WallFeeds.interleave(listOf(emptyList(), listOf(1, 2))))
        assertEquals(listOf(1, 2), WallFeeds.interleave(listOf(listOf(1, 2), emptyList())))
        assertEquals(listOf(7), WallFeeds.interleave(listOf(emptyList(), emptyList(), listOf(7))))
        assertEquals(listOf(1, 10, 2, 3, 100, 101, 200), WallFeeds.interleave(listOf(listOf(1, 2, 3), listOf(10), listOf(100, 101), listOf(200))))
        assertEquals(listOf(1, 1, 1, 1), WallFeeds.interleave(listOf(listOf(1, 1), listOf(1, 1))))
        assertEquals("nothing is lost", 9, WallFeeds.interleave(listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))).size)
    }
}
