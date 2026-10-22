package com.getisolated.canvasos

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * The 壁纸 app: every background as a picture to pick with one press, and beside it the QR code
 * for the phone page ([PhoneServer], kept running by [KeeperService]). Changes made on the phone
 * show up here as they happen.
 */
class WallpaperActivity : Activity() {
    private val prefs by lazy { Prefs(this) }
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val cards = LinkedHashMap<String, PickCard>()
    private lateinit var qr: ImageView
    private lateinit var address: TextView
    private var counts = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        val keys = Wallpapers.SOURCES + Backgrounds.THEMES.keys
        for (row in keys.chunked(4)) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
            for (k in row) {
                val c = PickCard(this).apply { setOnClickListener { pick(k) } }
                cards[k] = c
                r.addView(c, LinearLayout.LayoutParams(dp(128), -2).apply { marginEnd = dp(16) })
            }
            grid.addView(r, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(14) })
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(text(30f, bold = true).apply { text = "Fond d'écran" })
            addView(text(15f, Color.argb(160, 255, 255, 255)).apply { text = "Choisissez le fond de l'écran d'accueil ; le cadre photo utilise la même source." },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8); bottomMargin = dp(22) })
            addView(grid)
        }
        qr = ImageView(this).apply {
            background = rounded(Color.WHITE, dp(14).toFloat())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        address = text(13f, Color.argb(130, 255, 255, 255))
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(20f, bold = true).apply { text = "Depuis un téléphone" })
            addView(text(15f, Color.argb(190, 255, 255, 255)).apply {
                text = "Sur le même Wi-Fi, scannez le code avec l'appareil photo du téléphone : importez des photos ou ajoutez des liens d'images (une image, une API d'images aléatoires, une page ou un JSON qui liste des images). Le téléviseur les récupère chaque jour. La même page sert aussi de télécommande."
                setLineSpacing(0f, 1.25f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(18) })
            addView(qr, LinearLayout.LayoutParams(dp(220), dp(220)))
            addView(address, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(16, 16, 18))
            clipChildren = false
            setPadding(dp(64), dp(44), dp(56), dp(32))
            addView(left, LinearLayout.LayoutParams(0, -1, 1f))
            addView(right, LinearLayout.LayoutParams(dp(250), -2).apply { marginStart = dp(24) })
        })
        cards[prefs.background]?.requestFocus() ?: cards.values.first().requestFocus()
    }

    override fun onStart() {
        super.onStart()
        address.text = "Démarrage…"
        io.execute {
            // Usually already running (KeeperService); the address is read again, Wi-Fi may have changed.
            PhoneServer.start(this)
            val url = PhoneServer.url(this)
            // Opens the page on its wallpaper tab.
            val bmp = url?.let { PhoneServer.qr("$it#fond") }
            val off = if (url == null) PhoneServer.unavailable(this) else null
            main.post {
                address.text = url ?: off
                qr.setImageBitmap(bmp)
            }
        }
        counts = ""
        poll.run()
    }

    override fun onStop() {
        main.removeCallbacks(poll)
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    /** Follows the phone: a new choice moves the check mark, new photos redraw the previews. */
    private val poll = object : Runnable {
        override fun run() {
            cards.forEach { (k, c) -> c.selected(k == prefs.background) }
            io.execute {
                val now = Wallpapers.SOURCES.joinToString { s -> Wallpapers(this@WallpaperActivity, s).files().firstOrNull()?.name + Wallpapers(this@WallpaperActivity, s).files().size }
                if (now != counts) { counts = now; loadPreviews() }
            }
            main.postDelayed(this, 3000)
        }
    }

    /** Runs on [io]. */
    private fun loadPreviews() {
        for (s in Wallpapers.SOURCES) {
            val files = Wallpapers(this, s).files()
            val bmp = files.firstOrNull()?.let { BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = 8 }) }
            val label = Wallpapers.label(s) + if (s == Wallpapers.MINE && files.isNotEmpty()) " · ${files.size}" else ""
            val empty = when {
                files.isNotEmpty() -> null
                s == Wallpapers.MINE -> "Scannez pour importer"
                s == Wallpapers.CUSTOM -> "Scannez pour ajouter un lien"
                else -> "Téléchargé à la sélection"
            }
            main.post { cards[s]?.apply { image.setImageBitmap(bmp); this.label.text = label; emptyText(empty) } }
        }
        for ((k, v) in Backgrounds.THEMES) {
            val bmp: Bitmap = Backgrounds.render(k)
            main.post { cards[k]?.apply { image.setImageBitmap(bmp); label.text = v.first; emptyText(null) } }
        }
    }

    private fun pick(key: String) {
        if (key == Wallpapers.MINE || key == Wallpapers.CUSTOM) {
            if (Wallpapers(this, key).files().isEmpty()) {
                Toast.makeText(this, if (key == Wallpapers.MINE) "Aucune photo pour l'instant : scannez le code QR à droite avec un téléphone pour en importer" else "Aucun lien pour l'instant : scannez le code QR à droite avec un téléphone pour en ajouter", Toast.LENGTH_LONG).show()
                return
            }
        }
        prefs.background = key
        cards.forEach { (k, c) -> c.selected(k == key) }
        io.execute { Wallpapers.trimExcept(this, key) }
        if (key in Wallpapers.SOURCES && Wallpapers(this, key).files().isEmpty()) io.execute {
            Wallpapers(this, key).refresh(force = true)
            counts = ""
        }
    }
}
