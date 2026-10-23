package com.getisolated.canvasos

import android.app.Activity
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.getisolated.canvasos.tv.Input
import com.getisolated.canvasos.tv.TvControls
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * tvOS style home: a low glass dock and a compact status row in the top-right.
 */
class HomeActivity : Activity(), TileAdapter.Listener {
    companion object {
        const val SHELF_SECONDS = 8
        private const val EXTRA_GUARD = "com.getisolated.canvasos.GUARD"
        private const val EXTRA_WAKE = "com.getisolated.canvasos.WAKE"
        private const val REQUEST_BLUETOOTH = 41

        /**
         * How the home guard and the boot receiver bring the home screen back: the same task, with
         * no HOME category. Where canvasOS is not the system home, that category made Android open a
         * second, home-type task with a fresh screen every time (Aerial, Xiaomi MiTV-MFTR0).
         */
        fun bringBack(ctx: Context): Intent = Intent(ctx, HomeActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            .putExtra(EXTRA_GUARD, true)
    }

    private lateinit var root: FrameLayout
    private lateinit var scene: FrameLayout
    private lateinit var backdrop: FrameLayout
    private lateinit var bg: ImageView
    private lateinit var header: FrameLayout
    private lateinit var clockText: TextView
    /** The large clock top left, with the date above it ([Prefs.CLOCK_LARGE]). */
    private lateinit var bigClock: LinearLayout
    private lateinit var bigDate: TextView
    private lateinit var bigTime: TextView
    /** The weather ([Weather]): "14°" and its icon left of the corner clock, a line under the large clock's date. */
    private lateinit var wxCorner: LinearLayout
    private lateinit var wxIcon: ImageView
    private lateinit var wxTemp: TextView
    private lateinit var wxLine: TextView
    private var intro: Intro? = null
    private lateinit var avatar: IconButton
    private lateinit var netButton: IconButton
    private lateinit var ccButton: IconButton
    private lateinit var grid: RecyclerView
    private lateinit var adapter: TileAdapter
    private lateinit var repo: AppRepo
    private lateinit var prefs: Prefs
    private lateinit var control: ControlCenter
    private val tv by lazy { TvControls(this) }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val sheets = ArrayList<Sheet>()

    private val cols = 5
    private var tileW = 0
    private var tileH = 0
    /** Space between two tiles of a row. */
    private var gridGap = 0
    private var rowPitch = 0
    private var firstRowY = 0
    /** Where the focused row sits once the grid is expanded below the dock. */
    private var expandedRowY = 0

    private var focusedPos = 0
    private var firstFocus = true
    /** Grid scroll position; RecyclerView's own estimate is unreliable for grids. */
    private var scrollY = 0
    private var shownBackground: String? = null
    @Volatile private var refreshing = false
    /** The wallpaper the background shows, or is fading to. */
    private var bgShown: Bitmap? = null
    /** The background's fade, while one may be under way; [drawFinalScene] draws it at its end. */
    private var bgFade: FadeDrawable? = null
    private val bgTween = Tween()
    private var sw = 0
    private var sh = 0

    /** Liquid Glass slab behind the first row, the "dock". */
    private lateinit var dock: ImageView
    private val dockRect = Rect()
    private var dockRadius = 0f
    /** The glass baked over the home picture, kept for when the shelf goes away. */
    @Volatile private var homeDock: Bitmap? = null
    /** The glass the dock shows, or is fading to. */
    private var dockShown: Bitmap? = null
    private var dockFade: FadeDrawable? = null
    private val dockTween = Tween()

    /** Top Shelf: content of the focused dock app filling the screen above the dock. */
    private lateinit var shelf: FrameLayout
    private lateinit var shelfPics: FrameLayout
    private var shelfFront: ImageView? = null
    /** The dock's glass over [shelfFront]'s picture, baked before the picture shows. */
    private var shelfGlass: Bitmap? = null
    private val shelfTween = Tween()
    private lateinit var shelfSource: TextView
    private lateinit var shelfTitle: TextView
    private lateinit var shelfMeta: TextView
    private lateinit var shelfInfo: LinearLayout
    private lateinit var shelfDots: LinearLayout
    private val net = Executors.newFixedThreadPool(2)
    private var shelfPkg: String? = null
    private var pendingShelf: String? = null
    /** The app whose content is being fetched for the shelf; an answer for any other app is dropped. */
    private var shelfLoading: String? = null
    private var shelfItems: List<Shelf.Item> = emptyList()
    private var shelfIndex = 0
    private var shelfShown = false
    private val shelfShow = Runnable { pendingShelf?.let { showShelf(it) } }
    /** How the shelf comes and goes (a ViewPropertyAnimator's default curve, as it had). */
    private val shelfCurve = android.view.animation.AccelerateDecelerateInterpolator()
    private val shelfAdvance = object : Runnable {
        override fun run() {
            if (!shelfShown || shelfItems.size < 2) return
            val pkg = shelfPkg ?: return
            val items = shelfItems
            val from = shelfIndex
            net.execute {
                // The next item whose picture loads: a broken or tiny one is skipped, not shown forever.
                var next = -1
                var b: Bitmap? = null
                for (k in 1..minOf(3, items.size - 1)) {
                    val j = (from + k) % items.size
                    b = Shelf.image(this@HomeActivity, items[j].image)
                    if (b != null) { next = j; break }
                }
                val pic = b
                val at = next
                // Baked before the picture moves, so the dock's glass changes with it, not after it.
                val glass = pic?.let { bakeDock(it) }
                main.post {
                    if (shelfPkg == pkg && shelfShown && !control.isOpen && shelfItems === items) {
                        if (pic != null && glass != null) showShelfItem(at, pic, glass)
                        main.postDelayed(this, SHELF_SECONDS * 1000L)
                    }
                }
            }
        }
    }

    /** Position being dragged in "edit home screen" mode, or -1. */
    private var moving = -1

    /** The folder open over the grid ([FolderOverlay]), or null. */
    private var folderView: FolderOverlay? = null

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pkg = i.data?.schemeSpecificPart
            repo.invalidate(pkg)
            reload(pkg)
        }
    }

    /** A name, tile picture or the hidden list changed on the phone page ([AppRepo.notifyChanged]). */
    private val tileReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // A profile's photo changed on the phone page: only the avatar is drawn again.
            if (i.hasExtra(Profiles.EXTRA_AVATAR)) { showAvatar(); return }
            val pkg = i.getStringExtra(AppRepo.EXTRA_PKG)
            // The profile was switched over adb (ConfigReceiver): that profile's home, as from the avatar.
            if (pkg == null && prefs.currentProfile != shownProfile) { showProfile(); return }
            repo.invalidate(pkg)
            reload(pkg)
        }
    }

    /**
     * Tile pictures pushed over adb show up on the next return home. Compared with what the grid
     * shows, not with the last scan: the phone page or settings may have scanned since.
     */
    private fun checkCustomTiles() {
        io.execute {
            AppRepo.scanCustomTiles(this)
            main.post { if (adapter.apps.any { e -> (e.folder ?: listOf(e)).any { it.tileStamp != AppRepo.tileStamp(it.pkg) } }) reload() }
        }
    }

    /**
     * Xiaomi shows its own screensaver (with ads) after system/screen_saver_timeout instead of
     * Android's, so the photo frame starts 15 s ahead of it when the home screen sits idle, and
     * keeps the screen on so Xiaomi's does not follow. Elsewhere Android's screensaver applies.
     */
    private val idleFrame = Runnable {
        if (hasWindowFocus() && sheets.isEmpty() && !control.isOpen) openFrame()
    }

    private fun firmwareSaverDelay(): Long? {
        val t = runCatching { Settings.System.getLong(contentResolver, "screen_saver_timeout") }.getOrNull() ?: return null
        return if (t > 0) maxOf(30_000L, t - 15_000L) else null
    }

    private fun restartIdle() {
        main.removeCallbacks(idleFrame)
        firmwareSaverDelay()?.let { main.postDelayed(idleFrame, it) }
    }

    fun openFrame() {
        startActivity(Intent(this, FrameActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateClock()
            applyBackground()
            updateWeather()
            main.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Tween.readScale(this)
        prefs = Prefs(this)
        repo = AppRepo(this)
        shownProfile = prefs.currentProfile
        buildViews()
        control = ControlCenter(this, root)
        // Covers the screen before its first frame, so the home screen is never seen ahead of it.
        if (Intro.dueAfterBoot(this)) playIntro()
        reload()
        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
        // Only this app sends it (setPackage); not exported where Android lets a receiver say so.
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(tileReceiver, IntentFilter(AppRepo.ACTION_TILE_CHANGED), RECEIVER_NOT_EXPORTED)
        else registerReceiver(tileReceiver, IntentFilter(AppRepo.ACTION_TILE_CHANGED))
        KeeperService.start(this)
        if (!prefs.adbAuthorized) main.postDelayed({ askAdbAuthorization() }, if (intro != null) 8000L else 1500L)
    }

    // ------------------------------------------------------------------- intro

    /*
     * Waking from standby works like a boot: the home screen comes to the front, whatever was open,
     * and the intro plays. The broadcast can arrive before or after onResume, so both places check.
     */
    private var resumed = false
    private var wakeIntro = false
    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (prefs.intro != Prefs.INTRO_WAKE) return
            if (resumed) { playIntro(wake = true); return }
            wakeIntro = true
            // The home app may start activities from the background.
            startActivity(Intent(c, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_WAKE, true))
        }
    }

    /** Plays the boot animation over the home screen; [preview] is the one started from settings. */
    fun playIntro(preview: Boolean = false, wake: Boolean = false) {
        if (intro != null) return
        if (!preview) prefs.introBoot = Intro.bootStamp(this)
        sheets.toList().forEach { it.dismiss() }
        control.close()
        closeFolder(animate = false)
        intro = Intro(this, root, scene, prefs.introSound) {
            intro = null
            if (sheets.isEmpty() && !control.isOpen) focusAt(focusedPos)
        }.also { it.start(if (wake) prefs.wakeDelay.toLong() else 0) }
    }

    override fun onDestroy() {
        unregisterReceiver(pkgReceiver)
        main.removeCallbacksAndMessages(null)
        control.destroy()
        intro?.finish()
        runCatching { unregisterReceiver(screenOn) }
        runCatching { unregisterReceiver(tileReceiver) }
        io.shutdown()
        net.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ layout

    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val match = { FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        scene = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        backdrop = FrameLayout(this)
        root.addView(scene, match())
        scene.addView(backdrop, match())
        bg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        backdrop.addView(bg, match())

        val dm = resources.displayMetrics
        sw = dm.widthPixels
        sh = dm.heightPixels
        // Proportions measured from a 1920×1080 tvOS 26 screenshot.
        val side = (sw * 0.0583f).toInt()          // 112 px
        val gap = (sw * 0.0292f).toInt()           // 56 px
        gridGap = gap
        tileW = (sw - side * 2 - gap * (cols - 1)) / cols
        tileH = tileW * 9 / 16
        rowPitch = (sh * 0.2463f).toInt()          // 266 px
        // The dock sits at the bottom; only the top edge of the next row peeks out below it.
        firstRowY = (sh * 0.744f).toInt()          // 804 px; next row just peeks above the bottom edge
        expandedRowY = (sh * 0.36f).toInt()

        // The dock hugs the first row: a focused tile (scaled 1.12) nearly touches its edges.
        val pad = dp(15)
        dockRect.set(side - pad, firstRowY - pad, sw - side + pad, firstRowY + tileH + pad)
        dockRadius = dp(11) + pad.toFloat()
        buildShelf(side)
        dock = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
        scene.addView(dock, FrameLayout.LayoutParams(dockRect.width(), dockRect.height()).apply {
            leftMargin = dockRect.left; topMargin = dockRect.top
        })

        // A persistent status row, also visible over Top Shelf and the expanded app grid.
        header = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        // The tvOS 26 clock this launcher started with: the date over a large thin clock, top left.
        bigDate = text(20f, bold = true).apply { setShadowLayer(dp(8).toFloat(), 0f, 0f, Color.argb(60, 0, 0, 0)) }
        bigTime = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Fonts.inter(this@HomeActivity, 250)
            textSize = 118f
            includeFontPadding = false
            letterSpacing = -0.02f
            setShadowLayer(dp(12).toFloat(), 0f, 0f, Color.argb(50, 0, 0, 0))
        }
        bigClock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bigDate, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
            addView(bigTime, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        }
        header.addView(bigClock, FrameLayout.LayoutParams(-2, -2).apply {
            leftMargin = side - dp(4); topMargin = (sh * 0.197f).toInt()
        })
        clockText = text(14f, weight = 600).apply {
            setShadowLayer(dp(5).toFloat(), 0f, dp(1).toFloat(), Color.argb(140, 0, 0, 0))
        }
        val icons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            // The padding is the room a focused (enlarged) disc grows into; don't clip it away.
            clipToPadding = false
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        netButton = IconButton(this, R.drawable.ic_wifi) {
            runCatching { startActivity(tv.wifiIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        ccButton = IconButton(this, R.drawable.ic_toggles) { control.open() }
        netButton.contentDescription = "Réglages réseau"
        ccButton.contentDescription = "Centre de contrôle"
        icons.addView(clockText, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        buildWeather(icons)
        icons.addView(netButton, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(6) })
        icons.addView(ccButton, LinearLayout.LayoutParams(dp(28), dp(28)))
        avatar = IconButton(this, 0, portrait = true) { profileSheet() }.apply {
            id = View.generateViewId()
            contentDescription = "Profils"
        }
        showAvatar()
        icons.addView(avatar, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(6) })
        header.addView(icons, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply {
            rightMargin = dp(32); topMargin = dp(26)
        })
        listOf(netButton, ccButton, avatar).forEach { b ->
            b.setOnKeyListener { _, code, e ->
                if (e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_DOWN) { focusAt(focusedPos); true } else false
            }
        }
        netButton.nextFocusRightId = View.generateViewId().also { ccButton.id = it }
        ccButton.nextFocusLeftId = View.generateViewId().also { netButton.id = it }
        ccButton.nextFocusRightId = avatar.id
        avatar.nextFocusLeftId = ccButton.id
        scene.addView(header, match())

        adapter = TileAdapter(repo, tileW, tileH, this).apply { setHasStableIds(true); dockCount = cols }
        grid = RecyclerView(this).apply {
            clipChildren = false
            clipToPadding = false
            itemAnimator?.changeDuration = 0
            // Room below the last row so it can scroll up to the expanded position.
            setPadding(side, firstRowY, side, sh - expandedRowY - tileH)
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            overScrollMode = View.OVER_SCROLL_NEVER
            setItemViewCacheSize(30)
        }
        grid.layoutManager = object : GridLayoutManager(this, cols) {
            override fun requestChildRectangleOnScreen(
                parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean,
            ): Boolean {
                // The dock row rests at the bottom; any other row is brought up to the expanded position.
                val row = getPosition(child) / cols
                val wantedOffset = if (row == 0) 0 else firstRowY + row * rowPitch - expandedRowY
                val dy = wantedOffset - scrollY
                if (dy != 0) parent.smoothScrollBy(0, dy, Motion.ease, 360)
                return true
            }
        }
        grid.adapter = adapter
        // Tiles update in place; the default cross-fade would swap in a second view and drop focus.
        (grid.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        grid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(out: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val col = parent.getChildAdapterPosition(view) % cols
                out.left = col * gap / cols
                out.right = gap - (col + 1) * gap / cols
                out.bottom = rowPitch - tileH
            }
        })
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                // Measured from the rows themselves: layouts and jumps (reloads, scrollToPosition)
                // move the grid with dy = 0, so a running sum of dy drifts away from the truth.
                scrollY = gridOffset() ?: (scrollY + dy)
                val off = scrollY.toFloat()
                // The glass was baked for its resting place, so it fades out quickly once it moves.
                dock.translationY = -off
                dock.alpha = (1f - off / (rowPitch * 0.7f)).coerceIn(0f, 1f)
                // The large clock gives way to the rows coming up under it.
                if (!shelfShown) bigClock.alpha = dock.alpha
            }
        })
        scene.addView(grid, match())
        header.bringToFront()
        setContentView(root)
    }

    /**
     * Shows the gradient theme, or the photo for the current rotation slot (cross-fading when it
     * changes). Called every minute; the next photo is downloaded only when its turn comes.
     */
    private fun applyBackground() {
        val theme = prefs.background
        if (!Wallpapers.isPhoto(theme)) return showBackground(theme, null)
        if (refreshing) return
        refreshing = true
        io.execute {
            val walls = Wallpapers(this, Wallpapers.sourceFor(theme))
            runCatching { walls.refresh(); walls.advance(prefs.wallMinutes) }
            val photo = walls.current()
            refreshing = false
            main.post { if (!isDestroyed && prefs.background == theme) showBackground(theme, photo) }
        }
    }

    /**
     * The Control Center's "Photo suivante": moves the photo source on now (downloading the next
     * picture if the day's list still has one) and shows it; a gradient theme has nothing to move.
     */
    fun nextWallpaper(report: (String) -> Unit) {
        val theme = prefs.background
        if (!Wallpapers.isPhoto(theme)) { report("Fond d'écran : dégradé fixe, choisissez une source de photos"); return }
        if (refreshing) return
        refreshing = true
        report("Photo suivante…")
        io.execute {
            val walls = Wallpapers(this, Wallpapers.sourceFor(theme))
            val changed = runCatching { walls.refresh(); walls.advance(prefs.wallMinutes, force = true) }.getOrDefault(false)
            val photo = walls.current()
            refreshing = false
            main.post {
                if (isDestroyed) return@post
                if (prefs.background == theme) showBackground(theme, photo)
                report(if (changed) "" else if (photo == null) "Aucune photo pour l'instant" else "Une seule photo disponible pour l'instant")
            }
        }
    }

    private fun showBackground(theme: String, photo: java.io.File?) {
        val key = if (Wallpapers.isPhoto(theme)) "$theme:${photo?.name}" else theme
        if (key == shownBackground) return
        shownBackground = key
        io.execute {
            val bmp = photo?.let { Wallpapers(this, Wallpapers.sourceFor(theme)).forHome(it) } ?: Backgrounds.render(if (Wallpapers.isPhoto(theme)) "green" else theme)
            // Baked before anything moves: the dock's glass changes with the picture, not after it.
            val glass = bakeDock(bmp)
            homeDock = glass
            main.post {
                if (isDestroyed) return@post
                // The first picture rises from black; the next ones cross-fade over 1.2 s.
                val first = bgShown == null
                val ms = if (first) 300L else 1200L
                val curve = if (first) android.view.animation.AccelerateDecelerateInterpolator() else android.view.animation.LinearInterpolator()
                val pic = FadeDrawable(bgShown, bmp).also { bg.setImageDrawable(it) }
                bgFade = pic
                bgShown = bmp
                // Under the shelf the dock refracts the shelf's picture: homeDock waits for the shelf to go.
                val glassFade = if (shelfShown) null else stageDock(glass)
                if (!shelfShown) control.backgroundChanging(ms) { drawFinalScene(it) }
                runFade(bgTween, pic, ms, curve)
                glassFade?.let { runFade(dockTween, it, ms, curve) }
            }
        }
    }

    /** The dock's glass over [picture] (shown centre-cropped, full screen); blocking, off the main thread. */
    private fun bakeDock(picture: Bitmap): Bitmap =
        LiquidGlass.bake(picture, sw, sh, dockRect, dockRadius).also { it.prepareToDraw() }

    /**
     * Puts [glass] in the dock over the glass it shows, not drawn yet: nothing changes until
     * [runFade] starts it, in the same frame and on the same curve as the picture it refracts.
     */
    private fun stageDock(glass: Bitmap): FadeDrawable =
        FadeDrawable(dockShown, glass).also { dock.setImageDrawable(it); dockFade = it; dockShown = glass }

    private fun runFade(t: Tween, f: FadeDrawable, ms: Long, curve: android.animation.TimeInterpolator) =
        t.start(ms, curve, end = { f.from = null }) { f.progress = it }

    /**
     * Draws the home scene into [canvas] as [prepare] leaves it, then undoes it with [restore]:
     * one call on the UI thread, so no frame on screen ever shows the change.
     */
    private fun drawScene(canvas: Canvas, prepare: () -> Unit, restore: () -> Unit) {
        prepare()
        try { scene.draw(canvas) } finally { restore() }
    }

    /**
     * The home scene as the transitions under way will leave it: the background and the dock at
     * the end of their fades, the shelf shown with its front picture settled, or gone. The Control
     * Center's glass takes it as its target ([ControlCenter.backgroundChanging]), so it changes
     * with the background instead of after it. Read when called: a later transition's end wins.
     */
    private fun drawFinalScene(canvas: Canvas) {
        val undo = ArrayList<() -> Unit>()
        fun alpha(v: View, a: Float) { val o = v.alpha; if (o != a) { v.alpha = a; undo += { v.alpha = o } } }
        drawScene(canvas, prepare = {
            // Settled without an invalidate: the views on screen are not redrawn for this.
            listOfNotNull(bgFade, dockFade).forEach { f -> if (!f.settled) { f.settled = true; undo += { f.settled = false } } }
            if (shelfShown) {
                alpha(shelf, 1f)
                alpha(bigClock, 0f)
                val front = shelfFront
                for (k in 0 until shelfPics.childCount) shelfPics.getChildAt(k).let { alpha(it, if (it === front) 1f else 0f) }
                front?.let { v ->
                    val sx = v.scaleX; val sy = v.scaleY
                    if (sx != 1f || sy != 1f) { v.scaleX = 1f; v.scaleY = 1f; undo += { v.scaleX = sx; v.scaleY = sy } }
                }
                alpha(shelfInfo, 1f)
                val ty = shelfInfo.translationY
                if (ty != 0f) { shelfInfo.translationY = 0f; undo += { shelfInfo.translationY = ty } }
            } else {
                alpha(shelf, 0f)
                alpha(bigClock, dock.alpha)
            }
        }, restore = { undo.asReversed().forEach { it() } })
    }

    // ------------------------------------------------------------------- shelf

    private fun buildShelf(side: Int) {
        shelf = FrameLayout(this).apply { alpha = 0f; visibility = View.INVISIBLE }
        shelfPics = FrameLayout(this)
        shelf.addView(shelfPics, FrameLayout.LayoutParams(-1, -1))
        val shadow = { t: TextView -> t.setShadowLayer(dp(10).toFloat(), 0f, dp(1).toFloat(), Color.argb(150, 0, 0, 0)) }
        shelfSource = text(16f, Color.argb(200, 255, 255, 255), weight = 600).also(shadow)
        shelfTitle = text(38f, weight = 700).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.08f)
        }.also(shadow)
        shelfMeta = text(17f, Color.argb(210, 255, 255, 255), weight = 500).apply {
            maxLines = 1
            // Apps' own descriptions (Watch Next) run long.
            ellipsize = android.text.TextUtils.TruncateAt.END
        }.also(shadow)
        shelfInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(shelfSource)
            addView(shelfTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(shelfMeta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        }
        val above = sh - dockRect.top + dp(30)
        shelf.addView(shelfInfo, FrameLayout.LayoutParams((sw * 0.56f).toInt(), -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = side; bottomMargin = above
        })
        shelfDots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        shelf.addView(shelfDots, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = side; bottomMargin = above + dp(4)
        })
        backdrop.addView(shelf, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showShelf(pkg: String) {
        pendingShelf = null
        shelfLoading = pkg
        net.execute {
            // Watch Next, TMDB or the web, whichever has something (Shelf.load).
            val content = Shelf.load(this, pkg)
            val items = content?.items.orEmpty()
            // Start on the first item whose picture loads; art that is missing or too small is skipped.
            var start = -1
            var first: Bitmap? = null
            for (k in 0 until minOf(3, items.size)) {
                first = Shelf.image(this, items[k].image)
                if (first != null) { start = k; break }
            }
            val pic = first
            val at = start
            // Baked before the shelf appears, so the dock's glass comes with it, not after it.
            val glass = pic?.let { bakeDock(it) }
            main.post {
                if (shelfLoading != pkg || control.isOpen) return@post
                shelfLoading = null
                if (content == null || pic == null || glass == null) {
                    // The focused app has nothing to show: the previous app's shelf fades out now.
                    if (adapter.apps.getOrNull(focusedPos)?.pkg == pkg) hideShelf()
                    return@post
                }
                shelfPkg = pkg
                shelfItems = items
                shelfSource.text = content.source
                shelfDots.removeAllViews()
                repeat(items.size) {
                    shelfDots.addView(View(this).apply { background = rounded(Color.WHITE, dp(4).toFloat()) },
                        LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginStart = dp(7) })
                }
                showShelfItem(at, pic, glass)
                setShelfVisible(true)
                main.removeCallbacks(shelfAdvance)
                main.postDelayed(shelfAdvance, SHELF_SECONDS * 1000L)
            }
        }
    }

    /**
     * Shows item [i] with its picture [b] and [glass], the dock's glass over it. While the shelf is
     * shown, the dock's glass and the Control Center's follow the picture on the same frames; while
     * it is appearing, [setShelfVisible] brings the glass along with the shelf.
     */
    private fun showShelfItem(i: Int, b: Bitmap, glass: Bitmap) {
        val item = shelfItems.getOrNull(i) ?: return
        shelfIndex = i
        // The new picture fades in over the old one, settling from a slight zoom.
        val old = shelfFront
        val pic = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(b)
            alpha = 0f
            scaleX = 1.04f; scaleY = 1.04f
        }
        shelfPics.addView(pic, FrameLayout.LayoutParams(-1, -1))
        shelfFront = pic
        shelfGlass = glass
        shelfInfo.animate().cancel()
        shelfInfo.alpha = 0f
        shelfInfo.translationY = dp(10).toFloat()
        shelfTitle.text = item.title
        shelfMeta.text = item.meta
        shelfInfo.animate().alpha(1f).translationY(0f).setDuration(450).setStartDelay(150).start()
        for (k in 0 until shelfDots.childCount) shelfDots.getChildAt(k).alpha = if (k == i) 1f else 0.35f
        val ms = if (old == null) 0L else 700L
        // The dock refracts the new picture: staged now, moved with it.
        val glassFade = if (shelfShown) stageDock(glass) else null
        if (shelfShown) control.backgroundChanging(ms) { drawFinalScene(it) }
        // One Tween per picture: a newer one starting does not cut this one short.
        Tween().start(ms, Motion.ease, end = { if (old != null) shelfPics.removeView(old) }) { p ->
            pic.alpha = p
            pic.scaleX = lerp(1.04f, 1f, p); pic.scaleY = pic.scaleX
        }
        glassFade?.let { runFade(dockTween, it, ms, Motion.ease) }
        // Fetch the next picture ahead of time.
        shelfItems.getOrNull((i + 1) % shelfItems.size)?.let { n -> net.execute { Shelf.image(this, n.image) } }
    }

    private fun setShelfVisible(v: Boolean) {
        if (v == shelfShown) return
        shelfShown = v
        if (v) shelf.visibility = View.VISIBLE else bg.visibility = View.VISIBLE
        // The large clock gives way to the shelf.
        bigClock.animate().alpha(if (v) 0f else dock.alpha).setDuration(350).start()
        // The shelf, the dock's glass and the Control Center's glass change together: staged, then
        // started in the same frame with the same duration and curve.
        val ms = if (v) 500L else 350L
        val curve = shelfCurve
        val glassFade = (if (v) shelfGlass else homeDock)?.let { stageDock(it) }
        control.backgroundChanging(ms) { drawFinalScene(it) }
        val a0 = shelf.alpha
        val a1 = if (v) 1f else 0f
        shelfTween.start(ms, curve, end = {
            // The shelf covers the whole screen: stop drawing the photo underneath it.
            if (shelfShown) bg.visibility = View.INVISIBLE
            else {
                shelf.visibility = View.INVISIBLE
                shelfPics.removeAllViews()
                shelfFront = null
            }
        }) { shelf.alpha = lerp(a0, a1, it) }
        glassFade?.let { runFade(dockTween, it, ms, curve) }
    }

    private fun hideShelf() {
        main.removeCallbacks(shelfShow)
        main.removeCallbacks(shelfAdvance)
        pendingShelf = null
        shelfLoading = null
        shelfPkg = null
        setShelfVisible(false)
    }

    fun pauseShelfRotation() {
        main.removeCallbacks(shelfShow)
        main.removeCallbacks(shelfAdvance)
    }

    fun resumeShelfRotation() {
        if (shelfShown) {
            main.removeCallbacks(shelfAdvance)
            main.postDelayed(shelfAdvance, SHELF_SECONDS * 1000L)
        }
    }

    private fun updateClock() {
        val now = Date()
        clockText.text = SimpleDateFormat(if (prefs.clock24) "HH:mm" else "h:mm a", Locale.US).format(now)
        bigDate.text = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(now).replaceFirstChar { it.titlecase(Locale.FRANCE) }
        // The large clock leaves out AM/PM, as tvOS did; at that size it reads as the time of day.
        bigTime.text = SimpleDateFormat(if (prefs.clock24) "HH:mm" else "h:mm", Locale.US).format(now)
    }

    private fun applyClockStyle() {
        val style = prefs.clockStyle
        clockText.visibility = if (style == Prefs.CLOCK_CORNER) View.VISIBLE else View.GONE
        bigClock.visibility = if (style == Prefs.CLOCK_LARGE) View.VISIBLE else View.GONE
        updateClock()
    }

    /** The weather views, next to the clocks they go with; hidden until there is a forecast to show. */
    private fun buildWeather(icons: LinearLayout) {
        wxIcon = ImageView(this)
        wxTemp = text(14f, weight = 600).apply {
            setShadowLayer(dp(5).toFloat(), 0f, dp(1).toFloat(), Color.argb(140, 0, 0, 0))
        }
        wxCorner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(wxIcon, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(4) })
            addView(wxTemp)
        }
        icons.addView(wxCorner, icons.indexOfChild(clockText), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })
        wxLine = text(18f, Color.argb(230, 255, 255, 255), weight = 500).apply {
            setShadowLayer(dp(8).toFloat(), 0f, 0f, Color.argb(60, 0, 0, 0))
            visibility = View.GONE
        }
        bigClock.addView(wxLine, bigClock.indexOfChild(bigDate) + 1, LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = dp(4); topMargin = dp(6)
        })
    }

    /**
     * Shows the kept forecast with the clock style in use, and has a new one fetched when it is due
     * ([Weather.refreshIfDue]). From [ticker]: once a minute while the home screen is in front.
     */
    private fun updateWeather() {
        // Offline, the weather goes rather than showing a forecast that can no longer be refreshed.
        val f = if (prefs.weather && Net.isConnected(this)) Weather.cached(this) else null
        val style = prefs.clockStyle
        wxCorner.visibility = if (f != null && style == Prefs.CLOCK_CORNER) View.VISIBLE else View.GONE
        wxLine.visibility = if (f != null && style == Prefs.CLOCK_LARGE) View.VISIBLE else View.GONE
        if (f != null) {
            val icon = Weather.icon(f)
            if (wxIcon.tag != icon) { wxIcon.setImageResource(icon); wxIcon.tag = icon }
            Weather.degrees(f.temp).let { if (wxTemp.text.toString() != it) wxTemp.text = it }
            Weather.now(f).let { if (wxLine.text.toString() != it) wxLine.text = it }
        }
        Weather.refreshIfDue(this) { main.post { if (resumed && !isDestroyed) updateWeather() } }
    }

    /** The home scene alone, without the control center or any dialog; the glass controls sample it. */
    val sceneView: View get() = scene

    private fun updateNetworkIcon() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = try { cm.getNetworkCapabilities(cm.activeNetwork) } catch (e: SecurityException) { null }
        val wired = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        netButton.setIcon(if (wired) R.drawable.ic_ethernet else R.drawable.ic_wifi)
        netButton.alpha = if (caps == null) 0.4f else 1f
    }

    /** Pins a TV input to the grid, or takes it off (out of its folder too, so it does not come back there). */
    fun pinInput(i: Input, on: Boolean) {
        val enc = InputTile.encode(i)
        prefs.pinnedInputs = prefs.pinnedInputs.filter { InputTile.decode(it)?.uri != i.uri }.toSet() + if (on) setOf(enc) else emptySet()
        if (on) prefs.hidden = prefs.hidden - InputTile.key(i)
        else prefs.editFolders { Folders.remove(it, InputTile.key(i)) }
        reload()
    }

    fun isPinned(i: Input) = prefs.pinnedInputs.any { InputTile.decode(it)?.uri == i.uri }

    /** Shows the grid again from the saved state; [first] puts focus on its first entry (another profile's grid). */
    private fun reload(changed: String? = null, first: Boolean = false) {
        io.execute {
            val list = repo.orderedVisible()
            main.post {
                val focusedPkg = adapter.apps.getOrNull(focusedPos)?.pkg
                adapter.submit(list, changed)
                val idx = if (first) 0 else list.indexOfFirst { it.pkg == focusedPkg }.coerceAtLeast(0)
                // An open folder follows the grid; one that was deleted or emptied closes.
                folderView?.let { fv ->
                    val f = list.firstOrNull { it.pkg == fv.key }
                    if (f == null || f.folder.isNullOrEmpty()) closeFolder()
                    else fv.update(f, changed, focus = sheets.isEmpty() && !control.isOpen)
                }
                grid.post {
                    // On first show the header buttons grab default focus; the grid should have it.
                    val headerOk = header.hasFocus() && !firstFocus && !first
                    // A new profile's grid: focus goes to its first entry even if the old grid had it (stable ids would keep it on the same app).
                    if ((first || !grid.hasFocus()) && !headerOk && sheets.isEmpty() && !control.isOpen && folderView == null) focusAt(idx)
                    firstFocus = false
                    // A new list can leave the grid between rows; put the focused row back where it belongs.
                    grid.post { grid.focusedChild?.let { grid.layoutManager?.requestChildRectangleOnScreen(grid, it, Rect(), false, true) } }
                }
            }
        }
    }

    /** How far the grid is scrolled from its resting place, worked out from the first laid-out tile. */
    private fun gridOffset(): Int? {
        val lm = grid.layoutManager ?: return null
        val child = grid.getChildAt(0) ?: return null
        val p = grid.getChildAdapterPosition(child).takeIf { it >= 0 } ?: return null
        return firstRowY + (p / cols) * rowPitch - lm.getDecoratedTop(child)
    }

    private fun focusAt(pos: Int) {
        if (adapter.itemCount == 0) return
        val p = pos.coerceIn(0, adapter.itemCount - 1)
        val vh = grid.findViewHolderForAdapterPosition(p)
        if (vh != null) vh.itemView.requestFocus() else {
            grid.scrollToPosition(p)
            grid.post { grid.findViewHolderForAdapterPosition(p)?.itemView?.requestFocus() }
        }
    }

    // ------------------------------------------------------------------- tiles

    override fun onTileFocused(pos: Int, cell: TileCell) {
        focusedPos = pos
        val app = adapter.apps.getOrNull(pos)
        val pkg = app?.pkg
        main.removeCallbacks(shelfShow)
        // Shelf.has is cheap; what there is to show is found off the main thread (showShelf). Inputs and folders have none.
        if (pos < cols && pkg != null && app.input == null && app.folder == null && pkg != packageName && moving < 0 && Shelf.has(this, pkg)) {
            if (pkg != shelfPkg || !shelfShown) {
                // The shelf of the previous app stays up while the next one loads: when it arrives the
                // pictures cross-fade; when there is nothing, the shelf fades out then (showShelf).
                pendingShelf = pkg
                shelfLoading = null
                main.removeCallbacks(shelfAdvance)
                // Wait for focus to settle so sliding along the dock does not flicker.
                main.postDelayed(shelfShow, 300)
            }
        } else hideShelf()
    }

    override fun onTileClicked(pos: Int, cell: TileCell) {
        if (moving >= 0) { endMove(); return }
        val app = adapter.apps.getOrNull(pos) ?: return
        if (app.folder != null) { openFolder(pos); return }
        launch(app, cell, app.pkg)
    }

    /**
     * Opens [app] from its tile, or switches to it for a TV input. [recent] is what "Récents" brings
     * to the front once it has opened: the app itself, or the folder it was opened from.
     */
    private fun launch(app: AppEntry, cell: TileCell, recent: String) {
        app.input?.let { i -> io.execute { tv.setInput(i) }; return }
        // The stock launcher (Xiaomi's PatchWall) can only be opened through the system Home action.
        if (HomeGuardService.isStockHome(app.pkg) && HomeGuardService.openStockLauncher()) {
            rememberOpened(recent)
            return
        }
        val intent = repo.launchIntent(app) ?: return
        val opts = ActivityOptions.makeScaleUpAnimation(cell.card, 0, 0, cell.card.width, cell.card.height)
        try {
            startActivity(intent, opts.toBundle())
            rememberOpened(recent)
        } catch (e: Exception) {
            Toast.makeText(this, "Impossible d'ouvrir ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Only successful app launches reorder the shelf; editing or failed launches never do, and
     * neither does a launch while the dock is "Fixe" (Prefs.dockRecents off): the arrangement the
     * user made with "Modifier l'accueil" stays as it is.
     */
    private fun rememberOpened(pkg: String) {
        if (!prefs.dockRecents) return
        val visible = adapter.apps.map { it.pkg }
        prefs.order = RecentOrder.promote(pkg, visible, prefs.order)
        reload()
    }

    override fun onTileLongPressed(pos: Int, cell: TileCell) {
        if (moving >= 0) return
        val app = adapter.apps.getOrNull(pos) ?: return
        if (app.folder != null) { folderMenu(app, pos); return }
        app.input?.let { i ->
            val s = Sheet(root).title(app.label).message(null)
                .button("Modifier l'accueil") { startMove(pos) }
                .button("Renommer") { rename(app, pos) }
            // An input opens from a folder like from the grid.
            folderButtons(s, app, pos)
            showSheet(s.button("Retirer de l'accueil") { pinInput(i, false) }
                .button("Annuler") {}, restoreFocusTo = pos)
            return
        }
        val s = Sheet(root).title(app.label).message(null)
            .button("Modifier l'accueil") { startMove(pos) }
            .button("Renommer") { rename(app, pos) }
        folderButtons(s, app, pos)
        // A restricted profile neither hides apps nor reaches what is shared with every profile (pictures, system screens).
        val kid = prefs.restricted
        // A picture of the user's, from the phone page or pushed over adb (AppRepo.customTileFile).
        if (app.tileStamp != 0L && !kid) s.button("Image d'origine") {
            repo.invalidate(app.pkg)
            io.execute {
                AppRepo.clearCustomTile(this, app.pkg)
                main.post { reload(app.pkg) }
            }
        }
        if (!kid) s.button("Masquer") {
            prefs.hidden = prefs.hidden + app.pkg
            reload()
        }
        if (repo.isOwn(app.pkg)) {
            showSheet(s.button("Annuler") {}, restoreFocusTo = pos)
            return
        }
        if (!kid) s.button("Infos sur l'app") { appInfo(app) }
        if (!repo.isEssential(app.pkg)) s.button("Supprimer l'app") { unlocked("Supprimer l'app") { confirmDelete(app, pos) } }
        s.button("Annuler") {}
        showSheet(s, restoreFocusTo = pos)
    }

    private fun appInfo(app: AppEntry) {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ----------------------------------------------------------------- folders

    /** "Nouveau dossier…" and, once there are folders, "Ajouter au dossier…", for the entry at [pos] of the grid. */
    private fun folderButtons(s: Sheet, app: AppEntry, pos: Int) {
        s.button("Nouveau dossier…") { newFolder(app, pos) }
        if (adapter.apps.any { it.folder != null }) s.button("Ajouter au dossier…") { pickFolder(app, pos) }
    }

    /** A folder named on the TV's keyboard ("Dossier" when left empty), in [app]'s place on the grid, with [app] in it. */
    private fun newFolder(app: AppEntry, pos: Int) {
        showSheet(Sheet(root).title("Nouveau dossier").message("Le dossier prend la place de « ${app.label} » sur l'accueil, avec l'app dedans.")
            .input("", Folders.DEFAULT_NAME) { name ->
                val id = Folders.newId(prefs.folderList().map { it.id })
                prefs.editFolders { Folders.create(it, id, name, listOf(app.pkg)) }
                prefs.order = Folders.orderReplacing(adapter.apps.map { it.pkg }, prefs.order, app.pkg, listOf(Folders.key(id)))
                reload()
            }, restoreFocusTo = pos)
    }

    private fun pickFolder(app: AppEntry, pos: Int) {
        val s = Sheet(root).title("Ajouter au dossier").message("« ${app.label} » quitte l'accueil pour le dossier choisi.")
        for (f in adapter.apps.filter { it.folder != null }) s.button(f.label) {
            Folders.idOf(f.pkg)?.let { id -> prefs.editFolders { Folders.add(it, id, app.pkg) } }
            reload()
        }
        showSheet(s.button("Annuler") {}, restoreFocusTo = pos)
    }

    private fun folderMenu(f: AppEntry, pos: Int) {
        showSheet(Sheet(root).title(f.label).message(null)
            .button("Ouvrir") { openFolder(pos) }
            .button("Modifier l'accueil") { startMove(pos) }
            .button("Renommer le dossier") { renameFolder(f, pos) }
            .button("Ajouter des apps…") { pickApps(f) }
            .button("Supprimer le dossier") { confirmDeleteFolder(f, pos) }
            .button("Annuler") {}, restoreFocusTo = pos)
    }

    private fun renameFolder(f: AppEntry, pos: Int) {
        showSheet(Sheet(root).title("Renommer le dossier").message(null)
            .input(f.label, Folders.DEFAULT_NAME) { name ->
                Folders.idOf(f.pkg)?.let { id -> prefs.editFolders { Folders.rename(it, id, name) } }
                reload()
            }, restoreFocusTo = pos)
    }

    /**
     * Ticks apps into and out of folder [f]: its own apps first (ticked), then the grid's. Each press
     * shows at once behind the sheet; an app ticked off goes back to the end of the grid.
     */
    private fun pickApps(f: AppEntry) {
        val id = Folders.idOf(f.pkg) ?: return
        val s = Sheet(root).title("Ajouter des apps").message("Les apps cochées sont dans « ${f.label} » ; les autres restent sur l'accueil.")
        for (a in f.folder.orEmpty()) s.toggle(a.label, true) { on -> setInFolder(id, a.pkg, on) }
        for (a in adapter.apps.filter { it.folder == null }) s.toggle(a.label, false) { on -> setInFolder(id, a.pkg, on) }
        // Apps that leave the grid can shift the folder: focus finds it by key.
        showSheet(s.button("OK") {}, restoreKey = f.pkg)
    }

    private fun setInFolder(id: String, pkg: String, on: Boolean) {
        if (!on) return removeFromFolder(pkg)
        prefs.editFolders { Folders.add(it, id, pkg) }
        reload()
    }

    /** Takes [pkg] out of its folder, back onto the grid at its end. */
    private fun removeFromFolder(pkg: String) {
        prefs.editFolders { Folders.remove(it, pkg) }
        prefs.order = Folders.orderAtEnd(adapter.apps.map { it.pkg }, prefs.order, pkg)
        reload()
    }

    private fun confirmDeleteFolder(f: AppEntry, pos: Int) {
        showSheet(Sheet(root).title("Supprimer « ${f.label} » ?").message("Le dossier disparaît ; ses apps reviennent sur l'accueil à sa place.")
            .button("Supprimer") {
                val id = Folders.idOf(f.pkg) ?: return@button
                // Every app it held, hidden ones included, so they keep a place in the order.
                val apps = prefs.folderList().firstOrNull { it.id == id }?.apps.orEmpty()
                prefs.editFolders { Folders.delete(it, id) }
                prefs.order = Folders.orderReplacing(adapter.apps.map { it.pkg }, prefs.order, f.pkg, apps)
                reload()
            }
            .button("Annuler") {}, restoreFocusTo = pos)
    }

    /** Opens the folder at [pos] over the grid; an empty one goes straight to picking its apps. */
    private fun openFolder(pos: Int) {
        val f = adapter.apps.getOrNull(pos) ?: return
        if (f.folder.isNullOrEmpty()) { pickApps(f); return }
        folderView?.close(animate = false)
        hideShelf()
        setGridBlocked(true)
        folderView = FolderOverlay(root, repo, f, tileW, tileH, gridGap, rowPitch, cols, adapter.tvosFocus, folderActions)
            .also { it.open() }
    }

    /** Closes the open folder and focuses its tile again, wherever the grid now has it. */
    private fun closeFolder(animate: Boolean = true) {
        val fv = folderView ?: return
        folderView = null
        fv.close(animate)
        adapter.apps.indexOfFirst { it.pkg == fv.key }.takeIf { it >= 0 }?.let { focusedPos = it }
        // A sheet still closing (one opened from the folder) restores the grid itself when it is gone.
        if (sheets.isEmpty() && !control.isOpen) restoreGridFocus()
    }

    private val folderActions = object : FolderOverlay.Actions {
        override fun onLaunch(app: AppEntry, cell: TileCell) {
            // "Récents" brings the folder forward, not the app inside it.
            folderView?.let { launch(app, cell, it.key) }
        }

        override fun onMenu(app: AppEntry, pos: Int) {
            val s = Sheet(root).title(app.label).message(null)
                .button("Retirer du dossier") { removeFromFolder(app.pkg) }
                .button("Renommer") { rename(app, null) }
            if (!repo.isOwn(app.pkg) && !prefs.restricted) s.button("Infos sur l'app") { appInfo(app) }
            showSheet(s.button("Annuler") {})
        }
    }

    /** Names the tile; an empty name gives the app back its own. The TV's keyboard does the typing. */
    private fun rename(app: AppEntry, pos: Int?) {
        showSheet(Sheet(root).title("Renommer « ${app.label} »").message("Laissez le champ vide pour reprendre le nom d'origine, « ${app.originalLabel} ».")
            .input(app.label, app.originalLabel) { text ->
                val name = text.trim()
                prefs.setLabel(app.pkg, name.takeIf { it.isNotEmpty() && it != app.originalLabel })
                reload(app.pkg)
            }, restoreFocusTo = pos)
    }

    /**
     * Deletes through local adb, which also removes preinstalled apps the system
     * won't let go of ("pm uninstall --user 0": the APK stays on the system
     * partition, so it can be restored from settings).
     */
    private fun confirmDelete(app: AppEntry, pos: Int) {
        val system = repo.isSystem(app.pkg)
        if (!prefs.adbAuthorized) {
            if (!system) {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            showSheet(Sheet(root).title("Autoriser le débogage local")
                .message("Supprimer une app préinstallée nécessite le débogage local. Appuyez sur Commencer : le téléviseur affiche « Autoriser le débogage ? ». Cochez « Toujours autoriser », puis choisissez « Autoriser ».")
                .button("Commencer") { authorizeAdb() }
                .button("Annuler") {}, restoreFocusTo = pos)
            return
        }
        val msg = when {
            system -> "Cette app est préinstallée sur le téléviseur. Une fois supprimée, elle n'apparaît plus sur l'accueil et ne tourne plus en arrière-plan. Vous pourrez la restaurer dans Réglages de l'accueil → Apps → Apps préinstallées supprimées."
            repo.isLauncher(app.pkg) -> "Cette app est un écran d'accueil, qui peut prendre le relais si celui-ci a un problème. Sans elle, le téléviseur n'aura plus d'accueil de secours si celui-ci ne démarre pas. L'app et ses données seront supprimées."
            else -> "L'app et ses données seront supprimées."
        }
        showSheet(Sheet(root).title("Supprimer « ${app.label} » ?").message(msg)
            .button("Supprimer") {
                io.execute {
                    val out = LocalAdb.shell(this, if (system) "pm uninstall -k --user 0 ${app.pkg}" else "pm uninstall ${app.pkg}", 15_000)
                    val ok = out?.contains("Success") == true
                    main.post {
                        if (ok) {
                            if (system) prefs.removed = prefs.removed + app.pkg
                            reload()
                        } else Toast.makeText(this, "Échec de la suppression : ${out?.trim()?.take(80) ?: "débogage local indisponible"}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .button("Annuler") {}, restoreFocusTo = pos)
    }

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
        val col = pos % cols
        val target = pos + dx + dy * cols
        val lastRow = (n - 1) / cols
        val atEdge = (dx < 0 && col == 0) || (dx > 0 && (col == cols - 1 || pos == n - 1)) ||
            (dy < 0 && pos < cols) || (dy > 0 && pos / cols == lastRow)

        if (moving >= 0) {
            if (!atEdge) {
                val to = target.coerceIn(0, n - 1)
                adapter.move(pos, to)
                moving = to
                grid.post { focusAt(to) }
            } else Motion.bump(cell.card, dx, dy)
            return true
        }
        if (dy < 0 && pos < cols) {
            // Up from the first row reaches the buttons in the top-right corner.
            ccButton.requestFocus()
            return true
        }
        if (atEdge) {
            Motion.bump(cell.card, dx, dy)
            return true
        }
        if (dy > 0 && target >= n) {
            // Partial last row: drop to its last tile like tvOS does.
            focusAt(n - 1)
            return true
        }
        return false
    }

    private fun startMove(pos: Int) {
        moving = pos
        adapter.movingPkg = adapter.apps[pos].pkg
        adapter.notifyItemChanged(pos)
        grid.post { focusAt(pos) }
        Toast.makeText(this, "Déplacez avec les flèches, puis appuyez sur OK", Toast.LENGTH_SHORT).show()
    }

    private fun endMove() {
        val pos = moving
        moving = -1
        adapter.movingPkg = null
        adapter.notifyItemChanged(pos)
        prefs.order = adapter.apps.map { it.pkg }
        grid.post { focusAt(pos) }
    }

    // ---------------------------------------------------------------- profiles

    private val pins by lazy { PinSheets(root, prefs) { showSheet(it) } }

    /** [action] at once, or after the PIN when the profile on screen is a restricted one. */
    private fun unlocked(title: String, action: () -> Unit) {
        if (prefs.restricted) pins.ask(title, ok = action) else action()
    }

    /**
     * The avatar shows the profile on screen: its photo, or its initial on its colour ([ProfileAvatar]).
     * It stays up whenever there is more than one profile, being the only way to switch from here,
     * even with "Photo de profil" off.
     */
    private fun showAvatar() {
        val p = prefs.current()
        avatar.setIcon(ProfileAvatar.drawable(this, p, IconButton.PORTRAIT_DP))
        avatar.contentDescription = "Profil : ${Profiles.displayName(p)}"
        avatar.visibility = if (prefs.showAvatar || prefs.profileList().size > 1) View.VISIBLE else View.GONE
    }

    /** The avatar's sheet: one button per profile (the current one ticked), then managing them in the settings. */
    private fun profileSheet() {
        val list = prefs.profileList()
        val current = prefs.currentProfile
        val unprotected = !prefs.hasPin && list.any { it.restricted }
        val msg = when {
            unprotected -> "Un profil enfant demande un code PIN pour changer de profil, ouvrir les réglages ou supprimer une app. Aucun code n'est encore défini."
            list.size == 1 -> "Chaque profil a son propre accueil : ordre des apps, apps masquées, dossiers et fond d'écran. Créez-en dans « Gérer les profils… »."
            else -> null
        }
        val s = Sheet(root).title("Profils").message(msg)
        for (p in list) {
            val name = Profiles.displayName(p)
            s.button(if (p.id == current) "✓  $name" else name, ProfileAvatar.drawable(this, p, PillButton.PICTURE_DP)) { if (p.id != current) switchTo(p) }
        }
        if (unprotected) s.button("Définir un code PIN…") { pins.choose("Code PIN") }
        // The settings ask for the PIN themselves in a restricted profile, however they are opened.
        s.button("Gérer les profils…") { openSettings(SettingsActivity.PAGE_PROFILES) }
        showSheet(s.button("Annuler") {})
    }

    /**
     * Switches to [p]: leaving a restricted profile takes the PIN, and a restricted profile cannot be
     * entered before there is one (it could be left freely, and the parent may not know).
     */
    private fun switchTo(p: Profile) {
        when {
            p.restricted && !prefs.hasPin -> pins.choose("Code PIN",
                "« ${Profiles.displayName(p)} » est un profil enfant : choisissez d'abord le code PIN qui permettra d'en sortir (de ${Profiles.PIN_MIN} à ${Profiles.PIN_MAX} chiffres).") {
                applyProfile(p.id)
            }
            prefs.restricted -> pins.ask("Changer de profil") { applyProfile(p.id) }
            else -> applyProfile(p.id)
        }
    }

    /** Switches to profile [id], PIN already taken care of. */
    private fun applyProfile(id: String) {
        // A move in progress belongs to the grid being left.
        if (moving >= 0) endMove()
        if (prefs.switchProfile(id)) showProfile()
    }

    /** The profile whose home is on screen, to notice a switch made elsewhere (over adb). */
    private var shownProfile = Profiles.MAIN

    /** Shows the current profile's home: its grid from the first entry, its background and its avatar. */
    private fun showProfile() {
        shownProfile = prefs.currentProfile
        // A move left half done (a switch over adb) is dropped, not saved into the new profile's order.
        if (moving >= 0) { moving = -1; adapter.movingPkg = null }
        control.close()
        closeFolder(animate = false)
        hideShelf()
        repo.invalidate()
        focusedPos = 0
        showAvatar()
        applyBackground()
        reload(first = true)
    }

    /** Opens the settings, on one of its pages ([SettingsActivity.EXTRA_PAGE]) or the main one. */
    fun openSettings(page: String? = null) {
        startActivity(Intent(this, SettingsActivity::class.java).apply { page?.let { putExtra(SettingsActivity.EXTRA_PAGE, it) } })
    }

    // ------------------------------------------------------------ overlays / keys

    /** Shows [s]; once it closes, focus goes back to the grid entry keyed [restoreKey], else at [restoreFocusTo]. */
    fun showSheet(s: Sheet, restoreFocusTo: Int? = null, restoreKey: String? = null): Sheet {
        sheets += s
        setGridBlocked(true)
        folderView?.block()
        s.onDismiss {
            sheets -= s
            val fv = folderView
            when {
                sheets.isNotEmpty() -> {}
                control.isOpen -> control.refocus()
                // A sheet opened from an open folder gives focus back to it; the grid stays blocked.
                fv != null -> fv.refocus()
                else -> {
                    setGridBlocked(false)
                    val at = restoreKey?.let { k -> adapter.apps.indexOfFirst { it.pkg == k }.takeIf { it >= 0 } }
                    focusAt(at ?: restoreFocusTo ?: focusedPos)
                }
            }
        }
        return s.show()
    }

    /** The top row (clock, weather, network, Control Center and profile buttons) steps aside while the Control Center is open. */
    fun setHeaderShown(shown: Boolean, immediate: Boolean = false) {
        header.animate().cancel()
        if (immediate) { header.alpha = if (shown) 1f else 0f; return }
        header.animate().alpha(if (shown) 1f else 0f).setDuration(if (shown) 220 else 150).start()
    }

    /** Keeps D-pad focus from wandering from an overlay onto the screen behind it. */
    fun setGridBlocked(blocked: Boolean) {
        val mode = if (blocked) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
        grid.descendantFocusability = mode
        header.descendantFocusability = mode
    }

    fun restoreGridFocus() {
        folderView?.let { it.refocus(); return }
        setGridBlocked(false)
        focusAt(focusedPos)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        restartIdle()
        // Any key skips the intro to its closing zoom; nothing behind it reacts meanwhile.
        intro?.let { if (event.action == KeyEvent.ACTION_UP) it.skip(); return true }
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU)) {
            when {
                sheets.isNotEmpty() -> sheets.last().dismiss()
                // Before the Control Center: Back from an open folder returns to the grid.
                folderView != null -> closeFolder()
                // The large player first: its cursor, then itself, before the panel closes.
                control.isOpen -> control.back()
                moving >= 0 -> endMove()
                header.hasFocus() -> focusAt(focusedPos)
                else -> control.open()
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(Intro.EXTRA_PREVIEW, false)) { playIntro(preview = true); return }
        if (intent.getBooleanExtra(EXTRA_WAKE, false)) return  // onResume plays the intro
        checkCustomTiles()
        // Home pressed while already home: close everything and go back to the top.
        sheets.toList().forEach { it.dismiss() }
        control.close()
        if (moving >= 0) endMove()
        // The guard brought the screen back (an app exited to the stock launcher): stay where we were, in a folder too.
        if (intent.getBooleanExtra(EXTRA_GUARD, false)) return
        closeFolder(animate = false)
        focusedPos = 0
        // Scroll by our own offset: smoothScrollToPosition aligns to the padding and leaves the grid off its resting place.
        grid.smoothScrollBy(0, -scrollY, Motion.ease, 360)
        grid.postDelayed({ focusAt(0) }, 400)
    }

    override fun onStart() {
        super.onStart()
        Guard.ensure(this)
        main.postDelayed({ Guard.rebindIfStalled(this) }, 5_000)
    }

    override fun onResume() {
        super.onResume()
        // Renamed or recoloured in the settings meanwhile, or switched over adb.
        if (prefs.currentProfile != shownProfile) showProfile() else showAvatar()
        adapter.tvosFocus = prefs.focusTvos(this)
        applyClockStyle()
        control.applyStyle()
        if (wakeIntro) { wakeIntro = false; playIntro(wake = true) }
        // What was just watched changes the apps' Watch Next rows.
        Shelf.onHomeResumed()
        resumed = true
        prefs.homeInFront = true
        HomeGuardService.onHomeResumed()
        restartIdle()
        applyBackground()
        updateNetworkIcon()
        checkCustomTiles()
        main.removeCallbacks(ticker)
        ticker.run()
        control.refresh()
        if (shelfShown && !control.isOpen) resumeShelfRotation()
        // Notification banners (Prefs.banners) show in here while the home screen is in front.
        Banners.attach(root)
    }

    override fun onPause() {
        // Leaving for another app (or standby) ends the intro rather than leaving it half played.
        intro?.finish()
        resumed = false
        prefs.homeInFront = false
        main.removeCallbacks(idleFrame)
        main.removeCallbacks(ticker)
        main.removeCallbacks(shelfAdvance)
        Banners.detach(root)
        super.onPause()
    }

    private fun askAdbAuthorization() {
        if (prefs.adbAuthorized || isFinishing) return
        showSheet(Sheet(root).title("Activer l'extinction et la mémoire")
            .message("Le débogage local doit être autorisé une fois. Appuyez sur Commencer : le téléviseur affiche « Autoriser le débogage ? ». Cochez « Toujours autoriser », puis choisissez « Autoriser ».")
            .button("Commencer") { authorizeAdb() }
            .button("Plus tard") {})
    }

    fun authorizeAdb() {
        Toast.makeText(this, "Dans la fenêtre qui s'affiche, cochez « Toujours autoriser » puis choisissez « Autoriser »", Toast.LENGTH_LONG).show()
        io.execute {
            val ok = LocalAdb.shell(this, "echo ok", 60_000) != null
            main.post {
                Toast.makeText(this, if (ok) "C'est fait : l'extinction et la libération de la mémoire sont disponibles" else "Autorisation refusée ; réessayez dans Réglages de l'accueil", Toast.LENGTH_LONG).show()
                control.refresh()
            }
        }
    }

    /** The Bluetooth capsule's first press, from Android 12 on, where BLUETOOTH_CONNECT is asked on screen; before, the manifest grants it. */
    fun requestBluetoothPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf(Bluetooth.PERMISSION), REQUEST_BLUETOOTH)
        else control.onBluetoothPermission(true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH) {
            control.onBluetoothPermission(grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
    }
}

/**
 * Darkens the shelf picture where text and the dock sit: along the bottom, to
 * the left behind the title, and a little at the top behind the corner buttons.
 */
class ShelfScrim : Drawable() {
    private val p = Paint()

    override fun draw(c: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        p.shader = LinearGradient(0f, h * 0.3f, 0f, h, Color.TRANSPARENT, Color.argb(215, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = LinearGradient(0f, 0f, w * 0.62f, 0f, Color.argb(150, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.2f, Color.argb(90, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * [to] fading in over [from] as [progress] goes from 0 to 1, each centre-cropped to the bounds.
 * Drawn over rather than cross-faded: two opaque pictures never dip darker half-way, and a
 * picture fading in over another (the shelf) blends the same way, so the dock's glass, the
 * picture it refracts and the Control Center's glass stay identical on every frame.
 */
class FadeDrawable(var from: Bitmap?, private val to: Bitmap) : Drawable() {
    var progress = 0f
        set(v) { field = v; invalidateSelf() }
    /** Draws the end of the fade, without invalidating: for a scene drawn off screen. */
    var settled = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val m = android.graphics.Matrix()

    override fun draw(c: Canvas) {
        val p = if (settled) 1f else progress
        val f = from
        if (p < 1f && f != null) cover(c, f, 255)
        if (p > 0f) cover(c, to, (p * 255).toInt().coerceIn(0, 255))
    }

    private fun cover(c: Canvas, b: Bitmap, alpha: Int) {
        val r = bounds
        val s = maxOf(r.width() / b.width.toFloat(), r.height() / b.height.toFloat())
        m.setScale(s, s)
        m.postTranslate(r.left + (r.width() - b.width * s) / 2, r.top + (r.height() - b.height * s) / 2)
        paint.alpha = alpha
        c.drawBitmap(b, m, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Round glyph button for the top-right corner; fills white when focused. */
class IconButton(ctx: Context, icon: Int, private val portrait: Boolean = false, onClick: () -> Unit) : FrameLayout(ctx) {
    /** [icon] 0: none yet, for a [portrait] set later with [setIcon]. */
    private val img = ImageView(ctx).apply {
        if (icon != 0) setImageResource(icon)
        if (portrait) {
            scaleType = ImageView.ScaleType.CENTER_CROP
            // A full circle whatever is set: a photo's square corners never show.
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setOval(0, 0, view.width, view.height)
            }
            clipToOutline = true
        }
    }
    private val disc = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
    }

    init {
        isFocusable = true
        isClickable = true
        background = disc
        val size = ctx.dp(if (portrait) PORTRAIT_DP else 16)
        addView(img, LayoutParams(size, size, Gravity.CENTER))
        setOnClickListener { onClick() }
        setOnFocusChangeListener { _, f ->
            disc.setColor(if (f) Color.WHITE else Color.TRANSPARENT)
            if (!portrait) img.setColorFilter(if (f) Color.BLACK else Color.WHITE)
            tweenScale(if (f) 1.1f else 1f, 150)
        }
    }

    fun setIcon(res: Int) = img.setImageResource(res)
    fun setIcon(d: Drawable) = img.setImageDrawable(d)

    companion object {
        /** Side of a portrait (the profile's avatar), in dp; a glyph is 16. */
        const val PORTRAIT_DP = 24
    }
}
