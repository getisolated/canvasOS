package com.getisolated.canvasos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Guards how the Wi-Fi name Android reports is cleaned before the screens show it, and when it is dropped as unknown. */
class NetTest {
    @Test fun aQuotedNameLosesOnlyItsOuterQuotes() {
        assertEquals("Livebox-1234", Net.cleanSsid("\"Livebox-1234\""))
        assertEquals("Chez \"Jo\"", Net.cleanSsid("\"Chez \"Jo\"\""))
        assertEquals("a name that merely ends with a quote keeps it", "Livebox\"", Net.cleanSsid("Livebox\""))
        assertEquals("and so does one that merely starts with one", "\"Livebox", Net.cleanSsid("\"Livebox"))
    }

    @Test fun spacesInsideAreKeptAndOutsideAreDropped() {
        assertEquals("Mon Wi-Fi 5G", Net.cleanSsid("\"Mon Wi-Fi 5G\""))
        assertEquals("Mon Wi-Fi", Net.cleanSsid("  Mon Wi-Fi \n"))
        assertEquals("Mon Wi-Fi", Net.cleanSsid("\" Mon Wi-Fi \""))
    }

    @Test fun accentsAndEmojiSurvive() {
        assertEquals("Réseau de Zoé 🏠", Net.cleanSsid("\"Réseau de Zoé 🏠\""))
    }

    @Test fun whatAndroidAnswersWhenItMayNotTellIsNoName() {
        assertNull(Net.cleanSsid("<unknown ssid>"))
        assertNull(Net.cleanSsid("\"<unknown ssid>\""))
        assertNull(Net.cleanSsid(" <unknown ssid> "))
        assertNull(Net.cleanSsid("0x"))
        assertNull(Net.cleanSsid("\"0x\""))
        assertNull(Net.cleanSsid(""))
        assertNull(Net.cleanSsid("   "))
        assertNull(Net.cleanSsid("\"\""))
        assertNull(Net.cleanSsid("\" \""))
        assertNull(Net.cleanSsid(null))
    }

    @Test fun aNetworkNamedLikeAMarkerButLongerIsAName() {
        assertEquals("0x1F", Net.cleanSsid("\"0x1F\""))
        assertEquals("<unknown ssid> 2", Net.cleanSsid("<unknown ssid> 2"))
    }
}
