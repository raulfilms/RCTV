package com.nuvio.tv.ui.screens.sports

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.SportsPreferencesDataStore
import com.nuvio.tv.data.remote.espn.EspnSportsClient
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import com.nuvio.tv.domain.model.SportsLeague
import com.nuvio.tv.domain.model.SportsNewsArticle
import com.nuvio.tv.domain.model.SportsStandingsGroup
import com.nuvio.tv.domain.model.SportsTeam
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

/** Which section of a league's page is showing, mirroring the tab bar in the mockup. */
enum class LeagueDetailTab { HOME, GAMES, TEAMS, STANDINGS, SCHEDULE, NEWS, HIGHLIGHTS, VIDEOS }

data class LeagueDetailUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val league: SportsLeague? = null,
    val selectedTab: LeagueDetailTab = LeagueDetailTab.HOME,

    // Home + Games: shared pool fetched once up front (live/recent/upcoming window).
    val featuredEvent: SportsEvent? = null,
    val liveEvents: List<SportsEvent> = emptyList(),
    val upcomingEvents: List<SportsEvent> = emptyList(),
    val allEvents: List<SportsEvent> = emptyList(),

    // Teams tab (lazy).
    val teams: List<SportsTeam> = emptyList(),
    val isLoadingTeams: Boolean = false,
    val teamsLoaded: Boolean = false,

    // Standings tab (lazy).
    val standings: List<SportsStandingsGroup> = emptyList(),
    val isLoadingStandings: Boolean = false,
    val standingsLoaded: Boolean = false,

    // Schedule tab (lazy, week-by-week).
    val scheduleWeekOffset: Int = 0,
    val scheduleEvents: List<SportsEvent> = emptyList(),
    val isLoadingSchedule: Boolean = false,
    val scheduleLoaded: Boolean = false,

    // News tab (lazy).
    val news: List<SportsNewsArticle> = emptyList(),
    val isLoadingNews: Boolean = false,
    val newsLoaded: Boolean = false,

    // Favoriting + Library, mirroring SportsScreen so the hero/long-press dialog behave identically here.
    val favoriteTeamIds: Set<String> = emptySet(),
    val favoriteSportNames: Set<String> = emptySet(),
    val libraryEventIds: Set<String> = emptySet(),
    val favoriteOptionsTarget: SportsFavoriteTarget? = null
)

/** A week, for moving the Schedule tab. */
private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

