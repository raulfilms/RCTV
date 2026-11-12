package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * RCTV (Networks): switches the Live TV player to another channel without leaving the player.
 * The engine playing now (ExoPlayer or mpv) is kept and handed the new channel; it's only built
 * again when there is none, when the last channel needed one of the player's fallbacks (audio
 * off, safe audio, a codec workaround), or when handing it over fails. A newer switch cancels an
 * older one that hasn't started yet. A channel that fails to play goes through the player's usual
 * live reconnects and then its error screen, whose retry plays this channel again.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.switchToLiveChannel(url: String, name: String, logo: String?) {
    liveChannelSwitchJob?.cancel()
    liveChannelSwitchJob = null
    if (url.isBlank()) {
        _uiState.update { it.copy(error = context.getString(com.nuvio.tv.R.string.player_error_no_stream_url)) }
        return
    }
    // The last channel's fallbacks live in the player itself: a fresh one for the new channel.
    val needsFreshPlayer = isAudioDisabledForCurrentPlayback ||
        isSafeAudioModeActiveForCurrentPlayback ||
        pendingAudioPcmFallbackRebuild ||
        isVc1TrackSelectionBypassActiveForCurrentPlayback ||
        isExperimentalDv7ToDv81ActiveForCurrentPlayback

    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    errorRetryJob?.cancel()
    errorRetryJob = null
    stopLiveTvWatchdog()

    val request = PlayerMediaSourceFactory.normalizePlaybackRequest(url, emptyMap())
    currentStreamUrl = request.url
    currentHeaders = request.headers
    currentFilename = null
    currentStreamResponseHeaders = emptyMap()
    currentStreamMimeType = PlayerMediaSourceFactory.inferMimeType(
        url = request.url,
        filename = null,
        responseHeaders = emptyMap()
    )
    currentStreamCacheKey = null
    parsingErrorProbeAttempted = false
    resetErrorRetryState()
    lastSavedPosition = 0L
    startupEngineFailoverTriggered = false
    autoSubtitleSelected = false
    hasScannedTextTracksOnce = false
    lastPlaybackDiagnosticsForReport = LastPlaybackDiagnostics.EMPTY
    lastPlaybackIssueError = null
    playbackAnalyticsDiagnostics.reset()
    resetLoadingOverlayForNewStream()

    _uiState.update {
        it.copy(
            title = name,
            contentName = name,
            logo = logo,
            error = null,
            isBuffering = true,
            showPauseOverlay = false,
            playbackEnded = false,
            currentStreamUrl = request.url,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            selectedAudioTrackIndex = -1,
            selectedSubtitleTrackIndex = -1
        )
    }

    val playbackUrl = currentStreamUrl
    val playbackHeaders = currentHeaders

    if (!needsFreshPlayer && isUsingMpvEngine()) {
        val view = mpvView
        if (view != null) {
            // The same way a live reconnect hands mpv the stream again.
            hasRenderedFirstFrame = false
            view.applyLiveStreamTuning(true)
            val (mpvUrl, mpvHeaders) = mpvMediaFor(playbackUrl, playbackHeaders)
            view.setMediaUsingLoadfile(mpvUrl, mpvHeaders)
            view.setPaused(false)
            startLiveTvWatchdog(playbackUrl)
            liveStuckSinceMs = SystemClock.elapsedRealtime()
            return
        }
    }

    val player = _exoPlayer
    if (needsFreshPlayer || player == null || isUsingMpvEngine()) {
        releasePlayer(flushPlaybackState = false)
        initializePlayer(playbackUrl, playbackHeaders)
        return
    }

    liveChannelSwitchJob = scope.launch {
        try {
            val playerSettings = playerSettingsDataStore.playerSettings.first()
            runAfrPreflightIfEnabled(
                url = playbackUrl,
                headers = playbackHeaders,
                frameRateMatchingMode = playerSettings.frameRateMatchingMode,
                resolutionMatchingEnabled = playerSettings.resolutionMatchingEnabled,
                mimeType = currentStreamMimeType
            )
            // A newer switch (or leaving the player) since: this one is outdated.
            if (_exoPlayer !== player || currentStreamUrl != playbackUrl) return@launch
            player.stop()
            player.setMediaSource(
                mediaSourceFactory.createMediaSource(
                    context = context,
                    url = playbackUrl,
                    headers = playbackHeaders,
                    filename = currentFilename,
                    responseHeaders = currentStreamResponseHeaders,
                    mimeTypeOverride = currentStreamMimeType,
                    audioDelayUsProvider = audioDelayUs::get,
                    cacheKey = currentStreamCacheKey
                )
            )
            player.playWhenReady = true
            player.prepare()
            startLiveTvWatchdog(playbackUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Only the error's kind: a channel's address can carry the IPTV login.
            Log.w(PlayerRuntimeController.TAG, "LIVE_SWITCH: handing over failed (${e.javaClass.simpleName}), building the player again")
            if (currentStreamUrl != playbackUrl) return@launch
            releasePlayer(flushPlaybackState = false)
            initializePlayer(playbackUrl, playbackHeaders)
        }
    }
}
