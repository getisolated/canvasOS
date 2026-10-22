package com.getisolated.canvasos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards the Apple TV aerials: how the place name follows the film, and that the shipped catalogue has what Aerials.load reads from every entry. */
class AerialsTest {
    private fun aerial(vararg captions: Pair<Int, String>) = Aerial("id", "https://sylvan.apple.com/x.mov", captions.toList())

    // ---- captions ----

    @Test fun theCaptionChangesAsTheCameraMovesOn() {
        val a = aerial(0 to "Port", 30 to "Phare", 75 to "Falaises")
        assertEquals("Port", a.captionAt(0))
        assertEquals("Port", a.captionAt(29))
        assertEquals("Phare", a.captionAt(30))
        assertEquals("Phare", a.captionAt(74))
        assertEquals("Falaises", a.captionAt(75))
        assertEquals("Falaises", a.captionAt(10_000))
    }

    @Test fun beforeTheFirstCaptionThereIsNoText() {
        val a = aerial(5 to "Plus tard")
        assertEquals("", a.captionAt(0))
        assertEquals("", a.captionAt(4))
        assertEquals("Plus tard", a.captionAt(5))
        assertEquals("", a.captionAt(-1))
    }

    @Test fun aFilmWithoutCaptionsShowsNothing() {
        assertEquals("", aerial().captionAt(0))
        assertEquals("", aerial().captionAt(99))
    }

    @Test fun oneCaptionServesTheWholeFilm() {
        val a = aerial(0 to "Seul lieu")
        for (s in listOf(0, 1, 59, 600)) assertEquals("Seul lieu", a.captionAt(s))
    }

    // ---- the shipped catalogue ----

    private fun catalogue(): JSONObject {
        val file = listOf(File("src/main/assets/aerials.json"), File("app/src/main/assets/aerials.json")).firstOrNull { it.isFile }
            ?: throw AssertionError("aerials.json not found from ${File(".").absoluteFile}")
        return JSONObject(file.readText())
    }

    @Test fun everyEntryHasWhatTheLoaderReads() {
        val videos = catalogue().getJSONArray("videos")
        assertTrue("a catalogue with something in it", videos.length() >= 20)
        val ids = HashSet<String>()
        val urls = HashSet<String>()
        for (i in 0 until videos.length()) {
            // The same calls as Aerials.load, which fails the whole screensaver on the first entry it cannot read.
            val o = videos.getJSONObject(i)
            val id = o.getString("id")
            val url = o.getString("url")
            val caps = o.getJSONArray("captions")
            assertTrue("id $id", ids.add(id))
            assertTrue("url of $id", urls.add(url))
            assertTrue("$id: $url", url.startsWith("https://"))
            assertTrue("$id has a caption", caps.length() > 0)
            var last = -1
            for (j in 0 until caps.length()) {
                val c = caps.getJSONArray(j)
                val at = c.getInt(0)
                val text = c.getString(1)
                assertTrue("$id caption $j starts at $at after $last", at > last)
                assertTrue("$id caption $j has a name", text.isNotBlank())
                last = at
            }
        }
    }

    @Test fun everyFilmNamesItsPlaceFromTheFirstSecond() {
        val videos = catalogue().getJSONArray("videos")
        for (i in 0 until videos.length()) {
            val o = videos.getJSONObject(i)
            val caps = o.getJSONArray("captions")
            assertEquals("${o.getString("id")} has no caption at second 0", 0, caps.getJSONArray(0).getInt(0))
        }
    }

    @Test fun theCatalogueCaptionsPlayThroughAnAerial() {
        val videos = catalogue().getJSONArray("videos")
        val o = videos.getJSONObject(0)
        val caps = o.getJSONArray("captions")
        val a = Aerial(o.getString("id"), o.getString("url"), (0 until caps.length()).map { caps.getJSONArray(it).let { c -> c.getInt(0) to c.getString(1) } })
        assertEquals(caps.getJSONArray(0).getString(1), a.captionAt(0))
        assertEquals(caps.getJSONArray(caps.length() - 1).getString(1), a.captionAt(100_000))
    }
}
