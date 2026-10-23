package com.getisolated.canvasos

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Talks to the TV's own adbd on 127.0.0.1:5555 so a normal app can run
 * shell-privileged commands (force-stop, shutdown). The first connection
 * makes the TV show "允许调试吗？"; after "始终允许" it is silent.
 *
 * Once authorized, short commands share one connection, one at a time, so the phone page's
 * remote doesn't pay a handshake per key; it is closed after a minute unused. Long ones (an
 * install, the authorization prompt, shutdown) open their own: the read timeout is fixed per
 * connection, and a long command would hold up the rest.
 */
object LocalAdb {
    private const val TAG = "LocalAdb"
    private const val HOST = "127.0.0.1"
    private const val PORT = 5555
    private const val CONNECT_MS = 3000

    /** Calls allowing up to this long go over the shared connection, whose read timeout it is. */
    private const val SHARED_MAX_MS = 15_000
    private const val IDLE_MS = 60_000L

    private val lock = ReentrantLock()
    /** The shared connection (connected on its first command), or null; guarded by [lock], as is the rest. */
    private var shared: Dadb? = null
    /** [shared] has run a command: failing to open the next may only mean it went stale. */
    private var sharedUsed = false
    private var lastUsed = 0L
    private var idleClose: ScheduledFuture<*>? = null
    private val idle = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "LocalAdbIdle").apply { isDaemon = true } }

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
        // Never authorized: the prompt must be waited for no longer than the caller said.
        val share = waitAuthMs <= SHARED_MAX_MS && Prefs(ctx).adbAuthorized
        val out = if (share) sharedShell(ctx, cmd, waitAuthMs) else freshShell(ctx, cmd, waitAuthMs)
        if (out != null) Prefs(ctx).adbAuthorized = true
        return out
    }

    /** Fire-and-forget variant for commands that kill the connection (shutdown). */
    fun shellAsync(ctx: Context, cmd: String) {
        Thread { shell(ctx.applicationContext, cmd, 60_000) }.start()
    }

    private fun freshShell(ctx: Context, cmd: String, timeoutMs: Int): String? = try {
        Dadb.create(HOST, PORT, keys(ctx), connectTimeout = CONNECT_MS, socketTimeout = timeoutMs).use { d -> d.shell(cmd).allOutput }
    } catch (e: Throwable) {
        Log.w(TAG, "adb shell failed: $cmd", e)
        null
    }

    private fun sharedShell(ctx: Context, cmd: String, timeoutMs: Int): String? {
        // Busy for longer than this caller would wait: its own connection rather than a queue.
        if (!lock.tryLock(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) return freshShell(ctx, cmd, timeoutMs)
        try {
            for (attempt in 0..1) {
                val reused = shared != null && sharedUsed
                val stream = try {
                    val d = shared ?: Dadb.create(HOST, PORT, keys(ctx), connectTimeout = CONNECT_MS, socketTimeout = SHARED_MAX_MS)
                        .also { shared = it; sharedUsed = false }
                    d.openShell(cmd)
                } catch (e: Throwable) {
                    drop()
                    // Kept from earlier and dead since (adbd restarted, ADB switched off at wake): the
                    // command never started, so once more on a new connection.
                    if (reused && attempt == 0) continue
                    Log.w(TAG, "adb shell failed: $cmd", e)
                    return null
                }
                return try {
                    stream.use { it.readAll().allOutput }.also { sharedUsed = true }
                } catch (e: Throwable) {
                    // Timed out or cut halfway: not retried, the command may have run.
                    drop()
                    Log.w(TAG, "adb shell failed: $cmd", e)
                    null
                }
            }
            return null
        } finally {
            lastUsed = SystemClock.elapsedRealtime()
            scheduleIdleClose()
            lock.unlock()
        }
    }

    /** Under [lock]. */
    private fun drop() {
        shared?.let { runCatching { it.close() } }
        shared = null
        sharedUsed = false
    }

    /** Under [lock]: closes the shared connection once it has gone unused for [IDLE_MS]. */
    private fun scheduleIdleClose() {
        idleClose?.cancel(false)
        idleClose = if (shared == null) null else idle.schedule({
            lock.lock()
            try {
                if (SystemClock.elapsedRealtime() - lastUsed >= IDLE_MS - 100) drop()
            } finally {
                lock.unlock()
            }
        }, IDLE_MS, TimeUnit.MILLISECONDS)
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
