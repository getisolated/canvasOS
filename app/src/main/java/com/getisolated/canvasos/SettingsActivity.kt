package com.getisolated.canvasos

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.getisolated.canvasos.tv.TvControls

/**
 * tvOS-style settings, laid out on Apple TV's settings page (1080p measures / 2, see [u]): the
 * title centred on top, the icon card on the left with a description of the focused row low in
 * that column, and rows on the right under small uppercase headers, all over the home wallpaper
 * blurred and darkened. The top level lists the groups ([Page.title]); each opens a page of rows,
 * and the list pages (keep list, hidden apps…) sit one level below their group. All reuse the same list.
 */
class SettingsActivity : Activity() {
    companion object {
        const val SONY_DREAM = "com.sony.dtv.braviashow/com.sony.dtv.braviashow.BraviaDayDreamService"
        const val ICON_FILE = "settings_icon.png"
        /** Opens the settings on a sub-page rather than the main one: [PAGE_PROFILES] or [PAGE_LOOK]. */
        const val EXTRA_PAGE = "com.getisolated.canvasos.PAGE"
        const val PAGE_PROFILES = "profiles"
        /** Apparence, where the weather is switched on (the Control Center's idle Météo control leads there). */
        const val PAGE_LOOK = "look"

        /** Where the settings icon is read from, with a key that changes whenever the picture does. */
        class IconSource(val key: String, val open: () -> java.io.InputStream)

        /**
         * The settings icon in use ([Prefs.settingsIcon]): the chosen brand's title card from the APK
         * ([Brand]), a picture of the user's own uploaded from the phone page or pushed over adb to
         * Android/data/com.getisolated.canvasos/files (the title card again while there is none), or
         * null for the built-in gear.
         */
        fun iconSource(ctx: android.content.Context): IconSource? {
            val prefs = Prefs(ctx)
            val own = if (prefs.settingsIcon == Prefs.ICON_OWN) iconFile(ctx) else null
            return when {
                prefs.settingsIcon == Prefs.ICON_GEAR -> null
                own != null -> IconSource("own@" + own.lastModified()) { own.inputStream() }
                else -> IconSource(prefs.brand) { Brand.openIcon(ctx, prefs.brand) }
            }
        }

        /** The uploaded picture whether or not it is in use ([Prefs.settingsIcon]). */
        fun iconFile(ctx: android.content.Context): java.io.File? =
            listOfNotNull(java.io.File(ctx.filesDir, ICON_FILE), ctx.getExternalFilesDir(null)?.let { java.io.File(it, ICON_FILE) })
                .firstOrNull { it.isFile && it.length() > 0 }

        /** The icon decoded at no more than about [width] px wide: a 1280 px picture shown at 600 needn't take 6 MB. */
        fun decodeIcon(src: IconSource, width: Int): android.graphics.Bitmap? = decode(src.open, width)

        /** The same for a picture on disk (an app's own tile, [AppRepo.customTileFile]); null if it cannot be read. */
        fun decodeIcon(f: java.io.File, width: Int): android.graphics.Bitmap? = decode({ f.inputStream() }, width)

        private fun decode(open: () -> java.io.InputStream, width: Int): android.graphics.Bitmap? {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { open().use { android.graphics.BitmapFactory.decodeStream(it, null, o) } }.getOrElse { return null }
            var sample = 1
            while (o.outWidth / (sample * 2) >= width) sample *= 2
            return runCatching {
                open().use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) }
            }.getOrNull()
        }

        /** A profile's avatar at the start of its row (drawn at this size, shown at [ROW_AVATAR]), and as the big icon of the profiles page. */
        private const val ROW_AVATAR_DP = 36
        private const val ROW_AVATAR = 22f
        private const val BIG_AVATAR = 110f

        // Measured on Apple TV's settings page (white at these alphas over its dark teal background).
        private val TITLE = Color.argb(85, 255, 255, 255)
        private val IDLE_TEXT = Color.WHITE
        private val IDLE_VALUE = Color.argb(95, 255, 255, 255)
        private val FOCUS_VALUE = Color.argb(125, 0, 0, 0)
        /** A row at rest: a light translucent fill over the blurred wallpaper. */
        private val IDLE_ROW = Color.argb(25, 255, 255, 255)
        private val HEADER = Color.argb(95, 255, 255, 255)
        private val DESCRIPTION = Color.argb(158, 255, 255, 255)
        /** Behind everything until the blurred wallpaper is ready, and when there is none. */
        private val BASE = Color.rgb(22, 27, 36)
        /** The wallpaper's brightest allowed luminance once blurred: Apple TV's page never goes past about this. */
        private const val MAX_LUMA = 62f

        // The layout, in units of the 960-wide design (a 1080p screenshot's pixels / 2).
        private const val TITLE_TOP = 29f
        private const val LIST_WIDTH = 390f
        private const val LIST_END = 40f
        /** Where the scrolling list starts: rows slide up under a short fade below the title. */
        private const val LIST_TOP = 70f
        private const val CARD_W = 200f
        private const val CARD_H = 120f
        private const val CARD_TOP = 193f
        private const val ABOUT_TOP = 416f
        private const val ABOUT_W = 440f
        private const val ROW_H = 33f
        private const val ROW_GAP = 7f
        private const val ROW_RADIUS = 13f
        private const val ROW_PAD = 16f
        private const val HEADER_ABOVE = 16f
        private const val HEADER_BELOW = 8f
        private const val HEADER_SIZE = 13.5f
        private const val HEADER_TAG = "header"
        private const val LISTENER_NEEDED = "Écouteur requis (étape 4)"
        private const val PROFILES_ABOUT = "Chaque profil a son propre accueil : l'ordre des apps, les apps masquées, les dossiers, les noms des apps, " +
            "les entrées épinglées et le fond d'écran. Les autres réglages valent pour tous. Un profil enfant demande le code PIN pour changer de profil, " +
            "ouvrir les réglages ou supprimer une app."
    }

    private lateinit var prefs: Prefs
    private lateinit var repo: AppRepo
    private lateinit var root: FrameLayout
    private lateinit var body: FrameLayout
    private lateinit var list: LinearLayout
    private lateinit var heading: TextView
    private lateinit var about: TextView
    private lateinit var icon: ImageView
    /** The home wallpaper, blurred and darkened ([loadBackdrop]). */
    private lateinit var backdrop: ImageView
    /** What [backdrop] shows ("source:file"), so a return from the wallpaper picker redoes it only if it changed. */
    @Volatile private var backdropKey: String? = null
    /** Pixels per unit of the 960-wide design: the screen's width / 960, whatever its density. */
    private var unit = 2f
    private lateinit var scroll: ScrollView
    private var page = Page.MAIN
    /** For each page, the row of its parent page that opened it, focused again on the way back. */
    private val openers = HashMap<Page, Int>()

    /**
     * The top level ([MAIN]) lists the groups, the entries with a [title], in this order; each group
     * page holds the rows, and the list pages (keep list, hidden apps…) sit one level below their group.
     */
    private enum class Page(val title: String? = null, val summary: String? = null) {
        MAIN,
        LOOK("Apparence", "L'horloge, le Dock, la photo de profil, la météo, l'effet de sélection, le Centre de contrôle " +
            "(son style, ses commandes) et l'animation de démarrage."),
        WALLPAPER("Fond d'écran et cadre photo", "Le fond de l'écran d'accueil et la fréquence à laquelle il change, " +
            "et le cadre photo que le téléviseur affiche en économiseur d'écran."),
        SHELF("Top Shelf", "Les contenus de l'app sélectionnée dans le Dock, en plein écran au-dessus de lui, et d'où ils viennent."),
        PLAYBACK("Lecture et notifications", "Le lecteur du Centre de contrôle pour ce que lisent les apps, " +
            "et les bannières de notifications en haut à droite de l'écran."),
        MEMORY("Mémoire et alimentation", "La libération de la mémoire, en veille, à heures régulières ou tout de suite, les apps qu'elle épargne, " +
            "et ce que fait le bouton marche/arrêt."),
        APPS("Apps", "Les apps masquées de l'accueil, les apps préinstallées supprimées et les dossiers."),
        ACCOUNTS("Profils", "Un accueil par personne, avec ses apps, ses dossiers et son fond d'écran ; un profil enfant est protégé par un code PIN."),
        PHONE_GROUP("Page téléphone", "Une page web pour un téléphone sur le même Wi-Fi : télécommande avec clavier, photos et liens d'images, " +
            "installation d'apps. Son QR code et sa protection."),
        REMOTE("Télécommande", "Des touches de la télécommande associées à des fonctions de canvasOS, sur l'accueil : " +
            "appui simple ou appui long, la touche apprise en appuyant dessus."),
        SYSTEM("Système", "Le débogage local et ADB, l'adresse du téléviseur, la protection de l'accueil, les réglages d'origine du téléviseur, " +
            "la mise à jour de canvasOS et sa version."),
        KEEP, HIDDEN, REMOVED, PHONE, LAST_CLEAN, BANNER_APPS, FOLDERS, PROFILES, REMOTE_ACTIONS, REMOTE_APPS, REMOTE_INPUTS;

        /** Where Back goes; null at the top level. */
        val parent: Page? get() = when (this) {
            MAIN -> null
            KEEP, LAST_CLEAN -> MEMORY
            HIDDEN, REMOVED, FOLDERS -> APPS
            PHONE -> PHONE_GROUP
            BANNER_APPS -> PLAYBACK
            PROFILES -> ACCOUNTS
            REMOTE_ACTIONS -> REMOTE
            REMOTE_APPS, REMOTE_INPUTS -> REMOTE_ACTIONS
            else -> MAIN
        }
    }

    /** The page this screen was opened on ([EXTRA_PAGE]); Back from it leaves, as it came from the home screen. */
    private var entry = Page.MAIN

    /** Sheets up over the list (profiles, the PIN), newest last. */
    private val sheets = ArrayList<Sheet>()
    /** The row focused when the first sheet went up, focused again once they are all gone. */
    private var sheetRow = 0
    /** In a restricted profile, until the PIN is given; the list stays hidden meanwhile. */
    private var locked = false
    private val pins by lazy { PinSheets(root, prefs) { showSheet(it) } }

    /** "Régénérer le code" was pressed while this screen was open. */
    private var tokenRenewed = false

    /** What "Libérer la mémoire" did while this screen was open, shown on its row. */
    private var cleanResult: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        repo = AppRepo(this)

        val m = resources.displayMetrics
        unit = maxOf(m.widthPixels, m.heightPixels) / 960f
        root = FrameLayout(this).apply { setBackgroundColor(BASE) }
        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        heading = label(28f, TITLE, 600).apply { gravity = Gravity.CENTER }
        root.addView(heading, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = u(TITLE_TOP) })

        // The left column is everything left of the list; the card and the description are centred in it.
        val column = 960f - LIST_END - LIST_WIDTH
        body = FrameLayout(this)
        icon = ImageView(this).apply {
            showIcon(this)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipRounded(u(17).toFloat())
        }
        body.addView(icon, FrameLayout.LayoutParams(u(CARD_W), u(CARD_H), Gravity.TOP or Gravity.START).apply {
            leftMargin = u((column - CARD_W) / 2); topMargin = u(CARD_TOP)
        })
        about = label(15f, DESCRIPTION).apply { gravity = Gravity.CENTER_HORIZONTAL }
        // Low in the column as on tvOS; a description too long for the space left rises towards the card.
        about.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val over = v.bottom - (root.height - u(24))
            v.translationY = -minOf(maxOf(over, 0), maxOf(v.top - u(CARD_TOP + CARD_H + 18), 0)).toFloat()
        }
        body.addView(about, FrameLayout.LayoutParams(u(ABOUT_W), -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = u((column - ABOUT_W) / 2); topMargin = u(ABOUT_TOP)
        })

        // Not clipped, so a focused row can grow past the list's edges; the top padding (set per page in
        // [show]) lines the first row up with the card, and the rows scroll up under a fade below the title.
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(u(10), 0, u(10), u(48))
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
            // A fading edge redraws through an offscreen layer while scrolling: not on weak TVs.
            if (!Device.isLowEnd(this@SettingsActivity)) {
                isVerticalFadingEdgeEnabled = true
                setFadingEdgeLength(u(26))
            }
            addView(list)
        }
        body.addView(scroll, FrameLayout.LayoutParams(u(LIST_WIDTH + 20), -1, Gravity.TOP or Gravity.END).apply {
            rightMargin = u(LIST_END - 10); topMargin = u(LIST_TOP)
        })
        root.addView(body, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        entry = when (intent.getStringExtra(EXTRA_PAGE)) { PAGE_PROFILES -> Page.PROFILES; PAGE_LOOK -> Page.LOOK; else -> Page.MAIN }
        // A restricted profile reaches the settings only with the PIN, whichever way they were opened
        // (the avatar, the Control Center's gear, the grid's tile, another launcher).
        if (prefs.restricted && prefs.hasPin) {
            locked = true
            body.visibility = android.view.View.INVISIBLE
            heading.text = "Réglages de l'accueil"
            pins.ask("Réglages de l'accueil") {
                locked = false
                body.visibility = android.view.View.VISIBLE
                show(entry)
            }
        } else show(entry)
    }

    /**
     * Shows [s] over the page and keeps focus off the rows behind it. Once the last sheet closes,
     * the page is drawn again (what the sheets changed shows) with focus back on its row; a PIN
     * never given leaves the screen.
     */
    private fun showSheet(s: Sheet) {
        if (sheets.isEmpty()) sheetRow = list.indexOfChild(list.focusedChild).coerceAtLeast(0)
        sheets += s
        scroll.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        s.onDismiss {
            sheets -= s
            if (sheets.isNotEmpty() || isDestroyed) return@onDismiss
            if (locked) { finish(); return@onDismiss }
            scroll.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            show(page, sheetRow)
        }
        s.show()
    }

    private fun show(p: Page, focusIndex: Int = 0) {
        // Another page starts at its top, its first header showing; the same page drawn again stays where it was.
        if (p != page) scroll.scrollTo(0, 0)
        page = p
        list.removeAllViews()
        when (p) {
            Page.MAIN -> main()
            Page.LOOK -> lookGroup()
            Page.WALLPAPER -> wallpaperGroup()
            Page.SHELF -> shelfGroup()
            Page.PLAYBACK -> playbackGroup()
            Page.MEMORY -> memoryGroup()
            Page.APPS -> appsGroup()
            Page.ACCOUNTS -> accountsGroup()
            Page.PHONE_GROUP -> phoneGroup()
            Page.SYSTEM -> systemGroup()
            Page.REMOTE -> remoteGroup()
            Page.REMOTE_ACTIONS -> remoteActions()
            Page.REMOTE_APPS -> remoteApps()
            Page.REMOTE_INPUTS -> remoteInputs()
            Page.KEEP -> appPicker("Apps à conserver", prefs.keep) { pkg, on ->
                prefs.keep = if (on) prefs.keep + pkg else prefs.keep - pkg
            }
            Page.HIDDEN -> appPicker("Apps masquées", prefs.hidden, includeHidden = true) { pkg, on ->
                prefs.hidden = if (on) prefs.hidden + pkg else prefs.hidden - pkg
            }
            Page.REMOVED -> removedApps()
            Page.PHONE -> phonePage()
            Page.LAST_CLEAN -> lastCleanPage()
            Page.BANNER_APPS -> bannerAppsPage()
            Page.FOLDERS -> foldersPage()
            Page.PROFILES -> profilesPage()
        }
        // A list shorter than the column is centred in it; a longer one starts at the top and scrolls.
        list.setPadding(list.paddingLeft, u(8), list.paddingRight, u(48))
        list.post { centreList() }
        p.title?.let {
            heading.text = it
            // Under the icon until a row with a description of its own is focused.
            about.text = p.summary
        }
        pageIcon(p)
        list.post {
            val target = list.getChildAt(focusIndex.coerceIn(0, maxOf(list.childCount - 1, 0)))
            (target?.takeIf { it.isFocusable } ?: (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.isFocusable })
                ?.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        Foreground.resumed(this)
        loadBackdrop()
    }

    override fun onPause() {
        // A lesson left half way (the screen went away) must not keep every key of the TV.
        KeyCapture.learning = null
        learning = null
        Foreground.paused(this)
        super.onPause()
    }

    /**
     * The home screen's wallpaper behind the page, as Apple TV's settings sit on a blurred, darkened
     * haze of the home screen: the current picture of the current source decoded small, blurred twice
     * over, darkened, and its highlights held down so white text stays legible on a bright photo.
     * A gradient theme is blurred the same way; no picture yet leaves the plain dark page. Done off
     * the main thread, again on return only if the wallpaper changed meanwhile.
     */
    private fun loadBackdrop() {
        val theme = prefs.background
        Thread {
            // An earlier version let the phone page upload a settings background; it is no longer used.
            listOfNotNull(filesDir, getExternalFilesDir(null)).forEach { java.io.File(it, "settings_bg.jpg").delete() }
            val photo = if (Wallpapers.isPhoto(theme)) Wallpapers(applicationContext, Wallpapers.sourceFor(theme)).current() else null
            val key = "$theme:${photo?.name}"
            if (key == backdropKey) return@Thread
            val src = runCatching {
                when {
                    photo != null -> decodeIcon(photo, 192)
                    Wallpapers.isPhoto(theme) -> null
                    else -> Backgrounds.render(theme)
                }
            }.getOrNull()
            val bmp = src?.let { settle(Wallpapers.blurred(Wallpapers.blurred(it, 0f), 0.55f)) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val first = backdropKey == null
                backdropKey = key
                backdrop.setImageBitmap(bmp)
                if (first && bmp != null) {
                    backdrop.alpha = 0f
                    backdrop.animate().alpha(1f).setDuration(220).start()
                }
            }
        }.start()
    }

    /** Scales down every pixel brighter than [MAX_LUMA], keeping its hue: a white sky turns the deep grey-blue of tvOS's page. */
    private fun settle(b: android.graphics.Bitmap): android.graphics.Bitmap {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val r = Color.red(c); val g = Color.green(c); val bl = Color.blue(c)
            val luma = 0.299f * r + 0.587f * g + 0.114f * bl
            if (luma <= MAX_LUMA) continue
            val k = MAX_LUMA / luma
            px[i] = Color.rgb((r * k).toInt(), (g * k).toInt(), (bl * k).toInt())
        }
        b.setPixels(px, 0, w, 0, 0, w, h)
        return b
    }

    /** [v] units of the 960-wide design in pixels. */
    /** Centres the rows vertically when they do not fill the column; otherwise they start under the title. */
    private fun centreList() {
        val content = (0 until list.childCount).sumOf { i ->
            val v = list.getChildAt(i); val lp = v.layoutParams as LinearLayout.LayoutParams
            v.measuredHeight + lp.topMargin + lp.bottomMargin
        }
        val room = scroll.height - u(8) - u(48)
        val top = u(8) + maxOf(0, (room - content) / 2)
        if (list.paddingTop != top) list.setPadding(list.paddingLeft, top, list.paddingRight, list.paddingBottom)
    }

    private fun u(v: Number): Int = (v.toFloat() * unit + 0.5f).toInt()

    /** A text sized in design units rather than sp, so the page keeps Apple TV's proportions at any density. */
    private fun label(size: Float, color: Int, weight: Int = 400): TextView =
        text(size, color, weight = weight).apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * unit) }

    /** Whether the big icon shows the current profile's avatar ([pageIcon]) rather than the settings icon. */
    private var iconAvatar = false

    /** On the profiles page the big icon is the current profile's avatar, as on tvOS's account screens; elsewhere the settings icon. */
    private fun pageIcon(p: Page) {
        if (p == Page.PROFILES) {
            icon.setImageDrawable(ProfileAvatar.drawable(this, prefs.current(), (u(BIG_AVATAR) / resources.displayMetrics.density).toInt()))
            icon.scaleType = ImageView.ScaleType.CENTER
            iconAvatar = true
        } else if (iconAvatar) {
            iconAvatar = false
            icon.scaleType = ImageView.ScaleType.CENTER_CROP
            showIcon(icon)
        }
    }

    /** Opens [p] from a row of the current page, which Back from [p] focuses again. */
    private fun open(p: Page) {
        openers[p] = list.indexOfChild(list.focusedChild).coerceAtLeast(0)
        show(p)
    }

    private fun showIcon(v: ImageView) {
        val own = iconSource(this)?.let { decodeIcon(it, u(CARD_W * 2)) }
        if (own != null) v.setImageBitmap(own) else v.setImageResource(R.drawable.banner_settings)
    }

    /** The top level: one row per group ([Page.title]), each opening its page. */
    private fun main() {
        heading.text = "Réglages de l'accueil"
        about.text = ""
        for (g in Page.values()) g.title?.let { t -> row(t, g.summary, { "" }, chevron = true) { open(g) } }
    }

    /** Apparence: the clock, the home screen, the weather, effects and the boot animation. */
    private fun lookGroup() {
        section("Horloge")
        row("Format de l'heure", "L'heure sur l'écran d'accueil et dans le Centre de contrôle, au format 12 h (11:49 AM) ou 24 h (23:49).",
            { if (prefs.clock24) "24 h" else "12 h" }) { prefs.clock24 = !prefs.clock24 }
        row("Horloge", "Une petite horloge en haut à droite, ou une grande horloge avec la date en haut à gauche (l'ancien style), ou aucune.", {
            when (prefs.clockStyle) { Prefs.CLOCK_LARGE -> "Grande, à gauche"; Prefs.CLOCK_OFF -> "Masquée"; else -> "En haut à droite" }
        }) {
            val opts = listOf(Prefs.CLOCK_CORNER, Prefs.CLOCK_LARGE, Prefs.CLOCK_OFF)
            prefs.clockStyle = opts[(opts.indexOf(prefs.clockStyle) + 1) % opts.size]
        }
        section("Accueil")
        row("Dock", "Récents : l'app que vous ouvrez passe en tête, comme aujourd'hui. Fixe : l'ordre que vous avez choisi avec « Modifier l'accueil » est conservé.",
            { if (prefs.dockRecents) "Récents" else "Fixe" }) { prefs.dockRecents = !prefs.dockRecents }
        row("Photo de profil", "La photo du profil en haut à droite de l'accueil : l'initiale de son nom sur sa couleur, ou une photo importée " +
            "depuis la page téléphone. Appuyez dessus pour changer de profil. Elle reste affichée tant qu'il existe plusieurs profils.", { onOff(prefs.showAvatar) }) { prefs.showAvatar = !prefs.showAvatar }
        section("Météo")
        row("Météo", "La température et le temps qu'il fait à côté de l'horloge, et les prévisions sur cinq jours dans le Centre de contrôle. " +
            "Elles viennent d'Open-Meteo et sont mises à jour toutes les 30 minutes pendant que l'accueil est affiché. " +
            "Tant qu'aucune ville n'est choisie, le lieu est deviné une fois d'après l'adresse IP publique du téléviseur (ip-api.com).",
            { onOff(prefs.weather) }) { prefs.weather = !prefs.weather }
        row("Lieu de la météo", "Pour choisir une autre ville : la page téléphone (onglet Réglages, section Météo), ou un ordinateur avec la commande CONFIG (weather_city).",
            { Weather.placeLabel(prefs).ifEmpty { "Pas encore défini" } }) {}
        section("Effets")
        row("Effet de sélection", "tvOS : l'app sélectionnée s'agrandit, s'incline selon la direction et un reflet passe dessus. Simple : elle s'agrandit seulement." +
            "\nAuto choisit Simple sur les téléviseurs peu puissants.", {
            auto(prefs.focusStyle, prefs.focusTvos(this), "tvOS", "Simple")
        }) {
            prefs.focusStyle = next(prefs.focusStyle, Prefs.FOCUS_TVOS, Prefs.FOCUS_SIMPLE)
        }
        section("Centre de contrôle")
        row("Style du Centre de contrôle", "Verre : du verre dépoli qui laisse voir l'image derrière ; le bouton sélectionné devient blanc. Panneau : l'ancien panneau sombre." +
            "\nAuto choisit Verre sur tous les téléviseurs.", {
            auto(prefs.ccStyle, prefs.ccGlass(this), "Verre", "Panneau")
        }) {
            prefs.ccStyle = next(prefs.ccStyle, Prefs.CC_GLASS, Prefs.CC_PANEL)
        }
        row("Personnaliser le Centre de contrôle", "Ouvre le Centre de contrôle en mode modification. Ses commandes sont sur une grille " +
            "de 4 × 6 cases, sur trois pages au plus (Bas depuis la dernière ligne passe à la page suivante). OK sur une commande : " +
            "la déplacer, changer sa taille, la retirer, ajouter une commande ou une page. Retour pour terminer. " +
            "Ailleurs, un appui long sur OK sur le bouton du Centre de contrôle, en haut à droite de l'accueil, fait de même.", { "" }, chevron = true) {
            startActivity(Intent(this, HomeActivity::class.java).putExtra(HomeActivity.EXTRA_EDIT_CC, true))
        }
        row("Réinitialiser le panneau", "Remet les commandes du Centre de contrôle à leur place et à leur taille d'origine, sur deux pages.", { "" }) {
            showSheet(Sheet(root).title("Réinitialiser le Centre de contrôle ?")
                .message("Les commandes reprennent leur place et leur taille d'origine, sur deux pages.")
                .button("Réinitialiser") { prefs.resetCcLayout() }
                .button("Annuler") {})
        }
        section("Logo")
        row("Logo", "La carte-titre et le film de démarrage intégrés : ceux de canvasOS, ou ceux de l'Apple TV (tvOS). Ils servent d'icône à ces réglages et d'animation de démarrage, sauf choix contraire ci-dessous.", {
            Brand.label(prefs.brand)
        }) {
            prefs.brand = if (prefs.brand == Brand.TVOS) Brand.CANVASOS else Brand.TVOS
            showIcon(icon)
        }
        val picture = iconFile(this) != null
        row("Icône des réglages", "L'icône de ces réglages, sur l'accueil et en haut de cette page : la carte-titre du logo choisi, votre image importée depuis la page téléphone, ou l'engrenage intégré." +
            if (picture) "" else "\nAucune image importée pour l'instant.", {
            when (prefs.settingsIcon) { Prefs.ICON_OWN -> "Image importée"; Prefs.ICON_GEAR -> "Engrenage"; else -> "Logo " + Brand.label(prefs.brand) }
        }) {
            val opts = listOfNotNull(Prefs.ICON_BRAND, Prefs.ICON_OWN.takeIf { picture }, Prefs.ICON_GEAR)
            prefs.settingsIcon = opts[(opts.indexOf(prefs.settingsIcon) + 1) % opts.size]
            showIcon(icon)
        }
        section("Animation de démarrage")
        val video = Intro.videoFile(this) != null
        row("Animation de démarrage", "Jouée au premier affichage de l'accueil après l'allumage ; à la fin, le logo s'agrandit et ouvre l'accueil. Appuyez sur une touche pour la passer.", {
            when (prefs.intro) { Prefs.INTRO_BOOT -> "Au démarrage"; Prefs.INTRO_WAKE -> "Démarrage et réveil"; else -> "Désactivée" }
        }) {
            val opts = listOf(Prefs.INTRO_OFF, Prefs.INTRO_BOOT, Prefs.INTRO_WAKE)
            prefs.intro = opts[(opts.indexOf(prefs.intro) + 1) % opts.size]
            // This boot counts as played: turning it on doesn't start it on the next return home.
            prefs.introBoot = Intro.bootStamp(this)
        }
        row("Contenu de l'animation", "Film du logo choisi : celui de canvasOS ou celui de l'Apple TV, selon la ligne Logo. Vidéo importée : la vôtre, depuis la page téléphone, dont le logo final s'agrandit et ouvre l'accueil. Nom canvasOS : le nom apparaît en fondu puis s'agrandit." +
            if (video) "" else "\nAucune vidéo importée pour l'instant : à sa place, le film du logo choisi.", {
            when (prefs.introSource) { Prefs.INTRO_VIDEO -> "Vidéo importée"; Prefs.INTRO_WORDMARK -> "Nom canvasOS"; else -> "Film " + Brand.label(prefs.brand) }
        }) {
            val opts = listOfNotNull(Prefs.INTRO_BRAND, Prefs.INTRO_VIDEO.takeIf { video }, Prefs.INTRO_WORDMARK)
            prefs.introSource = opts[(opts.indexOf(prefs.introSource) + 1) % opts.size]
        }
        row("Délai au réveil", "Au sortir de la veille, l'écran reste noir ce temps avant l'animation. Beaucoup de téléviseurs (surtout OLED) n'affichent l'image que quelques secondes après l'allumage : allongez si le début de l'animation manque, raccourcissez si le noir dure trop.", {
            if (prefs.wakeDelay == 0) "Aucun" else "${prefs.wakeDelay / 1000.0} s".replace(".0 ", " ")
        }) {
            val opts = listOf(0, 1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 10_000)
            prefs.wakeDelay = opts.firstOrNull { it > prefs.wakeDelay } ?: 0
        }
        row("Son de l'animation", "Joue le son du film ou de la vidéo d'animation.", { onOff(prefs.introSound) }) { prefs.introSound = !prefs.introSound }
        row("Aperçu de l'animation", "Revient à l'accueil et la joue une fois maintenant.", { "" }, chevron = true) {
            startActivity(Intent(this, HomeActivity::class.java)
                .putExtra(Intro.EXTRA_PREVIEW, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Fond d'écran et cadre photo. */
    private fun wallpaperGroup() {
        section("Fond d'écran")
        row("Fond d'écran", "Le fond de l'écran d'accueil : un thème, une source de fonds d'écran ou vos propres photos.", {
            Backgrounds.THEMES[prefs.background]?.first ?: Wallpapers.label(Wallpapers.sourceFor(prefs.background)).let {
                if (prefs.background == Wallpapers.MINE) it else "$it (auto)"
            }
        }, chevron = true) { startActivity(Intent(this, WallpaperActivity::class.java)) }
        row("Changer de photo", "À quelle fréquence le fond d'écran automatique change. Jamais : la photo ne change qu'avec le bouton Photo suivante du Centre de contrôle (ou Changer maintenant ci-dessous).",
            { minutesLabel(prefs.wallMinutes) }) {
            val opts = listOf(0, 15, 30, 60, 180, 1440)
            prefs.wallMinutes = opts[(opts.indexOf(prefs.wallMinutes) + 1) % opts.size]
        }
        row("Changer maintenant", "Prend une nouvelle photo de la source de fonds d'écran. Les photos sont téléchargées au moment de les afficher ; les anciennes sont effacées après quelques jours.",
            { "${Wallpapers(this, Wallpapers.sourceFor(prefs.background)).files().size} photos" }) { v ->
            v.text = "Téléchargement…"
            Thread {
                val w = Wallpapers(applicationContext, Wallpapers.sourceFor(prefs.background))
                w.refresh()
                w.advance(prefs.wallMinutes, force = true)
                runOnUiThread { v.text = "${w.files().size} photos" }
            }.start()
        }
        section("Cadre photo")
        row("Cadre photo en économiseur", "Après un moment d'inactivité, le téléviseur affiche en plein écran des photos ou des vues aériennes, à la place de son propre économiseur d'écran.",
            { onOff(prefs.frameScreensaver) }) { v ->
            val on = !prefs.frameScreensaver
            val current = Settings.Secure.getString(contentResolver, "screensaver_components").orEmpty()
            val mine = "$packageName/${PhotoDream::class.java.name}"
            // Whatever the TV had before goes back when the frame is turned off (Sony's own if unknown).
            if (on && !current.startsWith(packageName)) prefs.prevScreensaver = current
            val comp = if (on) mine else prefs.prevScreensaver.ifEmpty { SONY_DREAM }
            v.text = "Réglage…"
            Thread {
                val ok = putSecure("screensaver_components", comp) && putSecure("screensaver_enabled", "1")
                if (ok) prefs.frameScreensaver = on
                runOnUiThread { v.text = if (ok) onOff(prefs.frameScreensaver) else "Débogage local requis" }
            }.start()
        }
        row("Contenu de l'économiseur", "Le cadre photo montre les photos du fond d'écran, ou les vues aériennes de l'Apple TV.",
            { if (prefs.saverMode == Prefs.SAVER_AERIAL) "Vues aériennes" else "Photos" }) {
            prefs.saverMode = if (prefs.saverMode == Prefs.SAVER_AERIAL) Prefs.SAVER_PHOTOS else Prefs.SAVER_AERIAL
        }
        row("Délai avant le cadre photo", "Durée sans activité avant l'affichage du cadre photo.", { minutesLabel(idleMinutes()) }) { v ->
            val opts = listOf(1, 3, 5, 10, 15, 30)
            val next = opts[(opts.indexOf(idleMinutes()) + 1) % opts.size]
            v.text = "Réglage…"
            Thread {
                val ok = LocalAdb.shell(applicationContext, "settings put system screen_off_timeout ${next * 60_000}") != null
                runOnUiThread { v.text = if (ok) minutesLabel(next) else "Débogage local requis" }
            }.start()
        }
        row("Durée par photo", "Durée d'affichage de chaque photo dans le cadre photo.", { "${prefs.frameSeconds} s" }) {
            val opts = listOf(15, 30, 60, 120)
            prefs.frameSeconds = opts[(opts.indexOf(prefs.frameSeconds) + 1) % opts.size]
        }
    }

    /** Top Shelf: its mode and its Android TV source. */
    private fun shelfGroup() {
        section("Top Shelf")
        row("Top Shelf", "Les contenus de l'app sélectionnée dans le Dock, en plein écran au-dessus. Complet : la ligne « Continuer à regarder » " +
            "publiée pour Android TV, puis les tendances TMDB (avec votre clé) et les sources web. Android TV seul : sans TMDB ni web. " +
            "Désactivé : le fond d'écran reste visible.", {
            when (prefs.shelfMode) { Prefs.SHELF_TV -> "Android TV seul"; Prefs.SHELF_OFF -> "Désactivé"; else -> "Complet" }
        }) {
            prefs.shelfMode = Prefs.SHELF_MODES[(Prefs.SHELF_MODES.indexOf(prefs.shelfMode) + 1) % Prefs.SHELF_MODES.size]
        }
        section("Sources")
        row("Continuer à regarder", "La ligne que les apps (YouTube, Netflix…) publient pour Android TV : ce que vous regardiez, à reprendre. " +
            "Désactivée, le Top Shelf n'utilise que TMDB et les sources web, quand le mode du Top Shelf les autorise.",
            { onOff(prefs.watchNext) }) { prefs.watchNext = !prefs.watchNext }
    }

    // ---------------------------------------------------------------- Télécommande

    /** The binding being changed (its key kept for a new function, its function for a new key); null for a new one. */
    private var remoteEdit: KeyMap.Binding? = null
    /** The key being learnt: the function it will do, the binding it replaces, and the sheet asking for it. */
    private class Learning(val action: String, val replacing: KeyMap.Binding?, val sheet: Sheet)
    private var learning: Learning? = null
    private var learnCode = -1
    private val tv by lazy { TvControls(this) }

    private fun bindings() = KeyMap.parse(prefs.keyMap)

    /** The name of an app or an input bound to a key, for the list. */
    private fun boundName(action: String): String? = when {
        action.startsWith(KeyMap.APP) -> repo.allApps().firstOrNull { it.pkg == action.removePrefix(KeyMap.APP) }?.label
        action.startsWith(KeyMap.INPUT) -> InputTile.decode(action.removePrefix(KeyMap.INPUT))?.name
        else -> null
    }

    /** Télécommande: the shortcuts, one row each, and a new one. */
    private fun remoteGroup() {
        val list = bindings()
        section("Raccourcis")
        if (list.isEmpty()) row("Aucun raccourci", "Choisissez « Ajouter un raccourci… », la fonction, puis appuyez sur la touche de la télécommande qui la fera.", { "" }) {}
        for (b in list) {
            row(KeyMap.keyName(b.code, b.long), "Appuyez pour changer la touche ou la fonction, ou supprimer ce raccourci. " +
                "Les raccourcis agissent sur l'accueil, pas dans les autres apps.", { KeyMap.label(b.action, ::boundName) }) { bindingMenu(b) }
        }
        row("Ajouter un raccourci…", "La fonction d'abord, puis la touche : un appui bref l'associe à l'appui simple, un appui maintenu à l'appui long. " +
            "Les touches du téléviseur (Accueil, Volume) et celles de l'accueil (flèches, OK, Retour en appui simple) sont refusées.",
            { "" }, chevron = true) { remoteEdit = null; open(Page.REMOTE_ACTIONS) }
        section("Touches du téléviseur")
        row("Capter les touches du téléviseur", "Désactivé par défaut. Les touches Réglages et Profil de la télécommande (et quelques autres : Guide, Source, TV) " +
            "sont prises par le téléviseur avant d'atteindre canvasOS. Activé, le service d'accessibilité de canvasOS (celui de la Protection de l'accueil) " +
            "filtre les touches : il voit alors chaque touche pressée, dans toutes les apps, et ne retient que ces touches-là, seulement sur l'accueil et " +
            "seulement si elles sont associées ; tout le reste passe tel quel. Rien n'est enregistré. Demande le débogage local une fois.",
            { if (!prefs.captureKeys) "Désactivé" else if (HomeGuardService.isRunning) "Activé" else "Activé (service inactif)" }) { v ->
            val on = !prefs.captureKeys
            v.text = "Réglage…"
            Thread {
                val ok = (Guard.hasSecureSettings(this) || Guard.grantViaLocalAdb(applicationContext)) && Guard.setCaptureKeys(applicationContext, on)
                runOnUiThread { v.text = if (!ok) "Débogage local requis" else if (on) "Activé" else "Désactivé" }
            }.start()
        }
    }

    private fun bindingMenu(b: KeyMap.Binding) {
        showSheet(Sheet(root).title(KeyMap.keyName(b.code, b.long)).message(KeyMap.label(b.action, ::boundName))
            .button("Changer la touche") { learn(b.action, replacing = b) }
            .button("Changer la fonction") { remoteEdit = b; open(Page.REMOTE_ACTIONS) }
            .button("Supprimer") { prefs.keyMap = KeyMap.serialize(KeyMap.remove(bindings(), b)) }
            .button("Annuler") {})
    }

    /** The functions a key can do, then the apps and the inputs on pages of their own. */
    private fun remoteActions() {
        heading.text = "Fonction"
        about.text = if (remoteEdit != null) "La nouvelle fonction de « ${KeyMap.keyName(remoteEdit!!.code, remoteEdit!!.long)} »." else "Ce que fera la touche ; elle est demandée ensuite."
        for ((id, name) in KeyMap.ACTIONS) row(name, null, { "" }) { chooseAction(id) }
        row("Ouvrir une app…", null, { "" }, chevron = true) { open(Page.REMOTE_APPS) }
        row("Source HDMI…", null, { "" }, chevron = true) { open(Page.REMOTE_INPUTS) }
    }

    private fun remoteApps() {
        heading.text = "Ouvrir une app"
        about.text = "L'app que la touche ouvrira."
        for (a in repo.allApps().sortedBy { it.label.lowercase() }) row(a.label, null, { "" }) { chooseAction(KeyMap.APP + a.pkg) }
    }

    /** The TV's inputs, read off the UI thread (a few hundred ms on some firmwares). */
    private fun remoteInputs() {
        heading.text = "Source HDMI"
        about.text = "L'entrée sur laquelle la touche basculera."
        row("Recherche des entrées…", null, { "" }) {}
        Thread {
            val inputs = runCatching { tv.inputs() }.getOrDefault(emptyList())
            runOnUiThread {
                if (page != Page.REMOTE_INPUTS || isDestroyed) return@runOnUiThread
                list.removeAllViews()
                if (inputs.isEmpty()) row("Aucune entrée", "Ce téléviseur n'expose pas d'entrée HDMI à canvasOS (voir AGENTS.md, section 9).", { "" }) {}
                for (i in inputs) row(i.name, null, { if (i.connected) "Connectée" else "" }) { chooseAction(KeyMap.INPUT + InputTile.encode(i)) }
                list.post { centreList(); list.getChildAt(0)?.requestFocus() }
            }
        }.start()
    }

    /** A function chosen: the key being changed keeps it, a new shortcut asks for its key. */
    private fun chooseAction(action: String) {
        val e = remoteEdit
        if (e != null) {
            remoteEdit = null
            save(KeyMap.Binding(e.code, e.long, action), replacing = e)
            show(Page.REMOTE)
        } else learn(action, replacing = null)
    }

    /** Asks for the key: the next key pressed is read in [dispatchKeyEvent], Back cancels. */
    private fun learn(action: String, replacing: KeyMap.Binding?) {
        val sheet = Sheet(root).title("Appuyez sur la touche")
            .message("Un appui bref l'associe à l'appui simple, un appui maintenu à l'appui long. Retour annule." +
                if (prefs.captureKeys) "" else " Les touches Réglages et Profil demandent « Capter les touches du téléviseur ».")
        learning = Learning(action, replacing, sheet)
        learnCode = -1
        // With key capture on, the service hands every key here, those the TV would keep included.
        KeyCapture.learning = { e -> dispatchKeyEvent(e) }
        // Only Back (dispatchKeyEvent) or a key learnt dismiss it; both clear the lesson first.
        showSheet(sheet)
    }

    /** A key pressed ([long]: held) while learning: refused with the reason, confirmed when the key already does something, else bound. */
    private fun learnt(code: Int, long: Boolean) {
        val l = learning ?: return
        KeyMap.reserved(code, long)?.let { l.sheet.message("$it Essayez une autre touche, ou Retour pour annuler."); return }
        val b = KeyMap.Binding(code, long, l.action)
        val taken = KeyMap.find(bindings(), code, long)?.takeIf { l.replacing == null || !it.sameKey(l.replacing.code, l.replacing.long) }
        learning = null
        KeyCapture.learning = null
        page = Page.REMOTE
        l.sheet.dismiss()
        if (taken != null) {
            showSheet(Sheet(root).title("Réaffecter « ${KeyMap.keyName(code, long)} » ?")
                .message("Cette touche fait déjà « ${KeyMap.label(taken.action, ::boundName)} ».")
                .button("Réaffecter") { save(b, l.replacing) }
                .button("Annuler") {})
        } else save(b, l.replacing)
    }

    private fun save(b: KeyMap.Binding, replacing: KeyMap.Binding?) {
        var l = bindings()
        if (replacing != null) l = KeyMap.remove(l, replacing)
        prefs.keyMap = KeyMap.serialize(KeyMap.put(l, b))
    }

    /**
     * While a key is being learnt, every key is the lesson's: Back cancels, a held key binds its
     * long press at once, any other key binds its press on release. Nothing reaches the rows behind.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val l = learning ?: return super.dispatchKeyEvent(event)
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) { learning = null; KeyCapture.learning = null; l.sheet.dismiss() }
            return true
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) learnCode = code
                if (event.isLongPress && learnCode == code) { learnCode = -1; learnt(code, long = true) }
            }
            KeyEvent.ACTION_UP -> if (learnCode == code) { learnCode = -1; learnt(code, long = false) }
        }
        return true
    }

    /** Lecture et notifications: the Control Center player and the banners. */
    private fun playbackGroup() {
        section("Lecture")
        row("Lecture en cours", "Activée par défaut. Affiche dans le Centre de contrôle ce que lit une app (titre, artiste, pochette) avec lecture/pause et piste suivante. " +
            "Lit pour cela les informations de lecture des apps (titre, artiste, pochette, état de lecture) via l'écouteur de notifications ; rien n'est enregistré.",
            { if (!MediaSessions.isAvailable(this)) LISTENER_NEEDED else onOff(prefs.nowPlaying) }) { v ->
            // On by default: without the listener it shows as needing it, and OK then asks for it rather than switching off.
            if (prefs.nowPlaying && MediaSessions.isAvailable(this)) { prefs.nowPlaying = false; return@row }
            v.text = "Réglage…"
            Thread {
                val ok = MediaSessions.isAvailable(applicationContext) || (allowListener() && MediaSessions.isAvailable(applicationContext))
                if (ok) prefs.nowPlaying = true
                runOnUiThread { v.text = if (ok) onOff(true) else LISTENER_NEEDED }
            }.start()
        }
        row("Lecteur sur l'accueil", "Activé par défaut, avec Lecture en cours. En haut à gauche de l'accueil, une capsule montre la pochette, le titre et l'artiste " +
            "de ce qu'une app lit, sans commande : OK ouvre cette app ; lecture, pause et piste suivante restent dans le Centre de contrôle. " +
            "Rien de plus n'est lu pour elle.",
            { if (!prefs.nowPlaying) "Lecture en cours requise" else onOff(prefs.homePlayer) }) { v ->
            if (!prefs.nowPlaying) { Motion.bump(v, 1, 0); return@row }
            prefs.homePlayer = !prefs.homePlayer
        }
        row("Paroles", "Activées par défaut. À chaque nouveau titre lu, le téléviseur demande à LRCLIB (lrclib.net, gratuit, sans clé) s'il a ses paroles : " +
            "le titre, l'artiste, l'album et la durée du morceau sont envoyés, rien d'autre, et rien n'est enregistré. Quand il en a, un bouton Paroles sort à droite du " +
            "lecteur de l'accueil quand celui-ci est sélectionné : OK affiche les paroles synchronisées sur le fond assombri (les paroles non synchronisées ne sont pas proposées), OK encore les retire. " +
            "Désactivées, rien n'est envoyé à LRCLIB.",
            { if (!prefs.nowPlaying || !prefs.homePlayer) "Lecteur sur l'accueil requis" else onOff(prefs.lyrics) }) { v ->
            if (!prefs.nowPlaying || !prefs.homePlayer) { Motion.bump(v, 1, 0); return@row }
            prefs.lyrics = !prefs.lyrics
        }
        row("Position des paroles", "Où les lignes s'affichent sur l'accueil : à gauche sous le lecteur, au centre, ou à droite.", {
            when (prefs.lyricsAlign) { Prefs.LYRICS_CENTER -> "Centre"; Prefs.LYRICS_RIGHT -> "Droite"; else -> "Gauche" }
        }) { prefs.lyricsAlign = Prefs.LYRICS_ALIGNS[(Prefs.LYRICS_ALIGNS.indexOf(prefs.lyricsAlign) + 1) % Prefs.LYRICS_ALIGNS.size] }
        section("Notifications")
        row("Bannières de notifications", "Activées par défaut. Affiche en haut à droite les notifications des apps (sonnette, messages, domotique…). " +
            "Le titre et le texte des notifications de toutes les apps, sauf celles exclues, sont lus, affichés 6 secondes et jamais enregistrés. " +
            "Désactivées, aucune notification n'est lue.",
            { if (!Banners.listenerGranted(this)) LISTENER_NEEDED else onOff(prefs.banners) }) { v ->
            if (prefs.banners && Banners.listenerGranted(this)) {
                prefs.banners = false
                show(Page.PLAYBACK, list.indexOfChild(list.focusedChild).coerceAtLeast(0))
                return@row
            }
            v.text = "Réglage…"
            Thread {
                val ok = Banners.listenerGranted(applicationContext) || allowListener()
                // Over the app being watched, banners need the overlay permission the guard also uses.
                if (ok && !Settings.canDrawOverlays(applicationContext) && prefs.adbAuthorized)
                    LocalAdb.shell(applicationContext, "appops set $packageName SYSTEM_ALERT_WINDOW allow", 10_000)
                if (ok) prefs.banners = true
                runOnUiThread {
                    if (!ok) v.text = LISTENER_NEEDED
                    // "Apps exclues des bannières" appears below: the page is rebuilt where the focus is.
                    else if (page == Page.PLAYBACK && !isDestroyed) show(Page.PLAYBACK, list.indexOfChild(list.focusedChild).coerceAtLeast(0))
                }
            }.start()
        }
        if (prefs.banners) row("Apps exclues des bannières", "Les notifications des apps cochées ne sont ni lues ni affichées. Toutes les autres s'affichent en bannière." +
            if (Settings.canDrawOverlays(this)) "" else "\nSans l'autorisation d'affichage par-dessus les autres apps (étape 4), les bannières n'apparaissent que sur l'accueil.",
            { "${prefs.bannerExcluded.size}" }, chevron = true) { open(Page.BANNER_APPS) }
    }

    /** Mémoire et alimentation. */
    private fun memoryGroup() {
        section("Nettoyage")
        row("Libérer la mémoire en veille", "Ferme les apps en arrière-plan quand le téléviseur passe en veille, pour un redémarrage plus fluide. " +
            "Les apps qui lisent du son ou de la vidéo, ou qui font tourner un service (diffusion, serveur), sont épargnées.", { onOff(prefs.cleanOnStandby) }) {
            prefs.cleanOnStandby = !prefs.cleanOnStandby
        }
        row("Libération programmée", "Ferme régulièrement les apps en arrière-plan, sauf l'app à l'écran et celles qui lisent du son ou de la vidéo ou font tourner un service.", {
            if (prefs.cleanIntervalHours == 0) "Désactivée" else "Toutes les ${prefs.cleanIntervalHours} h"
        }) {
            val opts = listOf(0, 2, 4, 8)
            prefs.cleanIntervalHours = opts[(opts.indexOf(prefs.cleanIntervalHours) + 1) % opts.size]
            KeeperService.start(this, KeeperService.ACTION_RESCHEDULE)
        }
        row("Libérer la mémoire", "Ferme maintenant les apps en arrière-plan. Celles qui lisent du son ou de la vidéo, ou qui font tourner un service " +
            "(diffusion, serveur), sont épargnées.", { cleanResult ?: "${Cleaner.availMb(this)} Mo libres" }) { v ->
            v.text = "Libération…"
            Thread {
                val r = Cleaner.clean(applicationContext)
                runOnUiThread {
                    // Short: the label must keep its room. The whole summary is on "Dernier nettoyage".
                    cleanResult = if (r.viaAdb) "${r.freedMb} Mo · ${r.closedText}" else r.freedText
                    // "Dernier nettoyage" below changed too, so the page is rebuilt (where the focus is) rather than one value set.
                    if (page == Page.MEMORY && !isDestroyed) show(Page.MEMORY, list.indexOfChild(list.focusedChild).coerceAtLeast(0))
                }
            }.start()
        }
        row("Dernier nettoyage", if (prefs.lastClean.isEmpty()) "La mémoire n'a pas encore été libérée." else "${lastCleanWhat()}.\nAppuyez pour voir les apps fermées.",
            { lastCleanWhen() }, chevron = true) { open(Page.LAST_CLEAN) }
        row("Apps à conserver", "Apps que la libération de la mémoire ne ferme jamais, comme la diffusion d'écran. " +
            "Celles qui lisent du son ou de la vidéo, ou qui font tourner un service, sont de toute façon épargnées tant qu'elles le font.", { "${prefs.keep.size}" }, chevron = true) {
            open(Page.KEEP)
        }
        section("Alimentation")
        row("Bouton marche/arrêt", "Veille : comme d'habitude. Arrêt complet : économise l'énergie, mais le démarrage est plus long.", {
            if (prefs.shutdownOnPowerKey) "Arrêt complet" else "Veille"
        }) {
            prefs.shutdownOnPowerKey = !prefs.shutdownOnPowerKey
            if (prefs.shutdownOnPowerKey && !prefs.adbAuthorized)
                Toast.makeText(this, "Autorisez d'abord le débogage local pour l'activer", Toast.LENGTH_LONG).show()
        }
    }

    /** Apps: hidden and removed apps, folders. */
    private fun appsGroup() {
        section("Apps")
        row("Apps masquées", "Apps qui ne s'affichent pas sur l'écran d'accueil.", { "${prefs.hidden.size}" }, chevron = true) { open(Page.HIDDEN) }
        row("Apps préinstallées supprimées", "Apps préinstallées supprimées depuis le menu d'appui long ; vous pouvez les restaurer ici.", { "${prefs.removed.size}" }, chevron = true) {
            open(Page.REMOVED)
        }
        section("Dossiers")
        row("Dossiers", "Les dossiers de l'accueil et leurs apps. Ils se créent et se gèrent depuis l'accueil, par un appui long sur une app ou un dossier.",
            { "${prefs.folderList().size}" }, chevron = true) { open(Page.FOLDERS) }
    }

    /** Profils. */
    private fun accountsGroup() {
        section("Profils")
        row("Profil actuel", "Le profil dont l'accueil est affiché. Changez-en avec la photo de profil, en haut à droite de l'accueil.",
            { Profiles.displayName(prefs.current()) }) {}
        row("Gérer les profils", PROFILES_ABOUT, { "${prefs.profileList().size}" }, chevron = true) { open(Page.PROFILES) }
    }

    /** Page téléphone. */
    private fun phoneGroup() {
        section("Accès")
        row("Afficher le QR code", "Scannez-le avec un téléphone sur le même Wi-Fi. La page qui s'ouvre sert de télécommande avec clavier, importe des photos et des liens d'images, règle l'animation de démarrage " +
            "et la météo, et installe des apps (APK). La télécommande et l'installation d'apps demandent le débogage local.",
            { "" }, chevron = true) { open(Page.PHONE) }
        section("Sécurité")
        row("Régénérer le code", "Change le code secret de l'adresse : les téléphones qui connaissaient l'ancienne ne peuvent plus s'en servir et doivent scanner à nouveau. " +
            "Faites-le si un téléphone qui a ouvert la page est perdu.", { if (tokenRenewed) "Nouveau code créé" else "" }) {
            PhoneServer.regenerate(this)
            tokenRenewed = true
        }
        row("Page téléphone", "Le code dans l'adresse est la seule protection : toute personne qui le connaît, sur le même réseau, peut commander le téléviseur. " +
            "Désactivée, la page ne répond plus du tout.", { onOff(prefs.phoneServer) }) {
            prefs.phoneServer = !prefs.phoneServer
            KeeperService.start(this)
        }
    }

    /** Système: debugging, the home guard, the TV's own settings, updates, the version. */
    private fun systemGroup() {
        section("Débogage")
        row("Débogage local", "Permet à l'accueil d'éteindre le téléviseur, de libérer la mémoire, etc. via le débogage local. Dans la fenêtre qui s'affiche, choisissez « Toujours autoriser ».",
            { if (prefs.adbAuthorized) "Autorisé" else "Non autorisé, appuyez sur OK" }) { v ->
            v.text = "Choisissez Toujours autoriser…"
            Thread {
                val ok = LocalAdb.shell(applicationContext, "echo ok", 60_000) != null
                runOnUiThread { v.text = if (ok) "Autorisé" else "Non autorisé, OK pour réessayer" }
            }.start()
        }
        row("Débogage ADB", "Doit être activé pour qu'un ordinateur puisse se connecter au téléviseur : sans lui, pas d'installation ni de mise à jour par le réseau. " +
            "Il se règle dans les options pour les développeurs du téléviseur.",
            { onOff(Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1) }) {}
        if (Guard.isXiaomi() || prefs.keepAdb) row("Garder le débogage ADB activé", "Empêche le système de désactiver le débogage ADB.", { onOff(prefs.keepAdb) }) { v ->
            val on = !prefs.keepAdb
            v.text = "Réglage…"
            Thread {
                val ok = !on || Guard.hasSecureSettings(this) || Guard.grantViaLocalAdb(applicationContext)
                if (ok) { prefs.keepAdb = on; Guard.ensureAdb(applicationContext) }
                runOnUiThread { v.text = if (ok) onOff(prefs.keepAdb) else "Débogage local requis" }
            }.start()
        }
        row("Adresse IP", "L'adresse du téléviseur sur le réseau local. Un ordinateur sur le même réseau s'en sert pour s'y connecter (adb connect, port 5555), " +
            "par exemple pour installer ou mettre à jour canvasOS.", { Net.lanAddress(this) ?: "Non connecté" }) {}
        // Only where the TV keeps Home for its own launcher; on Sony or stock Android TV canvasOS is the real home.
        if (Guard.needed(this)) {
            section("Protection")
            row("Protection de l'accueil", "Quand la touche Accueil ramène au lanceur d'origine du téléviseur, revient automatiquement ici.",
                { if (!prefs.guard) "Désactivée" else if (HomeGuardService.isRunning) "Activée" else "Activée (inactive)" }) { v ->
                val on = !prefs.guard
                v.text = "Réglage…"
                Thread {
                    val ok = (Guard.hasSecureSettings(this) || Guard.grantViaLocalAdb(applicationContext)) && Guard.setEnabled(applicationContext, on)
                    runOnUiThread {
                        v.text = if (!ok) "Débogage local requis" else if (on) "Activée" else "Désactivée"
                        if (ok && on) Toast.makeText(this, "La touche Accueil ramènera désormais ici au lieu du lanceur d'origine", Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
        }
        section("Téléviseur")
        row("Réglages du téléviseur", "Ouvre les réglages d'origine du téléviseur.", { "" }, chevron = true) { runCatching { startActivity(TvControls(this).settingsIntent()) } }
        section("Mise à jour")
        row("Mise à jour", "Cherche la dernière version de canvasOS sur GitHub (Releases) et l'installe sur place : les réglages et les autorisations sont conservés. " +
            "Appuyez une première fois pour vérifier, une seconde pour installer. L'installation demande le débogage local. " +
            "canvasOS cherche aussi chaque jour qu'une version existe, sans rien télécharger.", { updateValue() }) { v -> updatePressed(v) }
        section("À propos")
        row("À propos", "canvasOS, un écran d'accueil Android TV inspiré de tvOS.",
            { "Version " + packageManager.getPackageInfo(packageName, 0).versionName }) {}
    }

    /** The newer Release the last check in this screen found (what a second press installs), or null. */
    private var foundUpdate: Updater.Update? = null

    /** What the "Mise à jour" row says after a check or a failure ("À jour (1.1.0)", "Releases inaccessibles"); null: what the saved state says. */
    private var updateNote: String? = null

    private fun updateValue(): String = Updater.working ?: updateNote
        ?: Updater.pending(prefs)?.let { "$it disponible, OK pour installer" } ?: "Vérifier"

    /**
     * The "Mise à jour" row. Pressed once it asks GitHub; when that finds a newer version it says
     * so, and the next press (or this one, if the row already said so) downloads and installs it.
     * Installing replaces canvasOS, so the screen usually vanishes with the process.
     */
    private fun updatePressed(v: TextView) {
        if (v.text.endsWith("…")) return
        // What the row said when it was pressed: a version already on offer is installed without asking again.
        val offered = foundUpdate != null || (updateNote == null && Updater.pending(prefs) != null)
        fun say(text: String, detail: String? = null) = runOnUiThread {
            v.text = text
            if (detail != null) about.text = detail
        }
        v.text = "Vérification…"
        Thread {
            val update = foundUpdate ?: when (val c = Updater.check(applicationContext)) {
                is Updater.Check.UpToDate -> {
                    updateNote = "À jour (${c.version})"
                    return@Thread say(updateNote!!, "canvasOS ${c.version} est la dernière version publiée.")
                }
                is Updater.Check.Unavailable -> {
                    updateNote = "Releases inaccessibles"
                    return@Thread say(updateNote!!, c.reason)
                }
                is Updater.Check.Available -> c.update
            }
            foundUpdate = update
            updateNote = null
            val mb = if (update.size > 0) " (${(update.size + 524_288) / 1_048_576} Mo)" else ""
            val notes = if (update.notes.isEmpty()) "" else "\n\n" + update.notes.take(400)
            if (!offered) return@Thread say(updateValue(), "canvasOS ${update.version}$mb est disponible. Appuyez sur OK pour le télécharger et l'installer.$notes")
            if (!prefs.adbAuthorized) {
                say("Choisissez Toujours autoriser…")
                if (LocalAdb.shell(applicationContext, "echo ok", 60_000) == null) {
                    updateNote = "Débogage local requis"
                    return@Thread say(updateNote!!, "L'installation passe par le débogage local : autorisez-le (ligne « Débogage local » plus haut), puis réessayez.")
                }
            }
            Updater.downloadAndInstall(applicationContext, update) { say(it) }
                .onSuccess {
                    // Normally the process is gone by now.
                    updateNote = "Installée, redémarrage…"
                    say(updateNote!!)
                }
                .onFailure {
                    updateNote = "Échec, OK pour réessayer"
                    say(updateNote!!, it.message)
                }
        }.start()
    }

    private fun appPicker(title: String, selected: Set<String>, includeHidden: Boolean = false, source: List<AppEntry>? = null, toggle: (String, Boolean) -> Unit) {
        heading.text = title
        about.text = if (page == Page.KEEP) "La libération de la mémoire ne ferme pas les apps cochées." else "Les apps cochées n'apparaissent pas sur l'écran d'accueil."
        val apps = source ?: if (includeHidden) repo.allApps().sortedBy { it.label } else repo.allApps().filter { !repo.isSystem(it.pkg) }.sortedBy { it.label }
        val sel = selected.toMutableSet()
        for (a in apps) {
            row(a.label, null, { if (a.pkg in sel) "✓" else "" }) { v ->
                val on = a.pkg !in sel
                if (on) sel += a.pkg else sel -= a.pkg
                toggle(a.pkg, on)
                v.text = if (on) "✓" else ""
            }
        }
    }

    /**
     * The apps whose notifications never become banners (ticked = excluded): every app on the TV,
     * system ones included (a firmware app may be chatty), and any package excluded over adb.
     */
    private fun bannerAppsPage() {
        val listed = repo.allApps().filter { !repo.isOwn(it.pkg) }
        val known = listed.mapTo(HashSet()) { it.pkg }
        val more = (prefs.bannerExcluded - known).mapNotNull { pkg ->
            runCatching {
                AppEntry(pkg, packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString(), android.content.ComponentName(pkg, ""), false)
            }.getOrNull()
        }
        appPicker("Apps exclues des bannières", prefs.bannerExcluded, source = (listed + more).sortedBy { it.label.lowercase() }) { pkg, on ->
            prefs.bannerExcluded = if (on) prefs.bannerExcluded + pkg else prefs.bannerExcluded - pkg
        }
        about.text = "Les notifications des apps cochées ne sont ni lues ni affichées. Celles des autres apps s'affichent 6 secondes en haut à droite."
    }

    /** Approves the notification listener through local adb, as AGENTS.md step 4 does from a computer. Blocking. */
    private fun allowListener(): Boolean {
        if (!prefs.adbAuthorized) return false
        LocalAdb.shell(applicationContext, "cmd notification allow_listener $packageName/$packageName.RevivalListener; echo done", 10_000) ?: return false
        return Banners.listenerGranted(applicationContext)
    }

    /**
     * The home grid's folders with what they show, read-only: they are made and changed on the home
     * screen itself (long press), where the apps are.
     */
    private fun foldersPage() {
        heading.text = "Dossiers"
        about.text = "Les dossiers de l'accueil, dans leur ordre, avec le nombre d'apps qu'ils affichent."
        // What the grid shows: installed apps that are not hidden.
        val folders = repo.orderedVisible().filter { it.folder != null }
        if (folders.isEmpty()) row("Aucun dossier", null, { "" }) {}
        for (f in folders) {
            val apps = f.folder.orEmpty()
            row(f.label, if (apps.isEmpty()) "Ce dossier est vide." else apps.joinToString(", ") { it.label },
                { if (apps.size < 2) "${apps.size} app" else "${apps.size} apps" }) {}
        }
        row("Les dossiers se gèrent depuis l'accueil (appui long)", "Sur l'accueil, appuyez longuement sur une app : « Nouveau dossier… » " +
            "la range dans un nouveau dossier, « Ajouter au dossier… » dans un dossier existant. Appuyez longuement sur un dossier pour " +
            "le renommer, y ajouter des apps, le déplacer ou le supprimer ; ses apps reviennent alors sur l'accueil.", { "" }) {}
    }

    /**
     * The profiles: one row each (pressing it renames, recolours, makes it a child's or deletes it),
     * then a new one and the PIN. Every change is saved at once; the page is drawn again after.
     */
    private fun profilesPage() {
        heading.text = "Profils"
        about.text = PROFILES_ABOUT
        val current = prefs.currentProfile
        for (p in prefs.profileList()) {
            val desc = listOfNotNull(
                if (p.id == current) "Le profil affiché en ce moment." else null,
                if (p.restricted) "Profil enfant : le code PIN est demandé pour en sortir, ouvrir les réglages ou supprimer une app ; « Masquer » et « Infos sur l'app » n'y sont pas proposés." else null,
                if (p.isMain) "Le profil principal : celui de l'accueil d'avant les profils. Il ne peut pas être supprimé." else null,
            ).joinToString("\n").ifEmpty { "Appuyez pour le renommer, changer sa couleur, en faire un profil enfant ou le supprimer." }
            row(Profiles.displayName(p), desc, { if (p.restricted) "Enfant" else "" }, picture = ProfileAvatar.drawable(this, p, ROW_AVATAR_DP)) { profileMenu(p) }
        }
        row("Nouveau profil…", "Un profil de plus, avec son propre accueil. Il commence avec l'ordre des apps par défaut, sans apps masquées ni dossiers, " +
            "et avec le fond d'écran du profil principal.", { "" }) {
            showSheet(Sheet(root).title("Nouveau profil").message("Le nom du profil, par exemple « Enfants ».")
                .input("", Profiles.DEFAULT_NAME) { name -> prefs.addProfile(name) })
        }
        row("Code PIN", "Demandé dans un profil enfant pour en sortir, ouvrir les réglages ou supprimer une app. De ${Profiles.PIN_MIN} à ${Profiles.PIN_MAX} chiffres ; " +
            "le modifier ou le supprimer demande le code actuel. Oublié, il se supprime depuis un ordinateur (adb).",
            { if (prefs.hasPin) "Défini" else "Aucun" }) { pinMenu() }
    }

    private fun profileMenu(p: Profile) {
        val name = Profiles.displayName(p)
        val s = Sheet(root).title(name)
            .message(if (prefs.hasPin) null else "Un profil enfant n'est protégé qu'une fois un code PIN défini (ligne « Code PIN » de cette page).")
            .button("Renommer") {
                showSheet(Sheet(root).title("Renommer « $name »").message(if (p.isMain) "Laissez le champ vide pour reprendre le nom « ${Profiles.MAIN_NAME} »." else null)
                    .input(p.name, if (p.isMain) Profiles.MAIN_NAME else Profiles.DEFAULT_NAME) { text -> prefs.editProfiles { Profiles.rename(it, p.id, text) } })
            }
            .cycle("Couleur", Profiles.COLOR_NAMES, p.color) { c -> prefs.editProfiles { Profiles.setColor(it, p.id, c) } }
            .button("Photo…") { photoHelp(p) }
        if (Profiles.photoFile(this, p.id) != null) s.button("Supprimer la photo") {
            Profiles.clearPhoto(this, p.id)
        }
        s.toggle("Profil enfant", p.restricted) { on -> prefs.editProfiles { Profiles.setRestricted(it, p.id, on) } }
        if (Profiles.canDelete(prefs.profileList(), p.id, prefs.currentProfile)) s.button("Supprimer") {
            showSheet(Sheet(root).title("Supprimer « $name » ?").message("Son accueil (ordre des apps, apps masquées, dossiers, noms, fond d'écran) est effacé. Les apps elles-mêmes restent installées.")
                .button("Supprimer") { prefs.deleteProfile(p.id) }
                .button("Annuler") {})
        }
        showSheet(s.button("Annuler") {})
    }

    /**
     * "Photo…": a picture cannot be chosen with a remote, so this says where it comes from: the phone
     * page, or a computer (the path carries the profile's id).
     */
    private fun photoHelp(p: Profile) {
        val name = Profiles.displayName(p)
        showSheet(Sheet(root).title("Photo de « $name »")
            .message("Sans photo, le profil montre l'initiale de son nom sur sa couleur.\n\n" +
                "Depuis un téléphone : ouvrez la page téléphone (Réglages de l'accueil → Page téléphone), onglet Réglages, section Profils, puis « Changer la photo ».\n\n" +
                "Depuis un ordinateur : adb push photo.jpg /sdcard/Android/data/$packageName/files/${Profiles.AVATARS_DIR}/${p.id}.jpg")
            .button("OK") {})
    }

    /** "Code PIN": set one, or change or remove it after giving the current one. */
    private fun pinMenu() {
        if (!prefs.hasPin) { pins.choose("Définir le code PIN"); return }
        showSheet(Sheet(root).title("Code PIN").message(null)
            .button("Modifier") { pins.ask("Code actuel") { pins.choose("Nouveau code PIN") } }
            .button("Supprimer") {
                pins.ask("Code actuel") {
                    showSheet(Sheet(root).title("Supprimer le code PIN ?").message("Les profils enfants ne seront plus protégés : on pourra en sortir et ouvrir les réglages sans code.")
                        .button("Supprimer") { prefs.setPin(null) }
                        .button("Annuler") {})
                }
            }
            .button("Annuler") {})
    }

    /** Preinstalled apps deleted from the long-press menu; selecting one reinstalls it from the system partition. */
    private fun removedApps() {
        heading.text = "Apps préinstallées supprimées"
        about.text = "Choisissez une app pour la réinstaller depuis la partition système."
        if (prefs.removed.isEmpty()) row("Aucune app supprimée", null, { "" }) {}
        for (pkg in prefs.removed.sorted()) {
            val label = try {
                packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES).loadLabel(packageManager).toString()
            } catch (e: Exception) { pkg }
            row(label, null, { "Restaurer" }) { v ->
                v.text = "Restauration…"
                Thread {
                    val ok = LocalAdb.shell(applicationContext, "cmd package install-existing $pkg")?.contains("installed") == true
                    if (ok) prefs.removed = prefs.removed - pkg
                    runOnUiThread { v.text = if (ok) "Restaurée" else "Échec" }
                }.start()
            }
        }
    }

    /** The phone page's QR code, large enough to scan from the sofa, with its address. */
    private fun phonePage() {
        heading.text = "Page téléphone"
        about.text = "Sur le même Wi-Fi, scannez le code avec l'appareil photo d'un téléphone ; la page reste accessible à tout moment. " +
            "Télécommande et clavier, photos et liens d'images, animation de démarrage, installation d'apps (APK).\n" +
            "Le code dans l'adresse est la seule protection : régénérez-le si un téléphone qui a ouvert la page est perdu ; " +
            "les téléphones qui connaissaient l'ancien devront scanner à nouveau."
        val qr = ImageView(this).apply {
            background = rounded(Color.WHITE, u(ROW_RADIUS).toFloat())
            setPadding(u(12), u(12), u(12), u(12))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val address = label(15f, DESCRIPTION).apply { gravity = Gravity.CENTER_HORIZONTAL }
        // The same page by name (Mdns): the QR code stays on the IP, which every phone resolves.
        val byName = label(13f, DESCRIPTION).apply { gravity = Gravity.CENTER_HORIZONTAL }
        list.addView(qr, LinearLayout.LayoutParams(u(210), u(210)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        list.addView(address, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u(12) })
        list.addView(byName, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u(4); bottomMargin = u(16) })
        fun load() {
            address.text = "Démarrage…"
            byName.visibility = android.view.View.GONE
            Thread {
                // Usually already running (KeeperService); the address is read again, Wi-Fi may have changed.
                PhoneServer.start(applicationContext)
                val url = PhoneServer.url(applicationContext)
                val host = url?.let { PhoneServer.hostUrl(applicationContext) }
                val bmp = url?.let { PhoneServer.qr(it) }
                val off = if (url == null) PhoneServer.unavailable(applicationContext) else null
                runOnUiThread {
                    qr.setImageBitmap(bmp)
                    qr.visibility = if (bmp != null) android.view.View.VISIBLE else android.view.View.GONE
                    address.text = url ?: off
                    byName.text = host?.let { "Aussi : $it (iPhone et Android récents)" }
                    byName.visibility = if (host != null) android.view.View.VISIBLE else android.view.View.GONE
                }
            }.start()
        }
        load()
        // No description of its own: the page's explanation stays under the icon.
        row("Régénérer le code", null, { "" }) { v ->
            PhoneServer.regenerate(this)
            tokenRenewed = true
            v.text = "Nouveau code créé"
            load()
        }
    }

    /** When the last memory cleanup ran ("22 oct. 21:40"), from [Prefs.lastClean] ("22 oct. 21:40, 312 Mo libérés, 4 apps fermées"). */
    private fun lastCleanWhen() = prefs.lastClean.substringBefore(", ").ifEmpty { "Jamais" }

    /** What it did: "312 Mo libérés, 4 apps fermées". */
    private fun lastCleanWhat() = prefs.lastClean.substringAfter(", ", "")

    /** The apps the last memory cleanup closed, by name. Nothing here does anything; Back returns. */
    private fun lastCleanPage() {
        heading.text = "Dernier nettoyage"
        val cacheOnly = lastCleanWhat().endsWith("(cache uniquement)")
        about.text = when {
            prefs.lastClean.isEmpty() -> "La mémoire n'a pas encore été libérée."
            cacheOnly -> "${lastCleanWhen()} : ${lastCleanWhat()}.\nSans le débogage local, seules les apps en cache ont été arrêtées, et Android ne dit pas lesquelles."
            else -> "${lastCleanWhen()} : ${lastCleanWhat()}.\nLes apps à conserver, et celles qui lisaient du son ou de la vidéo ou faisaient tourner " +
                "un service (diffusion, serveur), n'ont pas été fermées."
        }
        val labels = prefs.lastCleanClosed.split(',').filter { it.isNotBlank() }.map { pkg ->
            runCatching { packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString() }.getOrDefault(pkg)
        }.sortedBy { it.lowercase() }
        if (labels.isEmpty()) row(if (cacheOnly) "Détail indisponible" else "Aucune app fermée", null, { "" }) {}
        for (label in labels) row(label, null, { "" }) {}
    }

    /** A small grey uppercase title above a run of rows, as tvOS splits a settings page ("AUTO-PLAY", "HOME SCREEN"). */
    private fun section(title: String) {
        val first = list.childCount == 0
        list.addView(label(HEADER_SIZE, HEADER, 600).apply {
            text = title.uppercase(java.util.Locale.FRENCH)
            letterSpacing = 0.02f
            tag = HEADER_TAG
        }, LinearLayout.LayoutParams(-2, -2).apply {
            // Lined up with the rows' labels; the row above already keeps its gap below it.
            marginStart = u(ROW_PAD); topMargin = if (first) 0 else u(HEADER_ABOVE); bottomMargin = u(HEADER_BELOW)
        })
    }

    /**
     * One settings row; [value] is re-read after each click, [desc] shows under the icon while
     * the row is focused, and [chevron] marks a row that opens another screen.
     */
    private fun row(label: String, desc: String?, value: () -> String, chevron: Boolean = false, picture: android.graphics.drawable.Drawable? = null, onClick: (TextView) -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(u(ROW_PAD), 0, u(if (chevron) ROW_PAD + 2 else ROW_PAD), 0)
        }
        val l = label(18.5f, IDLE_TEXT, 500).apply {
            text = label
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val v = label(18.5f, IDLE_VALUE).apply {
            text = value()
            maxLines = 1
        }
        picture?.let { d -> r.addView(ImageView(this).apply { setImageDrawable(d) }, LinearLayout.LayoutParams(u(ROW_AVATAR), u(ROW_AVATAR)).apply { marginEnd = u(10) }) }
        r.addView(l, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(v, LinearLayout.LayoutParams(-2, -2).apply { marginStart = u(12) })
        val arrow = if (chevron) Chevron(IDLE_VALUE, maxOf(1.6f * unit, 1.5f)) else null
        arrow?.let { r.addView(android.view.View(this).apply { background = it }, LinearLayout.LayoutParams(u(8), u(15)).apply { marginStart = u(12) }) }
        val idle = rounded(IDLE_ROW, u(ROW_RADIUS).toFloat())
        val focus = rounded(Color.WHITE, u(ROW_RADIUS).toFloat())
        r.background = idle
        r.setOnFocusChangeListener { _, f ->
            r.background = if (f) focus else idle
            l.setTextColor(if (f) Color.BLACK else IDLE_TEXT)
            v.setTextColor(if (f) FOCUS_VALUE else IDLE_VALUE)
            arrow?.color = if (f) FOCUS_VALUE else IDLE_VALUE
            if (f && desc != null) about.text = desc
            // A slight lift with a soft shadow under it, as the focused row on tvOS.
            r.tweenScale(if (f) 1.02f else 1f, 160, z = if (f) u(6).toFloat() else 0f)
        }
        r.setOnClickListener {
            val from = page
            onClick(v)
            // On a group page the value shows what the press changed; list pages set their own.
            if (page == from && from.title != null && !v.text.endsWith("…")) v.text = value()
        }
        list.addView(r, LinearLayout.LayoutParams(-1, u(ROW_H)).apply { bottomMargin = u(ROW_GAP) })
    }

    /** tvOS's thin chevron at the end of a row that opens a page: two strokes with round ends. */
    private class Chevron(color: Int, stroke: Float) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            this.color = color
        }
        var color: Int
            get() = paint.color
            set(v) { paint.color = v; invalidateSelf() }

        override fun draw(c: android.graphics.Canvas) {
            val b = bounds
            val i = paint.strokeWidth / 2
            c.drawPath(android.graphics.Path().apply {
                moveTo(b.left + i, b.top + i)
                lineTo(b.right - i, b.exactCenterY())
                lineTo(b.left + i, b.bottom - i)
            }, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun onOff(b: Boolean) = if (b) "Activé" else "Désactivé"

    /** "自动（…）" with what auto picks on this TV, or the forced choice. */
    private fun auto(pref: String, on: Boolean, yes: String, no: String) =
        if (pref == Prefs.AUTO) "Auto (${if (on) yes else no})" else if (on) yes else no

    /** The next of 自动 → [a] → [b]. */
    private fun next(pref: String, a: String, b: String) = when (pref) { Prefs.AUTO -> a; a -> b; else -> Prefs.AUTO }

    /** A secure setting, written directly once WRITE_SECURE_SETTINGS is granted, otherwise through local adb. Blocking. */
    private fun putSecure(key: String, value: String): Boolean =
        if (Guard.hasSecureSettings(this)) runCatching { Settings.Secure.putString(contentResolver, key, value) }.getOrDefault(false)
        else LocalAdb.shell(applicationContext, "settings put secure $key $value") != null

    private fun minutesLabel(m: Int) = when {
        m <= 0 -> "Jamais"
        m >= 1440 -> "Chaque jour"
        m >= 60 -> "${m / 60} h"
        else -> "$m min"
    }

    /** On Android TV the screensaver starts after the "screen off" timeout. */
    private fun idleMinutes() = Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 600_000) / 60_000

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && sheets.isNotEmpty()) {
            sheets.last().dismiss()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && page != Page.MAIN) {
            // Up one level, onto the row that opened this page; the page the screen was opened on leaves.
            val up = page.parent
            if (page == entry || up == null) finish() else show(up, openers[page] ?: 0)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK && (page != Page.MAIN || sheets.isNotEmpty())) true else super.onKeyDown(keyCode, event)
}
