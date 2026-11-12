package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import com.nuvio.tv.R
import com.nuvio.tv.core.subsync.CueSpan
import com.nuvio.tv.core.subsync.SpeechActivityAnalyzer
import com.nuvio.tv.core.subsync.SpeechWindow
import com.nuvio.tv.core.subsync.SubtitleAligner
import com.nuvio.tv.core.subsync.SubtitleAlignment
import com.nuvio.tv.core.subsync.SubtitleOffsetMap
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToLong

/*
 * RCTV: every subtitle that doesn't come inside the file (OpenSubtitles, AIOStreams, Torrentio, any
 * addon) is synced to the movie's own audio, on its own, as the movie plays, and kept in sync to the
 * end (the way a streaming service's subtitles are made for its own video):
 *
 * 1. Within seconds, when the stream is a file the app can read: a few stretches across the movie
 *    are heard (decoded in the background) and the shift (and, for a subtitle made for another frame
 *    rate, the stretch) where the subtitle's lines fall on the speech is set.
 * 2. As the movie plays, from the sound the player decodes ([LiveSpeechTap]; any source: HLS, this
 *    device's torrent server, a file): every half minute the last two minutes heard are checked.
 *    Where the lines no longer fall on the speech (a subtitle of another cut: a scene more or less,
 *    a longer logo), the subtitle moves from there on. When the stream couldn't be read, the first
 *    match comes from that sound too, after two or three minutes.
 * 3. When the sound goes out undecoded (passthrough to a receiver) or mpv plays: a minute read from
 *    the stream, just ahead, every few minutes, does the same.
 *
 * The first time a language is chosen on a stream, every file of that language the subtitle sources
 * offer is compared with the same speech, and the one that fits the movie best is put on (a file
 * picked by hand after that is kept).
 *
 * Only applied when the match is clear (a subtitle of another movie is left as it is), and the sync
 * stops at once when the delay is set by hand. Subtitles inside the file are made for it: left alone.
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

/** Following the subtitle along the movie: how often the loop runs, and looks at the speech heard. */
private const val FOLLOW_TICK_MS = 1_000L
private const val LOOK_EVERY_MS = 30_000L
/** ...the last this much of the movie that played, when at least [MIN_RECENT_HEARD_MS] of it was heard. */
private const val RECENT_MS = 120_000L
private const val MIN_RECENT_HEARD_MS = 60_000L
/** A second look this much later (half of it new) must agree before the subtitle moves. */
private const val CONFIRM_AFTER_MS = 60_000L
/** A shift this far from the one in use moves the subtitle (closer: it is in place); looks agree within [AGREE_MS]. */
private const val MOVE_MS = 200L
private const val AGREE_MS = 150L
/** Looks with no clear match in a row before the search widens (a long scene of another cut). */
private const val MISSES_BEFORE_WIDE = 3
private const val WIDE_RANGE_MS = 180_000L
/**
 * No match yet (the stream can't be read here): tried from the sound played once this much is heard,
 * every so often, up to... RCTV: from 1 minute, every 20 s (it was 2.5 minutes, every minute): on the
 * Mi Box the stream is never read (Android 9), so this is the only first match there, and the lines
 * were out of step for minutes. A match still needs the aligner's full confidence.
 */
private const val FIRST_MATCH_HEARD_MS = 60_000L
private const val FIRST_MATCH_EVERY_MS = 20_000L
private const val FIRST_MATCH_GIVE_UP_HEARD_MS = 15L * 60 * 1000
/** Decoded sound waited for this long before the stream is read instead (passthrough). */
private const val NO_PCM_WAIT_MS = 45_000L
/** Without decoded sound: a stretch this long, this far ahead, read from the stream this often. */
private const val AHEAD_MS = 60_000L
private const val AHEAD_LEAD_MS = 15_000L
private const val AHEAD_EVERY_MS = 6L * 60 * 1000

