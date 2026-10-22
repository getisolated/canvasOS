package com.getisolated.canvasos

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address

/**
 * What the TV knows about its own network, for the screens that tell the user (or the computer that
 * installs canvasOS, AGENTS.md steps 0 and 1) where to find it. Quick binder reads, safe on the UI thread.
 */
object Net {
    private fun caps(ctx: Context): NetworkCapabilities? = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        cm.getNetworkCapabilities(cm.activeNetwork ?: return null)
    }.getOrNull()

    /** The first IPv4 address of the active network that is not the loopback one, or null when offline. */
    fun lanAddress(ctx: Context): String? = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork ?: return null) ?: return null
        props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }.getOrNull()

    fun isConnected(ctx: Context): Boolean = caps(ctx) != null

    fun isWired(ctx: Context): Boolean = caps(ctx)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true

    /**
     * The Wi-Fi network's name, best effort: Android hands it out only to apps allowed to see the
     * location (9 and later), which this one is not, so most TVs answer null. Never asks for more.
     */
    fun ssid(ctx: Context): String? {
        if (isWired(ctx)) return null
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            @Suppress("DEPRECATION")
            cleanSsid(wm.connectionInfo?.ssid)
        } catch (e: SecurityException) {
            null
        }
    }

    /** Android quotes a readable SSID and answers `<unknown ssid>` when it may not tell. */
    fun cleanSsid(raw: String?): String? =
        raw?.removeSurrounding("\"")?.trim()?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" && it != "0x" }
}
