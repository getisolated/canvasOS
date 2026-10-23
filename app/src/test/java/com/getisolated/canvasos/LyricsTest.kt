package com.getisolated.canvasos

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTest {
    @Test fun lrcLinesReadInTimeOrderWithEveryTagForm() {
        val l = Lyrics.parseLrc("[ar:Someone]\n[00:12.50]Second\n[00:01.5]First\n[01:02.345] Third\n[00:30.00][00:45]Chorus\n[00:50.00]\nno tag here")
        assertEquals(listOf(1500L, 12500L, 30000L, 45000L, 50000L, 62345L), l.map { it.ms })
        assertEquals(listOf("First", "Second", "Chorus", "Chorus", "", "Third"), l.map { it.text })
    }

    @Test fun theLineSungIsTheLastOneStarted() {
        val l = Lyrics.parseLrc("[00:01.00]a\n[00:05.00]b\n[00:09.00]c")
        assertEquals(-1, Lyrics.lineAt(l, 0))
        assertEquals(0, Lyrics.lineAt(l, 1000))
        assertEquals(1, Lyrics.lineAt(l, 8999))
        assertEquals(2, Lyrics.lineAt(l, 60_000))
        assertEquals(-1, Lyrics.lineAt(emptyList(), 5000))
    }

    @Test fun aTrackObjectGivesSyncedLinesOrPlainText() {
        val synced = Lyrics.parseTrack(JSONObject().put("syncedLyrics", "[00:01.00]x").put("plainLyrics", "x").put("instrumental", false))
        assertTrue(synced.synced && synced.found)
        val plain = Lyrics.parseTrack(JSONObject().put("syncedLyrics", JSONObject.NULL).put("plainLyrics", "just text\n"))
        assertFalse(plain.synced)
        assertEquals("just text", plain.plain)
        assertTrue(Lyrics.parseTrack(JSONObject().put("instrumental", true)).instrumental)
        assertFalse(Lyrics.Result.NONE.found)
    }

    @Test fun theSearchPrefersASyncedTrackOfTheSameLength() {
        val a = JSONArray()
            .put(JSONObject().put("duration", 200).put("plainLyrics", "p"))
            .put(JSONObject().put("duration", 300).put("syncedLyrics", "[00:01.00]far"))
            .put(JSONObject().put("duration", 201).put("syncedLyrics", "[00:01.00]near"))
        assertEquals("near", Lyrics.best(a, 200_000)!!.lines[0].text)
        // No length known: any synced one, else plain.
        assertTrue(Lyrics.best(a, 0)!!.synced)
        assertNull(Lyrics.best(JSONArray(), 1000))
        assertEquals("p", Lyrics.best(JSONArray().put(JSONObject().put("plainLyrics", "p")), 1000)!!.plain)
    }

    @Test fun theCacheKeyIgnoresMilliseconds() {
        assertEquals(Lyrics.key("A", "B", 200_400), Lyrics.key("A", "B", 200_900))
    }
}
