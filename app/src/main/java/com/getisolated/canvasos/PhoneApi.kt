package com.getisolated.canvasos

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.zip.ZipFile

/**
 * The plain logic behind the phone page's remote, keyboard and APK install ([PhoneServer]),
 * kept free of Android classes so it can be unit-tested.
 */
object PhoneApi {
    /**
     * The only keys the phone may press, by the name the page sends: exactly what its remote and
     * keyboard can send. Nothing here turns the TV off or puts it to sleep (POWER, SLEEP,
     * SOFT_SLEEP, WAKEUP): a TV that is off can only be woken with its own remote, and the phone
     * would then be useless.
     */
    val KEYS = mapOf(
        "UP" to "KEYCODE_DPAD_UP",
        "DOWN" to "KEYCODE_DPAD_DOWN",
        "LEFT" to "KEYCODE_DPAD_LEFT",
        "RIGHT" to "KEYCODE_DPAD_RIGHT",
        "CENTER" to "KEYCODE_DPAD_CENTER",
        "BACK" to "KEYCODE_BACK",
        "HOME" to "KEYCODE_HOME",
        "VOLUME_UP" to "KEYCODE_VOLUME_UP",
        "VOLUME_DOWN" to "KEYCODE_VOLUME_DOWN",
        "VOLUME_MUTE" to "KEYCODE_VOLUME_MUTE",
        "DEL" to "KEYCODE_DEL",
        "ENTER" to "KEYCODE_ENTER",
    )

    /**
     * Long presses, by the name the page sends, of keys in [KEYS] only: a finger held on the
     * trackpad is a long OK, which opens canvasOS's app menus and the Control Center editing.
     */
    val LONG_PRESSES = mapOf(
        "CENTER_LONG" to "CENTER",
    )

    /** The keycode for a key name from the page, or null when it is not allowed. */
    fun keycode(name: String?): String? = KEYS[name]

    /**
     * What to queue for a key name from the page ([script]): its keycode, or for a long press the
     * `input` arguments `keyevent --longpress KEYCODE_…`; null when it is not allowed.
     */
    fun keyInput(name: String?): String? =
        KEYS[name] ?: LONG_PRESSES[name]?.let { KEYS[it] }?.let { "keyevent --longpress $it" }

    enum class VolumeStep { RAISE, LOWER, MUTE }

    /** How a key from the page is pressed without adb ([Foreground]). */
    sealed class Direct {
        /** Key events for [keycode] (KEYCODE_…), held down past the long-press timeout when [long]. */
        data class Key(val keycode: String, val long: Boolean = false) : Direct()
        /** Pressing Home on an activity does nothing: the home screen is started instead. */
        object Home : Direct()
        /** The volume is changed directly, which any app may do, from canvasOS or not. */
        data class Volume(val step: VolumeStep) : Direct()
    }

    /** The direct way to press a key name from the page, or null when it is not allowed (as [keyInput]). */
    fun direct(name: String?): Direct? = when (name) {
        "HOME" -> Direct.Home
        "VOLUME_UP" -> Direct.Volume(VolumeStep.RAISE)
        "VOLUME_DOWN" -> Direct.Volume(VolumeStep.LOWER)
        "VOLUME_MUTE" -> Direct.Volume(VolumeStep.MUTE)
        else -> KEYS[name]?.let { Direct.Key(it) } ?: LONG_PRESSES[name]?.let { KEYS[it] }?.let { Direct.Key(it, long = true) }
    }

    /** One key event of a press: down or up, its repeat count, the long-press flag, and how long to wait before it. */
    data class Stroke(val down: Boolean, val repeat: Int = 0, val longPress: Boolean = false, val waitMs: Long = 0)

    /** Past the long-press timeout, so a view's own long-press timer has fired before the up arrives. */
    const val LONG_PRESS_MARGIN_MS = 150L

    /**
     * A press as a remote sends it: down and up; held, the down, then once the long-press timeout
     * has passed a repeat flagged as a long press, then the up.
     */
    fun strokes(long: Boolean, longPressTimeoutMs: Int): List<Stroke> =
        if (!long) listOf(Stroke(down = true), Stroke(down = false))
        else listOf(
            Stroke(down = true),
            Stroke(down = true, repeat = 1, longPress = true, waitMs = longPressTimeoutMs + LONG_PRESS_MARGIN_MS),
            Stroke(down = false),
        )

    const val MAX_TEXT = 500

