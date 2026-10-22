package com.getisolated.canvasos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the sleep timer's state rules (what is left, and that a cancelled or replaced timer's alarm is ignored) over the in-memory settings. */
class PrefsBackedStateTest {
    private val ctx = FakeContext()
    private val prefs = Prefs(ctx)

    @Test fun noTimerMeansNothingLeft() {
        assertEquals(0L, SleepTimer.remainingMs(ctx))
    }

    @Test fun aRunningTimerHasTimeLeftUpToItsLength() {
        prefs.sleepAt = System.currentTimeMillis() + 10 * 60_000L
        val left = SleepTimer.remainingMs(ctx)
        assertTrue("$left", left in 9 * 60_000L..10 * 60_000L)
    }

    @Test fun aTimerThatEndedIsNotNegative() {
        prefs.sleepAt = System.currentTimeMillis() - 5_000L
        assertEquals(0L, SleepTimer.remainingMs(ctx))
        prefs.sleepAt = 1L
        assertEquals(0L, SleepTimer.remainingMs(ctx))
    }

    @Test fun anAlarmForAnotherTimerLeavesTheCurrentOneAlone() {
        val current = System.currentTimeMillis() + 30 * 60_000L
        prefs.sleepAt = current
        // The alarm of a timer that was replaced (or cancelled and set again) arrives late.
        SleepTimer.fire(ctx, current - 60_000L)
        assertEquals(current, prefs.sleepAt)
        SleepTimer.fire(ctx, current + 1L)
        assertEquals(current, prefs.sleepAt)
    }

    @Test fun anAlarmWithoutATimeIsIgnored() {
        prefs.sleepAt = 12345L
        SleepTimer.fire(ctx, 0L)
        assertEquals(12345L, prefs.sleepAt)
        prefs.sleepAt = 0L
        SleepTimer.fire(ctx, 0L)
        assertEquals(0L, prefs.sleepAt)
    }

    @Test fun anAlarmWhenNoTimerIsSetDoesNothing() {
        SleepTimer.fire(ctx, 99_999L)
        assertEquals(0L, prefs.sleepAt)
    }

    @Test fun theWarningComesBeforeTheEndAndTheSpanReadsInMinutes() {
        assertEquals(60_000L, SleepTimer.WARN_MS)
        assertEquals("1 min", SleepTimer.span(SleepTimer.WARN_MS))
    }
}
