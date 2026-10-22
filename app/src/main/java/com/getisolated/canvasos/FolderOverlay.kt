package com.getisolated.canvasos

import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator

/**
 * An open folder, as on tvOS: the home screen dims behind the folder's name and its apps' tiles,
 * laid out like the home grid (up to [maxCols] a row, the same tiles and focus) and scrolling when
 * there are more rows than fit. It sits on the home screen's root, above the grid and below the
 * Control Center and sheets; the home screen keeps its grid blocked while it is open and closes it
 * on Back / Menu ([HomeActivity.dispatchKeyEvent]).
 */
class FolderOverlay(
    private val host: FrameLayout,
    repo: AppRepo,
    folder: AppEntry,
    private val tileW: Int,
    private val tileH: Int,
    private val gap: Int,
    private val rowPitch: Int,
    private val maxCols: Int,
    tvosFocus: Boolean,
    private val actions: Actions,
) {
    interface Actions {
        /** OK on an app of the folder. */
        fun onLaunch(app: AppEntry, cell: TileCell)
        /** Long press on an app of the folder. */
        fun onMenu(app: AppEntry, pos: Int)
    }

    /** The folder's grid key ([Folders.key]). */
    val key: String = folder.pkg
    private val ctx = host.context

    val root = FrameLayout(ctx).apply {
        setBackgroundColor(Color.argb(190, 0, 0, 0))
        isClickable = true
        alpha = 0f
        // Above the grid's lifted tiles, below the Control Center (100 dp) and sheets (200 dp); no shadow.
        translationZ = ctx.dp(50).toFloat()
        outlineProvider = null
    }
    private val title = ctx.text(30f, weight = 600).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setShadowLayer(dp(10).toFloat(), 0f, dp(1).toFloat(), Color.argb(150, 0, 0, 0))
    }
    private val content = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    // Room inside the grid's edges for a focused tile's zoom, shadow and name; the grid clips at its
    // own edges (its parent clips), so rows scrolled away do not draw over the title.
    private val padH = ctx.dp(40)
    private val padTop = ctx.dp(24)
    private val padBottom = ctx.dp(12)

    /** The last focused app, focused again when a sheet opened from here closes. */
    private var lastPos = 0

    private val listener: TileAdapter.Listener = object : TileAdapter.Listener {
        override fun onTileFocused(pos: Int, cell: TileCell) { lastPos = pos }

        override fun onTileClicked(pos: Int, cell: TileCell) {
            adapter.apps.getOrNull(pos)?.let { actions.onLaunch(it, cell) }
        }

        override fun onTileLongPressed(pos: Int, cell: TileCell) {
            adapter.apps.getOrNull(pos)?.let { actions.onMenu(it, pos) }
        }

        /** Focus moves tile by tile and bumps at the edges, so it never leaves the folder. */
        override fun onTileKey(pos: Int, cell: TileCell, keyCode: Int): Boolean {
            val (dx, dy) = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1 to 0
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1 to 0
                KeyEvent.KEYCODE_DPAD_UP -> 0 to -1
                KeyEvent.KEYCODE_DPAD_DOWN -> 0 to 1
                else -> return false
            }
            adapter.lastDx = dx
            adapter.lastDy = dy
            val n = adapter.itemCount
            val cols = lm.spanCount
            val col = pos % cols
            val atEdge = (dx < 0 && col == 0) || (dx > 0 && (col == cols - 1 || pos == n - 1)) ||
                (dy < 0 && pos < cols) || (dy > 0 && pos / cols == (n - 1) / cols)
            if (atEdge) Motion.bump(cell.card, dx, dy)
            // Down into a partial last row drops to its last tile, as on the home grid.
            else focusAt(minOf(pos + dx + dy * cols, n - 1))
            return true
        }
    }

    private val adapter: TileAdapter = TileAdapter(repo, tileW, tileH, listener).apply {
        setHasStableIds(true)
        dockCount = 0
        this.tvosFocus = tvosFocus
    }

    private val lm: GridLayoutManager = object : GridLayoutManager(ctx, 1) {
        override fun requestChildRectangleOnScreen(
            parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean,
        ): Boolean {
            // Whole rows with the names under them: scroll just enough to show the focused row.
            val top = getPosition(child) / spanCount * rowPitch
            val view = parent.height - parent.paddingTop - parent.paddingBottom
            val now = offset() ?: return false
            val wanted = when {
                top < now -> top
                top + rowPitch > now + view -> top + rowPitch - view
                else -> now
            }
            if (wanted != now) parent.smoothScrollBy(0, wanted - now, Motion.ease, 300)
            return true
        }
    }

    private val grid: RecyclerView = RecyclerView(ctx).apply {
        clipChildren = false
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        setPadding(padH, padTop, padH, padBottom)
        layoutManager = lm
        adapter = this@FolderOverlay.adapter
        // Tiles update in place; a cross-fade would swap in a second view and drop focus.
        (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(out: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val cols = lm.spanCount
                val col = parent.getChildAdapterPosition(view).coerceAtLeast(0) % cols
                out.left = col * gap / cols
                out.right = gap - (col + 1) * gap / cols
                out.bottom = rowPitch - tileH
            }
        })
    }

    init {
        content.addView(title, LinearLayout.LayoutParams(tileW * maxCols, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(grid)
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        title.text = folder.label
        val apps = folder.folder.orEmpty()
        layoutFor(apps.size)
        adapter.submit(apps)
    }

    val isOpen get() = root.parent != null

    fun open() {
        host.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.animate().alpha(1f).setDuration(200).start()
        content.scaleX = 0.94f
        content.scaleY = 0.94f
        content.animate().scaleX(1f).scaleY(1f).setDuration(280).setInterpolator(Motion.ease).start()
        // Posted after addView's layout pass, so the first tile exists.
        grid.post { focusAt(0) }
    }

    fun close(animate: Boolean) {
        if (!isOpen) return
        // Focus goes back to the home grid at once; nothing in here may take it while it fades.
        root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        root.animate().cancel()
        content.animate().cancel()
        if (!animate) { host.removeView(root); return }
        content.animate().scaleX(0.96f).scaleY(0.96f).setDuration(160).start()
        root.animate().alpha(0f).setDuration(160).withEndAction { host.removeView(root) }.start()
    }

    /** Shows the folder as the grid now has it: its name, and its apps updated in place. */
    fun update(folder: AppEntry, changed: String?, focus: Boolean) {
        title.text = folder.label
        val apps = folder.folder.orEmpty()
        if (apps.size != adapter.itemCount) layoutFor(apps.size)
        adapter.submit(apps, changed)
        // An app taken out had the focus: the one now in its place gets it.
        if (focus) grid.post { if (isOpen && !grid.hasFocus() && root.descendantFocusability != ViewGroup.FOCUS_BLOCK_DESCENDANTS) refocus() }
    }

    /** Keeps focus out of the folder while a sheet opened from it is up; [refocus] lets it back in. */
    fun block() { root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS }

    fun refocus() {
        if (!isOpen) return
        root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        focusAt(lastPos)
    }

    /**
     * Up to [maxCols] columns, as many as there are apps, centred; the grid is as tall as its rows,
     * or as the screen allows, then it scrolls (a partly shown last row says there is more).
     */
    private fun layoutFor(n: Int) {
        val cols = n.coerceIn(1, maxCols)
        lm.spanCount = cols
        val rows = (maxOf(n, 1) + cols - 1) / cols
        val sh = ctx.resources.displayMetrics.heightPixels
        val most = sh - ctx.dp(56) * 2 - ctx.dp(40) - ctx.dp(28) - padTop - padBottom
        grid.layoutParams = LinearLayout.LayoutParams(cols * tileW + (cols - 1) * gap + padH * 2,
            minOf(rows * rowPitch, most) + padTop + padBottom).apply { topMargin = ctx.dp(28) - padTop }
    }

    /** How far the grid is scrolled, from its first laid-out tile (RecyclerView's own estimate drifts for grids). */
    private fun offset(): Int? {
        val child = grid.getChildAt(0) ?: return null
        val p = grid.getChildAdapterPosition(child).takeIf { it >= 0 } ?: return null
        return p / lm.spanCount * rowPitch - (lm.getDecoratedTop(child) - grid.paddingTop)
    }

    private fun focusAt(pos: Int) {
        val n = adapter.itemCount
        if (n == 0 || !isOpen) return
        val p = pos.coerceIn(0, n - 1)
        val vh = grid.findViewHolderForAdapterPosition(p)
        if (vh != null) vh.itemView.requestFocus() else {
            grid.scrollToPosition(p)
            grid.post { grid.findViewHolderForAdapterPosition(p)?.itemView?.requestFocus() }
        }
    }
}
