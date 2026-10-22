package com.getisolated.canvasos

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

/**
 * The timer behind the Control Center's "Veille" button: one alarm that puts the TV in standby.
 * [Prefs.sleepAt] is what counts and the alarm only wakes the app up to look at it, so a timer that
 * was cancelled or replaced never fires, even if its alarm was already on its way. Android drops
 * alarms at reboot and so ends the timer; a [Prefs.sleepAt] left in the past is simply ignored.
 */
object SleepTimer {
    const val EXTRA_AT = "at"

    /** How long before the end the home screen, when it is in front, offers to cancel. */
    const val WARN_MS = 60_000L

    /** Time left on the timer, 0 when none is running. */
    fun remainingMs(ctx: Context): Long {
        val at = Prefs(ctx).sleepAt
        return if (at == 0L) 0L else (at - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** Starts the timer, or restarts it from now when one is already running. */
    fun schedule(ctx: Context, minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        Prefs(ctx).sleepAt = at
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, SleepReceiver::class.java).putExtra(EXTRA_AT, at),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Exact, and allowed to run while the TV idles. Android 12 and later may refuse exact alarms
        // (the user can revoke them); then it is a few minutes late at worst, still better than never.
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(ctx: Context) {
        Prefs(ctx).sleepAt = 0L
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, SleepReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        ctx.getSystemService(AlarmManager::class.java).cancel(pi)
        pi.cancel()
    }

    /** The alarm for the timer set to end at [at] went off. */
    fun fire(ctx: Context, at: Long) {
        val prefs = Prefs(ctx)
        if (at == 0L || prefs.sleepAt != at) return
        prefs.sleepAt = 0L
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return
        // Already in standby (the remote's power key, say): nothing to do. Without the local debugging
        // authorization the key cannot be sent, and asking for it in the middle of the night is no help.
        if (!pm.isInteractive || !prefs.adbAuthorized) return
        // The CPU may sleep again as soon as the receiver returns, before the adb connection is made.
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvhome:sleep").acquire(15_000)
        // The user chose this delay in the Veille sheet (ControlCenter.sleepSheet): this is the one
        // place besides the confirmed flows where the sleep key is sent without a prompt of its own.
        Cleaner.sleep(ctx)
    }

    /** "27 min", "1 h", "1 h 30": a duration as it reads in the sheet, rounded up to the minute. */
    fun span(ms: Long): String {
        val minutes = (ms + 59_999L) / 60_000L
        return when {
            minutes < 1 -> "moins d'une minute"
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0L -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}"
        }
    }
}

/** Receives [SleepTimer]'s alarm. Not exported: only the app's own PendingIntent reaches it. */
class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        SleepTimer.fire(c.applicationContext, i.getLongExtra(SleepTimer.EXTRA_AT, 0L))
    }
}
