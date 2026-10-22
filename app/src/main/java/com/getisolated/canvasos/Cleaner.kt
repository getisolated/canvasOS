package com.getisolated.canvasos

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Talks to the TV's own adbd on 127.0.0.1:5555 so a normal app can run
 * shell-privileged commands (force-stop, shutdown). The first connection
 * makes the TV show "允许调试吗？"; after "始终允许" it is silent.
 */
object LocalAdb {
    private const val TAG = "LocalAdb"

    private fun keys(ctx: Context): AdbKeyPair {
        val priv = File(ctx.filesDir, "adbkey")
        val pub = File(ctx.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    /**
     * Runs [cmd] in the adb shell. Returns null when adbd is unreachable or
     * the key is not authorized yet. [waitAuthMs] is how long to wait for the
     * user to accept the authorization prompt.
     */
    fun shell(ctx: Context, cmd: String, waitAuthMs: Int = 4000): String? {
        val prefs = Prefs(ctx)
        return try {
            Dadb.create("127.0.0.1", 5555, keys(ctx), connectTimeout = 3000, socketTimeout = waitAuthMs).use { d ->
                d.shell(cmd).allOutput.also { prefs.adbAuthorized = true }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "adb shell failed: $cmd", e)
            null
        }
    }

    /** Fire-and-forget variant for commands that kill the connection (shutdown). */
    fun shellAsync(ctx: Context, cmd: String) {
        Thread { shell(ctx.applicationContext, cmd, 60_000) }.start()
    }
}

object Cleaner {
    private const val TAG = "canvasOSClean"

    data class Result(
        val beforeMb: Long,
        val afterMb: Long,
        val stopped: Int,
        val viaAdb: Boolean,
        /** Packages that had a process and were force-stopped. */
        val closed: List<String> = emptyList(),
        /** Packages left running because they were in use (in front, playing, a foreground service); [Prefs.keep] is not counted. */
        val spared: List<String> = emptyList(),
    ) {
        val freedMb get() = (afterMb - beforeMb).coerceAtLeast(0)

        val freedText get() = "$freedMb Mo " + if (freedMb > 1) "libérés" else "libéré"

        val closedText get() = when (closed.size) {
            0 -> "aucune app fermée"
            1 -> "1 app fermée"
            else -> "${closed.size} apps fermées"
        }

        /**
         * "312 Mo libérés · 4 apps fermées · 2 en cours d'utilisation épargnées". Without local adb
         * only cached processes could go, and nothing tells which.
         */
        fun summary(sep: String = " · "): String {
            if (!viaAdb) return "$freedText (cache uniquement)"
            val parts = mutableListOf(freedText, closedText)
            if (spared.isNotEmpty()) parts += "${spared.size} en cours d'utilisation " + if (spared.size > 1) "épargnées" else "épargnée"
            return parts.joinToString(sep)
        }
    }

    fun availMb(ctx: Context): Long = memInfo(ctx).availMem / 1024 / 1024
    fun totalMb(ctx: Context): Long = memInfo(ctx).totalMem / 1024 / 1024

    private fun memInfo(ctx: Context) = ActivityManager.MemoryInfo().also {
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
    }

    /**
     * Stops third-party apps except the keep list and those in use: [spare] (the app in front,
     * when the screen is on), apps playing sound or video ([MediaSessions]) and apps running a
     * foreground service ([Processes]): Spotify Connect, a Plex or Jellyfin server, Tailscale, a
     * casting receiver. Then kills cached background processes. Blocking; call off the main thread.
     */
    fun clean(ctx: Context, spare: String? = null): Result {
        val prefs = Prefs(ctx)
        val before = availMb(ctx)
        val keep = prefs.keep + boundBySystem(ctx) + ctx.packageName
        val inUse = HashSet<String>()
        spare?.let { inUse += it }
        inUse += MediaSessions.playingPackages(ctx)
        var stopped = 0
        var viaAdb = false
        var closed = emptyList<String>()
        var spared = emptyList<String>()

        val list = LocalAdb.shell(ctx, "pm list packages -3")
        if (list != null) {
            val candidates = list.lineSequence()
                .map { it.removePrefix("package:").trim() }
                .filter { it.isNotEmpty() && it !in keep }
                .toList()
            inUse += Processes.foregroundServicePackages(ctx)
            val targets = candidates.filter { it !in inUse }
            // Read before stopping anything: force-stopping an app that wasn't running closes nothing.
            val running = Processes.runningPackages(ctx)
            // Joined as a list: with every app spared, a script starting with "; " is a syntax error.
            val script = (targets.map { "am force-stop $it" } + "am kill-all" + "echo done").joinToString("; ")
            if (LocalAdb.shell(ctx, script, 15_000) != null) {
                stopped = targets.size
                viaAdb = true
                closed = if (running == null) targets else targets.filter { it in running }
                spared = candidates.filter { it in inUse }
            }
        }
        if (!viaAdb) {
            // No adb authorization yet: only cached background processes can go.
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (app in ctx.packageManager.getInstalledApplications(0)) {
                if (app.packageName in keep || app.packageName in inUse) continue
                am.killBackgroundProcesses(app.packageName)
            }
        }
        Thread.sleep(1500)
        val r = Result(before, availMb(ctx), stopped, viaAdb, closed, spared)
        Log.i(TAG, "freed ${r.freedMb} MB, adb=$viaAdb, closed $closed, spared (in use) $spared")
        prefs.lastClean = SimpleDateFormat("d MMM HH:mm", Locale.FRANCE).format(Date()) + ", " + r.summary(", ")
        prefs.lastCleanClosed = closed.joinToString(",")
        return r
    }

    /**
     * Third-party apps the system keeps bound for the user: enabled accessibility services (Button
     * Mapper, say; Android switches a force-stopped app's services off for good) and the keyboard
     * in use (Android picks another when it is force-stopped). Like [Prefs.keep], never reported.
     */
    private fun boundBySystem(ctx: Context): Set<String> = runCatching {
        val r = ctx.contentResolver
        val a11y = Settings.Secure.getString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':')
        val ime = Settings.Secure.getString(r, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        (a11y + ime).mapNotNullTo(HashSet()) { ComponentName.unflattenFromString(it.trim())?.packageName }
    }.getOrDefault(emptySet())

    fun foregroundPackage(ctx: Context): String? =
        LocalAdb.shell(ctx, "dumpsys activity activities | grep -m1 topResumedActivity")
            ?.let { Regex("""u0 ([\w.]+)/""").find(it)?.groupValues?.get(1) }

    fun shutdown(ctx: Context) = LocalAdb.shellAsync(ctx, "svc power shutdown")
    fun reboot(ctx: Context) = LocalAdb.shellAsync(ctx, "svc power reboot")
    fun sleep(ctx: Context) = LocalAdb.shellAsync(ctx, "input keyevent KEYCODE_SLEEP")
}
