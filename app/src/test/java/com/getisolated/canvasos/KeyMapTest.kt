package com.getisolated.canvasos

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyMapTest {
    private val red = KeyMap.Binding(KeyEvent.KEYCODE_PROG_RED, false, KeyMap.CONTROL_CENTER)
    private val oneLong = KeyMap.Binding(KeyEvent.KEYCODE_1, true, "app:com.spotify.tv.android")
    private val input = KeyMap.Binding(KeyEvent.KEYCODE_2, false, "input:content://x/y\u001fHDMI 1\u001fPS5")

    @Test fun bindingsSurviveTheirTextForm() {
        val back = KeyMap.parse(KeyMap.serialize(listOf(red, oneLong, input)))
        assertEquals(3, back.size)
        assertEquals(KeyEvent.KEYCODE_PROG_RED, back[0].code); assertFalse(back[0].long); assertEquals(KeyMap.CONTROL_CENTER, back[0].action)
        assertTrue(back[1].long); assertEquals("app:com.spotify.tv.android", back[1].action)
        assertEquals(input.action, back[2].action)
        assertTrue(KeyMap.parse("").isEmpty())
        assertTrue(KeyMap.parse("junk\u001e=x\u001e12=").isEmpty())
    }

    @Test fun oneBindingPerKeyAndPress() {
        var l = KeyMap.put(listOf(red, oneLong), KeyMap.Binding(KeyEvent.KEYCODE_PROG_RED, false, KeyMap.LYRICS))
        assertEquals(2, l.size)
        assertEquals(KeyMap.LYRICS, KeyMap.find(l, KeyEvent.KEYCODE_PROG_RED, false)!!.action)
        // The held press of the same key is another binding.
        l = KeyMap.put(l, KeyMap.Binding(KeyEvent.KEYCODE_PROG_RED, true, KeyMap.CLEAN))
        assertEquals(3, l.size)
        assertTrue(KeyMap.uses(l, KeyEvent.KEYCODE_PROG_RED))
        assertFalse(KeyMap.uses(l, KeyEvent.KEYCODE_PROG_BLUE))
        assertEquals(2, KeyMap.remove(l, red).size)
        assertNull(KeyMap.find(l, KeyEvent.KEYCODE_1, false))
    }

    @Test fun theTvsKeysAndTheHomeScreensAreRefused() {
        assertNotNull(KeyMap.reserved(KeyEvent.KEYCODE_HOME, false))
        assertNotNull(KeyMap.reserved(KeyEvent.KEYCODE_VOLUME_UP, true))
        assertNotNull(KeyMap.reserved(KeyEvent.KEYCODE_DPAD_CENTER, false))
        assertNotNull(KeyMap.reserved(KeyEvent.KEYCODE_DPAD_LEFT, true))
        // Back and Menu: a press is the home screen's, a hold is free.
        assertNotNull(KeyMap.reserved(KeyEvent.KEYCODE_BACK, false))
        assertNull(KeyMap.reserved(KeyEvent.KEYCODE_BACK, true))
        assertNull(KeyMap.reserved(KeyEvent.KEYCODE_PROG_RED, false))
        assertNull(KeyMap.reserved(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, false))
    }

    @Test fun namesReadInFrench() {
        assertEquals("Touche rouge", KeyMap.keyName(KeyEvent.KEYCODE_PROG_RED, false))
        assertEquals("Touche 1 (appui long)", KeyMap.keyName(KeyEvent.KEYCODE_1, true))
        assertEquals("Retour (appui long)", KeyMap.keyName(KeyEvent.KEYCODE_BACK, true))
        assertEquals("Profil", KeyMap.keyName(KeyEvent.KEYCODE_PROFILE_SWITCH, false))
        // The TV's own keys the service catches for the shortcuts; the home screen's never.
        assertTrue(KeyEvent.KEYCODE_SETTINGS in KeyCapture.CAPTURED && KeyEvent.KEYCODE_PROFILE_SWITCH in KeyCapture.CAPTURED)
        assertFalse(KeyEvent.KEYCODE_BACK in KeyCapture.CAPTURED || KeyEvent.KEYCODE_HOME in KeyCapture.CAPTURED)
        assertNull(KeyMap.reserved(KeyEvent.KEYCODE_SETTINGS, false))
        // An unknown key without Android's names (the unit tests have none): its code.
        assertTrue(KeyMap.keyName(KeyEvent.KEYCODE_F7, false).startsWith("Touche") || KeyMap.keyName(KeyEvent.KEYCODE_F7, false) == "F7")
        assertEquals("Centre de contrôle", KeyMap.label(KeyMap.CONTROL_CENTER))
        assertEquals("Ouvrir Spotify", KeyMap.label("app:com.spotify.tv.android") { "Spotify" })
        assertEquals("Ouvrir com.x", KeyMap.label("app:com.x"))
        assertEquals("Source HDMI 1 · PS5", KeyMap.label(input.action) { "HDMI 1 · PS5" })
        assertEquals("Touche rouge=control_center; Touche 1 (appui long)=app:com.spotify.tv.android", KeyMap.describe(listOf(red, oneLong)))
    }
}
