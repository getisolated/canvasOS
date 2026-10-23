package com.getisolated.canvasos

import android.view.KeyEvent

/**
 * Remote shortcuts (Réglages de l'accueil → Télécommande, [Prefs.keyMap]): a key of the remote,
 * pressed or held, does one of canvasOS's actions while the home screen is in front. Nothing is
 * bound until the user binds it; the key is learnt by pressing it, so any remote works. The home
 * screen alone sees the keys: no accessibility filtering, nothing read inside other apps.
 *
 * Pure: the bindings' text form, their names, and which keys are refused. [HomeActivity] runs
 * the actions.
 */
object KeyMap {
    const val SLEEP_TIMER = "sleep_timer"
    const val CLEAN = "clean"
    const val PLAY_PAUSE = "play_pause"
    const val NEXT = "next"
    const val PREVIOUS = "previous"
    const val OPEN_PLAYING = "open_playing"
    const val LYRICS = "lyrics"
    const val NEXT_PHOTO = "next_photo"
    const val PHOTO_FRAME = "photo_frame"
    const val CONTROL_CENTER = "control_center"
    const val HOME_SETTINGS = "home_settings"
    const val TV_SETTINGS = "tv_settings"
    const val PROFILES = "profiles"
    /** "app:<package>": opens that app. */
    const val APP = "app:"
    /** "input:<InputTile.encode>": switches to that HDMI input. */
    const val INPUT = "input:"

    /** The fixed actions, in the order the settings list them, with their names. */
    val ACTIONS: List<Pair<String, String>> = listOf(
        CONTROL_CENTER to "Centre de contrôle",
        PLAY_PAUSE to "Lecture / pause",
        NEXT to "Piste suivante",
        PREVIOUS to "Piste précédente",
        OPEN_PLAYING to "Ouvrir l'app qui lit",
        LYRICS to "Paroles",
        NEXT_PHOTO to "Photo suivante",
        PHOTO_FRAME to "Cadre photo",
        SLEEP_TIMER to "Minuteur de veille",
        CLEAN to "Libérer la mémoire",
        HOME_SETTINGS to "Réglages de l'accueil",
        TV_SETTINGS to "Réglages du téléviseur",
        PROFILES to "Liste des profils",
    )

    class Binding(val code: Int, val long: Boolean, val action: String) {
        /** The same key and press, whatever the action: one binding per key and press. */
        fun sameKey(code: Int, long: Boolean) = this.code == code && this.long == long
    }

    private const val RECORD = '\u001e'

    /** The bindings kept in [Prefs.keyMap]: "code[L]=action" records. Malformed records are dropped. */
    fun parse(s: String): List<Binding> = s.split(RECORD).mapNotNull { rec ->
        val eq = rec.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        var key = rec.substring(0, eq)
        val long = key.endsWith("L")
        if (long) key = key.dropLast(1)
        val code = key.toIntOrNull() ?: return@mapNotNull null
        val action = rec.substring(eq + 1)
        if (action.isEmpty()) null else Binding(code, long, action)
    }

    fun serialize(list: List<Binding>): String = list.joinToString(RECORD.toString()) { "${it.code}${if (it.long) "L" else ""}=${it.action}" }

    /** [list] with [b] in place of any binding of the same key and press. */
    fun put(list: List<Binding>, b: Binding): List<Binding> = list.filterNot { it.sameKey(b.code, b.long) } + b

    fun remove(list: List<Binding>, b: Binding): List<Binding> = list.filterNot { it.sameKey(b.code, b.long) }

    fun find(list: List<Binding>, code: Int, long: Boolean): Binding? = list.firstOrNull { it.sameKey(code, long) }

    /** Whether any binding, pressed or held, uses [code]: its events are then the home screen's to handle. */
    fun uses(list: List<Binding>, code: Int): Boolean = list.any { it.code == code }

    /**
     * Why [code] cannot be bound, or null when it can. The TV keeps its own keys; keys the home
     * screen already uses can only be held, and those whose hold is used neither.
     */
    fun reserved(code: Int, long: Boolean): String? = when {
        code in TV_KEYS -> "Cette touche est réservée au téléviseur."
        code in NAV_KEYS -> if (long) "L'appui long sur cette touche sert déjà sur l'accueil." else "Cette touche sert déjà sur l'accueil."
        code in HOME_KEYS && !long -> "Cette touche sert déjà sur l'accueil : maintenez-la pour l'associer à un appui long."
        else -> null
    }

    /** The name of [action], the app's or the input's from [named] for "app:" and "input:" (their id when unknown). */
    fun label(action: String, named: (String) -> String? = { null }): String = when {
        action.startsWith(APP) -> "Ouvrir " + (named(action) ?: action.removePrefix(APP))
        action.startsWith(INPUT) -> "Source " + (named(action) ?: "HDMI")
        else -> ACTIONS.firstOrNull { it.first == action }?.second ?: action
    }

