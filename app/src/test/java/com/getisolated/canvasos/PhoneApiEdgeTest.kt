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

/** Guards the phone keyboard's edge cases (nothing typeable, the length cap, odd characters) and the order and batching of what is sent to `input`. */
class PhoneApiEdgeTest {
    // ---- typeable ----

    @Test fun emptyOrUntypeableTextGivesNoArguments() {
        assertEquals("", PhoneApi.typeable(""))
        assertNull(PhoneApi.textArgs(""))
        assertNull("emoji only", PhoneApi.textArgs("😀🎬"))
        assertNull("letters of other scripts only", PhoneApi.textArgs("日本語Ελληνικάкириллица"))
        assertEquals("their spaces are all that is left of a sentence in them", "text '%s%s'", PhoneApi.textArgs("日本語 Ελληνικά кириллица"))
        assertNull("control characters only", PhoneApi.textArgs("\n\r\u0000\u0007"))
    }

    @Test fun combiningAccentsOnTheirOwnAreNothing() {
        assertEquals("", PhoneApi.typeable("́̀̂̈"))
        assertNull(PhoneApi.textArgs("́̀"))
        assertEquals("only the accents go from a decomposed letter", "e", PhoneApi.typeable("é"))
        assertEquals("an accent on a letter with no ASCII twin leaves nothing of it", "", PhoneApi.typeable("ќ"))
    }

    @Test fun accentedLettersLoseTheirAccentsInBothForms() {
        assertEquals("Eleve a l'ecole", PhoneApi.typeable("Élève à l’école"))
        // Precomposed (NFC) and decomposed (NFD) spellings of the same text type the same.
        assertEquals(PhoneApi.typeable("çñüÿ"), PhoneApi.typeable("çñüÿ"))
        assertEquals("cnuy", PhoneApi.typeable("çñüÿ"))
        assertEquals("CAFE", PhoneApi.typeable("CAFÉ"))
    }

    @Test fun ligaturesAndMarksPhonesInsertBecomeTheirAsciiLookAlikes() {
        assertEquals("oeuvre OE ae AE strasse", PhoneApi.typeable("œuvre Œ æ Æ straße"))
        assertEquals("'a'", PhoneApi.typeable("‘a’"))
        assertEquals("'b'", PhoneApi.typeable("‚b′"))
        assertEquals("\"c\" \"d\"", PhoneApi.typeable("“c” „d”"))
        assertEquals("\"e\" \"f\"", PhoneApi.typeable("«e» ″f″"))
        assertEquals("a-b-c-d", PhoneApi.typeable("a–b—c−d"))
        assertEquals("Wait...", PhoneApi.typeable("Wait…"))
    }

    @Test fun everyKindOfSpaceBecomesAPlainOneAndOtherWhiteSpaceGoes() {
        assertEquals("a b c d", PhoneApi.typeable("a b\u00a0c\u202fd"))
        assertEquals("a b", PhoneApi.typeable("a\tb"))
        assertEquals("newlines are dropped, not turned into Enter", "ab", PhoneApi.typeable("a\nb"))
        assertEquals("ab", PhoneApi.typeable("a\r\nb"))
    }

    @Test fun printableAsciiPassesThroughUntouched() {
        val all = (' '..'~').joinToString("")
        assertEquals(all, PhoneApi.typeable(all))
        assertEquals("", PhoneApi.typeable("\u007f"))
    }

    @Test fun theLengthCapCountsTheTypedCharactersNotTheTypedOnes() {
        assertEquals(PhoneApi.MAX_TEXT, PhoneApi.typeable("a".repeat(PhoneApi.MAX_TEXT)).length)
        assertEquals(PhoneApi.MAX_TEXT, PhoneApi.typeable("a".repeat(PhoneApi.MAX_TEXT + 1)).length)
        assertEquals(PhoneApi.MAX_TEXT - 1, PhoneApi.typeable("a".repeat(PhoneApi.MAX_TEXT - 1)).length)
        // The cap is on what remains: untypeable characters do not use it up.
        assertEquals(PhoneApi.MAX_TEXT, PhoneApi.typeable("é日".repeat(PhoneApi.MAX_TEXT * 2)).length)
        // "…" becomes three characters, and the cut falls inside them.
        assertEquals("a".repeat(PhoneApi.MAX_TEXT - 1) + ".", PhoneApi.typeable("a".repeat(PhoneApi.MAX_TEXT - 1) + "…"))
        // The first characters are the ones kept.
        assertTrue(PhoneApi.typeable("start" + "x".repeat(2000)).startsWith("start"))
    }

    @Test fun anOverlongTextIsCutBeforeItIsQuoted() {
        val args = PhoneApi.textArgs("b".repeat(5000))!!
        assertEquals("text '" + "b".repeat(PhoneApi.MAX_TEXT) + "'", args)
        // Spaces become %s after the cut, so a text of spaces is cut at 500 spaces.
        val spaces = PhoneApi.textArgs(" ".repeat(900))!!
        assertEquals("text '" + "%s".repeat(PhoneApi.MAX_TEXT) + "'", spaces)
    }

