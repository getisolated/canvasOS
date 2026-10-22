package com.getisolated.canvasos

import android.content.Context

/**
 * What is running on the TV, read through its own adb ([LocalAdb]), so the memory cleanup
 * ([Cleaner.clean]) can tell an app doing something for the user from one merely left open.
 * dumpsys and ps print differently on Android 9 to 14 and across firmwares, so the output is read
 * leniently by the pure functions below: a shape they don't know gives "nothing", never a crash.
 */
object Processes {
    /**
     * Only the lines the parser needs: a TV can run a few hundred services and the whole dump is
     * hundreds of kilobytes over adb. `dumpsys activity services` prints full records (dumpAll).
     */
    private const val SERVICES_CMD = "dumpsys activity services | grep -E 'ServiceRecord|packageName=|isForeground=true'"

    /** A package name: at least two dot-separated parts, letters first. */
    private val PACKAGE = Regex("""[A-Za-z]\w*(?:\.\w+)+""")

    /** "* ServiceRecord{8f3b2a1 u0 com.spotify.tv.android/.SpotifyService}", or with the user after the brace. */
    private val RECORD = Regex("""\bu\d+\s+([A-Za-z]\w*(?:\.\w+)+)/""")

    private val SPACES = Regex("""\s+""")

    /**
     * Packages running a foreground service: a casting receiver, a media server (Plex, Jellyfin),
     * a VPN (Tailscale), Spotify Connect, Home Assistant. Empty without local adb; blocking, at most
     * a few seconds.
     */
    fun foregroundServicePackages(ctx: Context): Set<String> {
        if (!Prefs(ctx).adbAuthorized) return emptySet() // never pop the authorization prompt from here
        return LocalAdb.shell(ctx, SERVICES_CMD, 8_000)?.let(::parseForegroundServices).orEmpty()
    }

    /** Packages with a live process, or null when ps could not be read (then nobody knows). Blocking. */
    fun runningPackages(ctx: Context): Set<String>? {
        if (!Prefs(ctx).adbAuthorized) return null
        return LocalAdb.shell(ctx, "ps -A -o NAME", 5_000)?.let(::parseProcessNames)?.takeIf { it.isNotEmpty() }
    }

    /**
     * Reads `dumpsys activity services`: each record starts with a `ServiceRecord{…}` line naming
     * its component, then `packageName=` and, for a foreground service, `isForeground=true`.
     * Lines from the bindings and other sections are ignored.
     */
    fun parseForegroundServices(dump: String): Set<String> {
        val out = LinkedHashSet<String>()
        var pkg: String? = null
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            when {
                "ServiceRecord{" in line -> pkg = RECORD.find(line)?.groupValues?.get(1)
                line.startsWith("packageName=") ->
                    line.removePrefix("packageName=").trim().takeIf { PACKAGE.matches(it) }?.let { pkg = it }
                line.startsWith("isForeground=true") -> pkg?.let { out += it }
            }
        }
        return out
    }

    /**
     * Reads `ps -A -o NAME`: an app's processes are named after its package, some with a
     * ":suffix" (com.plexapp.mediaserver:service). Daemons and kernel threads don't look like
     * packages and fall out.
     */
    fun parseProcessNames(ps: String): Set<String> = ps.lineSequence()
        .mapNotNull { it.trim().split(SPACES).lastOrNull()?.substringBefore(':') }
        .filterTo(LinkedHashSet()) { PACKAGE.matches(it) }
}
