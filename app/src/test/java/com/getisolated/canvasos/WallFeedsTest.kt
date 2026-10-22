package com.getisolated.canvasos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL
import java.time.LocalDate

/** Samples are trimmed from the real answers of each API (October 2026). */
class WallFeedsTest {
    private val day = LocalDate.of(2026, 10, 22).toEpochDay().toInt()

    @Test fun commonsKeepsLargeWidePhotosWithTheirCredits() {
        val json = """{"batchcomplete":true,"query":{"pages":[
          {"pageid":1,"ns":6,"title":"File:Monarch Butterfly.jpg","imageinfo":[{"width":2800,"height":1850,"mime":"image/jpeg",
            "thumburl":"https://thumb.wikimedia.org/wikipedia/commons/thumb/a/ab/Monarch.jpg/1920px-Monarch.jpg?utm_source=commons.wikimedia.org",
            "url":"https://upload.wikimedia.org/wikipedia/commons/a/ab/Monarch.jpg",
            "extmetadata":{"ObjectName":{"value":"Monarch Butterfly"},
              "ImageDescription":{"value":"Papillon <a href=\"https://fr.wikipedia.org/wiki/Monarque\" class=\"extiw\">monarque</a> &amp; asclépiade"},
              "Artist":{"value":"<bdi><a href=\"//commons.wikimedia.org/wiki/User:X\"><span title=\"photographe\">Jane Doe</span></a></bdi>"},
              "LicenseShortName":{"value":"CC BY-SA 4.0","hidden":""}}}]},
          {"pageid":2,"ns":6,"title":"File:Bell tower.jpg","imageinfo":[{"width":2968,"height":4563,"thumburl":"https://x.org/t.jpg","mime":"image/jpeg"}]},
          {"pageid":3,"ns":6,"title":"File:Tanzania map-fr.svg","imageinfo":[{"width":2064,"height":1200,"thumburl":"https://x.org/m.png","mime":"image/svg+xml"}]},
          {"pageid":4,"ns":6,"title":"File:Panorama.jpg","imageinfo":[{"width":11066,"height":2313,"thumburl":"https://x.org/p.jpg","mime":"image/jpeg"}]},
          {"pageid":5,"ns":6,"title":"File:Crete komos beach.jpg","imageinfo":[{"width":1024,"height":768,"thumburl":"https://x.org/s.jpg","mime":"image/jpeg"}]}
        ]}}"""
        val pics = WallFeeds.potd(json)
        assertEquals(1, pics.size)
        assertEquals("https://thumb.wikimedia.org/wikipedia/commons/thumb/a/ab/Monarch.jpg/1920px-Monarch.jpg?utm_source=commons.wikimedia.org", pics[0].url)
        assertEquals("Papillon monarque & asclépiade — Jane Doe · Wikimedia Commons · CC BY-SA 4.0", pics[0].caption)
        assertTrue(pics[0].id.matches(Regex("wm-[0-9a-f]{12}")))
        assertEquals("the id stays the same from one day to the next", pics[0].id, WallFeeds.potd(json)[0].id)
    }

    @Test fun commonsOlderFormatAndFallbackCaptions() {
        val json = """{"query":{"pages":{
          "42":{"title":"File:Crete beach.jpg","imageinfo":[{"width":3000,"height":2000,"url":"https://upload.wikimedia.org/c.jpg",
            "mime":"image/jpeg","extmetadata":{"ImageDescription":{"value":"Description: Komos Beach"},"LicenseShortName":{"value":"Public domain"}}}]},
          "43":{"title":"File:Oka River.jpg","imageinfo":[{"width":4836,"height":3016,"url":"https://upload.wikimedia.org/d.jpg","mime":"image/jpeg"}]}}}}"""
        val pics = WallFeeds.potd(json)
        assertEquals(setOf("Komos Beach · Wikimedia Commons · Domaine public", "Oka River · Wikimedia Commons"), pics.map { it.caption }.toSet())
        assertEquals("without a thumbnail, the original", setOf("https://upload.wikimedia.org/c.jpg", "https://upload.wikimedia.org/d.jpg"),
            pics.map { it.url }.toSet())
    }

