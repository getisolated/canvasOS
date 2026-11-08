package com.getisolated.canvasos

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log

/**
 * Brings the last Bluetooth speaker back after the TV boots or wakes, or Bluetooth comes on.
 *
 * Some firmwares ask a paired speaker to reconnect once, at wake, and give up after about ten
 * seconds (seen on Fire OS 8). A soundbar switched on at the same time can take 20 to 30 s before it
 * accepts a link, so it never comes back on its own. This asks again every [EVERY_MS] for
 * [WINDOW_MS], and stops as soon as an audio device is connected. The speaker to bring back is the
 * last audio device seen connected, kept in canvasOS's own preferences; nothing happens before one
 * has been seen. Started by [KeeperService].
 */
object AudioReconnect {
    private const val TAG = "canvasOSAudio"
    private const val KEY = "audio_reconnect_address"
    private const val EVERY_MS = 15_000L
    private const val WINDOW_MS = 6 * 60_000L

    private val thread by lazy { HandlerThread("audio-reconnect").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }
    @Volatile private var until = 0L

    private fun sp(ctx: Context) = ctx.getSharedPreferences("audio_reconnect", Context.MODE_PRIVATE)

    /** Starts (or extends) a reconnect window. Safe to call from any thread, as often as needed. */
    fun kick(ctx: Context) {
        val app = ctx.applicationContext
        // Boot clock, not wall clock: right after a cold boot the wall clock can be weeks off until it syncs.
        until = SystemClock.elapsedRealtime() + WINDOW_MS
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ tick(app) }, 3_000L)
    }

    private fun tick(ctx: Context) {
        try {
            val st = Bluetooth.state(ctx)
            if (st != Bluetooth.State.READY) return reschedule(ctx)
            val audio = Bluetooth.devices(ctx).filter { it.kind == Bluetooth.Kind.AUDIO }
            val connected = audio.firstOrNull { it.connected }
            if (connected != null) {
                sp(ctx).edit().putString(KEY, connected.address).apply()
                until = 0L
                return
            }
            val target = sp(ctx).getString(KEY, null)?.let { a -> audio.firstOrNull { it.address == a } } ?: return
            Log.i(TAG, "asking ${target.name} to connect: ${Bluetooth.connect(ctx, target)}")
        } catch (e: Throwable) {
            Log.w(TAG, "reconnect failed", e)
        }
        reschedule(ctx)
    }

    private fun reschedule(ctx: Context) {
        if (SystemClock.elapsedRealtime() < until) handler.postDelayed({ tick(ctx) }, EVERY_MS)
    }
}
