package com.getisolated.canvasos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FoldersTest {
    private val netflix = "com.netflix.ninja"
    private val youtube = "com.google.android.youtube.tv"
    private val kodi = "org.xbmc.kodi"

    @Test fun encodeDecodeRoundTrip() {
        val list = listOf(
            Folder("a1", "Streaming", listOf(netflix, youtube)),
            Folder("b2", "Médias « locaux »", listOf(kodi, "input:content://android.media.tv/passthrough/hdmi1")),
            Folder("c3", "Vide", emptyList()),
        )
        assertEquals(list, Folders.decode(Folders.encode(list)))
        // The format Prefs.folders documents.
        assertEquals(listOf(Folder("f1", "Streaming", listOf(netflix))),
            Folders.decode("""[{"id":"f1","name":"Streaming","apps":["com.netflix.ninja"]}]"""))
    }

    @Test fun brokenOrEmptyJsonIsNoFolders() {
        assertTrue(Folders.decode("").isEmpty())
        assertTrue(Folders.decode("not json").isEmpty())
        assertTrue(Folders.decode("{}").isEmpty())
        assertTrue(Folders.decode("""[1, "x", {"name":"sans id"}, {"id":"bad id!","apps":[]}]""").isEmpty())
    }

    @Test fun decodeRepairsWhatItReads() {
        val json = """[
            {"id":"a","name":"  ","apps":["$netflix","$netflix","folder:b",""]},
            {"id":"a","name":"Doublon","apps":["$kodi"]},
            {"id":"b","name":"Films","apps":["$netflix","$youtube"]}
        ]"""
        val list = Folders.decode(json)
        // Blank name → default; a repeated id keeps its first folder; no folders in folders; one folder per app.
        assertEquals(listOf(Folder("a", Folders.DEFAULT_NAME, listOf(netflix)), Folder("b", "Films", listOf(youtube))), list)
    }

    @Test fun createTakesTheAppsOutOfOtherFolders() {
        var list = Folders.create(emptyList(), "a", "Streaming", listOf(netflix, youtube))
        list = Folders.create(list, "b", "  Films  ", listOf(netflix, netflix, "folder:a"))
        assertEquals(listOf(Folder("a", "Streaming", listOf(youtube)), Folder("b", "Films", listOf(netflix))), list)
        // An id in use, or not an id, changes nothing.
        assertSame(list, Folders.create(list, "a", "Autre", listOf(kodi)))
        assertSame(list, Folders.create(list, "x y", "Autre", listOf(kodi)))
        assertEquals(Folders.DEFAULT_NAME, Folders.create(emptyList(), "c", null, emptyList()).single().name)
        assertEquals(Labels.MAX, Folders.create(emptyList(), "c", "x".repeat(200), emptyList()).single().name.length)
    }

    @Test fun anAppIsInOneFolderOnly() {
        var list = Folders.create(emptyList(), "a", "A", listOf(netflix))
        list = Folders.create(list, "b", "B", emptyList())
        list = Folders.add(list, "b", netflix)
        assertEquals(emptyList<String>(), Folders.byId(list, "a")!!.apps)
        assertEquals(listOf(netflix), Folders.byId(list, "b")!!.apps)
        assertEquals("b", Folders.folderOf(list, netflix)!!.id)
        // Adding it again keeps one copy.
        assertSame(list, Folders.add(list, "b", netflix))
        list = Folders.add(list, "b", youtube)
        assertEquals(setOf(netflix, youtube), Folders.members(list))
        assertEquals(listOf(netflix, youtube), Folders.byId(list, "b")!!.apps)
        // A folder never holds a folder.
        assertSame(list, Folders.add(list, "a", Folders.key("b")))
    }

    @Test fun removeAndDelete() {
        var list = Folders.create(emptyList(), "a", "A", listOf(netflix, youtube))
        list = Folders.remove(list, netflix)
        assertEquals(listOf(youtube), list.single().apps)
        assertNull(Folders.folderOf(list, netflix))
        // The last app out leaves the folder in place, empty.
        list = Folders.remove(list, youtube)
        assertEquals(Folder("a", "A", emptyList()), list.single())
        assertTrue(Folders.delete(list, "a").isEmpty())
    }

    @Test fun unknownIdsAreIgnored() {
        val list = Folders.create(emptyList(), "a", "A", listOf(netflix))
        assertSame(list, Folders.add(list, "zz", kodi))
        assertEquals(list, Folders.rename(list, "zz", "Autre"))
        assertEquals(list, Folders.delete(list, "zz"))
        assertEquals(list, Folders.remove(list, kodi))
        assertNull(Folders.byId(list, "zz"))
        assertEquals("Films", Folders.rename(list, "a", " Films ").single().name)
        assertEquals(Folders.DEFAULT_NAME, Folders.rename(list, "a", "").single().name)
    }

    @Test fun keysAndIds() {
        val key = Folders.key("k3x9qa")
        assertTrue(Folders.isFolder(key))
        assertEquals("k3x9qa", Folders.idOf(key))
        assertNull(Folders.idOf(netflix))
        val taken = mutableSetOf<String>()
        val random = Random(42)
        repeat(200) {
            val id = Folders.newId(taken, random)
            assertTrue(id.matches(Regex("[a-z0-9]{6}")))
            assertTrue(taken.add(id))
        }
    }

    @Test fun orderKeepsTheFolderInPlace() {
        val grid = listOf(netflix, youtube, kodi)
        val saved = listOf("hidden.app", youtube)
        // A new folder takes its first app's place; the app leaves the order.
        assertEquals(listOf(netflix, "folder:a", kodi, "hidden.app"),
            Folders.orderReplacing(grid, saved, youtube, listOf("folder:a")))
        // A deleted folder's apps come back where it was.
        assertEquals(listOf(netflix, "x", "y", kodi, "hidden.app", youtube),
            Folders.orderReplacing(listOf(netflix, "folder:a", kodi), saved, "folder:a", listOf("x", "y")))
        // An app out of a folder lands after everything on the grid, ahead of what is not shown.
        assertEquals(listOf(youtube, kodi, netflix, "hidden.app"), Folders.orderAtEnd(listOf(youtube, kodi), listOf(netflix, "hidden.app"), netflix))
    }
}