/** Files of the chosen language compared at most (each a small download), each given this long. */
private const val MAX_SIBLINGS = 10
private const val SIBLING_TIMEOUT_MS = 15_000L
/** Another file is put on when it stands out this much more clearly than the one chosen. */
private const val SWITCH_MARGIN = 1.0

/**
 * RCTV: per stream, the subtitle languages whose files were compared with the movie's speech, and
 * the file the sync picked (with its match, so it's on in place at once).
 */
internal class SubtitleChoiceState {
    private var streamUrl: String? = null
    private val compared = HashSet<String>()
    private var picked: Pair<Subtitle, SubtitleAlignment>? = null

    private fun on(url: String) {
        if (url == streamUrl) return
        streamUrl = url
        compared.clear()
        picked = null
    }

    @Synchronized
    fun wasCompared(url: String, language: String): Boolean {
        on(url)
        return language in compared
    }

    @Synchronized
    fun markCompared(url: String, language: String) {
        on(url)
        compared += language
    }

    @Synchronized
    fun pick(url: String, subtitle: Subtitle, alignment: SubtitleAlignment) {
        on(url)
        picked = subtitle to alignment
    }

    /** The match for [subtitle] when the sync itself just put it on (once). */
    @Synchronized
    fun takePicked(url: String, subtitle: Subtitle): SubtitleAlignment? {
        on(url)
        val choice = picked?.takeIf { it.first == subtitle } ?: return null
        picked = null
        return choice.second
    }
}

/** One subtitle being synced on one stream. */
private class SubtitleSync(
    val subtitle: Subtitle,
    val streamUrl: String,
    val headers: Map<String, String>,
    val cues: List<CueSpan>
) {
    var scale = 1.0
    /** The shift along the movie; empty until the first match. */
    val offsets = SubtitleOffsetMap()
    /** The delay the sync set last (another one: set by hand, and the sync stops). */
    var appliedDelayMs = 0
    /** The other files of its language, as downloaded (null: couldn't be). */
    val siblingCues = HashMap<String, List<CueSpan>?>()
}

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
    val headers = currentHeaders
    val durationMs = awaitPlaying() ?: return
    if (durationMs < MIN_DURATION_MS || _playbackTimeline.value.isLive) return
    delay(START_AFTER_FIRST_FRAME_MS)
    if (!stillOn(subtitle, streamUrl)) return
    val startedAt = SystemClock.elapsedRealtime()

    val cues = subtitleCueSpans(subtitle)
    if (cues == null || cues.size < 20) {
        Log.i(TAG, "Too few lines to sync: left as it is")
        return
    }
    val sync = SubtitleSync(subtitle, streamUrl, headers, cues)
    sync.appliedDelayMs = _uiState.value.subtitleDelayMs
    // The sound ExoPlayer decodes is heard from now on (mpv plays its own).
    val tapSession = if (isUsingMpvEngine()) null else liveSpeechTap.start()
    try {
        // Put on by the sync itself (the best file of its language): its match is known.
        subtitleChoice.takePicked(streamUrl, subtitle)?.let { picked ->
            if (applyMatch(sync, picked)) {
                Log.i(TAG, "Subtitle picked and synced: ${sync.appliedDelayMs} ms, x${picked.scale}")
                showSyncedNotice(sync)
                followAlong(sync, tapSession)
            }
            return
        }
        showPlayerNotice(context.getString(R.string.subtitle_auto_sync_working), NOTICE_MS)
        if (SpeechActivityAnalyzer.canAnalyze(streamUrl)) {
            val found = alignAcrossMovie(sync, durationMs)
            if (found != null) {
                if (!applyMatch(sync, found)) return
                Log.i(
                    TAG,
                    "Subtitle synced: ${sync.appliedDelayMs} ms, x${found.scale}, confidence " +
                        "${"%.1f".format(found.confidence)}, in ${SystemClock.elapsedRealtime() - startedAt} ms"
                )
                showSyncedNotice(sync)
            }
            if (handChanged(sync)) return
            val speech = HeardSpeech.get(streamUrl)
            if (speech != null && switchToBestOfLanguage(sync, speech, found)) return
        }
        followAlong(sync, tapSession)
    } finally {
        tapSession?.let { liveSpeechTap.stop(it) }
    }
}