    // ---- textArgs ----

    @Test fun spacesBecomePercentSAndQuotesAreEscaped() {
        assertEquals("text 'a%sb%sc'", PhoneApi.textArgs("a b c"))
        assertEquals("text '%s'", PhoneApi.textArgs(" "))
        assertEquals("text '%s%s'", PhoneApi.textArgs("  "))
        assertEquals("text 'l'\\''ete'", PhoneApi.textArgs("l’été"))
        assertEquals("text ''\\'''\\'''", PhoneApi.textArgs("''"))
    }

    @Test fun shellMetacharactersStayInsideTheQuotes() {
        for (s in listOf("a; reboot", "\$(id)", "`id`", "a && b", "a | b", "a > /sdcard/x", "*", "~", "#", "!", "\\", "a\"b")) {
            val args = PhoneApi.textArgs(s)!!
            assertTrue(args, args.startsWith("text '") && args.endsWith("'"))
        }
        assumeTrue(File("/bin/sh").canExecute())
        // Through a real shell, the one word that `input` would get back is the text with %s for spaces.
        for (s in listOf("a; echo pwned", "\$(echo pwned)", "`echo pwned`", "x' ; echo pwned ; '", "a && echo pwned", "*")) {
            val p = ProcessBuilder("/bin/sh", "-c", "printf '%s' " + PhoneApi.textArgs(s)!!.removePrefix("text ")).redirectErrorStream(true).start()
            assertEquals(s.replace(" ", "%s"), p.inputStream.bufferedReader().readText())
            p.waitFor()
        }
    }

    @Test fun shellQuoteHandlesTheEmptyStringAndLoneQuotes() {
        assertEquals("''", PhoneApi.shellQuote(""))
        assertEquals("''\\'''", PhoneApi.shellQuote("'"))
        assertEquals("'a b'", PhoneApi.shellQuote("a b"))
    }

    // ---- script ----

    @Test fun theScriptKeepsTheOrderOfKeysAndText() {
        assertEquals("input text 'a'; input keyevent KEYCODE_DEL KEYCODE_DEL; input text 'b'",
            PhoneApi.script(listOf("text 'a'", "KEYCODE_DEL", "KEYCODE_DEL", "text 'b'")))
        assertEquals("input keyevent KEYCODE_HOME; input text 'x'; input keyevent KEYCODE_BACK",
            PhoneApi.script(listOf("KEYCODE_HOME", "text 'x'", "KEYCODE_BACK")))
    }

    @Test fun aSingleItemNeedsNoSeparator() {
        assertEquals("input keyevent KEYCODE_HOME", PhoneApi.script(listOf("KEYCODE_HOME")))
        assertEquals("input text 'a'", PhoneApi.script(listOf("text 'a'")))
    }

    @Test fun aRunOfKeysIsOneCallWhateverItsLength() {
        val keys = List(40) { "KEYCODE_DPAD_DOWN" }
        assertEquals("input keyevent " + keys.joinToString(" "), PhoneApi.script(keys))
        assertEquals(1, PhoneApi.script(keys).split("; ").size)
    }

    @Test fun anythingThatIsNotAKeycodeGoesToInputAsIsAndBreaksTheRun() {
        assertEquals("input keyevent KEYCODE_A; input tap 10 20; input keyevent KEYCODE_B", PhoneApi.script(listOf("KEYCODE_A", "tap 10 20", "KEYCODE_B")))
        // "KEYCODE_" must lead the item: text that merely contains it is typed, not pressed.
        assertEquals("input text 'xKEYCODE_DEL'", PhoneApi.script(listOf("text 'xKEYCODE_DEL'")))
    }

    @Test fun textArgsAndKeysComposeIntoOneLine() {
        val items = listOf(PhoneApi.textArgs("Hello world")!!, PhoneApi.keycode("CENTER")!!, PhoneApi.keycode("DOWN")!!, PhoneApi.textArgs("ok")!!)
        assertEquals("input text 'Hello%sworld'; input keyevent KEYCODE_DPAD_CENTER KEYCODE_DPAD_DOWN; input text 'ok'", PhoneApi.script(items))
    }

    @Test fun aLongPressIsItsOwnKeyeventCallAndSplitsTheRun() {
        val long = PhoneApi.keyInput("CENTER_LONG")!!
        val keys = { n: String -> PhoneApi.keyInput(n)!! }
        assertEquals("input keyevent KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN; input keyevent --longpress KEYCODE_DPAD_CENTER; input keyevent KEYCODE_DPAD_UP",
            PhoneApi.script(listOf(keys("DOWN"), keys("DOWN"), long, keys("UP"))))
        // Two long presses in a row stay two: one --longpress applies to every keycode after it.
        assertEquals("input keyevent --longpress KEYCODE_DPAD_CENTER; input keyevent --longpress KEYCODE_DPAD_CENTER",
            PhoneApi.script(listOf(long, long)))
        assertEquals("input text 'a'; input keyevent --longpress KEYCODE_DPAD_CENTER; input keyevent KEYCODE_ENTER",
            PhoneApi.script(listOf(PhoneApi.textArgs("a")!!, long, keys("ENTER"))))
        assertFalse(PhoneApi.script(listOf(long)).contains("input --longpress"))
    }

