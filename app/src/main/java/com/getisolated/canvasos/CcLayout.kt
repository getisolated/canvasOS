package com.getisolated.canvasos

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Where the Control Center's controls stand ([Prefs.ccLayout]): up to [MAX_PAGES] pages, each a
 * grid of [COLS] × [ROWS] cells, on which every control ([IDS]) stands at most once, at one of its
 * sizes ([VARIANTS]). Holes are allowed; nothing ever moves by itself. Pure (no views, no
 * preferences), so the rules the editor follows are tested on their own (CcLayoutTest).
 *
 * Stored as `{"pages":[[{"id":"power","x":0,"y":0,"w":2,"h":2},…],[…]]}`; anything that does not
 * read back as a valid layout gives the default one.
 */
object CcLayout {
    const val COLS = 4
    const val ROWS = 6
    const val MAX_PAGES = 3

    const val POWER = "power"
    const val SLEEP = "sleep"
    const val NETWORK = "network"
    const val MEMORY = "memory"
    const val PHOTO = "photo"
    const val FRAME = "frame"
    const val SETTINGS = "settings"
    const val BRIGHTNESS = "brightness"
    const val INPUT = "input"
    const val PLAYER = "player"
    const val WEATHER = "weather"
    const val BLUETOOTH = "bluetooth"

    data class Size(val w: Int, val h: Int)

    /** One control on a page, in cells. */
    data class Item(val id: String, val x: Int, val y: Int, val w: Int, val h: Int) {
        val right get() = x + w
        val bottom get() = y + h
        val size get() = Size(w, h)
        fun overlaps(o: Item) = x < o.right && o.x < right && y < o.bottom && o.y < bottom
    }

    /** A cell on a page. */
    data class Pos(val page: Int, val x: Int, val y: Int)

    data class Layout(val pages: List<List<Item>>) {
        /** The page [id] stands on and where, or null when it is not placed. */
        fun find(id: String): Pair<Int, Item>? {
            pages.forEachIndexed { p, items -> items.firstOrNull { it.id == id }?.let { return p to it } }
            return null
        }

        val placed: Set<String> get() = pages.flatten().map { it.id }.toSet()

        /** Controls that could be added: every known one not placed yet, in [IDS] order. */
        val unplaced: List<String> get() = placed.let { p -> IDS.filter { it !in p } }
    }

    /** Each control's sizes; the first is the one it gets when added. */
    val VARIANTS: Map<String, List<Size>> = linkedMapOf(
        POWER to listOf(Size(2, 2), Size(2, 1)),
        NETWORK to listOf(Size(2, 1), Size(1, 1)),
        SLEEP to listOf(Size(2, 1), Size(1, 1)),
        MEMORY to listOf(Size(1, 1), Size(2, 1)),
        PHOTO to listOf(Size(1, 1), Size(2, 1)),
        FRAME to listOf(Size(1, 1), Size(2, 1)),
        SETTINGS to listOf(Size(1, 1), Size(2, 1)),
        PLAYER to listOf(Size(4, 2), Size(4, 1)),
        BRIGHTNESS to listOf(Size(4, 1)),
        INPUT to listOf(Size(4, 1), Size(2, 1)),
        WEATHER to listOf(Size(4, 1), Size(4, 2)),
        BLUETOOTH to listOf(Size(4, 1), Size(2, 1)),
    )
    val IDS: List<String> = VARIANTS.keys.toList()

    fun variants(id: String): List<Size> = VARIANTS[id].orEmpty()

    /** The size after [current] in [id]'s list, round again to the first; null when it has only one. */
    fun nextSize(id: String, current: Size): Size? {
        val v = variants(id)
        if (v.size < 2) return null
        return v[(v.indexOf(current) + 1) % v.size]
    }

    /**
     * Page 1: Éteindre beside Réseau and Veille, the four round buttons, then the player (two lines,
     * or one when [playerOneLine], from the old "Lecteur" setting). Page 2: the four wide capsules.
     */
    fun defaultLayout(playerOneLine: Boolean = false): Layout = Layout(listOf(
        listOf(
            Item(POWER, 0, 0, 2, 2),
            Item(NETWORK, 2, 0, 2, 1),
            Item(SLEEP, 2, 1, 2, 1),
            Item(MEMORY, 0, 2, 1, 1),
            Item(PHOTO, 1, 2, 1, 1),
            Item(FRAME, 2, 2, 1, 1),
            Item(SETTINGS, 3, 2, 1, 1),
            Item(PLAYER, 0, 3, 4, if (playerOneLine) 1 else 2),
        ),
        listOf(
            Item(BRIGHTNESS, 0, 0, 4, 1),
            Item(INPUT, 0, 1, 4, 1),
            Item(WEATHER, 0, 2, 4, 1),
            Item(BLUETOOTH, 0, 3, 4, 1),
        ),
    ))

