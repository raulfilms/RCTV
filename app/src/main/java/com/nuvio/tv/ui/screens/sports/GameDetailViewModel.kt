package com.nuvio.tv.ui.screens.sports

import com.nuvio.tv.domain.model.hasTeam
import com.nuvio.tv.domain.model.SportsTeamKey
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
import kotlinx.coroutines.ensureActive
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
    val isInLibrary: Boolean = false,
    /** The game's highlights, playing full screen (Watch Recap); null when not open. */
    val recap: GameRecapState? = null,
    /** The teams' full names ("Tampa Bay Buccaneers"), from the game's summary. */
    val homeTeamFullName: String? = null,
    val awayTeamFullName: String? = null,
    /** The game's week (NFL) and season type (1 preseason, 2 regular season, 3 postseason). */
    val week: Int? = null,
    val seasonType: Int? = null,
    /** A golf tournament's leaderboard (instead of team stats). */
    val golfPlayers: List<EspnSportsClient.GolfPlayer> = emptyList(),
    /** A race's results, or its running order while it's on (NASCAR). */
    val raceResults: List<com.nuvio.tv.domain.model.SportsRaceResult> = emptyList(),
    /** A PPV show's poster, description and card. */
    val showDetails: com.nuvio.tv.domain.model.SportsShowDetails? = null
)

