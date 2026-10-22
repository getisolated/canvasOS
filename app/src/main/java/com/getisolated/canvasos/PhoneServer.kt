package com.getisolated.canvasos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.os.Build
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
 * Page téléphone): choose the background and the screensaver, send photos, paste picture links,
 * upload the boot animation, use the phone as a remote with a keyboard, and install an APK. Typing
 * a link or picking photos with a remote is slow; with a phone it is not. After Aerial's phone page
 * (github.com/pengchujin/aerial-tv, MIT).
 *
 * [KeeperService] keeps it listening on the local network whenever [Prefs.phoneServer] is on, so a
 * phone that opened it once can come back. Every address carries a random token ([Prefs.phoneToken])
 * shown only in the QR code. It is the only authentication: another device on the network cannot
 * use the page by guessing, and regenerating it locks out every phone that knew the old one. The
 * remote and the APK install go through local adb ([LocalAdb]) and need its authorization.
 */
class PhoneServer private constructor(private val ctx: Context) : NanoHTTPD(PORT) {
    private val prefs = Prefs(ctx)

    override fun serve(session: IHTTPSession): Response {
        // The first path segment is the token, read each time so a new one applies at once.
        val parts = session.uri.removePrefix("/").split('/', limit = 2)
        if (!PhoneApi.sameToken(parts[0], prefs.phoneToken)) return text(Response.Status.NOT_FOUND, "Not found")
        val path = "/" + parts.getOrElse(1) { "" }
        val post = session.method == Method.POST
        return runCatching {
            when {
                path == "/" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page())
                path == "/api/state" -> json(state())
                path.startsWith("/thumb/") -> thumb(path.removePrefix("/thumb/"))
                post && path == "/api/background" -> {
                    val v = form(session)["id"].orEmpty()
                    if (v in Wallpapers.SOURCES || v in Backgrounds.THEMES) {
                        prefs.background = v
                        Wallpapers.trimExcept(ctx, v)
                    }
                    json(state())
                }
                post && path == "/api/screensaver" -> {
                    val v = form(session)["mode"].orEmpty()
                    if (v == Prefs.SAVER_PHOTOS || v == Prefs.SAVER_AERIAL) prefs.saverMode = v
                    json(state())
                }
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
                    if (v == Prefs.INTRO_VIDEO || v == Prefs.INTRO_WORDMARK) prefs.introSource = v
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
                post && path == "/api/backdrop/upload" -> uploadBackdrop(session)
                post && path == "/api/backdrop/delete" -> {
                    session.parseBody(HashMap())
                    backdropFiles().forEach { it.delete() }
                    json(state())
                }
                post && path == "/api/icon/delete" -> {
                    session.parseBody(HashMap())
                    iconFiles().forEach { it.delete() }
                    json(state())
                }
                post && path == "/api/intro/delete" -> {
                    session.parseBody(HashMap())
                    introFiles().forEach { it.delete() }
                    json(state())
                }
                post && path == "/api/key" -> {
                    val code = PhoneApi.keycode(form(session)["key"]) ?: return text(Response.Status.BAD_REQUEST, "Unknown key")
                    json(remote(code))
                }
                post && path == "/api/text" -> {
                    val args = PhoneApi.textArgs(form(session)["text"].orEmpty())
                        ?: return json(JSONObject().put("ok", false).put("reason", "empty"))
                    json(remote(args))
                }
                post && path == "/api/apk/upload" -> installApk(session)
                path == "/api/weather/search" -> weatherSearch(session.parameters["q"]?.firstOrNull().orEmpty())
                post && path == "/api/weather" -> {
                    form(session)["on"]?.let { prefs.weather = it == "true" }
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
                else -> text(Response.Status.NOT_FOUND, "Not found")
            }
        }.getOrElse { text(Response.Status.INTERNAL_ERROR, it.message ?: "error") }
    }

    private fun page(): String = ctx.assets.open("web/index.html").bufferedReader().use { it.readText() }
        .replace("{{DEVICE}}", Build.MODEL.replace("_", " "))

