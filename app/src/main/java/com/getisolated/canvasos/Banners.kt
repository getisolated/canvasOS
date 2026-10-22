package com.getisolated.canvasos

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.lang.ref.WeakReference

/**
 * tvOS-style notification banners, top right, for the apps the user picked ([Prefs.bannerApps]:
 * a doorbell, home automation) and no other. Off by default ([Prefs.banners]).
 *
 * [RevivalListener] hands over every posted notification and this returns at once unless banners
 * are on and the app is on the list. For those apps it reads the app's name and icon and the
 * notification's title and text, shows them for 6 seconds, and forgets them: the banner on screen
 * is the only copy, nothing is logged or written anywhere, and a newer banner replaces it (no queue).
 *
 * Where: inside the home screen when it is in front ([attach]), else over whatever is being watched
 * through an overlay window when canvasOS may draw over apps (SYSTEM_ALERT_WINDOW, granted with the
 * guard, AGENTS.md step 4). With neither, the notification is dropped. Plain views, no blur: a
 * banner must not stutter a video on a low-end TV.
 */
object Banners {
    private const val TAG = "canvasOSBanners"
    private const val SHOW_MS = 6_000L
    private const val WIDTH_DP = 420
    private const val MARGIN_END_DP = 32
    private const val MARGIN_TOP_DP = 26

    /** The template notifications from a media session carry; their app's own controls, not news. */
    const val MEDIA_TEMPLATE = "android.app.Notification\$MediaStyle"

    /**
     * Whether a notification becomes a banner: an app on the list, something that happened (not a
     * running service or a playing track, not the summary of a group whose children come as well),
     * with a title or a text to show.
     */
    fun accept(
        pkg: String,
        isOngoing: Boolean,
        isGroupSummary: Boolean,
        template: String?,
        title: CharSequence?,
        text: CharSequence?,
        allow: Set<String>,
    ): Boolean = pkg in allow && !isOngoing && !isGroupSummary && !isMedia(template) &&
        !(title.isNullOrBlank() && text.isNullOrBlank())

    /** Notification.MediaStyle, DecoratedMediaCustomViewStyle, and AndroidX's, which reports the platform's. */
    private fun isMedia(template: String?) =
        template != null && (template.endsWith("MediaStyle") || template.endsWith("MediaCustomViewStyle"))

