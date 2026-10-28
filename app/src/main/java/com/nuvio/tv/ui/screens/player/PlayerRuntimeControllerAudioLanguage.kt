package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.imdb.EpisodeOrderRegistry
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.smartscraper.AudioLanguage
import com.nuvio.tv.core.smartscraper.AutoStreamEngine
import com.nuvio.tv.core.smartscraper.AutoStreamSession
import com.nuvio.tv.core.smartscraper.ProviderFamily
import com.nuvio.tv.core.smartscraper.ProviderVariant
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
        followAudioWithSubtitle(language)
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
    val videoId = (if (isEpisode) currentVideoId ?: contentId else contentId) ?: return
    // RCTV: found and opened already while the title played: it switches at once.
    val key = languageKey()
    val prepared = preparedLanguage?.takeIf {
        it.key == key && it.language == language && System.currentTimeMillis() - it.preparedAtMs < PREPARED_LANGUAGE_MAX_AGE_MS
    }
    _uiState.update { it.copy(audioLanguageSwitching = language) }
    if (prepared == null) {
        showPlayerNotice(context.getString(com.nuvio.tv.R.string.audio_language_searching, language.nativeName), NOTICE_MS * 3)
    }

    audioLanguageSwitchJob = scope.launch {
        val chain = (prepared?.chain ?: languageChain(language)).orEmpty()
        if (prepared != null) preparedLanguage = null
        val target = chain.firstOrNull()
        if (target == null) {
            Log.i(TAG, "No ${language.nativeName} source for this title")
            _uiState.update { it.copy(audioLanguageSwitching = null) }
            showPlayerNotice(context.getString(com.nuvio.tv.R.string.audio_language_not_found, language.nativeName), NOTICE_MS)
            return@launch
        }
        val position = (currentPlaybackPositionMs() ?: _playbackTimeline.value.currentPosition).coerceAtLeast(0L)
        Log.i(TAG, "Playing the ${language.nativeName} source from ${position / 1000} s")
        AutoStreamSession.begin(
            videoId = currentVideoId ?: videoId,
            current = target,
            candidates = chain.drop(1),
            config = SmartScraperEnvironment.config.autoStream,
            wantedLanguages = setOf(language.code)
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
        // The subtitle follows, once the new source shows (its own tracks are known then).
        val waitUntil = System.currentTimeMillis() + SUBTITLE_FOLLOW_WAIT_MS
        delay(1_000)
        while (!hasRenderedFirstFrame && System.currentTimeMillis() < waitUntil) delay(300)
        followAudioWithSubtitle(language)
    }
}

private const val SUBTITLE_FOLLOW_WAIT_MS = 30_000L

/**
 * RCTV: the audio changed to [language]: a subtitle that's on changes to that language too (the
 * file's own track in it, else the addons' best one, synced to the audio on its own); subtitles
 * that are off stay off, and one already in that language stays.
 */
internal fun PlayerRuntimeController.followAudioWithSubtitle(language: AudioLanguage) {
    val state = _uiState.value
    val addonOn = state.selectedAddonSubtitle
    val internalOn = state.subtitleTracks.firstOrNull { it.index == state.selectedSubtitleTrackIndex && !it.isForced }
    if (addonOn == null && internalOn == null) return
    val onLanguage = subtitleLanguageOf(addonOn?.lang ?: internalOn?.language ?: internalOn?.name)
    if (onLanguage == language) return

    // The file's own subtitle in that language (not a forced one) first.
    val track = state.subtitleTracks.firstOrNull { !it.isForced && subtitleLanguageOf(it.language ?: it.name) == language }
    if (track != null) {
        autoSubtitleSelected = true
        pendingAddonSubtitleLanguage = null
        pendingAddonSubtitleTrackId = null
        resetSubtitleAutoSyncState()
        rememberInternalSubtitleSelection(track.index)
        selectSubtitleTrack(track.index)
        Log.i(TAG, "Subtitles follow the audio: the file's ${language.nativeName} track")
        return
    }
    // Else the addons' first one in it (they come best first).
    val subtitle = state.addonSubtitles.firstOrNull { subtitleLanguageOf(it.lang) == language } ?: run {
        Log.i(TAG, "No ${language.nativeName} subtitle to follow the audio")
        return
    }
    autoSubtitleSelected = true
    rememberAddonSubtitleSelection(subtitle)
    selectAddonSubtitle(subtitle)
    Log.i(TAG, "Subtitles follow the audio: ${language.nativeName}")
}

/** A subtitle's language from its code or name ("spa", "es-419", "Spanish", "Español (Latino)"...). */
private fun subtitleLanguageOf(text: String?): AudioLanguage? {
    val value = text?.trim()?.lowercase() ?: return null
    AudioLanguage.ofCode(value)?.let { return it }
    return when {
        Regex("spanish|espa[ñn]ol|castellano|latino").containsMatchIn(value) -> AudioLanguage.SPANISH
        Regex("english|ingl[eé]s").containsMatchIn(value) -> AudioLanguage.ENGLISH
        else -> null
    }
}

/**
 * The title's sources in [language], the best first (Auto Stream's rules first, then any right one);
 * null when it has none. RCTV: Spanish comes from LatinoDebrid and the LATAM addon (and AIOStreams'
 * dubbed setup), English from the others (Torrentio, AIOStreams...); the rest only after them.
 */
internal suspend fun PlayerRuntimeController.languageChain(language: AudioLanguage): List<Stream>? {
    val isEpisode = contentType in listOf("series", "tv") && currentSeason != null && currentEpisode != null
    val type = contentType ?: "movie"
    val videoId = (if (isEpisode) currentVideoId ?: contentId else contentId) ?: return null
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
    return preferredProviders(chain, language).ifEmpty { null }
}

/** [chain] with the providers meant for [language] first (in their order), the others after. */
private fun preferredProviders(chain: List<Stream>, language: AudioLanguage): List<Stream> {
    val (preferred, others) = chain.partition { stream ->
        val provider = runCatching { SmartScraperEnvironment.normalize(stream).provider }.getOrNull()
            ?: return@partition false
        if (language == AudioLanguage.SPANISH) {
            provider.family == ProviderFamily.LATINODEBRID ||
                (provider.family == ProviderFamily.AIOSTREAMS && provider.variant == ProviderVariant.DUBBED)
        } else {
            provider.family != ProviderFamily.LATINODEBRID
        }
    }
    return preferred + others
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
