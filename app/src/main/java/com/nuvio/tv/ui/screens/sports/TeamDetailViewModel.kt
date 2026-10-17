package com.nuvio.tv.ui.screens.sports

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.SportsLibraryStore
import com.nuvio.tv.data.local.SportsPreferencesDataStore
import com.nuvio.tv.data.remote.espn.EspnSportsClient
import com.nuvio.tv.domain.model.SportsAthlete
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import com.nuvio.tv.domain.model.SportsNewsArticle
import com.nuvio.tv.domain.model.SportsTeam
import com.nuvio.tv.domain.model.SportsTeamGame
import com.nuvio.tv.domain.model.SportsTeamHistory
import com.nuvio.tv.domain.model.SportsTeamProfile
import com.nuvio.tv.domain.model.SportsTeamStatCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.util.Locale
import javax.inject.Inject

/** The tabs under a team page's header. */
enum class TeamPageTab { SCHEDULE, ROSTER, STATS, NEWS, VIDEOS, HISTORY }

/** Loading, loaded (maybe empty) or failed: one tab's data. */
data class TeamTabData<T>(val items: List<T> = emptyList(), val isLoading: Boolean = false, val loaded: Boolean = false)

data class TeamDetailUiState(
    val profile: SportsTeamProfile? = null,
    val isLoadingProfile: Boolean = true,
    val games: List<SportsTeamGame> = emptyList(),
    val isLoadingSchedule: Boolean = true,
    val scheduleFailed: Boolean = false,
    /** The game on now, else the next one, else the last one played: where the schedule opens. */
    val currentGameIndex: Int = 0,
    val roster: TeamTabData<SportsAthlete> = TeamTabData(),
    val stats: TeamTabData<SportsTeamStatCategory> = TeamTabData(),
    val news: TeamTabData<SportsNewsArticle> = TeamTabData(),
    val history: SportsTeamHistory? = null,
    val isLoadingHistory: Boolean = false,
    val historyLoaded: Boolean = false,
    /** Every team of the league ("All NFL Teams"), with their colors. */
    val leagueTeams: List<SportsTeam> = emptyList(),
    /** The team's star (the player who led it most this season), for the header. */
    val featuredPlayer: SportsAthlete? = null,
    /** Each game's players, by event id, then by team id (loaded as the games show). */
    val gamePlayers: Map<String, Map<String, List<SportsAthlete>>> = emptyMap(),
    val isInLibrary: Boolean = false,
    val favoriteTeamIds: Set<String> = emptySet(),
    val favoriteSportNames: Set<String> = emptySet(),
    val libraryEventIds: Set<String> = emptySet()
)

/**
 * Backs a team's page: its header (city, name, record, standing), its schedule (the whole season,
 * opening on the game or week on now), roster, stats, news and history, all from ESPN (history from
 * Wikipedia). "Add to Library" puts the team in My Teams (the Library's teams).
 */
