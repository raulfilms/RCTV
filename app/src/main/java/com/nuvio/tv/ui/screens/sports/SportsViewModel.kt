package com.nuvio.tv.ui.screens.sports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.SportsPreferencesDataStore
import com.nuvio.tv.data.remote.espn.EspnSportsClient
import com.nuvio.tv.data.repository.SportsAddonRepository
import com.nuvio.tv.domain.model.SportsAddonEvent
import com.nuvio.tv.domain.model.SportsContinueWatchingItem
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import com.nuvio.tv.domain.model.SportsLeague
import com.nuvio.tv.domain.model.SportsTalkItem
import com.nuvio.tv.domain.model.SportsTeam
import com.nuvio.tv.domain.model.Stream
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

/** What a "hold to favorite" long-press was performed on, backing [SportsFavoriteOptionsDialog]. */
sealed class SportsFavoriteTarget {
    data class TeamTarget(val team: SportsTeam) : SportsFavoriteTarget()
    data class LeagueTarget(val league: SportsLeague) : SportsFavoriteTarget()
    data class EventTarget(val event: SportsEvent) : SportsFavoriteTarget()
}

data class SportsUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val featuredEvent: SportsEvent? = null,
    val continueWatching: List<SportsContinueWatchingItem> = emptyList(),
    val liveNow: List<SportsEvent> = emptyList(),
    val upcomingGames: List<SportsEvent> = emptyList(),
    val pickedForYou: List<SportsEvent> = emptyList(),
    val leagues: List<SportsLeague> = emptyList(),
    val teams: List<SportsTeam> = emptyList(),
    /** Full pool of teams gathered from the curated leagues, used by the favorites picker (unlike [teams], not capped to 5). */
    val browsableTeams: List<SportsTeam> = emptyList(),
    val sportsTalk: List<SportsTalkItem> = emptyList(),
    val favoriteSportNames: Set<String> = emptySet(),
    val favoriteTeams: List<SportsTeam> = emptyList(),
    /** Actually-playable live/upcoming events sourced from the user's installed sports addons (phase 3). Separate from the ESPN rows above, which are metadata/schedule-only. */
    val addonEvents: List<SportsAddonEvent> = emptyList(),
    val isLoadingAddonEvents: Boolean = false,
    val selectedAddonEvent: SportsAddonEvent? = null,
    val addonEventStreams: List<Stream> = emptyList(),
    val isLoadingAddonStreams: Boolean = false,
    val addonStreamsError: String? = null,
    /** Non-null while the "hold to favorite" options dialog is showing for a long-pressed team/match/league. */
    val favoriteOptionsTarget: SportsFavoriteTarget? = null
)

