package com.getisolated.canvasos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the settings' defaults (privacy ones above all), the "keep" merge rule and the small rules in Prefs' getters and setters. */
class PrefsTest {
    private val ctx = FakeContext()
    private fun prefs() = Prefs(ctx)

    /** The defaults canvasOS has shipped with; changing one changes every fresh install. */
    @Test fun freshInstallDefaults() {
        val p = prefs()
        // Opt-in: these read notifications or media sessions, and say so in the manifest and the strings.
        assertFalse(p.banners)
        assertFalse(p.nowPlaying)
        assertTrue(p.bannerApps.isEmpty())
        assertEquals("", p.tmdbKey)
        // Never on by themselves.
        assertFalse(p.shutdownOnPowerKey)
        assertFalse(p.frameScreensaver)
        assertFalse(p.guard)
        assertFalse(p.keepAdb)
        assertFalse(p.adbAuthorized)
        assertEquals(Prefs.INTRO_OFF, p.intro)
        // On from the start.
        assertTrue(p.showAvatar)
        assertTrue(p.cleanOnStandby)
        assertTrue(p.phoneServer)
        assertTrue(p.dockRecents)
        assertTrue(p.weather)
        assertTrue(p.introSound)
        assertTrue(p.ownIcon)
        assertTrue(p.settingsBg)
        assertEquals(4, p.cleanIntervalHours)
        assertEquals("daily", p.background)
        assertEquals(30, p.wallMinutes)
        assertEquals(30, p.frameSeconds)
        assertEquals(Prefs.SAVER_PHOTOS, p.saverMode)
        assertEquals(Prefs.CLOCK_CORNER, p.clockStyle)
        assertEquals(Prefs.AUTO, p.ccStyle)
        assertEquals(Prefs.AUTO, p.focusStyle)
        assertEquals(Prefs.INTRO_VIDEO, p.introSource)
        assertEquals(5000, p.wakeDelay)
        assertEquals(0L, p.sleepAt)
        assertEquals(0L, p.updateCheckedAt)
        assertEquals("", p.updateAvailable)
        assertNull(p.weatherLat)
        assertNull(p.weatherLon)
        assertEquals("", p.weatherPlace)
        assertFalse(p.weatherAuto)
        assertEquals("", p.labels)
        assertTrue(p.order.isEmpty())
        assertTrue(p.hidden.isEmpty())
        assertTrue(p.removed.isEmpty())
        assertTrue(p.pinnedInputs.isEmpty())
        assertEquals("a fresh install writes nothing just by being read", emptySet<String>(), ctx.store.keys)
    }

    @Test fun theDefaultBackgroundIsAPhotoSource() {
        assertTrue(Wallpapers.isPhoto(prefs().background))
    }

    @Test fun valuesComeBackFromAnotherPrefsOverTheSameStore() {
        val a = prefs()
        a.background = "scenery"
        a.wallMinutes = 5
        a.guard = true
        a.sleepAt = 1_761_162_300_000L
        a.introBoot = 123_456_789_012L
        a.clockStyle = Prefs.CLOCK_LARGE
        val b = Prefs(ctx)
        assertEquals("scenery", b.background)
        assertEquals(5, b.wallMinutes)
        assertTrue(b.guard)
        assertEquals(1_761_162_300_000L, b.sleepAt)
        assertEquals(123_456_789_012L, b.introBoot)
        assertEquals(Prefs.CLOCK_LARGE, b.clockStyle)
    }

    @Test fun theGridOrderIsAListWithoutEmptyNames() {
        val p = prefs()
        p.order = listOf("com.netflix.ninja", "org.xbmc.kodi")
        assertEquals("com.netflix.ninja,org.xbmc.kodi", ctx.store["order"])
        assertEquals(listOf("com.netflix.ninja", "org.xbmc.kodi"), p.order)
        p.order = emptyList()
        assertEquals(emptyList<String>(), p.order)
        ctx.store.edit().putString("order", ",a,,b,").apply()
        assertEquals(listOf("a", "b"), p.order)
    }

