package com.getisolated.canvasos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepTimerTest {
    private fun min(m: Long) = m * 60_000L

    @Test fun spansReadAsInTheSheet() {
        assertEquals("15 min", SleepTimer.span(min(15)))
        assertEquals("1 h", SleepTimer.span(min(60)))
        assertEquals("1 h 30", SleepTimer.span(min(90)))
        assertEquals("1 h 05", SleepTimer.span(min(65)))
        assertEquals("2 h", SleepTimer.span(min(120)))
    }

    @Test fun aSpanRoundsUpSoTheLastMinuteStillShows() {
        assertEquals("42 min", SleepTimer.span(min(41) + 1))
        assertEquals("1 min", SleepTimer.span(1))
        assertEquals("1 h", SleepTimer.span(min(59) + 59_000))
    }

    @Test fun anElapsedTimerReadsAsLessThanAMinute() {
        assertEquals("moins d'une minute", SleepTimer.span(0))
        assertEquals("moins d'une minute", SleepTimer.span(-5_000))
    }

    @Test fun ssidLosesItsQuotesAndTheUnknownMarkers() {
        assertEquals("Livebox-1234", Net.cleanSsid("\"Livebox-1234\""))
        assertEquals("Livebox-1234", Net.cleanSsid("Livebox-1234"))
        assertNull(Net.cleanSsid("<unknown ssid>"))
        assertNull(Net.cleanSsid("\"\""))
        assertNull(Net.cleanSsid(null))
    }
}