    @Test fun commonsAsksForTheLastWeekAndThisDayInPastYears() {
        val url = WallFeeds.potdUrl(day)
        assertTrue(url.startsWith("https://commons.wikimedia.org/w/api.php?action=query&format=json"))
        val titles = url.substringAfter("&titles=").split("%7C")
        assertEquals(27, titles.size)
        assertTrue("Template:Potd/2026-10-22" in titles)
        assertTrue("Template:Potd/2026-10-16" in titles)
        assertFalse("Template:Potd/2026-10-15" in titles)
        assertTrue("Template:Potd/2025-10-22" in titles)
        assertTrue("Template:Potd/2006-10-22" in titles)
    }

    @Test fun apodFromScienceNasaGovSkipsSmallAndUprightAndAsksForAScreenSizedCopy() {
        val json = """[
          {"id":1239424,"date":"2010-06-02T00:00:00","title":{"rendered":"APOD: 2010 June 2 &#8211; A Twisted Meteor Trail Over Tenerife"},
           "featured_media":1239425,
           "featured_image_url":"https://assets.science.nasa.gov/content/dam/science/cds/apod/apod/2010/june/meteorwiggle_rendtel_big.jpg?w=3504&h=2336&fit=clip&crop=faces%2Cfocalpoint"},
          {"id":1239432,"date":"2010-06-06T00:00:00","title":{"rendered":"APOD: 2010 June 6 &#8211; Lunokhod: Reflections on a Moon Rover"},
           "featured_image_url":"https://assets.science.nasa.gov/content/dam/science/cds/apod/apod/2010/june/lunokhod_lavochkin_big.jpg?w=610&h=494&fit=clip"},
          {"id":1239401,"date":"2010-05-21T00:00:00","title":{"rendered":"APOD: 2010 May 21 &#8211; Calm, Crescent Moon, and Venus"},
           "featured_image_url":"https://assets.science.nasa.gov/content/dam/science/cds/apod/apod/2010/may/MoonVenus_heden.jpg?w=725&h=1200&fit=clip"},
          {"id":1290000,"date":"2026-07-18T00:05:00","title":{"rendered":"APOD: 2026 July 18 -Shadow and Rainbow"},"featured_media":1290001,
           "featured_image_url":"https://assets.science.nasa.gov/dynamicimage/assets/science/cds/apod/apod/2026/july/shadow.jpg?w=4032&h=3024&fit=clip"},
          {"id":5,"date":"2026-07-19T00:05:00","title":{"rendered":"Mars Rover News"},
           "featured_image_url":"https://assets.science.nasa.gov/dynamicimage/assets/science/x.jpg?w=4000&h=2250"}
        ]"""
        val meteor = "https://assets.science.nasa.gov/dynamicimage/assets/science/cds/apod/apod/2010/june/meteorwiggle_rendtel_big.jpg?w=1920&h=1080&fit=clip"
        val shadow = "https://assets.science.nasa.gov/dynamicimage/assets/science/cds/apod/apod/2026/july/shadow.jpg?w=1920&h=1080&fit=clip"
        assertEquals(listOf(
            FeedPic(meteor, "A Twisted Meteor Trail Over Tenerife — NASA APOD", "apod-2010-06-02"),
            FeedPic(shadow, "Shadow and Rainbow — NASA APOD", "apod-2026-07-18"),
        ), WallFeeds.apod(json))

        // The credits come from a second, optional request.
        assertEquals("https://science.nasa.gov/wp-json/wp/v2/media?include=1239425,1290001&per_page=2&_fields=id,nasa_hds_core_meta_image_credit",
            WallFeeds.apodCreditsUrl(json))
        val credits = WallFeeds.apodCredits("""[{"id":1290001,"nasa_hds_core_meta_image_credit":"Aldo Zanetti"},
          {"id":1306840,"nasa_hds_core_meta_image_credit":""},
          {"id":1239425,"nasa_hds_core_meta_image_credit":"<b> Image Credit: <\/b> <a href=\"https:\/\/www.nasa.gov\/\" target=\"_blank\">Apollo 11<\/a> , <a href=\"https:\/\/www.nasa.gov\/\">NASA<\/a>"}]""")
        assertEquals(mapOf(1290001 to "Aldo Zanetti", 1239425 to "Apollo 11, NASA"), credits)
        assertEquals(listOf("A Twisted Meteor Trail Over Tenerife — Apollo 11, NASA · NASA APOD", "Shadow and Rainbow — Aldo Zanetti · NASA APOD"),
            WallFeeds.apod(json, credits).map { it.caption })
        assertNull(WallFeeds.apodCreditsUrl("""[{"id":5,"title":{"rendered":"APOD"}}]"""))
    }

