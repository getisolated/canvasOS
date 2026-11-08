package com.getisolated.canvasos

import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.getisolated.canvasos.tv.FireOsAdapter

/**
 * The guard's Fire OS mode ([HomeGuardService] in [FireGuardService]). Fire OS sends the Home key
 * to Amazon's home before any accessibility service sees it, and the same home activity
 * (`HomeActivity_vNext`) also opens Fire TV Settings and the Appstore. Bringing canvasOS back the
 * moment it shows, as the guard does elsewhere, closed both. So:
 *
 * 1. Amazon's home comes to the front: a black cover hides it, and nothing happens yet.
 * 2. Every [POLL_MS] for up to [DECIDE_MAX_MS], the launcher's tab bar says what it is showing, by
 *    view id only: `nav-tab-apps_*` is the Appstore (left alone), `nav-tab-movies_and_tv_*` is the
 *    plain home. The Appstore can show the movie tabs first and switch about a second later, so
 *    the plain home counts after it held [HOME_STABLE_MS] and [MIN_DECIDE_MS] went by.
 * 3. A Settings window (Amazon's settings app, or any activity whose class names Settings) stands
 *    the guard down for [SETTINGS_GRACE_MS]: on Fire OS 8 Settings opens through the home first.
 * 4. While the Appstore is open, Home only swaps the launcher's tabs, with no window change; the tab
 *    bar is read every [APPSTORE_POLL_MS] and canvasOS comes back when the movie tabs do.
 * 5. canvasOS is started by AlarmManager: after Home, Fire OS holds an app's own activity starts for
 *    about five seconds, and an activity PendingIntent fired by the system is exempt. A check
 *    [VERIFY_MS] later starts it again if a closing app (Netflix) swallowed the first start.
 *
 * Reading view ids is why [FireGuardService] may retrieve window content; only the launcher's
 * windows are read, and only their view ids. It also takes content-change events, which it
 * ignores: without them Android never refreshes the service's copy of the launcher's views, and
 * the Appstore's tabs went on showing there after Home had replaced them. Ported from the TV Home Watcher of
 * github.com/NikhilSiddhu/firetv-tvos.
 */