    private fun names(source: String) = JSONArray().apply { Wallpapers(ctx, source).files().forEach { put(it.name) } }

    private fun state(): JSONObject = JSONObject()
        .put("background", prefs.background)
        .put("screensaver", prefs.saverMode)
        .put("links", prefs.customWallUrls)
        .put("sources", JSONArray().apply {
            Wallpapers.SOURCES.forEach { put(JSONObject().put("id", it).put("label", Wallpapers.label(it))) }
        })
        .put("themes", JSONArray().apply {
            Backgrounds.THEMES.forEach { (k, v) -> put(JSONObject().put("id", k).put("label", v.first)) }
        })
        .put("intro", prefs.intro)
        .put("introVideo", Intro.videoFile(ctx)?.length() ?: 0L)
        .put("introSource", prefs.introSource)
        .put("icon", SettingsActivity.iconFile(ctx) != null)
        .put("backdrop", SettingsActivity.backdropFile(ctx) != null)
        .put("mine", names(Wallpapers.MINE))
        .put("custom", names(Wallpapers.CUSTOM))
        .put("adb", prefs.adbAuthorized)
        .put("weather", prefs.weather)
        .put("weatherPlace", prefs.weatherPlace)
        .put("weatherAuto", prefs.weatherAuto)

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
        prefs.ownIcon = true
        return json(state().put("ok", true))
    }

    /** The settings page background: any picture, kept as JPEG at most 1920 px wide. */
    private fun uploadBackdrop(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val src = files.values.map { File(it) }.firstOrNull { it.exists() && it.length() in 1..MAX_UPLOAD }
            ?.let { decodeAtMost(it, 1920) } ?: return json(state().put("ok", false))
        val bmp = if (src.width > 1920) Bitmap.createScaledBitmap(src, 1920, src.height * 1920 / src.width, true) else src
        val tmp = File(ctx.filesDir, SettingsActivity.BACKDROP_FILE + ".part")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        backdropFiles().forEach { it.delete() }
        tmp.renameTo(File(ctx.filesDir, SettingsActivity.BACKDROP_FILE))
        prefs.settingsBg = true
        return json(state().put("ok", true))
    }

    /** Presses a key or types text on the TV through local adb; "adb" means local debugging was never authorized. */
    private fun remote(item: String): JSONObject = when {
        !prefs.adbAuthorized -> JSONObject().put("ok", false).put("reason", "adb")
        send(ctx, item) -> JSONObject().put("ok", true)
        else -> JSONObject().put("ok", false).put("reason", "unreachable")
    }

    /**
     * Installs the uploaded APK with the shell's `pm`, which needs a file the shell can read: app
     * storage under Android/data is, the cache NanoHTTPD writes uploads to is not. The file goes in
     * through stdin (`-S`): handed a path on shared storage, `pm` on Android 9 and later can fail
     * with "Unable to open file", because system_server opens it and may not read there.
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
            return json(failure("C'est canvasOS lui-même : mettez-le à jour depuis un ordinateur (AGENTS.md, étape 3)."))
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
                val out = LocalAdb.shell(ctx, "pm install -r -S ${apk.length()} < ${PhoneApi.shellQuote(apk.absolutePath)}", 180_000)
                when {
                    out == null -> json(JSONObject().put("ok", false).put("reason", "unreachable"))
                    "Success" in out -> json(JSONObject().put("ok", true).put("message", "Installée : $label"))
                    else -> json(failure(listOfNotNull(PhoneApi.installHint(out), out.trim().takeLast(400).ifEmpty { null }).joinToString("\n")))
                }
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

    private fun backdropFiles() = listOfNotNull(File(ctx.filesDir, SettingsActivity.BACKDROP_FILE),
        ctx.getExternalFilesDir(null)?.let { File(it, SettingsActivity.BACKDROP_FILE) })

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
            running?.let { if (it.isAlive) return true else it.stop() }
            running = null
            val server = PhoneServer(app)
            return runCatching { server.start(SOCKET_READ_TIMEOUT, true) }.isSuccess.also { if (it) running = server }
        }

        @Synchronized
        fun stop() {
            running?.stop()
            running = null
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