    /** [json] as a layout, or the default one when it is missing, unreadable or breaks a rule. */
    fun parse(json: String?, playerOneLine: Boolean = false): Layout {
        if (json.isNullOrBlank()) return defaultLayout(playerOneLine)
        val l = runCatching {
            val pages = JSONObject(json).getJSONArray("pages")
            Layout((0 until pages.length()).map { p ->
                val a = pages.getJSONArray(p)
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    Item(o.getString("id"), o.getInt("x"), o.getInt("y"), o.getInt("w"), o.getInt("h"))
                }
            })
        }.getOrNull()
        return if (l != null && isValid(l)) l else defaultLayout(playerOneLine)
    }

    fun serialize(l: Layout): String = JSONObject().put("pages", JSONArray().apply {
        for (items in l.pages) put(JSONArray().apply {
            for (it in items) put(JSONObject().put("id", it.id).put("x", it.x).put("y", it.y).put("w", it.w).put("h", it.h))
        })
    }).toString()

    /**
     * 1 to [MAX_PAGES] pages, at least one control, every control known, once, at one of its sizes,
     * inside the grid and over no other.
     */
    fun isValid(l: Layout): Boolean {
        if (l.pages.isEmpty() || l.pages.size > MAX_PAGES) return false
        val all = l.pages.flatten()
        if (all.isEmpty() || all.map { it.id }.toSet().size != all.size) return false
        for (items in l.pages) for ((i, it) in items.withIndex()) {
            if (it.size !in variants(it.id)) return false
            if (!inGrid(it.x, it.y, it.w, it.h)) return false
            if (items.subList(0, i).any { o -> o.overlaps(it) }) return false
        }
        return true
    }

    private fun inGrid(x: Int, y: Int, w: Int, h: Int) = x >= 0 && y >= 0 && x + w <= COLS && y + h <= ROWS

    /** A [w] × [h] control at ([x], [y]) on [page] stays in the grid and covers none of its controls but [ignore]. */
    fun fits(page: List<Item>, x: Int, y: Int, w: Int, h: Int, ignore: String? = null): Boolean {
        if (!inGrid(x, y, w, h)) return false
        val probe = Item("", x, y, w, h)
        return page.none { it.id != ignore && it.overlaps(probe) }
    }

    /** The first cell, row by row, where a [w] × [h] control fits on [page]; null when none does. */
    fun firstFree(page: List<Item>, w: Int, h: Int): Pair<Int, Int>? {
        for (y in 0..ROWS - h) for (x in 0..COLS - w) if (fits(page, x, y, w, h)) return x to y
        return null
    }

    private fun replace(l: Layout, id: String, to: Item?, toPage: Int): Layout =
        Layout(l.pages.mapIndexed { p, items ->
            val rest = items.filter { it.id != id }
            if (p == toPage && to != null) rest + to else rest
        })

    /** [id] moved to ([x], [y]) on [page], same size; null when it is not placed or does not fit there. */
    fun move(l: Layout, id: String, page: Int, x: Int, y: Int): Layout? {
        val (_, it) = l.find(id) ?: return null
        if (page !in l.pages.indices || !fits(l.pages[page], x, y, it.w, it.h, ignore = id)) return null
        return replace(l, id, it.copy(x = x, y = y), page)
    }

    /** [id] at [size], its top left corner where it is; null when that size is not one of its own or does not fit. */
    fun resize(l: Layout, id: String, size: Size): Layout? {
        val (p, it) = l.find(id) ?: return null
        if (size !in variants(id) || !fits(l.pages[p], it.x, it.y, size.w, size.h, ignore = id)) return null
        return replace(l, id, it.copy(w = size.w, h = size.h), p)
    }

    /** Without [id]; null when it is not placed or is the last control (the panel would have nothing to focus). */
    fun remove(l: Layout, id: String): Layout? {
        if (l.find(id) == null || l.pages.sumOf { it.size } <= 1) return null
        return Layout(l.pages.map { items -> items.filter { it.id != id } })
    }

    /**
     * [id] added at its first size: at the first free cell of [page], else of the pages after it,
     * then before it, else on a new page while there are fewer than [MAX_PAGES]. The new layout
     * and the page it went to; null when it is already placed, unknown or nowhere fits.
     */
    fun add(l: Layout, id: String, page: Int): Pair<Layout, Int>? {
        val size = variants(id).firstOrNull() ?: return null
        if (l.find(id) != null) return null
        val start = page.coerceIn(0, l.pages.size - 1)
        val order = (start until l.pages.size) + (0 until start)
        for (p in order) {
            val (x, y) = firstFree(l.pages[p], size.w, size.h) ?: continue
            return replace(l, id, Item(id, x, y, size.w, size.h), p) to p
        }
        if (l.pages.size >= MAX_PAGES) return null
        return Layout(l.pages + listOf(listOf(Item(id, 0, 0, size.w, size.h)))) to l.pages.size
    }

    /** One more, empty page at the end; null at [MAX_PAGES]. */
    fun addPage(l: Layout): Layout? = if (l.pages.size >= MAX_PAGES) null else Layout(l.pages + listOf(emptyList()))

    /** Without [page]; null unless it is empty and not the only one. */
    fun removePage(l: Layout, page: Int): Layout? {
        if (page !in l.pages.indices || l.pages[page].isNotEmpty() || l.pages.size <= 1) return null
        return Layout(l.pages.filterIndexed { p, _ -> p != page })
    }

    /**
     * A control being moved one cell in ([dx], [dy]) from [at]: it stays in the grid; past the
     * bottom row it goes on to the top of the next page, past the top to the bottom of the
     * previous one. Null when there is nowhere further (a bump).
     */
    fun nudge(pages: Int, at: Pos, w: Int, h: Int, dx: Int, dy: Int): Pos? {
        val x = at.x + dx
        val y = at.y + dy
        if (x < 0 || x + w > COLS) return null
        if (y < 0) return if (at.page > 0) Pos(at.page - 1, at.x, ROWS - h) else null
        if (y + h > ROWS) return if (at.page < pages - 1) Pos(at.page + 1, at.x, 0) else null
        return Pos(at.page, x, y)
    }

    /** Twice the distance between two spans' centres: whole numbers for half cells. */
    private fun centreGap(a: Int, aLen: Int, b: Int, bLen: Int) = abs((2 * a + aLen) - (2 * b + bLen))

    /**
     * Where focus goes from [from] in ([dx], [dy]) among [items] (one page): the nearest control
     * that way. Those facing it (overlapping its rows for Left and Right, its columns for Up and
     * Down) come first, nearest edge, then nearest centre; [prefer] (where focus just came from)
     * wins a tie, so going back and forth returns to the same control. Holes are skipped. Null
     * when there is nothing that way on the page.
     */
    fun neighbour(items: List<Item>, from: Item, dx: Int, dy: Int, prefer: String? = null): Item? {
        val ahead = items.filter {
            it.id != from.id && when {
                dx > 0 -> it.x >= from.right
                dx < 0 -> it.right <= from.x
                dy > 0 -> it.y >= from.bottom
                else -> it.bottom <= from.y
            }
        }
        if (ahead.isEmpty()) return null
        fun major(it: Item) = when {
            dx > 0 -> it.x - from.right
            dx < 0 -> from.x - it.right
            dy > 0 -> it.y - from.bottom
            else -> from.y - it.bottom
        }
        fun minor(it: Item) = if (dx != 0) centreGap(it.y, it.h, from.y, from.h) else centreGap(it.x, it.w, from.x, from.w)
        fun facing(it: Item) = if (dx != 0) it.y < from.bottom && from.y < it.bottom else it.x < from.right && from.x < it.right
        val beam = ahead.filter(::facing)
        val order = compareBy<Item>({ major(it) }, { minor(it) }, { it.y }, { it.x })
        if (beam.isNotEmpty()) {
            val nearest = beam.minOf { major(it) }
            beam.firstOrNull { it.id == prefer && major(it) == nearest }?.let { return it }
            return beam.minWithOrNull(order)
        }
        return ahead.minWithOrNull(compareBy<Item>({ major(it) * 2 + minor(it) }, { it.y }, { it.x }))
    }

    /**
     * Where focus lands on a new page reached by going down ([down]) or up from [from]: the nearest
     * control in the same columns, the top one going down and the bottom one going up; failing
     * that, the nearest by column.
     */
    fun entry(items: List<Item>, from: Item, down: Boolean): Item? {
        if (items.isEmpty()) return null
        val sameColumns = items.filter { it.x < from.right && from.x < it.right }
        val pool = sameColumns.ifEmpty { items }
        return pool.minWithOrNull(compareBy<Item>({ if (down) it.y else -it.bottom }, { centreGap(it.x, it.w, from.x, from.w) }, { it.x }))
    }

    /** The control nearest [at] (centre to centre), for focus once [at] has gone; null on an empty page. */
    fun nearest(items: List<Item>, at: Item): Item? = items.filter { it.id != at.id }.minWithOrNull(compareBy<Item>(
        { centreGap(it.x, it.w, at.x, at.w) + centreGap(it.y, it.h, at.y, at.h) }, { it.y }, { it.x }))

    /** The first control of a page in reading order. */
    fun first(items: List<Item>): Item? = items.minWithOrNull(compareBy<Item>({ it.y }, { it.x }))

    /** Rows down to the lowest control on any page (at least 1): the panel look's dark card ends there. */
    fun usedRows(l: Layout): Int = (l.pages.flatten().maxOfOrNull { it.bottom } ?: 1).coerceAtLeast(1)
}
