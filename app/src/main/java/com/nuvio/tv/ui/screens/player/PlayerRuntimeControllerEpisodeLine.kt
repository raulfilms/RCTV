package com.nuvio.tv.ui.screens.player

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * RCTV: under a show's logo in the player, its season, episode and the episode's name, in the
 * show's own language: Spanish for a show in Spanish, English for the rest. The name comes from
 * TMDB in that language when TMDB is on (Latin American Spanish first). Otherwise it's the one the
 * episode already has.
 */

private const val TAG = "EpisodeLine"

/** The show's language may be known only once its details arrive. */
private const val LANGUAGE_WAIT_MS = 15_000L

/** Watches the episode playing: each one's line is made in the show's language. */
internal fun PlayerRuntimeController.startEpisodeLineWatch() {
    scope.launch {
        _uiState.map { it.currentSeason to it.currentEpisode }.distinctUntilChanged().collect { (season, episode) ->
            _uiState.update { it.copy(episodeLineTitle = null) }
            if (season == null || episode == null) return@collect
            if (contentType?.lowercase().let { it != "series" && it != "tv" }) return@collect
            withTimeoutOrNull(LANGUAGE_WAIT_MS) {
                while (contentLanguage.isNullOrBlank()) delay(500)
            }
            val spanish = isSpanishLanguageCode(contentLanguage)
            _uiState.update { it.copy(episodeLineSpanish = spanish) }
            val name = try {
                episodeNameIn(season, episode, if (spanish) "es-MX" else "en-US")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No episode name from TMDB: ${e.message}")
                null
            }
            val state = _uiState.value
            if (!name.isNullOrBlank() && state.currentSeason == season && state.currentEpisode == episode) {
                _uiState.update { it.copy(episodeLineTitle = name) }
            }
        }
    }
}

/** The episode's name on TMDB in [language]; null when TMDB is off or doesn't have it. */
private suspend fun PlayerRuntimeController.episodeNameIn(season: Int, episode: Int, language: String): String? {
    val id = contentId?.takeIf { it.isNotBlank() } ?: return null
    if (!tmdbSettingsDataStore.settings.first().enabled) return null
    val tmdbId = tmdbService.ensureTmdbId(id, "series") ?: return null
    return tmdbMetadataService.fetchEpisodeEnrichment(
        tmdbId = tmdbId,
        seasonNumbers = listOf(season),
        language = language
    )[season to episode]?.title?.trim()?.takeIf { it.isNotBlank() }
}

/** "es", "spa", "es-MX", "es-419"... */
private fun isSpanishLanguageCode(language: String?): Boolean {
    val code = language?.trim()?.lowercase()?.replace('_', '-') ?: return false
    return code == "es" || code == "spa" || code.startsWith("es-")
}
