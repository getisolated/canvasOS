package com.getisolated.canvasos

import com.getisolated.canvasos.tv.Input
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards how a pinned HDMI input is keyed on the grid and kept in Prefs.pinnedInputs, so a pin survives a restart. */
class InputTileTest {
    private val hdmi = Input("content://android.media.tv/passthrough/com.sony.dtv.tvx%2F.hdmi%2FHW2", "HDMI 2", "Switch", true)

    @Test fun theKeyIsThePrefixedUriAndIsRecognised() {
        assertEquals("input:" + hdmi.uri, InputTile.key(hdmi))
        assertTrue(InputTile.isInput(InputTile.key(hdmi)))
        assertTrue(InputTile.isInput("input:"))
        assertFalse(InputTile.isInput("com.netflix.ninja"))
        assertFalse(InputTile.isInput("com.getisolated.canvasos:WallpaperActivity"))
        assertFalse(InputTile.isInput(""))
        assertFalse("the prefix has to lead", InputTile.isInput("x input:y"))
        assertFalse("and is case sensitive", InputTile.isInput("Input:y"))
    }

    @Test fun anInputSurvivesTheRoundTripExceptItsConnectionState() {
        val back = InputTile.decode(InputTile.encode(hdmi))!!
        assertEquals(hdmi.copy(connected = false), back)
        assertEquals("HDMI 2 · Switch", back.name)
        assertEquals(InputTile.key(hdmi), InputTile.key(back))
    }

    @Test fun anEmptyLabelRoundTripsAndKeepsTheBareTitle() {
        val bare = Input("tcl-input://", "Sources TCL", "", false)
        val back = InputTile.decode(InputTile.encode(bare))!!
        assertEquals(bare, back)
        assertEquals("Sources TCL", back.name)
    }

    @Test fun theMiddleDotInALabelIsNotTheSeparator() {
        // Input.name joins title and label with " · ", and a title or label may hold one too.
        val odd = Input("extInput:hdmi?port=1", "HDMI 1 · ARC", "Console · salon", true)
        val back = InputTile.decode(InputTile.encode(odd))!!
        assertEquals("HDMI 1 · ARC", back.title)
        assertEquals("Console · salon", back.label)
        assertEquals("HDMI 1 · ARC · Console · salon", back.name)
        assertEquals(2, InputTile.encode(odd).count { it == '\u001f' })
    }

    @Test fun accentsAndSymbolsInNamesSurvive() {
        val i = Input("xiaomi-input:23", "Entrée HDMI 1", "Télé « salon » ★", false)
        assertEquals(i, InputTile.decode(InputTile.encode(i)))
    }

    @Test fun malformedStringsAreNoInput() {
        assertNull(InputTile.decode(""))
        assertNull(InputTile.decode("just-a-uri"))
        assertNull("two fields", InputTile.decode("a\u001fb"))
        assertNull("four fields", InputTile.decode("a\u001fb\u001fc\u001fd"))
        assertNull("a bare middle dot is not a separator", InputTile.decode("a · b · c"))
        assertNull("the key is not the stored form", InputTile.decode(InputTile.key(hdmi)))
    }

    @Test fun threeEmptyFieldsDecodeToAnEmptyInputRatherThanFailing() {
        // Three fields are all decode asks for; AppRepo then shows a tile with a blank name.
        val i = InputTile.decode("\u001f\u001f")!!
        assertEquals(Input("", "", "", false), i)
        assertEquals("input:", InputTile.key(i))
    }

    @Test fun aPinnedInputIsFoundByItsUriWhateverItsLabel() {
        // HomeActivity.isPinned compares decoded uris, so renaming an input on the TV keeps its pin.
        val stored = InputTile.encode(hdmi)
        val renamed = hdmi.copy(label = "Autre nom")
        assertEquals(renamed.uri, InputTile.decode(stored)!!.uri)
        assertEquals(InputTile.key(renamed), InputTile.key(InputTile.decode(stored)!!))
        assertTrue(InputTile.decode(InputTile.encode(hdmi.copy(uri = "other")))!!.uri != hdmi.uri)
    }
}
