package com.getisolated.canvasos

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.palette.graphics.Palette
import com.getisolated.canvasos.tv.Input
import org.json.JSONObject
import java.io.File
import java.text.Collator
import java.util.Locale

data class AppEntry(
    val pkg: String,
    val label: String,
    val component: ComponentName,
    val leanback: Boolean,
    /** Set for a TV input pinned to the grid; then [pkg] is its [InputTile.key]. */
    val input: Input? = null,
    /** The name the app gives itself; [label] is the user's when they renamed it ([Prefs.labels]). */
    val originalLabel: String = label,
    /** Version of the user's own tile picture ([AppRepo.customTileFile]), 0 for none: a new one makes the grid redraw the tile. */
    val tileStamp: Long = 0L,
    /** Set for a folder ([Folders]): the apps it shows, in its order; then [pkg] is its [Folders.key]. */
    val folder: List<AppEntry>? = null,
    /** A folder's version: its mosaic's apps and their tiles. A new one redraws the folder's tile ([AppRepo.tileKey]). */
    val stamp: Long = 0L,
)

/** The user's names for grid entries, kept in [Prefs.labels] as a JSON object (grid key → name). */
object Labels {
    const val MAX = 60

    fun decode(json: String): Map<String, String> {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (k in o.keys()) o.optString(k).trim().takeIf { it.isNotEmpty() }?.let { out[k] = it }
        return out
    }

    /** [json] with [pkg] renamed to [label]; null or blank drops the entry, so the app's own name comes back. */
    fun with(json: String, pkg: String, label: String?): String {
        val map = decode(json).toMutableMap()
        val name = label?.trim()?.take(MAX)
        if (name.isNullOrEmpty()) map.remove(pkg) else map[pkg] = name
        return JSONObject(map).toString()
    }
}

/** A TV input (HDMI) pinned to the home grid from the Control Center's input list. */
object InputTile {
    private const val PREFIX = "input:"
    private const val SEP = "\u001f"

    fun key(i: Input) = PREFIX + i.uri
    fun isInput(pkg: String) = pkg.startsWith(PREFIX)

    /** How it is kept in [Prefs.pinnedInputs]. */
    fun encode(i: Input) = listOf(i.uri, i.title, i.label).joinToString(SEP)

    fun decode(s: String): Input? {
        val p = s.split(SEP)
        return if (p.size == 3) Input(p[0], p[1], p[2], false) else null
    }
}

