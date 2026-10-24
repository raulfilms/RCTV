package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.Player
import com.nuvio.tv.core.smartscraper.AutoStreamEngine
import com.nuvio.tv.core.smartscraper.AutoStreamSession
import com.nuvio.tv.core.smartscraper.SmartScraperEnvironment
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * RCTV Auto Stream in the player: a source that won't start (an error, or no picture after a few
 * seconds) is skipped for the title's next valid one on its own, never another title or episode.
 * Whether each source started, failed or kept buffering is learned, to rank better next time.
 */

private const val AUTO_STREAM_TAG = "AutoStream"
private const val WATCH_INTERVAL_MS = 500L
/** Rebuffers after the first picture that say the connection doesn't keep up with this file. */
private const val REBUFFERS_FOR_LEARNING = 3
/** Played this long without rebuffering: the connection keeps up with this file. */
private const val SMOOTH_PLAYBACK_MS = 5L * 60 * 1000

/** Started with the player: watches each Auto Stream source until it shows its first picture. */
internal fun PlayerRuntimeController.startAutoStreamWatch() {
    scope.launch {
        var smoothRecordedAttempt = -1
        var lastAttempt = -1
        var attemptPlayingSinceMs = 0L
        while (isActive) {
            delay(WATCH_INTERVAL_MS)
            val videoId = currentVideoId ?: continue
            val session = AutoStreamSession.current(videoId) ?: continue
            if (session.attempt != lastAttempt) {
                lastAttempt = session.attempt
                attemptPlayingSinceMs = 0L
            }
            val now = System.currentTimeMillis()
            if (session.started) {
                // Played a while without buffering again: files like it are fine on this connection.
                if (smoothRecordedAttempt != session.attempt && rebufferCount == 0 && _uiState.value.isPlaying) {
                    if (attemptPlayingSinceMs == 0L) attemptPlayingSinceMs = now
                    if (now - attemptPlayingSinceMs >= SMOOTH_PLAYBACK_MS) {
                        smoothRecordedAttempt = session.attempt
                        fileBitrateMbps()?.let { SmartScraperEnvironment.recordConnection(null, it, rebuffered = false) }
                    }
                }
                continue
            }
            // RCTV: a debrid error clip ("Debrid not working"...) instead of the movie: the next source.
            val clipDuration = _exoPlayer?.duration?.takeIf { it > 0 }
                ?: _playbackTimeline.value.duration.takeIf { now - session.attemptStartedAtMs > 3_000L }
                ?: 0L
            if (skipIfDebridErrorClip(clipDuration)) continue
            if (hasRenderedFirstFrame) {
                AutoStreamSession.markStarted(videoId)
                SmartScraperEnvironment.recordPlayback(session.current, ok = true)
                Log.i(AUTO_STREAM_TAG, "Started in ${now - session.attemptStartedAtMs} ms (try ${session.attempt + 1})")
                continue
            }
            if (_uiState.value.error != null || isInBackground || userPausedManually) continue
            // The other engine says when it fails; only its errors move on.
            val player = _exoPlayer ?: continue
            if (player.playbackState == Player.STATE_READY && !player.playWhenReady) continue
            if (now - session.attemptStartedAtMs < session.startupTimeoutMs) continue
            tryAutoStreamFallback("no picture after ${session.startupTimeoutMs / 1000} s")
        }
    }
}

/**
 * RCTV: the Auto Stream source turned out to be an addon's error clip (a few seconds: "Debrid not
 * working", "download failed"...), not the movie or episode: the next source plays instead, before
 * the clip is seen. True when it was skipped.
 */
internal fun PlayerRuntimeController.skipIfDebridErrorClip(durationMs: Long): Boolean {
    if (!isShortPlaceholderDuration(durationMs) || _playbackTimeline.value.isLive) return false
    val session = AutoStreamSession.current(currentVideoId) ?: return false
    if (session.started) return false
    return tryAutoStreamFallback("an error clip of ${durationMs / 1000} s, not the title")
}

/**
 * The current Auto Stream source failed ([reason]): the next valid one plays instead, here, at once.
 * False when this isn't an Auto Stream or no source is left (then the error shows as usual).
 */
internal fun PlayerRuntimeController.tryAutoStreamFallback(reason: String): Boolean {
    val videoId = currentVideoId ?: return false
    val session = AutoStreamSession.current(videoId) ?: return false
    val failed = session.current
    SmartScraperEnvironment.recordPlayback(failed, ok = false)
    val next = AutoStreamSession.next(videoId) ?: run {
        Log.w(AUTO_STREAM_TAG, "$reason; no source left to try")
        return false
    }
    Log.w(AUTO_STREAM_TAG, "$reason; trying the next source (${session.fallbacks} of ${session.maxFallbacks})")
    cancelFirstFrameWatchdog()
    cancelStallWatchdog()
    _uiState.update {
        it.copy(
            error = null,
            showSwitchToMpvErrorAction = false,
            showLoadingOverlay = it.loadingOverlayEnabled,
            loadingMessage = context.getString(com.nuvio.tv.R.string.auto_stream_trying_next)
        )
    }
    // Not from inside the player's own callback: the player is released and built again.
    scope.launch { switchToSourceStream(next) }
    return true
}

/** Rebuffering again and again: files of this bitrate are too much for the connection lately. */
internal fun PlayerRuntimeController.onAutoStreamRebuffer(count: Int) {
    if (count < REBUFFERS_FOR_LEARNING) return
    val session = AutoStreamSession.current(currentVideoId) ?: return
    if (session.rebufferingRecorded) return
    val mbps = fileBitrateMbps() ?: return
    AutoStreamSession.markRebufferingRecorded(currentVideoId)
    SmartScraperEnvironment.recordConnection(null, mbps, rebuffered = true)
    Log.i(AUTO_STREAM_TAG, "Kept buffering at %.1f Mbps: lighter files will rank first".format(mbps))
}

/** The playing file's average bitrate (Mbps), from its size and length. */
private fun PlayerRuntimeController.fileBitrateMbps(): Double? {
    val size = currentVideoSize?.takeIf { it > 0 } ?: return null
    val durationMs = _exoPlayer?.duration?.takeIf { it > 0 } ?: return null
    return size * 8.0 / (durationMs / 1000.0) / 1_000_000.0
}

/** What a source is ("4K · 5.7 GB · 5.1 · HDR10"), never where it comes from. */
internal fun sourceSummary(stream: Stream): String? {
    val release = SmartScraperEnvironment.normalize(stream).release
    return AutoStreamEngine.summary(release).ifBlank {
        com.nuvio.tv.ui.util.SourcePrivacy.streamLabel(stream, "").title.ifBlank { null }
    }
}