@HiltViewModel
class TeamDetailViewModel @Inject constructor(
    private val client: EspnSportsClient,
    private val preferences: SportsPreferencesDataStore,
    private val libraryPreferences: LibraryPreferences,
    private val sportsLibraryStore: SportsLibraryStore,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private fun arg(name: String): String? = savedStateHandle.get<String>(name)
        ?.let { value -> runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value) }
        ?.takeIf { it.isNotBlank() }

    val leagueId: String = arg("leagueId").orEmpty()
    val teamId: String = arg("teamId").orEmpty()
    private val feed: EspnSportsClient.Feed? = EspnSportsClient.feedForLeagueId(leagueId)

    /** What the page knows before ESPN answers (from the tile that opened it). */
    val initialName: String = arg("name").orEmpty()
    val initialBadgeUrl: String? = arg("badgeUrl")
    val initialColor: String? = arg("color")

    val isSoccer: Boolean = feed?.sportPath == "soccer" || leagueId.startsWith("soccer")

    /** "NFL", "La Liga". */
    val leagueLabel: String = EspnSportsClient.TEAMS_FILTERS.firstOrNull { it.id == leagueId }?.label
        ?: leagueId.substringAfter(':').uppercase(Locale.US)

    private val _uiState = MutableStateFlow(TeamDetailUiState())
    val uiState: StateFlow<TeamDetailUiState> = _uiState.asStateFlow()

    private val loadingPlayers = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            preferences.favoriteTeamIds.collect { ids ->
                _uiState.update { it.copy(favoriteTeamIds = ids, isInLibrary = teamId in ids) }
            }
        }
        viewModelScope.launch {
            preferences.favoriteSports.collect { sports -> _uiState.update { it.copy(favoriteSportNames = sports) } }
        }
        viewModelScope.launch {
            libraryPreferences.libraryItems.collect { items ->
                val ids = items.filter { it.type.equals(SPORTS_EVENT_LIBRARY_TYPE, ignoreCase = true) }.map { it.id }.toSet()
                _uiState.update { it.copy(libraryEventIds = ids) }
            }
        }
        load()
    }

    private fun load() {
        val feed = feed
        if (feed == null || teamId.isBlank()) {
            _uiState.update { it.copy(isLoadingProfile = false, isLoadingSchedule = false, scheduleFailed = true) }
            return
        }
        viewModelScope.launch {
            val profile = withContext(Dispatchers.IO) { runCatching { client.fetchTeamProfile(feed, teamId) }.getOrNull() }
            _uiState.update { it.copy(profile = profile, isLoadingProfile = false) }
        }
        viewModelScope.launch {
            val leagueTeams = async(Dispatchers.IO) { runCatching { client.fetchTeams(feed) }.getOrDefault(emptyList()) }
            val games = withContext(Dispatchers.IO) {
                runCatching { client.fetchTeamSchedule(feed, teamId, leagueLabel) }
            }
            val teams = leagueTeams.await()
            val colors = teams.associate { it.id to it.color }
            // The schedule has no colors for the other teams: the league's teams do.
            val now = System.currentTimeMillis()
            val list = games.getOrDefault(emptyList()).map { game ->
                // As the clock has it: a game whose start time has come is live.
                val event = game.event.withClock(now)
                game.copy(
                    event = event.copy(
                        homeTeamColor = event.homeTeamColor ?: colors[event.homeTeamId],
                        awayTeamColor = event.awayTeamColor ?: colors[event.awayTeamId]
                    )
                )
            }
            _uiState.update {
                it.copy(
                    games = list,
                    currentGameIndex = currentIndexOf(list),
                    isLoadingSchedule = false,
                    scheduleFailed = games.isFailure,
                    leagueTeams = teams.sortedBy { team -> team.name },
                    featuredPlayer = it.featuredPlayer ?: starOf(list)
                )
            }
            // No games played yet to tell the star: the roster's.
            if (_uiState.value.featuredPlayer == null) loadRoster()
        }
    }

    /** While the page shows (call it from the page): a game whose start time has come goes live. */
    suspend fun keepCurrent() {
        while (true) {
            kotlinx.coroutines.delay(SPORTS_CLOCK_TICK_MS)
            val now = System.currentTimeMillis()
            _uiState.update { state ->
                val started = state.games.any { it.event.status == SportsEventStatus.UPCOMING && it.event.isLiveAt(now) }
                if (!started) return@update state
                state.copy(games = state.games.map { game -> game.copy(event = game.event.withClock(now)) })
            }
        }
    }

    /** The game on now, else the next one, else the last one. */
    private fun currentIndexOf(games: List<SportsTeamGame>): Int {
        if (games.isEmpty()) return 0
        games.indexOfFirst { it.event.status == SportsEventStatus.LIVE }.takeIf { it >= 0 }?.let { return it }
        games.indexOfFirst { it.event.status == SportsEventStatus.UPCOMING }.takeIf { it >= 0 }?.let { return it }
        return games.lastIndex
    }

    /** The player who led the team in the most games played. */
    private fun starOf(games: List<SportsTeamGame>): SportsAthlete? {
        val counts = LinkedHashMap<String, Pair<SportsAthlete, Int>>()
        games.asReversed().forEach { game ->
            game.leaders[teamId].orEmpty().take(1).forEach { athlete ->
                if (athlete.headshotUrl == null) return@forEach
                val known = counts[athlete.id]
                counts[athlete.id] = athlete to ((known?.second ?: 0) + 1)
            }
        }
        return counts.values.maxByOrNull { it.second }?.first
    }

    /** The roster's star: by position (QB, C...), then the most years in the league. */
    private fun rosterStar(roster: List<SportsAthlete>): SportsAthlete? {
        val withPhoto = roster.filter { it.headshotUrl != null }
        if (withPhoto.isEmpty()) return null
        val preferred = when (feed?.sportPath) {
            "football" -> listOf("QB")
            "hockey" -> listOf("C", "LW", "RW")
            "basketball" -> listOf("PG", "SF", "SG", "G", "F")
            "baseball" -> listOf("SS", "CF", "RF", "DH", "1B", "SP")
            "soccer" -> listOf("F", "FW", "ST", "M", "AM")
            else -> emptyList()
        }
        val byPosition = withPhoto.filter { athlete -> preferred.any { it.equals(athlete.position, ignoreCase = true) } }
        return (byPosition.ifEmpty { withPhoto }).maxByOrNull { it.experienceYears ?: 0 }
    }

    fun onTabShown(tab: TeamPageTab) {
        when (tab) {
            TeamPageTab.ROSTER -> loadRoster()
            TeamPageTab.STATS -> loadStats()
            TeamPageTab.NEWS -> loadNews()
            TeamPageTab.HISTORY -> loadHistory()
            TeamPageTab.SCHEDULE, TeamPageTab.VIDEOS -> Unit
        }
    }

    private fun loadRoster() {
        val feed = feed ?: return
        val state = _uiState.value.roster
        if (state.isLoading || state.loaded) return
        _uiState.update { it.copy(roster = it.roster.copy(isLoading = true)) }
        viewModelScope.launch {
            val roster = withContext(Dispatchers.IO) { runCatching { client.fetchTeamRoster(feed, teamId) }.getOrDefault(emptyList()) }
            _uiState.update {
                it.copy(
                    roster = TeamTabData(items = roster, isLoading = false, loaded = true),
                    featuredPlayer = it.featuredPlayer ?: rosterStar(roster)
                )
            }
        }
    }

    private fun loadStats() {
        val feed = feed ?: return
        val state = _uiState.value.stats
        if (state.isLoading || state.loaded) return
        _uiState.update { it.copy(stats = it.stats.copy(isLoading = true)) }
        viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) { runCatching { client.fetchTeamStats(feed, teamId) }.getOrDefault(emptyList()) }
            _uiState.update { it.copy(stats = TeamTabData(items = stats, isLoading = false, loaded = true)) }
        }
    }

    private fun loadNews() {
        val feed = feed ?: return
        val state = _uiState.value.news
        if (state.isLoading || state.loaded) return
        _uiState.update { it.copy(news = it.news.copy(isLoading = true)) }
        viewModelScope.launch {
            val news = withContext(Dispatchers.IO) { runCatching { client.fetchTeamNews(feed, teamId) }.getOrDefault(emptyList()) }
            _uiState.update { it.copy(news = TeamTabData(items = news, isLoading = false, loaded = true)) }
        }
    }

    private fun loadHistory() {
        val state = _uiState.value
        if (state.isLoadingHistory || state.historyLoaded) return
        val name = state.profile?.displayName ?: initialName
        if (name.isBlank()) return
        _uiState.update { it.copy(isLoadingHistory = true) }
        val titles = if (isSoccer) {
            listOf(name, "$name F.C.", "$name FC", "$name CF", "$name C.F.")
        } else {
            listOf(name)
        }
        val keywords = when (feed?.sportPath) {
            "football" -> listOf("football", "fútbol americano", "nfl")
            "basketball" -> listOf("basketball", "baloncesto", "nba")
            "baseball" -> listOf("baseball", "béisbol", "mlb")
            "hockey" -> listOf("hockey", "nhl")
            "soccer" -> listOf("football", "soccer", "fútbol", "club")
            else -> listOf("team", "equipo", "club")
        }
        val language = Locale.getDefault().language.ifBlank { "en" }
        viewModelScope.launch {
            val history = withContext(Dispatchers.IO) {
                runCatching { client.fetchTeamHistory(titles, language, keywords) }.getOrNull()
            }
            _uiState.update { it.copy(history = history, isLoadingHistory = false, historyLoaded = true) }
        }
    }

    /** A game's players (its leaders' photos), the first time its card shows. */
    fun loadGamePlayers(game: SportsTeamGame) {
        val feed = feed ?: return
        val eventId = game.event.id
        if (eventId in _uiState.value.gamePlayers || !loadingPlayers.add(eventId)) return
        val parsed = EspnSportsClient.parseEventId(eventId) ?: return
        val rawId = parsed.rawEventId
        // The game's own competition (a club's Champions League game, say).
        val gameFeed = if (parsed.leaguePath != feed.leaguePath) feed.copy(leaguePath = parsed.leaguePath) else feed
        viewModelScope.launch {
            val players = withContext(Dispatchers.IO) {
                runCatching { client.fetchGamePlayers(gameFeed, rawId) }.getOrDefault(emptyMap())
            }
            // The schedule's own leaders fill in a team the summary has none for.
            val merged = LinkedHashMap(game.leaders.mapValues { (_, list) -> list.filter { it.headshotUrl != null } })
            players.forEach { (team, list) -> if (list.isNotEmpty()) merged[team] = list }
            _uiState.update { it.copy(gamePlayers = it.gamePlayers + (eventId to merged)) }
        }
    }

    /** Add to Library: the team into My Teams (and the Library's teams), or back out. */
    fun toggleLibrary() {
        viewModelScope.launch {
            if (teamId !in preferences.favoriteTeamIds.value) {
                runCatching { sportsLibraryStore.rememberTeams(listOf(teamForLibrary())) }
            }
            preferences.toggleFavoriteTeam(teamId)
        }
    }

    fun toggleFavoriteTeam(id: String) {
        viewModelScope.launch {
            if (id == teamId && id !in preferences.favoriteTeamIds.value) {
                runCatching { sportsLibraryStore.rememberTeams(listOf(teamForLibrary())) }
            } else if (id !in preferences.favoriteTeamIds.value) {
                _uiState.value.leagueTeams.firstOrNull { it.id == id }?.let { team ->
                    runCatching { sportsLibraryStore.rememberTeams(listOf(team)) }
                }
            }
            preferences.toggleFavoriteTeam(id)
        }
    }

    fun toggleFavoriteSport(sportName: String) {
        viewModelScope.launch { preferences.toggleFavoriteSport(sportName) }
    }

    /** A game in or out of the Library. */
    fun toggleLibraryEvent(event: SportsEvent) {
        viewModelScope.launch {
            if (event.id in _uiState.value.libraryEventIds) {
                libraryPreferences.removeItem(itemId = event.id, itemType = SPORTS_EVENT_LIBRARY_TYPE)
            } else {
                runCatching { sportsLibraryStore.rememberEvent(event) }
                libraryPreferences.addItem(item = event.toSavedLibraryItem())
            }
        }
    }

    /** The team as My Teams keeps it. */
    fun teamForLibrary(): SportsTeam {
        val profile = _uiState.value.profile
        return SportsTeam(
            id = teamId,
            name = profile?.displayName ?: initialName,
            shortName = profile?.abbreviation,
            sportName = feed?.sportLabel,
            leagueId = leagueId,
            leagueName = leagueLabel,
            badgeUrl = profile?.logoUrl ?: initialBadgeUrl,
            color = profile?.color ?: initialColor
        )
    }
}
