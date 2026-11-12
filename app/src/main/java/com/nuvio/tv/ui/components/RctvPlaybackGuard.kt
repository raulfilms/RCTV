package com.nuvio.tv.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.nuvio.tv.R
import com.nuvio.tv.core.rctvcontrol.RctvAccessManager
import com.nuvio.tv.core.rctvcontrol.RctvPlaybackStart
import kotlinx.coroutines.delay

/** RCTV Control: this TV's account (activation, plan, playback limits); null where nothing provides it. */
val LocalRctvAccess = compositionLocalOf<RctvAccessManager?> { null }

/**
 * RCTV Control: while the player is open the account's website knows what this TV plays, so each
 * account keeps its limits (live TV on 2 TVs at once, movies and series on as many as the owner
 * set). When there's no room, or the plan no longer has live TV, [onStop] closes the player with
 * the message to show. Without a connection it plays.
 *
 * @param kind "iptv" for live TV, "vod" for movies and episodes.
 */
@Composable
fun RctvPlaybackGuard(kind: String, title: String, onStop: (messageRes: Int) -> Unit) {
    val access = LocalRctvAccess.current ?: return
    val latestOnStop by rememberUpdatedState(onStop)
    val session = remember { arrayOfNulls<String>(1) }

    LaunchedEffect(kind, title) {
        var start = access.startPlayback(kind, title)
        while (true) {
            when (val current = start) {
                is RctvPlaybackStart.Denied -> {
                    latestOnStop(messageFor(current.reason))
                    return@LaunchedEffect
                }
                RctvPlaybackStart.Unchecked -> {
                    // No answer: it plays, and asks again in a minute.
                    delay(UNCHECKED_RETRY_MS)
                    start = access.startPlayback(kind, title)
                }
                is RctvPlaybackStart.Allowed -> {
                    session[0] = current.sessionId
                    delay(current.beatSeconds * 1000L)
                    when (access.beatPlayback(current.sessionId)) {
                        "PLAN_ENDED" -> {
                            session[0] = null
                            latestOnStop(R.string.rctv_playback_plan_ended)
                            return@LaunchedEffect
                        }
                        // Lost (the TV was away too long): asked for again.
                        "ENDED" -> start = access.startPlayback(kind, title)
                        else -> Unit
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { session[0]?.let(access::releasePlayback) }
    }
}

@StringRes
private fun messageFor(reason: String): Int = when (reason) {
    "TOO_MANY_IPTV" -> R.string.rctv_playback_too_many_iptv
    "TOO_MANY_VOD" -> R.string.rctv_playback_too_many_vod
    "NO_IPTV_IN_PLAN" -> R.string.rctv_playback_no_iptv
    else -> R.string.rctv_playback_too_many_vod
}

private const val UNCHECKED_RETRY_MS = 60_000L