class FireHome(private val service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private var cover: View? = null
    private var lastLaunchAt = 0L
    private var lastSettingsAt = -SETTINGS_GRACE_MS
    private var redirectStartedAt = 0L
    private var homeSeenAt = 0L
    private var inAppstore = false
    /** When the Appstore was opened, and whether its tabs have shown since: see [appstorePoll]. */
    private var appstoreAt = 0L
    private var appstoreSeen = false
    private var appstoreHomeAt = 0L
    private var retries = 0
    /** Amazon's home came back inside the Settings grace (Back out of Settings): [graceCheck] looks again. */
    private var homeInGrace = false

    private val graceCheck = Runnable {
        if (!homeInGrace || cover != null || inAppstore) return@Runnable
        homeInGrace = false
        // decide tells the plain home from the Appstore, and waits while the tabs load.
        if (frontPackage() in TAKE_OVER_FROM) {
            Log.i(TAG, "Amazon's home is still in front after Settings; looking at what it shows")
            lastLaunchAt = SystemClock.uptimeMillis()
            startDeciding()
        }
    }

    private val hideCoverLater = Runnable { hideCover() }

    private val decide = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (now - lastSettingsAt < SETTINGS_GRACE_MS) {
                Log.i(TAG, "a TV screen took the home window; leaving it")
                hideCover()
                return
            }
            val view = tabs()
            if (view == Tabs.APPSTORE) {
                Log.i(TAG, "Amazon's home is showing the Appstore; leaving it")
                hideCover()
                watchAppstore(seen = true)
                return
            }
            if (view == Tabs.HOME) { if (homeSeenAt == 0L) homeSeenAt = now } else homeSeenAt = 0L
            val waited = now - redirectStartedAt
            val homeStable = view == Tabs.HOME && now - homeSeenAt >= HOME_STABLE_MS
            if ((homeStable && waited >= MIN_DECIDE_MS) || waited >= DECIDE_MAX_MS) {
                // An app or an HDMI input took the front meanwhile (CEC at wake): leave it.
                val front = frontPackage()
                // Unreadable for a moment after a window change: look again, until the limit.
                if (front == null && waited < DECIDE_MAX_MS) { handler.postDelayed(this, POLL_MS); return }
                if (front != null && front !in TAKE_OVER_FROM) {
                    Log.i(TAG, "$front came to the front; leaving it")
                    hideCover()
                    return
                }
                Log.i(TAG, "${if (view == Tabs.HOME) "plain home" else "undecided"} after $waited ms; bringing canvasOS back")
                launch()
                return
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    /**
     * The Appstore is open in Amazon's home. Opening it shows the movie tabs for up to a couple of
     * seconds before the Appstore's, so the movie tabs mean Home was pressed only once the
     * Appstore's have shown. Ends when another app comes to the front.
     */
    private val appstorePoll = object : Runnable {
        override fun run() {
            if (!inAppstore || cover != null) return
            val now = SystemClock.uptimeMillis()
            if (frontPackage() != FireOsAdapter.LAUNCHER) {
                // The Appstore's own window hands over to the launcher; give it time to.
                if (now - appstoreAt < APPSTORE_OPEN_MS) { handler.postDelayed(this, APPSTORE_POLL_MS); return }
                Log.i(TAG, "the Appstore was left for ${frontPackage()}")
                inAppstore = false
                return
            }
            val view = tabs()
            if (view == Tabs.APPSTORE) appstoreSeen = true
            if (!appstoreSeen && now - appstoreAt > APPSTORE_OPEN_MS) {
                // Never showed the Appstore after all: the home as it is decides.
                inAppstore = false
                startDeciding()
                return
            }
            // The plain home must hold, as in decide: a screen passing through it is not Home.
            if (view == Tabs.HOME && appstoreSeen) { if (appstoreHomeAt == 0L) appstoreHomeAt = now } else appstoreHomeAt = 0L
            if (view == Tabs.HOME && appstoreSeen && now - appstoreHomeAt >= HOME_STABLE_MS && now - lastSettingsAt > SETTINGS_GRACE_MS) {
                Log.i(TAG, "the Appstore gave way to the plain home (Home pressed); bringing canvasOS back")
                inAppstore = false
                lastLaunchAt = SystemClock.uptimeMillis()
                showCover()
                launch()
                return
            }
            handler.postDelayed(this, APPSTORE_POLL_MS)
        }
    }

    private val verify = object : Runnable {
        override fun run() {
            val front = frontPackage()
            if (front == service.packageName) { hideCover(); return }
            if (retries++ < MAX_RETRIES) {
                Log.i(TAG, "canvasOS did not come to the front ($front); again ($retries)")
                showCover()
                schedule()
                handler.postDelayed(this, VERIFY_MS)
            } else {
                Log.w(TAG, "canvasOS still not in front ($front); giving up")
                hideCover()
            }
        }
    }

    /**
     * A window came to the front, with the guard on and the stock launcher not opened on purpose.
     * [homes] are the stock HOME activities ([HomeGuardService.stockHomes]); [activity] is false for
     * dialogs and overlays, canvasOS's own banners among them.
     */
    fun onWindow(pkg: String, cls: String, activity: Boolean, homes: Map<String, Set<String>>) {
        val now = SystemClock.uptimeMillis()
        if (pkg == service.packageName) {
            if (!activity) return
            inAppstore = false
            hideCover()
            return
        }
        // Amazon's Settings only: another app's settings screen (Netflix's) is just that app.
        if (pkg.startsWith("com.amazon.tv.settings") || (pkg == FireOsAdapter.LAUNCHER && cls.contains("Settings"))) {
            if (now - lastSettingsAt > SETTINGS_GRACE_MS) Log.i(TAG, "$pkg/$cls in front; standing down")
            lastSettingsAt = now
            homeInGrace = false
            handler.removeCallbacks(decide)
            handler.removeCallbacks(verify)
            hideCover()
            return
        }
        // The Appstore's own activity only hands over to Amazon's home ([appstorePoll]).
        if (pkg == APPSTORE) {
            Log.i(TAG, "the Appstore is opening; leaving Amazon's home to it")
            handler.removeCallbacks(decide)
            handler.removeCallbacks(verify)
            hideCover()
            watchAppstore(seen = false)
            return
        }
        if (cls !in homes[pkg].orEmpty()) {
            // Another app or an input came to the front: whatever was being decided is over.
            if (activity) {
                homeInGrace = false
                handler.removeCallbacks(decide)
                handler.removeCallbacks(verify)
                hideCover()
            }
            return
        }
        if (inAppstore) return // the Appstore's handover, or Home within it: appstorePoll decides
        if (now - lastSettingsAt < SETTINGS_GRACE_MS) {
            homeInGrace = true
            handler.removeCallbacks(graceCheck)
            handler.postDelayed(graceCheck, lastSettingsAt + SETTINGS_GRACE_MS + POLL_MS - now)
            return
        }
        if (now - lastLaunchAt < DEBOUNCE_MS || cover != null) return
        lastLaunchAt = now
        Log.i(TAG, "Amazon's home came to the front; looking at what it shows")
        startDeciding()
    }

    /** Waking, or bound again after boot: the stock home may be in front with no window change. */
    fun takeOver(reason: String) {
        if (cover != null || frontPackage() !in TAKE_OVER_FROM) return
        Log.i(TAG, "$reason with the stock launcher in front; looking at what it shows")
        lastLaunchAt = SystemClock.uptimeMillis()
        startDeciding()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        hideCover()
    }

    private fun watchAppstore(seen: Boolean) {
        inAppstore = true
        appstoreAt = SystemClock.uptimeMillis()
        appstoreSeen = seen
        appstoreHomeAt = 0L
        handler.removeCallbacks(appstorePoll)
        handler.postDelayed(appstorePoll, APPSTORE_POLL_MS)
    }

    private fun startDeciding() {
        // decide watches the Appstore again if that is what it finds.
        inAppstore = false
        handler.removeCallbacks(appstorePoll)
        showCover()
        handler.removeCallbacks(decide)
        redirectStartedAt = SystemClock.uptimeMillis()
        homeSeenAt = 0L
        handler.postDelayed(decide, POLL_MS)
    }

    private fun launch() {
        retries = 0
        schedule()
        handler.removeCallbacks(verify)
        handler.postDelayed(verify, VERIFY_MS)
    }

    /** Fired by the system, so exempt from the app-switch hold that follows Home (see the class comment). */
    private fun schedule() {
        val intent = HomeActivity.bringBack(service)
        runCatching {
            val am = service.getSystemService(AlarmManager::class.java)!!
            val pi = PendingIntent.getActivity(service, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis(), pi) }
                .onFailure { am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis(), pi) } // no exact alarms (Android 12+)
        }.onFailure {
            Log.w(TAG, "alarm refused; starting the home screen directly", it)
            runCatching { service.startActivity(intent) }
        }
    }

    /** The app on top, whatever overlay (canvasOS's banners, the cover) is over it. */
    private fun frontPackage(): String? = runCatching {
        service.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .maxByOrNull { it.layer }?.root?.packageName?.toString()
    }.getOrNull()

    private enum class Tabs { UNKNOWN, HOME, APPSTORE }

    /** Which tabs the launcher's windows show. */
    private fun tabs(): Tabs {
        var result = Tabs.UNKNOWN
        runCatching {
            for (w in service.windows) {
                val root = w.root ?: continue
                if (root.packageName?.toString() != FireOsAdapter.LAUNCHER) continue
                when (scan(root, 0)) {
                    Tabs.APPSTORE -> return Tabs.APPSTORE
                    Tabs.HOME -> result = Tabs.HOME
                    Tabs.UNKNOWN -> Unit
                }
            }
        }.onFailure { Log.w(TAG, "could not read the launcher's tabs", it) }
        return result
    }

    private fun scan(n: AccessibilityNodeInfo?, depth: Int): Tabs {
        if (n == null || depth > 40) return Tabs.UNKNOWN
        n.viewIdResourceName?.let { id ->
            if (id.startsWith("nav-tab-apps_")) return Tabs.APPSTORE
            if (id.startsWith("nav-tab-movies_and_tv_")) return Tabs.HOME
        }
        var found = Tabs.UNKNOWN
        for (i in 0 until n.childCount) {
            when (scan(n.getChild(i), depth + 1)) {
                Tabs.APPSTORE -> return Tabs.APPSTORE
                Tabs.HOME -> found = Tabs.HOME
                Tabs.UNKNOWN -> Unit
            }
        }
        return found
    }

    private fun showCover() {
        if (cover != null) return
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val v = View(service).apply { setBackgroundColor(Color.BLACK) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { wm.addView(v, lp); cover = v }.onFailure { Log.w(TAG, "no cover", it) }
        // Never left up, whatever else is missed.
        handler.removeCallbacks(hideCoverLater)
        handler.postDelayed(hideCoverLater, COVER_MAX_MS)
    }

    private fun hideCover() {
        handler.removeCallbacks(hideCoverLater)
        handler.removeCallbacks(verify)
        val v = cover ?: return
        cover = null
        runCatching { (service.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) }
    }

    companion object {
        private const val TAG = "canvasOSGuard"
        private const val POLL_MS = 150L
        private const val DECIDE_MAX_MS = 2500L
        private const val MIN_DECIDE_MS = 1200L
        private const val HOME_STABLE_MS = 500L
        private const val APPSTORE = "com.amazon.venezia"
        /** The stock homes, by the app on top: the guard's own record may be older than a wake. */
        private val TAKE_OVER_FROM = setOf(FireOsAdapter.LAUNCHER, "com.amazon.firehomestarter")
        private const val APPSTORE_POLL_MS = 500L
        /** How long the Appstore's tabs may take to show after it was opened. */
        private const val APPSTORE_OPEN_MS = 6000L
        private const val SETTINGS_GRACE_MS = 4000L
        private const val DEBOUNCE_MS = 600L
        private const val VERIFY_MS = 900L
        private const val MAX_RETRIES = 3
        private const val COVER_MAX_MS = 6000L
    }
}
