package com.getisolated.canvasos

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.widget.FrameLayout
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
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

    /**
     * tvOS-like avatar colours, by index (what [Profile.color] keeps): Apple's system hues, a shade
     * deeper so a white letter reads on them, and a gold rather than a yellow for the same reason.
     */
    val COLORS = listOf(0xFF2F7CF6, 0xFF34B356, 0xFFF5821F, 0xFFE8483B, 0xFF9F5AD8, 0xFFD6A01D).map { it.toInt() }
    val COLOR_NAMES = listOf("Bleu", "Vert", "Orange", "Rouge", "Violet", "Jaune")

    /** App storage (and Android/data/…/files, for pictures pushed over adb) folder of the profile photos, `<id>.jpg`. */
    const val AVATARS_DIR = "avatars"
    /** Side of a photo as kept, in px: enough for the largest avatar, 120 dp in the settings (240 px on a 1080p TV). */
    const val PHOTO_SIZE = 256
    /** On [AppRepo.ACTION_TILE_CHANGED]: a profile's photo changed, the home screen redraws its avatar. */
    const val EXTRA_AVATAR = "avatar"

    private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
    private const val ID_LENGTH = 6
    private val ID = Regex("[A-Za-z0-9_-]{1,32}")

    private val implicitMain = Profile(MAIN, "", 0, false)

    fun storeName(id: String) = if (id == MAIN) STORE else "$STORE-$id"

    /** A name as kept: trimmed and cut to [MAX_NAME]; blank is [DEFAULT_NAME], or "" for the main profile (shown as [MAIN_NAME]). */
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

    /** Whether [id] has the shape of a profile id (it names a file in [AVATARS_DIR]: nothing else can be reached). */
    fun isId(id: String) = ID.matches(id)

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

    /**
     * The letter on the avatar: the name's first letter or digit, in capitals ("É" for "élodie").
     * What comes before it is skipped, so "😀 Joie" is "J" and no half of an emoji is ever drawn;
     * "" when the name has no letter at all.
     */
    fun initial(name: String): String {
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            if (Character.isLetterOrDigit(cp)) return String(Character.toChars(Character.toTitleCase(cp)))
            i += Character.charCount(cp)
        }
        return ""
    }

    /** The letter [p]'s avatar shows: the initial of the name on screen, so "P" for the unnamed main profile or a name of emoji only. */
    fun letter(p: Profile): String = initial(displayName(p)).ifEmpty { initial(DEFAULT_NAME) }

    /** [COLORS] entry [c] as CSS ("#2f7cf6"), for the phone page. */
    fun colorHex(c: Int): String = "#%06x".format(COLORS[cleanColor(c)] and 0xFFFFFF)

    /**
     * Where [id]'s photo can be, in the order it is looked for: app storage (uploaded from the phone
     * page) first, then [externalDir] (Android/data/…/files, pushed over adb), like the tile pictures.
     */
    fun photoPaths(filesDir: File, externalDir: File?, id: String): List<File> =
        if (!isId(id)) emptyList() else listOfNotNull(filesDir, externalDir).map { File(File(it, AVATARS_DIR), "$id.jpg") }

    /** [id]'s photo, or null for the initial. Blocking (a few file checks). */
    fun photoFile(ctx: Context, id: String): File? =
        photoPaths(ctx.filesDir, ctx.getExternalFilesDir(null), id).firstOrNull { it.isFile && it.length() > 0 }

    /** The version of a photo: the size too, since `adb push` keeps the computer's date and a new picture can carry an older one. */
    fun photoStamp(f: File?): Long = if (f == null) 0L else f.lastModified() * 31 + f.length()

    /**
     * Makes [bytes] (any picture Android decodes) [id]'s photo: centre-cropped to a square and kept as
     * a [PHOTO_SIZE] JPEG in app storage. False when it is not a picture. Blocking.
     */
    fun setPhoto(ctx: Context, id: String, bytes: ByteArray): Boolean {
        if (!isId(id)) return false
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return false
        // The smallest power-of-two reduction still covering the square: a 48 MP photo decoded whole would not fit the heap.
        var sample = 1
        while (minOf(o.outWidth, o.outHeight) / (sample * 2) >= PHOTO_SIZE) sample *= 2
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return false
        val out = Bitmap.createBitmap(PHOTO_SIZE, PHOTO_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.BLACK)
            drawBitmap(src, null, cover(src.width, src.height, PHOTO_SIZE.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        src.recycle()
        val dir = File(ctx.filesDir, AVATARS_DIR).apply { mkdirs() }
        val tmp = File(dir, "$id.jpg.part")
        tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        out.recycle()
        // One pushed over adb would otherwise come back once this one is removed.
        photoPaths(ctx.filesDir, ctx.getExternalFilesDir(null), id).forEach { it.delete() }
        return tmp.renameTo(File(dir, "$id.jpg"))
    }

    /** Gives [id] back its initial; false when it had no photo. Blocking. */
    fun clearPhoto(ctx: Context, id: String): Boolean {
        var any = false
        photoPaths(ctx.filesDir, ctx.getExternalFilesDir(null), id).forEach { if (it.delete()) any = true }
        return any
    }

    /** Tells the home screen to draw the avatar again (a photo changed on the phone page). */
    fun notifyPhoto(ctx: Context, id: String) {
        ctx.sendBroadcast(Intent(AppRepo.ACTION_TILE_CHANGED).setPackage(ctx.packageName).putExtra(EXTRA_AVATAR, id))
    }

    /** Where a [w]×[h] picture goes to fill a [side] square, centred: the overflow is cut on the long side. */
    fun cover(w: Int, h: Int, side: Float): RectF {
        val scale = maxOf(side / w, side / h)
        val dw = w * scale
        val dh = h * scale
        return RectF((side - dw) / 2, (side - dh) / 2, (side + dw) / 2, (side + dh) / 2)
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
 * A profile's picture, as on tvOS: its photo in a circle, or else the first letter of its name in
 * white Inter (SF's stand-in) on a disc of its colour, lighter at the top. Every profile has one,
 * the unnamed main profile too ("P" for "Principal").
 */
object ProfileAvatar {
    private val cache = HashMap<String, Bitmap>()

    /**
     * [p]'s avatar, [size] px square, transparent outside the circle. Main thread: kept per size until
     * the name, colour or photo changes. Checks the photo's file each time (cheap), so a picture
     * pushed over adb shows on the next call.
     */
    fun bitmap(ctx: Context, p: Profile, size: Int): Bitmap {
        val photo = Profiles.photoFile(ctx, p.id)
        val id = "${p.id}|${Profiles.letter(p)}|${p.color}|${Profiles.photoStamp(photo)}|"
        val key = "$id$size"
        cache[key]?.let { return it }
        // A profile renamed, recoloured or with a new photo does not need its old pictures any more.
        cache.keys.removeAll { it.startsWith("${p.id}|") && !it.startsWith(id) }
        return draw(ctx, p, size, photo).also { cache[key] = it }
    }

    /** [bitmap] at [sizeDp], ready for an ImageView or a button. */
    fun drawable(ctx: Context, p: Profile, sizeDp: Int): BitmapDrawable =
        BitmapDrawable(ctx.resources, bitmap(ctx, p, ctx.dp(sizeDp)))

    /** Draws [p]'s avatar afresh, not kept: any thread (the phone page's server draws them too). */
    fun draw(ctx: Context, p: Profile, size: Int, photo: File? = Profiles.photoFile(ctx, p.id)): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val src = photo?.let { runCatching { decode(it, size) }.getOrNull() }
        if (src != null) {
            // A circle filled with the photo, centre-cropped: no clip path, whose edge is not anti-aliased.
            val r = Profiles.cover(src.width, src.height, size.toFloat())
            val m = Matrix().apply { setRectToRect(RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), r, Matrix.ScaleToFit.FILL) }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
            }
            c.drawCircle(size / 2f, size / 2f, size / 2f, paint)
            return bmp
        }
        val base = Profiles.COLORS[p.color.mod(Profiles.COLORS.size)]
        // Lighter at the top, like Apple's monogram discs: a hint of light, not a visible gradient.
        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, size.toFloat(), lighten(base, 0.16f), darken(base, 0.06f), Shader.TileMode.CLAMP)
        }
        c.drawCircle(size / 2f, size / 2f, size / 2f, disc)
        drawLetter(ctx, c, size, Profiles.letter(p))
        return bmp
    }

    /**
     * The letter in Inter 600, its cap height half the disc, centred on what is drawn rather than on
     * the font's metrics: the baseline arithmetic alone puts capitals visibly low. Latin, Greek and
     * Cyrillic letters and digits are centred on the capital height, so an accent (É) rises above it
     * as on tvOS instead of pushing the letter down; other scripts (汉字) on their own bounds.
     */
    private fun drawLetter(ctx: Context, c: Canvas, size: Int, letter: String) {
        val t = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            typeface = Fonts.inter(ctx, 600)
            textSize = 100f
        }
        val cased = cased(letter)
        val cap = Rect()
        val glyph = Rect()
        val ref = if (cased) "H" else letter
        t.getTextBounds(ref, 0, ref.length, cap)
        if (cap.height() <= 0) return
        t.textSize = 100f * size * 0.5f / cap.height()
        t.getTextBounds(ref, 0, ref.length, cap)
        t.getTextBounds(letter, 0, letter.length, glyph)
        val x = size / 2f - glyph.exactCenterX()
        val y = size / 2f - cap.exactCenterY()
        c.drawText(letter, x, y, t)
    }

    private fun cased(letter: String): Boolean {
        if (letter.isEmpty()) return false
        val cp = letter.codePointAt(0)
        if (Character.isDigit(cp)) return true
        return when (Character.UnicodeScript.of(cp)) {
            Character.UnicodeScript.LATIN, Character.UnicodeScript.GREEK, Character.UnicodeScript.CYRILLIC -> true
            else -> false
        }
    }

    /** The photo decoded at no less than [size] (a power-of-two reduction): one pushed over adb may be a full camera picture. */
    private fun decode(f: File, size: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (minOf(o.outWidth, o.outHeight) / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun lighten(color: Int, f: Float): Int {
        fun ch(v: Int) = (v + (255 - v) * f).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(color)), ch(Color.green(color)), ch(Color.blue(color)))
    }

    private fun darken(color: Int, f: Float): Int {
        fun ch(v: Int) = (v * (1 - f)).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(color)), ch(Color.green(color)), ch(Color.blue(color)))
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
