package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.debrid.DirectDebridPlayableResult
import com.nuvio.tv.core.smartscraper.AudioLanguage
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * RCTV: English and Español both ready. While a title plays, its source in the other language
 * (Spanish from LatinoDebrid and the LATAM addon, or AIOStreams' dubbed setup; English from
 * Torrentio, AIOStreams...) is found, its link made ready on the debrid service and opened, so
 * choosing that language in the audio menu switches at once, with no searching.
 */

private const val TAG = "LanguageReady"
/** A prepared link is used for this long (debrid links last hours). */
internal const val PREPARED_LANGUAGE_MAX_AGE_MS = 60L * 60 * 1000
/** Once the title plays, how long before the other language is looked for (the start comes first). */
private const val PREPARE_AFTER_START_MS = 8_000L
private const val MAX_TRIES = 4

/** The other language's source for this title, its first link ready to play. */
internal class PreparedLanguage(
    val key: String,
    val language: AudioLanguage,
    val chain: List<Stream>,
    val preparedAtMs: Long
)

/** This title (movie, or the episode) as the prepared language is kept under. */
internal fun PlayerRuntimeController.languageKey(): String? {
    val isEpisode = contentType in listOf("series", "tv") && currentSeason != null && currentEpisode != null
    val videoId = (if (isEpisode) currentVideoId ?: contentId else contentId) ?: return null
    return if (isEpisode) "$videoId:$currentSeason:$currentEpisode" else videoId
}

/** Started with the player: once each title plays, its other language is made ready. */
internal fun PlayerRuntimeController.startLanguageReadyWatch() {
    scope.launch {
        var lastKey: String? = null
        while (isActive) {
            delay(2_000)
            if (!hasRenderedFirstFrame || _playbackTimeline.value.isLive) continue
            val key = languageKey() ?: continue
            // A title, and the language it plays in (after a switch, the other one is made ready).
            val watchKey = key + "|" + currentFileAudioLanguages().joinToString(",") { it.code }
            if (watchKey == lastKey) continue
            lastKey = watchKey
            preparedLanguage = preparedLanguage?.takeIf { it.key == key }
            languagePrepareJob?.cancel()
            languagePrepareJob = scope.launch {
                delay(PREPARE_AFTER_START_MS)
                try {
                    prepareOtherLanguage(key)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't make the other language ready: ${e.message}")
                }
            }
        }
    }
}

private suspend fun PlayerRuntimeController.prepareOtherLanguage(key: String) {
    val hasTracks = _uiState.value.audioTracks.mapNotNull { AudioLanguage.ofCode(it.language) }.toSet()
    val playing = currentFileAudioLanguages()
    // English playing: Español is made ready; Español playing: English (unless the file has it).
    val target = listOf(AudioLanguage.SPANISH, AudioLanguage.ENGLISH)
        .firstOrNull { it !in hasTracks && it !in playing } ?: return
    val chain = languageChain(target) ?: run {
        Log.i(TAG, "No ${target.nativeName} source for this title")
        return
    }
    val startedAt = System.currentTimeMillis()
    chain.take(MAX_TRIES).forEachIndexed { index, candidate ->
        if (languageKey() != key) return
        val playable = when (val result = directDebridResolver.resolveToPlayableStream(candidate, currentSeason, currentEpisode)) {
            is DirectDebridPlayableResult.Success -> result.stream
            else -> null
        } ?: return@forEachIndexed
        val url = playable.getStreamUrl() ?: return@forEachIndexed
        // Opened on the player's connection; an addon's error clip is passed over.
        if (!warmUp(url, playable.behaviorHints?.proxyHeaders?.request.orEmpty())) return@forEachIndexed
        if (languageKey() != key) return
        preparedLanguage = PreparedLanguage(
            key = key,
            language = target,
            chain = listOf(playable) + chain.drop(index + 1),
            preparedAtMs = System.currentTimeMillis()
        )
        Log.i(TAG, "${target.nativeName} ready in ${System.currentTimeMillis() - startedAt} ms")
        return
    }
}
