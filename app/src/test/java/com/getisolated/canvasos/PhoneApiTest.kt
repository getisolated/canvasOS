package com.getisolated.canvasos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PhoneApiTest {
    @Test fun everyRemoteKeyMapsToARealKeycode() {
        val page = listOf("UP", "DOWN", "LEFT", "RIGHT", "CENTER", "BACK", "HOME", "MENU", "VOLUME_UP", "VOLUME_DOWN",
            "VOLUME_MUTE", "MEDIA_PLAY_PAUSE", "MEDIA_REWIND", "MEDIA_FAST_FORWARD", "MEDIA_PREVIOUS", "MEDIA_NEXT", "DEL")
        for (k in page) assertNotNull(k, PhoneApi.keycode(k))
        assertEquals("KEYCODE_DPAD_UP", PhoneApi.keycode("UP"))
        assertEquals("KEYCODE_DPAD_CENTER", PhoneApi.keycode("CENTER"))
        assertEquals("KEYCODE_DEL", PhoneApi.keycode("DEL"))
    }

    @Test fun nothingThatTurnsTheTvOffIsAllowed() {
        for (k in listOf("POWER", "SLEEP", "SOFT_SLEEP", "WAKEUP", "KEYCODE_POWER", "KEYCODE_SLEEP", "up", "", "UP; reboot", null))
            assertNull(k, PhoneApi.keycode(k))
        for (code in PhoneApi.KEYS.values)
            assertFalse(code, code.contains("POWER") || code.contains("SLEEP") || code.contains("WAKE"))
    }

    @Test fun textBecomesTypeableAscii() {
        assertEquals("Cafe creme brulee", PhoneApi.typeable("Café crème brûlée"))
        assertEquals("l'ete \"chaud\" - oeuf...", PhoneApi.typeable("l’été “chaud” – œuf…"))
        assertEquals("\" a b \"", PhoneApi.typeable("« a b »"))
        assertEquals("", PhoneApi.typeable("日本語"))
        assertEquals(PhoneApi.MAX_TEXT, PhoneApi.typeable("a".repeat(2000)).length)
    }

    @Test fun textArgumentsAreQuotedWithSpacesAsPercentS() {
        assertEquals("text 'hello%sworld'", PhoneApi.textArgs("hello world"))
        assertEquals("text 'it'\\''s'", PhoneApi.textArgs("it's"))
        assertEquals("text '\$(reboot)'", PhoneApi.textArgs("\$(reboot)"))
        assertNull(PhoneApi.textArgs("日本"))
        assertNull(PhoneApi.textArgs(""))
    }

    @Test fun shellQuoteSurvivesARealShell() {
        assumeTrue(File("/bin/sh").canExecute())
        for (s in listOf("it's", "'; reboot; '", "\$(id) `id` \"x\" \\ & | ; < > *", "''", "a'b'c")) {
            val p = ProcessBuilder("/bin/sh", "-c", "printf %s " + PhoneApi.shellQuote(s)).redirectErrorStream(true).start()
            assertEquals(s, p.inputStream.bufferedReader().readText())
            p.waitFor()
        }
    }

    @Test fun neighbouringKeysShareOneInputCall() {
        assertEquals("input keyevent KEYCODE_DPAD_UP KEYCODE_DPAD_UP; input text 'a'; input keyevent KEYCODE_DEL",
            PhoneApi.script(listOf("KEYCODE_DPAD_UP", "KEYCODE_DPAD_UP", "text 'a'", "KEYCODE_DEL")))
        assertEquals("input text 'a'; input text 'b'", PhoneApi.script(listOf("text 'a'", "text 'b'")))
        assertEquals("", PhoneApi.script(emptyList()))
    }

    @Test fun onlyAZipWithAManifestIsAnApk() {
        val dir = createTempDirectory()
        try {
            fun zip(name: String, vararg entries: String) = File(dir, name).also { f ->
                ZipOutputStream(f.outputStream()).use { z -> entries.forEach { z.putNextEntry(ZipEntry(it)); z.write(1); z.closeEntry() } }
            }
            assertTrue(PhoneApi.isApk(zip("app.apk", "AndroidManifest.xml", "classes.dex")))
            assertFalse(PhoneApi.isApk(zip("photos.zip", "a.jpg")))
            assertFalse(PhoneApi.isApk(File(dir, "text.apk").apply { writeText("not an apk at all") }))
            assertFalse(PhoneApi.isApk(File(dir, "empty.apk").apply { writeBytes(ByteArray(0)) }))
            assertFalse(PhoneApi.isApk(File(dir, "missing.apk")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun tokensAreLongRandomAndComparedExactly() {
        val a = PhoneApi.newToken()
        assertEquals(PhoneApi.TOKEN_LENGTH, a.length)
        assertTrue(a.all { it in "abcdefghjkmnpqrstuvwxyz23456789" })
        assertNotEquals(a, PhoneApi.newToken())
        assertTrue(PhoneApi.sameToken(a, a))
        assertFalse(PhoneApi.sameToken(a.dropLast(1), a))
        assertFalse(PhoneApi.sameToken("", ""))
    }

    @Test fun commonInstallFailuresAreExplained() {
        assertTrue(PhoneApi.installHint("Failure [INSTALL_FAILED_VERIFICATION_FAILURE]")!!.contains("TCL"))
        assertNotNull(PhoneApi.installHint("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package x signatures do not match]"))
        assertNull(PhoneApi.installHint("Failure [SOMETHING_NEW]"))
    }

    private fun createTempDirectory(): File = java.nio.file.Files.createTempDirectory("phoneapi").toFile()
}