@HiltViewModel
class LeagueDetailViewModel @Inject constructor(
    private val client: EspnSportsClient,
    private val preferences: SportsPreferencesDataStore,
    private val libraryPreferences: LibraryPreferences,
    private val sportsLibraryStore: com.nuvio.tv.data.local.SportsLibraryStore,
    private val bananaBallLive: BananaBallLive,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    // Navigation-Compose's own decoding of a path segment containing ":" (as in "football:nfl") is
    // inconsistent across versions, so this is decoded defensively rather than assumed - a no-op if
    // the platform already decoded it, and the fix if it didn't (leaving us with "football%3Anfl").
    private val leagueId: String = decodeArg(savedStateHandle.get<String>("leagueId"))
    private val leagueNameArg: String = decodeArg(savedStateHandle.get<String>("leagueName"))
    private val sportNameArg: String = decodeArg(savedStateHandle.get<String>("sportName"))
    private val badgeUrlArg: String? = decodeArg(savedStateHandle.get<String>("badgeUrl")).takeIf { it.isNotBlank() }

    /** The curated feed behind this league, or null if navigated here with an id we don't recognize (defensive - shouldn't happen from in-app navigation). */
    private val feed: EspnSportsClient.Feed? = EspnSportsClient.feedForLeagueId(leagueId)

    private val _uiState = MutableStateFlow(
        LeagueDetailUiState(
            league = SportsLeague(id = leagueId, name = leagueNameArg, sportName = sportNameArg, badgeUrl = badgeUrlArg)
        )
    )
    val uiState: StateFlow<LeagueDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(preferences.favoriteSports, preferences.favoriteTeamIds) { sports, teamIds -> sports to teamIds }
                .collect { (sports, teamIds) ->
                    _uiState.update { it.copy(favoriteSportNames = sports, favoriteTeamIds = teamIds) }
                }
        }
        viewModelScope.launch {
            libraryPreferences.libraryItems.collect { items ->
                val ids = items
                    .filter { it.type.equals(SPORTS_EVENT_LIBRARY_TYPE, ignoreCase = true) }
                    .map { it.id }
                    .toSet()
                _uiState.update { it.copy(libraryEventIds = ids) }
            }
        }
        loadHome()
    }

    private fun loadHome() {
        val currentFeed = feed
        if (currentFeed == null) {
            _uiState.update { it.copy(isLoading = false, error = "Unknown league") }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val near = client.fetchFeed(currentFeed, client.dateRange(-3, 10))
                    // Banana Ball between seasons: its next season's games instead of nothing.
                    if (near.events.isEmpty() && currentFeed.isBananaBall()) client.fetchFeed(currentFeed) else near
                }
            }
            result.onSuccess { loadedFeed ->
                // As the clock has them: games whose start time has come are live.
                val now = System.currentTimeMillis()
                val feedResult = loadedFeed.copy(events = loadedFeed.events.map { it.withClock(now) })
                val league = feedResult.league
                val liveEvents = feedResult.events.filter { it.status == SportsEventStatus.LIVE }
                    .sortedBy { it.startTimeMs ?: Long.MAX_VALUE }
                val upcomingEvents = feedResult.events.filter { it.status == SportsEventStatus.UPCOMING }
                    .sortedBy { it.startTimeMs ?: Long.MAX_VALUE }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = null,
                        league = league,
                        featuredEvent = liveEvents.firstOrNull() ?: upcomingEvents.firstOrNull(),
                        liveEvents = liveEvents,
                        upcomingEvents = upcomingEvents,
                        allEvents = feedResult.events.sortedBy { event -> event.startTimeMs ?: Long.MAX_VALUE }
                    )
                }
            }.onFailure { throwable ->
                _uiState.update { it.copy(isLoading = false, error = throwable.message ?: "error") }
            }
        }
    }

    fun retry() = loadHome()

    fun selectTab(tab: LeagueDetailTab) {
        _uiState.update { it.copy(selectedTab = tab) }
        when (tab) {
            LeagueDetailTab.TEAMS -> loadTeamsIfNeeded()
            LeagueDetailTab.STANDINGS -> loadStandingsIfNeeded()
            LeagueDetailTab.SCHEDULE -> loadScheduleIfNeeded()
            LeagueDetailTab.NEWS -> loadNewsIfNeeded()
            else -> Unit
        }
    }

    private fun loadTeamsIfNeeded() {
        val currentFeed = feed ?: return
        if (uiState.value.teamsLoaded || uiState.value.isLoadingTeams) return
        _uiState.update { it.copy(isLoadingTeams = true) }
        viewModelScope.launch {
            val teams = withContext(Dispatchers.IO) {
                runCatching { client.fetchTeams(currentFeed) }.getOrDefault(emptyList())
            }
            _uiState.update { it.copy(isLoadingTeams = false, teamsLoaded = true, teams = teams) }
        }
    }

    private fun loadStandingsIfNeeded() {
        val currentFeed = feed ?: return
        if (uiState.value.standingsLoaded || uiState.value.isLoadingStandings) return
        _uiState.update { it.copy(isLoadingStandings = true) }
        viewModelScope.launch {
            val groups = withContext(Dispatchers.IO) {
                runCatching { client.fetchStandings(currentFeed) }.getOrDefault(emptyList())
            }
            _uiState.update { it.copy(isLoadingStandings = false, standingsLoaded = true, standings = groups) }
        }
    }

    private fun loadNewsIfNeeded() {
        val currentFeed = feed ?: return
        if (uiState.value.newsLoaded || uiState.value.isLoadingNews) return
        _uiState.update { it.copy(isLoadingNews = true) }
        viewModelScope.launch {
            val articles = withContext(Dispatchers.IO) {
                runCatching { client.fetchNews(currentFeed) }.getOrDefault(emptyList())
            }
            _uiState.update { it.copy(isLoadingNews = false, newsLoaded = true, news = articles) }
        }
    }

    private fun loadScheduleIfNeeded() {
        if (uiState.value.scheduleLoaded || uiState.value.isLoadingSchedule) return
        var weekOffset = uiState.value.scheduleWeekOffset
        // Banana Ball between seasons: the schedule opens on the week its next game is in.
        if (weekOffset == 0 && feed?.isBananaBall() == true) {
            val now = System.currentTimeMillis()
            val firstGame = uiState.value.upcomingEvents.mapNotNull { it.startTimeMs }.filter { it > now }.minOrNull()
            if (firstGame != null) weekOffset = ((firstGame - now) / WEEK_MS).toInt()
        }
        loadScheduleWeek(weekOffset)
    }

    private fun EspnSportsClient.Feed.isBananaBall(): Boolean =
        leaguePath == com.nuvio.tv.data.remote.espn.BananaBallSource.LEAGUE_PATH

    /** Moves the Schedule tab by [delta] weeks (e.g. -1/+1 for the mockup's "< WEEK >" control) and reloads. */
    fun changeScheduleWeek(delta: Int) {
        loadScheduleWeek(uiState.value.scheduleWeekOffset + delta)
    }

    private fun loadScheduleWeek(weekOffset: Int) {
        val currentFeed = feed ?: return
        _uiState.update { it.copy(isLoadingSchedule = true, scheduleWeekOffset = weekOffset) }
        viewModelScope.launch {
            val startDay = weekOffset * 7
            val range = client.dateRange(startDay, startDay + 6)
            val events = withContext(Dispatchers.IO) {
                runCatching { client.fetchFeed(currentFeed, range).events }.getOrDefault(emptyList())
            }
            _uiState.update {
                it.copy(
                    isLoadingSchedule = false,
                    scheduleLoaded = true,
                    scheduleEvents = events
                        .map { event -> event.withClock(System.currentTimeMillis()) }
                        .sortedBy { event -> event.startTimeMs ?: Long.MAX_VALUE }
                )
            }
        }
    }

    /** While the page shows (call it from the page): games whose start time has come go live. */
    suspend fun keepCurrent() {
        while (true) {
            kotlinx.coroutines.delay(SPORTS_CLOCK_TICK_MS)
            // Banana Ball's games on now (on TV, or streaming on YouTube).
            runCatching { _uiState.value.let { bananaBallLive.refresh(it.upcomingEvents + it.scheduleEvents + it.liveEvents) } }
            val now = System.currentTimeMillis()
            _uiState.update { state ->
                val started = state.upcomingEvents.filter { it.isLiveAt(now) }
                val scheduleStarted = state.scheduleEvents.any { it.status == SportsEventStatus.UPCOMING && it.isLiveAt(now) }
                if (started.isEmpty() && !scheduleStarted) return@update state
                val startedIds = started.map { it.id }.toSet()
                val live = (state.liveEvents + started.map { it.withClock(now) }).sortedBy { it.startTimeMs ?: Long.MAX_VALUE }
                val upcoming = state.upcomingEvents.filterNot { it.id in startedIds }
                state.copy(
                    liveEvents = live,
                    upcomingEvents = upcoming,
                    featuredEvent = live.firstOrNull() ?: upcoming.firstOrNull(),
                    allEvents = state.allEvents.map { it.withClock(now) },
                    scheduleEvents = state.scheduleEvents.map { it.withClock(now) }
                )
            }
        }
    }

    fun toggleFavoriteSport(sportName: String) {
        viewModelScope.launch { preferences.toggleFavoriteSport(sportName) }
    }

    /** A team in or out of "My Teams" (and the Library); going in, its name and badge are kept. */
    fun toggleFavoriteTeam(teamId: String) {
        val state = uiState.value
        val team = (state.teams + state.standings.flatMap { group -> group.entries.map { it.team } }).firstOrNull { it.id == teamId }
            ?: (state.allEvents + state.scheduleEvents).firstNotNullOfOrNull { it.teamOf(teamId) }
        viewModelScope.launch {
            if (team != null && teamId !in preferences.favoriteTeamIds.value) sportsLibraryStore.rememberTeams(listOf(team))
            preferences.toggleFavoriteTeam(teamId)
        }
    }

    fun toggleLibraryEvent(event: SportsEvent) {
        viewModelScope.launch {
            if (event.id in uiState.value.libraryEventIds) {
                libraryPreferences.removeItem(itemId = event.id, itemType = SPORTS_EVENT_LIBRARY_TYPE)
            } else {
                libraryPreferences.addItem(item = event.toSavedLibraryItem())
            }
        }
    }

    fun showFavoriteOptions(target: SportsFavoriteTarget) {
        _uiState.update { it.copy(favoriteOptionsTarget = target) }
    }

    fun dismissFavoriteOptions() {
        _uiState.update { it.copy(favoriteOptionsTarget = null) }
    }

    private companion object {
        fun decodeArg(raw: String?): String {
            if (raw.isNullOrEmpty()) return ""
            return runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        }
    }
}
