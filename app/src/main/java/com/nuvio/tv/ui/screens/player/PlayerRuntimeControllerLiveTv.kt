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

    /**
     * RCTV (owner): a channel's first start that shows no picture (the server answers but sends
     * nothing, a game's channel off the air) waited forever: after this long it's opened again
     * (sooner with another version of it to turn to).
     */
    const val FIRST_START_STUCK_MS = 15_000L
    const val FIRST_START_VERSION_SWITCH_MS = 8_000L

    /** A channel that never showed a picture: each new try waits this long, and after this many it says so. */
    const val NEVER_PLAYED_RETRY_MS = 12_000L
    const val NEVER_PLAYED_MAX_RECONNECTS = 3
    /** Failed reconnects in a row before the error is shown. */
    const val MAX_RECONNECTS = 12
    /** Playing this long counts as healthy again: the reconnect count starts over. */
    const val HEALTHY_RESET_MS = 20_000L

    /**
     * RCTV (owner's rule): a channel with other versions (the provider has it more than once)
     * turns to the next one sooner, so the person doesn't sit through a stall: no new data for
     * this long, or buffering this long even trickling.
     */
    const val VERSION_STALL_SWITCH_MS = 4_000L
    const val VERSION_SLOW_STALL_SWITCH_MS = 12_000L

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
    // RCTV (owner's rule): another version of the channel is tried before any error shows.
    val hasOtherVersion = com.nuvio.tv.ui.screens.livetv.LiveTvChannelVersions.next(currentStreamUrl) != null
    return when {
        code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> true
        code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
            val status = error.findInvalidResponseCodeException()?.responseCode
            // A channel that never played and answers "forbidden / not found" is off the air
            // (unless it has another version).
            played || hasOtherVersion || status == null || status !in setOf(401, 403, 404, 410)
        }
        code in 2000..2999 -> true // network and source errors
        code in 3000..3999 -> played || hasOtherVersion // a stream that played and then sent garbage
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
    // RCTV (owner's rule): a channel the provider has more than once turns to its next version at
    // once instead of opening the same one again (the person stays on the same channel).
    val otherVersion = if (light) null else com.nuvio.tv.ui.screens.livetv.LiveTvChannelVersions.next(currentStreamUrl)
    // Never the address: it carries the IPTV login.
    Log.w(
        PlayerRuntimeController.TAG,
        "LIVE_RECONNECT: attempt=${attempt + 1} light=$light otherVersion=${otherVersion != null} reason=$reason"
    )
    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        _uiState.update {
            it.copy(error = null, isBuffering = true, showPauseOverlay = false, playbackEnded = false)
        }
        if (otherVersion != null) {
            val request = PlayerMediaSourceFactory.normalizePlaybackRequest(otherVersion, emptyMap())
            currentStreamUrl = request.url
            currentHeaders = request.headers
            currentStreamMimeType = PlayerMediaSourceFactory.inferMimeType(
                url = request.url,
                filename = null,
                responseHeaders = emptyMap()
            )
            currentStreamResponseHeaders = emptyMap()
            currentStreamCacheKey = null
            _uiState.update { it.copy(currentStreamUrl = request.url) }
        } else {
            delay(LiveTvPlaybackTuning.reconnectDelayMs(attempt))
        }
        val paused = userPausedManually
        if (isUsingMpvEngine()) {
            val view = mpvView
            if (view != null) {
                hasRenderedFirstFrame = false
                view.applyLiveStreamTuning(true)
                val (mpvUrl, mpvHeaders) = mpvMediaFor(currentStreamUrl, currentHeaders)
                view.setMediaUsingLoadfile(mpvUrl, mpvHeaders)
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
    // RCTV: one channel whatever version of it plays (its reconnect count goes on across them).
    val channelKey = com.nuvio.tv.ui.screens.livetv.LiveTvChannelVersions.channelKey(url)
    if (liveTvUrl != channelKey) {
        liveTvUrl = channelKey
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
                    val stuckMs = now - liveStuckSinceMs
                    val neverPlayed = !liveTvHasPlayed
                    when {
                        // RCTV (owner): the first start showing nothing no longer waits forever.
                        liveReconnectAttempts == 0 -> {
                            val hasOtherVersion = com.nuvio.tv.ui.screens.livetv.LiveTvChannelVersions.next(currentStreamUrl) != null
                            val limit = if (hasOtherVersion) LiveTvPlaybackTuning.FIRST_START_VERSION_SWITCH_MS else LiveTvPlaybackTuning.FIRST_START_STUCK_MS
                            if (stuckMs >= limit) reconnectLiveTv(reason = "no picture on first start")
                        }
                        // Never showed a picture after a few tries: it says so (Back or try again).
                        neverPlayed && liveReconnectAttempts >= LiveTvPlaybackTuning.NEVER_PLAYED_MAX_RECONNECTS -> {
                            if (stuckMs >= LiveTvPlaybackTuning.NEVER_PLAYED_RETRY_MS) {
                                giveUpLiveTv()
                                return@launch
                            }
                        }
                        liveReconnectAttempts < LiveTvPlaybackTuning.MAX_RECONNECTS &&
                            stuckMs >= (if (neverPlayed) LiveTvPlaybackTuning.NEVER_PLAYED_RETRY_MS else LiveTvPlaybackTuning.STARTUP_STUCK_RECONNECT_MS) -> {
                            reconnectLiveTv(reason = "no picture after reconnect")
                        }
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
                    // RCTV: with another version to turn to, it turns sooner.
                    val hasOtherVersion = com.nuvio.tv.ui.screens.livetv.LiveTvChannelVersions.next(currentStreamUrl) != null
                    val noDataLimit = if (hasOtherVersion) LiveTvPlaybackTuning.VERSION_STALL_SWITCH_MS else LiveTvPlaybackTuning.STALL_RECONNECT_MS
                    val stallLimit = if (hasOtherVersion) LiveTvPlaybackTuning.VERSION_SLOW_STALL_SWITCH_MS else LiveTvPlaybackTuning.SLOW_STALL_RECONNECT_MS
                    if (noDataMs >= noDataLimit || stalledMs >= stallLimit) {
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

/**
 * RCTV (owner): a channel that never showed a picture after a few tries stops trying and says so,
 * instead of loading for minutes (Back leaves; the error's own button tries again).
 */
private fun PlayerRuntimeController.giveUpLiveTv() {
    Log.w(PlayerRuntimeController.TAG, "LIVE_GIVE_UP: no picture after $liveReconnectAttempts tries")
    errorRetryJob?.cancel()
    runCatching { _exoPlayer?.stop() }
    _uiState.update {
        it.copy(
            error = context.getString(com.nuvio.tv.R.string.player_live_channel_failed, it.title.ifBlank { "this channel" }),
            isBuffering = false,
            showLoadingOverlay = false
        )
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
