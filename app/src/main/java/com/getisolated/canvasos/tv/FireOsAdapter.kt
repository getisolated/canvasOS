package com.getisolated.canvasos.tv

import android.content.Context
import android.os.Build

/**
 * Amazon Fire OS (Fire TV sticks and cubes, Fire TV smart TVs). Tested on a Fire OS 8.1.8.5 smart TV
 * (AFTBR92D74, Android 11).
 *
 * - Home: `com.amazon.tv.launcher` and `com.amazon.firehomestarter` are protected; no app can
 *   take the HOME role, and the Home key is handled before any accessibility service sees it
 *   (`amazon.intent.action.HOME_PRESSED` only reaches Amazon's own apps). The guard keeps
 *   canvasOS in front instead, in its Fire OS mode ([com.getisolated.canvasos.FireHome]).
 * - Local adb: Fire OS 7.6.6.9 and 8.1.0.3 stopped apps from connecting to 127.0.0.1:5555
 *   (CVE-2024-27350): the handshake ends without the authorization window. Sleep goes through
 *   the guard's accessibility service there, and Libérer la mémoire keeps to what an app may do.
 * - Settings, Wi-Fi, volume: standard Android. `android.settings.SETTINGS` opens Amazon's settings
 *   through its launcher (`com.amazon.tv.launcher.ui.SettingsActivity`), which the guard lets through.
 */
class FireOsAdapter(ctx: Context) : AndroidAdapter(ctx) {
    override fun matches() = isFireOs()

    companion object {
        const val LAUNCHER = "com.amazon.tv.launcher"

        /** Amazon's own devices report the manufacturer; some Fire TV Edition sets only the AFT model prefix. */
        fun isFireOs() = Build.MANUFACTURER.equals("Amazon", true) || Build.MODEL.startsWith("AFT")
    }
}