    @Test fun apodApiKeepsImagesAndSkipsVideosAndTheLogoPlaceholder() {
        val json = """[
          {"date":"2024-01-01","media_type":"image","title":"Night Sky","copyright":"\nJohn Doe\n",
           "hdurl":"https://apod.nasa.gov/apod/image/2401/sky.jpg","url":"https://apod.nasa.gov/apod/image/2401/sky_1024.jpg"},
          {"date":"2024-01-02","media_type":"video","title":"A Film","url":"https://www.youtube.com/embed/x"},
          {"date":"2024-01-03","media_type":"image","title":"Galaxy","url":"https://apod.nasa.gov/apod/image/2401/galaxy.jpg"},
          {"date":"2025-06-27","media_type":"image","title":"NASA Science","copyright":"Robert Eder","service_version":"v1",
           "hdurl":"https://science.nasa.gov/wp-content/themes/nasa-child/assets/images/nasa-logo@2x.png"}
        ]"""
        assertEquals(listOf(
            FeedPic("https://apod.nasa.gov/apod/image/2401/sky.jpg", "Night Sky — © John Doe · NASA APOD", "apod-2024-01-01"),
            FeedPic("https://apod.nasa.gov/apod/image/2401/galaxy.jpg", "Galaxy — NASA APOD", "apod-2024-01-03"),
        ), WallFeeds.apodApi(json))
    }

    @Test fun metSearchGivesIds() {
        assertEquals(listOf(436558, 437784), WallFeeds.metIds("""{"total":902,"objectIDs":[436558,437784]}"""))
        assertTrue(WallFeeds.metIds("""{"total":26,"objectIDs":null}""").isEmpty())
        assertTrue(WallFeeds.isMet(WallFeeds.metUrl(day)))
        assertTrue(WallFeeds.metUrl(day).contains("/public/collection/v1.1/search?"))
    }

    @Test fun metObjectsKeepPublicDomainLandscapesOnly() {
        val haarlem = """{"objectID":436558,"isPublicDomain":true,"primaryImage":"https://images.metmuseum.org/CRDImages/ep/original/DP146495.jpg",
            "primaryImageSmall":"https://images.metmuseum.org/CRDImages/ep/web-large/DP146495.jpg","title":"View of Haarlem and the Haarlemmer Meer",
            "artistDisplayName":"Jan van Goyen","objectDate":"1646","classification":"Paintings",
            "measurements":[{"elementName":"Overall","elementDescription":null,"elementMeasurements":{"Height":34.6,"Width":50.5}}]}"""
        assertEquals(FeedPic("https://images.metmuseum.org/CRDImages/ep/original/DP146495.jpg",
            "View of Haarlem and the Haarlemmer Meer — Jan van Goyen (1646) · The Met · Domaine public", "met-436558"),
            WallFeeds.metObject(haarlem))
        val madonna = """{"objectID":435682,"isPublicDomain":true,"primaryImage":"https://images.metmuseum.org/CRDImages/ep/original/EP258.jpg",
            "title":"Madonna and Child","artistDisplayName":"Boccaccio Boccaccino","objectDate":"1506–1518",
            "measurements":[{"elementName":"Overall","elementMeasurements":{"Height":51.8,"Width":37.1}}]}"""
        assertNull("upright", WallFeeds.metObject(madonna))
        assertNull("not public domain, though the search asked for it",
            WallFeeds.metObject("""{"objectID":438638,"isPublicDomain":false,"primaryImage":"","title":"Landscape"}"""))
        assertNull("no picture", WallFeeds.metObject("""{"objectID":1,"isPublicDomain":true,"primaryImage":"","title":"x"}"""))
        assertEquals("no size: kept, the download decides", "Landscape · The Met · Domaine public", WallFeeds.metObject(
            """{"objectID":2,"isPublicDomain":true,"primaryImage":"https://images.metmuseum.org/a.jpg","title":"Landscape","artistDisplayName":"","objectDate":""}""")?.caption)
    }