    // ---- keys ----

    @Test fun keyNamesAreMatchedExactly() {
        for (bad in listOf("up", "Up", " UP", "UP ", "UP\n", "KEYCODE_DPAD_UP", "DPAD_UP", "VOLUME", "MEDIA_STOP", "SEARCH", "TV", "SETTINGS", "ASSIST",
            "CENTER_LONG ", "Center_Long", "LONG", "--longpress", "UP_LONG", "HOME_LONG", "KEYCODE_DPAD_CENTER_LONG")) {
            assertNull(bad, PhoneApi.keycode(bad))
            assertNull(bad, PhoneApi.keyInput(bad))
        }
    }

    @Test fun everyKeyIsAKeycodeNameAndNoTwoNamesShareOne() {
        for ((name, code) in PhoneApi.KEYS) {
            assertTrue("$name -> $code", code.matches(Regex("KEYCODE_[A-Z_]+")))
            assertTrue(name, name.matches(Regex("[A-Z_]+")))
        }
        assertEquals(PhoneApi.KEYS.size, PhoneApi.KEYS.values.toSet().size)
    }

    @Test fun aLongPressIsOnlyEverOfAnAllowedKeyUnderItsOwnName() {
        for ((name, key) in PhoneApi.LONG_PRESSES) {
            assertTrue(name, name.matches(Regex("[A-Z_]+_LONG")))
            assertFalse("$name shadows a plain key", name in PhoneApi.KEYS)
            assertEquals("keyevent --longpress " + PhoneApi.KEYS.getValue(key), PhoneApi.keyInput(name))
        }
        assertEquals(PhoneApi.LONG_PRESSES.size, PhoneApi.LONG_PRESSES.values.toSet().size)
    }

    // ---- install hints ----

    @Test fun eachKnownInstallFailureHasItsOwnFrenchExplanation() {
        val outputs = listOf(
            "Failure [INSTALL_FAILED_VERIFICATION_FAILURE]",
            "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package a.b signatures do not match the previously installed version]",
            "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]",
            "Failure [INSTALL_FAILED_OLDER_SDK]",
            "Failure [INSTALL_FAILED_NO_MATCHING_ABIS: Failed to extract native libraries, res=-113]",
            "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]",
            "Failure [INSTALL_PARSE_FAILED_NOT_APK: Failed to parse /data/local/tmp/x.apk]",
            "Failure [INSTALL_FAILED_INVALID_APK: Split lib_slice_0_apk was defined multiple times]",
        )
        val hints = outputs.map { out -> PhoneApi.installHint(out).also { assertNotNull(out, it) } }
        // The two invalid-file codes share one message; every other failure has its own.
        assertEquals(outputs.size - 1, hints.toSet().size)
        assertEquals(hints[6], hints[7])
        hints.forEach { assertTrue(it, it!!.endsWith(".") && it.length > 20) }
    }

    @Test fun unknownOrEmptyInstallOutputHasNoHint() {
        assertNull(PhoneApi.installHint(""))
        assertNull(PhoneApi.installHint("Success"))
        assertNull(PhoneApi.installHint("Failure [INSTALL_FAILED_ALREADY_EXISTS]"))
        assertNull(PhoneApi.installHint("install_failed_older_sdk"))
    }

    // ---- tokens ----

    @Test fun twoTokensDifferInContentAndAreComparedWholeNotByPrefix() {
        val a = PhoneApi.newToken()
        var b = PhoneApi.newToken()
        while (b == a) b = PhoneApi.newToken()
        assertFalse(PhoneApi.sameToken(b, a))
        assertFalse(PhoneApi.sameToken(a + "x", a))
        assertFalse(PhoneApi.sameToken(a.dropLast(1) + (if (a.last() == 'a') 'b' else 'a'), a))
        assertFalse(PhoneApi.sameToken(" $a", a))
        assertTrue(PhoneApi.sameToken(String(a.toCharArray()), a))
        assertFalse("an empty expected token matches nothing", PhoneApi.sameToken("anything", ""))
        assertNotEquals(a, b)
    }

    @Test fun manyTokensAllUseTheAlphabetWithoutLookAlikes() {
        val chars = HashSet<Char>()
        repeat(300) { PhoneApi.newToken().also { assertEquals(PhoneApi.TOKEN_LENGTH, it.length) }.forEach { c -> chars += c } }
        assertTrue(chars.toString(), chars.none { it in "01lLoOiI" })
        assertTrue("the whole alphabet gets used", chars.size > 25)
    }
}
