package com.getisolated.canvasos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CustomTilesTest {
    @Test fun renamingAddsReplacesAndRemovesOneEntry() {
        var json = Labels.with("", "com.netflix.ninja", "  Films  ")
        assertEquals(mapOf("com.netflix.ninja" to "Films"), Labels.decode(json))
        json = Labels.with(json, "org.xbmc.kodi", "Médiathèque")
        json = Labels.with(json, "com.netflix.ninja", "Netflix ⭐")
        assertEquals(mapOf("com.netflix.ninja" to "Netflix ⭐", "org.xbmc.kodi" to "Médiathèque"), Labels.decode(json))
        json = Labels.with(json, "com.netflix.ninja", "   ")
        assertEquals(mapOf("org.xbmc.kodi" to "Médiathèque"), Labels.decode(json))
        json = Labels.with(json, "org.xbmc.kodi", null)
        assertTrue(Labels.decode(json).isEmpty())
    }

    @Test fun namesAreCappedAndBrokenJsonIsNoNames() {
        val long = "x".repeat(200)
        assertEquals(Labels.MAX, Labels.decode(Labels.with("{}", "a.b", long))["a.b"]!!.length)
        assertTrue(Labels.decode("not json").isEmpty())
        assertTrue(Labels.decode("").isEmpty())
        assertNull(Labels.decode("""{"a.b":""}""")["a.b"])
        // A broken value is not carried over into the next save.
        assertEquals(mapOf("c.d" to "C"), Labels.decode(Labels.with("[1,2]", "c.d", "C")))
    }

    @Test fun uploadedPictureIsLookedForBeforePushedOne() {
        val files = File("/data/user/0/com.getisolated.canvasos/files")
        val ext = File("/sdcard/Android/data/com.getisolated.canvasos/files")
        assertEquals(
            listOf(File(files, "tiles/com.netflix.ninja.jpg"), File(ext, "tiles/com.netflix.ninja.jpg")),
            AppRepo.customTilePaths(files, ext, "com.netflix.ninja"),
        )
        assertEquals(listOf(File(files, "tiles/org.xbmc.kodi.jpg")), AppRepo.customTilePaths(files, null, "org.xbmc.kodi"))
    }

    @Test fun onlyPackageNamesMapToAFile() {
        val files = File("/f")
        for (key in listOf(
            "com.getisolated.canvasos:WallpaperActivity",          // canvasOS's own screen
            "input:content://android.media.tv/passthrough/x%2FHW2", // a pinned TV input
            "../../shared_prefs/tvhome",
            "com.example/../x",
            "nodot",
            "",
        )) assertTrue(key, AppRepo.customTilePaths(files, null, key).isEmpty())
    }
}