    @Test fun setsAreCopiesOnTheWayInAndOut() {
        val p = prefs()
        val mine = mutableSetOf("a.b", "c.d")
        p.hidden = mine
        mine += "e.f"
        assertEquals(setOf("a.b", "c.d"), p.hidden)
        p.removed = setOf("x.y")
        p.pinnedInputs = setOf("input:content://x")
        p.bannerApps = setOf("com.ring.doorbell")
        assertEquals(setOf("x.y"), p.removed)
        assertEquals(setOf("input:content://x"), p.pinnedInputs)
        assertEquals(setOf("com.ring.doorbell"), p.bannerApps)
        p.hidden = emptySet()
        assertTrue(p.hidden.isEmpty())
    }

    // ---- the keep list ----

    @Test fun withNothingSavedTheKeepListIsTheDefaultsAndStaysUnwritten() {
        assertEquals(Prefs.DEFAULT_KEEP, prefs().keep)
        assertFalse(ctx.store.contains("keep"))
        assertFalse(ctx.store.contains("keep_defaults"))
    }

    @Test fun aKeepListSavedBeforeTrackingGetsTheNewerDefaultsOnce() {
        // Before defaults were tracked, a saved list had seen exactly these three.
        val before = setOf("com.dangbei.screencast", "com.xiaodianshi.tv.yst", "dev.vodik7.atvtoolsserver")
        ctx.store.edit().putStringSet("keep", setOf("com.dangbei.screencast", "my.custom.app")).apply()
        val merged = prefs().keep
        assertEquals(setOf("com.dangbei.screencast", "my.custom.app") + (Prefs.DEFAULT_KEEP - before), merged)
        assertTrue("com.spotify.tv.android" in merged)
        assertTrue("com.weekd.miracastreceiver" in merged)
        // The merge is written down, with what has now been seen.
        assertEquals(merged, ctx.store.getStringSet("keep", null))
        assertEquals(Prefs.DEFAULT_KEEP, ctx.store.getStringSet("keep_defaults", null))
        assertEquals("a second read changes nothing", merged, prefs().keep)
    }

    @Test fun aDefaultTheUserTookOutOfTheirSavedListIsNotBroughtBack() {
        val before = setOf("com.dangbei.screencast", "com.xiaodianshi.tv.yst", "dev.vodik7.atvtoolsserver")
        // Saved before tracking, with the three old defaults removed on purpose.
        ctx.store.edit().putStringSet("keep", setOf("my.custom.app")).apply()
        val keep = prefs().keep
        assertTrue(before.none { it in keep })
        // Removing a merged default later sticks as well: saving through the setter marks them all seen.
        prefs().keep = keep - "com.spotify.tv.android"
        assertFalse("com.spotify.tv.android" in prefs().keep)
        assertEquals(Prefs.DEFAULT_KEEP, ctx.store.getStringSet("keep_defaults", null))
    }

    @Test fun aDefaultAddedByALaterVersionJoinsAListThatHadSeenTheOthers() {
        // The state of a TV that last ran a version whose defaults lacked AdGuard.
        val seen = Prefs.DEFAULT_KEEP - "com.adguard.android"
        ctx.store.edit().putStringSet("keep", setOf("my.custom.app")).putStringSet("keep_defaults", seen).apply()
        assertEquals(setOf("my.custom.app", "com.adguard.android"), prefs().keep)
        assertEquals(Prefs.DEFAULT_KEEP, ctx.store.getStringSet("keep_defaults", null))
        assertEquals(setOf("my.custom.app", "com.adguard.android"), prefs().keep)
    }

    @Test fun anUpToDateSavedListIsReturnedAsItIs() {
        val p = prefs()
        p.keep = setOf("only.this.one")
        assertEquals(setOf("only.this.one"), p.keep)
        assertEquals("an emptied list stays empty", emptySet<String>(), p.apply { keep = emptySet() }.keep)
    }

    // ---- small rules ----

    @Test fun theWakeDelayStaysBetweenZeroAndFifteenSeconds() {
        val p = prefs()
        p.wakeDelay = 99_000
        assertEquals(15_000, p.wakeDelay)
        p.wakeDelay = -3
        assertEquals(0, p.wakeDelay)
        p.wakeDelay = 7_500
        assertEquals(7_500, p.wakeDelay)
    }

    @Test fun theTmdbKeyIsTrimmed() {
        val p = prefs()
        p.tmdbKey = "  0123456789abcdef \n"
        assertEquals("0123456789abcdef", p.tmdbKey)
    }