    @Test fun metPickMovesOnEachTimeASearchComesRound() {
        val ids = (1..30).toList()
        val first = WallFeeds.metPick(ids, 0, 24)
        assertEquals(24, first.size)
        assertEquals(1, first[0])
        // Eight searches take turns: day 8 has the first one again, a stretch further, wrapping round.
        val again = WallFeeds.metPick(ids, 8, 24)
        assertEquals(25, again[0])
        assertEquals(18, again.last())
        assertEquals(listOf(1, 2, 3), WallFeeds.metPick(listOf(1, 2, 3), 5, 24))
        assertTrue(WallFeeds.metPick(emptyList(), 3, 24).isEmpty())
    }

    @Test fun articUsesIiifAndCleanArtistNames() {
        val json = """{"preference":null,"pagination":{"total":62119,"limit":30,"offset":30,"total_pages":2071,"current_page":2},"data":[
          {"id":152747,"title":"York Harbor, Coast of Maine","artist_title":"Martin Johnson Heade","artist_display":"Martin Johnson Heade (American, 1819–1904)",
           "date_display":"1877","image_id":"3192ae4d-ffca-fe91-af12-2ca681f43e13","is_public_domain":true,
           "thumbnail":{"lqip":"data:image/gif;base64,R0lGODlhBQAFAPQAAD","width":3000,"height":1498,"alt_text":"A painting."}},
          {"id":196282,"title":"The Prairie on Fire","artist_title":null,"artist_display":"Alvan Fisher\nAmerican, 1792–1863","date_display":"1827",
           "image_id":"7951054b-f49a-6824-10ab-a3a301f39115","is_public_domain":true,"thumbnail":{"width":3000,"height":2162}},
          {"id":25183,"title":"Ono Falls on the Kisokaidō Road","artist_title":"Katsushika Hokusai","date_display":"c. 1833",
           "image_id":"6610ddf3-b8d5-a8fa-5a03-a803e7e76042","is_public_domain":true,"thumbnail":{"width":6714,"height":9874}},
          {"id":1,"title":"No picture","artist_title":"X","date_display":"","image_id":null,"is_public_domain":true,"thumbnail":null},
          {"id":2,"title":"Too small for 1686 px","artist_title":"Y","date_display":"","image_id":"abc","is_public_domain":true,"thumbnail":{"width":1200,"height":800}}
        ],"config":{"iiif_url":"https://www.artic.edu/iiif/2","website_url":"http://www.artic.edu"}}"""
        assertEquals(listOf(
            FeedPic("https://www.artic.edu/iiif/2/3192ae4d-ffca-fe91-af12-2ca681f43e13/full/1686,/0/default.jpg",
                "York Harbor, Coast of Maine — Martin Johnson Heade (1877) · Art Institute of Chicago · Domaine public", "aic-152747"),
            FeedPic("https://www.artic.edu/iiif/2/7951054b-f49a-6824-10ab-a3a301f39115/full/1686,/0/default.jpg",
                "The Prairie on Fire — Alvan Fisher (1827) · Art Institute of Chicago · Domaine public", "aic-196282"),
        ), WallFeeds.artic(json))
    }

    @Test fun wallhavenSkipsHugeOriginals() {
        val json = """{"data":[
          {"id":"zpzjlj","resolution":"2192x1233","file_size":552709,"path":"https://w.wallhaven.cc/full/zp/wallhaven-zpzjlj.jpg",
           "thumbs":{"large":"https://th.wallhaven.cc/lg/zp/zpzjlj.jpg","original":"https://th.wallhaven.cc/orig/zp/zpzjlj.jpg"}},
          {"id":"9o2mwd","resolution":"3840x2160","file_size":15023438,"path":"https://w.wallhaven.cc/full/9o/wallhaven-9o2mwd.png"}
        ],"meta":{"current_page":1,"last_page":1,"per_page":24,"total":2}}"""
        assertEquals(listOf(FeedPic("https://w.wallhaven.cc/full/zp/wallhaven-zpzjlj.jpg", "Wallhaven · wallhaven.cc/w/zpzjlj", "wh-zpzjlj")),
            WallFeeds.wallhaven(json))
        assertTrue(WallFeeds.wallhaven("""{"error":"rate limited"}""").isEmpty())
    }

