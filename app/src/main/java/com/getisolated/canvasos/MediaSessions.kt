package com.getisolated.canvasos

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log

/**
 * What is playing on the TV, from Android's media sessions (Spotify, Plex, Kodi, a casting
 * receiver…). Listing them needs an approved notification listener, and [RevivalListener] is one
 * (`cmd notification allow_listener`, AGENTS.md step 4, or Protection de l'accueil). The memory
 * cleanup ([Cleaner.clean]) only reads each session's package name and playback state. Only when
 * the user turns on "Lecture en cours" ([Prefs.nowPlaying]) does [NowPlaying] read more: the
 * session's title, artist, album and artwork. Neither reads a notification.
 *
 * Everything here is a few binder calls, no network, and fails soft: when the listener is not
 * approved on this TV (SecurityException) or the firmware misbehaves, the answer is "nothing".
 */
object MediaSessions {
    private const val TAG = "MediaSessions"

    /** Playback states that mean sound or video is going (Android's own PlaybackState.isActive set, API 31). */
    val ACTIVE = setOf(
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
    )

    /** Throws when the listener is not approved; callers below turn that into "nothing". */
    private fun sessions(ctx: Context): List<MediaController> {
        val msm = ctx.getSystemService(MediaSessionManager::class.java) ?: return emptyList()
        return msm.getActiveSessions(ComponentName(ctx, RevivalListener::class.java)).orEmpty()
    }

    /**
     * The active sessions, most recently active first (Android's order); empty when they cannot be
     * read. For a "now playing" view: what a caller reads from them beyond the package name and
     * playback state (title, artwork) is that caller's to disclose.
     */
    fun controllers(ctx: Context): List<MediaController> = try {
        sessions(ctx)
    } catch (e: SecurityException) {
        emptyList() // the notification listener is not approved on this TV
    } catch (e: Throwable) {
        Log.w(TAG, "could not list media sessions", e)
        emptyList()
    }

    /** Whether sessions can be read at all here (the listener is approved and the firmware lets us). */
    fun isAvailable(ctx: Context): Boolean = runCatching { sessions(ctx) }.isSuccess

    /** Packages whose session is playing or buffering right now. Reads the package name and playback state only. */
    fun playingPackages(ctx: Context): Set<String> = controllers(ctx).mapNotNullTo(HashSet()) { c ->
        runCatching { c.packageName.takeIf { c.playbackState?.state?.let { it in ACTIVE } == true } }.getOrNull()
    }
}
