package com.getisolated.canvasos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the updater's version reading against odd tags (two-digit parts, prefixes, garbage) and its decisions when the running version is unreadable. */
class UpdaterEdgeCasesTest {
    private fun v(s: String) = Updater.Version.parse(s)!!
    private fun cmp(a: String, b: String) = v(a).compareTo(v(b))

    @Test fun twoDigitPartsCompareAsNumbers() {
        assertTrue(cmp("1.10.0", "1.9.0") > 0)
        assertTrue(cmp("1.2.10", "1.2.9") > 0)
        assertTrue(cmp("10.0.0", "9.99.99") > 0)
        assertTrue(cmp("1.99.0", "1.100.0") < 0)
        assertTrue(cmp("1.0.100", "1.0.20") > 0)
        assertEquals(0, cmp("01.02.03", "1.2.3"))
    }

    @Test fun thePrefixIsOneLowercaseOrUppercaseVAndTrimmedSpaceAroundIt() {
        assertEquals("1.2.0", v("v1.2.0").text)
        assertEquals("1.2.0", v("V1.2.0").text)
        assertEquals("1.2.0", v("  v1.2.0\n").text)
        assertEquals("a single number is a version", "2", v("V2").text)
        assertNull("a space after the v is not a tag", Updater.Version.parse("v 1.2.0"))
        assertNull("only one v is dropped", Updater.Version.parse("vv1.2.0"))
        assertNull(Updater.Version.parse("release-1.2.0"))
    }

    @Test fun emptyAndBlankAreNoVersion() {
        assertNull(Updater.Version.parse(""))
        assertNull(Updater.Version.parse("   "))
        assertNull(Updater.Version.parse("v"))
        assertNull(Updater.Version.parse("V "))
        assertNull(Updater.Version.parse("+build"))
        assertNull(Updater.Version.parse("-rc.1"))
    }

    @Test fun malformedNumbersAreNoVersion() {
        for (s in listOf("1..2", "1.2.", ".1", ".", "1.2.x", "1.2.3a", "1.2.3 beta", "1,2,3", "1.-2", "1.2.-3", "1.0.0-", "1.0.0-rc..1",
            "99999999999.0.0", "1.2.3.4.5", "1.2.3.4.5.6.7")) {
            assertNull(s, Updater.Version.parse(s))
        }
    }

    @Test fun fourPartsIsTheMostAndTheFourthCounts() {
        assertEquals("1.2.3.4", v("1.2.3.4").text)
        assertTrue(cmp("1.2.3.4", "1.2.3") > 0)
        assertTrue(cmp("1.2.3.4", "1.2.3.5") < 0)
    }

    @Test fun garbageNeverThrows() {
        val nasty = listOf(
            "\u0000", "\u0000\u0000", "😀", "1.😀", "v😀", "1.2.3-😀", "\t\r\n", "null", "undefined",
            "x".repeat(10_000), "1.".repeat(5_000), "9".repeat(10_000), "-".repeat(1_000), "+".repeat(1_000), "1.0.0-" + "a.".repeat(500) + "a",
            "{\"tag_name\":\"v1\"}", "<html>", "../../etc/passwd", "1.2.3+" + "x".repeat(500),
        )
        for (s in nasty) {
            Updater.Version.parse(s)
            Updater.isNewer(s, "1.0.0")
            Updater.isNewer("1.0.0", s)
        }
        // Parsing is total, so the two helpers below are as well.
        assertFalse(Updater.isNewer("x".repeat(10_000), "1.0.0"))
    }

    @Test fun buildMetadataNeverChangesTheTextOrTheOrder() {
        assertEquals("1.2.0", v("1.2.0+20261022").text)
        assertEquals("1.2.0-rc.1", v("1.2.0-rc.1+sha.5114f85").text)
        assertEquals("a '+' alone leaves the version", "1.2.0", v("1.2.0+").text)
        assertEquals(0, cmp("1.2.0+a", "1.2.0"))
    }