    /** Whether the notification listener is approved: without it no notification reaches [onPosted]. */
    fun listenerGranted(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(ctx, RevivalListener::class.java))
    }.getOrDefault(false)

    // ---- what is on screen: one banner at most ----

    private class Up(val card: View, val root: View, val wm: WindowManager?, val tween: Tween)

    private var up: Up? = null
    private var home: WeakReference<FrameLayout>? = null
    private var windowCtx: Context? = null
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val hide = Runnable { dismiss() }

    /** The home screen is in front: banners show inside [root], above the Control Center and sheets. */
    fun attach(root: FrameLayout) { home = WeakReference(root) }

    /** The home screen left the front: banners go to the overlay window, where allowed. */
    fun detach(root: FrameLayout) { if (home?.get() === root) home = null }

    /** From [RevivalListener.onNotificationPosted], on the main thread. */
    fun onPosted(ctx: Context, sbn: StatusBarNotification) {
        val prefs = Prefs(ctx)
        if (!prefs.banners) return
        val allow = prefs.bannerApps
        val pkg = sbn.packageName ?: return
        if (pkg !in allow) return
        val n = sbn.notification ?: return
        val x = n.extras ?: return
        val ongoing = sbn.isOngoing || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
        val summary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
        val template = if (x.containsKey(Notification.EXTRA_MEDIA_SESSION)) MEDIA_TEMPLATE else x.getString(Notification.EXTRA_TEMPLATE)
        val title = x.getCharSequence(Notification.EXTRA_TITLE)
        val text = x.getCharSequence(Notification.EXTRA_TEXT)
        if (!accept(pkg, ongoing, summary, template, title, text, allow)) return
        val app = ctx.applicationContext
        // In standby there is no one to read it, and it must not wait for the wake-up.
        if (app.getSystemService(PowerManager::class.java)?.isInteractive == false) return
        val pm = app.packageManager
        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        // The small icon is a square silhouette made for this; the app icon of a TV app is often its wide banner.
        val small = runCatching { n.smallIcon?.loadDrawable(app) }.getOrNull()
        val icon = small ?: runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
        val accent = n.color.takeIf { Color.alpha(it) >= 0x80 }?.let { it or 0xFF000000.toInt() } ?: Color.rgb(72, 72, 78)
        show(app, label, icon, small != null, accent,
            title?.toString()?.trim().orEmpty().ifEmpty { label }, text?.toString()?.trim().orEmpty())
    }

    private fun show(ctx: Context, label: String, icon: Drawable?, silhouette: Boolean, accent: Int, title: String, text: String) {
        removeNow()
        handler.removeCallbacks(hide)
        val host = home?.get()?.takeIf { it.isAttachedToWindow && it.windowToken != null }
        val shown = try {
            when {
                host != null -> inHome(host, card(host.context, label, icon, silhouette, accent, title, text))
                Settings.canDrawOverlays(ctx) -> inWindow(ctx, label, icon, silhouette, accent, title, text)
                else -> null // nowhere to show it; it is not kept for later either
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not show a banner", e) // the content is not logged
            null
        } ?: return
        up = shown
        val from = shown.card.dp(WIDTH_DP + MARGIN_END_DP).toFloat()
        shown.card.translationX = from
        shown.tween.start(380, Motion.ease) { f -> shown.card.translationX = lerp(from, 0f, f) }
        handler.postDelayed(hide, SHOW_MS)
    }

    private fun inHome(host: FrameLayout, card: View): Up {
        // Above the Control Center (101 dp) and sheets (200 dp), below the boot animation (400 dp).
        // No outline: raised this high, Android would draw a large shadow under it every frame.
        card.translationZ = host.dp(300).toFloat()
        card.outlineProvider = null
        host.addView(card, FrameLayout.LayoutParams(host.dp(WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = host.dp(MARGIN_TOP_DP); marginEnd = host.dp(MARGIN_END_DP)
        })
        return Up(card, card, null, Tween())
    }

    /**
     * A window over the app in front that takes no focus and no touch: the remote keeps working the
     * app being watched. It reaches the right edge of the screen, so the banner slides in from there.
     */
    private fun inWindow(ctx: Context, label: String, icon: Drawable?, silhouette: Boolean, accent: Int, title: String, text: String): Up {
        val wctx = windowContext(ctx)
        val wm = wctx.getSystemService(WindowManager::class.java)
        val card = card(wctx, label, icon, silhouette, accent, title, text)
        val root = FrameLayout(wctx).apply {
            clipChildren = true
            setPadding(0, ctx.dp(MARGIN_TOP_DP), ctx.dp(MARGIN_END_DP), ctx.dp(8))
            addView(card, FrameLayout.LayoutParams(ctx.dp(WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val lp = WindowManager.LayoutParams(
            ctx.dp(WIDTH_DP + MARGIN_END_DP), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            setTitle("canvasOS banner")
        }
        wm.addView(root, lp)
        return Up(card, root, wm, Tween())
    }

    /** Android 11 and later want a window context for windows added outside an activity. */
    private fun windowContext(ctx: Context): Context {
        windowCtx?.let { return it }
        val w = if (Build.VERSION.SDK_INT >= 30) runCatching {
            val display = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ctx.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        }.getOrNull() else null
        return (w ?: ctx).also { windowCtx = it }
    }

    /** Slides the banner back out to the right, then removes it and what it said. */
    private fun dismiss() {
        val u = up ?: return
        val to = u.card.dp(WIDTH_DP + MARGIN_END_DP).toFloat()
        val from = u.card.translationX
        u.tween.start(260, Motion.ease, end = { if (up === u) removeNow() }) { f -> u.card.translationX = lerp(from, to, f) }
    }

    private fun removeNow() {
        val u = up ?: return
        up = null
        u.tween.cancel()
        runCatching {
            if (u.wm != null) u.wm.removeViewImmediate(u.root) else (u.root.parent as? ViewGroup)?.removeView(u.root)
        }
    }

    /** Dark rounded card with a thin light rim, like the Control Center's panel: icon, bold title, text. */
    private fun card(ctx: Context, label: String, icon: Drawable?, silhouette: Boolean, accent: Int, title: String, text: String): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(238, 30, 30, 34))
                cornerRadius = ctx.dp(22).toFloat()
                setStroke(ctx.dp(1), Color.argb(60, 255, 255, 255))
            }
            setPadding(ctx.dp(14), ctx.dp(13), ctx.dp(20), ctx.dp(13))
            contentDescription = "$label : $title"
            isFocusable = false
        }
        val badge = FrameLayout(ctx).apply { clipRounded(ctx.dp(11).toFloat()) }
        val img = ImageView(ctx).apply { setImageDrawable(icon) }
        if (silhouette || icon == null) {
            // A notification's small icon is drawn white, on the app's colour.
            badge.background = rounded(accent, ctx.dp(11).toFloat())
            img.setColorFilter(Color.WHITE)
            if (icon == null) img.setImageResource(R.drawable.ic_apps)
            badge.addView(img, FrameLayout.LayoutParams(ctx.dp(24), ctx.dp(24), Gravity.CENTER))
        } else {
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            badge.addView(img, FrameLayout.LayoutParams(-1, -1))
        }
        card.addView(badge, LinearLayout.LayoutParams(ctx.dp(42), ctx.dp(42)).apply { marginEnd = ctx.dp(14) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ctx.text(15f, Color.WHITE, weight = 650).apply {
            this.text = title
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (text.isNotEmpty()) col.addView(ctx.text(13.5f, Color.argb(205, 255, 255, 255)).apply {
            this.text = text
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.15f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(3) })
        card.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        return card
    }
}