/** A few stretches across the movie, read from the stream (more when those don't settle it). */
private suspend fun PlayerRuntimeController.alignAcrossMovie(sync: SubtitleSync, durationMs: Long): SubtitleAlignment? {
    val url = sync.streamUrl
    var speech = HeardSpeech.get(url) ?: hear(url, sync.headers, windowsOf(durationMs, FIRST_WINDOWS))
        ?.also { HeardSpeech.put(url, it) }
        ?: return null
    var alignment = align(sync.cues, speech)
    if (alignment == null && speech.size <= FIRST_WINDOWS.size && stillOn(sync.subtitle, url)) {
        // Not settled yet: a few more stretches of the movie.
        hear(url, sync.headers, windowsOf(durationMs, MORE_WINDOWS))?.let { more ->
            speech = (speech + more).sortedBy { it.startMs }
            HeardSpeech.put(url, speech)
            alignment = align(sync.cues, speech)
        }
    }
    if (alignment == null) Log.i(TAG, "No clear match across the movie yet")
    return alignment
}

/**
 * Follows the subtitle along the movie (see the top of this file): the shift for each part of it,
 * and the first match when the stream couldn't give it. Until the subtitle or the stream changes,
 * or the delay is set by hand.
 */
private suspend fun PlayerRuntimeController.followAlong(sync: SubtitleSync, tapSession: Int?) {
    val canRead = SpeechActivityAnalyzer.canAnalyze(sync.streamUrl)
    val startedAt = SystemClock.elapsedRealtime()
    var lastLook = startedAt
    var lastLookPosition = Long.MIN_VALUE
    var lastAhead = startedAt
    var lastFirstTry = 0L
    var pendingOffset: Long? = null
    var pendingAt = 0L
    var misses = 0
    while (stillOn(sync.subtitle, sync.streamUrl)) {
        delay(FOLLOW_TICK_MS)
        if (handChanged(sync)) {
            Log.i(TAG, "Subtitle delay set by hand: the sync leaves it")
            return
        }
        val position = currentPlaybackPositionMs() ?: continue
        // Each part of the movie with its own shift: the one for where it plays now.
        sync.offsets.offsetAt(position)?.let { wanted ->
            if (delayFor(wanted) != sync.appliedDelayMs) setSyncDelay(sync, wanted)
        }
        val now = SystemClock.elapsedRealtime()
        val hearsPlayer = tapSession != null && liveSpeechTap.hearsPcm
        if (!hearsPlayer && tapSession != null && now - startedAt < NO_PCM_WAIT_MS) continue

        if (sync.offsets.isEmpty) {
            // No match yet: only the sound played can still give it.
            if (!hearsPlayer) {
                Log.i(TAG, "No clear match for the subtitle (another cut or another movie?): left as it is")
                return
            }
            if (now - lastFirstTry < FIRST_MATCH_EVERY_MS) continue
            val heard = liveSpeechTap.heardMs()
            if (heard < FIRST_MATCH_HEARD_MS) continue
            lastFirstTry = now
            val speech = withContext(Dispatchers.Default) { liveSpeechTap.speech() } ?: continue
            val found = align(sync.cues, speech)
            if (found != null) {
                if (!applyMatch(sync, found)) return
                Log.i(TAG, "Subtitle synced from the sound played: ${sync.appliedDelayMs} ms, x${found.scale}")
                showSyncedNotice(sync)
            }
            if (switchToBestOfLanguage(sync, speech, found)) return
            if (found == null && heard >= FIRST_MATCH_GIVE_UP_HEARD_MS) {
                Log.i(TAG, "No clear match for the subtitle (another cut or another movie?): left as it is")
                return
            }
            continue
        }

        // Matched: now and then, whether the lines still fall on the speech here.
        val lookFrom: Long
        val speech: List<SpeechWindow>
        when {
            hearsPlayer -> {
                if (now - lastLook < LOOK_EVERY_MS || abs(position - lastLookPosition) < LOOK_EVERY_MS / 2) continue
                lastLook = now
                lastLookPosition = position
                lookFrom = (position - RECENT_MS).coerceAtLeast(0L)
                if (liveSpeechTap.heardMs(lookFrom, position) < MIN_RECENT_HEARD_MS) continue
                speech = withContext(Dispatchers.Default) { liveSpeechTap.speech(lookFrom, position) } ?: continue
            }
            canRead -> {
                if (now - lastAhead < AHEAD_EVERY_MS) continue
                lastAhead = now
                lookFrom = position + AHEAD_LEAD_MS
                speech = hear(sync.streamUrl, sync.headers, listOf(lookFrom..(lookFrom + AHEAD_MS))) ?: continue
            }
            // Nothing to hear along the movie: the first match holds.
            else -> return
        }
        if (!stillOn(sync.subtitle, sync.streamUrl)) return
        val current = sync.offsets.offsetAt(position) ?: continue
        // A stretch read now and then decides alone, so it must stand out more; so must a wide search.
        val wide = hearsPlayer && misses >= MISSES_BEFORE_WIDE
        val near = withContext(Dispatchers.Default) {
            SubtitleAligner.alignNear(
                sync.cues,
                speech,
                sync.scale,
                aroundMs = current,
                rangeMs = if (wide) WIDE_RANGE_MS else SubtitleAligner.NEAR_RANGE_MS,
                strict = !hearsPlayer || wide
            )
        }
        if (near == null) {
            misses++
            continue
        }
        misses = 0
        if (abs(near.offsetMs - current) < MOVE_MS) {
            pendingOffset = null
            continue
        }
        val agreed = pendingOffset?.let { abs(it - near.offsetMs) <= AGREE_MS && now - pendingAt >= CONFIRM_AFTER_MS } == true
        if (hearsPlayer && !agreed) {
            if (pendingOffset == null || abs(pendingOffset - near.offsetMs) > AGREE_MS) {
                pendingOffset = near.offsetMs
                pendingAt = now
            }
            continue
        }
        pendingOffset = null
        // From the middle of what was heard on: where the other cut's scene starts is in there.
        val at = if (hearsPlayer) lookFrom + (position - lookFrom) / 2 else lookFrom
        sync.offsets.put(at, near.offsetMs)
        Log.i(TAG, "Subtitle followed: ${near.offsetMs} ms from ${at / 1000} s of the movie (was $current ms)")
        if (position >= at) setSyncDelay(sync, near.offsetMs)
    }
}

