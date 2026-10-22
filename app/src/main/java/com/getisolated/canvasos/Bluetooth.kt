package com.getisolated.canvasos

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlin.math.roundToInt

/**
 * The Bluetooth capsule of the Control Center: the devices already paired with the TV, which of
 * them are connected, their battery when they report it, and connecting or disconnecting the
 * audio ones. Pairing stays in the TV's own settings; nothing is scanned.
 *
 * Nothing here may run on the main thread: the profile proxies are bound services and wait for
 * their answer ([devices], [connect], [disconnect]). Only the pure formatting at the bottom
 * ([kindOf], [status], [summary]) is cheap, and unit-tested.
 */
object Bluetooth {
    private const val TAG = "canvasOSBluetooth"

    /** Android 12's runtime permission for paired devices; a plain string, as the constant is API 31 and lint flags its use. */
    const val PERMISSION = "android.permission.BLUETOOTH_CONNECT"

    /** How long a profile service gets to bind; a TV that lacks one (no hands-free) just never answers. */
    private const val PROXY_TIMEOUT_MS = 2_000L
    /** Once one profile is bound the others get this long: A2DP answers at once, hands-free often never. */
    private const val PROXY_GRACE_MS = 350L
    /** Longest device name shown in the capsule and sheet before it is cut with an ellipsis. */
    private const val NAME_MAX = 26

    enum class Kind { AUDIO, CONTROLLER, REMOTE, OTHER }

    /** Why the list is, or is not, available: [NONE] hides the capsule altogether. */
    enum class State { NONE, NEEDS_PERMISSION, OFF, READY }

    /**
     * One paired device. [noun] is a word for what it is, taken from its Bluetooth class
     * ("Casque", "Enceinte", "Manette"), shown before the name unless the name already says so.
     */
    data class Device(
        val address: String,
        val name: String,
        val kind: Kind,
        val connected: Boolean,
        val battery: Int?,
        val noun: String? = null,
    ) {
        val label get() = if (noun == null || name.contains(noun, ignoreCase = true)) name else "$noun $name"
    }

    // ---- availability -------------------------------------------------------------

    private fun adapter(): BluetoothAdapter? = try { BluetoothAdapter.getDefaultAdapter() } catch (e: Throwable) { null }

    /** False on a TV without Bluetooth: the capsule stays hidden. */
    fun available() = adapter() != null

    /** BLUETOOTH_CONNECT is a runtime permission from Android 12; before that, the manifest's BLUETOOTH is enough. */
    fun hasPermission(ctx: Context) =
        Build.VERSION.SDK_INT < 31 || ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun state(ctx: Context): State {
        val a = adapter() ?: return State.NONE
        if (!hasPermission(ctx)) return State.NEEDS_PERMISSION
        return if (runCatching { a.isEnabled }.getOrDefault(false)) State.READY else State.OFF
    }

    /** The TV's Bluetooth settings where the firmware has them, [fallback] (its settings home) where it does not. */
    fun settingsIntent(ctx: Context, fallback: Intent): Intent {
        val i = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        return if (runCatching { i.resolveActivity(ctx.packageManager) }.getOrNull() != null) i else fallback
    }

    // ---- the paired devices -------------------------------------------------------

    /**
     * Every paired device, connected ones first. Blocking: up to a couple of seconds while the
     * profile services bind. Empty without an adapter or without the permission.
     */
    // Lint cannot see that hasPermission() is the BLUETOOTH_CONNECT check; a SecurityException is caught below anyway.
    @SuppressLint("MissingPermission")
    fun devices(ctx: Context): List<Device> {
        val a = adapter() ?: return emptyList()
        if (!hasPermission(ctx)) return emptyList()
        val bonded = try { a.bondedDevices.orEmpty().toList() } catch (e: Throwable) { Log.w(TAG, "bondedDevices", e); return emptyList() }
        if (bonded.isEmpty()) return emptyList()
        val app = ctx.applicationContext
        val linked = connectedAddresses(app, a)
        val inputs: List<InputDevice> = try {
            InputDevice.getDeviceIds().toList().mapNotNull { id -> InputDevice.getDevice(id) }
        } catch (e: Throwable) { emptyList() }
        return bonded.mapNotNull { d ->
            try {
                val cls = d.bluetoothClass?.deviceClass ?: 0
                val kind = kindOf(cls)
                val name = d.name?.trim()?.takeIf { it.isNotEmpty() } ?: d.address
                // A game controller or a remote has no audio link: it is connected when Android lists
                // an input device of that name. Headsets with media keys show up there too.
                val input: InputDevice? = inputs.firstOrNull { matchesInput(name, it.name) }
                val connected = d.address.uppercase() in linked || input != null
                val battery: Int? = if (!connected) null else (if (input != null) batteryOfInput(input) else null) ?: batteryOf(d)
                Device(d.address, name, kind, connected, battery, nounOf(cls, kind))
            } catch (e: Throwable) {
                Log.w(TAG, "device ${d.address}", e)
                null
            }
        }.sortedWith(compareByDescending<Device> { it.connected }.thenBy { it.kind.ordinal }.thenBy { it.name.lowercase() })
    }