    /**
     * What `input text` can type: printable ASCII. `input` looks each character up in the virtual
     * keyboard's key map, which has no accented letters, and on Android 9 a single unknown
     * character aborts the whole string. So accents are dropped (é → e) and a few marks phones
     * insert by themselves (’ « » …) become their ASCII look-alikes; anything else is left out.
     */
    fun typeable(text: String): String {
        val replaced = buildString {
            for (c in text) append(
                when (c) {
                    '’', '‘', '‚', '′' -> "'"
                    '“', '”', '„', '«', '»', '″' -> "\""
                    '–', '—', '−' -> "-"
                    '…' -> "..."
                    'œ' -> "oe"; 'Œ' -> "OE"; 'æ' -> "ae"; 'Æ' -> "AE"; 'ß' -> "ss"
                    ' ', ' ', '\t' -> " "
                    else -> c.toString()
                },
            )
        }
        return Normalizer.normalize(replaced, Normalizer.Form.NFD)
            .filter { it in ' '..'~' }
            .take(MAX_TEXT)
    }

    /** [s] as one single-quoted shell word: `it's` → `'it'\''s'`. */
    fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /**
     * The `input` arguments that type [text], or null when nothing typeable is left. `input text`
     * turns `%s` into a space; a literal space works on recent Android but not everywhere.
     */
    fun textArgs(text: String): String? {
        val t = typeable(text).ifEmpty { return null }
        return "text " + shellQuote(t.replace(" ", "%s"))
    }

    /**
     * One shell line for queued inputs, in order: each item is a keycode (KEYCODE_…) or other
     * `input` arguments (`text …`, `keyevent --longpress …`), which go out on their own. `input` starts a whole VM per call on Android 9 and 10 (half
     * a second on a slow TV), so neighbouring keys go out together as one `input keyevent A B C`.
     */
    fun script(items: List<String>): String {
        val out = ArrayList<String>()
        val keys = ArrayList<String>()
        fun flush() {
            if (keys.isNotEmpty()) out += "input keyevent " + keys.joinToString(" ")
            keys.clear()
        }
        for (it in items) if (it.startsWith("KEYCODE_")) keys += it else { flush(); out += "input $it" }
        flush()
        return out.joinToString("; ")
    }

    /** A zip ("PK\3\4") with an AndroidManifest.xml at its root: an APK, as far as the package installer will care. */
    fun isApk(f: File): Boolean {
        val magic = ByteArray(4)
        val n = runCatching { f.inputStream().use { it.read(magic) } }.getOrDefault(0)
        if (n != 4 || magic[0] != 0x50.toByte() || magic[1] != 0x4b.toByte() || magic[2] != 3.toByte() || magic[3] != 4.toByte()) return false
        return runCatching { ZipFile(f).use { it.getEntry("AndroidManifest.xml") != null } }.getOrDefault(false)
    }

    /** A short French explanation for the install failures people actually meet, or null. */
    fun installHint(pmOutput: String): String? = when {
        "INSTALL_FAILED_VERIFICATION_FAILURE" in pmOutput ->
            "Le téléviseur refuse les installations par le débogage (c'est le cas des TCL) : installez cette app depuis le téléviseur, avec son gestionnaire de fichiers."
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in pmOutput ->
            "Une autre version de cette app est déjà installée avec une signature différente : désinstallez-la d'abord."
        "INSTALL_FAILED_VERSION_DOWNGRADE" in pmOutput -> "Une version plus récente de cette app est déjà installée."
        "INSTALL_FAILED_OLDER_SDK" in pmOutput -> "Cette app demande une version d'Android plus récente que celle du téléviseur."
        "INSTALL_FAILED_NO_MATCHING_ABIS" in pmOutput -> "Cette app n'est pas compilée pour le processeur de ce téléviseur."
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" in pmOutput -> "Le téléviseur n'a plus assez d'espace de stockage."
        "INSTALL_PARSE_FAILED" in pmOutput || "INSTALL_FAILED_INVALID_APK" in pmOutput -> "Ce fichier n'est pas un APK valide, ou il est incomplet."
        else -> null
    }

    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
    const val TOKEN_LENGTH = 10

    /** A random token for the page's address: 10 characters without look-alikes (l/1, o/0), about 50 bits. */
    fun newToken(): String {
        val rnd = SecureRandom()
        return (1..TOKEN_LENGTH).map { ALPHABET[rnd.nextInt(ALPHABET.length)] }.joinToString("")
    }

    /** Compares tokens in constant time, so response timing tells nothing about a guess. */
    fun sameToken(given: String, expected: String) =
        expected.isNotEmpty() && MessageDigest.isEqual(given.toByteArray(), expected.toByteArray())
}
