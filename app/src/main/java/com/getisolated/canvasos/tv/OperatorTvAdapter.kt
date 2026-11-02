package com.getisolated.canvasos.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import com.getisolated.canvasos.HomeGuardService
import com.getisolated.canvasos.LocalAdb
import com.getisolated.canvasos.Prefs

/**
 * Operator boxes whose own launcher is also the live TV player (SFR Connect TV: SDMC DV8945-KFS,
 * Android 12). The channels have no app of their own there, so no tile could open them; they are
 * listed as one input, « TV », for the Control Center's Source and for a tile pinned to the home grid.
 *
 * - Detection: the stock home also answers Android's live channels intent (VIEW on
 *   TvContract.Channels). Google's launchers are left out: there the channels are an app of their own.
 * - Switching: [DIRECT] has the action each known launcher opens its full-screen player with.
 *   Other launchers get the standard intent, which may only bring their home to the front, the
 *   channels a press away: on SFR it does just that. SINGLE_TOP is needed for the launcher, already
 *   running as Home, to get the intent at all; without it Android brings its task to the front and drops it.
 * - The home guard is told first, or it would bring canvasOS back over the stock home at once.
 * - Channel numbers: the operator's player zaps on digits typed on its own remote, so [showChannel]
 *   opens the TV and types them there (SFR shows « Chaîne 15 : LCI » and switches).
 *
 * To open another operator's player directly, find its action (AGENTS.md, section 9.2) and add it to [DIRECT].
 */
class OperatorTvAdapter(ctx: Context) : AndroidAdapter(ctx) {
    private val launcher by lazy { liveTvLauncher() }

    override fun matches() = launcher != null

    override fun inputs() = super.inputs() + listOfNotNull(launcher?.let { tvInput(it) })

    override fun setInput(i: Input) {
        if (!i.uri.startsWith(URI_PREFIX)) return super.setInput(i)
        val pkg = i.uri.removePrefix(URI_PREFIX)
        HomeGuardService.allowStockLauncher()
        start((DIRECT[pkg]?.let { Intent(it) } ?: liveChannels()).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    override fun inputSummary(list: List<Input>) =
        if (list.singleOrNull()?.uri?.startsWith(URI_PREFIX) == true) "Chaînes en direct" else super.inputSummary(list)

    /** The launcher's own banner, which operators draw for their TV (SFR's: a white TV on red). */
    override fun inputBanner(i: Input): Drawable? =
        if (!i.uri.startsWith(URI_PREFIX)) super.inputBanner(i)
        else runCatching { ctx.packageManager.getApplicationBanner(i.uri.removePrefix(URI_PREFIX)) }.getOrNull()

    override fun hasChannels() = launcher != null

    /**
     * Typing into another app takes the TV's own adb ([LocalAdb]): without local debugging the TV
     * only opens. The digits go once the player is in front (it was behind canvasOS) and has settled.
     */
    override fun showChannel(number: String): Boolean {
        val pkg = launcher ?: return false
        if (number.isEmpty() || !number.all { it in '0'..'9' }) return false
        setInput(tvInput(pkg))
        if (!Prefs(ctx).adbAuthorized) return false
        val keys = number.map { "KEYCODE_$it" }.joinToString(" ")
        val script = "for i in \$(seq 1 25); do dumpsys activity activities | grep -m1 mResumedActivity | grep -q $pkg && break; sleep 0.2; done; " +
            "sleep $SETTLE_S; input keyevent $keys"
        return LocalAdb.shell(ctx, script, 10_000) != null
    }

    private fun tvInput(pkg: String) = Input(URI_PREFIX + pkg, "TV", "", true)

    /** The stock home that also plays the channels, if there is one. */
    private fun liveTvLauncher(): String? {
        val pm = ctx.packageManager
        val homes = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet() - ctx.packageName - GOOGLE_LAUNCHERS
        return pm.queryIntentActivities(liveChannels(), PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.firstOrNull { it in homes }
    }

    private fun liveChannels() = Intent(Intent.ACTION_VIEW).setDataAndType(TvContract.Channels.CONTENT_URI, CHANNELS_TYPE)

    companion object {
        /** Followed by the launcher's package; kept in pinned tiles, so keep it stable. */
        private const val URI_PREFIX = "operator-tv://"
        private const val CHANNELS_TYPE = "vnd.android.cursor.dir/channel"
        /** Seconds between the player coming to the front and the digits: before, SFR's ignores them. */
        private const val SETTLE_S = "1"

        /** Launcher package to the action that opens its player in full screen. */
        private val DIRECT = mapOf(
            "com.sfr.androidtv.launcher" to "com.sfr.androidtv.TV",
        )

        private val GOOGLE_LAUNCHERS = setOf(
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher",
        )
    }
}
