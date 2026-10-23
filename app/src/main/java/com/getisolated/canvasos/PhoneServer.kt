package com.getisolated.canvasos

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.LruCache
import com.getisolated.canvasos.tv.TvControls
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address

/**
 * The page a phone opens after scanning the QR code (in the 壁纸 app, or Réglages de l'accueil →
 * Page téléphone): send photos, paste picture links, upload the boot animation and the settings
 * icon, set profile photos, the weather city and API keys, rename the grid's apps and change their
 * tiles, use the phone as a remote with a keyboard, and install an APK. The wallpaper source and
 * the screensaver are chosen on the TV. Typing a link or picking photos with a remote is
 * slow; with a phone it is not. After Aerial's phone page (github.com/pengchujin/aerial-tv, MIT).
 *
 * [KeeperService] keeps it listening on the local network whenever [Prefs.phoneServer] is on, so a
 * phone that opened it once can come back. Every address carries a random token ([Prefs.phoneToken])
 * shown only in the QR code. It is the only authentication: another device on the network cannot
 * use the page by guessing, and regenerating it locks out every phone that knew the old one. The
 * remote reaches canvasOS's own screens directly ([Foreground]); other apps, and the APK install,
 * go through local adb ([LocalAdb]) and need its authorization.
 */
class PhoneServer private constructor(private val ctx: Context) : NanoHTTPD(PORT) {
    private val prefs = Prefs(ctx)
    /** The grid's apps for the Apps tab; its tiles are drawn for the thumbnails and not kept. */
    private val repo by lazy { AppRepo(ctx) }
    /** The apps as last listed, so each thumbnail needn't query the package manager again. */
    @Volatile private var known: Map<String, AppEntry> = emptyMap()
    /** Thumbnails as JPEG by tile key: a new picture has a new key. */
    private val appThumbs = object : LruCache<String, ByteArray>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    private val thumbLock = Any()

