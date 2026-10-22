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
 * tvOS-style settings: the title centred on top, a large icon on the left with a
 * description of the focused row under it, and grouped rows on the right.
 * Sub-pages (keep list, hidden apps) reuse the same list.
 */
class SettingsActivity : Activity() {
    companion object {
        const val SONY_DREAM = "com.sony.dtv.braviashow/com.sony.dtv.braviashow.BraviaDayDreamService"
        const val ICON_FILE = "settings_icon.png"
        const val BACKDROP_FILE = "settings_bg.jpg"

        /**
         * A picture of the user's own behind the whole settings page (Apple TV's, a TV on a blue haze,
         * say), with the TV where the big icon would be; uploaded from the phone page or pushed over adb.
         */
        fun backdropFile(ctx: android.content.Context): java.io.File? =
            listOfNotNull(java.io.File(ctx.filesDir, BACKDROP_FILE), ctx.getExternalFilesDir(null)?.let { java.io.File(it, BACKDROP_FILE) })
                .firstOrNull { it.isFile && it.length() > 0 }

        /**
         * A picture of the user's own for the settings icon (Apple TV's, say), uploaded from the phone
         * page or pushed over adb to Android/data/com.getisolated.canvasos/files; null for the built-in gear.
         * Like the boot video, it is not part of the app.
         */
        fun customIcon(ctx: android.content.Context): java.io.File? = if (Prefs(ctx).ownIcon) iconFile(ctx) else null

        /** The uploaded picture whether or not it is in use ([Prefs.ownIcon]). */
        fun iconFile(ctx: android.content.Context): java.io.File? =
            listOfNotNull(java.io.File(ctx.filesDir, ICON_FILE), ctx.getExternalFilesDir(null)?.let { java.io.File(it, ICON_FILE) })
                .firstOrNull { it.isFile && it.length() > 0 }

        /** The icon decoded at no more than about [width] px wide: a 1280 px picture shown at 600 needn't take 6 MB. */
        fun decodeIcon(f: java.io.File, width: Int): android.graphics.Bitmap? {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.path, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= width) sample *= 2
            return android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }

