package com.getisolated.canvasos

import org.junit.Assert.assertEquals
import org.junit.Test

/** Guards the French wording of a memory cleanup's result (plurals, the clamp at zero, the cache-only case) shown in the Control Center and Réglages. */
class CleanerResultTest {
    private fun result(before: Long, after: Long, closed: List<String> = emptyList(), spared: List<String> = emptyList(), viaAdb: Boolean = true) =
        Cleaner.Result(before, after, closed.size, viaAdb, closed, spared)

    @Test fun freedMemoryIsNeverNegative() {
        assertEquals(0, result(500, 480).freedMb)
        assertEquals("0 Mo libéré", result(500, 480).freedText)
        assertEquals(0, result(500, 500).freedMb)
        assertEquals(1, result(500, 501).freedMb)
    }

    @Test fun freedTextIsSingularUpToOneMegabyte() {
        assertEquals("0 Mo libéré", result(100, 100).freedText)
        assertEquals("1 Mo libéré", result(100, 101).freedText)
        assertEquals("2 Mo libérés", result(100, 102).freedText)
        assertEquals("1024 Mo libérés", result(0, 1024).freedText)
    }

    @Test fun closedTextCountsTheClosedApps() {
        assertEquals("aucune app fermée", result(0, 0).closedText)
        assertEquals("1 app fermée", result(0, 0, closed = listOf("a.b")).closedText)
        assertEquals("2 apps fermées", result(0, 0, closed = listOf("a.b", "c.d")).closedText)
        assertEquals("12 apps fermées", result(0, 0, closed = (1..12).map { "app.n$it" }).closedText)
    }

    @Test fun closedTextIgnoresTheStoppedCount() {
        // `stopped` is how many force-stops were sent; the text reads the apps that really had a process.
        val r = Cleaner.Result(0, 0, stopped = 9, viaAdb = true, closed = listOf("a.b"))
        assertEquals("1 app fermée", r.closedText)
    }

    @Test fun theSummaryListsOnlyWhatHappened() {
        assertEquals("5 Mo libérés · aucune app fermée", result(10, 15).summary())
        assertEquals("5 Mo libérés · 1 app fermée", result(10, 15, closed = listOf("a.b")).summary())
        assertEquals("0 Mo libéré · aucune app fermée · 3 en cours d'utilisation épargnées",
            result(10, 10, spared = listOf("a.b", "c.d", "e.f")).summary())
        assertEquals("1 en cours d'utilisation épargnée", result(0, 0, spared = listOf("a.b")).summary().substringAfterLast(" · "))
    }

    @Test fun withoutAdbOnlyTheCacheWasCleanedAndNoAppIsListed() {
        val r = result(100, 130, closed = listOf("a.b", "c.d"), spared = listOf("e.f"), viaAdb = false)
        assertEquals("30 Mo libérés (cache uniquement)", r.summary())
        assertEquals("the separator makes no difference there", "30 Mo libérés (cache uniquement)", r.summary(", "))
        assertEquals("1 Mo libéré (cache uniquement)", result(100, 101, viaAdb = false).summary())
    }

    @Test fun theSeparatorIsWhatTheCallerAsks() {
        val r = result(0, 8, closed = listOf("a.b"), spared = listOf("c.d"))
        assertEquals("8 Mo libérés, 1 app fermée, 1 en cours d'utilisation épargnée", r.summary(", "))
        assertEquals("8 Mo libérés|1 app fermée|1 en cours d'utilisation épargnée", r.summary("|"))
    }

    @Test fun theLastCleanLineSplitsBackIntoWhenAndWhat() {
        // Cleaner.clean stores "d MMM HH:mm, <summary(", ")>"; Réglages splits it on the first ", ".
        val stored = "22 oct. 21:45, " + result(0, 312, closed = listOf("a.b", "c.d"), spared = listOf("e.f")).summary(", ")
        assertEquals("22 oct. 21:45", stored.substringBefore(", "))
        assertEquals("312 Mo libérés, 2 apps fermées, 1 en cours d'utilisation épargnée", stored.substringAfter(", ", ""))
    }
}
