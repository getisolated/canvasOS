package com.getisolated.canvasos

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingTest {
    @Test fun thePlayingSessionWinsOverAMoreRecentPausedOne() {
        assertEquals(1, NowPlaying.pick(listOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_PLAYING)))
        assertEquals(2, NowPlaying.pick(listOf(null, PlaybackState.STATE_STOPPED, PlaybackState.STATE_BUFFERING)))
    }

    @Test fun aPausedSessionShowsWhenNothingPlays() {
        assertEquals(1, NowPlaying.pick(listOf(PlaybackState.STATE_STOPPED, PlaybackState.STATE_PAUSED, PlaybackState.STATE_PAUSED)))
    }

    @Test fun nothingPlayingOrPausedHidesTheCapsule() {
        assertNull(NowPlaying.pick(emptyList()))
        assertNull(NowPlaying.pick(listOf(null, PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE, PlaybackState.STATE_ERROR)))
    }

    @Test fun theSecondLineSaysWhoOrWhatTheAppIsDoing() {
        assertEquals("Daft Punk", NowPlaying.subtitle(true, "Daft Punk", "Spotify", true))
        assertEquals("Spotify", NowPlaying.subtitle(true, null, "Spotify", false))
        // Without a title the app's name is the title already.
        assertEquals("En lecture", NowPlaying.subtitle(false, null, "Plex", true))
        assertEquals("En pause", NowPlaying.subtitle(false, null, "Plex", false))
    }

    @Test fun timesReadLikeApples() {
        assertEquals("0:00", NowPlaying.clock(0))
        assertEquals("0:52", NowPlaying.clock(52_999))
        assertEquals("12:05", NowPlaying.clock(725_000))
        assertEquals("1:02:03", NowPlaying.clock(3_723_000))
        assertEquals("0:00", NowPlaying.clock(-5))
        assertEquals("\u22122:46", NowPlaying.remaining(52_000, 218_000))
        // Whole seconds of each: the two times add up to the length.
        assertEquals("\u22122:46", NowPlaying.remaining(52_900, 218_400))
        assertEquals("\u22120:00", NowPlaying.remaining(300_000, 218_000))
        assertEquals("\u22121:00:00", NowPlaying.remaining(0, 3_600_000))
    }

    @Test fun thePositionMovesOnOnlyWhilePlaying() {
        // Read at 10 s when the clock said 1 000; 2.5 s later, at normal speed.
        assertEquals(12_500L, NowPlaying.positionAt(10_000, 1_000, 1f, 3_500, 200_000))
        assertEquals(15_000L, NowPlaying.positionAt(10_000, 1_000, 2f, 3_500, 200_000))
        assertEquals(10_000L, NowPlaying.positionAt(10_000, 1_000, 0f, 3_500, 200_000))
        // Never past the end, nor carried backwards by a reading from the future.
        assertEquals(200_000L, NowPlaying.positionAt(199_000, 0, 1f, 60_000, 200_000))
        assertEquals(10_000L, NowPlaying.positionAt(10_000, 5_000, 1f, 3_000, 200_000))
        // Unknown length: it keeps going; unknown position stays unknown.
        assertEquals(70_000L, NowPlaying.positionAt(10_000, 0, 1f, 60_000, 0))
        assertEquals(-1L, NowPlaying.positionAt(-1, 0, 1f, 60_000, 200_000))
    }

    @Test fun backRestartsTheTrackPastThreeSeconds() {
        assertTrue(NowPlaying.restartsOnBack(3_001))
        assertTrue(NowPlaying.restartsOnBack(120_000))
        assertFalse(NowPlaying.restartsOnBack(3_000))
        assertFalse(NowPlaying.restartsOnBack(0))
        // Position unknown: the previous track.
        assertFalse(NowPlaying.restartsOnBack(-1))
    }

    @Test fun stepsStayWithinTheTrack() {
        assertEquals(60_000L, NowPlaying.step(50_000, 1, 200_000))
        assertEquals(40_000L, NowPlaying.step(50_000, -1, 200_000))
        assertEquals(0L, NowPlaying.step(4_000, -1, 200_000))
        assertEquals(200_000L, NowPlaying.step(195_000, 1, 200_000))
        assertEquals(200_000L, NowPlaying.step(200_000, 1, 200_000))
        // Length unknown: no upper bound.
        assertEquals(510_000L, NowPlaying.step(500_000, 1, 0))
        assertEquals(10_000L, NowPlaying.step(-1, 1, 200_000))
    }
    @Test fun theCursorBarIsOneFillFromTheStartToTheCursor() {
        // Without a cursor: up to what has played; nothing before playback starts.
        assertEquals(50f, NowPlaying.barFill(200f, 0.25f, -1f, 6f), 0.001f)
        assertEquals(0f, NowPlaying.barFill(200f, 0f, -1f, 6f), 0.001f)
        // With one: up to the cursor, behind or ahead of playback, whatever has played.
        assertEquals(150f, NowPlaying.barFill(200f, 0.25f, 0.75f, 6f), 0.001f)
        assertEquals(20f, NowPlaying.barFill(200f, 0.6f, 0.1f, 6f), 0.001f)
        // Never shorter than its thickness (round ends), never past the end.
        assertEquals(6f, NowPlaying.barFill(200f, 0.01f, -1f, 6f), 0.001f)
        assertEquals(6f, NowPlaying.barFill(200f, 0.5f, 0f, 6f), 0.001f)
        assertEquals(200f, NowPlaying.barFill(200f, 0.5f, 1.4f, 6f), 0.001f)
        assertEquals(200f, NowPlaying.barFill(200f, 3f, -1f, 6f), 0.001f)
    }

    @Test fun artworkAddressesFromAppsAndTheWebAreLoaded() {
        assertTrue(NowPlaying.loadable("content://com.spotify.music.provider/art/123"))
        assertTrue(NowPlaying.loadable("https://lh3.googleusercontent.com/abc=w544-h544"))
        assertTrue(NowPlaying.loadable("HTTP://example.com/a.jpg"))
        assertTrue(NowPlaying.loadable("android.resource://com.plexapp.android/drawable/art"))
        assertFalse(NowPlaying.loadable("file:///sdcard/a.jpg"))
        assertFalse(NowPlaying.loadable("data:image/png;base64,AAAA"))
        assertFalse(NowPlaying.loadable("not a uri"))
        assertFalse(NowPlaying.loadable(""))
    }

    @Test fun largeArtworkIsDecodedAtAboutTheSizeItIsShown() {
        // Shown at 440 px: a 3000 px poster is decoded at a quarter (750 px), never below 440.
        assertEquals(4, NowPlaying.sampleSize(3000, 3000, 440))
        assertEquals(2, NowPlaying.sampleSize(1200, 900, 440))
        assertEquals(1, NowPlaying.sampleSize(544, 544, 440))
        assertEquals(1, NowPlaying.sampleSize(300, 300, 440))
        assertEquals(1, NowPlaying.sampleSize(3000, 3000, 0))
    }
}
