package com.nuvio.tv.core.playback

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * RCTV (owner, as YouTube TV): one playback for the whole app, live channels and movies and episodes
 * alike. The player's state (its ViewModel, with its one ExoPlayer) belongs to a playback session
 * here, not to the player's screen: Back on the player leaves it playing ("minimized") while the app
 * is browsed, with the Now Playing banner on top; choosing the banner opens the player again on the
 * same session (same picture, position, audio, captions and speed: nothing restarts).
 *
 * - One session at a time: anything new to play (a channel, a movie, an episode) stops the
 *   minimized one first, so there's never a second decoder.
 * - A session no one minimized ends with its player screen, as before.
 * - Minimized, it ends when the app goes to the background (as the player did).
 *
 * Main thread only.
 */
object PlaybackSession {

    /** Added to the player's arguments to open it again on the minimized session. */
    const val RESUME_KEY = "rctvResumeSession"

    /** What the Now Playing banner shows. */
    data class NowPlaying(
        val isLive: Boolean,
        /** The channel's name, or the movie's or series' title. */
        val title: String,
        /** What's on the channel now, or "S1:E3 Its Name". */
        val subtitle: String?,
        /** The channel's logo, or the movie's or series' poster. */
        val art: String?,
        /** For RCTV Control's count of what this TV plays ("iptv" or "vod"). */
        val guardKind: String,
        val guardTitle: String
    )

    private class Session(
        val token: String,
        val store: ViewModelStore,
        val destinationId: Int,
        val args: Bundle
    ) {
        var stop: (() -> Unit)? = null
        var player: (() -> Player?)? = null
        var released = false

        fun release() {
            if (released) return
            released = true
            runCatching { stop?.invoke() }
            runCatching { store.clear() }
            stop = null
            player = null
        }
    }

    private var current: Session? = null
    private var minimized: Session? = null

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)

    /** What plays while the app is browsed; null when the player isn't minimized. */
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    /** Something plays behind the app (the player was left with Back). */
    val isMinimized: Boolean get() = minimized != null

    /**
     * The player screen [entry] opened: its session's store. The minimized session when [entry]
     * was opened from the banner; else a new one (the minimized one, if any, stops first).
     */
    fun bind(entry: NavBackStackEntry): ViewModelStore {
        val waiting = minimized
        val resumeToken = entry.arguments?.getString(RESUME_KEY)
        if (waiting != null && !waiting.released && resumeToken == waiting.token) {
            minimized = null
            _nowPlaying.value = null
            current = waiting
            endWithScreen(entry, waiting)
            return waiting.store
        }
        if (waiting != null) {
            minimized = null
            _nowPlaying.value = null
            waiting.release()
        }
        val session = Session(
            token = UUID.randomUUID().toString(),
            store = ViewModelStore(),
            destinationId = entry.destination.id,
            args = Bundle(entry.arguments ?: Bundle())
        )
        session.args.putString(RESUME_KEY, session.token)
        current = session
        endWithScreen(entry, session)
        return session.store
    }

    /** The session's player: how to stop it, and its ExoPlayer (to show it behind the Guide). */
    fun attach(store: ViewModelStore, stop: () -> Unit, player: () -> Player?) {
        val session = listOfNotNull(current, minimized).firstOrNull { it.store === store } ?: return
        session.stop = stop
        session.player = player
    }

    /** The player is being left with Back: it goes on playing. False when there's no session. */
    fun minimize(store: ViewModelStore?, nowPlaying: NowPlaying): Boolean {
        val session = current?.takeIf { store == null || it.store === store } ?: return false
        if (session.released) return false
        val art = nowPlaying.art ?: session.args.getString("poster")?.takeIf { it.isNotBlank() }
            ?: session.args.getString("backdrop")?.takeIf { it.isNotBlank() }
        minimized = session
        _nowPlaying.value = nowPlaying.copy(art = art)
        return true
    }

    /** The banner was chosen: the player opens again on the minimized session. */
    fun resume(navController: NavController): Boolean {
        val session = minimized ?: return false
        return runCatching { navController.navigate(session.destinationId, Bundle(session.args)) }.isSuccess
    }

    /** The minimized session's ExoPlayer (null when nothing is minimized or it plays another way). */
    fun minimizedPlayer(): Player? = minimized?.player?.invoke()

    /** Stops what plays behind the app (the app went to the background, or it may not play). */
    fun stopMinimized() {
        val session = minimized ?: return
        minimized = null
        _nowPlaying.value = null
        if (current === session) current = null
        session.release()
    }

    /** A session ends with its player screen, unless it was minimized (Back) on the way out. */
    private fun endWithScreen(entry: NavBackStackEntry, session: Session) {
        entry.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY && minimized !== session) {
                if (current === session) current = null
                session.release()
            }
        })
    }
}
