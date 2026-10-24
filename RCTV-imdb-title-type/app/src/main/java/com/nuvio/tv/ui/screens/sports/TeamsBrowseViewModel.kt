package com.nuvio.tv.ui.screens.sports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.SportsPreferencesDataStore
import com.nuvio.tv.data.remote.espn.EspnSportsClient
import com.nuvio.tv.domain.model.SportsLeague
import com.nuvio.tv.domain.model.SportsTeam
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** A league's teams on the Teams page, under its short name ("NFL") and its sport's emoji ("🏈"). */
data class TeamsLeagueSection(
    val league: SportsLeague,
    val teams: List<SportsTeam>,
    val label: String = league.name,
    val emoji: String = "",
    /** Unique on the page (a college league has a section per division or conference). */
    val key: String = league.id
)

data class TeamsBrowseUiState(
    val isLoading: Boolean = true,
    val selectedFilterId: String? = null,
    val sections: List<TeamsLeagueSection> = emptyList(),
    val favoriteTeamIds: Set<String> = emptySet(),
    val favoriteTeams: List<SportsTeam> = emptyList()
)

/**
 * Backs the "Teams" See All screen: a filterable, section-per-league browse of teams. Each section's
 * teams are the league's full roster (every team in the league, not just whoever happens to be
 * playing this week), cached in-memory for the lifetime of this ViewModel so switching filters back
 * and forth doesn't re-fetch.
 *
 * RCTV: the leagues load a few at a time (the NCAA's, hundreds of teams each, one at a time) and
 * show as they come, and only their teams are asked for: every league at once, each with its
 * scoreboard too, and every league's games for ten days to name the person's teams, took more
 * memory than a TV has (the system closed the app). The person's teams are named from the
 * Library's saved ones and the leagues loaded.
 */
