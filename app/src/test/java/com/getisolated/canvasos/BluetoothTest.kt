package com.getisolated.canvasos

import com.getisolated.canvasos.Bluetooth.Device
import com.getisolated.canvasos.Bluetooth.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothTest {
    @Test fun audioAndVideoDevicesAreAudio() {
        assertEquals(Kind.AUDIO, Bluetooth.kindOf(0x0418)) // headphones
        assertEquals(Kind.AUDIO, Bluetooth.kindOf(0x0404)) // wearable headset
        assertEquals(Kind.AUDIO, Bluetooth.kindOf(0x0414)) // loudspeaker
        assertEquals(Kind.AUDIO, Bluetooth.kindOf(0x0428)) // hi-fi audio
    }

    @Test fun gamepadsAndJoysticksAreControllers() {
        assertEquals(Kind.CONTROLLER, Bluetooth.kindOf(0x0508)) // gamepad
        assertEquals(Kind.CONTROLLER, Bluetooth.kindOf(0x0504)) // joystick
        // A gamepad that also has a trackpad is still a gamepad.
        assertEquals(Kind.CONTROLLER, Bluetooth.kindOf(0x0588))
    }

    @Test fun keyboardsMiceAndRemotesAreRemotes() {
        assertEquals(Kind.REMOTE, Bluetooth.kindOf(0x0540)) // keyboard
        assertEquals(Kind.REMOTE, Bluetooth.kindOf(0x0580)) // pointing device
        assertEquals(Kind.REMOTE, Bluetooth.kindOf(0x05C0)) // keyboard and pointing
        assertEquals(Kind.REMOTE, Bluetooth.kindOf(0x050C)) // remote control
    }

    @Test fun everythingElseIsOther() {
        assertEquals(Kind.OTHER, Bluetooth.kindOf(0))      // no class at all, as many LE devices
        assertEquals(Kind.OTHER, Bluetooth.kindOf(0x1F00)) // uncategorized
        assertEquals(Kind.OTHER, Bluetooth.kindOf(0x0200)) // phone
        assertEquals(Kind.OTHER, Bluetooth.kindOf(0x0500)) // peripheral that is none of the above
        assertEquals(Kind.OTHER, Bluetooth.kindOf(0x0714)) // wearable
    }

    @Test fun nounsNameWhatTheClassSays() {
        assertEquals("Casque", Bluetooth.nounOf(0x0418, Kind.AUDIO))
        assertEquals("Enceinte", Bluetooth.nounOf(0x0414, Kind.AUDIO))
        assertNull(Bluetooth.nounOf(0x0428, Kind.AUDIO))
        assertEquals("Manette", Bluetooth.nounOf(0x0508, Kind.CONTROLLER))
        assertNull(Bluetooth.nounOf(0x0540, Kind.REMOTE))
    }

    @Test fun inputDevicesAreMatchedByName() {
        assertTrue(Bluetooth.matchesInput("Xbox Wireless Controller", "xbox wireless controller "))
        assertFalse(Bluetooth.matchesInput("Xbox Wireless Controller", "Xbox 360 Controller"))
        assertFalse(Bluetooth.matchesInput("Xbox Wireless Controller", null))
        assertFalse(Bluetooth.matchesInput("", ""))
    }

    private fun device(name: String, connected: Boolean, battery: Int? = null, noun: String? = null, kind: Kind = Kind.AUDIO) =
        Device("00:11:22:33:44:55", name, kind, connected, battery, noun)

    @Test fun oneConnectedDeviceIsNamedWithItsBattery() {
        assertEquals("Casque WH-1000XM4 · connecté · 80 %",
            Bluetooth.summary(listOf(device("WH-1000XM4", true, 80, "Casque"), device("JBL Flip", false))))
    }

    @Test fun batteryIsLeftOutWhenTheDeviceDoesNotReportIt() {
        assertEquals("WH-1000XM4 · connecté", Bluetooth.summary(listOf(device("WH-1000XM4", true))))
    }

    @Test fun nounIsNotRepeatedWhenTheNameHasIt() {
        assertEquals("Casque Bluetooth", device("Casque Bluetooth", true, noun = "Casque").label)
        assertEquals("Manette Pro", device("Pro", true, noun = "Manette", kind = Kind.CONTROLLER).label)
    }

    @Test fun severalConnectedDevicesAreCounted() {
        assertEquals("2 appareils connectés", Bluetooth.summary(listOf(device("A", true), device("B", true, 50), device("C", false))))
    }

    @Test fun noConnectedDeviceSaysSo() {
        assertEquals("Aucun appareil connecté", Bluetooth.summary(emptyList()))
        assertEquals("Aucun appareil connecté", Bluetooth.summary(listOf(device("A", false))))
    }

    @Test fun sheetLinesShowTheStateAndOnlyConnectedDevicesShowBattery() {
        assertEquals("JBL Flip · déconnecté", Bluetooth.status(device("JBL Flip", false, 40)))
        assertEquals("Manette Pro · connecté · 5 %", Bluetooth.status(device("Pro", true, 5, "Manette", Kind.CONTROLLER)))
    }

    @Test fun longNamesAreCutShort() {
        val line = Bluetooth.status(device("Enceinte Bluetooth Salon Etage Parents", true))
        assertEquals("Enceinte Bluetooth Salon… · connecté", line)
    }
}