    /** The key's name for the list ("Touche rouge", "Touche 1", "Info"), with " (appui long)" when held. */
    fun keyName(code: Int, long: Boolean): String {
        val base = KEY_NAMES[code] ?: when (code) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> "Touche ${code - KeyEvent.KEYCODE_0}"
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> "Touche ${('A' + (code - KeyEvent.KEYCODE_A))}"
            else -> runCatching { KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() } }
                .getOrDefault("Touche $code")
        }
        return if (long) "$base (appui long)" else base
    }

    /** One line per binding, for the CONFIG answer: "Touche rouge=lyrics; Touche 1 (appui long)=app:com.x". */
    fun describe(list: List<Binding>): String = list.joinToString("; ") { "${keyName(it.code, it.long)}=${it.action}" }

    private val TV_KEYS = setOf(
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE,
        KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_TV_POWER,
    )
    /** Arrows and OK: their press and their hold both move or act on the home screen. */
    private val NAV_KEYS = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
    )
    /** Back and Menu: their press opens or closes things on the home screen; their hold is free. */
    private val HOME_KEYS = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU)

    private val KEY_NAMES = mapOf(
        KeyEvent.KEYCODE_PROG_RED to "Touche rouge",
        KeyEvent.KEYCODE_PROG_GREEN to "Touche verte",
        KeyEvent.KEYCODE_PROG_YELLOW to "Touche jaune",
        KeyEvent.KEYCODE_PROG_BLUE to "Touche bleue",
        KeyEvent.KEYCODE_BACK to "Retour",
        KeyEvent.KEYCODE_MENU to "Menu",
        KeyEvent.KEYCODE_INFO to "Info",
        KeyEvent.KEYCODE_GUIDE to "Guide",
        KeyEvent.KEYCODE_SETTINGS to "Réglages",
        KeyEvent.KEYCODE_PROFILE_SWITCH to "Profil",
        KeyEvent.KEYCODE_ALL_APPS to "Apps",
        KeyEvent.KEYCODE_NOTIFICATION to "Notifications",
        KeyEvent.KEYCODE_TV_INPUT to "Source",
        KeyEvent.KEYCODE_BOOKMARK to "Favoris",
        KeyEvent.KEYCODE_CAPTIONS to "Sous-titres",
        KeyEvent.KEYCODE_CHANNEL_UP to "Chaîne +",
        KeyEvent.KEYCODE_CHANNEL_DOWN to "Chaîne −",
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "Lecture/pause",
        KeyEvent.KEYCODE_MEDIA_PLAY to "Lecture",
        KeyEvent.KEYCODE_MEDIA_PAUSE to "Pause",
        KeyEvent.KEYCODE_MEDIA_STOP to "Stop",
        KeyEvent.KEYCODE_MEDIA_NEXT to "Suivant",
        KeyEvent.KEYCODE_MEDIA_PREVIOUS to "Précédent",
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to "Avance rapide",
        KeyEvent.KEYCODE_MEDIA_REWIND to "Retour rapide",
        KeyEvent.KEYCODE_MEDIA_RECORD to "Enregistrer",
        KeyEvent.KEYCODE_TV to "TV",
        KeyEvent.KEYCODE_DVR to "DVR",
        KeyEvent.KEYCODE_LAST_CHANNEL to "Dernière chaîne",
    )
}

/**
 * Keys the TV handles before any app sees them (Réglages, Profil on Google TV remotes, and a few
 * others), caught by the home guard's accessibility service when the user turned « Capter les
 * touches du téléviseur » on ([Prefs.captureKeys]): the service then sees every key pressed, in
 * every app, and lets all of them through except these, and only while the home screen is in
 * front ([home]), or while the settings learn a key ([learning], every key then). Main thread.
 */
object KeyCapture {
    /** The settings' lesson: every key goes here while it is set. */
    @Volatile var learning: ((KeyEvent) -> Unit)? = null
    /** The home screen's shortcuts; false when the key is not bound, and the TV keeps it. */
    @Volatile var home: ((KeyEvent) -> Boolean)? = null

    /** The keys worth catching: those the system swallows on TVs. */
    val CAPTURED = setOf(
        KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_PROFILE_SWITCH, KeyEvent.KEYCODE_ALL_APPS, KeyEvent.KEYCODE_GUIDE,
        KeyEvent.KEYCODE_TV_INPUT, KeyEvent.KEYCODE_TV, KeyEvent.KEYCODE_DVR, KeyEvent.KEYCODE_NOTIFICATION,
    )

    /** Whether the service keeps [e] (true) or lets the TV have it. */
    fun offer(e: KeyEvent, homeInFront: Boolean): Boolean {
        learning?.let { it(e); return true }
        if (!homeInFront || e.keyCode !in CAPTURED) return false
        return home?.invoke(e) == true
    }
}
