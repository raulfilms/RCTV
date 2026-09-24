package com.nuvio.tv.ui.screens.sports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.SportsPreferencesDataStore
import com.nuvio.tv.data.remote.espn.EspnSportsClient
import com.nuvio.tv.domain.model.SportsLeague
import com.nuvio.tv.domain.model.SportsTeam
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class TeamsLeagueSection(val league: SportsLeague, val teams: List<SportsTeam>)

data class TeamsBrowseUiState(
    val isLoading: Boolean = true,
    val selectedFilterId: String? = null,
    val sections: List<TeamsLeagueSection> = emptyList(),
    val favoriteTeamIds: Set<String> = emptySet(),
    val favoriteTeams: List<SportsTeam> = emptyList()
)

/**
 * Backs the "Teams" See All screen: a filterable, section-per-league browse of teams. Each section's
 * teams come straight out of that league's ESPN scoreboard window (the same feed the Sports tab
 * itself uses), cached in-memory for the lifetime of this ViewModel so switching filters back and
 * forth doesn't re-fetch.
 */
@HiltViewModel
class TeamsBrowseViewModel @Inject constructor(
    private val client: EspnSportsClient,
    private val preferences: SportsPreferencesDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(TeamsBrowseUiState())
    val uiState: StateFlow<TeamsBrowseUiState> = _uiState.asStateFlow()

    val filters = EspnSportsClient.TEAMS_FILTERS

    private val sectionCache = mutableMapOf<String, TeamsLeagueSection>()

    init {
        viewModelScope.launch {
            preferences.favoriteTeamIds.collect { ids ->
                _uiState.update { it.copy(favoriteTeamIds = ids) }
                hydrateFavoriteTeams(ids)
            }
        }
        selectFilter(null)
    }

    fun toggleFavoriteTeam(teamId: String) {
        viewModelScope.launch { preferences.toggleFavoriteTeam(teamId) }
    }

    fun selectFilter(filterId: String?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, selectedFilterId = filterId) }
            val feeds = if (filterId == null) {
                EspnSportsClient.FEEDS
            } else {
                filters.firstOrNull { it.id == filterId }?.let { listOf(it.feed) }.orEmpty()
            }
            val sections = withContext(Dispatchers.IO) { loadSections(feeds) }
            _uiState.update { it.copy(isLoading = false, sections = sections) }
        }
    }

    private suspend fun loadSections(feeds: List<EspnSportsClient.Feed>): List<TeamsLeagueSection> = coroutineScope {
        feeds.map { feed ->
            async {
                val cacheKey = "${feed.sportPath}:${feed.leaguePath}"
                sectionCache.getOrPut(cacheKey) {
                    val result = runCatching { client.fetchFeed(feed) }.getOrNull()
                    TeamsLeagueSection(
                        league = result?.league ?: SportsLeague(id = cacheKey, name = feed.leaguePath, sportName = feed.sportLabel),
                        teams = result?.teams.orEmpty()
                    )
                }
            }
        }.awaitAll().filter { it.teams.isNotEmpty() }
    }

    private suspend fun hydrateFavoriteTeams(ids: Set<String>) {
        if (ids.isEmpty()) {
            _uiState.update { it.copy(favoriteTeams = emptyList()) }
            return
        }
        val cached = sectionCache.values.flatMap { it.teams }.associateBy { it.id }
        val missing = ids - cached.keys
        val fetched = if (missing.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                // Favorited teams whose league section hasn't been loaded yet: fall back to
                // fetching every curated feed once so we can still resolve their names/badges.
                runCatching { client.fetchAllFixtures() }.getOrDefault(emptyList())
                    .flatMap { it.teams }
                    .associateBy { it.id }
            }
        } else emptyMap()
        val teams = ids.mapNotNull { id -> cached[id] ?: fetched[id] }
        _uiState.update { it.copy(favoriteTeams = teams) }
    }
}