/**
 * The first time this language is chosen on this stream: every other file of it the subtitle sources
 * offer is compared with the same [speech]; when one fits the movie clearly better than the one on
 * ([current]: its match, null when none), that one is put on (true; its own sync then starts in
 * place). Tried again later while nothing fits yet.
 */
private suspend fun PlayerRuntimeController.switchToBestOfLanguage(
    sync: SubtitleSync,
    speech: List<SpeechWindow>,
    current: SubtitleAlignment?
): Boolean {
    val language = PlayerSubtitleUtils.normalizeLanguageCode(sync.subtitle.lang)
    if (language.isBlank() || subtitleChoice.wasCompared(sync.streamUrl, language)) return false
    val others = _uiState.value.addonSubtitles
        .filter { it.url != sync.subtitle.url && PlayerSubtitleUtils.normalizeLanguageCode(it.lang) == language }
        .distinctBy { it.url }
        .take(MAX_SIBLINGS)
    var best: Subtitle? = null
    var bestFound: SubtitleAlignment? = null
    for (other in others) {
        if (!stillOn(sync.subtitle, sync.streamUrl) || handChanged(sync)) return false
        val cues = if (sync.siblingCues.containsKey(other.url)) {
            sync.siblingCues[other.url]
        } else {
            withTimeoutOrNull(SIBLING_TIMEOUT_MS) { subtitleCueSpans(other) }.also { sync.siblingCues[other.url] = it }
        }
        if (cues == null || cues.size < 20) continue
        val found = align(cues, speech) ?: continue
        if (bestFound == null || found.confidence > bestFound.confidence) {
            best = other
            bestFound = found
        }
    }
    // Nothing fits yet (too little heard): compared again with more.
    if (current == null && bestFound == null) return false
    subtitleChoice.markCompared(sync.streamUrl, language)
    if (best == null || bestFound == null) return false
    if (current != null && bestFound.confidence < current.confidence + SWITCH_MARGIN) return false
    if (!stillOn(sync.subtitle, sync.streamUrl) || handChanged(sync)) return false
    Log.i(
        TAG,
        "Another $language subtitle fits this movie better (confidence " +
            "${current?.let { "%.1f".format(it.confidence) } ?: "none"} -> ${"%.1f".format(bestFound.confidence)}): put on"
    )
    subtitleChoice.pick(sync.streamUrl, best, bestFound)
    selectAddonSubtitle(best)
    return true
}

