package com.getisolated.canvasos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import kotlin.random.Random

/** One person's home screen: its own grid ([Prefs] keeps it in [Profiles.storeName]), a name and a colour. */
data class Profile(val id: String, val name: String, val color: Int, val restricted: Boolean) {
    val isMain get() = id == Profiles.MAIN
}

/**
 * The profiles, kept in [Prefs.profiles] as a JSON array
 * (`[{"id":"p1","name":"Yassine","color":3,"restricted":false}]`). The main profile ([MAIN]) is
 * always there, first, even when the array does not list it: it is the grid every version before
 * profiles had, in the main store, so a TV that never makes a profile reads exactly what it did.
 * Pure: every edit returns a new list, and one that does not apply leaves the list as it was.
 */
object Profiles {
    const val MAIN = "main"
    /** How the main profile is called until it is given a name. */
    const val MAIN_NAME = "Principal"
    const val DEFAULT_NAME = "Profil"
    const val MAX_NAME = 30
    /** The main store ([Prefs]); another profile's grid is in "tvhome-<id>". */
    const val STORE = "tvhome"
    const val PIN_MIN = 4
    const val PIN_MAX = 6
    /** Wrong PINs in a row before the sheet that asks closes. */
    const val MAX_TRIES = 3

    /** tvOS-like avatar colours (system blue, green, orange, pink, purple and graphite), by index. */
    val COLORS = listOf(0xFF0A84FF, 0xFF30D158, 0xFFFF9F0A, 0xFFFF375F, 0xFFBF5AF2, 0xFF8E8E93).map { it.toInt() }
    val COLOR_NAMES = listOf("Bleu", "Vert", "Orange", "Rose", "Violet", "Graphite")

    private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
    private const val ID_LENGTH = 6
    private val ID = Regex("[A-Za-z0-9_-]{1,32}")

    private val implicitMain = Profile(MAIN, "", 0, false)

    fun storeName(id: String) = if (id == MAIN) STORE else "$STORE-$id"

    /** A name as kept: trimmed and cut to [MAX_NAME]; blank is [DEFAULT_NAME], or "" for the main profile (its silhouette comes back). */
    fun cleanName(name: String?, main: Boolean = false): String =
        name?.trim()?.take(MAX_NAME)?.takeIf { it.isNotEmpty() } ?: if (main) "" else DEFAULT_NAME

    /** What the profile is called on screen. */
    fun displayName(p: Profile): String = p.name.ifEmpty { if (p.isMain) MAIN_NAME else DEFAULT_NAME }

    private fun cleanColor(c: Int) = c.mod(COLORS.size)