/** Watch Recap: looking for the game's highlights on YouTube, playing them, or none found. */
data class GameRecapState(
    val isLoading: Boolean = true,
    val title: String = "",
    val videoUrl: String? = null,
    val audioUrl: String? = null,
    val notFound: Boolean = false
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
    private val sportsLibraryStore: com.nuvio.tv.data.local.SportsLibraryStore,
    private val youTubeSearch: com.nuvio.tv.data.trailer.YouTubeVideoSearch,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val event: SportsEvent = buildEvent(savedStateHandle)

    /** The game is over: its page offers Watch Recap (its highlights). */
    val isOver: Boolean get() = event.isOverAt(System.currentTimeMillis())

    /** The teams' full names from the game's summary (to find its highlights). */
    @Volatile private var homeAliases: List<String> = emptyList()
    @Volatile private var awayAliases: List<String> = emptyList()
    private var recapJob: kotlinx.coroutines.Job? = null

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
        if (event.isGolf()) {
            viewModelScope.launch {
                val players = withContext(Dispatchers.IO) {
                    runCatching { client.fetchGolfLeaderboard(parsed.leaguePath, parsed.rawEventId, event.startTimeMs) }
                        .getOrDefault(emptyList())
                }
                _uiState.update { it.copy(isLoadingStats = false, golfPlayers = players) }
            }
            return
        }
        if (event.isRace()) {
            viewModelScope.launch {
                // While it's on, the running order every half minute.
                while (true) {
                    val results = withContext(Dispatchers.IO) {
                        runCatching { client.fetchRaceResults(parsed.leaguePath, parsed.rawEventId, event.startTimeMs) }
                            .getOrDefault(emptyList())
                    }
                    _uiState.update { state ->
                        state.copy(isLoadingStats = false, raceResults = results.ifEmpty { state.raceResults })
                    }
                    if (event.status != SportsEventStatus.LIVE) break
                    kotlinx.coroutines.delay(RACE_REFRESH_MS)
                }
            }
            return
        }
        if (event.isPpv()) {
            viewModelScope.launch {
                val details = withContext(Dispatchers.IO) {
                    runCatching { client.fetchShowDetails(parsed.rawEventId) }.getOrNull()
                }
                _uiState.update { it.copy(isLoadingStats = false, showDetails = details) }
            }
            return
        }
        viewModelScope.launch {
            val summary = withContext(Dispatchers.IO) {
                runCatching { client.fetchGameSummary(parsed.sportPath, parsed.leaguePath, parsed.rawEventId) }.getOrNull()
            }
            homeAliases = summary?.homeTeamAliases.orEmpty()
            awayAliases = summary?.awayTeamAliases.orEmpty()
            _uiState.update {
                it.copy(
                    isLoadingStats = false,
                    stats = summary?.stats.orEmpty(),
                    homeTeamFullName = SportsRecapPicker.fullNameOf(event.homeTeamName, homeAliases),
                    awayTeamFullName = SportsRecapPicker.fullNameOf(event.awayTeamName, awayAliases),
                    week = summary?.week,
                    seasonType = summary?.seasonType
                )
            }
        }
    }

    /**
     * Watch Recap: finds the game's highlights on YouTube (both teams, "Highlights", after the game)
     * and plays them full screen; says so when there are none.
     */
    fun watchRecap() {
        if (recapJob?.isActive == true) return
        _uiState.update { it.copy(recap = GameRecapState(isLoading = true)) }
        recapJob = viewModelScope.launch {
            val game = event.copy(
                homeTeamAliases = (event.homeTeamAliases + homeAliases).distinct(),
                awayTeamAliases = (event.awayTeamAliases + awayAliases).distinct()
            )
            val now = System.currentTimeMillis()
            val found = ArrayList<com.nuvio.tv.data.trailer.YouTubeSearchResult>()
            val spanish = SportsRecapPicker.isSpanish(game)
            val week = _uiState.value.week
            // RCTV (owner's list): a league with its own channel (NBA, NFL, LaLiga...) only from there:
            // its newest uploads first (a game that just ended), then a search of that channel.
            val official = OfficialRecapChannels.of(game)
            if (official != null) {
                val daysSinceGame = game.startTimeMs?.let { (now - it) / 86_400_000.0 }
                if (daysSinceGame == null || daysSinceGame <= RECAP_IN_LATEST_DAYS) {
                    val latest = runCatching { youTubeSearch.latestOfChannel(official.handle) }.getOrDefault(emptyList())
                    ensureActive() // Closed meanwhile.
                    found += SportsRecapPicker.pick(latest, game, now, week = week, channel = official)
                }
                if (found.isEmpty()) {
                    for (query in SportsRecapPicker.channelQueriesFor(game, official)) {
                        val results = runCatching {
                            youTubeSearch.searchChannel(query, official.handle, spanish = official.spanish)
                        }.getOrDefault(emptyList())
                        ensureActive()
                        found += SportsRecapPicker.pick(results, game, now, week = week, channel = official)
                        if (found.isNotEmpty()) break
                    }
                }
            } else {
                for (query in SportsRecapPicker.queriesFor(game)) {
                    val results = runCatching { youTubeSearch.search(query, spanish = spanish) }.getOrDefault(emptyList())
                    ensureActive() // Closed meanwhile.
                    found += SportsRecapPicker.pick(results, game, now, week = week)
                        .filter { candidate -> found.none { it.videoId == candidate.videoId } }
                    if (found.isNotEmpty()) break
                }
            }
            // RCTV Smart Player: the likeliest one, in YouTube's official player, inside RCTV. One its
            // owner keeps off other sites gives its place to the next one (never the YouTube app).
            recapCandidates = found.distinctBy { it.videoId }
            // RCTV (owner's rule): always 16:9: a vertical Short gives its place to the next one.
            recapAt = firstHorizontalFrom(0)
            showRecapCandidate()
        }
    }

    /**
     * RCTV: from [start], the first recap that YouTube doesn't say is a Short (vertical). One YouTube
     * says nothing about is kept (a recap from an official channel, not a Short in its search).
     */
    private suspend fun firstHorizontalFrom(start: Int): Int {
        var index = start
        while (index < recapCandidates.size) {
            val short = runCatching { youTubeSearch.isShort(recapCandidates[index].videoId) }.getOrNull()
            ensureActiveRecap()
            if (short != true) return index
            index++
        }
        return index
    }

    private suspend fun ensureActiveRecap() = kotlinx.coroutines.currentCoroutineContext().ensureActive()

    /** The highlights found for the game, the likeliest first; [recapAt]: the one playing. */
    private var recapCandidates: List<com.nuvio.tv.data.trailer.YouTubeSearchResult> = emptyList()
    private var recapAt = 0

    private fun showRecapCandidate() {
        val video = recapCandidates.getOrNull(recapAt)
        _uiState.update {
            it.copy(
                recap = if (video != null) {
                    GameRecapState(isLoading = false, title = video.title, videoUrl = video.watchUrl, audioUrl = null)
                } else {
                    GameRecapState(isLoading = false, notFound = true)
                }
            )
        }
    }

    /** RCTV: the video playing can't play here (its owner allows it only on YouTube): the next one. */
    fun recapUnplayable() {
        if (_uiState.value.recap == null) return
        if (recapJob?.isActive == true) return
        recapJob = viewModelScope.launch {
            recapAt = firstHorizontalFrom(recapAt + 1)
            showRecapCandidate()
        }
    }

    fun closeRecap() {
        recapJob?.cancel()
        recapCandidates = emptyList()
        _uiState.update { it.copy(recap = null) }
    }

    /** A team in or out of "My Teams" (and the Library); going in, its name and badge are kept. */
    /** "My Teams" by team and league ([SportsTeamKey]): [teamKey] is the team with its league. */
    fun toggleFavoriteTeam(teamKey: String) {
        val teamId = SportsTeamKey.teamIdOf(teamKey)
        val team = event.teamOf(teamId)
        viewModelScope.launch {
            val saved = preferences.favoriteTeamIds.value
            val isFavorite = teamKey in saved || teamId in saved
            if (team != null && !isFavorite) sportsLibraryStore.rememberTeams(listOf(team))
            preferences.toggleFavoriteTeam(teamKey)
        }
    }

    fun toggleFavoriteSport(sportName: String) {
        viewModelScope.launch { preferences.toggleFavoriteSport(sportName) }
    }

    fun toggleLibrary() {
        viewModelScope.launch {
            if (uiState.value.isInLibrary) {
                libraryPreferences.removeItem(itemId = event.id, itemType = SPORTS_EVENT_LIBRARY_TYPE)
            } else {
                // Its details go along, so it opens from the Library as it is now.
                sportsLibraryStore.rememberEvent(event)
                libraryPreferences.addItem(item = event.toSavedLibraryItem())
            }
        }
    }

    private companion object {
        /** A channel's newest uploads hold the games of about the last few days. */
        const val RECAP_IN_LATEST_DAYS = 3.0

        /** How often a race on now looks at its running order again. */
        const val RACE_REFRESH_MS = 30_000L

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
