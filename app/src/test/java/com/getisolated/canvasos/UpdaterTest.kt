package com.getisolated.canvasos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    private fun v(s: String) = Updater.Version.parse(s)!!

    @Test fun tagsAndVersionNamesParse() {
        assertEquals("1.2.0", v("v1.2.0").text)
        assertEquals("1.2.0", v(" V1.2.0 ").text)
        assertEquals("2.0.0-rc.1", v("2.0.0-rc.1+build.7").text)
        assertNull(Updater.Version.parse(""))
        assertNull(Updater.Version.parse(null))
        assertNull(Updater.Version.parse("latest"))
        assertNull(Updater.Version.parse("1.x.0"))
        assertNull(Updater.Version.parse("1.0.-1"))
        assertNull(Updater.Version.parse("1.0.0-"))
    }

    @Test fun numbersCompareAsNumbersNotText() {
        assertTrue(v("1.10.0") > v("1.9.9"))
        assertTrue(v("1.2.1") > v("1.2.0"))
        assertTrue(v("2.0.0") > v("1.99.99"))
        assertTrue(v("1.0.0") < v("v1.0.1"))
    }

    @Test fun aMissingPartIsZero() {
        assertEquals(0, v("1.2").compareTo(v("1.2.0")))
        assertEquals(0, v("1.2.0.0").compareTo(v("1.2")))
        assertTrue(v("1.2.1") > v("1.2"))
    }

    @Test fun aPreReleaseIsOlderThanItsRelease() {
        assertTrue(v("1.2.0-rc.1") < v("1.2.0"))
        assertTrue(v("1.2.0-rc.1") < v("1.2.0-rc.2"))
        assertTrue(v("1.2.0-rc.2") < v("1.2.0-rc.10"))
        assertTrue(v("1.2.0-alpha") < v("1.2.0-beta"))
        assertTrue(v("1.2.0-1") < v("1.2.0-alpha"))
        assertTrue(v("1.2.0-rc") < v("1.2.0-rc.1"))
        assertTrue(v("1.2.0-rc.1") > v("1.1.9"))
    }

    @Test fun buildMetadataIsIgnored() {
        assertEquals(0, v("1.2.0+a").compareTo(v("1.2.0+b")))
    }

    @Test fun isNewerNeedsTwoVersionsAndAStrictIncrease() {
        assertTrue(Updater.isNewer("1.1.0", "1.0.0"))
        assertFalse(Updater.isNewer("1.0.0", "1.0.0"))
        assertFalse(Updater.isNewer("0.9.0", "1.0.0"))
        assertFalse(Updater.isNewer("garbage", "1.0.0"))
        assertFalse(Updater.isNewer("", "1.0.0"))
    }

    private fun release(tag: String, vararg assets: Pair<String, String>, body: String = "Notes") = """
        {"tag_name": "$tag", "name": "canvasOS $tag", "draft": false, "prerelease": false, "body": ${org.json.JSONObject.quote(body)},
         "assets": [${assets.joinToString(",") { (name, url) ->
        """{"name": "$name", "size": 20971520, "state": "uploaded", "browser_download_url": "$url"}"""
    }}]}
    """.trimIndent()

    private val standard = "canvasOS.apk" to "https://github.com/getisolated/canvasOS/releases/download/v1.2.0/canvasOS.apk"
    private val guard = "canvasOS-guard.apk" to "https://github.com/getisolated/canvasOS/releases/download/v1.2.0/canvasOS-guard.apk"

    @Test fun aNewerReleaseGivesItsStandardAssetForTheStandardFlavor() {
        val r = Updater.parse(release("v1.2.0", guard, standard, body = "Nouveautés"), "1.1.0", "standard")
        val u = (r as Updater.Check.Available).update
        assertEquals("1.2.0", u.version)
        assertEquals(standard.second, u.url)
        assertEquals(20971520L, u.size)
        assertEquals("Nouveautés", u.notes)
    }

    @Test fun theGuardFlavorTakesTheGuardAsset() {
        val r = Updater.parse(release("v1.2.0", standard, guard), "1.1.0", "guard")
        assertEquals(guard.second, (r as Updater.Check.Available).update.url)
        assertEquals("canvasOS-guard.apk", Updater.assetName("guard"))
        assertEquals("canvasOS.apk", Updater.assetName("standard"))
    }

    @Test fun theSameOrAnOlderReleaseIsUpToDate() {
        assertEquals(Updater.Check.UpToDate("1.2.0"), Updater.parse(release("v1.2.0", standard, guard), "1.2.0", "standard"))
        assertEquals(Updater.Check.UpToDate("1.3.0"), Updater.parse(release("v1.2.0", standard, guard), "1.3.0", "standard"))
    }

    @Test fun aNewerReleaseWithoutTheRightAssetIsUnavailableNotUpToDate() {
        val r = Updater.parse(release("v1.2.0", standard), "1.1.0", "guard")
        assertTrue(r is Updater.Check.Unavailable)
        assertTrue((r as Updater.Check.Unavailable).reason.contains("canvasOS-guard.apk"))
        assertTrue(Updater.parse(release("v1.2.0"), "1.1.0", "standard") is Updater.Check.Unavailable)
    }

    @Test fun anAssetLinkThatIsNotHttpsIsRefused() {
        val r = Updater.parse(release("v1.2.0", "canvasOS.apk" to "http://example.com/canvasOS.apk"), "1.1.0", "standard")
        assertTrue(r is Updater.Check.Unavailable)
    }

    @Test fun unreadableAnswersAreUnavailable() {
        assertTrue(Updater.parse("<html>rate limited</html>", "1.1.0", "standard") is Updater.Check.Unavailable)
        assertTrue(Updater.parse("""{"message": "Not Found"}""", "1.1.0", "standard") is Updater.Check.Unavailable)
        assertTrue(Updater.parse(release("nightly", standard), "1.1.0", "standard") is Updater.Check.Unavailable)
    }

    @Test fun longNotesAreCut() {
        val r = Updater.parse(release("v1.2.0", standard, body = "x".repeat(5000)), "1.1.0", "standard")
        assertEquals(1200, (r as Updater.Check.Available).update.notes.length)
    }
}
