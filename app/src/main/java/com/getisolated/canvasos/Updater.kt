package com.getisolated.canvasos

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Updates canvasOS from its public GitHub Releases (tags `vX.Y.Z`, each with `canvasOS.apk` and
 * `canvasOS-guard.apk`; the build's flavor says which one this install takes). The Releases carry
 * the project's stable signing key, so `pm install -r` replaces the app in place and keeps its
 * settings and grants ([Installer], through local adb).
 *
 * Nothing here downloads on its own: [check] is a single small API call (once a day from
 * [KeeperService], and from Réglages → Système → Mise à jour); [downloadAndInstall] runs only when
 * the user presses « Mise à jour » again. A repo that is private, or a rate limit, answers
 * [Check.Unavailable], which the screens word as « Releases inaccessibles » rather than « à jour ».
 */
object Updater {
    private const val TAG = "canvasOSUpdate"
    private const val REPO = "getisolated/canvasOS"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val FILE = "update.apk"

    /** A canvasOS APK is about 20 MB; this only stops a runaway download from filling the TV's storage. */
    private const val MAX_APK = 150L * 1024 * 1024
    private const val MAX_JSON = 1024 * 1024
    private const val MAX_NOTES = 1200

    /** A newer Release: [version] without the tag's `v`, the APK's [url] and [size] in bytes (0 when GitHub did not say), the Release's [notes]. */
    data class Update(val version: String, val url: String, val size: Long, val notes: String)

    sealed interface Check {
        data class UpToDate(val version: String) : Check
        data class Available(val update: Update) : Check
        /** GitHub could not tell: no connection, a private repo (404), a rate limit (403)… [reason] is French. */
        data class Unavailable(val reason: String) : Check
    }

    /** What [downloadAndInstall] is doing right now ("Téléchargement 43 %…", "Installation…"), or null; read by the settings row. */
    @Volatile
    var working: String? = null
        private set

    private val busy = AtomicBoolean(false)

    /** The asset a flavor installs: the standard build declares HOME, the guard one (TCL) does not. */
    fun assetName(flavor: String) = if (flavor == "guard") "canvasOS-guard.apk" else "canvasOS.apk"

    /** [version] is newer than the [current] one; false when either is not a version. */
    fun isNewer(version: String, current: String): Boolean {
        val v = Version.parse(version) ?: return false
        val c = Version.parse(current) ?: return true
        return v > c
    }

    /**
     * The version a previous check found, if it is still newer than the one running: right after an
     * update [Prefs.updateAvailable] still holds the version that was just installed.
     */
    fun pending(prefs: Prefs): String? =
        prefs.updateAvailable.takeIf { it.isNotEmpty() && isNewer(it, BuildConfig.VERSION_NAME) }

    /**
     * Asks GitHub for the latest Release. Blocking, off the main thread. Records the attempt in
     * [Prefs.updateCheckedAt] whatever the answer, so a failing check is retried the next day and
     * not every minute, and [Prefs.updateAvailable] when it got one (a failure keeps what was known).
     */
    fun check(ctx: Context, timeoutMs: Int = 10_000): Check {
        val prefs = Prefs(ctx)
        prefs.updateCheckedAt = System.currentTimeMillis()
        val r = fetch(timeoutMs)
        when (r) {
            is Check.UpToDate -> prefs.updateAvailable = ""
            is Check.Available -> prefs.updateAvailable = r.update.version
            is Check.Unavailable -> Log.w(TAG, "check failed: ${r.reason}")
        }
        return r
    }

    /**
     * `check_update` for [ConfigReceiver]: the new version, "none" when up to date, or "unavailable".
     * The receiver has about 10 s and the main thread may not touch the network, so the check runs
     * on its own thread with short timeouts and is waited for.
     */
    fun checkForConfig(ctx: Context): String {
        val answer = AtomicReference<Check?>(null)
        val t = Thread { answer.set(check(ctx.applicationContext, 3_000)) }.apply { isDaemon = true; start() }
        t.join(7_000)
        return when (val r = answer.get()) {
            is Check.Available -> r.update.version
            is Check.UpToDate -> "none"
            else -> "unavailable"
        }
    }

    private fun fetch(timeoutMs: Int): Check {
        val c = URL(LATEST_URL).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "canvasOS/${BuildConfig.VERSION_NAME}")
            when (val code = c.responseCode) {
                200 -> {}
                // GitHub answers 404 for a private repo (and for a repo without a Release), whoever asks.
                401, 404 -> return Check.Unavailable("GitHub n'affiche aucune Release publique de canvasOS (dépôt privé, ou pas encore de Release).")
                403, 429 -> return Check.Unavailable(
                    if (c.getHeaderField("X-RateLimit-Remaining") == "0") "GitHub limite les requêtes de ce réseau : réessayez dans une heure."
                    else "GitHub refuse l'accès aux Releases (dépôt privé ?)."
                )
                else -> return Check.Unavailable("GitHub a répondu HTTP $code.")
            }
            val body = c.inputStream.use { readLimited(it, MAX_JSON) } ?: return Check.Unavailable("La réponse de GitHub est trop volumineuse.")
            parse(body, BuildConfig.VERSION_NAME, BuildConfig.FLAVOR)
        } catch (e: Exception) {
            Log.w(TAG, "GitHub unreachable", e)
            Check.Unavailable("GitHub est injoignable : vérifiez la connexion Internet du téléviseur.")
        } finally {
            c.disconnect()
        }
    }

    private fun readLimited(input: java.io.InputStream, max: Int): String? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > max) return null
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    /**
     * Reads a `releases/latest` answer: [Check.UpToDate] unless its tag is newer than [current], in
     * which case the asset for [flavor] is looked up. Pure, so it is unit-tested.
     */
    fun parse(json: String, current: String, flavor: String): Check {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return Check.Unavailable("La réponse de GitHub est illisible.")
        val tag = o.optString("tag_name").trim()
        val latest = Version.parse(tag) ?: return Check.Unavailable("La version « $tag » de la Release est illisible.")
        val have = Version.parse(current)
        if (have != null && latest <= have) return Check.UpToDate(current)
        val name = assetName(flavor)
        val assets = o.optJSONArray("assets")
        val asset = (0 until (assets?.length() ?: 0)).mapNotNull { assets?.optJSONObject(it) }.firstOrNull { it.optString("name") == name }
            ?: return Check.Unavailable("La Release $tag ne contient pas $name.")
        val url = asset.optString("browser_download_url")
        // The link comes from the API, but nothing but TLS is worth installing from.
        if (!url.startsWith("https://")) return Check.Unavailable("Le lien de $name dans la Release $tag est invalide.")
        return Check.Available(Update(latest.text, url, asset.optLong("size", 0L), (if (o.isNull("body")) "" else o.optString("body")).trim().take(MAX_NOTES)))
    }

    /**
     * Downloads [update] into app storage, checks it is canvasOS and newer, installs it and deletes
     * the file. [onStage] hears "Téléchargement 43 %…" then "Installation…" (any thread). Installing
     * replaces the running package, so on success the process usually dies before this returns.
     * Blocking, needs local debugging authorized; one at a time.
     */
    fun downloadAndInstall(ctx: Context, update: Update, onStage: (String) -> Unit): Result<String> {
        val dir = ctx.getExternalFilesDir(null) ?: return Result.failure(Refused("Le stockage de l'application est indisponible."))
        if (!busy.compareAndSet(false, true)) return Result.failure(Refused("Une mise à jour est déjà en cours."))
        val apk = File(dir, FILE)
        return try {
            fun stage(s: String) { working = s; onStage(s) }
            stage("Téléchargement 0 %…")
            try {
                download(update, apk) { stage("Téléchargement $it %…") }
            } catch (e: Exception) {
                Log.w(TAG, "download failed", e)
                return Result.failure(if (e is Refused) e else Refused("Le téléchargement a échoué : ${e.message ?: e.javaClass.simpleName}."))
            }
            verify(ctx, apk)?.let { return Result.failure(Refused(it)) }
            stage("Installation…")
            Installer.install(ctx, apk).recoverCatching { e ->
                // A Release signed with another key than the installed build (an install from before the stable key, or a local build).
                if ((e as? Installer.Failure)?.output?.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") == true)
                    throw Refused("Cette installation de canvasOS n'a pas la signature des Releases : mettez-la à jour une première fois depuis un ordinateur (AGENTS.md, étape 3).")
                throw e
            }
        } finally {
            apk.delete()
            working = null
            busy.set(false)
        }
    }

    /** A refusal whose message is French and ready to show, as opposed to a network error's. */
    private class Refused(message: String) : Exception(message)

    private fun download(update: Update, to: File, onPercent: (Int) -> Unit) {
        if (update.size > MAX_APK) throw Refused("Le fichier de la Release est trop volumineux (${update.size / 1024 / 1024} Mo).")
        val c = URL(update.url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "canvasOS/${BuildConfig.VERSION_NAME}")
            // The asset link redirects (https to https) to GitHub's storage; HttpURLConnection follows it.
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val total = if (update.size > 0) update.size else c.contentLengthLong
            var done = 0L
            var last = -1
            c.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done > MAX_APK) throw Refused("Le fichier téléchargé dépasse ${MAX_APK / 1024 / 1024} Mo.")
                        val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                        if (pct != last) { last = pct; onPercent(pct) }
                    }
                }
            }
            if (update.size > 0 && done != update.size) throw Refused("Le fichier est incomplet ($done octets reçus sur ${update.size}).")
        } finally {
            c.disconnect()
        }
    }

    /** Null when [apk] is a canvasOS newer than the running one; otherwise why not installing it. */
    private fun verify(ctx: Context, apk: File): String? {
        if (!PhoneApi.isApk(apk)) return "Le fichier téléchargé n'est pas un APK."
        val info = runCatching { ctx.packageManager.getPackageArchiveInfo(apk.path, 0) }.getOrNull()
            ?: return "Le fichier téléchargé est illisible."
        if (info.packageName != ctx.packageName) return "Le fichier téléchargé n'est pas canvasOS."
        val name = info.versionName.orEmpty()
        if (!isNewer(name, BuildConfig.VERSION_NAME)) return "Cette Release ne contient pas de version plus récente (APK en $name)."
        return null
    }

    /**
     * A version as tags and `versionName` write it: `1.2.0`, `v1.10`, `2.0.0-rc.1`. Numbers compare as
     * numbers (1.10 > 1.9), a missing part is 0 (1.2 = 1.2.0), and a pre-release is older than its
     * release, as in semver; `+build` metadata is ignored.
     */
    class Version private constructor(val text: String, private val core: List<Int>, private val pre: List<String>) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            for (i in 0 until maxOf(core.size, other.core.size)) {
                val c = core.getOrElse(i) { 0 }.compareTo(other.core.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            if (pre.isEmpty() != other.pre.isEmpty()) return if (pre.isEmpty()) 1 else -1
            for (i in 0 until minOf(pre.size, other.pre.size)) {
                val a = pre[i]
                val b = other.pre[i]
                val an = a.toLongOrNull()
                val bn = b.toLongOrNull()
                val c = when {
                    an != null && bn != null -> an.compareTo(bn)
                    an != null -> -1
                    bn != null -> 1
                    else -> a.compareTo(b)
                }
                if (c != 0) return c
            }
            return pre.size.compareTo(other.pre.size)
        }

        companion object {
            /** The version in [s] ("v1.2.0" gives text "1.2.0"), or null when it is not one. */
            fun parse(s: String?): Version? {
                val text = s?.trim()?.removePrefix("v")?.removePrefix("V")?.substringBefore('+').orEmpty()
                if (text.isEmpty()) return null
                val core = text.substringBefore('-').split('.').map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
                if (core.size > 4) return null
                val pre = if ('-' in text) text.substringAfter('-').split('.') else emptyList()
                if (pre.any { it.isEmpty() }) return null
                return Version(text, core, pre)
            }
        }
    }
}
