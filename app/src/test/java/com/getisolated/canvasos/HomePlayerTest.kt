package com.getisolated.canvasos

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePlayerTest {
    /** The bars stay within the view and never all move together. */
    @Test fun theBarsStayInRangeAndApart() {
        var apart = false
        var t = 0.0
        while (t < 20.0) {
            val l = (0 until 3).map { HomePlayer.levelAt(t, it) }
            for (v in l) assertTrue("$v at $t", v in 0.2f..1f)
            if (l.distinct().size == 3) apart = true
            t += 0.05
        }
        assertTrue(apart)
        assertNotEquals(HomePlayer.levelAt(1.0, 0), HomePlayer.levelAt(1.0, 1))
    }
}
