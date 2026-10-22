package com.getisolated.canvasos

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** The first-launch assistant's rules that need no Android, kept apart for the unit tests. */
object Onboarding {
    /** Apps the dock page lets the user pick: the dock is the grid's first row. */
    const val DOCK = 5

    /**
     * The grid order once [ticked] apps make the dock: them first, in the order they were ticked
     * (at most [DOCK]), then the grid as it [visible]ly stands, then the rest of [saved] (hidden
     * apps, apps in folders), so those keep their places. Nothing ticked leaves [saved] as it was:
     * an empty order is the default one ([Prefs.DEFAULT_ORDER]).
     */
    fun dockOrder(ticked: List<String>, visible: List<String>, saved: List<String>): List<String> =
        if (ticked.isEmpty()) saved else (ticked.distinct().take(DOCK) + visible + saved).distinct()
}

/**
 * The first-launch assistant, in the manner of a new Apple TV's setup: full-screen pages that pick
 * the clock, the background, the dock's apps, the weather, the main profile's name and colour, and
 * ask for local debugging. [HomeActivity] opens it on a fresh install while [Prefs.setupDone] is
 * false, and again when Home closed it on the way; it cannot be skipped (Back on the first page
 * does nothing). Every choice is saved as it is made, so leaving halfway loses nothing, and each
 * can be changed later in Réglages de l'accueil or with CONFIG.
 *
 * Only the D-pad, OK and Back are needed: Left and Right move within a page, Down reaches
 * « Précédent » / « Continuer », Back goes to the previous page. The installing agent follows it
 * with screencap and uiautomator, so every button carries its label as content description.
 */
class OnboardingActivity : Activity() {
    private val prefs by lazy { Prefs(this) }
    private val repo by lazy { AppRepo(this) }
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    /** Photo downloads, which can take a while: kept off [io] so previews and tiles keep coming. */
    private val net = Executors.newSingleThreadExecutor()

    private lateinit var backdrop: ImageView
    private lateinit var content: LinearLayout
    private lateinit var dots: LinearLayout
    private lateinit var title: TextView
    private lateinit var lead: TextView
    private lateinit var body: FrameLayout
    private lateinit var hint: TextView
    private lateinit var back: PillButton
    private lateinit var next: PillButton

    private var page = WELCOME
    @Volatile private var shownBackdrop: String? = null

    private val wallCards = LinkedHashMap<String, PickCard>()

    /** The dock page: the grid's entries (loaded once), their tiles, and the ticked ones in order. */
    private var dockApps: List<AppEntry>? = null
    private var dockLoading = false
    private val dockTiles = HashMap<String, Bitmap>()
    private val dockCards = HashMap<String, PickCard>()
    private val ticked = ArrayList<String>()
    /** The grid order before the dock page changed it; nothing ticked puts it back. */
    private var savedOrder: List<String>? = null

