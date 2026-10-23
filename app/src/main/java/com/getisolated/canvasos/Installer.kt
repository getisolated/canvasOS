package com.getisolated.canvasos

import android.content.Context
import java.io.File

/**
 * Installs an APK with the shell's `pm`, through local adb ([LocalAdb]); shared by the phone page's
 * APK upload ([PhoneServer]) and canvasOS's own update ([Updater]). Needs local debugging to be
 * authorized ([Prefs.adbAuthorized]); callers check that first, to word their own "not authorized"
 * message.
 *
 * `pm` needs a file the shell can read: app storage under Android/data is, the cache is not. The
 * file goes in through stdin (`-S`): handed a path on shared storage, `pm` on Android 9 and later
 * can fail with "Unable to open file", because system_server opens it and may not read there.
 * Installing canvasOS itself replaces the running package, which kills this process mid-command:
 * no result comes back then, and [BootReceiver] brings the new version up (MY_PACKAGE_REPLACED).
 */
object Installer {
    private const val TIMEOUT_MS = 180_000

    /**
     * Why an install failed; [message] is French and ready to show. [unreachable]: local adb did not
     * answer (never authorized, or adbd is off). [output] is what `pm` printed, for the caller that
     * wants to recognize a failure ("INSTALL_FAILED_UPDATE_INCOMPATIBLE").
     */
    class Failure(message: String, val unreachable: Boolean = false, val output: String = "") : Exception(message)

    /** Installs [apk] over the installed version (`-r`); the success value is what `pm` printed ("Success"). Blocking, up to 180 s. */
    fun install(ctx: Context, apk: File): Result<String> = synchronized(this) {
        if (!apk.isFile || apk.length() == 0L) return Result.failure(Failure("Le fichier à installer est introuvable."))
        val src = PhoneApi.shellQuote(apk.absolutePath)
        // The shell copies the APK to its own /data/local/tmp and installs from there: system_server
        // cannot open the app's files, and streaming the bytes through `pm install -S` breaks on some
        // firmwares ("Failure calling service package: Failed transaction", Google TV 14). Streaming
        // stays as the fallback, for a /data too full for the copy.
        val tmp = "/data/local/tmp/canvasos-install.apk"
        var out = LocalAdb.shell(ctx, "cp $src $tmp && pm install -r $tmp; rm -f $tmp", TIMEOUT_MS)
        if (out != null && "Success" !in out) {
            out = LocalAdb.shell(ctx, "pm install -r -S ${apk.length()} < $src", TIMEOUT_MS) ?: out
        }
        when {
            out == null -> Result.failure(Failure("Le débogage local ne répond pas : autorisez-le dans Réglages de l'accueil → Système.", unreachable = true))
            "Success" in out -> Result.success(out.trim())
            else -> Result.failure(Failure(
                listOfNotNull(PhoneApi.installHint(out), out.trim().takeLast(400).ifEmpty { null }).joinToString("\n"),
                output = out,
            ))
        }
    }
}
