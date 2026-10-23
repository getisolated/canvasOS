package com.getisolated.canvasos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the Unsplash topic parser: screen-width URLs that keep ixid, credits, ids, and the size and shape filters. */
class UnsplashFeedTest {
    private val json = """[
      {"id":"abc123","width":6000,"height":4000,"description":null,"alt_description":"a lake at dawn",
       "urls":{"raw":"https://images.unsplash.com/photo-1?ixid=M3w&ixlib=rb"},
       "links":{"download_location":"https://api.unsplash.com/photos/abc123/download?ixid=M3w"},
       "user":{"name":"Jane Doe"}},
      {"id":"small","width":1200,"height":800,"urls":{"raw":"https://images.unsplash.com/photo-2?ixid=x"},"links":{},"user":{"name":"X"}},
      {"id":"tall","width":4000,"height":6000,"urls":{"raw":"https://images.unsplash.com/photo-3?ixid=x"},"links":{},"user":{"name":"X"}}
    ]"""

    @Test fun keepsWideLargePhotosAtScreenWidth() {
        val pics = WallFeeds.unsplash(json, 3840)
        assertEquals(1, pics.size)
        val p = pics[0]
        assertEquals("us-abc123", p.id)
        assertTrue(p.url.startsWith("https://images.unsplash.com/photo-1?ixid=M3w&ixlib=rb&w=3840&fit=max"))
        assertEquals("a lake at dawn · Photo de Jane Doe · Unsplash", p.caption)
        assertEquals("https://api.unsplash.com/photos/abc123/download?ixid=M3w", p.track)
    }

    @Test fun smallerScreensAcceptSmallerPhotos() {
        assertEquals(1, WallFeeds.unsplash(json, 1920).size)
        assertEquals(0, WallFeeds.unsplash("not json", 1920).size)
    }

    @Test fun twoTopicPagesADayThatRotate() {
        val a = WallFeeds.unsplashUrls(10); val b = WallFeeds.unsplashUrls(11)
        assertEquals(2, a.size)
        assertTrue(a[0].contains("/topics/wallpapers/photos") && a[1].contains("/topics/nature/photos"))
        assertTrue(a[0] != b[0])
    }
}
