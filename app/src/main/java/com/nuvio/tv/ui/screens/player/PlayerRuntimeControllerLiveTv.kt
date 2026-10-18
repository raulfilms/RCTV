package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import com.nuvio.tv.ui.screens.settings.MemoryBudget
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * RCTV: a live channel never stops on its own. IPTV servers drop connections, publish segments
 * late and stall for a few seconds; the player keeps a deeper cushion than for movies, never
 * speeds up toward the live edge, and when the stream stalls, ends or fails it reconnects to the
 * live edge by itself instead of spinning or showing an error.
 */
internal object LiveTvPlaybackTuning {
    /** Keep up to a minute buffered (an HLS channel stops at its live edge, a TS one at real time). */
    const val MIN_BUFFER_MS = 30_000
    const val MAX_BUFFER_MS = 60_000
    /** Seconds gathered before the picture starts: the cushion against the server's hiccups. */
    const val BUFFER_FOR_PLAYBACK_MS = 3_500
    /** After a stall, gather a bigger cushion so the next hiccup doesn't stop the picture again. */
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 8_000

    /** Buffering this long without a single new byte means the connection is dead: reconnect. */
    const val STALL_RECONNECT_MS = 8_000L
    /** Buffering this long, even trickling, means a fresh connection will do better. */
    const val SLOW_STALL_RECONNECT_MS = 25_000L
    /** A reconnect that hasn't shown a picture after this long gets another one. */
    const val STARTUP_STUCK_RECONNECT_MS = 20_000L
    /** Failed reconnects in a row before the error is shown. */
    const val MAX_RECONNECTS = 12
    /** Playing this long counts as healthy again: the reconnect count starts over. */
    const val HEALTHY_RESET_MS = 20_000L

    fun reconnectDelayMs(attempt: Int): Long = when (attempt) {
        0 -> 250L
        1 -> 1_000L
        2 -> 2_000L
        else -> 4_000L
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    fun buildLoadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            MIN_BUFFER_MS,
            MAX_BUFFER_MS,
            BUFFER_FOR_PLAYBACK_MS,
            BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
        )
        .setTargetBufferBytes(MemoryBudget.budgetMb.coerceAtLeast(MemoryBudget.MIN_BUFFER_MB) * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(true)
        // Live can't be rewound into, so nothing is kept behind the picture.
        .setBackBuffer(0, false)
        .build()

    /**
     * Plays at exactly 1x: speeding up chases the live edge (where every late segment is a stall)
     * and any speed change flips Dolby passthrough audio to PCM and back.
     */
    val liveConfiguration: MediaItem.LiveConfiguration = MediaItem.LiveConfiguration.Builder()
        .setMinPlaybackSpeed(1f)
        .setMaxPlaybackSpeed(1f)
        .build()
}

/** Live TV, a sports channel or a live event: anything opened as a channel. */
internal val PlayerRuntimeController.isLiveTvPlayback: Boolean
    get() = LivePlaybackUiPolicy.isLiveTvContentType(contentType)

/**
 * Opened as a channel and playing like one: the player calls it live or it has no end. A replay
 * opened from a channel row has a length, so it ends, seeks and resumes like any video.
 */
internal fun PlayerRuntimeController.isLiveTvStreamNow(): Boolean {
    if (!isLiveTvPlayback) return false
    if (isUsingMpvEngine()) {
        val view = mpvView ?: return true
        return view.isLiveStreamNow() || view.durationMs() <= 0L
    }
    val player = _exoPlayer ?: return true
    val duration = player.duration
    return player.isCurrentMediaItemLive || duration == C.TIME_UNSET || duration <= 0L
}

/** Whether [error] on a live channel is one a fresh connection fixes. */
internal fun PlayerRuntimeController.shouldReconnectLiveTv(error: PlaybackException): Boolean {
    if (!isLiveTvStreamNow()) return false
    if (liveReconnectAttempts >= LiveTvPlaybackTuning.MAX_RECONNECTS) return false
    val played = liveTvHasPlayed || hasRenderedFirstFrame
    val code = error.errorCode
    return when {
        code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> true
        code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
            val status = error.findInvalidResponseCodeException()?.responseCode
            // A channel that never played and answers "forbidden / not found" is off the air.
            played || status == null || status !in setOf(401, 403, 404, 410)
        }
        code in 2000..2999 -> true // network and source errors
        code in 3000..3999 -> played // a stream that played and then sent garbage
        code == PlaybackException.ERROR_CODE_TIMEOUT -> true
        else -> false
    }
}

/**
 * Reconnects the channel at its live edge. [light] re-prepares the same player (what a fallen
 * behind HLS window needs); otherwise the stream is opened again from scratch.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.reconnectLiveTv(reason: String, light: Boolean = false) {
    val attempt = liveReconnectAttempts
    liveReconnectAttempts++
    Log.w(PlayerRuntimeController.TAG, "LIVE_RECONNECT: attempt=${attempt + 1} light=$light reason=$reason")
    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        _uiState.update {
            it.copy(error = null, isBuffering = true, showPauseOverlay = false, playbackEnded = false)
        }
        delay(LiveTvPlaybackTuning.reconnectDelayMs(attempt))
        val paused = userPausedManually
        if (isUsingMpvEngine()) {
            val view = mpvView
            if (view != null) {
                hasRenderedFirstFrame = false
                view.applyLiveStreamTuning(true)
                view.setMediaUsingLoadfile(currentStreamUrl, currentHeaders)
                view.setPaused(paused)
                liveStuckSinceMs = SystemClock.elapsedRealtime()
                return@launch
            }
        }
        val player = _exoPlayer
        if (light && player != null) {
            player.seekToDefaultPosition()
            player.prepare()
            player.playWhenReady = !paused
            return@launch
        }
        releasePlayer(flushPlaybackState = false)
        initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
    }
}

/**
 * Watches a live channel once a second: a stall that gets no new data, a stream that ended or a
 * reconnect that never came back is reconnected; 20 seconds of healthy picture reset the count.
 */