    private var adbWaiting = false
    private var adbRefused = false
    private var adbStatus: TextView? = null
    private var adbButton: PillButton? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Tween.readScale(this)
        val match = { FrameLayout.LayoutParams(-1, -1) }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(backdrop, match())
        // Dark enough for white text over any photo; darker at the bottom, where the buttons are.
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(150, 0, 0, 0), Color.argb(170, 0, 0, 0), Color.argb(225, 0, 0, 0)))
        }, match())

        dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        repeat(PAGES) { dots.addView(View(this), LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginStart = dp(5); marginEnd = dp(5) }) }
        title = text(34f, bold = true).apply { gravity = Gravity.CENTER }
        lead = text(17f, Color.argb(195, 255, 255, 255)).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
        }
        body = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        hint = text(14f, Color.argb(150, 255, 255, 255)).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
        }
        back = pill("Précédent") { go(page - 1) }
        next = pill("Continuer") { go(page + 1) }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            addView(back, LinearLayout.LayoutParams(dp(230), dp(52)).apply { marginEnd = dp(20) })
            addView(next, LinearLayout.LayoutParams(dp(230), dp(52)))
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(56), dp(26), dp(56), dp(26))
            addView(dots, LinearLayout.LayoutParams(-2, -2))
            addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
            addView(lead, LinearLayout.LayoutParams(dp(700), -2).apply { topMargin = dp(10) })
            addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(20) })
            addView(hint, LinearLayout.LayoutParams(dp(740), -2).apply { topMargin = dp(10) })
            addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        }
        root.addView(content, match())
        setContentView(root)
        showBackdrop()
        show(savedInstanceState?.getInt(STATE_PAGE) ?: resumeAt)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt(STATE_PAGE, page)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        io.shutdown()
        net.shutdown()
        super.onDestroy()
    }

    /** Back is the previous page; on the first one it does nothing, the assistant has to be finished. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) go(page - 1)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------ pages

    private fun go(to: Int) {
        if (to < 0 || to == page) return
        if (to >= PAGES) return finishSetup()
        hideKeyboard()
        show(to)
    }

    private fun show(p: Int) {
        page = p.coerceIn(0, PAGES - 1)
        resumeAt = page
        body.removeAllViews()
        wallCards.clear()
        dockCards.clear()
        adbStatus = null
        adbButton = null
        for (i in 0 until dots.childCount) dots.getChildAt(i).background =
            rounded(if (i == page) Color.WHITE else Color.argb(70, 255, 255, 255), dp(4).toFloat())
        val welcome = page == WELCOME
        title.visibility = if (welcome) View.GONE else View.VISIBLE
        lead.visibility = if (welcome) View.GONE else View.VISIBLE
        back.visibility = if (welcome) View.GONE else View.VISIBLE
        setHint(null)
        setNext(when (page) { WELCOME -> "Commencer"; DONE -> "Ouvrir l'accueil"; else -> "Continuer" })
        val focus: View? = when (page) {
            WELCOME -> welcomePage()
            CLOCK -> clockPage()
            WALLPAPER -> wallpaperPage()
            DOCK -> dockPage()
            WEATHER -> weatherPage()
            PROFILE -> profilePage()
            ADB -> adbPage()
            else -> donePage()
        }
        (focus ?: next).requestFocus()
        content.alpha = 0f
        content.animate().alpha(1f).setDuration(220).start()
    }

    private fun heading(t: String, l: String) {
        title.text = t
        lead.text = l
    }

    private fun setHint(s: String?) {
        hint.text = s.orEmpty()
        hint.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun setNext(label: String) {
        next.setLabel(label)
        next.contentDescription = label
    }

    private fun welcomePage(): View? {
        val col = column()
        col.addView(TextView(this).apply {
            text = "canvasOS"
            setTextColor(Color.WHITE)
            typeface = Fonts.inter(this@OnboardingActivity, 700)
            textSize = 76f
            letterSpacing = -0.02f
            includeFontPadding = false
            gravity = Gravity.CENTER
        })
        col.addView(text(19f, Color.argb(210, 255, 255, 255)).apply {
            text = "Bienvenue. Les étapes suivantes prennent une minute ; tout pourra être changé plus tard dans Réglages de l'accueil."
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }, LinearLayout.LayoutParams(dp(620), -2).apply { topMargin = dp(26) })
        body.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        return next
    }

    private fun clockPage(): View? {
        heading("Horloge", "Le format de l'heure et la place de l'horloge sur l'accueil.")
        val col = column()
        val preview = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Fonts.inter(this@OnboardingActivity, 250)
            textSize = 46f
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
        fun refresh() {
            preview.text = SimpleDateFormat(if (prefs.clock24) "HH:mm" else "h:mm a", Locale.US).format(Date())
            preview.alpha = if (prefs.clockStyle == Prefs.CLOCK_OFF) 0.3f else 1f
        }
        refresh()
        col.addView(preview)
        val (format, focus) = choices(listOf("24 h", "12 h"), if (prefs.clock24) 0 else 1, 170) {
            prefs.clock24 = it == 0
            refresh()
        }
        col.addView(caption("Format de l'heure"), captionParams())
        col.addView(format)
        val styles = listOf(Prefs.CLOCK_CORNER, Prefs.CLOCK_LARGE, Prefs.CLOCK_OFF)
        val (style, _) = choices(listOf("En haut à droite", "Grande, à gauche", "Masquée"), styles.indexOf(prefs.clockStyle).coerceAtLeast(0), 250) {
            prefs.clockStyle = styles[it]
            refresh()
        }
        col.addView(caption("Position"), captionParams())
        col.addView(style)
        body.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
        return focus
    }

    private fun wallpaperPage(): View? {
        heading("Fond d'écran", "Le fond de l'accueil ; le cadre photo utilise la même source.")
        setHint("Vos propres photos et des liens d'images s'ajoutent plus tard depuis un téléphone, avec le code QR de la tuile Fond d'écran de l'accueil.")
        // Mes photos and Lien personnalisé need a phone first; they show here only once they have pictures.
        val keys = Wallpapers.SOURCES.filter { (it != Wallpapers.MINE && it != Wallpapers.CUSTOM) || Wallpapers(this, it).files().isNotEmpty() } +
            Backgrounds.THEMES.keys
        val grid = column()
        for (row in keys.chunked(if (keys.size > 10) 6 else 5)) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
            for (k in row) {
                val c = PickCard(this, CARD_W, CARD_H).apply {
                    contentDescription = label(k)
                    label.text = label(k)
                    setOnClickListener { pickWallpaper(k) }
                }
                wallCards[k] = c
                r.addView(c, LinearLayout.LayoutParams(dp(CARD_W), -2).apply { if (r.childCount > 0) marginStart = dp(16) })
            }
            grid.addView(r, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(12) })
        }
        body.addView(grid, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
        markWallpaper()
        loadPreviews()
        return wallCards[prefs.background] ?: wallCards.values.firstOrNull()
    }

    private fun label(key: String) = Backgrounds.THEMES[key]?.first ?: Wallpapers.label(key)

    private fun markWallpaper() = wallCards.forEach { (k, c) -> c.selected(k == prefs.background) }

    /** The cards' pictures: a source's newest photo (none until it is picked once), or the gradient itself. */
    private fun loadPreviews() {
        val keys = wallCards.keys.toList()
        io.execute {
            for (k in keys) {
                val photo = k in Wallpapers.SOURCES
                val file = if (photo) Wallpapers(this, k).files().firstOrNull() else null
                val bmp = if (photo) file?.let { BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = 8 }) } else Backgrounds.render(k)
                val empty = if (photo && file == null) "Téléchargé à la sélection" else null
                main.post { wallCards[k]?.apply { image.setImageBitmap(bmp); emptyText(empty) } }
            }
        }
    }

    /** As [WallpaperActivity] picks: saved at once, and a photo source with nothing yet starts downloading. */
    private fun pickWallpaper(key: String) {
        prefs.background = key
        markWallpaper()
        io.execute { Wallpapers.trimExcept(this, key) }
        if (key in Wallpapers.SOURCES && Wallpapers(this, key).files().isEmpty()) {
            wallCards[key]?.emptyText("Téléchargement…")
            net.execute {
                runCatching { Wallpapers(this, key).refresh(force = true) }
                main.post {
                    if (isDestroyed) return@post
                    if (page == WALLPAPER) loadPreviews()
                    showBackdrop()
                }
            }
        }
        showBackdrop()
    }

    /** The assistant's own background: the home background as it stands, or graphite while a photo is not there yet. */
    private fun showBackdrop() {
        val bg = prefs.background
        io.execute {
            val photo = Wallpapers.isPhoto(bg)
            val file = if (photo) Wallpapers(this, bg).current() else null
            val key = if (photo) "$bg:${file?.name}" else bg
            if (key == shownBackdrop) return@execute
            val bmp = file?.let { BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = 4 }) }
                ?: Backgrounds.render(if (photo) "graphite" else bg)
            main.post {
                if (isDestroyed) return@post
                shownBackdrop = key
                backdrop.setImageBitmap(bmp)
            }
        }
    }

    private fun dockPage(): View? {
        heading("Dock", "Choisissez jusqu'à ${Onboarding.DOCK} apps pour le Dock, la rangée du bas de l'accueil.")
        setHint("OK coche ou décoche une app ; le numéro est sa place dans le Dock. Sans choix, l'ordre par défaut est conservé." +
            if (prefs.dockRecents) " Ensuite, l'app que vous ouvrez passe en tête (Réglages de l'accueil → Dock : Fixe pour l'éviter)." else "")
        if (savedOrder == null) savedOrder = prefs.order
        val apps = dockApps
        if (apps == null) {
            body.addView(caption("Chargement des apps…"), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            loadDock()
            return null
        }
        return buildDock(apps)
    }

    private fun loadDock() {
        if (dockLoading) return
        dockLoading = true
        io.execute {
            // The grid as it would show now, before anything is ticked.
            val apps = repo.orderedVisible()
            main.post {
                if (isDestroyed) return@post
                dockApps = apps
                if (page == DOCK) {
                    body.removeAllViews()
                    val first = buildDock(apps)
                    // Only if the user has not moved on to the buttons meanwhile.
                    if (next.isFocused || back.isFocused || currentFocus == null) first?.requestFocus()
                }
            }
            val w = dp(CARD_W)
            val h = dp(CARD_H)
            for (a in apps) {
                val tile = runCatching { repo.tile(a, keep = false) }.getOrNull() ?: continue
                val small = Bitmap.createScaledBitmap(tile, w, h, true)
                main.post {
                    dockTiles[a.pkg] = small
                    dockCards[a.pkg]?.image?.setImageBitmap(small)
                }
            }
        }
    }

    private fun buildDock(apps: List<AppEntry>): View? {
        val grid = column()
        for (row in apps.chunked(6)) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
            for (a in row) {
                val c = PickCard(this, CARD_W, CARD_H).apply {
                    label.text = a.label
                    contentDescription = a.label
                    dockTiles[a.pkg]?.let { image.setImageBitmap(it) }
                    setOnClickListener { tick(a.pkg) }
                }
                dockCards[a.pkg] = c
                r.addView(c, LinearLayout.LayoutParams(dp(CARD_W), -2).apply { if (r.childCount > 0) marginStart = dp(16) })
            }
            grid.addView(r, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(12) })
        }
        // Scrolls when there are more apps than fit; the padding is room for a focused card's zoom.
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            clipChildren = false
            isFocusable = false
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(grid, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
        }
        body.addView(scroll, FrameLayout.LayoutParams(-2, -1, Gravity.CENTER_HORIZONTAL))
        markDock()
        return dockCards[ticked.firstOrNull()] ?: apps.firstOrNull()?.let { dockCards[it.pkg] }
    }

    private fun tick(pkg: String) {
        if (pkg in ticked) ticked.remove(pkg)
        else if (ticked.size >= Onboarding.DOCK) {
            setHint("Le Dock compte ${Onboarding.DOCK} apps au plus : décochez-en une avant d'en ajouter une autre.")
            return
        } else ticked += pkg
        markDock()
        val visible = dockApps.orEmpty().map { it.pkg }
        prefs.order = Onboarding.dockOrder(ticked, visible, savedOrder.orEmpty())
    }

    private fun markDock() = dockCards.forEach { (k, c) ->
        val i = ticked.indexOf(k)
        c.badge(if (i >= 0) "${i + 1}" else null)
        c.contentDescription = (c.label.text?.toString() ?: k) + if (i >= 0) " (Dock ${i + 1})" else ""
    }

    private fun weatherPage(): View? {
        heading("Météo", "Afficher la météo sur l'accueil ?")
        setHint("La température à côté de l'horloge, et les prévisions dans le Centre de contrôle. Le lieu est deviné d'après l'adresse IP du téléviseur ; une autre ville se choisit depuis la page téléphone.")
        val (row, focus) = choices(listOf("Oui", "Non"), if (prefs.weather) 0 else 1, 170) { prefs.weather = it == 0 }
        body.addView(row, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        return focus
    }

    private fun profilePage(): View? {
        heading("Comment vous appelez-vous ?", "Le nom et la couleur du profil principal, en haut à droite de l'accueil.")
        setHint("D'autres profils, dont un profil enfant protégé par un code PIN, se créent dans Réglages de l'accueil → Profils.")
        val col = column()
        val mainProfile = Profiles.byId(prefs.profileList(), Profiles.MAIN)
        val swatches = ArrayList<Swatch>()
        val field = nameField(mainProfile?.name.orEmpty()) { name ->
            prefs.editProfiles { Profiles.rename(it, Profiles.MAIN, name) }
            swatches.forEach { it.letter = Profiles.initial(name) }
        }
        col.addView(field, LinearLayout.LayoutParams(dp(420), dp(56)))
        val colors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; clipChildren = false }
        val selected = mainProfile?.color ?: 0
        for (i in Profiles.COLORS.indices) {
            val s = Swatch(i, Profiles.initial(field.text.toString()), i == selected) { idx ->
                prefs.editProfiles { Profiles.setColor(it, Profiles.MAIN, idx) }
                swatches.forEach { it.chosen = it.index == idx }
            }
            swatches += s
            colors.addView(s, LinearLayout.LayoutParams(dp(50), dp(50)).apply { if (i > 0) marginStart = dp(18) })
        }
        col.addView(caption("Couleur"), captionParams())
        col.addView(colors)
        body.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
        return field
    }

    /**
     * The name, saved as it is typed (the main profile keeps « Principal » while it is empty). Like
     * [Sheet.input]: OK opens the TV's keyboard, whose Done key closes it and moves on to Continuer.
     */
    private fun nameField(initial: String, changed: (String) -> Unit): EditText {
        val r = dp(14).toFloat()
        val idle = rounded(Color.argb(46, 255, 255, 255), r)
        val focused = rounded(Color.argb(72, 255, 255, 255), r).apply { setStroke(dp(3), Color.WHITE) }
        return EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            isSingleLine = true
            // Landscape keyboards go full screen by default and hide the page; this one keeps it in view.
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            filters = arrayOf(android.text.InputFilter.LengthFilter(Profiles.MAX_NAME))
            textSize = 20f
            typeface = Fonts.inter(this@OnboardingActivity, 500)
            includeFontPadding = false
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(110, 255, 255, 255))
            highlightColor = Color.argb(90, 255, 255, 255)
            if (android.os.Build.VERSION.SDK_INT >= 29) textCursorDrawable = rounded(Color.WHITE, 0f).apply { setSize(dp(2), 0) }
            hint = Profiles.MAIN_NAME
            contentDescription = "Nom du profil"
            gravity = Gravity.CENTER
            setPadding(dp(20), 0, dp(20), 0)
            background = idle
            setText(initial)
            setSelection(text.length)
            setOnFocusChangeListener { _, has -> background = if (has) focused else idle }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = changed(s?.toString().orEmpty())
            })
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_DONE) { hideKeyboard(); next.requestFocus(); true } else false
            }
        }
    }

    /** One of the six profile colours, with the name's initial on it; a ring marks the chosen one. */
    private inner class Swatch(val index: Int, letter: String, chosen: Boolean, pick: (Int) -> Unit) : TextView(this@OnboardingActivity) {
        var chosen = chosen
            set(v) { field = v; paint() }
        var letter: String = letter
            set(v) { field = v; text = v }

        init {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Fonts.inter(context, 600)
            textSize = 20f
            includeFontPadding = false
            isFocusable = true
            isClickable = true
            text = letter
            paint()
            setOnClickListener { pick(index) }
            setOnFocusChangeListener { _, f -> tweenScale(if (f) 1.2f else 1f, 160, z = if (f) dp(10).toFloat() else 0f) }
        }

        private fun paint() {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Profiles.COLORS[index])
                if (chosen) setStroke(dp(3), Color.WHITE)
            }
            contentDescription = Profiles.COLOR_NAMES[index] + if (chosen) " (choisie)" else ""
        }
    }

    private fun adbPage(): View? {
        heading("Débogage local", "Une autorisation, donnée une seule fois, permet à canvasOS d'éteindre le téléviseur et de le mettre en veille, " +
            "de libérer toute la mémoire, de servir de télécommande depuis un téléphone et d'y installer des APK.")
        setHint("Possible aussi plus tard : Réglages de l'accueil → Débogage local.")
        val col = column()
        val status = text(19f, Color.WHITE, weight = 500).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
        }
        val button = pill("Autoriser maintenant") { authorizeAdb() }
        col.addView(status, LinearLayout.LayoutParams(dp(640), -2))
        col.addView(button, LinearLayout.LayoutParams(dp(300), dp(52)).apply { topMargin = dp(24) })
        body.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        adbStatus = status
        adbButton = button
        renderAdb()
        return if (prefs.adbAuthorized) next else button
    }

    private fun renderAdb() {
        val ok = prefs.adbAuthorized
        adbStatus?.text = when {
            ok -> "Autorisé ✓"
            adbWaiting -> "Répondez à la fenêtre du téléviseur : cochez « Toujours autoriser », puis choisissez « Autoriser »."
            adbRefused -> "Pas encore autorisé. Réessayez, ou plus tard dans Réglages de l'accueil → Débogage local."
            else -> "Le téléviseur va demander « Autoriser le débogage ? » : cochez « Toujours autoriser », puis choisissez « Autoriser »."
        }
        adbButton?.let { b ->
            b.visibility = if (ok) View.GONE else View.VISIBLE
            val l = when { adbWaiting -> "En attente…"; adbRefused -> "Réessayer"; else -> "Autoriser maintenant" }
            b.setLabel(l)
            b.contentDescription = l
        }
        if (page == ADB) setNext(if (ok) "Continuer" else "Plus tard")
    }

    /** The same request as [HomeActivity.authorizeAdb]: a shell command that makes the TV ask, waiting up to a minute for the answer. */
    private fun authorizeAdb() {
        if (adbWaiting || prefs.adbAuthorized) return
        adbWaiting = true
        adbRefused = false
        renderAdb()
        Toast.makeText(this, "Dans la fenêtre qui s'affiche, cochez « Toujours autoriser » puis choisissez « Autoriser »", Toast.LENGTH_LONG).show()
        val app = applicationContext
        Thread {
            val ok = LocalAdb.shell(app, "echo ok", 60_000) != null
            main.post {
                adbWaiting = false
                adbRefused = !ok
                if (isDestroyed) return@post
                val wasOnButton = adbButton?.isFocused == true
                renderAdb()
                if (ok && wasOnButton) next.requestFocus()
            }
        }.start()
    }

    private fun donePage(): View? {
        heading("Tout est prêt", "Tout se change plus tard dans Réglages de l'accueil : Centre de contrôle (touche Retour), puis l'engrenage.")
        val col = column()
        val clock = (if (prefs.clock24) "24 h" else "12 h") + ", " + when (prefs.clockStyle) {
            Prefs.CLOCK_LARGE -> "grande, à gauche"; Prefs.CLOCK_OFF -> "masquée"; else -> "en haut à droite"
        }
        val bg = prefs.background
        val wall = Backgrounds.THEMES[bg]?.let { "Dégradé " + it.first.lowercase(Locale.FRANCE) } ?: Wallpapers.label(bg)
        val names = dockApps.orEmpty().associate { it.pkg to it.label }
        val dock = if (ticked.isEmpty()) "ordre par défaut" else ticked.mapNotNull { names[it] }.joinToString(", ")
        val me = prefs.profileList().first()
        val lines = listOf(
            "Horloge" to clock,
            "Fond d'écran" to wall,
            "Dock" to dock,
            "Météo" to if (prefs.weather) "affichée" else "masquée",
            "Profil" to "${Profiles.displayName(me)} (${Profiles.COLOR_NAMES[me.color].lowercase(Locale.FRANCE)})",
            "Débogage local" to if (prefs.adbAuthorized) "autorisé" else "pas encore autorisé",
        )
        for ((k, v) in lines) col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(text(17f, Color.argb(150, 255, 255, 255)).apply { text = k; gravity = Gravity.END },
                LinearLayout.LayoutParams(dp(200), -2).apply { marginEnd = dp(18) })
            addView(text(17f, Color.WHITE, weight = 500).apply { text = v; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(dp(440), -2))
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        body.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        return next
    }

    /** Saves that the assistant is over and goes home, which applies everything when it resumes. */
    private fun finishSetup() {
        hideKeyboard()
        prefs.setupDone = true
        resumeAt = WELCOME
        // The home grid reloads on this (the dock order); the clock, background, weather and avatar on resume.
        AppRepo.notifyChanged(this, null)
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // ---------------------------------------------------------------- helpers

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }

    private fun caption(s: String) = text(14f, Color.argb(150, 255, 255, 255), weight = 500).apply {
        text = s
        gravity = Gravity.CENTER
    }

    private fun captionParams() = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18); bottomMargin = dp(8) }

    private fun pill(label: String, action: () -> Unit) = PillButton(this, label, onClick = action).apply { contentDescription = label }

    /**
     * A row of pills, one of them chosen (a check before its label); OK on another chooses it and
     * calls [pick] with its index. Returns the row and the chosen pill, which takes the focus first.
     */
    private fun choices(labels: List<String>, selected: Int, width: Int, pick: (Int) -> Unit): Pair<LinearLayout, PillButton> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
        }
        val buttons = ArrayList<PillButton>()
        fun mark(at: Int) = buttons.forEachIndexed { i, b ->
            b.setLabel(if (i == at) "✓  ${labels[i]}" else labels[i])
            b.contentDescription = labels[i] + if (i == at) " (choisi)" else ""
        }
        labels.forEachIndexed { i, l ->
            val b = PillButton(this, l) { mark(i); pick(i) }
            buttons += b
            row.addView(b, LinearLayout.LayoutParams(dp(width), dp(52)).apply { if (i > 0) marginStart = dp(16) })
        }
        val at = selected.coerceIn(0, labels.size - 1)
        mark(at)
        return row to buttons[at]
    }

    private fun hideKeyboard() {
        currentFocus?.let { getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    companion object {
        const val WELCOME = 0
        const val CLOCK = 1
        const val WALLPAPER = 2
        const val DOCK = 3
        const val WEATHER = 4
        const val PROFILE = 5
        const val ADB = 6
        const val DONE = 7
        const val PAGES = 8
        private const val STATE_PAGE = "page"
        /** Picture size of the background and dock cards, in dp. */
        private const val CARD_W = 120
        private const val CARD_H = 68

        /**
         * The page last shown, for when Home closed the assistant and opens it again in the same
         * process: it comes back where the user was. A new process starts from the welcome page.
         */
        @Volatile private var resumeAt = WELCOME
    }
}
