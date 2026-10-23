package com.getisolated.canvasos

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneManifestTest {
    @Test fun manifestIsScopedToTheToken() {
        val m = phoneManifest("abc123")
        assertEquals("/abc123/", m.getString("start_url"))
        assertEquals("/abc123/", m.getString("scope"))
        assertEquals("standalone", m.getString("display"))
        assertEquals(4, m.getJSONArray("icons").length())
    }
}