    @Test fun theOrderIsConsistentOnARangeOfTags() {
        // Ascending, as semver sorts them.
        val ascending = listOf("0.9", "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11",
            "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1", "1.1.0.1", "1.2.0", "1.10.0", "2.0.0-rc.1", "2.0.0", "10.0.0")
        for (i in ascending.indices) for (j in ascending.indices) {
            val c = cmp(ascending[i], ascending[j])
            assertEquals("${ascending[i]} vs ${ascending[j]}", i.compareTo(j).coerceIn(-1, 1), c.coerceIn(-1, 1))
            assertEquals("antisymmetric: ${ascending[i]} vs ${ascending[j]}", c.coerceIn(-1, 1), -cmp(ascending[j], ascending[i]).coerceIn(-1, 1))
        }
    }

    @Test fun anUnreadableRunningVersionTakesAnyReadableRelease() {
        // A dev build with a odd versionName is offered the release rather than never updated.
        assertTrue(Updater.isNewer("1.0.0", "garbage"))
        assertTrue(Updater.isNewer("1.0.0", ""))
        assertFalse("but an unreadable release is never offered", Updater.isNewer("garbage", "garbage"))
        assertFalse(Updater.isNewer("garbage", "1.0.0"))
    }

    @Test fun aPreReleaseOfTheNextVersionIsNewerThanTheCurrentOne() {
        assertTrue(Updater.isNewer("1.2.0-rc.1", "1.1.0"))
        assertFalse(Updater.isNewer("1.2.0-rc.1", "1.2.0"))
        assertTrue("and a release is newer than its own candidate", Updater.isNewer("1.2.0", "1.2.0-rc.3"))
    }

    // ---- pending(), through the fake settings ----

    @Test fun aRememberedUpdateStaysPendingWhileItIsNewerThanTheRunningBuild() {
        val prefs = Prefs(FakeContext())
        assertNull("nothing remembered", Updater.pending(prefs))
        prefs.updateAvailable = "999.0.0"
        assertEquals("999.0.0", Updater.pending(prefs))
        // Right after the update, the remembered version is the one now running (or an older one).
        prefs.updateAvailable = BuildConfig.VERSION_NAME
        assertNull(Updater.pending(prefs))
        prefs.updateAvailable = "0.0.1"
        assertNull(Updater.pending(prefs))
        prefs.updateAvailable = "not a version"
        assertNull(Updater.pending(prefs))
    }

    @Test fun theRunningBuildHasAVersionTheUpdaterCanRead() {
        // If this fails, every check would call a dev build "newer than anything".
        assertNotNull(BuildConfig.VERSION_NAME, Updater.Version.parse(BuildConfig.VERSION_NAME))
    }

    // ---- parse(), the rest of the release answer ----

    private val asset = """{"name":"canvasOS.apk","size":1234,"browser_download_url":"https://github.com/getisolated/canvasOS/releases/download/v1.2.0/canvasOS.apk"}"""

    @Test fun anUnreadableCurrentVersionStillOffersTheRelease() {
        val r = Updater.parse("""{"tag_name":"v1.2.0","assets":[$asset]}""", "dev", "standard")
        assertTrue(r is Updater.Check.Available)
        assertEquals("1.2.0", (r as Updater.Check.Available).update.version)
    }

    @Test fun theTagMayBeUppercaseOrPaddedAndTheVersionKeepsItsPreReleasePart() {
        val r = Updater.parse("""{"tag_name":" V1.2.0-rc.1 ","assets":[$asset]}""", "1.1.0", "standard") as Updater.Check.Available
        assertEquals("1.2.0-rc.1", r.update.version)
    }

    @Test fun missingFieldsGiveSafeDefaults() {
        val r = Updater.parse("""{"tag_name":"v1.2.0","assets":[{"name":"canvasOS.apk","browser_download_url":"https://x.org/canvasOS.apk"}]}""",
            "1.1.0", "standard") as Updater.Check.Available
        assertEquals(0L, r.update.size)
        assertEquals("", r.update.notes)
        assertEquals("https://x.org/canvasOS.apk", r.update.url)
    }

