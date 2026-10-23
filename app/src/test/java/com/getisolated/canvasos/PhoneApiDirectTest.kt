package com.getisolated.canvasos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The remote without adb ([Foreground]): which key names go where, and how a press is timed. */
class PhoneApiDirectTest {
    @Test fun everyKeyTheAdbPathAcceptsHasADirectWay() {
        for (k in PhoneApi.KEYS.keys + PhoneApi.LONG_PRESSES.keys) assertTrue(k, PhoneApi.direct(k) != null)
    }

    @Test fun nothingElseIsAccepted() {
        for (k in listOf(null, "", "POWER", "SLEEP", "WAKEUP", "MENU", "center", "KEYCODE_DPAD_UP", "HOME_LONG"))
            assertNull(k, PhoneApi.direct(k))
    }

    @Test fun homeAndVolumeAreNotKeyEvents() {
        assertEquals(PhoneApi.Direct.Home, PhoneApi.direct("HOME"))
        assertEquals(PhoneApi.Direct.Volume(PhoneApi.VolumeStep.RAISE), PhoneApi.direct("VOLUME_UP"))
        assertEquals(PhoneApi.Direct.Volume(PhoneApi.VolumeStep.LOWER), PhoneApi.direct("VOLUME_DOWN"))
        assertEquals(PhoneApi.Direct.Volume(PhoneApi.VolumeStep.MUTE), PhoneApi.direct("VOLUME_MUTE"))
    }

    @Test fun keysKeepTheirKeycode() {
        assertEquals(PhoneApi.Direct.Key("KEYCODE_DPAD_UP"), PhoneApi.direct("UP"))
        assertEquals(PhoneApi.Direct.Key("KEYCODE_BACK"), PhoneApi.direct("BACK"))
        assertEquals(PhoneApi.Direct.Key("KEYCODE_DEL"), PhoneApi.direct("DEL"))
        assertEquals(PhoneApi.Direct.Key("KEYCODE_ENTER"), PhoneApi.direct("ENTER"))
        assertEquals(PhoneApi.Direct.Key("KEYCODE_DPAD_CENTER", long = true), PhoneApi.direct("CENTER_LONG"))
    }

    @Test fun aTapIsADownThenAnUp() {
        assertEquals(
            listOf(PhoneApi.Stroke(down = true), PhoneApi.Stroke(down = false)),
            PhoneApi.strokes(long = false, longPressTimeoutMs = 500),
        )
    }

    @Test fun aHoldRepeatsAsALongPressOnlyAfterTheTimeout() {
        val s = PhoneApi.strokes(long = true, longPressTimeoutMs = 500)
        assertEquals(3, s.size)
        assertEquals(PhoneApi.Stroke(down = true), s[0])
        // Like a remote's: the repeat comes after the long-press timeout, so views' own timers fire first.
        assertTrue(s[1].down && s[1].repeat == 1 && s[1].longPress)
        assertTrue(s[1].waitMs > 500)
        assertEquals(PhoneApi.Stroke(down = false), s[2])
    }
}
