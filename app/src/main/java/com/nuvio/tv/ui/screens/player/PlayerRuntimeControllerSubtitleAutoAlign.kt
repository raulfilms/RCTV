package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.core.subsync.CueSpan
import com.nuvio.tv.core.subsync.SpeechActivityAnalyzer
import com.nuvio.tv.core.subsync.SpeechWindow
import com.nuvio.tv.core.subsync.SubtitleAligner
import com.nuvio.tv.core.subsync.SubtitleAlignment
import com.nuvio.tv.domain.model.Subtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/*
 * RCTV: every subtitle that doesn't come inside the file (OpenSubtitles, AIOStreams, Torrentio, any
 * addon) is synced to the movie's own audio, on its own, as the movie plays: the app hears where
 * people talk in a few stretches of the movie (decoding them in the background, from the stream) and
 * finds the shift (and, for a subtitle made for another frame rate, the stretch) where the subtitle's
 * lines fall on the speech. Nothing to do and nothing stops: the subtitle moves into place within
 * seconds. Only applied when the match is clear (a subtitle of another cut or another movie is left
 * as it is), and never over a delay set by hand meanwhile.
 */

private const val TAG = "SubtitleAutoSync"
/** Stretches of the movie heard first (where in it, 0..1), and more when those don't settle it. */
private val FIRST_WINDOWS = listOf(0.12, 0.30, 0.48, 0.66, 0.84)
private val MORE_WINDOWS = listOf(0.21, 0.39, 0.57, 0.75)
private const val WINDOW_MS = 36_000L
/** Shorter than this (a trailer, a clip): left alone. */
private const val MIN_DURATION_MS = 5L * 60 * 1000
private const val DURATION_WAIT_MS = 90_000L
/** The movie's start isn't slowed down by this: it begins a moment after the picture shows. */
private const val START_AFTER_FIRST_FRAME_MS = 3_000L
private const val NOTICE_MS = 4_000L

/** The speech heard in the last streams (another subtitle for one of them lines up at once). */
private object HeardSpeech {
    private const val KEPT = 3
    private val streams = LinkedHashMap<String, List<SpeechWindow>>()

    @Synchronized
    fun get(url: String): List<SpeechWindow>? = streams[url]

    @Synchronized
    fun put(url: String, speech: List<SpeechWindow>) {
        streams.remove(url)
        streams[url] = speech
        while (streams.size > KEPT) streams.remove(streams.keys.first())
    }
}

/** Watches the subtitle chosen: each external one is synced to the audio as soon as it's on. */
internal fun PlayerRuntimeController.startSubtitleAutoAlignWatch() {
    scope.launch {
        _uiState.map { it.selectedAddonSubtitle }.distinctUntilChanged().collect { subtitle ->
            subtitleAutoAlignJob?.cancel()
            setSubtitleTimeScale(1.0)
            if (subtitle == null) return@collect
            subtitleAutoAlignJob = scope.launch {
                try {
                    autoAlignSubtitle(subtitle)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't sync the subtitle: ${e.message}")
                }
            }
        }
    }
}

private suspend fun PlayerRuntimeController.autoAlignSubtitle(subtitle: Subtitle) {
    val streamUrl = currentStreamUrl
    if (!SpeechActivityAnalyzer.canAnalyze(streamUrl)) return
    val headers = currentHeaders
    val durationMs = awaitPlaying() ?: return
    if (durationMs < MIN_DURATION_MS || _playbackTimeline.value.isLive) return
    delay(START_AFTER_FIRST_FRAME_MS)
    if (!stillOn(subtitle, streamUrl)) return
    val delayBefore = _uiState.value.subtitleDelayMs
    val startedAt = SystemClock.elapsedRealtime()

    val cues = subtitleCueSpans(subtitle)
    if (cues == null || cues.size < 20) {
        Log.i(TAG, "Too few lines to sync: left as it is")
        return
    }
    showPlayerNotice(context.getString(R.string.subtitle_auto_sync_working), NOTICE_MS)

    var speech = HeardSpeech.get(streamUrl) ?: hear(streamUrl, headers, windowsOf(durationMs, FIRST_WINDOWS))
        ?.also { HeardSpeech.put(streamUrl, it) }
        ?: return
    var alignment = align(cues, speech)
    if (alignment == null && speech.size <= FIRST_WINDOWS.size && stillOn(subtitle, streamUrl)) {
        // Not settled yet: a few more stretches of the movie.
        hear(streamUrl, headers, windowsOf(durationMs, MORE_WINDOWS))?.let { more ->
            speech = (speech + more).sortedBy { it.startMs }
            HeardSpeech.put(streamUrl, speech)
            alignment = align(cues, speech)
        }
    }
    val found = alignment
    if (found == null) {
        Log.i(TAG, "No clear match for the subtitle (another cut or another movie?): left as it is")
        return
    }
    // Still the same subtitle on the same stream, and its timing not changed by hand meanwhile.
    if (!stillOn(subtitle, streamUrl) || _uiState.value.subtitleDelayMs != delayBefore) return
    if (found.scale != 1.0 && !canStretchSubtitles()) {
        Log.i(TAG, "The subtitle is for another frame rate (x${found.scale}): this subtitle path can't stretch it")
        return
    }
    val delayMs = SubtitleAligner.delayMs(found).coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    setSubtitleTimeScale(found.scale)
    applyAutoAlignedDelay(delayMs)
    Log.i(
        TAG,
        "Subtitle synced: ${delayMs} ms, x${found.scale}, confidence ${"%.1f".format(found.confidence)}, " +
            "in ${SystemClock.elapsedRealtime() - startedAt} ms"
    )
    showPlayerNotice(
        if (abs(delayMs) < 100 && found.scale == 1.0) {
            context.getString(R.string.subtitle_auto_sync_already)
        } else {
            context.getString(R.string.subtitle_auto_sync_done, formatAutoSyncDelay(delayMs))
        },
        NOTICE_MS
    )
}