    @Test fun dogsLeaveOutImageNetsSmallPictures() {
        val json = """{"message":["https://images.dog.ceo/breeds/german-shepherd/n02106662_19801.jpg",
          "https://images.dog.ceo/breeds/poodle-toy/cookie.jpg","https://images.dog.ceo/breeds/terrier-australian/fb_img_1596625396617.jpg"],
          "status":"success"}"""
        assertEquals(listOf(
            FeedPic("https://images.dog.ceo/breeds/poodle-toy/cookie.jpg", "Dog CEO", "dog-poodle-toy_cookie"),
            FeedPic("https://images.dog.ceo/breeds/terrier-australian/fb_img_1596625396617.jpg", "Dog CEO", "dog-terrier-australian_fb_img_1596625396617"),
        ), WallFeeds.dogs(json))
    }

    @Test fun catsKeepOnlyLargeWidePictures() {
        val json = """[{"id":"ds6","url":"https://cdn2.thecatapi.com/images/ds6.jpg","width":700,"height":467},
          {"id":"s42I_BL-a","url":"https://cdn2.thecatapi.com/images/s42I_BL-a.jpg","width":1620,"height":1080},
          {"id":"MjA4NzAwOQ","url":"https://cdn2.thecatapi.com/images/MjA4NzAwOQ.jpg","width":2000,"height":3000}]"""
        assertEquals(listOf(FeedPic("https://cdn2.thecatapi.com/images/s42I_BL-a.jpg", "TheCatAPI", "cat-s42I_BL-a")), WallFeeds.cats(json))
    }

    @Test fun pictureIdComesFromTheFileName() {
        assertEquals("yqkl97", WallFeeds.pictureId("https://w.wallhaven.cc/full/yq/wallhaven-yqkl97.jpg"))
        assertEquals("yqkl97", WallFeeds.pictureId("https://th.wallhaven.cc/lg/yq/yqkl97.jpg?v=2"))
        assertEquals("1916.1044", WallFeeds.pictureId("https://openaccess-cdn.clevelandart.org/1916.1044/1916.1044_web.jpg"))
        assertNull("shared by every IIIF picture", WallFeeds.pictureId("https://www.artic.edu/iiif/2/3192ae4d/full/843,/0/default.jpg"))
        assertNull("no digit, no id", WallFeeds.pictureId("https://example.org/photos/sunset.jpg"))
    }

    @Test fun imageUrlFindsPictureAddressesInAnyText() {
        val text = """{"a":"https:\/\/p0.qhimg.com\/bdr\/__85\/t01ab.jpg","b":"<img src='http://x.org/a/b.PNG?w=1920&h=1080'>",
            "c":"https://x.org/anim.gif","d":"(https://x.org/c.webp)"}""".replace("\\/", "/")
        assertEquals(listOf("https://p0.qhimg.com/bdr/__85/t01ab.jpg", "http://x.org/a/b.PNG?w=1920&h=1080", "https://x.org/c.webp"),
            WallFeeds.IMAGE_URL.findAll(text).map { it.value }.toList())
    }

    @Test fun plainTextAndShortening() {
        assertEquals("Un petit Syrphe au soleil (femelle)",
            WallFeeds.plain("Un petit <a href=\"https://fr.wikipedia.org/wiki/Syrphidae\" class=\"extiw\">Syrphe</a> au soleil (femelle)"))
        assertEquals("a b & c’d", WallFeeds.plain("<p>a</p><p>b</p> &amp; c&#8217;d&nbsp;"))
        assertEquals("Short", WallFeeds.shorten("Short", 10))
        assertEquals("First sentence is here", WallFeeds.shorten("First sentence is here. Then a much longer second one follows.", 40))
        val cut = WallFeeds.shorten("word ".repeat(30).trim(), 40)
        assertTrue(cut, cut.length <= 40 && cut.endsWith("word…"))
    }