    /**
     * Addresses of the devices with an audio link up: what Android routes sound to (cheap, public),
     * plus what the A2DP and hands-free services say is connected.
     */
    private fun connectedAddresses(ctx: Context, a: BluetoothAdapter): Set<String> {
        val out = HashSet<String>()
        runCatching {
            val audio = ctx.getSystemService(AudioManager::class.java)
            for (o in audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (isBluetoothOutput(o.type)) o.address.takeIf { it.isNotEmpty() }?.let { out += it.uppercase() }
            }
        }
        val proxies = Proxies(ctx, a, intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET))
        try {
            for (profile in intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
                val proxy = proxies[profile] ?: continue
                runCatching { proxy.connectedDevices.forEach { out += it.address.uppercase() } }
            }
        } finally {
            proxies.close()
        }
        return out
    }

    private fun isBluetoothOutput(type: Int) = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_HEARING_AID ||
        (Build.VERSION.SDK_INT >= 31 && (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER))

    /** The percentage an input device (a controller, a remote) reports; Android 12 and later, when it has a battery. */
    private fun batteryOfInput(input: InputDevice): Int? {
        if (Build.VERSION.SDK_INT < 31) return null
        return try {
            val b = input.batteryState
            if (!b.isPresent) null else b.capacity.takeIf { !it.isNaN() }?.let { (it * 100).roundToInt().coerceIn(0, 100) }
        } catch (e: Throwable) { null }
    }

    /** The level a headset reports, through BluetoothDevice.getBatteryLevel (hidden since Android 8); null where firmware hides it. */
    private fun batteryOf(d: BluetoothDevice): Int? = try {
        (BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(d) as? Int)?.takeIf { it in 0..100 }
    } catch (e: Throwable) { null }

    // ---- connecting ---------------------------------------------------------------

    /**
     * Asks Android to connect an audio device that is already paired. The request returns at once
     * and the link comes up a few seconds later, so true means "asked", not "connected". False when
     * the firmware gives apps no way to do it (the methods are hidden): the caller sends the user to
     * the TV's settings. Blocking.
     */
    fun connect(ctx: Context, d: Device) = link(ctx, d, "connect")

    /** Same as [connect], for ending the link. */
    fun disconnect(ctx: Context, d: Device) = link(ctx, d, "disconnect")

    private fun link(ctx: Context, d: Device, method: String): Boolean {
        if (d.kind != Kind.AUDIO) return false
        val a = adapter() ?: return false
        if (!hasPermission(ctx)) return false
        val target = try { a.getRemoteDevice(d.address) } catch (e: Throwable) { return false }
        val profiles = intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
        val proxies = Proxies(ctx.applicationContext, a, profiles)
        try {
            var ok = false
            for (profile in profiles) {
                val proxy = proxies[profile] ?: continue
                ok = call(proxy, method, target) || ok
            }
            return ok
        } finally {
            proxies.close()
        }
    }

    /** BluetoothA2dp and BluetoothHeadset have public getters but only hidden connect/disconnect. */
    private fun call(proxy: BluetoothProfile, method: String, d: BluetoothDevice): Boolean = try {
        proxy.javaClass.getMethod(method, BluetoothDevice::class.java).invoke(proxy, d) as? Boolean ?: false
    } catch (e: Throwable) {
        Log.w(TAG, "$method is not available on this firmware: ${e.cause ?: e}")
        false
    }

    /**
     * Profile services bound together and waited for with a timeout, so the calling background
     * thread gets answers synchronously. The callbacks arrive on the main thread, which is why
     * this must never be built there. [close] releases them all, including one that binds late.
     */
    private class Proxies(ctx: Context, private val adapter: BluetoothAdapter, profiles: IntArray) {
        private val found = ConcurrentHashMap<Int, BluetoothProfile>()
        private val latch = CountDownLatch(profiles.size)
        @Volatile private var closed = false
        private val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (closed) { runCatching { adapter.closeProfileProxy(profile, proxy) }; return }
                found[profile] = proxy
                latch.countDown()
            }

            override fun onServiceDisconnected(profile: Int) { found.remove(profile) }
        }

        init {
            for (p in profiles) {
                val asked = try { adapter.getProfileProxy(ctx, listener, p) } catch (e: Throwable) { false }
                if (!asked) latch.countDown()
            }
            val start = SystemClock.elapsedRealtime()
            var firstAt = 0L
            try {
                while (latch.count > 0) {
                    val now = SystemClock.elapsedRealtime()
                    if (firstAt == 0L && found.isNotEmpty()) firstAt = now
                    if (now - start > PROXY_TIMEOUT_MS || (firstAt != 0L && now - firstAt > PROXY_GRACE_MS)) break
                    Thread.sleep(20)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        operator fun get(profile: Int): BluetoothProfile? = found[profile]

        fun close() {
            closed = true
            for ((profile, proxy) in found) runCatching { adapter.closeProfileProxy(profile, proxy) }
            found.clear()
        }
    }

    // ---- pure helpers (unit-tested) -------------------------------------------------

    // BluetoothClass.Device.Major.AUDIO_VIDEO and PERIPHERAL, repeated so the tests need no Android.
    private const val MAJOR_MASK = 0x1F00
    private const val MAJOR_AUDIO_VIDEO = 0x0400
    private const val MAJOR_PERIPHERAL = 0x0500

    /**
     * What a device is, from [BluetoothClass.getDeviceClass][android.bluetooth.BluetoothClass.getDeviceClass]
     * (major and minor class in one number; 0 when the device did not say, as many LE ones).
     * Peripherals carry their kind in the minor class: bits 6-7 say keyboard and/or pointing, bits
     * 2-5 say joystick (1), gamepad (2), remote control (3) and so on.
     */
    fun kindOf(deviceClass: Int): Kind = when (deviceClass and MAJOR_MASK) {
        MAJOR_AUDIO_VIDEO -> Kind.AUDIO
        MAJOR_PERIPHERAL -> {
            val sub = deviceClass and 0x3C
            when {
                sub == 0x04 || sub == 0x08 -> Kind.CONTROLLER
                sub == 0x0C || (deviceClass and 0xC0) != 0 -> Kind.REMOTE
                else -> Kind.OTHER
            }
        }
        else -> Kind.OTHER
    }

    /** "Casque", "Enceinte" or "Manette" when the class says so, so a bare model name reads as what it is. */
    fun nounOf(deviceClass: Int, kind: Kind): String? = when (kind) {
        Kind.AUDIO -> when (deviceClass) {
            0x0404, 0x0418 -> "Casque"   // wearable headset, headphones
            0x0414, 0x0410 -> "Enceinte" // loudspeaker, portable audio
            else -> null
        }
        Kind.CONTROLLER -> "Manette"
        else -> null
    }

    /** Whether the input device Android lists is this paired device: the same name, as the firmware reports it. */
    fun matchesInput(deviceName: String, inputName: String?): Boolean =
        inputName != null && deviceName.isNotBlank() && deviceName.trim().equals(inputName.trim(), ignoreCase = true)

    private fun shorten(s: String) = if (s.length <= NAME_MAX) s else s.take(NAME_MAX - 1).trimEnd() + "…"

    /** "Casque WH-1000XM4 · connecté · 80 %": a sheet line, and the capsule's subtitle for one device. */
    fun status(d: Device): String = buildString {
        append(shorten(d.label))
        append(if (d.connected) " · connecté" else " · déconnecté")
        if (d.connected && d.battery != null) append(" · ${d.battery} %")
    }

    /** The capsule's subtitle. */
    fun summary(devices: List<Device>): String {
        val on = devices.filter { it.connected }
        return when (on.size) {
            0 -> "Aucun appareil connecté"
            1 -> status(on[0])
            else -> "${on.size} appareils connectés"
        }
    }
}
