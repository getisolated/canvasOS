package com.getisolated.canvasos

import com.getisolated.canvasos.CcLayout.Item
import com.getisolated.canvasos.CcLayout.Layout
import com.getisolated.canvasos.CcLayout.Pos
import com.getisolated.canvasos.CcLayout.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CcLayoutTest {
    private val def = CcLayout.defaultLayout()

    @Test fun defaultLayoutIsValidAndHoldsEveryControlOnce() {
        assertTrue(CcLayout.isValid(def))
        assertEquals(CcLayout.IDS.toSet(), def.placed)
        assertEquals(2, def.pages.size)
        assertEquals(Item(CcLayout.POWER, 0, 0, 2, 2), def.find(CcLayout.POWER)!!.second)
        assertEquals(Item(CcLayout.PLAYER, 0, 3, 4, 2), def.find(CcLayout.PLAYER)!!.second)
        assertEquals(1 to Item(CcLayout.BLUETOOTH, 0, 3, 4, 1), def.find(CcLayout.BLUETOOTH))
    }

    @Test fun oneLinePlayerFromTheOldSettingLeavesARowFree() {
        val l = CcLayout.defaultLayout(playerOneLine = true)
        assertTrue(CcLayout.isValid(l))
        assertEquals(1, l.find(CcLayout.PLAYER)!!.second.h)
        assertEquals(0 to 4, CcLayout.firstFree(l.pages[0], 4, 1))
    }

    @Test fun serializeRoundTrips() {
        val l = CcLayout.move(def, CcLayout.MEMORY, 0, 0, 5)!!
        assertEquals(l, CcLayout.parse(CcLayout.serialize(l)))
    }

    @Test fun brokenOrInvalidJsonGivesTheDefault() {
        assertEquals(def, CcLayout.parse(null))
        assertEquals(def, CcLayout.parse("not json"))
        assertEquals(def, CcLayout.parse("""{"pages":[]}"""))
        assertEquals(CcLayout.defaultLayout(true), CcLayout.parse("{", playerOneLine = true))
        // Overlap, unknown id, size not among its own, out of the grid, duplicate, too many pages, nothing placed.
        val bad = listOf(
            """{"pages":[[{"id":"power","x":0,"y":0,"w":2,"h":2},{"id":"sleep","x":1,"y":1,"w":2,"h":1}]]}""",
            """{"pages":[[{"id":"toaster","x":0,"y":0,"w":1,"h":1}]]}""",
            """{"pages":[[{"id":"memory","x":0,"y":0,"w":3,"h":1}]]}""",
            """{"pages":[[{"id":"brightness","x":1,"y":0,"w":4,"h":1}]]}""",
            """{"pages":[[{"id":"photo","x":0,"y":5,"w":1,"h":1}],[{"id":"photo","x":0,"y":0,"w":1,"h":1}]]}""",
            """{"pages":[[{"id":"photo","x":0,"y":0,"w":1,"h":1}],[],[],[]]}""",
            """{"pages":[[],[]]}""",
        )
        for (json in bad) assertEquals(json, def, CcLayout.parse(json))
        val ok = """{"pages":[[{"id":"photo","x":3,"y":5,"w":1,"h":1}],[]]}"""
        assertEquals(Layout(listOf(listOf(Item(CcLayout.PHOTO, 3, 5, 1, 1)), emptyList())), CcLayout.parse(ok))
    }

    @Test fun fitsChecksBoundsAndOverlapsButIgnoresTheMovedControl() {
        val p = def.pages[0]
        assertTrue(CcLayout.fits(p, 0, 5, 4, 1))
        assertFalse(CcLayout.fits(p, 0, 4, 4, 1))
        assertFalse(CcLayout.fits(p, 1, 5, 4, 1))
        assertFalse(CcLayout.fits(p, 0, 0, 1, 1))
        assertTrue(CcLayout.fits(p, 0, 0, 2, 1, ignore = CcLayout.POWER))
    }

    @Test fun moveOnlyWhereItFits() {
        assertNull(CcLayout.move(def, CcLayout.MEMORY, 0, 1, 2))
        val l = CcLayout.move(def, CcLayout.MEMORY, 1, 2, 5)!!
        assertEquals(1 to Item(CcLayout.MEMORY, 2, 5, 1, 1), l.find(CcLayout.MEMORY))
        assertTrue(CcLayout.isValid(l))
        // Onto its own cells, shifted by one: it does not collide with itself.
        val power = CcLayout.move(CcLayout.remove(def, CcLayout.MEMORY)!!, CcLayout.POWER, 0, 0, 1)
        assertNull(power)
        assertNull(CcLayout.move(def, CcLayout.MEMORY, 5, 0, 0))
    }

    @Test fun resizeCyclesVariantsInPlaceOrRefuses() {
        assertEquals(Size(1, 1), CcLayout.nextSize(CcLayout.SLEEP, Size(2, 1)))
        assertEquals(Size(2, 1), CcLayout.nextSize(CcLayout.SLEEP, Size(1, 1)))
        assertNull(CcLayout.nextSize(CcLayout.BRIGHTNESS, Size(4, 1)))
        // Memory at (0,2) would cover Photo at (1,2).
        assertNull(CcLayout.resize(def, CcLayout.MEMORY, Size(2, 1)))
        val l = CcLayout.resize(def, CcLayout.POWER, Size(2, 1))!!
        assertEquals(Item(CcLayout.POWER, 0, 0, 2, 1), l.find(CcLayout.POWER)!!.second)
        assertNull(CcLayout.resize(def, CcLayout.POWER, Size(3, 3)))
        // The one-line player grows back into the free row under it.
        val one = CcLayout.defaultLayout(true)
        assertEquals(2, CcLayout.resize(one, CcLayout.PLAYER, Size(4, 2))!!.find(CcLayout.PLAYER)!!.second.h)
    }

    @Test fun removeKeepsAtLeastOneControl() {
        val l = CcLayout.remove(def, CcLayout.WEATHER)!!
        assertNull(l.find(CcLayout.WEATHER))
        assertEquals(listOf(CcLayout.WEATHER), l.unplaced)
        val single = Layout(listOf(listOf(Item(CcLayout.PHOTO, 0, 0, 1, 1))))
        assertNull(CcLayout.remove(single, CcLayout.PHOTO))
        assertNull(CcLayout.remove(l, CcLayout.WEATHER))
    }

    @Test fun addTakesFirstFreePlaceThenOtherPagesThenANewPage() {
        val noMem = CcLayout.remove(def, CcLayout.MEMORY)!!
        // Back into the hole it left on page 1.
        assertEquals(0 to Item(CcLayout.MEMORY, 0, 2, 1, 1), CcLayout.add(noMem, CcLayout.MEMORY, 0)!!.let { (l, p) -> p to l.find(CcLayout.MEMORY)!!.second })
        // From page 2 it stays on page 2, under Bluetooth.
        assertEquals(1 to Item(CcLayout.MEMORY, 0, 4, 1, 1), CcLayout.add(noMem, CcLayout.MEMORY, 1)!!.let { (l, p) -> p to l.find(CcLayout.MEMORY)!!.second })
        assertNull(CcLayout.add(def, CcLayout.MEMORY, 0))
        // Both pages full: a third page.
        val full = Layout(listOf(fill(), fill(), ))
        val (l, p) = CcLayout.add(full, CcLayout.WEATHER, 0)!!
        assertEquals(2, p)
        assertEquals(Item(CcLayout.WEATHER, 0, 0, 4, 1), l.pages[2].single())
        assertNull(CcLayout.add(Layout(listOf(fill(), fill(), fill())), CcLayout.WEATHER, 0))
    }

    /** A page filled with 1 × 1 cells of one control (only for fits/add, not a valid layout). */
    private fun fill() = (0 until CcLayout.ROWS).flatMap { y -> (0 until CcLayout.COLS).map { x -> Item("x$x$y", x, y, 1, 1) } }

    @Test fun pagesAddedUpToThreeAndRemovedOnlyWhenEmpty() {
        val three = CcLayout.addPage(def)!!
        assertEquals(3, three.pages.size)
        assertTrue(CcLayout.isValid(three))
        assertNull(CcLayout.addPage(three))
        assertNull(CcLayout.removePage(three, 0))
        assertEquals(def, CcLayout.removePage(three, 2))
        assertNull(CcLayout.removePage(Layout(listOf(emptyList())), 0))
    }

    @Test fun nudgeStaysInTheGridAndCrossesPages() {
        assertEquals(Pos(0, 1, 0), CcLayout.nudge(2, Pos(0, 0, 0), 1, 1, 1, 0))
        assertNull(CcLayout.nudge(2, Pos(0, 3, 0), 1, 1, 1, 0))
        assertNull(CcLayout.nudge(2, Pos(0, 0, 0), 4, 1, 1, 0))
        assertNull(CcLayout.nudge(2, Pos(0, 0, 0), 1, 1, 0, -1))
        assertEquals(Pos(1, 2, 0), CcLayout.nudge(2, Pos(0, 2, 5), 1, 1, 0, 1))
        assertEquals(Pos(0, 0, 4), CcLayout.nudge(2, Pos(1, 0, 0), 4, 2, 0, -1))
        assertNull(CcLayout.nudge(2, Pos(1, 0, 4), 4, 2, 0, 1))
    }

    @Test fun neighbourOnTheDefaultPage() {
        val p = def.pages[0]
        fun at(id: String) = def.find(id)!!.second
        fun go(id: String, dx: Int, dy: Int, prefer: String? = null) = CcLayout.neighbour(p, at(id), dx, dy, prefer)?.id
        assertEquals(CcLayout.NETWORK, go(CcLayout.POWER, 1, 0))
        assertEquals(CcLayout.SLEEP, go(CcLayout.NETWORK, 0, 1))
        assertEquals(CcLayout.POWER, go(CcLayout.SLEEP, -1, 0))
        assertEquals(CcLayout.FRAME, go(CcLayout.SLEEP, 0, 1))
        assertEquals(CcLayout.MEMORY, go(CcLayout.POWER, 0, 1))
        assertEquals(CcLayout.PLAYER, go(CcLayout.SETTINGS, 0, 1))
        // Back up from the player to where focus came from.
        assertEquals(CcLayout.SETTINGS, go(CcLayout.PLAYER, 0, -1, prefer = CcLayout.SETTINGS))
        assertEquals(CcLayout.PHOTO, go(CcLayout.PLAYER, 0, -1))
        assertNull(go(CcLayout.PLAYER, 0, 1))
        assertNull(go(CcLayout.NETWORK, 1, 0))
        assertNull(go(CcLayout.POWER, 0, -1))
    }

    @Test fun neighbourSkipsHoles() {
        val items = listOf(Item("a", 0, 0, 1, 1), Item("b", 3, 0, 1, 1), Item("c", 2, 4, 2, 1))
        assertEquals("b", CcLayout.neighbour(items, items[0], 1, 0)?.id)
        // Nothing straight below "a": the nearest control further down that way.
        assertEquals("c", CcLayout.neighbour(items, items[0], 0, 1)?.id)
        assertEquals("c", CcLayout.neighbour(items, items[1], 0, 1)?.id)
    }

    @Test fun entryOnANewPageKeepsTheColumn() {
        val p2 = def.pages[1]
        assertEquals(CcLayout.BRIGHTNESS, CcLayout.entry(p2, Item(CcLayout.PLAYER, 0, 3, 4, 2), down = true)?.id)
        assertEquals(CcLayout.SETTINGS, CcLayout.entry(def.pages[0].filter { it.id != CcLayout.PLAYER }, Item("x", 3, 0, 1, 1), down = false)?.id)
        val items = listOf(Item("left", 0, 0, 2, 1), Item("right", 2, 1, 2, 1))
        assertEquals("right", CcLayout.entry(items, Item("x", 3, 3, 1, 1), down = true)?.id)
        assertNull(CcLayout.entry(emptyList(), Item("x", 0, 0, 1, 1), down = true))
    }

    @Test fun nearestFirstAndUsedRows() {
        val p = def.pages[0]
        assertEquals(CcLayout.FRAME, CcLayout.nearest(p, def.find(CcLayout.SETTINGS)!!.second)?.id)
        assertEquals(CcLayout.POWER, CcLayout.first(p)?.id)
        assertEquals(5, CcLayout.usedRows(def))
        assertNotNull(CcLayout.first(def.pages[1]))
    }
}
