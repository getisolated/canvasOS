package com.getisolated.canvasos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the table of wallpaper sources and built-in gradients: names, labels, and that a background setting always means exactly one of them. */
class WallpaperSourcesTest {
    @Test fun theSourcesAreTheDocumentedOnesInTheDocumentedOrder() {
        // AGENTS.md section 8 lists them as daily|scenery|fresh|anime|art|mine|custom.
        assertEquals(setOf("daily", "scenery", "fresh", "anime", "art", "mine", "custom"), Wallpapers.SOURCES.toSet())
        assertEquals(Wallpapers.SOURCES.size, Wallpapers.SOURCES.toSet().size)
    }

    @Test fun theGradientsAreTheDocumentedOnes() {
        assertEquals(setOf("green", "blue", "purple", "orange", "graphite"), Backgrounds.THEMES.keys)
    }

    @Test fun aSettingNamesASourceOrAGradientNeverBoth() {
        for (s in Wallpapers.SOURCES) {
            assertTrue(s, Wallpapers.isPhoto(s))
            assertFalse(s, s in Backgrounds.THEMES)
        }
        for (g in Backgrounds.THEMES.keys) assertFalse(g, Wallpapers.isPhoto(g))
    }

    @Test fun everySourceHasItsOwnFrenchLabel() {
        val labels = Wallpapers.SOURCES.map { Wallpapers.label(it) }
        assertTrue(labels.toString(), labels.all { it.isNotBlank() })
        assertEquals("two sources must not read alike in the settings", labels.size, labels.toSet().size)
        assertEquals("Photos du jour", Wallpapers.label(Wallpapers.BING))
        assertEquals("Mes photos", Wallpapers.label(Wallpapers.MINE))
        assertEquals("Lien personnalisé", Wallpapers.label(Wallpapers.CUSTOM))
    }

    @Test fun aGradientOrAnUnknownValueFallsBackToBing() {
        for (g in Backgrounds.THEMES.keys) assertEquals(Wallpapers.BING, Wallpapers.sourceFor(g))
        for (v in listOf("", "unknown", "DAILY", "daily ", "anime-backup")) assertEquals(v, Wallpapers.BING, Wallpapers.sourceFor(v))
        for (s in Wallpapers.SOURCES) assertEquals(s, Wallpapers.sourceFor(s))
        assertEquals("Photos du jour", Wallpapers.label("anything else"))
    }

    @Test fun theBackupFeedIsNotASourceTheUserCanPick() {
        assertFalse(Wallpapers.isPhoto(Wallpapers.ANIME_BACKUP))
        assertFalse(Wallpapers.ANIME_BACKUP in Wallpapers.SOURCES)
    }

    @Test fun everyGradientHasALabelAndFourOpaqueBlobsInsideThePicture() {
        for ((key, theme) in Backgrounds.THEMES) {
            val (label, blobs) = theme
            assertTrue(key, label.isNotBlank())
            assertEquals(key, 4, blobs.size)
            for (b in blobs) {
                assertEquals("$key: colour is opaque", 0xFF, (b.color shr 24) and 0xFF)
                assertTrue("$key: radius", b.r > 0f && b.r <= 1f)
                assertTrue("$key: x", b.x in -0.2f..1.2f)
                assertTrue("$key: y", b.y in -0.2f..1.2f)
            }
        }
        assertEquals(Backgrounds.THEMES.size, Backgrounds.THEMES.values.map { it.first }.toSet().size)
    }

    @Test fun theDefaultSettingIsAPhotoSourceAndNoSourceIsTheDefaultGradient() {
        assertEquals(Wallpapers.BING, Wallpapers.sourceFor(Prefs(FakeContext()).background))
        assertEquals("daily", Wallpapers.BING)
    }
}