/** Enumerates launchable apps and renders their 5:3 tvOS-style tiles. */
class AppRepo(private val ctx: Context) {
    private val pm: PackageManager = ctx.packageManager
    private val prefs = Prefs(ctx)
    private val tiles = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun allApps(): List<AppEntry> {
        val byPkg = LinkedHashMap<String, AppEntry>()
        fun add(list: List<ResolveInfo>, leanback: Boolean) {
            for (ri in list) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == ctx.packageName && (!leanback || ai.name == HomeActivity::class.java.name)) continue
                // canvasOS lists two screens of its own (settings, 壁纸); each gets a key of its own.
                val key = if (ai.packageName == ctx.packageName && ai.name != SettingsActivity::class.java.name) ownKey(ai.name) else ai.packageName
                if (byPkg[key]?.leanback == true) continue
                byPkg[key] = AppEntry(
                    key,
                    ri.loadLabel(pm).toString().trim(),
                    ComponentName(ai.packageName, ai.name),
                    leanback,
                )
            }
        }
        add(pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0), true)
        add(pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0), false)
        // Firmware apps the maker's launcher shows although they declare no launcher category
        // (Xiaomi's 应用中心 by category, 高清播放器 and 米家 by name; from Aerial's device notes).
        add(pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(CATEGORY_APPSTORE_LAUNCHER), 0), false)
        for (c in UNLISTED_ENTRIES) {
            if (c.packageName in byPkg) continue
            val ai = runCatching { pm.getActivityInfo(c, 0) }.getOrNull() ?: continue
            if (ai.exported && ai.isEnabled && ai.applicationInfo.enabled)
                byPkg[c.packageName] = AppEntry(c.packageName, ai.loadLabel(pm).toString().trim(), c, false)
        }
        // Other launchers only confuse things on a launcher grid; so does the home screen itself
        // (the guard build lists it as an app).
        HIDE_ALWAYS.forEach { byPkg.remove(it) }
        if (byPkg[ctx.packageName]?.component?.className == HomeActivity::class.java.name) byPkg.remove(ctx.packageName)
        for (s in prefs.pinnedInputs) {
            val i = InputTile.decode(s) ?: continue
            byPkg[InputTile.key(i)] = AppEntry(InputTile.key(i), i.name, ComponentName(ctx, HomeActivity::class.java), false, i)
        }
        // The user's names and pictures; listing the tile folders is cheap, and finds pictures pushed over adb.
        val names = Labels.decode(prefs.labels)
        scanCustomTiles(ctx)
        val st = stamps
        return byPkg.values.map { e ->
            val name = names[e.pkg]
            val stamp = st[e.pkg] ?: 0L
            if (name == null && stamp == 0L) e else e.copy(label = name ?: e.label, originalLabel = e.label, tileStamp = stamp)
        }
    }

    /**
     * The grid: visible apps and folders in user order; new apps go to the end. An app in a folder
     * shows in the folder only. Hiding still wins: a hidden app stays in its folder's list but shows
     * nowhere, and an uninstalled one is left out of its folder (it comes back if reinstalled).
     */
    fun orderedVisible(apps: List<AppEntry> = allApps()): List<AppEntry> {
        val hidden = prefs.hidden
        var order = prefs.order
        if (order.isEmpty()) order = Prefs.DEFAULT_ORDER
        val rank = order.withIndex().associate { it.value to it.index }
        val collator = Collator.getInstance(Locale.FRANCE)
        val folders = prefs.folderList()
        val inFolder = Folders.members(folders)
        val byKey = apps.associateBy { it.pkg }
        val st = stamps
        val home = ComponentName(ctx, HomeActivity::class.java)
        val folderEntries = folders.map { f ->
            val shown = f.apps.mapNotNull { byKey[it] }.filter { it.pkg !in hidden }
            AppEntry(Folders.key(f.id), f.name, home, false, folder = shown, stamp = folderStamp(shown, st))
        }
        return (apps.filter { it.pkg !in hidden && it.pkg !in inFolder } + folderEntries)
            .sortedWith(compareBy<AppEntry> { rank[it.pkg] ?: Int.MAX_VALUE }
                .thenBy { if (isSystem(it.pkg)) 1 else 0 }
                .thenComparator { a, b -> collator.compare(a.label, b.label) })
    }

    /** Grid key of one of canvasOS's own screens besides settings. */
    private fun ownKey(cls: String) = ctx.packageName + ":" + cls.substringAfterLast('.')

    /** A screen of canvasOS itself or a pinned input: it can be moved and hidden, not deleted. */
    fun isOwn(pkg: String) = pkg == ctx.packageName || pkg.startsWith(ctx.packageName + ":") || InputTile.isInput(pkg)

    fun isSystem(pkg: String): Boolean = if (InputTile.isInput(pkg) || Folders.isFolder(pkg)) false else try {
        (pm.getApplicationInfo(pkg, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
    } catch (e: PackageManager.NameNotFoundException) { false }

    /**
     * Apps the TV needs to work (settings, the TV player, inputs, the built-in
     * launcher); never offered for deletion. Launchers the user installed can go.
     */
    fun isEssential(pkg: String): Boolean {
        if (pkg == ctx.packageName || pkg in ESSENTIAL) return true
        if (!isSystem(pkg)) return false
        if (listOf(".settings", "tvinput", "launcher", "systemui", "inputmethod", ".ime").any { it in pkg.lowercase() }) return true
        return isLauncher(pkg)
    }

    fun isLauncher(pkg: String): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return pm.queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS).any { it.activityInfo.packageName == pkg }
    }

    fun launchIntent(app: AppEntry): Intent? {
        if (app.component.packageName == ctx.packageName)
            return Intent(Intent.ACTION_MAIN).setComponent(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val i = if (app.leanback) pm.getLeanbackLaunchIntentForPackage(app.pkg) else null
        return (i ?: pm.getLaunchIntentForPackage(app.pkg) ?: Intent(Intent.ACTION_MAIN).setComponent(app.component))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    fun cachedTile(app: AppEntry): Bitmap? = tiles.get(tileKey(app))

    /**
     * The settings tile is keyed by its custom icon's date too, and an app with a picture of the
     * user's by that picture's version, so a newly uploaded one shows up. Cheap: the main thread
     * calls it for every tile it binds.
     */
    fun tileKey(pkg: String, st: Map<String, Long> = stamps): String = when {
        pkg == ctx.packageName -> pkg + "@" + (SettingsActivity.customIcon(ctx)?.lastModified() ?: 0L)
        else -> st[pkg]?.let { "$pkg@$it" } ?: pkg
    }

    /** [tileKey] of a grid entry; a folder's changes with its [AppEntry.stamp]. */
    fun tileKey(app: AppEntry, st: Map<String, Long> = stamps): String =
        if (app.folder != null) "${app.pkg}@${app.stamp}" else tileKey(app.pkg, st)

    /** A hash of the apps a folder's mosaic shows and of their tiles' keys (a new picture, the settings icon). */
    private fun folderStamp(shown: List<AppEntry>, st: Map<String, Long>): Long =
        shown.take(MOSAIC).fold(17L) { h, m -> h * 1_000_003L + tileKey(m.pkg, st).hashCode() }

    /** Builds the tile bitmap; call off the main thread. [keep] false leaves it out of the cache (the phone page's thumbnails). */
    fun tile(app: AppEntry, keep: Boolean = true): Bitmap {
        // One snapshot for the key and the picture: a plain key never holds the user's picture.
        val st = stamps
        val key = tileKey(app, st)
        tiles.get(key)?.let { return it }
        val bmp = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        app.input?.let { drawInputTile(c, it); if (keep) tiles.put(key, bmp); return bmp }
        app.folder?.let { members ->
            drawFolderTile(c, members)
            if (keep) {
                // The folder's earlier versions will not be shown again.
                tiles.snapshot().keys.filter { it.startsWith("${app.pkg}@") }.forEach { tiles.remove(it) }
                tiles.put(key, bmp)
            }
            return bmp
        }
        val own = if (app.component.className == SettingsActivity::class.java.name)
            SettingsActivity.customIcon(ctx)?.let { SettingsActivity.decodeIcon(it, TILE_W) }?.let { BitmapDrawable(ctx.resources, it) }
        else if (app.pkg in st)
            customTileFile(ctx, app.pkg)?.let { SettingsActivity.decodeIcon(it, TILE_W) }?.let { BitmapDrawable(ctx.resources, it) }
        else null
        val banner = own ?: try {
            pm.getActivityInfo(app.component, 0).loadBanner(pm)
                ?: pm.getApplicationInfo(app.pkg, 0).loadBanner(pm)
        } catch (e: Exception) { null }
        if (banner != null) {
            drawCenterCrop(c, banner)
        } else {
            val icon = try { pm.getActivityIcon(app.component) } catch (e: Exception) { pm.defaultActivityIcon }
            drawIconTile(c, icon)
        }
        if (keep) tiles.put(key, bmp)
        return bmp
    }

    /**
     * Drops the cached tile of [pkg] (it was updated or removed), whatever picture it was drawn with,
     * and of the folder whose mosaic shows it; or of every app.
     */
    fun invalidate(pkg: String? = null) {
        if (pkg == null) { tiles.evictAll(); return }
        val keys = listOf(pkg) + prefs.folderList().filter { pkg in it.apps }.map { Folders.key(it.id) }
        tiles.snapshot().keys.filter { k -> keys.any { k == it || k.startsWith("$it@") } }.forEach { tiles.remove(it) }
    }

    private fun drawCenterCrop(c: Canvas, d: Drawable) {
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: TILE_W
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: TILE_H
        val scale = maxOf(TILE_W.toFloat() / iw, TILE_H.toFloat() / ih)
        val w = (iw * scale).toInt()
        val h = (ih * scale).toInt()
        val l = (TILE_W - w) / 2
        val t = (TILE_H - h) / 2
        c.drawColor(Color.BLACK)
        d.setBounds(l, t, l + w, t + h)
        d.draw(c)
    }

    /**
     * Icon-only apps: the icon blown up and blurred fills the tile, with the
     * crisp icon in the middle — how tvOS 26 frames non-TV apps.
     */
    private fun drawIconTile(c: Canvas, icon: Drawable) {
        val src = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 192, 192)
        icon.draw(Canvas(src))
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val base = Palette.from(src).maximumColorCount(12).generate().let {
            it.lightVibrantSwatch ?: it.vibrantSwatch ?: it.dominantSwatch
        }?.rgb ?: Color.rgb(120, 130, 150)
        c.drawColor(base)
        // Crop the centre of the icon, stretch it over the tile and blur it.
        val crop = Bitmap.createBitmap(src, 24, 40, 144, 112)
        val blurred = Wallpapers.blurred(crop, darken = 0f)
        p.alpha = 235
        c.drawBitmap(blurred, null, RectF(-40f, -40f, TILE_W + 40f, TILE_H + 40f), p)
        p.alpha = 255
        // A light veil like frosted glass.
        c.drawColor(Color.argb(22, 255, 255, 255))
        val s = (TILE_H * 0.58f).toInt()
        val dst = RectF(((TILE_W - s) / 2).toFloat(), ((TILE_H - s) / 2).toFloat(),
            ((TILE_W + s) / 2).toFloat(), ((TILE_H + s) / 2).toFloat())
        c.drawBitmap(src, Rect(0, 0, 192, 192), dst, p)
        crop.recycle(); blurred.recycle(); src.recycle()
    }

    /** The dark glass card under input tiles. */
    private fun drawGlassCard(c: Canvas, p: Paint) {
        p.shader = android.graphics.LinearGradient(0f, 0f, TILE_W.toFloat(), TILE_H.toFloat(),
            Color.rgb(58, 64, 82), Color.rgb(22, 24, 32), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, TILE_W.toFloat(), TILE_H.toFloat(), p)
        p.shader = null
    }

    /** An input tile: a dark glass card with the input glyph and its name, like tvOS's own inputs. */
    private fun drawInputTile(c: Canvas, i: Input) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        drawGlassCard(c, p)
        val glyph = ctx.getDrawable(R.drawable.ic_input)!!.mutate()
        glyph.setTint(Color.WHITE)
        val g = 64
        glyph.setBounds((TILE_W - g) / 2, 46, (TILE_W + g) / 2, 46 + g)
        glyph.draw(c)
        p.color = Color.WHITE
        p.textAlign = Paint.Align.CENTER
        p.typeface = Fonts.inter(ctx, 600)
        p.textSize = 30f
        c.drawText(i.title, TILE_W / 2f, 160f, p)
        if (i.label.isNotEmpty()) {
            p.color = Color.argb(170, 255, 255, 255)
            p.typeface = Fonts.inter(ctx, 500)
            p.textSize = 20f
            c.drawText(i.label, TILE_W / 2f, 192f, p)
        }
    }

    /**
     * A folder: its first four apps' own tiles in a 2×2 mosaic on nothing at all (the owner's
     * choice: no card, no rim). Places left empty show a faint well, so a folder of one or two apps
     * still reads as a folder. Builds the members' tiles it needs (and caches them for when the
     * folder opens); off the main thread.
     */
    private fun drawFolderTile(c: Canvas, members: List<AppEntry>) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // 16:9 places as tall as the tile allows, centred.
        val gap = 12f
        val padY = 20f
        val h = (TILE_H - padY * 2 - gap) / 2
        val w = h * 16f / 9f
        val left = (TILE_W - w * 2 - gap) / 2
        val r = 11f
        // No shadow under the members: the mosaic sits straight on the wallpaper, like the tiles do.
        // Its own paint: the tiles' paint alpha would also fade the bitmap shader.
        val well = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(30, 255, 255, 255) }
        val m = android.graphics.Matrix()
        for (k in 0 until MOSAIC) {
            val x = left + (k % 2) * (w + gap)
            val y = padY + (k / 2) * (h + gap)
            val dst = RectF(x, y, x + w, y + h)
            val app = members.getOrNull(k)
            if (app == null) { c.drawRoundRect(dst, r, r, well); continue }
            val src = tile(app)
            // A shader rather than a clip: Canvas clips are not anti-aliased, the rounded rect is.
            p.shader = android.graphics.BitmapShader(src, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP).apply {
                m.setRectToRect(RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), dst, android.graphics.Matrix.ScaleToFit.FILL)
                setLocalMatrix(m)
            }
            c.drawRoundRect(dst, r, r, p)
            p.shader = null
        }
    }

    companion object {
        private const val CATEGORY_APPSTORE_LAUNCHER = "android.intent.category.APPSTORE_LAUNCHER"
        private val UNLISTED_ENTRIES = listOf(
            ComponentName("com.xiaomi.mitv.mediaexplorer", "com.xiaomi.mitv.mediaexplorer.NewScraperMainEntryActivity"), // 高清播放器
            ComponentName("com.xiaomi.smarthome.tv", "com.xiaomi.smarthome.tv.ui.MainActivity"), // 米家
        )
        const val TILE_W = 400
        const val TILE_H = 225
        /** Apps a folder's tile shows. */
        const val MOSAIC = 4

        /** Folder of the user's tile pictures, `<package>.jpg`, in app storage and in Android/data/…/files. */
        const val TILES_DIR = "tiles"
        /** A tile picture is kept at twice the tile's size, so it stays sharp if tiles grow. */
        private const val CUSTOM_W = 800
        private const val CUSTOM_H = 450

        /** Sent to this app alone when a name, a picture or the hidden list changed elsewhere (the phone page). */
        const val ACTION_TILE_CHANGED = "com.getisolated.canvasos.TILE_CHANGED"
        const val EXTRA_PKG = "pkg"

        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

        /**
         * The user's tile pictures by package, as [tileStamp] versions, from the last [scanCustomTiles].
         * Shared by every [AppRepo] in the process: the phone page's and the home screen's.
         */
        @Volatile private var stamps: Map<String, Long> = emptyMap()

        /**
         * Where a picture of the user's for [pkg] can be, in the order it is looked for: app storage
         * (uploaded from the phone page) first, then [externalDir] (Android/data/…/files, pushed over
         * adb), like the boot video. None for keys that are not a package name (canvasOS's own screens,
         * TV inputs): nothing outside the folder can be named.
         */
        fun customTilePaths(filesDir: File, externalDir: File?, pkg: String): List<File> =
            if (!PACKAGE.matches(pkg)) emptyList()
            else listOfNotNull(filesDir, externalDir).map { File(File(it, TILES_DIR), "$pkg.jpg") }

        /** Whether [pkg] can have a picture of the user's; the settings tile has its own icon (Prefs.ownIcon). */
        fun canHaveCustomTile(ctx: Context, pkg: String) = pkg != ctx.packageName && PACKAGE.matches(pkg)

        fun customTileFile(ctx: Context, pkg: String): File? =
            if (pkg == ctx.packageName) null
            else customTilePaths(ctx.filesDir, ctx.getExternalFilesDir(null), pkg).firstOrNull { it.isFile && it.length() > 0 }

        /** The version of [pkg]'s picture as last scanned, 0 for none; compare with [AppEntry.tileStamp]. */
        fun tileStamp(pkg: String): Long = stamps[pkg] ?: 0L

        /**
         * Re-reads which apps have a picture of the user's. Blocking (lists two folders). The version
         * takes the size too: `adb push` keeps the date of the computer's file, so a new picture can
         * carry an older date.
         */
        fun scanCustomTiles(ctx: Context) {
            val found = HashMap<String, Long>()
            // Android/data first, so a picture in app storage wins, as in customTileFile.
            for (dir in listOfNotNull(ctx.getExternalFilesDir(null), ctx.filesDir)) {
                File(dir, TILES_DIR).listFiles()?.forEach { f ->
                    val pkg = f.name.removeSuffix(".jpg")
                    if (f.name.endsWith(".jpg") && canHaveCustomTile(ctx, pkg) && f.isFile && f.length() > 0)
                        found[pkg] = f.lastModified() * 31 + f.length()
                }
            }
            stamps = found
        }

        /**
         * Makes [bytes] (any picture Android decodes) the tile of [pkg]: centre-cropped to 16:9 and
         * kept as an 800×450 JPEG in app storage. False when it is not a picture. Blocking.
         */
        fun setCustomTile(ctx: Context, pkg: String, bytes: ByteArray): Boolean {
            if (!canHaveCustomTile(ctx, pkg)) return false
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return false
            // The smallest power-of-two reduction that still covers the tile: a 48 MP photo decoded whole would not fit the heap.
            var sample = 1
            while (o.outWidth / (sample * 2) >= CUSTOM_W && o.outHeight / (sample * 2) >= CUSTOM_H) sample *= 2
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return false
            val out = Bitmap.createBitmap(CUSTOM_W, CUSTOM_H, Bitmap.Config.ARGB_8888)
            val scale = maxOf(CUSTOM_W.toFloat() / src.width, CUSTOM_H.toFloat() / src.height)
            val w = src.width * scale
            val h = src.height * scale
            Canvas(out).apply {
                drawColor(Color.BLACK)
                drawBitmap(src, null, RectF((CUSTOM_W - w) / 2, (CUSTOM_H - h) / 2, (CUSTOM_W + w) / 2, (CUSTOM_H + h) / 2),
                    Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            }
            src.recycle()
            val dir = File(ctx.filesDir, TILES_DIR).apply { mkdirs() }
            val tmp = File(dir, "$pkg.jpg.part")
            tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            out.recycle()
            // One pushed over adb would otherwise come back once this one is removed.
            customTilePaths(ctx.filesDir, ctx.getExternalFilesDir(null), pkg).forEach { it.delete() }
            val ok = tmp.renameTo(File(dir, "$pkg.jpg"))
            scanCustomTiles(ctx)
            return ok
        }

        /** Gives [pkg] back the tile drawn from its banner or icon; false when it had no picture of the user's. Blocking. */
        fun clearCustomTile(ctx: Context, pkg: String): Boolean {
            var any = false
            customTilePaths(ctx.filesDir, ctx.getExternalFilesDir(null), pkg).forEach { if (it.delete()) any = true }
            scanCustomTiles(ctx)
            return any
        }

        /** Tells the home screen to redraw [pkg] (or the whole grid): its name, picture or visibility changed. */
        fun notifyChanged(ctx: Context, pkg: String?) {
            ctx.sendBroadcast(Intent(ACTION_TILE_CHANGED).setPackage(ctx.packageName).apply { pkg?.let { putExtra(EXTRA_PKG, it) } })
        }
        val HIDE_ALWAYS = setOf(
            "com.oversea.aslauncher",
            "com.dangbei.TVHomeLauncher",
            "com.android.tv.settings.system.FallbackHome",
        )
        private val ESSENTIAL = setOf(
            "com.sony.dtv.tvx",                // Sony's TV / HDMI player
            "com.android.tv",                  // Live Channels
            "com.google.android.tv",
            "com.android.vending",
            "com.google.android.gms",
            "com.sony.dtv.systemupdate",
            "com.sony.dtv.update",
        )
    }
}