        private val IDLE_TEXT = Color.argb(235, 255, 255, 255)
        private val IDLE_VALUE = Color.argb(130, 255, 255, 255)
        private val FOCUS_VALUE = Color.argb(140, 0, 0, 0)
    }

    private lateinit var prefs: Prefs
    private lateinit var repo: AppRepo
    private lateinit var list: LinearLayout
    private lateinit var heading: TextView
    private lateinit var about: TextView
    private lateinit var icon: ImageView
    private lateinit var backdrop: ImageView
    private lateinit var scroll: ScrollView
    private var page = Page.MAIN
    /** The main-page row that opened the current sub-page, focused again on the way back. */
    private var opener = 0

    private enum class Page { MAIN, KEEP, HIDDEN, REMOVED, PHONE }

    /** "Régénérer le code" was pressed while this screen was open. */
    private var tokenRenewed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        repo = AppRepo(this)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(28, 28, 30)) }
        backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> placeBackdrop(v as ImageView) }
        }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        heading = text(28f, weight = 600).apply { gravity = Gravity.CENTER }
        root.addView(heading, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(40) })

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(48), dp(84), dp(32), 0)
        }
        icon = ImageView(this).apply {
            showIcon(this)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipRounded(dp(18).toFloat())
            clipToOutline = true
        }
        left.addView(icon, LinearLayout.LayoutParams(-1, dp(207)))
        about = text(15f, Color.argb(150, 255, 255, 255)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(0f, 1.3f)
        }
        left.addView(about, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(26) })
        showBackdrop()
        body.addView(left, LinearLayout.LayoutParams(0, -1, 1f))

        // Not clipped, so a focused row can grow past the list's edges.
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
            setPadding(dp(12), dp(16), dp(12), dp(60))
            addView(list)
        }
        body.addView(scroll, LinearLayout.LayoutParams(0, -1, 1.05f).apply { marginEnd = dp(52) })
        root.addView(body, FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(84) })
        setContentView(root)
        show(Page.MAIN)
    }

    private fun show(p: Page, focusIndex: Int = 0) {
        page = p
        list.removeAllViews()
        when (p) {
            Page.MAIN -> main()
            Page.KEEP -> appPicker("Apps à conserver", prefs.keep) { pkg, on ->
                prefs.keep = if (on) prefs.keep + pkg else prefs.keep - pkg
            }
            Page.HIDDEN -> appPicker("Apps masquées", prefs.hidden, includeHidden = true) { pkg, on ->
                prefs.hidden = if (on) prefs.hidden + pkg else prefs.hidden - pkg
            }
            Page.REMOVED -> removedApps()
            Page.PHONE -> phonePage()
        }
        list.post {
            val target = list.getChildAt(focusIndex.coerceIn(0, maxOf(list.childCount - 1, 0)))
            (target?.takeIf { it.isFocusable } ?: (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.isFocusable })
                ?.requestFocus()
        }
    }

    /** The uploaded background, whose TV stands in for the big icon; or the plain dark page. RGB_565: half the memory. */
    private fun showBackdrop() {
        val bmp = (if (prefs.settingsBg) backdropFile(this) else null)?.let {
            android.graphics.BitmapFactory.decodeFile(it.path, android.graphics.BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            })
        }
        backdrop.setImageBitmap(bmp)
        placeBackdrop(backdrop)
        icon.visibility = if (bmp != null) android.view.View.INVISIBLE else android.view.View.VISIBLE
        // Below the TV in the picture, which reaches a little lower than the icon would.
        (about.layoutParams as LinearLayout.LayoutParams).topMargin = dp(if (bmp != null) 62 else 26)
        about.requestLayout()
    }

    /**
     * Fills the screen, 8% larger than needed and against the right edge: a subject in the left
     * third of a 16:9 picture (where the icon would be) then sits over the description column.
     */
    private fun placeBackdrop(v: ImageView) {
        val d = v.drawable ?: return
        if (v.width == 0 || d.intrinsicWidth <= 0) return
        val s = maxOf(v.width / d.intrinsicWidth.toFloat(), v.height / d.intrinsicHeight.toFloat()) * 1.08f
        v.imageMatrix = android.graphics.Matrix().apply {
            setScale(s, s)
            postTranslate(v.width - d.intrinsicWidth * s, (v.height - d.intrinsicHeight * s) / 2)
        }
    }

    private fun showIcon(v: ImageView) {
        val own = customIcon(this)?.let { decodeIcon(it, dp(380)) }
        if (own != null) v.setImageBitmap(own) else v.setImageResource(R.drawable.banner_settings)
    }

    private fun main() {
        heading.text = "Réglages de l'accueil"
        about.text = ""

        section("Apparence")
        row("Format de l'heure", "L'heure sur l'écran d'accueil et dans le Centre de contrôle, au format 12 h (11:49 AM) ou 24 h (23:49).",
            { if (prefs.clock24) "24 h" else "12 h" }) { prefs.clock24 = !prefs.clock24 }
        row("Horloge", "Une petite horloge en haut à droite, ou une grande horloge avec la date en haut à gauche (l'ancien style), ou aucune.", {
            when (prefs.clockStyle) { Prefs.CLOCK_LARGE -> "Grande, à gauche"; Prefs.CLOCK_OFF -> "Masquée"; else -> "En haut à droite" }
        }) {
            val opts = listOf(Prefs.CLOCK_CORNER, Prefs.CLOCK_LARGE, Prefs.CLOCK_OFF)
            prefs.clockStyle = opts[(opts.indexOf(prefs.clockStyle) + 1) % opts.size]
        }
        if (backdropFile(this) != null) row("Fond des réglages", "Le fond de cette page : l'image importée, ou le fond sombre standard.", {
            if (prefs.settingsBg) "Image" else "Standard"
        }) {
            prefs.settingsBg = !prefs.settingsBg
            showBackdrop()
        }
        if (iconFile(this) != null) row("Icône des réglages", "L'icône de ces réglages : votre image importée, ou l'engrenage intégré.", {
            if (prefs.ownIcon) "Image importée" else "Engrenage"
        }) {
            prefs.ownIcon = !prefs.ownIcon
            showIcon(icon)
        }
        row("Photo de profil", "La photo de profil en haut à droite ; appuyez dessus pour ouvrir le Centre de contrôle.", { onOff(prefs.showAvatar) }) { prefs.showAvatar = !prefs.showAvatar }
        row("Effet de sélection", "tvOS : l'app sélectionnée s'agrandit, s'incline selon la direction et un reflet passe dessus. Simple : elle s'agrandit seulement." +
            "\nAuto choisit Simple sur les téléviseurs peu puissants.", {
            auto(prefs.focusStyle, prefs.focusTvos(this), "tvOS", "Simple")
        }) {
            prefs.focusStyle = next(prefs.focusStyle, Prefs.FOCUS_TVOS, Prefs.FOCUS_SIMPLE)
        }
        row("Style du Centre de contrôle", "Verre : du verre dépoli qui laisse voir l'image derrière ; le bouton sélectionné éclaire ses voisins. Panneau : l'ancien panneau sombre, plus léger." +
            "\nAuto choisit Panneau sur les téléviseurs peu puissants.", {
            auto(prefs.ccStyle, prefs.ccGlass(this), "Verre", "Panneau")
        }) {
            prefs.ccStyle = next(prefs.ccStyle, Prefs.CC_GLASS, Prefs.CC_PANEL)
        }

        section("Animation de démarrage")
        val video = Intro.videoFile(this) != null
        row("Animation de démarrage", "Jouée au premier affichage de l'accueil après l'allumage ; à la fin, le logo s'agrandit et ouvre l'accueil. Appuyez sur une touche pour la passer." +
            if (video) "" else "\nAucune vidéo importée : le logo canvasOS la remplace.", {
            when (prefs.intro) { Prefs.INTRO_BOOT -> "Au démarrage"; Prefs.INTRO_WAKE -> "Démarrage et réveil"; else -> "Désactivée" }
        }) {
            val opts = listOf(Prefs.INTRO_OFF, Prefs.INTRO_BOOT, Prefs.INTRO_WAKE)
            prefs.intro = opts[(opts.indexOf(prefs.intro) + 1) % opts.size]
            // This boot counts as played: turning it on doesn't start it on the next return home.
            prefs.introBoot = Intro.bootStamp(this)
        }
        row("Contenu de l'animation", "Vidéo importée : par exemple l'animation de démarrage de l'Apple TV, dont le logo final s'agrandit et ouvre l'accueil. Logo canvasOS : le nom canvasOS apparaît en fondu puis s'agrandit." +
            if (video) "" else "\nTant qu'aucune vidéo n'est importée, les deux affichent le logo canvasOS.", {
            if (prefs.introSource == Prefs.INTRO_WORDMARK) "Logo canvasOS" else "Vidéo importée"
        }) {
            prefs.introSource = if (prefs.introSource == Prefs.INTRO_WORDMARK) Prefs.INTRO_VIDEO else Prefs.INTRO_WORDMARK
        }
        row("Délai au réveil", "Au sortir de la veille, l'écran reste noir ce temps avant l'animation. Beaucoup de téléviseurs (surtout OLED) n'affichent l'image que quelques secondes après l'allumage : allongez si le début de l'animation manque, raccourcissez si le noir dure trop.", {
            if (prefs.wakeDelay == 0) "Aucun" else "${prefs.wakeDelay / 1000.0} s".replace(".0 ", " ")
        }) {
            val opts = listOf(0, 1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 10_000)
            prefs.wakeDelay = opts.firstOrNull { it > prefs.wakeDelay } ?: 0
        }
        row("Son de l'animation", "Joue le son de la vidéo d'animation.", { onOff(prefs.introSound) }) { prefs.introSound = !prefs.introSound }
        row("Aperçu de l'animation", "Revient à l'accueil et la joue une fois maintenant.", { "" }, chevron = true) {
            startActivity(Intent(this, HomeActivity::class.java)
                .putExtra(Intro.EXTRA_PREVIEW, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        section("Fond d'écran et cadre photo")
        row("Fond d'écran", "Le fond de l'écran d'accueil : un thème, une source de fonds d'écran ou vos propres photos.", {
            Backgrounds.THEMES[prefs.background]?.first ?: Wallpapers.label(Wallpapers.sourceFor(prefs.background)).let {
                if (prefs.background == Wallpapers.MINE) it else "$it (auto)"
            }
        }, chevron = true) { startActivity(Intent(this, WallpaperActivity::class.java)) }
        row("Changer de photo", "À quelle fréquence le fond d'écran automatique change.", { minutesLabel(prefs.wallMinutes) }) {
            val opts = listOf(15, 30, 60, 180, 1440)
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

        section("Mémoire et alimentation")
        row("Libérer la mémoire en veille", "Ferme les apps en arrière-plan quand le téléviseur passe en veille, pour un redémarrage plus fluide.", { onOff(prefs.cleanOnStandby) }) {
            prefs.cleanOnStandby = !prefs.cleanOnStandby
        }
        row("Libération programmée", "Ferme régulièrement les apps en arrière-plan.", {
            if (prefs.cleanIntervalHours == 0) "Désactivée" else "Toutes les ${prefs.cleanIntervalHours} h"
        }) {
            val opts = listOf(0, 2, 4, 8)
            prefs.cleanIntervalHours = opts[(opts.indexOf(prefs.cleanIntervalHours) + 1) % opts.size]
            KeeperService.start(this, KeeperService.ACTION_RESCHEDULE)
        }
        row("Libérer la mémoire", "Ferme maintenant les apps en arrière-plan.", { "${Cleaner.availMb(this)} Mo libres" }) { v ->
            v.text = "Libération…"
            Thread {
                val r = Cleaner.clean(applicationContext)
                runOnUiThread { v.text = "${r.freedMb} Mo libérés" }
            }.start()
        }
        row("Apps à conserver", "Apps que la libération de la mémoire ne ferme pas, comme la diffusion d'écran ou la musique.", { "${prefs.keep.size}" }, chevron = true) {
            show(Page.KEEP)
        }
        row("Bouton marche/arrêt", "Veille : comme d'habitude. Arrêt complet : économise l'énergie, mais le démarrage est plus long.", {
            if (prefs.shutdownOnPowerKey) "Arrêt complet" else "Veille"
        }) {
            prefs.shutdownOnPowerKey = !prefs.shutdownOnPowerKey
            if (prefs.shutdownOnPowerKey && !prefs.adbAuthorized)
                Toast.makeText(this, "Autorisez d'abord le débogage local pour l'activer", Toast.LENGTH_LONG).show()
        }

        section("Apps")
        row("Apps masquées", "Apps qui ne s'affichent pas sur l'écran d'accueil.", { "${prefs.hidden.size}" }, chevron = true) { show(Page.HIDDEN) }
        row("Apps préinstallées supprimées", "Apps préinstallées supprimées depuis le menu d'appui long ; vous pouvez les restaurer ici.", { "${prefs.removed.size}" }, chevron = true) {
            show(Page.REMOVED)
        }

        section("Page téléphone")
        row("Afficher le QR code", "Scannez-le avec un téléphone sur le même Wi-Fi. La page qui s'ouvre sert de télécommande avec clavier, choisit le fond d'écran, " +
            "l'économiseur et l'animation de démarrage, importe des photos et installe des apps (APK). La télécommande et l'installation d'apps demandent le débogage local.",
            { "" }, chevron = true) { show(Page.PHONE) }
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

        section("Système")
        row("Débogage local", "Permet à l'accueil d'éteindre le téléviseur, de libérer la mémoire, etc. via le débogage local. Dans la fenêtre qui s'affiche, choisissez « Toujours autoriser ».",
            { if (prefs.adbAuthorized) "Autorisé" else "Non autorisé, appuyez sur OK" }) { v ->
            v.text = "Choisissez Toujours autoriser…"
            Thread {
                val ok = LocalAdb.shell(applicationContext, "echo ok", 60_000) != null
                runOnUiThread { v.text = if (ok) "Autorisé" else "Non autorisé, OK pour réessayer" }
            }.start()
        }
        // Only where the TV keeps Home for its own launcher; on Sony or stock Android TV canvasOS is the real home.
        if (Guard.needed(this)) row("Protection de l'accueil", "Quand la touche Accueil ramène au lanceur d'origine du téléviseur, revient automatiquement ici.",
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
        if (Guard.isXiaomi() || prefs.keepAdb) row("Garder le débogage ADB activé", "Empêche le système de désactiver le débogage ADB.", { onOff(prefs.keepAdb) }) { v ->
            val on = !prefs.keepAdb
            v.text = "Réglage…"
            Thread {
                val ok = !on || Guard.hasSecureSettings(this) || Guard.grantViaLocalAdb(applicationContext)
                if (ok) { prefs.keepAdb = on; Guard.ensureAdb(applicationContext) }
                runOnUiThread { v.text = if (ok) onOff(prefs.keepAdb) else "Débogage local requis" }
            }.start()
        }
        row("Réglages du téléviseur", "Ouvre les réglages d'origine du téléviseur.", { "" }, chevron = true) { runCatching { startActivity(TvControls(this).settingsIntent()) } }
        row("À propos", "canvasOS, un écran d'accueil Android TV inspiré de tvOS.",
            { "Version " + packageManager.getPackageInfo(packageName, 0).versionName }) {}
    }

    private fun appPicker(title: String, selected: Set<String>, includeHidden: Boolean = false, toggle: (String, Boolean) -> Unit) {
        heading.text = title
        about.text = if (page == Page.KEEP) "La libération de la mémoire ne ferme pas les apps cochées." else "Les apps cochées n'apparaissent pas sur l'écran d'accueil."
        val apps = if (includeHidden) repo.allApps().sortedBy { it.label } else repo.allApps().filter { !repo.isSystem(it.pkg) }.sortedBy { it.label }
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
            "Télécommande et clavier, fond d'écran, économiseur, animation de démarrage, installation d'apps (APK).\n" +
            "Le code dans l'adresse est la seule protection : régénérez-le si un téléphone qui a ouvert la page est perdu ; " +
            "les téléphones qui connaissaient l'ancien devront scanner à nouveau."
        val qr = ImageView(this).apply {
            background = rounded(Color.WHITE, dp(14).toFloat())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val address = text(15f, Color.argb(170, 255, 255, 255)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        list.addView(qr, LinearLayout.LayoutParams(dp(250), dp(250)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        list.addView(address, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); bottomMargin = dp(18) })
        fun load() {
            address.text = "Démarrage…"
            Thread {
                // Usually already running (KeeperService); the address is read again, Wi-Fi may have changed.
                PhoneServer.start(applicationContext)
                val url = PhoneServer.url(applicationContext)
                val bmp = url?.let { PhoneServer.qr(it) }
                val off = if (url == null) PhoneServer.unavailable(applicationContext) else null
                runOnUiThread {
                    qr.setImageBitmap(bmp)
                    qr.visibility = if (bmp != null) android.view.View.VISIBLE else android.view.View.GONE
                    address.text = url ?: off
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

    /** A small grey group title above a run of rows. */
    private fun section(title: String) {
        val first = list.childCount == 0
        list.addView(text(14f, Color.argb(120, 255, 255, 255), weight = 500).apply { text = title },
            LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = dp(18); topMargin = if (first) 0 else dp(22); bottomMargin = dp(8)
            })
    }

    /**
     * One settings row; [value] is re-read after each click, [desc] shows under the icon while
     * the row is focused, and [chevron] marks a row that opens another screen.
     */
    private fun row(label: String, desc: String?, value: () -> String, chevron: Boolean = false, onClick: (TextView) -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(20), 0, dp(if (chevron) 14 else 20), 0)
        }
        val l = text(17f, IDLE_TEXT, weight = 500).apply {
            text = label
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val v = text(16f, IDLE_VALUE).apply { text = value() }
        r.addView(l, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(v, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        val arrow = if (chevron) text(24f, IDLE_VALUE, weight = 300).apply { text = "›" } else null
        arrow?.let { r.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10); bottomMargin = dp(2) }) }
        val idle = rounded(Color.argb(20, 255, 255, 255), dp(14).toFloat())
        val focus = rounded(Color.WHITE, dp(14).toFloat())
        r.background = idle
        r.setOnFocusChangeListener { _, f ->
            r.background = if (f) focus else idle
            l.setTextColor(if (f) Color.BLACK else IDLE_TEXT)
            v.setTextColor(if (f) FOCUS_VALUE else IDLE_VALUE)
            arrow?.setTextColor(if (f) FOCUS_VALUE else IDLE_VALUE)
            if (f && desc != null) about.text = desc
            r.tweenScale(if (f) 1.03f else 1f, 160)
        }
        r.setOnClickListener {
            if (page == Page.MAIN) opener = list.indexOfChild(r)
            onClick(v)
            if (page == Page.MAIN && !v.text.endsWith("…")) v.text = value()
        }
        list.addView(r, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(6) })
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
        m >= 1440 -> "Chaque jour"
        m >= 60 -> "${m / 60} h"
        else -> "$m min"
    }

    /** On Android TV the screensaver starts after the "screen off" timeout. */
    private fun idleMinutes() = Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 600_000) / 60_000

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && page != Page.MAIN) {
            show(Page.MAIN, opener)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK && page != Page.MAIN) true else super.onKeyDown(keyCode, event)
}