    @Test fun theWeatherPlaceKeepsItsCoordinatesAndLosesThemWhenCleared() {
        val p = prefs()
        p.weatherLat = 48.85341
        p.weatherLon = 2.3488
        assertEquals(48.85341, p.weatherLat!!, 0.0)
        assertEquals(2.3488, p.weatherLon!!, 0.0)
        p.weatherLat = null
        assertNull(p.weatherLat)
        assertFalse(ctx.store.contains("weather_lat"))
        ctx.store.edit().putString("weather_lon", "not a number").apply()
        assertNull(p.weatherLon)
    }

    @Test fun thePhoneTokenIsMadeOnceAndKept() {
        val a = prefs().phoneToken
        assertEquals(PhoneApi.TOKEN_LENGTH, a.length)
        assertEquals(a, prefs().phoneToken)
        assertEquals(a, ctx.store["phone_token"])
        prefs().phoneToken = PhoneApi.newToken()
        assertNotEquals("a new code replaces the old one", a, prefs().phoneToken)
    }

    @Test fun forcedStylesAnswerWithoutAskingTheDevice() {
        val p = prefs()
        p.ccStyle = Prefs.CC_GLASS
        p.focusStyle = Prefs.FOCUS_TVOS
        assertTrue(p.ccGlass(ctx))
        assertTrue(p.focusTvos(ctx))
        p.ccStyle = Prefs.CC_PANEL
        p.focusStyle = Prefs.FOCUS_SIMPLE
        assertFalse(p.ccGlass(ctx))
        assertFalse(p.focusTvos(ctx))
    }

    @Test fun namesGivenToAppsAreKeptPerPackage() {
        val p = prefs()
        assertNull(p.label("com.netflix.ninja"))
        p.setLabel("com.netflix.ninja", "  Films ")
        p.setLabel("org.xbmc.kodi", "Médiathèque")
        assertEquals("Films", p.label("com.netflix.ninja"))
        assertEquals("Médiathèque", Prefs(ctx).label("org.xbmc.kodi"))
        p.setLabel("com.netflix.ninja", "   ")
        assertNull(p.label("com.netflix.ninja"))
        assertEquals("the other name is untouched", "Médiathèque", p.label("org.xbmc.kodi"))
        p.setLabel("org.xbmc.kodi", null)
        assertEquals("{}", p.labels)
        // A stored value that is not JSON means no names, and renaming starts over from it.
        p.labels = "garbage"
        assertNull(p.label("org.xbmc.kodi"))
        p.setLabel("a.b", "Ab")
        assertEquals("Ab", p.label("a.b"))
    }

    // ---- constants other code relies on ----

    @Test fun theDefaultGridOrderNamesEachPackageOnce() {
        // AppRepo.orderedVisible ranks by the last index, so a repeat would silently move an app.
        val order = Prefs.DEFAULT_ORDER
        assertEquals(order.filter { order.count { o -> o == it } > 1 }.distinct().toString(), order.size, order.toSet().size)
        order.forEach { assertTrue(it, it.matches(Regex("[a-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) }
    }

    @Test fun theDefaultKeepListHoldsOnlyPackageNames() {
        assertTrue(Prefs.DEFAULT_KEEP.isNotEmpty())
        Prefs.DEFAULT_KEEP.forEach { assertTrue(it, it.matches(Regex("[a-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) }
        assertFalse("canvasOS itself is never listed: Cleaner adds it", "com.getisolated.canvasos" in Prefs.DEFAULT_KEEP)
    }

    @Test fun styleConstantsAreDistinctWithinEachSetting() {
        assertEquals(3, setOf(Prefs.CLOCK_CORNER, Prefs.CLOCK_LARGE, Prefs.CLOCK_OFF).size)
        assertEquals(3, setOf(Prefs.AUTO, Prefs.CC_GLASS, Prefs.CC_PANEL).size)
        assertEquals(3, setOf(Prefs.AUTO, Prefs.FOCUS_TVOS, Prefs.FOCUS_SIMPLE).size)
        assertEquals(3, setOf(Prefs.INTRO_OFF, Prefs.INTRO_BOOT, Prefs.INTRO_WAKE).size)
        assertEquals(2, setOf(Prefs.INTRO_VIDEO, Prefs.INTRO_WORDMARK).size)
        assertEquals(2, setOf(Prefs.SAVER_PHOTOS, Prefs.SAVER_AERIAL).size)
    }
}
