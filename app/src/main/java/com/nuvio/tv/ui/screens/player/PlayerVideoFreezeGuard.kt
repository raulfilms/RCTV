@file:androidx.annotation.OptIn(UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * RCTV (owner): a movie or an episode whose picture stops while it goes on playing (the sound goes
 * on, the time goes on, the picture stays) picks up again by itself. It happened coming back to the
 * player from the Now Playing bar (the picture moves from the bar to the full screen) and after
 * jumping back or forward.
 *
 * Every second, while it really plays (not paused, not loading) on a screen: when no new picture has
 * reached the screen for [PICTURE_STILL_MS] while the time went on, the picture is stuck. First it
 * jumps to where it is (the decoder starts over from there, nothing is skipped); if it gets stuck
 * again, the picture's decoder is made anew (the picture off and on). At most [MAX_FIXES] times in a
 * row, a while apart; once pictures flow again for a minute it may try again later.
 *
 * Not for live channels (they have their own watch), the MPV engine or tunneled playback (whose
 * pictures aren't counted the same way).
 */
internal fun PlayerRuntimeController.startVideoFreezeGuard() {
    if (videoFreezeGuardJob?.isActive == true) return
    videoFreezeGuardJob = scope.launch {
        val watch = FreezeWatch()
        while (isActive) {
            delay(FREEZE_CHECK_MS)
            val player = _exoPlayer
            if (player == null) {
                watch.forget(null)
                continue
            }
            if (watch.player !== player) watch.forget(player)
            if (!freezeWatchApplies(player)) {
                watch.resetPicture()
                continue
            }
            val now = SystemClock.elapsedRealtime()
            if (!watch.pictureStuck(player, now)) continue
            if (now - watch.lastFixAtMs < FIX_SPACING_MS || watch.fixesInARow >= MAX_FIXES) continue
            watch.lastFixAtMs = now
            watch.fixesInARow += 1
            val position = player.currentPosition
            Log.i(
                PlayerRuntimeController.TAG,
                "VIDEO_FREEZE: picture stuck, fix=${watch.fixesInARow} positionMs=$position"
            )
            if (watch.fixesInARow == 1) {
                seekPlaybackTo(position, SeekParameters.EXACT)
            } else {
                renewVideoDecoder(player)
            }
            watch.resetPicture()
        }
    }
}

/** Only while it really plays a movie or an episode with ExoPlayer, untunneled. */
private fun PlayerRuntimeController.freezeWatchApplies(player: ExoPlayer): Boolean {
    if (isUsingMpvEngine()) return false
    if (_playbackTimeline.value.isLive || isLiveTvStreamNow()) return false
    if (!hasRenderedFirstFrame) return false
    val state = _uiState.value
    if (state.tunnelingEnabled || !state.error.isNullOrBlank()) return false
    if (pendingPreviewSeekPosition != null) return false
    return player.playbackState == Player.STATE_READY && player.isPlaying
}

/** The picture off and back on: its decoder is made anew, at the same place. */
private suspend fun renewVideoDecoder(player: ExoPlayer) {
    val before = player.trackSelectionParameters
    if (before.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)) return
    player.trackSelectionParameters = before.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
        .build()
    delay(RENEW_GAP_MS)
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
        .build()
}

private class FreezeWatch {
    var player: ExoPlayer? = null
    var lastFixAtMs = 0L
    var fixesInARow = 0

    private var counters: DecoderCounters? = null
    private var lastRendered = -1
    private var stillSinceMs = 0L
    private var stillFromPositionMs = 0L

    /** Another player (or none): everything starts over. */
    fun forget(next: ExoPlayer?) {
        player = next
        lastFixAtMs = 0L
        fixesInARow = 0
        resetPicture()
    }

    fun resetPicture() {
        counters = null
        lastRendered = -1
        stillSinceMs = 0L
        stillFromPositionMs = 0L
    }

    /** No new picture on the screen for a while, while the time went on. */
    fun pictureStuck(player: ExoPlayer, now: Long): Boolean {
        // No screen to draw on (or not known yet), or no picture in what plays: nothing to watch.
        val surface = player.surfaceSize
        if (surface.width <= 0 || surface.height <= 0 || player.videoSize.width <= 0) {
            resetPicture()
            return false
        }
        val current = player.videoDecoderCounters
        if (current == null) {
            resetPicture()
            return false
        }
        current.ensureUpdated()
        val rendered = current.renderedOutputBufferCount
        val position = player.currentPosition
        if (current !== counters || rendered != lastRendered) {
            counters = current
            lastRendered = rendered
            stillSinceMs = now
            stillFromPositionMs = position
            // Pictures flow again: after a good while, it may help again another time.
            if (fixesInARow > 0 && now - lastFixAtMs >= FIXES_FORGOTTEN_AFTER_MS) fixesInARow = 0
            return false
        }
        return now - stillSinceMs >= PICTURE_STILL_MS &&
            position - stillFromPositionMs >= PICTURE_STILL_MS * 3 / 4
    }
}

private const val FREEZE_CHECK_MS = 1_000L
private const val PICTURE_STILL_MS = 3_000L
private const val FIX_SPACING_MS = 8_000L
private const val MAX_FIXES = 3
private const val FIXES_FORGOTTEN_AFTER_MS = 60_000L
private const val RENEW_GAP_MS = 150L