    @Test fun interleaveMixesTheFirstTwoThenAppendsTheRest() {
        assertEquals(listOf(1, 10, 2, 20, 3, 4, 100), WallFeeds.interleave(listOf(listOf(1, 2, 3, 4), listOf(10, 20), listOf(100))))
        assertEquals(listOf(1, 2), WallFeeds.interleave(listOf(listOf(1, 2))))
        assertTrue(WallFeeds.interleave(emptyList<List<Int>>()).isEmpty())
    }

    @Test fun feedsTryTheNewSourcesFirstAnd360Last() {
        for (d in listOf(day, day + 1)) {
            val scenery = WallFeeds.feeds(Wallpapers.SCENERY, d)
            assertEquals(setOf("commons.wikimedia.org", "science.nasa.gov"), scenery.take(2).map { URL(it).host }.toSet())
            assertEquals(listOf(WallFeeds.APOD_API, WallFeeds.PICSUM), scenery.subList(2, 4))
            assertEquals(2, scenery.drop(4).count { "360.cn" in it })
            assertEquals(6, scenery.size)

            val fresh = WallFeeds.feeds(Wallpapers.FRESH, d)
            assertEquals(setOf("wallhaven.cc", "api.thecatapi.com"), fresh.take(2).map { URL(it).host }.toSet())
            assertEquals(WallFeeds.DOGS, fresh[2])
            assertTrue(fresh.drop(3).all { "360.cn" in it })

            val art = WallFeeds.feeds(Wallpapers.ART, d)
            assertEquals(setOf("collectionapi.metmuseum.org", "api.artic.edu"), art.take(2).map { URL(it).host }.toSet())
            assertEquals(listOf("openaccess-api.clevelandart.org", "wallhaven.cc"), art.drop(2).map { URL(it).host })

            val anime = WallFeeds.feeds(Wallpapers.ANIME_BACKUP, d)
            assertEquals("wallhaven.cc", URL(anime[0]).host)
            assertTrue("360.cn" in anime.last())
            assertTrue(anime.none { "alcy" in it })
        }
        assertNotEquals("the lead changes daily", URL(WallFeeds.feeds(Wallpapers.SCENERY, day)[0]).host,
            URL(WallFeeds.feeds(Wallpapers.SCENERY, day + 1)[0]).host)
        assertTrue(WallFeeds.feeds(Wallpapers.MINE, day).isEmpty())
    }

    @Test fun readersAndAgentsByHost() {
        assertNotNull(WallFeeds.reader(WallFeeds.potdUrl(day)))
        assertNotNull(WallFeeds.reader(WallFeeds.APOD_API))
        assertTrue(WallFeeds.isApod(WallFeeds.apodUrl(day)))
        assertNull("two steps, see isApod", WallFeeds.reader(WallFeeds.apodUrl(day)))
        assertNotNull(WallFeeds.reader(WallFeeds.articUrl(day)))
        assertNotNull(WallFeeds.reader(WallFeeds.animalsUrl(day)))
        assertNotNull(WallFeeds.reader(WallFeeds.CATS))
        assertNotNull(WallFeeds.reader(WallFeeds.DOGS))
        assertNull("plain picture link", WallFeeds.reader(WallFeeds.PICSUM))
        assertNull("read like a custom link", WallFeeds.reader("http://wallpaper.apc.360.cn/index.php?c=WallPaper&a=getAppsByCategory&cid=9"))
        assertNull("a page, not the API", WallFeeds.reader("https://wallhaven.cc/w/zpzjlj"))
        assertNull("two steps, see isMet", WallFeeds.reader(WallFeeds.metUrl(day)))
        assertEquals(WallFeeds.AGENT, WallFeeds.agent("commons.wikimedia.org"))
        assertEquals(WallFeeds.AGENT, WallFeeds.agent("api.artic.edu"))
        assertEquals("unchanged for everyone else", "Mozilla/5.0 (Linux; Android 12) canvasOS", WallFeeds.agent("www.bing.com"))
    }
}