    override fun serve(session: IHTTPSession): Response {
        // The first path segment is the token, read each time so a new one applies at once.
        val parts = session.uri.removePrefix("/").split('/', limit = 2)
        if (!PhoneApi.sameToken(parts[0], prefs.phoneToken)) return text(Response.Status.NOT_FOUND, "Not found")
        // Without the trailing slash, the page's relative links would resolve above the token.
        if (parts.size == 1) {
            return newFixedLengthResponse(Response.Status.REDIRECT, "text/plain; charset=utf-8", "").apply {
                addHeader("Location", "/${parts[0]}/")
            }
        }
        val path = "/" + parts.getOrElse(1) { "" }
        val post = session.method == Method.POST
        return runCatching {
            when {
                path == "/" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page())
                path == "/manifest.webmanifest" -> newFixedLengthResponse(
                    Response.Status.OK, "application/manifest+json; charset=utf-8", phoneManifest(parts[0]).toString())
                path == "/icon-192.png" || path == "/icon-512.png" || path == "/apple-touch-icon.png" -> webAsset(path.drop(1))
                path == "/api/state" -> json(state())
                path == "/api/apps" -> json(apps())
                path.startsWith("/thumb/app/") -> appThumb(path.removePrefix("/thumb/app/"))
                path.startsWith("/thumb/avatar/") -> avatarThumb(path.removePrefix("/thumb/avatar/"))
                path.startsWith("/thumb/") -> thumb(path.removePrefix("/thumb/"))
                post && path == "/api/links" -> {
                    val links = form(session)["links"].orEmpty().lines().map { it.trim() }
                        .filter { it.startsWith("http://") || it.startsWith("https://") }.distinct().take(20)
                    prefs.customWallUrls = links.joinToString("\n")
                    if (links.isNotEmpty()) {
                        prefs.background = Wallpapers.CUSTOM
                        // Fetching can take a while; the page polls the state for the count.
                        Thread { runCatching { Wallpapers(ctx, Wallpapers.CUSTOM).refresh(force = true) } }.start()
                    }
                    json(state())
                }
                post && path == "/api/delete" -> {
                    val f = form(session)
                    Wallpapers(ctx, if (f["source"] == Wallpapers.CUSTOM) Wallpapers.CUSTOM else Wallpapers.MINE).delete(f["id"].orEmpty())
                    json(state())
                }
                post && path == "/api/upload" -> upload(session)
                post && path == "/api/intro/source" -> {
                    val v = form(session)["source"].orEmpty()
                    if (v in Prefs.INTRO_SOURCES) prefs.introSource = v
                    json(state())
                }
                post && path == "/api/brand" -> {
                    val v = form(session)["brand"].orEmpty()
                    if (v in Brand.ALL) prefs.brand = v
                    json(state())
                }
                post && path == "/api/icon" -> {
                    val v = form(session)["source"].orEmpty()
                    if (v in Prefs.ICON_SOURCES) prefs.settingsIcon = v
                    json(state())
                }
                post && path == "/api/intro" -> {
                    val v = form(session)["mode"].orEmpty()
                    if (v in listOf(Prefs.INTRO_OFF, Prefs.INTRO_BOOT, Prefs.INTRO_WAKE)) {
                        prefs.intro = v
                        // This boot counts as played: turning it on doesn't start it on the next return home.
                        prefs.introBoot = Intro.bootStamp(ctx)
                    }
                    json(state())
                }
                post && path == "/api/intro/upload" -> uploadIntro(session)
                post && path == "/api/icon/upload" -> uploadIcon(session)
                post && path == "/api/icon/delete" -> {
                    session.parseBody(HashMap())
                    iconFiles().forEach { it.delete() }
                    if (prefs.settingsIcon == Prefs.ICON_OWN) prefs.settingsIcon = Prefs.ICON_BRAND
                    json(state())
                }
                post && path == "/api/intro/delete" -> {
                    session.parseBody(HashMap())
                    introFiles().forEach { it.delete() }
                    if (prefs.introSource == Prefs.INTRO_VIDEO) prefs.introSource = Prefs.INTRO_BRAND
                    json(state())
                }
                post && path == "/api/key" -> {
                    val name = form(session)["key"]
                    val item = PhoneApi.keyInput(name) ?: return text(Response.Status.BAD_REQUEST, "Unknown key")
                    val plan = PhoneApi.direct(name)
                    json(remote(item) { plan != null && Foreground.press(ctx, plan, adb = prefs.adbAuthorized) })
                }
                post && path == "/api/text" -> {
                    val typed = PhoneApi.typeable(form(session)["text"].orEmpty())
                    val args = PhoneApi.textArgs(typed)
                        ?: return json(JSONObject().put("ok", false).put("reason", "empty"))
                    json(remote(args) { Foreground.type(typed) })
                }
                post && path == "/api/apk/upload" -> installApk(session)
                post && path == "/api/app/label" -> {
                    val f = form(session)
                    val app = app(f["pkg"].orEmpty()) ?: return json(apps().put("ok", false))
                    val name = f["label"].orEmpty().trim()
                    prefs.setLabel(app.pkg, name.takeIf { it.isNotEmpty() && it != app.originalLabel })
                    AppRepo.notifyChanged(ctx, app.pkg)
                    json(apps().put("ok", true))
                }
                post && path == "/api/app/tile" -> uploadTile(session)
                post && path == "/api/app/tile/delete" -> {
                    val app = app(form(session)["pkg"].orEmpty()) ?: return json(apps().put("ok", false))
                    AppRepo.clearCustomTile(ctx, app.pkg)
                    AppRepo.notifyChanged(ctx, app.pkg)
                    json(apps().put("ok", true))
                }
                post && path == "/api/app/hidden" -> {
                    val f = form(session)
                    val app = app(f["pkg"].orEmpty()) ?: return json(apps().put("ok", false))
                    prefs.hidden = if (f["on"] == "true") prefs.hidden + app.pkg else prefs.hidden - app.pkg
                    AppRepo.notifyChanged(ctx, app.pkg)
                    json(apps().put("ok", true))
                }
                post && path == "/api/app/open" -> json(open(form(session)["pkg"].orEmpty()))
                post && path == "/api/profile/photo" -> uploadProfilePhoto(session)
                post && path == "/api/profile/photo/delete" -> {
                    val p = Profiles.byId(prefs.profileList(), form(session)["id"].orEmpty()) ?: return json(state().put("ok", false))
                    Profiles.clearPhoto(ctx, p.id)
                    Profiles.notifyPhoto(ctx, p.id)
                    json(state().put("ok", true))
                }
                path == "/api/weather/search" -> weatherSearch(session.parameters["q"]?.firstOrNull().orEmpty())
                post && path == "/api/weather" -> {
                    form(session)["on"]?.let { prefs.weather = it == "true" }
                    json(state())
                }
                post && path == "/api/lyrics" -> {
                    // Paroles: the button beside the home screen's player (Réglages → Lecture et notifications).
                    form(session)["on"]?.let { prefs.lyrics = it == "true" }
                    json(state())
                }
                post && path == "/api/weather/place" -> {
                    val f = form(session)
                    val lat = f["lat"]?.toDoubleOrNull()
                    val lon = f["lon"]?.toDoubleOrNull()
                    val name = f["name"].orEmpty().trim().take(80)
                    if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0 && name.isNotEmpty())
                        Weather.setPlace(ctx, Weather.Place(name, lat, lon), auto = false)
                    json(state())
                }
                post && path == "/api/unsplash" -> {
                    // The Unsplash key for the wallpaper source; "" removes it. Checked with one call, never echoed.
                    val key = form(session)["key"].orEmpty().trim()
                    prefs.unsplashKey = key
                    Wallpapers(ctx, Wallpapers.UNSPLASH).forgetList()
                    val ok = if (key.isEmpty()) null else Wallpapers.checkUnsplashKey(key)
                    json(state().put("unsplashOk", ok ?: JSONObject.NULL))
                }
                post && path == "/api/tmdb" -> {
                    // The Top Shelf's TMDB key; "" removes it. The answer says whether TMDB took it, never the key.
                    val key = form(session)["key"].orEmpty().trim()
                    Shelf.setTmdbKey(ctx, key)
                    val ok = if (key.isEmpty()) null else Shelf.checkTmdbKey(key)
                    json(state().put("tmdbOk", ok ?: JSONObject.NULL))
                }
                else -> text(Response.Status.NOT_FOUND, "Not found")
            }
        }.getOrElse { text(Response.Status.INTERNAL_ERROR, it.message ?: "error") }
    }

    private fun page(): String = ctx.assets.open("web/index.html").bufferedReader().use { it.readText() }
        .replace("{{DEVICE}}", Build.MODEL.replace("_", " "))

    private fun webAsset(name: String): Response {
        val bytes = ctx.assets.open("web/$name").use { it.readBytes() }
        return newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(bytes), bytes.size.toLong())
            .apply { addHeader("Cache-Control", "max-age=86400") }
    }

    private fun names(source: String) = JSONArray().apply { Wallpapers(ctx, source).files().forEach { put(it.name) } }

    private fun state(): JSONObject = JSONObject()
        .put("links", prefs.customWallUrls)
        .put("intro", prefs.intro)
        .put("introVideo", Intro.videoFile(ctx)?.length() ?: 0L)
        .put("introSource", prefs.introSource)
        .put("brand", prefs.brand)
        .put("icon", SettingsActivity.iconFile(ctx) != null)
        .put("settingsIcon", prefs.settingsIcon)
        .put("mine", names(Wallpapers.MINE))
        .put("custom", names(Wallpapers.CUSTOM))
        .put("adb", prefs.adbAuthorized)
        .put("front", Foreground.activity != null)
        .put("weather", prefs.weather)
        .put("weatherPlace", prefs.weatherPlace)
        .put("weatherAuto", prefs.weatherAuto)
        .put("lyrics", prefs.lyrics)
        .put("homePlayer", prefs.nowPlaying && prefs.homePlayer)
        .put("tmdbKey", prefs.tmdbKey.isNotEmpty())
        .put("unsplashKey", prefs.unsplashKey.isNotEmpty())
        .put("profiles", profiles())

    /** The profiles for the Réglages tab; `v` changes with the picture, so the phone fetches a new one rather than its cached one. */
    private fun profiles(): JSONArray {
        val current = prefs.currentProfile
        return JSONArray().apply {
            for (p in prefs.profileList()) {
                val photo = Profiles.photoFile(ctx, p.id)
                put(JSONObject()
                    .put("id", p.id)
                    .put("name", Profiles.displayName(p))
                    .put("color", Profiles.colorHex(p.color))
                    .put("photo", photo != null)
                    .put("current", p.id == current)
                    .put("v", "${Profiles.letter(p)}|${p.color}|${Profiles.photoStamp(photo)}".hashCode().toUInt().toString(36)))
            }
        }
    }

    /** A profile's avatar as the TV draws it (photo or initial), 128 px, as PNG: the circle keeps its transparent corners. */
    private fun avatarThumb(id: String): Response {
        val p = Profiles.byId(prefs.profileList(), id) ?: return text(Response.Status.NOT_FOUND, "")
        val bmp = ProfileAvatar.draw(ctx, p, AVATAR_THUMB)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        val bytes = out.toByteArray()
        return newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "max-age=3600")
        }
    }

    /** A profile's photo: any picture up to 20 MB, centre-cropped to a square by [Profiles.setPhoto]; the home screen shows it at once. */
    private fun uploadProfilePhoto(session: IHTTPSession): Response {
        val length = session.headers["content-length"]?.toLongOrNull() ?: -1L
        if (length > MAX_TILE + 64 * 1024) {
            drain(session, length)
            return json(state().put("ok", false).put("message", "Image trop volumineuse : 20 Mo au maximum."))
        }
        val files = HashMap<String, String>()
        session.parseBody(files)
        val p = Profiles.byId(prefs.profileList(), session.parameters["id"]?.firstOrNull().orEmpty())
            ?: return json(state().put("ok", false).put("message", "Ce profil n'existe plus."))
        val f = files.values.map { File(it) }.firstOrNull { it.exists() && it.length() in 1..MAX_TILE }
            ?: return json(state().put("ok", false))
        val ok = Profiles.setPhoto(ctx, p.id, f.readBytes())
        if (ok) Profiles.notifyPhoto(ctx, p.id)
        return json(state().put("ok", ok))
    }

    /**
     * The Apps tab: the home in its order, each item of `kind` app, folder (its shown apps under
     * `apps`, in the folder's order) or input; then the hidden apps by name. `tile` says whether the
     * app can take a picture of the user's (not canvasOS's own screens, inputs nor folders); `v`
     * changes with the tile, so the phone fetches a new thumbnail rather than its cached one.
     */
    private fun apps(): JSONObject {
        val all = repo.allApps()
        val grid = repo.orderedVisible(all)
        known = all.associateBy { it.pkg }
        knownGrid = grid
        val hidden = prefs.hidden
        val collator = java.text.Collator.getInstance(java.util.Locale.FRANCE)
        fun item(a: AppEntry): JSONObject = JSONObject()
            .put("id", a.pkg)
            .put("kind", when { a.folder != null -> "folder"; a.input != null -> "input"; else -> "app" })
            .put("label", a.label)
            .put("originalLabel", a.originalLabel)
            .put("custom", a.tileStamp != 0L)
            .put("hidden", a.pkg in hidden)
            .put("tile", a.folder == null && AppRepo.canHaveCustomTile(ctx, a.pkg))
            .put("v", repo.tileKey(a).hashCode().toUInt().toString(36))
            .apply { a.folder?.let { m -> put("apps", JSONArray().apply { m.forEach { put(item(it)) } }) } }
        val list = grid + all.filter { it.pkg in hidden }.sortedWith { a, b -> collator.compare(a.label, b.label) }
        return JSONObject().put("apps", JSONArray().apply { list.forEach { put(item(it)) } })
    }

    /** The home as last listed (folders with their shown apps), for the folders' thumbnails and « Ouvrir ». */
    @Volatile private var knownGrid: List<AppEntry> = emptyList()
    private val tv by lazy { TvControls(ctx) }

    /** An app or input by key, from the last listing or a fresh one; null for anything else (folders included). */
    private fun app(pkg: String): AppEntry? =
        known[pkg] ?: repo.allApps().also { all -> known = all.associateBy { it.pkg } }.firstOrNull { it.pkg == pkg }

    /** Any grid entry by key, a folder too: what the thumbnails draw. */
    private fun entry(key: String): AppEntry? =
        if (Folders.isFolder(key)) (knownGrid.firstOrNull { it.pkg == key } ?: repo.orderedVisible().firstOrNull { it.pkg == key })
        else app(key)

    /** An entry's tile as the home screen draws it (an app's, a folder's mosaic, an input's), as a 400×225 JPEG. */
    private fun appThumb(pkg: String): Response {
        val app = entry(pkg) ?: return text(Response.Status.NOT_FOUND, "")
        val key = repo.tileKey(app)
        // One tile at a time: the page asks for them all at once, and each is a few hundred kB to draw.
        val bytes = appThumbs.get(key) ?: synchronized(thumbLock) {
            appThumbs.get(key) ?: ByteArrayOutputStream().also {
                repo.tile(app, keep = false).compress(Bitmap.CompressFormat.JPEG, 80, it)
            }.toByteArray().also { appThumbs.put(key, it) }
        }
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "max-age=3600")
        }
    }

    /**
     * « Ouvrir » on the Apps tab: [key] opened on the TV as OK on its tile would. With canvasOS in
     * front, the home screen does it ([HomeActivity.ACTION_OPEN]); otherwise an app goes through local
     * adb or the overlay permission (Android drops activity starts from the background), an input
     * switches directly, and a folder brings the home screen back first.
     */
    private fun open(key: String): JSONObject {
        val listing = apps()
        val grid = knownGrid
        val entry = grid.firstOrNull { it.pkg == key } ?: grid.firstNotNullOfOrNull { f -> f.folder?.firstOrNull { it.pkg == key } }
        val res = when {
            entry == null -> known[key]?.takeIf { it.pkg in prefs.hidden }?.let { fail("« ${it.label} » est masquée sur l'accueil.") }
                ?: fail("Cet élément n'est plus sur l'accueil.")
            inFront() -> if (startHome(key)) ok() else fail("Impossible d'ouvrir ${entry.label}.")
            entry.input != null -> { tv.setInput(entry.input); ok() }
            entry.folder != null -> openFolderFromBackground(key)
            else -> openAppFromBackground(entry)
        }
        for (k in res.keys()) listing.put(k, res.get(k))
        return listing
    }

    private fun ok() = JSONObject().put("ok", true)
    private fun fail(message: String) = JSONObject().put("ok", false).put("message", message)

    /** canvasOS's own process shows a screen: the home screen (or its settings) is what the TV shows. */
    private fun inFront(): Boolean {
        val me = android.os.Process.myPid()
        return ctx.getSystemService(ActivityManager::class.java)?.runningAppProcesses.orEmpty().any {
            it.pid == me && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

    private fun startHome(key: String) = runCatching {
        ctx.startActivity(Intent(ctx, HomeActivity::class.java)
            .setAction(HomeActivity.ACTION_OPEN)
            .putExtra(HomeActivity.EXTRA_KEY, key)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        true
    }.getOrDefault(false)

    /** Through local adb when it can; else a direct start, which Android may drop silently, so the home screen must be seen in front. */
    private fun openFolderFromBackground(key: String): JSONObject {
        if (prefs.adbAuthorized) {
            val home = ComponentName(ctx, HomeActivity::class.java).flattenToShortString()
            val out = LocalAdb.shell(ctx, "am start -n " + PhoneApi.shellQuote(home) + " -a " + HomeActivity.ACTION_OPEN +
                " --es " + HomeActivity.EXTRA_KEY + " " + PhoneApi.shellQuote(key) +
                " -f " + (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            if (out != null && !out.contains("Error")) return ok()
        }
        if (startHome(key)) {
            val until = SystemClock.uptimeMillis() + 1500
            while (SystemClock.uptimeMillis() < until) {
                if (inFront()) return ok()
                Thread.sleep(150)
            }
        }
        return fail("Ouvrez d'abord l'accueil canvasOS.")
    }

    private fun openAppFromBackground(app: AppEntry): JSONObject {
        // The stock launcher (Xiaomi's PatchWall) only opens through the system Home action, as from its tile.
        if (HomeGuardService.isStockHome(app.pkg) && HomeGuardService.openStockLauncher()) return ok()
        val intent = repo.launchIntent(app) ?: return fail("Impossible d'ouvrir ${app.label}.")
        return when {
            prefs.adbAuthorized -> {
                val cmd = StringBuilder("am start -n ").append(PhoneApi.shellQuote((intent.component ?: app.component).flattenToShortString()))
                intent.action?.let { cmd.append(" -a ").append(PhoneApi.shellQuote(it)) }
                intent.categories?.forEach { cmd.append(" -c ").append(PhoneApi.shellQuote(it)) }
                val out = LocalAdb.shell(ctx, cmd.toString())
                when {
                    out == null -> JSONObject().put("ok", false).put("reason", "unreachable")
                    out.contains("Error") -> fail("Impossible d'ouvrir ${app.label}.")
                    else -> ok()
                }
            }
            Settings.canDrawOverlays(ctx) ->
                if (runCatching { ctx.startActivity(intent) }.isSuccess) ok() else fail("Impossible d'ouvrir ${app.label}.")
            else -> JSONObject().put("ok", false).put("reason", "adb")
        }
    }

    /** An app's own tile picture: any picture up to 20 MB, centre-cropped to 16:9 by [AppRepo.setCustomTile]. */
    private fun uploadTile(session: IHTTPSession): Response {
        val length = session.headers["content-length"]?.toLongOrNull() ?: -1L
        if (length > MAX_TILE + 64 * 1024) {
            drain(session, length)
            return json(apps().put("ok", false).put("message", "Image trop volumineuse : 20 Mo au maximum."))
        }
        val files = HashMap<String, String>()
        session.parseBody(files)
        val app = app(session.parameters["pkg"]?.firstOrNull().orEmpty()) ?: return json(apps().put("ok", false))
        val f = files.values.map { File(it) }.firstOrNull { it.exists() && it.length() in 1..MAX_TILE }
            ?: return json(apps().put("ok", false))
        val ok = AppRepo.setCustomTile(ctx, app.pkg, f.readBytes())
        if (ok) AppRepo.notifyChanged(ctx, app.pkg)
        return json(apps().put("ok", ok))
    }

    /** Cities for the phone page's search, looked up by the TV (Open-Meteo's geocoding); ok=false when it cannot reach it. */
    private fun weatherSearch(q: String): Response {
        val query = q.trim().take(80)
        if (query.length < 2) return json(JSONObject().put("ok", true).put("results", JSONArray()))
        val found = Weather.geocode(query) ?: return json(JSONObject().put("ok", false))
        return json(JSONObject().put("ok", true).put("results", JSONArray().apply {
            found.forEach { put(JSONObject().put("name", it.name).put("detail", it.detail).put("lat", it.lat).put("lon", it.lon)) }
        }))
    }

    private fun thumb(rest: String): Response {
        val (source, name) = rest.split('/', limit = 2).takeIf { it.size == 2 } ?: return text(Response.Status.NOT_FOUND, "")
        if (source !in listOf(Wallpapers.MINE, Wallpapers.CUSTOM) || name.contains('/') || !name.endsWith(".jpg"))
            return text(Response.Status.NOT_FOUND, "")
        val f = Wallpapers(ctx, source).files().firstOrNull { it.name == name } ?: return text(Response.Status.NOT_FOUND, "")
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 })
            ?: return text(Response.Status.NOT_FOUND, "")
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bmp.recycle()
        val bytes = out.toByteArray()
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "max-age=3600")
        }
    }

    private fun upload(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val mine = Wallpapers(ctx, Wallpapers.MINE)
        // NanoHTTPD stores each part in a temp file; several photos arrive as photo, photo1, photo2...
        var added = 0
        for (path in files.values) {
            val f = File(path)
            if (f.exists() && f.length() in 1..MAX_UPLOAD && mine.add(f.readBytes()) != null) added++
        }
        if (added > 0) prefs.background = Wallpapers.MINE
        return json(state().put("added", added))
    }

    /** The boot animation: one video, kept in app storage (where [Intro.videoFile] looks first). */
    private fun uploadIntro(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val f = files.values.map { File(it) }.firstOrNull { it.exists() && it.length() in 1..MAX_INTRO }
            ?: return json(state().put("ok", false))
        // Only something Android can play as video; anything else would leave the intro black.
        val video = runCatching {
            MediaMetadataRetriever().run {
                try { setDataSource(f.path); extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" } finally { release() }
            }
        }.getOrDefault(false)
        if (!video) return json(state().put("ok", false))
        val dest = File(ctx.filesDir, Intro.FILE)
        val tmp = File(ctx.filesDir, Intro.FILE + ".part")
        f.copyTo(tmp, overwrite = true)
        // One pushed over adb would otherwise come back once this one is deleted.
        introFiles().forEach { it.delete() }
        tmp.renameTo(dest)
        prefs.introSource = Prefs.INTRO_VIDEO
        return json(state().put("ok", true))
    }

    /**
     * Decodes at the smallest power-of-two reduction still at least [width] wide: a 48 MP phone photo
     * decoded whole needs about 190 MB, more than this app's heap.
     */
    private fun decodeAtMost(f: File, width: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= width) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** The settings icon: any picture, kept as PNG, at most 960 px wide (a tile is drawn at 400 or so). */
    private fun uploadIcon(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val src = files.values.map { File(it) }.firstOrNull { it.exists() && it.length() in 1..MAX_UPLOAD }
            ?.let { decodeAtMost(it, 960) } ?: return json(state().put("ok", false))
        val bmp = if (src.width > 960) Bitmap.createScaledBitmap(src, 960, src.height * 960 / src.width, true) else src
        val tmp = File(ctx.filesDir, SettingsActivity.ICON_FILE + ".part")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        iconFiles().forEach { it.delete() }
        tmp.renameTo(File(ctx.filesDir, SettingsActivity.ICON_FILE))
        prefs.settingsIcon = Prefs.ICON_OWN
        return json(state().put("ok", true))
    }

    /**
     * Presses a key or types text on the TV: [direct] first, which needs nothing while canvasOS is in
     * front ([Foreground]), otherwise [item] through local adb. `via` says which; "adb" means local
     * debugging was never authorized and another app is in front.
     */
    private fun remote(item: String, direct: () -> Boolean): JSONObject = when {
        sendDirect(direct) -> JSONObject().put("ok", true).put("via", "direct")
        !prefs.adbAuthorized -> JSONObject().put("ok", false).put("reason", "adb")
        send(ctx, item) -> JSONObject().put("ok", true).put("via", "adb")
        else -> JSONObject().put("ok", false).put("reason", "unreachable")
    }

    /**
     * Installs the uploaded APK ([Installer]). `pm` needs a file the shell can read: app storage
     * under Android/data is, the cache NanoHTTPD writes uploads to is not, hence the copy below.
     */
    private fun installApk(session: IHTTPSession): Response {
        val length = session.headers["content-length"]?.toLongOrNull() ?: -1L
        if (length > MAX_APK + 64 * 1024) return json(failure("Fichier trop volumineux : 200 Mo au maximum."))
        if (!prefs.adbAuthorized) {
            drain(session, length)
            return json(JSONObject().put("ok", false).put("reason", "adb"))
        }
        val files = HashMap<String, String>()
        session.parseBody(files)
        val part = files.values.map { File(it) }.firstOrNull { it.exists() && it.length() in 1..MAX_APK }
            ?: return json(failure("Aucun fichier reçu."))
        if (!PhoneApi.isApk(part)) return json(failure("Ce fichier n'est pas un APK."))
        val pm = ctx.packageManager
        val info = runCatching { pm.getPackageArchiveInfo(part.path, 0) }.getOrNull()
        // Installing itself would kill this server halfway; and each release is signed differently anyway.
        if (info?.packageName == ctx.packageName)
            return json(failure("C'est canvasOS lui-même : mettez-le à jour dans Réglages de l'accueil → Système → Mise à jour, ou depuis un ordinateur (AGENTS.md, étape 3)."))
        val label = info?.applicationInfo?.let {
            it.sourceDir = part.path
            it.publicSourceDir = part.path
            runCatching { it.loadLabel(pm).toString() }.getOrNull()
        } ?: info?.packageName ?: "l'app"
        val dir = ctx.getExternalFilesDir(null) ?: return json(failure("Le stockage partagé du téléviseur est indisponible."))
        return synchronized(installing) {
            val apk = File(dir, APK_FILE)
            try {
                part.copyTo(apk, overwrite = true)
                Installer.install(ctx, apk).fold(
                    onSuccess = { json(JSONObject().put("ok", true).put("message", "Installée : $label")) },
                    onFailure = { e ->
                        if ((e as? Installer.Failure)?.unreachable == true) json(JSONObject().put("ok", false).put("reason", "unreachable"))
                        else json(failure(e.message.orEmpty()))
                    },
                )
            } finally {
                apk.delete()
            }
        }
    }

    private fun failure(message: String) = JSONObject().put("ok", false).put("message", message)

    /** Reads and drops a body that is turned down, so the phone gets the answer rather than a reset connection. */
    private fun drain(session: IHTTPSession, length: Long) {
        val buf = ByteArray(64 * 1024)
        var left = length
        while (left > 0) {
            val n = session.inputStream.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            left -= n
        }
    }

    private fun iconFiles() = listOfNotNull(File(ctx.filesDir, SettingsActivity.ICON_FILE),
        ctx.getExternalFilesDir(null)?.let { File(it, SettingsActivity.ICON_FILE) })

    private fun introFiles() = listOfNotNull(File(ctx.filesDir, Intro.FILE), ctx.getExternalFilesDir(null)?.let { File(it, Intro.FILE) })

    private fun form(session: IHTTPSession): Map<String, String> {
        session.parseBody(HashMap())
        return session.parameters.mapValues { it.value.firstOrNull().orEmpty() }
    }

    private fun json(o: JSONObject) = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString())

    private fun text(status: Response.Status, msg: String) = newFixedLengthResponse(status, "text/plain; charset=utf-8", msg)

    companion object {
        const val PORT = 8790
        private const val MAX_UPLOAD = 40L * 1024 * 1024
        private const val MAX_INTRO = 100L * 1024 * 1024
        private const val MAX_APK = 200L * 1024 * 1024
        private const val MAX_TILE = 20L * 1024 * 1024
        /** Side of the phone page's avatars, in px: 64 CSS px at a phone's 2x. */
        private const val AVATAR_THUMB = 128
        private const val APK_FILE = "install.apk"

        @Volatile
        private var running: PhoneServer? = null

        /** One install at a time: they share [APK_FILE]. */
        private val installing = Any()

        /**
         * Starts the server unless it is off ([Prefs.phoneServer]) or already listening; cheap to call
         * again. Blocking (binds the socket): call it off the main thread, or use [ensure].
         */
        @Synchronized
        fun start(ctx: Context): Boolean {
            val app = ctx.applicationContext
            if (!Prefs(app).phoneServer) {
                stop()
                return false
            }
            running?.let {
                if (it.isAlive) {
                    Mdns.start(app) // nothing to do unless the address changed
                    return true
                }
                it.stop()
            }
            running = null
            val server = PhoneServer(app)
            return runCatching { server.start(SOCKET_READ_TIMEOUT, true) }.isSuccess.also {
                if (it) running = server
                if (it) Mdns.start(app) else Mdns.stop()
            }
        }

        @Synchronized
        fun stop() {
            running?.stop()
            running = null
            Mdns.stop()
        }

        /** Starts or stops the server to match [Prefs.phoneServer], on a background thread; does nothing when it already does. */
        fun ensure(ctx: Context) {
            val app = ctx.applicationContext
            if (Prefs(app).phoneServer == (running?.isAlive == true)) return
            // start() reads the setting again under its lock, so quick on/off/on switches end on the last one.
            Thread { start(app) }.start()
        }

        /**
         * The page's address, for the QR code and status reports. Re-read each time, since the TV may
         * have moved to another network; null when the page is off or the TV is offline.
         */
        fun url(ctx: Context): String? {
            if (running?.isAlive != true) return null
            val ip = lanAddress(ctx) ?: return null
            return "http://$ip:$PORT/${Prefs(ctx).phoneToken}/"
        }

        /**
         * The same page by name, `http://canvasos.local:8790/<token>/`, answered by [Mdns]: iPhones and
         * recent Android phones resolve it, older Android phones do not, so the QR code keeps [url].
         * Null when the page is off or the name is not being answered.
         */
        fun hostUrl(ctx: Context): String? {
            if (running?.isAlive != true || !Mdns.isRunning) return null
            return "http://${Mdns.HOST}:$PORT/${Prefs(ctx).phoneToken}/"
        }

        /** Why [url] is null, for the screens that show the QR code. */
        fun unavailable(ctx: Context): String = when {
            !Prefs(ctx).phoneServer -> "La page téléphone est désactivée (Réglages de l'accueil → Page téléphone)."
            lanAddress(ctx) == null -> "Le téléviseur n'est pas connecté au réseau. Connectez-le, puis rouvrez cette page."
            else -> "La page téléphone n'a pas pu démarrer : le port $PORT est peut-être pris par une autre app."
        }

        /** A new token: every address handed out so far stops working at once. */
        fun regenerate(ctx: Context) {
            Prefs(ctx).phoneToken = PhoneApi.newToken()
        }

        private val pending = ArrayList<String>()
        private val inputLock = Any()

        @Volatile
        private var lastSent = false

        /**
         * Presses a key straight into canvasOS ([Foreground]), after whatever adb is still sending
         * ([send]) and before the next: one press at a time, in the order the requests got here.
         * False when canvasOS is not in front, or Android would not take it. Blocking.
         */
        private fun sendDirect(direct: () -> Boolean): Boolean = synchronized(inputLock) { direct() }

        /**
         * Sends a keycode or `input text` arguments to the TV in the order the phone pressed them:
         * one `input` call at a time, carrying everything queued while the previous one ran
         * ([PhoneApi.script]). Blocking.
         */
        private fun send(ctx: Context, item: String): Boolean {
            synchronized(pending) { pending += item }
            synchronized(inputLock) {
                val batch = synchronized(pending) { pending.toList().also { pending.clear() } }
                // Already sent by an earlier request, together with its own.
                if (batch.isEmpty()) return lastSent
                lastSent = LocalAdb.shell(ctx, PhoneApi.script(batch), 15_000) != null
                return lastSent
            }
        }

        private fun lanAddress(ctx: Context): String? = Net.lanAddress(ctx)

        /** A QR code, dark modules on white, each module [scale] pixels square. */
        fun qr(text: String, scale: Int = 10): Bitmap {
            val m = QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
            val n = m.width
            val px = IntArray(n * scale * n * scale)
            for (y in 0 until n * scale) for (x in 0 until n * scale)
                px[y * n * scale + x] = if (m[x / scale, y / scale]) Color.BLACK else Color.WHITE
            return Bitmap.createBitmap(px, n * scale, n * scale, Bitmap.Config.ARGB_8888)
        }
    }
}

/** The web app manifest for the phone page: scoped to the token directory so the icon keeps the code. */
internal fun phoneManifest(token: String): JSONObject {
    val root = "/$token/"
    fun icon(size: Int, purpose: String) = JSONObject().put("src", "icon-$size.png").put("sizes", "${size}x$size")
        .put("type", "image/png").put("purpose", purpose)
    return JSONObject()
        .put("name", "canvasOS").put("short_name", "canvasOS").put("lang", "fr")
        .put("id", root).put("start_url", root).put("scope", root)
        .put("display", "standalone").put("orientation", "portrait")
        .put("background_color", "#000000").put("theme_color", "#000000")
        .put("icons", JSONArray().put(icon(192, "any")).put(icon(512, "any")).put(icon(192, "maskable")).put(icon(512, "maskable")))
}
