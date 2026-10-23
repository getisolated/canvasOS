package com.getisolated.canvasos

import android.content.Context
import android.content.res.AssetFileDescriptor
import java.io.InputStream

/**
 * The two looks shipped in the APK (assets/brand): the canvasOS title card and start-up film, and the
 * Apple TV ones (tvOS). The chosen one supplies the settings icon ([SettingsActivity.iconSource]) and
 * the boot animation ([Intro]) unless the user points either at a picture or a video of their own.
 */
object Brand {
    const val CANVASOS = "canvasos"
    const val TVOS = "tvos"
    val ALL = listOf(CANVASOS, TVOS)

    fun label(brand: String) = if (brand == TVOS) "tvOS" else "canvasOS"

    /** The title card, 16:9 on black, shown as the settings tile and on the settings page. */
    fun iconAsset(brand: String) = if (brand == TVOS) "brand/tvos.jpg" else "brand/canvasos.png"

    /** The start-up film, ending on the logo that [Intro] zooms into. */
    fun videoAsset(brand: String) = if (brand == TVOS) "brand/tvos.mp4" else "brand/canvasos.mp4"

    fun openIcon(ctx: Context, brand: String): InputStream = ctx.assets.open(iconAsset(brand))

    /** The film as a descriptor MediaPlayer can read in place (mp4 is stored uncompressed in the APK). */
    fun openVideo(ctx: Context, brand: String): AssetFileDescriptor = ctx.assets.openFd(videoAsset(brand))
}
