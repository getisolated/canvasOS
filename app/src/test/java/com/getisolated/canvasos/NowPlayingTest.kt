package com.getisolated.canvasos

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun theSecondLineSaysWhoAndWhetherItPlays() {
        assertEquals("▶  Daft Punk", NowPlaying.subtitle(true, "Daft Punk", "Spotify", true))
        assertEquals("‖  Spotify", NowPlaying.subtitle(true, null, "Spotify", false))
        // Without a title the app's name is the title already.
        assertEquals("▶  En lecture", NowPlaying.subtitle(false, null, "Plex", true))
        assertEquals("‖  En pause", NowPlaying.subtitle(false, null, "Plex", false))
    }
}
