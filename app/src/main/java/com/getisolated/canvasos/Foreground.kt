package com.getisolated.canvasos

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The canvasOS screen in front, if any, and the phone page's remote pressing keys into it without
 * local adb ([PhoneServer]). Every Activity sets itself here in onResume and clears itself in onPause.
 *
 * An app may inject input into its own windows (Android checks the focused window's uid, as for
 * Espresso), so with canvasOS in front a key goes through the whole input pipeline like a real
 * remote's: focus moves, views time their own long presses, an open keyboard sees it first. When
 * Android refuses (another window took focus meanwhile), the key is handed to the activity instead.
 * Blocking; never called on the main thread.
 */
object Foreground {
    private const val TAG = "canvasOSRemote"

    @Volatile
    var activity: Activity? = null
        private set

    fun resumed(a: Activity) = synchronized(this) { activity = a }

    /** Only if [a] is still the one set: the next screen's onResume may already have run. */
    fun paused(a: Activity) = synchronized(this) { if (activity === a) activity = null }

    private val main = Handler(Looper.getMainLooper())
    private val instrumentation by lazy { Instrumentation() }

    /**
     * Presses [plan] without adb: anything into canvasOS when it is in front. With another app in
     * front, only when there is no [adb] to press it as a real key: the volume, which any app may
     * change, and Home, when Android lets canvasOS start its home screen from the background.
     * False when it must go through adb instead.
     */
    fun press(ctx: Context, plan: PhoneApi.Direct, adb: Boolean): Boolean {
        val a = activity
        if (a == null && adb) return false
        return when (plan) {
            is PhoneApi.Direct.Volume -> volume(ctx, plan.step)
            PhoneApi.Direct.Home -> (a != null || canStartFromBack(ctx)) && home(a ?: ctx.applicationContext)
            is PhoneApi.Direct.Key -> a != null && key(a, plan)
        }
    }

    /** Types [text] (already [PhoneApi.typeable]) into canvasOS; false when not in front or a character has no key. */
    fun type(text: String): Boolean {
        val a = activity ?: return false
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray()) ?: return false
        val now = SystemClock.uptimeMillis()
        val inject = InjectOrDispatch(a)
        // getEvents leaves the times at 0; each press gets its own.
        for (e in events) if (!inject(KeyEvent.changeTimeRepeat(e, now, 0).also { it.source = InputDevice.SOURCE_KEYBOARD })) return false
        return true
    }

    private fun key(a: Activity, plan: PhoneApi.Direct.Key): Boolean {
        val code = KeyEvent.keyCodeFromString(plan.keycode)
        if (code == KeyEvent.KEYCODE_UNKNOWN) return false
        val inject = InjectOrDispatch(a)
        val down = SystemClock.uptimeMillis()
        for (s in PhoneApi.strokes(plan.long, ViewConfiguration.getLongPressTimeout())) {
            if (s.waitMs > 0) SystemClock.sleep(s.waitMs)
            val flags = if (s.longPress) KeyEvent.FLAG_LONG_PRESS else 0
            val e = KeyEvent(
                down, SystemClock.uptimeMillis(), if (s.down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                code, s.repeat, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD,
            )
            if (!inject(e)) return false
        }
        return true
    }

    /**
     * One press's events: injected while Android allows it; after a refusal the rest go to the
     * activity's dispatchKeyEvent on the main thread, so a down and its up take the same way.
     */
    private class InjectOrDispatch(private val a: Activity) {
        private var injecting = true

        operator fun invoke(e: KeyEvent): Boolean {
            if (injecting) {
                try {
                    instrumentation.sendKeySync(e)
                    return true
                } catch (t: Throwable) {
                    Log.i(TAG, "injection refused, dispatching to the activity: $t")
                    injecting = false
                }
            }
            return onMain(false) {
                if (activity !== a || a.isFinishing) return@onMain false
                if (!a.dispatchKeyEvent(e)) navigate(a, e)
                true
            }
        }
    }

    /** What the view root does with an arrow nobody handled, which dispatchKeyEvent alone skips. */
    private fun navigate(a: Activity, e: KeyEvent) {
        if (e.action != KeyEvent.ACTION_DOWN) return
        val dir = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            else -> return
        }
        val focused = a.currentFocus
        if (focused == null) { a.window.decorView.restoreDefaultFocus(); return }
        focused.focusSearch(dir)?.takeIf { it !== focused }?.requestFocus(dir)
    }

    /** As the Home key: canvasOS's home screen, back to its top when it is already there (HomeActivity.onNewIntent). */
    private fun home(ctx: Context): Boolean = onMain(false) {
        ctx.startActivity(
            Intent(ctx, HomeActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
        true
    }

    /** From Android 10 on, an app in the background may start an activity only with the overlay permission (AGENTS.md, step 4). */
    private fun canStartFromBack(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(ctx)

    /** Volume as the remote's keys change it, with the TV's own volume bar; any app may. */
    private fun volume(ctx: Context, step: PhoneApi.VolumeStep): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val dir = when (step) {
            PhoneApi.VolumeStep.RAISE -> AudioManager.ADJUST_RAISE
            PhoneApi.VolumeStep.LOWER -> AudioManager.ADJUST_LOWER
            PhoneApi.VolumeStep.MUTE -> AudioManager.ADJUST_TOGGLE_MUTE
        }
        return runCatching { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI) }.isSuccess
    }

    /** Runs [block] on the main thread and waits for it, [fallback] if it fails or takes over two seconds. */
    private fun <T> onMain(fallback: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return runCatching(block).getOrDefault(fallback)
        var result = fallback
        val done = CountDownLatch(1)
        main.post {
            try { result = block() } catch (t: Throwable) { Log.w(TAG, "remote key failed", t) } finally { done.countDown() }
        }
        return if (done.await(2, TimeUnit.SECONDS)) result else fallback
    }
}