/** [found] as the subtitle's timing for the whole movie; false when this subtitle path can't take it. */
private fun PlayerRuntimeController.applyMatch(sync: SubtitleSync, found: SubtitleAlignment): Boolean {
    if (!stillOn(sync.subtitle, sync.streamUrl) || handChanged(sync)) return false
    if (found.scale != 1.0 && !canStretchSubtitles() && !moveSubtitleToSidecar(sync.subtitle)) {
        Log.i(TAG, "The subtitle is for another frame rate (x${found.scale}): this subtitle path can't stretch it")
        return false
    }
    setSubtitleTimeScale(found.scale)
    sync.scale = found.scale
    sync.offsets.reset(found.offsetMs)
    setSyncDelay(sync, found.offsetMs)
    return true
}

private fun PlayerRuntimeController.showSyncedNotice(sync: SubtitleSync) {
    showPlayerNotice(
        if (abs(sync.appliedDelayMs) < 100 && sync.scale == 1.0) {
            context.getString(R.string.subtitle_auto_sync_already)
        } else {
            context.getString(R.string.subtitle_auto_sync_done, formatAutoSyncDelay(sync.appliedDelayMs))
        },
        NOTICE_MS
    )
}

/** A shift as the delay control's value (10 ms steps, within its range). */
private fun delayFor(offsetMs: Long): Int =
    ((offsetMs / 10.0).roundToLong() * 10).toInt().coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)

private fun PlayerRuntimeController.setSyncDelay(sync: SubtitleSync, offsetMs: Long) {
    val delayMs = delayFor(offsetMs)
    sync.appliedDelayMs = delayMs
    if (_uiState.value.subtitleDelayMs != delayMs) applyAutoAlignedDelay(delayMs)
}

/** The delay isn't the one the sync set: someone set it by hand. */
private fun PlayerRuntimeController.handChanged(sync: SubtitleSync): Boolean =
    _uiState.value.subtitleDelayMs != sync.appliedDelayMs

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
/** The file's sound in [windows]; null when it can't be read here (see SpeechReadGuard). */
private suspend fun PlayerRuntimeController.hear(url: String, headers: Map<String, String>, windows: List<LongRange>): List<SpeechWindow>? =
    withContext(Dispatchers.IO) {
        com.nuvio.tv.core.subsync.SpeechReadGuard.reading(context) {
            SpeechActivityAnalyzer.analyze(url, headers, windows) { !isActive }
        }
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

/**
 * The subtitle drawn by the app itself (which can stretch it) instead of by ExoPlayer, without
 * reloading the movie; false when the app can't draw this one (ASS kept on libass).
 */
private fun PlayerRuntimeController.moveSubtitleToSidecar(subtitle: Subtitle): Boolean {
    if (isUsingMpvEngine() || !canAttachAddonSubtitleViaSidecar(subtitle)) return false
    _exoPlayer?.let { player ->
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    return startSidecarAddonSubtitle(subtitle)
}

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
