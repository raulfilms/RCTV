package com.nuvio.tv.ui.screens.sports

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.SportsPreferencesDataStore
import com.nuvio.tv.data.remote.espn.EspnSportsClient
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import com.nuvio.tv.domain.model.SportsGameStat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class GameDetailUiState(
    val stats: List<SportsGameStat> = emptyList(),
    val isLoadingStats: Boolean = true,
    val favoriteTeamIds: Set<String> = emptySet(),
    val favoriteSportNames: Set<String> = emptySet(),
    val isInLibrary: Boolean = false
)

/**
 * Backs the game detail page (tapped from any match card). Unlike the league/teams screens, the
 * event's own display data (teams, score, status, venue) is passed in whole through the navigation
 * route rather than refetched here - it's already correct and in hand at the moment the person taps
 * the card, and refetching risks not finding the same event again (a schedule-tab game might fall
 * outside whatever date window a fresh fetch would use). Only the team-vs-team stat comparison, which
 * genuinely isn't available anywhere else, is fetched fresh from ESPN's game summary endpoint.
 */
@HiltViewModel
class GameDetailViewModel @Inject constructor(
    private val client: EspnSportsClient,
    private val preferences: SportsPreferencesDataStore,
    private val libraryPreferences: LibraryPreferences,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val event: SportsEvent = buildEvent(savedStateHandle)

    private val _uiState = MutableStateFlow(GameDetailUiState())
    val uiState: StateFlow<GameDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(preferences.favoriteSports, preferences.favoriteTeamIds) { sports, teamIds -> sports to teamIds }
                .collect { (sports, teamIds) ->
                    _uiState.update { it.copy(favoriteSportNames = sports, favoriteTeamIds = teamIds) }
                }
        }
        viewModelScope.launch {
            libraryPreferences.libraryItems.collect { items ->
                val inLibrary = items.any {
                    it.id == event.id && it.type.equals(SPORTS_EVENT_LIBRARY_TYPE, ignoreCase = true)
                }
                _uiState.update { it.copy(isInLibrary = inLibrary) }
            }
        }
        loadStats()
    }

    private fun loadStats() {
        val parsed = EspnSportsClient.parseEventId(event.id)
        if (parsed == null) {
            _uiState.update { it.copy(isLoadingStats = false, stats = emptyList()) }
            return
        }
        viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) {
                runCatching { client.fetchGameStats(parsed.sportPath, parsed.leaguePath, parsed.rawEventId) }
                    .getOrDefault(emptyList())
            }
            _uiState.update { it.copy(isLoadingStats = false, stats = stats) }
        }
    }

    fun toggleFavoriteTeam(teamId: String) {
        viewModelScope.launch { preferences.toggleFavoriteTeam(teamId) }
    }

    fun toggleFavoriteSport(sportName: String) {
        viewModelScope.launch { preferences.toggleFavoriteSport(sportName) }
    }

    fun toggleLibrary() {
        viewModelScope.launch {
            if (uiState.value.isInLibrary) {
                libraryPreferences.removeItem(itemId = event.id, itemType = SPORTS_EVENT_LIBRARY_TYPE)
            } else {
                libraryPreferences.addItem(item = event.toSavedLibraryItem())
            }
        }
    }

    private companion object {
        fun decodeArg(raw: String?): String {
            if (raw.isNullOrEmpty()) return ""
            return runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        }

        fun buildEvent(savedStateHandle: SavedStateHandle): SportsEvent {
            fun str(key: String): String = decodeArg(savedStateHandle.get<String>(key))
            fun strOrNull(key: String): String? = str(key).takeIf { it.isNotBlank() }
            fun intOrNull(key: String): Int? = savedStateHandle.get<String>(key)?.toIntOrNull()
            fun longOrNull(key: String): Long? = savedStateHandle.get<String>(key)?.toLongOrNull()

            val status = runCatching { SportsEventStatus.valueOf(str("status")) }
                .getOrDefault(SportsEventStatus.UPCOMING)

            return SportsEvent(
                id = decodeArg(savedStateHandle.get<String>("eventId")),
                name = str("name"),
                sportName = strOrNull("sportName"),
                leagueId = strOrNull("leagueId"),
                leagueName = strOrNull("leagueName"),
                homeTeamId = strOrNull("homeTeamId"),
                homeTeamName = str("homeTeamName"),
                homeTeamBadgeUrl = strOrNull("homeTeamBadgeUrl"),
                awayTeamId = strOrNull("awayTeamId"),
                awayTeamName = str("awayTeamName"),
                awayTeamBadgeUrl = strOrNull("awayTeamBadgeUrl"),
                homeScore = intOrNull("homeScore"),
                awayScore = intOrNull("awayScore"),
                startTimeMs = longOrNull("startTimeMs"),
                venue = strOrNull("venue"),
                status = status,
                statusDetail = strOrNull("statusDetail")
            )
        }
    }
}