internal fun PlayerRuntimeController.startLiveTvWatchdog(url: String) {
    liveWatchdogJob?.cancel()
    liveWatchdogJob = null
    if (!isLiveTvPlayback) return
    if (liveTvUrl != url) {
        liveTvUrl = url
        liveTvHasPlayed = false
        liveReconnectAttempts = 0
    }
    liveStuckSinceMs = SystemClock.elapsedRealtime()
    liveWatchdogJob = scope.launch {
        var stalledSinceMs = 0L
        var lastProgressAtMs = 0L
        var lastBufferedMarker = -1L
        var healthySinceMs = 0L
        while (isActive) {
            delay(1_000L)
            val now = SystemClock.elapsedRealtime()
            if (errorRetryJob?.isActive == true || isInBackground) {
                stalledSinceMs = 0L
                healthySinceMs = 0L
                continue
            }
            val snapshot = liveTvSnapshot() ?: continue
            if (!isLiveTvStreamNow()) {
                // A replay: it buffers, ends and errs like any video.
                stalledSinceMs = 0L
                healthySinceMs = 0L
                continue
            }
            when {
                !snapshot.started -> {
                    stalledSinceMs = 0L
                    healthySinceMs = 0L
                    // Only a reconnect of ours that is stuck: the first start has its own watchdogs.
                    if (liveReconnectAttempts in 1 until LiveTvPlaybackTuning.MAX_RECONNECTS &&
                        now - liveStuckSinceMs >= LiveTvPlaybackTuning.STARTUP_STUCK_RECONNECT_MS
                    ) {
                        reconnectLiveTv(reason = "no picture after reconnect")
                    }
                }
                snapshot.ended -> {
                    stalledSinceMs = 0L
                    healthySinceMs = 0L
                    reconnectLiveTv(reason = "stream ended")
                }
                snapshot.stalled -> {
                    healthySinceMs = 0L
                    if (stalledSinceMs == 0L) {
                        stalledSinceMs = now
                        lastProgressAtMs = now
                        lastBufferedMarker = snapshot.bufferedMarkerMs
                    } else if (snapshot.bufferedMarkerMs > lastBufferedMarker) {
                        lastBufferedMarker = snapshot.bufferedMarkerMs
                        lastProgressAtMs = now
                    }
                    val noDataMs = now - lastProgressAtMs
                    val stalledMs = now - stalledSinceMs
                    if (noDataMs >= LiveTvPlaybackTuning.STALL_RECONNECT_MS ||
                        stalledMs >= LiveTvPlaybackTuning.SLOW_STALL_RECONNECT_MS
                    ) {
                        stalledSinceMs = 0L
                        reconnectLiveTv(reason = "stalled ${stalledMs}ms, no new data for ${noDataMs}ms")
                    }
                }
                snapshot.playing -> {
                    stalledSinceMs = 0L
                    liveStuckSinceMs = now
                    if (healthySinceMs == 0L) healthySinceMs = now
                    if (now - healthySinceMs >= LiveTvPlaybackTuning.HEALTHY_RESET_MS) {
                        liveTvHasPlayed = true
                        liveReconnectAttempts = 0
                    }
                }
                else -> {
                    stalledSinceMs = 0L
                    healthySinceMs = 0L
                    liveStuckSinceMs = now
                }
            }
        }
    }
}

internal fun PlayerRuntimeController.stopLiveTvWatchdog() {
    liveWatchdogJob?.cancel()
    liveWatchdogJob = null
}

private data class LiveTvSnapshot(
    val started: Boolean,
    val playing: Boolean,
    val stalled: Boolean,
    val ended: Boolean,
    /** Grows while data keeps arriving (buffered position, or position plus cache). */
    val bufferedMarkerMs: Long
)

private fun PlayerRuntimeController.liveTvSnapshot(): LiveTvSnapshot? {
    if (isUsingMpvEngine()) {
        val view = mpvView ?: return null
        val started = hasRenderedFirstFrame
        val cacheMs = (view.demuxerCacheDurationSec() * 1000.0).toLong()
        val positionMs = view.currentPositionMs().coerceAtLeast(0L)
        val waiting = view.isPausedForCacheNow() || view.isCoreIdleNow()
        return LiveTvSnapshot(
            started = started,
            playing = started && view.isPlayingNow() && !waiting,
            stalled = started && !userPausedManually && waiting && !view.isEofReached(),
            ended = started && !userPausedManually && view.isEofReached(),
            bufferedMarkerMs = positionMs + cacheMs
        )
    }
    val player = _exoPlayer ?: return null
    val started = hasRenderedFirstFrame
    val state = player.playbackState
    return LiveTvSnapshot(
        started = started,
        playing = started && player.isPlaying,
        stalled = started && player.playWhenReady && state == Player.STATE_BUFFERING,
        ended = started && state == Player.STATE_ENDED,
        bufferedMarkerMs = player.bufferedPosition.takeIf { it != C.TIME_UNSET } ?: 0L
    )
}
