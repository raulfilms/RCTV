package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.imdb.EpisodeOrderRegistry
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.smartscraper.AudioLanguage
import com.nuvio.tv.core.smartscraper.AutoStreamEngine
import com.nuvio.tv.core.smartscraper.AutoStreamSession
import com.nuvio.tv.core.smartscraper.RejectReason
import com.nuvio.tv.core.smartscraper.ReleaseParser
import com.nuvio.tv.core.smartscraper.SmartScraperEnvironment
import com.nuvio.tv.core.smartscraper.SmartSourceRequest
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * RCTV: the audio menu has two languages, English then Español, like Disney+: choosing one changes
 * only the audio. A file that has it switches its track; one that doesn't is swapped, at the same
 * moment, for the title's best source in that language (LatinoDebrid's for Spanish, say), the
 * movie or episode never changing. The choice is kept: the next title starts in it.
 */

private const val TAG = "AudioLanguage"
/** How long the title's sources are waited for when looking for another language. */
private const val LANGUAGE_SEARCH_TIMEOUT_MS = 10_000L
private const val NOTICE_MS = 3_000L

/** The language playing now: the selected track's, else what the file says, else the title's own. */
internal fun PlayerRuntimeController.currentAudioLanguage(): AudioLanguage? {
    val state = _uiState.value
    val selected = state.audioTracks.firstOrNull { it.index == state.selectedAudioTrackIndex }
        ?: state.audioTracks.firstOrNull { it.isSelected }
    AudioLanguage.ofCode(selected?.language)?.let { return it }
    return currentFileAudioLanguages().firstOrNull()
}

/** What the playing file's name and source say about its audio (else the title's own language). */
internal fun PlayerRuntimeController.currentFileAudioLanguages(): Set<AudioLanguage> {
    val original = AudioLanguage.ofCode(contentLanguage)
    AutoStreamSession.current(currentVideoId)?.current?.let { stream ->
        return SmartScraperEnvironment.normalize(stream).audioLanguages(original)
    }
    val text = listOfNotNull(
        currentFilename,
        navigationArgs.filename,
        _uiState.value.currentStreamName,
        currentStreamDescription
    ).joinToString("\n")
    val said = ReleaseParser.audioLanguagesIn(text) +
        (if (currentAddonName?.contains("latino", ignoreCase = true) == true) setOf(AudioLanguage.SPANISH) else emptySet())
    return said.ifEmpty { setOf(original ?: AudioLanguage.ENGLISH) }
}

internal fun PlayerRuntimeController.selectAudioLanguage(language: AudioLanguage) {
    _uiState.update { it.copy(showAudioOverlay = false) }
    // Kept for what plays next (Auto Stream and the tracks of files with several languages).
    scope.launch { playerSettingsDataStore.setPreferredAudioLanguage(language.code) }

    // This file has it: only its track changes.
    val tracks = _uiState.value.audioTracks
    val track = tracks.firstOrNull { AudioLanguage.ofCode(it.language) == language }
    if (track != null) {
        audioLanguageSwitchJob?.cancel()
        rememberAudioSelection(track.index)
        selectAudioTrack(track.index)
        _uiState.update { it.copy(currentAudioLanguage = language, audioLanguageSwitching = null) }
        return
    }
    // Its only audio, not labeled by language, is already it.
    if (tracks.none { AudioLanguage.ofCode(it.language) != null } && currentAudioLanguage() == language) {
        _uiState.update { it.copy(currentAudioLanguage = language) }
        return
    }
    switchToLanguageSource(language)
}

/**
 * The title's best source in [language] (Auto Stream's rules first, then any right one), playing
 * from the same moment; the current one keeps playing until it's found.
 */