    /** Profiles from [json], the main one first; broken JSON is the main profile alone. A repeated id keeps its first entry. */
    fun decode(json: String): List<Profile> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return listOf(implicitMain)
        val ids = HashSet<String>()
        var main = implicitMain
        val out = ArrayList<Profile>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (!ID.matches(id) || !ids.add(id)) continue
            val p = Profile(id, cleanName(o.optString("name"), id == MAIN), cleanColor(o.optInt("color", 0)), o.optBoolean("restricted", false))
            if (p.isMain) main = p else out += p
        }
        return listOf(main) + out
    }

    fun encode(list: List<Profile>): String = JSONArray().apply {
        for (p in list) put(JSONObject().put("id", p.id).put("name", p.name).put("color", p.color).put("restricted", p.restricted))
    }.toString()

    /** A short random id that no profile of [taken] has (never [MAIN], which is shorter). */
    fun newId(taken: Collection<String>, random: Random = Random.Default): String {
        while (true) {
            val id = String(CharArray(ID_LENGTH) { ID_CHARS[random.nextInt(ID_CHARS.length)] })
            if (id !in taken) return id
        }
    }

    fun byId(list: List<Profile>, id: String): Profile? = list.firstOrNull { it.id == id }

    /** The profile called [name] on screen, whatever the case ("principal" finds the unnamed main profile). */
    fun byName(list: List<Profile>, name: String): Profile? =
        name.trim().let { n -> list.firstOrNull { displayName(it).equals(n, ignoreCase = true) } }

    /** [id] when it is one of [list], else [MAIN]: a profile deleted elsewhere does not leave the TV on nothing. */
    fun resolve(list: List<Profile>, id: String): String = if (byId(list, id) != null) id else MAIN

    /** The first colour no profile has yet; once all six are taken, they go round again. */
    fun nextColor(list: List<Profile>): Int {
        val used = list.map { it.color }.toSet()
        return COLORS.indices.firstOrNull { it !in used } ?: (list.size % COLORS.size)
    }

    /** [list] with a new profile [id] at the end. An id in use or not valid changes nothing. */
    fun add(list: List<Profile>, id: String, name: String?, restricted: Boolean = false, color: Int = nextColor(list)): List<Profile> {
        if (!ID.matches(id) || id == MAIN || byId(list, id) != null) return list
        return list + Profile(id, cleanName(name), cleanColor(color), restricted)
    }

    fun rename(list: List<Profile>, id: String, name: String?): List<Profile> =
        list.map { if (it.id == id) it.copy(name = cleanName(name, it.isMain)) else it }

    fun setColor(list: List<Profile>, id: String, color: Int): List<Profile> =
        list.map { if (it.id == id) it.copy(color = cleanColor(color)) else it }

    fun setRestricted(list: List<Profile>, id: String, on: Boolean): List<Profile> =
        list.map { if (it.id == id) it.copy(restricted = on) else it }

    /**
     * Whether [id] can go: not the main profile (its grid shares the main store with every other
     * setting), not [current] (the screen shows it), and not the last one.
     */
    fun canDelete(list: List<Profile>, id: String, current: String): Boolean =
        id != MAIN && id != current && list.size > 1 && byId(list, id) != null

    fun delete(list: List<Profile>, id: String, current: String): List<Profile> =
        if (canDelete(list, id, current)) list.filter { it.id != id } else list

    /** The letter on the avatar: the name's first character, upper case ("É" for "élodie"); "" for no name. */
    fun initial(name: String): String {
        val n = name.trim()
        if (n.isEmpty()) return ""
        return String(Character.toChars(n.codePointAt(0))).uppercase(Locale.FRANCE)
    }

    /** 4 to 6 digits, ASCII only (what a TV's number pad types). */
    fun validPin(pin: String) = pin.length in PIN_MIN..PIN_MAX && pin.all { it in '0'..'9' }

    /** SHA-256 of [salt] followed by [pin], in hex: what [Prefs.profilePin] keeps instead of the PIN. */
    fun hashPin(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Whether [pin] is the one [hash] was made from; false when there is no PIN. */
    fun pinMatches(hash: String, salt: String, pin: String): Boolean =
        hash.isNotEmpty() && MessageDigest.isEqual(hashPin(salt, pin).toByteArray(), hash.toByteArray())

    /** A random salt for [hashPin], made once per TV. */
    fun newSalt(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
}

/**
 * The avatar in the top-right corner: a disc in the profile's colour with the first letter of its
 * name, as on tvOS. The main profile without a name keeps the grey silhouette (profile_avatar.xml),
 * so nothing changes on a TV that never makes a profile.
 */
object ProfileAvatar {
    private val cache = HashMap<String, Bitmap>()

    /** The picture for [p], or null for the silhouette. Main thread; drawn once per name and colour. */
    fun bitmap(ctx: Context, p: Profile): Bitmap? {
        val letter = Profiles.initial(p.name)
        if (letter.isEmpty()) return null
        val key = "${p.id}|$letter|${p.color}"
        cache[key]?.let { return it }
        // A profile renamed or recoloured does not need its old picture any more.
        cache.keys.removeAll { it.startsWith("${p.id}|") }
        val size = ctx.dp(48)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val base = Profiles.COLORS[p.color]
        val p0 = Paint(Paint.ANTI_ALIAS_FLAG)
        // Lighter at the top, like Apple's monogram discs.
        p0.shader = LinearGradient(0f, 0f, 0f, size.toFloat(), lighten(base, 0.22f), base, Shader.TileMode.CLAMP)
        c.drawCircle(size / 2f, size / 2f, size / 2f, p0)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            typeface = Fonts.inter(ctx, 600)
            textSize = size * 0.5f
            textAlign = Paint.Align.CENTER
        }
        val r = Rect()
        t.getTextBounds(letter, 0, letter.length, r)
        c.drawText(letter, size / 2f, size / 2f - r.exactCenterY(), t)
        cache[key] = bmp
        return bmp
    }

    private fun lighten(color: Int, f: Float): Int {
        fun ch(v: Int) = (v + (255 - v) * f).toInt().coerceIn(0, 255)
        return android.graphics.Color.rgb(ch(android.graphics.Color.red(color)), ch(android.graphics.Color.green(color)), ch(android.graphics.Color.blue(color)))
    }
}

/**
 * The PIN sheets, shared by the home screen and the settings: asking for the PIN before something
 * a restricted profile may not do, and choosing a new one. [show] puts a sheet up the way the
 * screen does (focus kept off what is behind it).
 */
class PinSheets(private val host: FrameLayout, private val prefs: Prefs, private val show: (Sheet) -> Unit) {
    private val pinInput = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD

    /**
     * Asks for the PIN, then runs [ok]. A wrong one asks again, up to [Profiles.MAX_TRIES] in all;
     * then the sheet just closes. With no PIN set there is nothing to ask: [ok] runs at once.
     */
    fun ask(title: String, tries: Int = 0, ok: () -> Unit) {
        if (!prefs.hasPin) { ok(); return }
        val left = Profiles.MAX_TRIES - tries
        val msg = if (tries == 0) "Saisissez le code PIN."
            else "Code incorrect. " + if (left > 1) "Encore $left essais." else "Dernier essai."
        show(Sheet(host).title(title).message(msg).input("", "Code PIN", pinInput, Profiles.PIN_MAX) { pin ->
            when {
                prefs.checkPin(pin) -> ok()
                tries + 1 < Profiles.MAX_TRIES -> ask(title, tries + 1, ok)
            }
        })
    }

    /** Asks for a new PIN, then for it again, and saves it; [done] runs once it is saved. */
    fun choose(title: String, message: String? = null, done: () -> Unit = {}) {
        val intro = message ?: "De ${Profiles.PIN_MIN} à ${Profiles.PIN_MAX} chiffres."
        show(Sheet(host).title(title).message(intro).input("", "Nouveau code", pinInput, Profiles.PIN_MAX) { pin ->
            if (!Profiles.validPin(pin)) return@input choose(title, "Le code doit compter de ${Profiles.PIN_MIN} à ${Profiles.PIN_MAX} chiffres.", done)
            show(Sheet(host).title("Confirmer le code").message("Saisissez le même code une seconde fois.")
                .input("", "Code PIN", pinInput, Profiles.PIN_MAX) { again ->
                    if (again != pin) return@input choose(title, "Les deux codes étaient différents. Recommencez.", done)
                    prefs.setPin(pin)
                    done()
                })
        })
    }
}
