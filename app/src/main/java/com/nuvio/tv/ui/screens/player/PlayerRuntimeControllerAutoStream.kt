package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.Player
import com.nuvio.tv.core.smartscraper.AutoStreamConfig
import com.nuvio.tv.core.smartscraper.AutoStreamEngine
import com.nuvio.tv.core.smartscraper.AutoStreamLinkCheck
import com.nuvio.tv.core.smartscraper.AutoStreamResolver
import com.nuvio.tv.core.smartscraper.AutoStreamSession
import com.nuvio.tv.core.smartscraper.AutoStreamTrackCheck
import com.nuvio.tv.core.smartscraper.SmartScraperEnvironment
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * RCTV Auto Stream in the player (v2.0, §8): each source's tracks are checked before its first
 * picture (5.1 or 2.0, 480p or more, English or the title's own audio); one that fails, won't start
 * or breaks is skipped for the title's next valid one, its link checked first, never another title
 * or episode. Before any picture, nothing is started past the startup deadline: the sources are
 * shown instead. After the picture, the next source carries on where it was, with a short notice.
 * Whether each source started, failed or kept buffering is learned, to rank better next time.
 */

private const val AUTO_STREAM_TAG = "AutoStream"
private const val WATCH_INTERVAL_MS = 500L
/** Rebuffers after the first picture that say the connection doesn't keep up with this file. */
private const val REBUFFERS_FOR_LEARNING = 3
/** Played this long without rebuffering: the connection keeps up with this file. */
private const val SMOOTH_PLAYBACK_MS = 5L * 60 * 1000
private const val NOTICE_MS = 3_000L
private const val GIVE_UP_NOTICE_MS = 5_000L

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
            if (session.switching) continue
            // §8.3: the player didn't say the tracks in time: it plays as it is.
            if (session.prePlayCheckPending && now - session.attemptStartedAtMs >= session.prePlayCheckTimeoutMs) {
                Log.i(AUTO_STREAM_TAG, "Tracks not reported in ${session.prePlayCheckTimeoutMs} ms: playing unchecked")
                AutoStreamSession.markPrePlayChecked(videoId)
                releaseAutoStreamHold()
            }
            // RCTV: a debrid error clip ("Debrid not working"...) instead of the movie: the next source.
            val clipDuration = _exoPlayer?.duration?.takeIf { it > 0 }
                ?: _playbackTimeline.value.duration.takeIf { now - session.attemptStartedAtMs > 3_000L }
                ?: 0L
            if (skipIfDebridErrorClip(clipDuration)) continue
            if (skipIfWrongLength(clipDuration)) continue
            if (hasRenderedFirstFrame && !session.prePlayCheckPending) {
                AutoStreamSession.markStarted(videoId)
                SmartScraperEnvironment.recordPlayback(session.current, ok = true)
                Log.i(
                    AUTO_STREAM_TAG,
                    "First picture ${now - session.startedAtMs} ms after Play " +
                        "(this source ${now - session.attemptStartedAtMs} ms, try ${session.attempt + 1})"
                )
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
 * §8.3: mpv starts the Auto Stream source paused until its tracks are checked (ExoPlayer holds
 * itself until it's ready anyway). True when it should start paused now.
 */
internal fun PlayerRuntimeController.holdMpvStartForAutoStreamCheck(): Boolean {
    val session = AutoStreamSession.current(currentVideoId) ?: return false
    if (!session.prePlayCheckPending || session.started || userPausedManually) return false
    session.holdingStart = true
    return true
}

/** The check is done: an mpv source held for it plays now. */
private fun PlayerRuntimeController.releaseAutoStreamHold() {
    val session = AutoStreamSession.current(currentVideoId) ?: return
    if (!session.holdingStart) return
    session.holdingStart = false
    if (isUsingMpvEngine() && !userPausedManually) mpvView?.setPaused(false)
}

/**
 * §8.3: the Auto Stream source's tracks, once the player says them, before its first picture: its
 * audio 5.1 or 2.0, its picture 480p or more, English or the title's own audio (J: an audio not said
 * that turns out to be 7.1 is skipped here). One that fails is skipped for the next, without a word.
 */
internal fun PlayerRuntimeController.checkAutoStreamTracks() {
    val videoId = currentVideoId ?: return
    val session = AutoStreamSession.current(videoId) ?: return
    if (!session.prePlayCheckPending || session.started || session.switching) return
    val audio = _uiState.value.audioTracks
    if (audio.isEmpty()) return
    val size = if (isUsingMpvEngine()) mpvView?.videoSizeNow() else null
    val width = size?.first ?: currentVideoWidth
    val height = size?.second ?: currentVideoHeight
    val violation = AutoStreamTrackCheck.check(
        audio = audio.map { AutoStreamTrackCheck.AudioTrack(it.language, it.channelCount) },
        videoWidth = width,
        videoHeight = height,
        wantedLanguages = session.wantedLanguages,
        config = SmartScraperEnvironment.config.autoStream
    )
    AutoStreamSession.markPrePlayChecked(videoId)
    if (violation == null) {
        Log.i(AUTO_STREAM_TAG, "Tracks checked ${System.currentTimeMillis() - session.attemptStartedAtMs} ms into this source: fine")
        releaseAutoStreamHold()
        return
    }
    tryAutoStreamFallback("before its first picture: ${violation.description}")
}

/**
 * RCTV: the Auto Stream source turned out to be an addon's error clip (a few seconds: "Debrid not
 * working", "download failed"...), not the movie or episode: the next source plays instead, before
 * the clip is seen. True when it was skipped.
 */
internal fun PlayerRuntimeController.skipIfDebridErrorClip(durationMs: Long): Boolean {
    if (!isShortPlaceholderDuration(durationMs) || _playbackTimeline.value.isLive || isLiveTvPlayback) return false
    val session = AutoStreamSession.current(currentVideoId)
    if (session != null) {
        if (session.started) return false
        return tryAutoStreamFallback("an error clip of ${durationMs / 1000} s, not the title")
    }
    // RCTV: a source chosen by hand, a movie's or an episode's, that's a clip of a minute or two.
    if (!isMovieOrEpisode()) return false
    stopForUnavailableSource("an error clip of ${durationMs / 1000} s, not the title")
    return true
}

/**
 * RCTV (owner's rule): a movie is the movie. Auto Stream's source much shorter or longer than the
 * movie (a 50-minute making-of for a 2 h 49 min movie) is something else: the next source plays.
 * An Extended or Director's Cut is a little longer and plays.
 */
internal fun PlayerRuntimeController.skipIfWrongLength(durationMs: Long): Boolean {
    if (durationMs <= 0L || _playbackTimeline.value.isLive || isLiveTvPlayback) return false
    if (contentType?.lowercase() != "movie") return false
    val session = AutoStreamSession.current(currentVideoId) ?: return false
    if (session.started || session.switching) return false
    val expected = runtimeMinutesOf(_uiState.value.runtime) ?: return false
    val minutes = durationMs / 60_000.0
    if (minutes >= expected * MIN_LENGTH_SHARE && minutes <= expected * MAX_LENGTH_SHARE) return false
    return tryAutoStreamFallback("${minutes.toInt()} min long, the movie is $expected min: not the movie")
}

/**
 * A source shorter than this share of the movie's length isn't the movie... RCTV (owner's rule):
 * 0.8, not 0.7: a 2 h 30 min movie's file runs 2 hours at least.
 */
private const val MIN_LENGTH_SHARE = 0.8

/** ...nor one longer than this (an Extended or Director's Cut is well within it). */
private const val MAX_LENGTH_SHARE = 1.8

/** "169", "169 min", "2h 49m" → minutes; null when it doesn't say, or says less than 40. */
internal fun runtimeMinutesOf(text: String?): Int? {
    val value = text?.lowercase()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val hours = Regex("""(\d+)\s*h""").find(value)?.groupValues?.get(1)?.toIntOrNull()
    val minutes = Regex("""(\d+)\s*m""").find(value)?.groupValues?.get(1)?.toIntOrNull()
    val total = if (hours != null || minutes != null) (hours ?: 0) * 60 + (minutes ?: 0) else Regex("""\d+""").find(value)?.value?.toIntOrNull()
    return total?.takeIf { it >= 40 }
}

private fun PlayerRuntimeController.isMovieOrEpisode(): Boolean =
    contentType?.lowercase().let { it == "movie" || it == "series" }

/** Asked once more at most this long before the player gets the link. */
private const val PRE_PLAY_TIMEOUT_MS = 8_000L

/**
 * RCTV: before any source plays (one chosen by hand, the next episode's, Auto Stream's), its link
 * is asked for its first bytes: one that isn't there (no file, an addon's error clip, an error page,
 * the service refusing it) is never put on. Auto Stream then plays its next source; otherwise the
 * sources are shown with a notice that says nothing of the service. A link that checked out a
 * moment ago (Auto Stream's own check), a live channel, a file on this device: not asked. A link
 * that doesn't answer in time: the player tries it (its own error shows if it fails).
 */
internal suspend fun PlayerRuntimeController.passesPrePlayCheck(url: String, headers: Map<String, String>): Boolean {
    if (isLiveTvPlayback || !isMovieOrEpisode() || !isRemoteLink(url) || AutoStreamLinkVerifier.recentlyOk(url)) return true
    val check = AutoStreamLinkVerifier.verify(
        url = url,
        headers = headers,
        knownSizeBytes = null,
        maxBytes = Long.MAX_VALUE,
        timeoutMs = PRE_PLAY_TIMEOUT_MS
    )
    if (!AutoStreamLinkCheck.isNotThere(check)) return true
    val reason = "its link: ${check.outcome.description}${check.httpCode?.let { " (HTTP $it)" }.orEmpty()}"
    if (AutoStreamSession.current(currentVideoId) != null && tryAutoStreamFallback(reason)) return false
    stopForUnavailableSource(reason)
    return false
}

/** A link on another machine (not this device's torrent server or a local file). */
private fun isRemoteLink(url: String): Boolean {
    val lower = url.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
    val host = lower.substringAfter("://").substringBefore('/').substringBefore(':')
    return host != "localhost" && host != "127.0.0.1" && host != "[" && !host.startsWith("[")
}

/**
 * RCTV: a source chosen by hand isn't there: nothing of it shows (no error clip, no service's
 * name); the sources are shown to choose another, with a short notice.
 */
private fun PlayerRuntimeController.stopForUnavailableSource(reason: String) {
    Log.w(AUTO_STREAM_TAG, "A source isn't there ($reason): not played, the sources are shown")
    cancelFirstFrameWatchdog()
    cancelStallWatchdog()
    runCatching {
        _exoPlayer?.playWhenReady = false
        _exoPlayer?.stop()
    }
    if (isUsingMpvEngine()) mpvView?.setPaused(true)
    _uiState.update {
        it.copy(
            error = null,
            showSwitchToMpvErrorAction = false,
            showLoadingOverlay = false,
            loadingMessage = null,
            isBuffering = false
        )
    }
    showPlayerNotice(context.getString(com.nuvio.tv.R.string.player_source_unavailable), GIVE_UP_NOTICE_MS)
    showSourcesPanel()
}

/**
 * The current Auto Stream source failed ([reason]): the next valid one whose link checks out
 * plays instead, here. Before any picture, none is started past the startup deadline: then the
 * sources are shown, saying no automatic stream was found (§8.4). After the picture, the next one
 * carries on from the same moment, with a short notice. False when this isn't an Auto Stream, or no
 * source is left after it had played (then the error shows as usual).
 */
internal fun PlayerRuntimeController.tryAutoStreamFallback(reason: String): Boolean {
    val videoId = currentVideoId ?: return false
    val session = AutoStreamSession.current(videoId) ?: return false
    if (session.switching) return true
    val failed = session.current
    SmartScraperEnvironment.recordPlayback(failed, ok = false)
    val resumeAt = if (session.everStarted) {
        (currentPlaybackPositionMs() ?: _playbackTimeline.value.currentPosition).takeIf { it > 0L }
    } else {
        null
    }
    val upcoming = session.upcoming
    val left = AutoStreamSession.fallbacksLeft(videoId)
    if (upcoming.isEmpty() || left <= 0 || (!session.everStarted && session.pastDeadline())) {
        if (session.everStarted) {
            Log.w(AUTO_STREAM_TAG, "$reason; no source left to try")
            AutoStreamSession.end()
            return false
        }
        autoStreamGiveUp(reason)
        return true
    }
    Log.w(AUTO_STREAM_TAG, "$reason; looking for the next source (${session.fallbacks} of ${session.maxFallbacks} tried)")
    session.switching = true
    // Before its picture, the failed source mustn't start while the next one's link is checked.
    val beforePicture = !session.started
    if (beforePicture) {
        runCatching { _exoPlayer?.playWhenReady = false }
        if (isUsingMpvEngine()) mpvView?.setPaused(true)
    }
    cancelFirstFrameWatchdog()
    cancelStallWatchdog()
    _uiState.update {
        it.copy(
            error = null,
            showSwitchToMpvErrorAction = false,
            showLoadingOverlay = it.loadingOverlayEnabled,
            loadingMessage = context.getString(com.nuvio.tv.R.string.stream_finding_source)
        )
    }
    val config = SmartScraperEnvironment.config.autoStream
    scope.launch {
        if (beforePicture) runCatching { _exoPlayer?.stop() }
        val tried = ArrayList<Stream>()
        val found = AutoStreamResolver.firstPlayable(
            candidates = upcoming,
            deadlineAtMs = if (session.everStarted) Long.MAX_VALUE else session.deadlineAtMs,
            attemptTimeoutMs = (config.resolveVerifyTimeoutSeconds * 1_000).toLong(),
            maxAttempts = left,
            providerOf = AutoStreamResolver::providerOf,
            onFailure = { candidate, failure ->
                tried += candidate
                SmartScraperEnvironment.recordPlayback(candidate, ok = false)
                Log.i(AUTO_STREAM_TAG, "A source failed its check: ${failure.reason}" + if (failure.providerLevel) " (its provider's)" else "")
            }
        ) { candidate -> resolveAndVerifyForPlayer(candidate, config) }
        // Someone chose a source themselves, or the title changed, meanwhile.
        if (AutoStreamSession.current(videoId) !== session) return@launch
        if (found == null || !AutoStreamSession.advanceTo(videoId, found.stream, tried)) {
            session.switching = false
            if (session.everStarted) {
                Log.w(AUTO_STREAM_TAG, "$reason; no other source plays")
                AutoStreamSession.end()
                _uiState.update {
                    it.copy(
                        showLoadingOverlay = false,
                        loadingMessage = null,
                        error = context.getString(com.nuvio.tv.R.string.player_error_play_stream_failed)
                    )
                }
            } else {
                autoStreamGiveUp(reason)
            }
            return@launch
        }
        switchToSourceStream(found.value)
        if (resumeAt != null) {
            _uiState.update { it.copy(pendingSeekPosition = resumeAt) }
            showPlayerNotice(context.getString(com.nuvio.tv.R.string.auto_stream_switched_source), NOTICE_MS)
        }
    }
    return true
}

/** One Auto Stream source for the player: its link (from the debrid service when needed), then its first bytes. */
private suspend fun PlayerRuntimeController.resolveAndVerifyForPlayer(
    candidate: Stream,
    config: AutoStreamConfig
): AutoStreamResolver.Attempt<Stream> {
    val playable = if (candidate.isTorrent() || (candidate.getStreamUrl().isNullOrBlank() && candidate.isDirectDebrid())) {
        resolveDirectDebridStreamIfNeeded(candidate, currentSeason, currentEpisode)
            ?: return AutoStreamResolver.Attempt.Failed("no link", providerLevel = true)
    } else {
        candidate
    }
    val url = playable.getStreamUrl()?.takeIf { it.isNotBlank() }
        ?: return AutoStreamResolver.Attempt.Failed("no link", providerLevel = true)
    val release = SmartScraperEnvironment.normalize(candidate).release
    val isSeries = contentType in listOf("series", "tv")
    val check = AutoStreamLinkVerifier.verify(
        url = url,
        headers = PlayerMediaSourceFactory.sanitizeHeaders(playable.behaviorHints?.proxyHeaders?.request),
        knownSizeBytes = release.sizeBytes?.takeIf { !(release.sizeIsPack && isSeries) },
        maxBytes = config.maxFileSizeBytesFor(runtimeMinutesOf(_uiState.value.runtime), isSeries),
        timeoutMs = (config.resolveVerifyTimeoutSeconds * 1_000).toLong()
    )
    return if (check.ok) {
        AutoStreamResolver.Attempt.Ok(playable)
    } else {
        AutoStreamResolver.Attempt.Failed(
            "${check.outcome.description}${check.httpCode?.let { " (HTTP $it)" }.orEmpty()}",
            providerLevel = check.providerLevel
        )
    }
}

/**
 * §8.4: nothing playable before the startup deadline (or nothing left to try): playback stops, the
 * sources are shown, and why. Hard rules are never relaxed to find something.
 */
private fun PlayerRuntimeController.autoStreamGiveUp(reason: String) {
    val session = AutoStreamSession.current(currentVideoId)
    val elapsed = session?.let { System.currentTimeMillis() - it.startedAtMs }
    Log.w(AUTO_STREAM_TAG, "$reason; nothing playable${elapsed?.let { " $it ms after Play" }.orEmpty()}: the sources are shown")
    AutoStreamSession.end()
    cancelFirstFrameWatchdog()
    cancelStallWatchdog()
    runCatching { _exoPlayer?.stop() }
    if (isUsingMpvEngine()) mpvView?.setPaused(true)
    _uiState.update {
        it.copy(
            error = null,
            showSwitchToMpvErrorAction = false,
            showLoadingOverlay = false,
            loadingMessage = null,
            isBuffering = false
        )
    }
    showPlayerNotice(context.getString(com.nuvio.tv.R.string.auto_stream_nothing_found), GIVE_UP_NOTICE_MS)
    showSourcesPanel()
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