private fun PlayerRuntimeController.switchToLanguageSource(language: AudioLanguage) {
    audioLanguageSwitchJob?.cancel()
    val isEpisode = contentType in listOf("series", "tv") && currentSeason != null && currentEpisode != null
    val type = contentType ?: "movie"
    val videoId = (if (isEpisode) currentVideoId ?: contentId else contentId) ?: return
    _uiState.update { it.copy(audioLanguageSwitching = language) }
    showPlayerNotice(context.getString(com.nuvio.tv.R.string.audio_language_searching, language.nativeName), NOTICE_MS * 3)

    audioLanguageSwitchJob = scope.launch {
        val streams = titleStreams(type, videoId, if (isEpisode) currentSeason else null, if (isEpisode) currentEpisode else null)
        val original = AudioLanguage.ofCode(contentLanguage)
        val durationMinutes = (_exoPlayer?.duration ?: 0L).takeIf { it > 0 }?.let { (it / 60_000L).toInt() }
        val request = SmartSourceRequest(
            title = contentName ?: title,
            alternateTitles = listOfNotNull(title.takeIf { it.isNotBlank() && it != contentName }),
            year = SmartSourceRequest.yearOf(year),
            isSeries = isEpisode,
            season = if (isEpisode) currentSeason else null,
            episode = if (isEpisode) currentEpisode else null,
            imdbId = contentId?.takeIf { it.startsWith("tt") }?.substringBefore(':'),
            runtimeMinutes = durationMinutes,
            equivalentEpisodes = if (isEpisode) {
                EpisodeOrderRegistry.equivalentEpisodes(contentId, currentSeason, currentEpisode)
            } else {
                emptySet()
            },
            preferredLanguage = language,
            originalLanguage = original
        )
        val playing = currentStreamUrl
        val auto = SmartScraperEnvironment.autoStreamRank(streams, request)
        var chain = auto.candidates
            .filter { it.languageMatch }
            .flatMap { candidate -> listOf(candidate.stream) + candidate.copies.map { it.stream } }
        if (chain.isEmpty()) {
            // Beyond Auto Stream's limits (bigger, 7.1...): any right source in that language.
            chain = SmartScraperEnvironment.rank(streams, request).all
                .filter { language in it.audioLanguages && it.rejection !in UNUSABLE }
                .map { it.stream }
        }
        chain = chain.filterNot { it.getStreamUrl() != null && it.getStreamUrl() == playing }
        val target = chain.firstOrNull()
        if (target == null) {
            Log.i(TAG, "No ${language.nativeName} source for this title")
            _uiState.update { it.copy(audioLanguageSwitching = null) }
            showPlayerNotice(context.getString(com.nuvio.tv.R.string.audio_language_not_found, language.nativeName), NOTICE_MS)
            return@launch
        }

        val position = (_exoPlayer?.currentPosition ?: _uiState.value.currentPosition).coerceAtLeast(0L)
        Log.i(TAG, "Playing the ${language.nativeName} source from ${position / 1000} s")
        AutoStreamSession.begin(
            videoId = currentVideoId ?: videoId,
            current = target,
            candidates = chain.drop(1),
            config = SmartScraperEnvironment.config.autoStream
        )
        switchToSourceStream(target)
        _uiState.update {
            it.copy(
                pendingSeekPosition = position.takeIf { p -> p > 0L },
                currentAudioLanguage = language,
                audioLanguageSwitching = null
            )
        }
        val summary = AutoStreamEngine.summary(SmartScraperEnvironment.normalize(target).release, language)
        showPlayerNotice(summary.ifBlank { language.nativeName }, NOTICE_MS)
    }
}

/** Sources that can't be played as they are. */
private val UNUSABLE = setOf(
    RejectReason.NOT_PLAYABLE,
    RejectReason.NOT_CACHED,
    RejectReason.LOW_QUALITY_SOURCE,
    RejectReason.WRONG_TITLE
)

/** The title's streams: the sources panel's when it has them, else every addon's (all at once). */
private suspend fun PlayerRuntimeController.titleStreams(
    type: String,
    videoId: String,
    season: Int?,
    episode: Int?
): List<Stream> {
    val requestKey = buildSourceRequestKey(type = type, videoId = videoId, season = season, episode = episode)
    val loaded = _uiState.value.sourceAllStreams
    if (requestKey == sourceStreamsCacheRequestKey && sourceStreamsFetchCompleted && loaded.isNotEmpty()) return loaded
    var latest: List<Stream> = emptyList()
    withTimeoutOrNull(LANGUAGE_SEARCH_TIMEOUT_MS) {
        streamRepository.getStreamsFromAllAddons(
            type = type,
            videoId = videoId,
            season = season,
            episode = episode
        ).collect { result ->
            if (result is NetworkResult.Success) latest = result.data.flatMap { it.streams }
        }
    }
    return latest
}

/** A short line over the picture (what's happening), gone after [durationMs]. */
internal fun PlayerRuntimeController.showPlayerNotice(text: String, durationMs: Long) {
    hideStreamSourceIndicatorJob?.cancel()
    _uiState.update { it.copy(showStreamSourceIndicator = true, streamSourceIndicatorText = text) }
    hideStreamSourceIndicatorJob = scope.launch {
        delay(durationMs)
        _uiState.update { it.copy(showStreamSourceIndicator = false) }
    }
}