    @Test fun notesAreTrimmedBeforeTheyAreCut() {
        val body = "   " + "y".repeat(3000) + "   "
        val r = Updater.parse("""{"tag_name":"v1.2.0","body":"$body","assets":[$asset]}""", "1.1.0", "standard") as Updater.Check.Available
        assertEquals("y".repeat(1200), r.update.notes)
    }

    @Test fun anAnswerWithoutAssetsOrWithJunkAssetsIsUnavailable() {
        for (answer in listOf(
            """{"tag_name":"v1.2.0"}""",
            """{"tag_name":"v1.2.0","assets":null}""",
            """{"tag_name":"v1.2.0","assets":[]}""",
            """{"tag_name":"v1.2.0","assets":[1,"x",null,[]]}""",
            """{"tag_name":"v1.2.0","assets":{"name":"canvasOS.apk"}}""",
        )) {
            val r = Updater.parse(answer, "1.1.0", "standard")
            assertTrue(answer, r is Updater.Check.Unavailable)
            assertTrue(answer, (r as Updater.Check.Unavailable).reason.contains("canvasOS.apk"))
        }
    }

    @Test fun theFirstAssetOfTheRightNameWins() {
        val second = """{"name":"canvasOS.apk","size":9,"browser_download_url":"https://evil.example/canvasOS.apk"}"""
        val r = Updater.parse("""{"tag_name":"v1.2.0","assets":[$asset,$second]}""", "1.1.0", "standard") as Updater.Check.Available
        assertTrue(r.update.url.startsWith("https://github.com/getisolated/"))
    }

    @Test fun anAssetNeedsAnHttpsLinkAndTheNameIsMatchedExactly() {
        fun answer(name: String, url: String) = """{"tag_name":"v1.2.0","assets":[{"name":"$name","browser_download_url":"$url"}]}"""
        assertTrue(Updater.parse(answer("canvasOS.apk", ""), "1.1.0", "standard") is Updater.Check.Unavailable)
        assertTrue(Updater.parse(answer("canvasOS.apk", "ftp://x.org/a.apk"), "1.1.0", "standard") is Updater.Check.Unavailable)
        assertTrue(Updater.parse(answer("canvasOS.apk", "HTTPS://x.org/a.apk"), "1.1.0", "standard") is Updater.Check.Unavailable)
        assertTrue(Updater.parse(answer("CanvasOS.apk", "https://x.org/a.apk"), "1.1.0", "standard") is Updater.Check.Unavailable)
        assertTrue(Updater.parse(answer("canvasOS.apk.sha256", "https://x.org/a.sha"), "1.1.0", "standard") is Updater.Check.Unavailable)
        assertTrue(Updater.parse(answer("canvasOS.apk", "https://x.org/a.apk"), "1.1.0", "standard") is Updater.Check.Available)
    }

    @Test fun anUpToDateAnswerNeedsNoAsset() {
        assertEquals(Updater.Check.UpToDate("1.2.0"), Updater.parse("""{"tag_name":"v1.2.0"}""", "1.2.0", "guard"))
        assertEquals(Updater.Check.UpToDate("1.2.0"), Updater.parse("""{"tag_name":"v1.2.0","assets":[]}""", "1.2.0", "standard"))
    }

    @Test fun anyOtherFlavorTakesTheStandardApk() {
        assertEquals("canvasOS.apk", Updater.assetName("standard"))
        assertEquals("canvasOS.apk", Updater.assetName(""))
        assertEquals("canvasOS.apk", Updater.assetName("somethingNew"))
        assertEquals("canvasOS-guard.apk", Updater.assetName("guard"))
    }

    @Test fun anUnreadableTagNamesItInTheReason() {
        val r = Updater.parse("""{"tag_name":"nightly","assets":[$asset]}""", "1.1.0", "standard") as Updater.Check.Unavailable
        assertTrue(r.reason, r.reason.contains("nightly"))
        val none = Updater.parse("""{"assets":[$asset]}""", "1.1.0", "standard")
        assertTrue("no tag at all", none is Updater.Check.Unavailable)
        assertTrue(Updater.parse("[]", "1.1.0", "standard") is Updater.Check.Unavailable)
        assertTrue(Updater.parse("", "1.1.0", "standard") is Updater.Check.Unavailable)
    }
}