@HiltViewModel
class TeamsBrowseViewModel @Inject constructor(
    private val client: EspnSportsClient,
    private val preferences: SportsPreferencesDataStore,
    private val sportsLibraryStore: com.nuvio.tv.data.local.SportsLibraryStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(TeamsBrowseUiState())
    val uiState: StateFlow<TeamsBrowseUiState> = _uiState.asStateFlow()

    val filters = EspnSportsClient.TEAMS_FILTERS

    /** Each league's sections once loaded (by "sport:league"); a league that couldn't load isn't kept (tried again). */
    private val sectionCache = ConcurrentHashMap<String, List<TeamsLeagueSection>>()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            combine(preferences.favoriteTeamIds, sportsLibraryStore.teams) { ids, _ -> ids }.collect { ids ->
                _uiState.update { it.copy(favoriteTeamIds = ids) }
                hydrateFavoriteTeams(ids)
            }
        }
        selectFilter(null)
    }

    /** A team in or out of "My Teams" (and the Library); going in, its name and badge are kept. */
    fun toggleFavoriteTeam(teamId: String) {
        val state = uiState.value
        val team = (state.favoriteTeams + state.sections.flatMap { it.teams }).firstOrNull { it.id == teamId }
        viewModelScope.launch {
            if (team != null && teamId !in preferences.favoriteTeamIds.value) sportsLibraryStore.rememberTeams(listOf(team))
            preferences.toggleFavoriteTeam(teamId)
        }
    }

    fun selectFilter(filterId: String?) {
        loadJob?.cancel()
        val feeds = if (filterId == null) {
            // The leagues with teams (not golf's tours, NASCAR's series or PPV Events). RCTV: the
            // NCAA's (a thousand teams each) only on their own chip: with every other league on the
            // same page they took more memory than the TV has, and the system closed the app.
            filters.map { it.feed }.filter { it.leaguePath !in EspnSportsClient.COLLEGE_LEAGUE_PATHS }
        } else {
            filters.firstOrNull { it.id == filterId }?.let { listOf(it.feed) }.orEmpty()
        }
        // What's loaded already shows at once.
        _uiState.update {
            it.copy(
                isLoading = feeds.any { feed -> !sectionCache.containsKey(keyOf(feed)) },
                selectedFilterId = filterId,
                sections = sectionsOf(feeds)
            )
        }
        loadJob = viewModelScope.launch {
            loadSections(feeds) {
                // Each league as it comes in, in the filters' order.
                _uiState.update { state ->
                    if (state.selectedFilterId != filterId) state else state.copy(sections = sectionsOf(feeds))
                }
            }
            _uiState.update { state ->
                if (state.selectedFilterId != filterId) state else state.copy(isLoading = false, sections = sectionsOf(feeds))
            }
            hydrateFavoriteTeams(uiState.value.favoriteTeamIds)
        }
    }

    private fun keyOf(feed: EspnSportsClient.Feed): String = "${feed.sportPath}:${feed.leaguePath}"

    /** [feeds]' sections loaded so far, in their order. */
    private fun sectionsOf(feeds: List<EspnSportsClient.Feed>): List<TeamsLeagueSection> =
        feeds.flatMap { sectionCache[keyOf(it)].orEmpty() }.filter { it.teams.isNotEmpty() }

    /** The leagues of [feeds] not loaded yet, a few at a time (the NCAA's one at a time); [onLoaded] after each. */
    private suspend fun loadSections(feeds: List<EspnSportsClient.Feed>, onLoaded: () -> Unit) = withContext(Dispatchers.IO) {
        val permits = Semaphore(LEAGUES_AT_ONCE)
        val heavyPermit = Semaphore(1)
        coroutineScope {
            feeds.filter { !sectionCache.containsKey(keyOf(it)) }.distinctBy { keyOf(it) }.forEach { feed ->
                launch {
                    val heavy = feed.leaguePath in EspnSportsClient.COLLEGE_LEAGUE_PATHS
                    val sections = if (heavy) {
                        heavyPermit.withPermit { permits.withPermit { sectionsOfLeague(feed) } }
                    } else {
                        permits.withPermit { sectionsOfLeague(feed) }
                    }
                    if (sections.any { it.teams.isNotEmpty() }) {
                        sectionCache[keyOf(feed)] = sections
                        onLoaded()
                    }
                }
            }
        }
    }

    /** A league's teams: every one (the NCAA's by division or conference). Only the teams are asked for. */
    private fun sectionsOfLeague(feed: EspnSportsClient.Feed): List<TeamsLeagueSection> {
        val cacheKey = keyOf(feed)
        val leagueLabel = filters.firstOrNull { it.id == cacheKey }?.label ?: feed.leaguePath
        val league = SportsLeague(id = cacheKey, name = leagueLabel, sportName = feed.sportLabel)
        return if (feed.leaguePath in EspnSportsClient.COLLEGE_LEAGUE_PATHS) {
            // The NCAA: every division's teams, a section per division or conference.
            val groups = runCatching { client.fetchTeamGroups(feed) }.getOrDefault(emptyList())
            groups.mapIndexed { index, group ->
                TeamsLeagueSection(
                    league = league,
                    teams = group.teams,
                    label = listOfNotNull(leagueLabel, group.label).joinToString(" · "),
                    emoji = feed.emoji,
                    key = "$cacheKey#$index"
                )
            }
        } else {
            val teams = runCatching { client.fetchTeams(feed) }.getOrDefault(emptyList())
            listOf(TeamsLeagueSection(league = league, teams = teams, label = leagueLabel, emoji = feed.emoji))
        }
    }

    /** The person's teams: as loaded with their leagues, else as the Library keeps them. */
    private suspend fun hydrateFavoriteTeams(ids: Set<String>) {
        if (ids.isEmpty()) {
            _uiState.update { it.copy(favoriteTeams = emptyList()) }
            return
        }
        val loaded = sectionCache.values.flatten().flatMap { it.teams }.associateBy { it.id }
        val saved = sportsLibraryStore.teams.value.associate { it.team.id to it.team }
        val teams = ids.mapNotNull { id -> loaded[id] ?: saved[id] }
        _uiState.update { it.copy(favoriteTeams = teams) }
        // The Library shows "My Teams" with these names and badges: the ones it didn't have yet.
        val newlyFound = teams.filter { it.id !in saved }
        if (newlyFound.isNotEmpty()) runCatching { sportsLibraryStore.rememberTeams(newlyFound) }
    }

    private companion object {
        /** How many leagues' teams are asked for at once. */
        const val LEAGUES_AT_ONCE = 3
    }
}
