package com.getisolated.canvasos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import java.util.concurrent.Executors

/**
 * Keeps the launcher process alive, cleans memory when the TV goes to
 * standby (the remote's power key only turns the screen off) and on a timer,
 * and keeps the phone page ([PhoneServer]) listening, and findable ([Mdns]) when the network changes.
 */
class KeeperService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (prefs.shutdownOnPowerKey && prefs.adbAuthorized) {
                Cleaner.shutdown(applicationContext)
            } else if (prefs.cleanOnStandby) {
                runClean(spareForeground = false)
            }
        }
    }

    /** Xiaomi switches ADB debugging off on wake; with [Prefs.keepAdb] it goes straight back on. */
    private val adbObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = Guard.ensureAdb(this@KeeperService)
    }

    private val periodic = object : Runnable {
        override fun run() {
            runClean(spareForeground = true)
            schedule()
        }
    }

    /**
     * The daily look at GitHub's latest Release ([Updater]): one small request, nothing downloaded.
     * Skipped while the TV is offline, and tried again soon.
     */
    private val updateCheck = object : Runnable {
        override fun run() {
            if (!Net.isConnected(this@KeeperService)) {
                main.postDelayed(this, OFFLINE_RETRY)
                return
            }
            worker.execute {
                Updater.check(applicationContext)
                main.post { scheduleUpdateCheck() }
            }
        }
    }

    /**
     * Any change of network: looked at once things settle ([addressCheck]), so a Wi-Fi blip that
     * comes back with the same address goes unnoticed. Called on a system thread.
     */
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) = settle()
        override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) = settle()
        override fun onLost(n: Network) = settle()

        private fun settle() {
            main.removeCallbacks(addressCheck)
            main.postDelayed(addressCheck, NETWORK_SETTLE)
        }
    }

    /**
     * Keeps [Prefs.lastLanIp] and reopens [Mdns] on the new interface. When the address really
     * changed (not offline: that is no new address) and the phone page is on, a banner says so:
     * phones that kept the old address must use canvasos.local or scan again.
     */
    private val addressCheck = Runnable {
        val ip = Net.lanAddress(this) ?: return@Runnable
        val last = prefs.lastLanIp
        if (ip != last) {
            prefs.lastLanIp = ip
            if (last.isNotEmpty() && prefs.phoneServer) Banners.notice(this, "Page téléphone : nouvelle adresse",
                "Le téléviseur a changé d'adresse ($ip). canvasos.local marche toujours ; " +
                    "sinon rescannez le QR code dans Réglages de l'accueil → Page téléphone.")
        }
        worker.execute { Mdns.restart(applicationContext) }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Accueil en arrière-plan", NotificationManager.IMPORTANCE_MIN))
        startForeground(1, Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_memory)
            .setContentTitle("Accueil")
            .setContentText("Libère la mémoire en veille")
            .build())
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, adbObserver)
        // Reports the current network at once, which records the first address seen.
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network) }
        Guard.ensure(this)
        schedule()
        scheduleUpdateCheck()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESCHEDULE) schedule()
        // Runs right after onCreate too: the phone page listens whenever it is on, and every start
        // (home screen, boot, revival, its own switch) brings it back in line with the setting.
        PhoneServer.ensure(this)
        return START_STICKY
    }

    private fun schedule() {
        main.removeCallbacks(periodic)
        val h = prefs.cleanIntervalHours
        if (h > 0) main.postDelayed(periodic, h * 3_600_000L)
    }

    /** A day after the last attempt ([Prefs.updateCheckedAt]), but not within two minutes of starting: boot has better things to do with the network. */
    private fun scheduleUpdateCheck() {
        main.removeCallbacks(updateCheck)
        val due = prefs.updateCheckedAt + UPDATE_EVERY - System.currentTimeMillis()
        main.postDelayed(updateCheck, due.coerceIn(2 * 60_000L, UPDATE_EVERY))
    }

    private fun runClean(spareForeground: Boolean) {
        val pm = getSystemService(PowerManager::class.java)
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvhome:clean").apply { acquire(30_000) }
        worker.execute {
            try {
                val fg = if (spareForeground) Cleaner.foregroundPackage(this) else null
                Cleaner.clean(this, fg)
            } finally {
                if (wl.isHeld) wl.release()
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        contentResolver.unregisterContentObserver(adbObserver)
        main.removeCallbacks(periodic)
        main.removeCallbacks(updateCheck)
        main.removeCallbacks(addressCheck)
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network) }
        worker.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CH = "keeper"
        const val ACTION_RESCHEDULE = "reschedule"
        private const val UPDATE_EVERY = 24 * 3_600_000L
        private const val OFFLINE_RETRY = 30 * 60_000L
        /** How long the network must stay quiet before its address counts. */
        private const val NETWORK_SETTLE = 5_000L

        fun start(ctx: Context, action: String? = null) {
            ctx.startForegroundService(Intent(ctx, KeeperService::class.java).setAction(action))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        KeeperService.start(c)
        Guard.ensure(c)
        // After a real boot on a firmware that still hands Home to its own launcher: open the home
        // screen, unless the guard is already running and will look at what is in front itself (an
        // HDMI-CEC source may have switched the TV to its input by now). Starting an activity from
        // here needs SYSTEM_ALERT_WINDOW, granted with the guard.
        if (i.action != Intent.ACTION_BOOT_COMPLETED || !Prefs(c).guard || HomeGuardService.isRunning || Guard.isDefaultHome(c)) return
        runCatching { c.startActivity(HomeActivity.bringBack(c)) }
    }
}