@HiltViewModel
class SportsViewModel @Inject constructor(
    private val client: EspnSportsClient,
    private val preferences: SportsPreferencesDataStore,
    private val sportsAddonRepository: SportsAddonRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SportsUiState())
    val uiState: StateFlow<SportsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(preferences.favoriteSports, preferences.favoriteTeamIds) { sports, teamIds -> sports to teamIds }
                .collect { (sports, teamIds) ->
                    _uiState.update { it.copy(favoriteSportNames = sports) }
                    refresh(favoriteTeamIds = teamIds)
                }
        }
        loadAddonEvents()
    }

    /** Loads playable events from the user's installed sports addons. Runs independently of [refresh]/ESPN - a missing or misbehaving addon never blocks the schedule/scores rows. */
    fun loadAddonEvents() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingAddonEvents = true) }
            val events = withContext(Dispatchers.IO) {
                runCatching { sportsAddonRepository.fetchSportsEvents() }.getOrDefault(emptyList())
            }
            _uiState.update { it.copy(isLoadingAddonEvents = false, addonEvents = events) }
        }
    }

    /** Fetches playable streams for an addon-sourced event and opens the stream picker overlay. */
    fun selectAddonEvent(event: SportsAddonEvent) {
        _uiState.update {
            it.copy(selectedAddonEvent = event, addonEventStreams = emptyList(), isLoadingAddonStreams = true, addonStreamsError = null)
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { sportsAddonRepository.fetchPlayableStreams(event) }
            }
            result.onSuccess { streams ->
                _uiState.update {
                    it.copy(
                        isLoadingAddonStreams = false,
                        addonEventStreams = streams,
                        addonStreamsError = if (streams.isEmpty()) "No playable streams found for this event." else null
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(isLoadingAddonStreams = false, addonEventStreams = emptyList(), addonStreamsError = throwable.message ?: "error")
                }
            }
        }
    }

    fun dismissAddonEventPicker() {
        _uiState.update {
            it.copy(selectedAddonEvent = null, addonEventStreams = emptyList(), isLoadingAddonStreams = false, addonStreamsError = null)
        }
    }

    fun toggleFavoriteSport(sportName: String) {
        viewModelScope.launch { preferences.toggleFavoriteSport(sportName) }
    }

    fun toggleFavoriteTeam(teamId: String) {
        viewModelScope.launch { preferences.toggleFavoriteTeam(teamId) }
    }

    /** Opens the "hold to favorite" options dialog for a long-pressed team/match/league card. */
    fun showFavoriteOptions(target: SportsFavoriteTarget) {
        _uiState.update { it.copy(favoriteOptionsTarget = target) }
    }

    fun dismissFavoriteOptions() {
        _uiState.update { it.copy(favoriteOptionsTarget = null) }
    }

    fun retry() {
        viewModelScope.launch { refresh(favoriteTeamIds = preferences.favoriteTeamIds.value) }
    }

    private suspend fun refresh(favoriteTeamIds: Set<String>) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        val result = withContext(Dispatchers.IO) {
            runCatching { loadAll(favoriteTeamIds) }
        }
        result.onSuccess { loaded ->
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = null,
                    featuredEvent = loaded.liveNow.firstOrNull() ?: loaded.upcomingGames.firstOrNull(),
                    liveNow = loaded.liveNow,
                    upcomingGames = loaded.upcomingGames,
                    pickedForYou = loaded.pickedForYou,
                    leagues = loaded.leagues,
                    teams = loaded.teams.take(5),
                    browsableTeams = loaded.teams,
                    sportsTalk = mockSportsTalk(),
                    continueWatching = emptyList(),
                    favoriteTeams = loaded.favoriteTeams
                )
            }
        }.onFailure { throwable ->
            _uiState.update { it.copy(isLoading = false, error = throwable.message ?: "error") }
        }
    }

    private data class LoadedData(
        val liveNow: List<SportsEvent>,
        val upcomingGames: List<SportsEvent>,
        val pickedForYou: List<SportsEvent>,
        val leagues: List<SportsLeague>,
        val teams: List<SportsTeam>,
        val favoriteTeams: List<SportsTeam>
    )

    /** Fetches every curated ESPN feed and aggregates events/teams/leagues across all of them (each feed already carries its own league + team metadata, so no separate lookups are needed). */
    private fun loadAll(favoriteTeamIds: Set<String>): LoadedData {
        val results = client.fetchAllFixtures()

        val leagues = results.map { it.league }
        val allEvents = results.flatMap { it.events }
        val allTeams = results.flatMap { it.teams }.distinctBy { it.id }

        val liveNow = allEvents.filter { it.status == SportsEventStatus.LIVE }
            .sortedBy { it.startTimeMs ?: Long.MAX_VALUE }
        val upcomingGames = allEvents.filter { it.status == SportsEventStatus.UPCOMING }
            .distinctBy { it.id }
            .sortedBy { it.startTimeMs ?: Long.MAX_VALUE }
            .take(30)

        val favoriteTeams = allTeams.filter { it.id in favoriteTeamIds }

        val pickedForYou = if (favoriteTeams.isNotEmpty()) {
            val favoriteTeamIdSet = favoriteTeams.map { it.id }.toSet()
            upcomingGames.filter { it.homeTeamId in favoriteTeamIdSet || it.awayTeamId in favoriteTeamIdSet }
        } else {
            upcomingGames.take(10)
        }

        return LoadedData(
            liveNow = liveNow,
            upcomingGames = upcomingGames,
            pickedForYou = pickedForYou.ifEmpty { upcomingGames.take(10) },
            leagues = leagues,
            teams = allTeams,
            favoriteTeams = favoriteTeams
        )
    }

    private fun mockSportsTalk(): List<SportsTalkItem> = listOf(
        SportsTalkItem(
            id = "talk_1",
            title = "Weekend Recap: Biggest Upsets",
            summary = "The biggest surprises from this weekend's games across every league.",
            sourceName = "Sports Talk"
        ),
        SportsTalkItem(
            id = "talk_2",
            title = "Trade Rumors Heating Up",
            summary = "What's driving the latest chatter around the league ahead of the deadline.",
            sourceName = "Sports Talk"
        ),
        SportsTalkItem(
            id = "talk_3",
            title = "Power Rankings: Who's On Top",
            summary = "This week's power rankings after a wild stretch of games.",
            sourceName = "Sports Talk"
        )
    )
}
