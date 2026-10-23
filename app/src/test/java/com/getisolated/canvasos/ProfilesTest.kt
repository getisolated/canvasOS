package com.getisolated.canvasos

import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ProfilesTest {
    private val main = Profile(Profiles.MAIN, "", 0, false)
    private val kids = Profile("k1", "Enfants", 2, true)
    private val yass = Profile("y1", "Yassine", 3, false)

    // ------------------------------------------------------------------ the pure model

    @Test fun theMainProfileIsAlwaysThereAndFirst() {
        assertEquals(listOf(main), Profiles.decode(""))
        assertEquals(listOf(main), Profiles.decode("not json"))
        assertEquals(listOf(main), Profiles.decode("{}"))
        // The format Prefs.profiles documents: the main profile need not be listed.
        assertEquals(listOf(main, Profile("p1", "Yassine", 3, false)),
            Profiles.decode("""[{"id":"p1","name":"Yassine","color":3,"restricted":false}]"""))
        // Listed after others, it still comes first, with what was saved for it.
        assertEquals(listOf(Profile(Profiles.MAIN, "Papa", 1, false), kids),
            Profiles.decode("""[{"id":"k1","name":"Enfants","color":2,"restricted":true},{"id":"main","name":"Papa","color":1}]"""))
    }

    @Test fun encodeDecodeRoundTrip() {
        val list = listOf(Profile(Profiles.MAIN, "Papa", 5, false), kids, yass, Profile("x", "« Invité » é", 0, false))
        assertEquals(list, Profiles.decode(Profiles.encode(list)))
    }

    @Test fun decodeRepairsWhatItReads() {
        val json = """[
            {"id":"a","name":"  Léa  ","color":-1},
            {"id":"a","name":"Doublon"},
            {"id":"bad id!","name":"Non"},
            {"name":"sans id"},
            7,
            {"id":"b","name":"   ","color":13,"restricted":true},
            {"id":"main","name":"   "}
        ]"""
        assertEquals(listOf(main, Profile("a", "Léa", 5, false), Profile("b", Profiles.DEFAULT_NAME, 1, true)), Profiles.decode(json))
    }

    @Test fun displayNames() {
        assertEquals(Profiles.MAIN_NAME, Profiles.displayName(main))
        assertEquals("Papa", Profiles.displayName(main.copy(name = "Papa")))
        assertEquals("Enfants", Profiles.displayName(kids))
        assertEquals(Profiles.DEFAULT_NAME, Profiles.displayName(Profile("z", "", 0, false)))
    }

    @Test fun addTakesTheNextFreeColourAndRefusesTakenOrBadIds() {
        var list = listOf(main)
        list = Profiles.add(list, "k1", "  Enfants ", restricted = true)
        assertEquals(Profile("k1", "Enfants", 1, true), list.last())
        list = Profiles.add(list, "y1", "Yassine")
        assertEquals(2, list.last().color)
        assertSame(list, Profiles.add(list, "k1", "Autre"))
        assertSame(list, Profiles.add(list, Profiles.MAIN, "Autre"))
        assertSame(list, Profiles.add(list, "bad id", "Autre"))
        assertEquals(Profiles.DEFAULT_NAME, Profiles.add(list, "n1", "   ").last().name)
        assertEquals(Profiles.MAX_NAME, Profiles.add(list, "n1", "x".repeat(100)).last().name.length)
        // A colour asked for is kept, folded into the six.
        assertEquals(4, Profiles.add(list, "n1", "N", color = 10).last().color)
    }

    @Test fun coloursGoRoundOnceAllAreTaken() {
        var list = listOf(main)
        repeat(Profiles.COLORS.size - 1) { list = Profiles.add(list, "p$it", "P$it") }
        assertEquals((0 until Profiles.COLORS.size).toList(), list.map { it.color })
        assertEquals(list.size % Profiles.COLORS.size, Profiles.nextColor(list))
        assertEquals(Profiles.COLORS.size, Profiles.COLOR_NAMES.size)
    }

    @Test fun renameColourAndRestrictedTouchOneProfile() {
        val list = listOf(main, kids, yass)
        assertEquals(listOf(main, kids.copy(name = "Les petits"), yass), Profiles.rename(list, "k1", " Les petits "))
        assertEquals(Profiles.DEFAULT_NAME, Profiles.byId(Profiles.rename(list, "k1", ""), "k1")!!.name)
        // The main profile renamed to nothing goes back to "Principal" (and its "P").
        assertEquals("", Profiles.byId(Profiles.rename(Profiles.rename(list, Profiles.MAIN, "Papa"), Profiles.MAIN, " "), Profiles.MAIN)!!.name)
        assertEquals(0, Profiles.byId(Profiles.setColor(list, "y1", Profiles.COLORS.size), "y1")!!.color)
        assertEquals(Profiles.COLORS.size - 1, Profiles.byId(Profiles.setColor(list, "y1", -1), "y1")!!.color)
        assertFalse(Profiles.byId(Profiles.setRestricted(list, "k1", false), "k1")!!.restricted)
        assertTrue(Profiles.byId(Profiles.setRestricted(list, Profiles.MAIN, true), Profiles.MAIN)!!.restricted)
        assertEquals(list, Profiles.rename(list, "nope", "X"))
    }

    @Test fun deleteNeverTakesTheMainTheCurrentOrTheLastProfile() {
        val list = listOf(main, kids, yass)
        assertEquals(listOf(main, yass), Profiles.delete(list, "k1", Profiles.MAIN))
        assertFalse(Profiles.canDelete(list, Profiles.MAIN, "k1"))
        assertFalse(Profiles.canDelete(list, "k1", "k1"))
        assertFalse(Profiles.canDelete(list, "nope", Profiles.MAIN))
        assertFalse(Profiles.canDelete(listOf(main), Profiles.MAIN, "x"))
        assertEquals(list, Profiles.delete(list, "y1", "y1"))
    }

    @Test fun lookups() {
        val list = listOf(main, kids, yass)
        assertEquals("k1", Profiles.byName(list, " enfants ")?.id)
        assertEquals(Profiles.MAIN, Profiles.byName(list, "principal")?.id)
        assertNull(Profiles.byName(list, "Invité"))
        assertEquals("y1", Profiles.resolve(list, "y1"))
        assertEquals(Profiles.MAIN, Profiles.resolve(list, "gone"))
        assertEquals("tvhome", Profiles.storeName(Profiles.MAIN))
        assertEquals("tvhome-k1", Profiles.storeName("k1"))
    }

    @Test fun newIdsAreFreshAndNeverMain() {
        val random = Random(7)
        val taken = HashSet<String>()
        repeat(200) {
            val id = Profiles.newId(taken, random)
            assertTrue(id.matches(Regex("[a-z0-9]{6}")))
            assertTrue(taken.add(id))
        }
    }

    @Test fun initials() {
        assertEquals("Y", Profiles.initial("yassine"))
        assertEquals("É", Profiles.initial("  élodie"))
        assertEquals("", Profiles.initial("   "))
        assertEquals("", Profiles.initial(""))
        assertEquals("7", Profiles.initial("7e"))
        assertEquals("Ö", Profiles.initial("ölüm"))
        assertEquals("张", Profiles.initial("张伟"))
        // A digraph letter takes its title case (Dž), not its all-capitals form (DŽ).
        assertEquals("ǅ", Profiles.initial("ǆemal"))
        // Emoji and punctuation are skipped: no half of a surrogate pair, no emoji in a white letter's place.
        assertEquals("J", Profiles.initial("😀 Joie"))
        assertEquals("L", Profiles.initial("« léa »"))
        assertEquals("", Profiles.initial("😀👍"))
        // A letter outside the BMP stays whole.
        assertEquals("𝐀", Profiles.initial("𝐀bc"))
    }

    @Test fun everyProfileHasALetter() {
        assertEquals("P", Profiles.letter(main))
        assertEquals("P", Profiles.letter(Profile(Profiles.MAIN, "", 0, false)))
        assertEquals("E", Profiles.letter(kids))
        assertEquals("P", Profiles.letter(Profile("e1", "😀", 0, false)))
        assertEquals("P", Profiles.letter(Profiles.add(listOf(main), "n1", "  ").last()))
    }

    @Test fun sixTvosColours() {
        assertEquals(6, Profiles.COLORS.size)
        assertEquals(Profiles.COLORS.size, Profiles.COLORS.toSet().size)
        assertTrue(Profiles.COLORS.all { (it ushr 24) == 0xFF })
        assertEquals("#2f7cf6", Profiles.colorHex(0))
        assertEquals(Profiles.colorHex(0), Profiles.colorHex(Profiles.COLORS.size))
    }

    @Test fun photoPathsAreOnlyForProfileIds() {
        val files = java.io.File("/data/files")
        val ext = java.io.File("/sdcard/ext")
        assertEquals(listOf(java.io.File("/data/files/avatars/abc123.jpg"), java.io.File("/sdcard/ext/avatars/abc123.jpg")),
            Profiles.photoPaths(files, ext, "abc123"))
        assertEquals(listOf(java.io.File("/data/files/avatars/main.jpg")), Profiles.photoPaths(files, null, Profiles.MAIN))
        assertTrue(Profiles.photoPaths(files, ext, "../prefs").isEmpty())
        assertTrue(Profiles.photoPaths(files, ext, "").isEmpty())
    }

    @Test fun pinsAreFourToSixAsciiDigits() {
        assertTrue(Profiles.validPin("1234"))
        assertTrue(Profiles.validPin("000000"))
        assertFalse(Profiles.validPin("123"))
        assertFalse(Profiles.validPin("1234567"))
        assertFalse(Profiles.validPin("12a4"))
        assertFalse(Profiles.validPin("12 34"))
        assertFalse(Profiles.validPin("١٢٣٤"))  // Arabic-Indic digits are digits, but not the TV's
        assertFalse(Profiles.validPin(""))
    }

    @Test fun pinHashIsSaltedSha256() {
        // SHA-256("abc"), the FIPS 180-2 test vector: the salt goes first.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Profiles.hashPin("ab", "c"))
        assertNotEquals(Profiles.hashPin("s1", "1234"), Profiles.hashPin("s2", "1234"))
        val h = Profiles.hashPin("salt", "1234")
        assertTrue(Profiles.pinMatches(h, "salt", "1234"))
        assertFalse(Profiles.pinMatches(h, "salt", "4321"))
        assertFalse(Profiles.pinMatches("", "salt", ""))
        val salt = Profiles.newSalt()
        assertTrue(salt.matches(Regex("[0-9a-f]{32}")))
        assertNotEquals(salt, Profiles.newSalt())
    }

    // ------------------------------------------------------------------ Prefs over one store per name

    /** Like [FakeContext], but each name has its own store, as on a TV. */
    private class Stores : ContextWrapper(null) {
        val stores = HashMap<String, MemorySharedPreferences>()
        fun of(name: String) = stores.getOrPut(name) { MemorySharedPreferences() }
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = of(name.orEmpty())
        override fun getApplicationContext() = this
        /** Profile photos ([Profiles.photoFile]) go to a folder of their own, gone with the test. */
        val files: java.io.File by lazy { kotlin.io.path.createTempDirectory("profiles").toFile().apply { deleteOnExit() } }
        override fun getFilesDir() = files
        override fun getExternalFilesDir(type: String?): java.io.File? = null
    }

    private val ctx = Stores()
    private fun prefs() = Prefs(ctx)

    @Test fun withoutProfilesTheGridStaysInTheMainStore() {
        val p = prefs()
        assertEquals(Profiles.MAIN, p.currentProfile)
        assertEquals(listOf(main), p.profileList())
        assertFalse(p.restricted)
        assertFalse(p.hasPin)
        assertFalse(p.checkPin("1234"))
        p.order = listOf("a", "b")
        p.hidden = setOf("c")
        p.background = "anime"
        // The main profile's grid is where it always was.
        assertEquals("a,b", ctx.of("tvhome")["order"])
        assertEquals(setOf("c"), ctx.of("tvhome")["hidden"])
        assertEquals("anime", ctx.of("tvhome")["background"])
        assertEquals(setOf("tvhome"), ctx.stores.keys)
    }

    @Test fun eachProfileHasItsOwnGridAndSharesEverythingElse() {
        val p = prefs()
        p.order = listOf("netflix", "youtube")
        p.hidden = setOf("tv.settings")
        p.background = "scenery"
        p.wallMinutes = 60
        p.labels = """{"netflix":"Films"}"""
        p.pinnedInputs = setOf("hdmi1")
        p.editFolders { Folders.create(it, "f1", "Jeux", listOf("steam")) }
        p.guard = true
        p.keep = setOf("cast")

        val kid = p.addProfile("Enfants", restricted = true)
        assertTrue(p.switchProfile(kid.id))
        assertEquals(kid.id, p.currentProfile)
        assertTrue(p.restricted)
        // A new profile starts with the default order, nothing hidden, no folders, and the main profile's background.
        assertTrue(p.order.isEmpty())
        assertTrue(p.hidden.isEmpty())
        assertTrue(p.folderList().isEmpty())
        assertEquals("", p.labels)
        assertTrue(p.pinnedInputs.isEmpty())
        assertEquals("scenery", p.background)
        assertEquals(60, p.wallMinutes)
        // The TV's settings are the same for everyone.
        assertTrue(p.guard)
        assertEquals(setOf("cast"), p.keep)

        p.order = listOf("cartoons")
        p.hidden = setOf("netflix")
        p.background = "fresh"
        assertEquals("cartoons", ctx.of("tvhome-${kid.id}")["order"])

        // Another Prefs (another screen) sees the same profile.
        val q = prefs()
        assertEquals(listOf("cartoons"), q.order)
        assertTrue(q.switchProfile(Profiles.MAIN))
        assertEquals(listOf("netflix", "youtube"), p.order)
        assertEquals(setOf("tv.settings"), p.hidden)
        assertEquals("scenery", p.background)
        assertEquals("Jeux", p.folderList().single().name)
        assertFalse(p.switchProfile("nope"))
        assertEquals(Profiles.MAIN, p.currentProfile)
    }

    @Test fun deletingAProfileClearsItsGridAndAGoneProfileFallsBackToMain() {
        val p = prefs()
        val a = p.addProfile("A")
        val b = p.addProfile("B")
        p.switchProfile(a.id)
        p.order = listOf("x")
        assertFalse("not the profile on screen", p.deleteProfile(a.id))
        assertFalse("never the main one", p.deleteProfile(Profiles.MAIN))
        // Its photo, as pushed over adb or uploaded, goes with it.
        val photo = java.io.File(java.io.File(ctx.files, Profiles.AVATARS_DIR).apply { mkdirs() }, "${a.id}.jpg").apply { writeBytes(byteArrayOf(1)) }
        p.switchProfile(b.id)
        assertTrue(p.deleteProfile(a.id))
        assertFalse(photo.exists())
        assertTrue(ctx.of("tvhome-${a.id}").keys.isEmpty())
        assertEquals(listOf(Profiles.MAIN, b.id), p.profileList().map { it.id })
        // A current profile that no longer exists (deleted over adb, say) shows the main grid.
        p.profiles = Profiles.encode(listOf(main))
        assertEquals(Profiles.MAIN, p.currentProfile)
        assertEquals(main, p.current())
    }

    @Test fun thePinIsKeptHashedAndCanBeRemoved() {
        val p = prefs()
        p.setPin("2580")
        assertTrue(p.hasPin)
        assertTrue(p.checkPin("2580"))
        assertFalse(p.checkPin("0000"))
        val stored = ctx.of("tvhome")["profile_pin"] as String
        // A 64-digit hex hash, never the PIN itself. (It may well contain "2580" by chance: the salt
        // is random, so a substring check here was flaky.)
        assertTrue(stored != "2580" && stored.matches(Regex("[0-9a-f]{64}")))
        // The salt is made once: a new PIN keeps it.
        val salt = ctx.of("tvhome")["profile_pin_salt"]
        p.setPin("1111")
        assertEquals(salt, ctx.of("tvhome")["profile_pin_salt"])
        assertTrue(prefs().checkPin("1111"))
        p.setPin("")
        assertFalse(p.hasPin)
        assertFalse(p.checkPin("1111"))
    }
}