private fun PlayerRuntimeController.stillOn(subtitle: Subtitle, streamUrl: String): Boolean =
    _uiState.value.selectedAddonSubtitle == subtitle && currentStreamUrl == streamUrl

/** The movie's length, once it plays (null: it didn't start in time). */
private suspend fun PlayerRuntimeController.awaitPlaying(): Long? {
    val deadline = SystemClock.elapsedRealtime() + DURATION_WAIT_MS
    while (SystemClock.elapsedRealtime() < deadline) {
        val duration = maxOf(lastKnownDuration, _playbackTimeline.value.duration)
        if (duration > 0 && hasRenderedFirstFrame) return duration
        delay(500)
    }
    return null
}

private fun windowsOf(durationMs: Long, fractions: List<Double>): List<LongRange> =
    fractions.map { fraction ->
        val start = (durationMs * fraction).toLong()
        start..(start + WINDOW_MS)
    }.filter { it.last < durationMs }

/** The speech in [windows] of the stream (decoded in the background); null when its audio can't be read. */
private suspend fun hear(url: String, headers: Map<String, String>, windows: List<LongRange>): List<SpeechWindow>? =
    withContext(Dispatchers.IO) {
        SpeechActivityAnalyzer.analyze(url, headers, windows) { !isActive }
    }

private suspend fun align(cues: List<CueSpan>, speech: List<SpeechWindow>): SubtitleAlignment? =
    withContext(Dispatchers.Default) { SubtitleAligner.align(cues, speech) }

/** The subtitle's lines (their times as its file has them); null when it can't be downloaded. */
private suspend fun PlayerRuntimeController.subtitleCueSpans(subtitle: Subtitle): List<CueSpan>? {
    val body = try {
        downloadSubtitleBody(subtitle.url, subtitle.lang, subtitle.headers)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't download the subtitle: ${e.message}")
        return null
    }
    return withContext(Dispatchers.Default) { subtitleCueSpansOf(body, subtitle.url) }
}

/** Stretching a subtitle works on the app's own subtitle drawing and on mpv. */
private fun PlayerRuntimeController.canStretchSubtitles(): Boolean =
    isUsingMpvEngine() || isSidecarAddonSubtitleActive()

/** A subtitle's lines at [scale] times their file's times (1: as they are). */
internal fun PlayerRuntimeController.setSubtitleTimeScale(scale: Double) {
    if (subtitleTimeScale == scale) return
    subtitleTimeScale = scale
    if (isUsingMpvEngine()) mpvView?.setSubtitleSpeed(scale)
    if (isSidecarAddonSubtitleActive()) {
        lastSidecarCueSignature = null
        renderSidecarCuesAtCurrentPosition()
    }
}

/** The found delay, set like the delay control sets it (without showing the control). */
private fun PlayerRuntimeController.applyAutoAlignedDelay(delayMs: Int) {
    subtitleDelayUs.set(delayMs.toLong() * 1000L)
    if (isUsingMpvEngine()) mpvView?.setSubtitleDelayMs(delayMs)
    _uiState.update { it.copy(subtitleDelayMs = delayMs) }
    refreshActiveSubtitleTrackAfterTimingChange()
    persistTrackPreference()
}
