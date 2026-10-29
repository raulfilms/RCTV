package com.nuvio.tv.ui.screens.sports

import com.nuvio.tv.core.util.runCatchingCancellable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.SportsPreferencesDataStore
import com.nuvio.tv.data.remote.espn.EspnSportsClient
import com.nuvio.tv.data.repository.SportsAddonRepository
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SavedLibraryItem
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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Local-only content type used to bookmark a schedule fixture in the Library, alongside movies/series. Never synced to Trakt/Simkl - a match has no tmdb/imdb id for those providers to key off of. */
const val SPORTS_EVENT_LIBRARY_TYPE = "sports_event"

/** Builds the Library entry for a bookmarked match: a schedule/score bookmark, not a playable addon item (so it has no addonBaseUrl). */
fun SportsEvent.toSavedLibraryItem(): SavedLibraryItem {
    val matchup = "$homeTeamName vs $awayTeamName"
    return SavedLibraryItem(
        id = id,
        type = SPORTS_EVENT_LIBRARY_TYPE,
        name = name.takeIf { it.isNotBlank() } ?: matchup,
        poster = null,
        posterShape = PosterShape.LANDSCAPE,
        background = homeTeamBadgeUrl ?: awayTeamBadgeUrl,
        description = listOfNotNull(matchup, leagueName ?: sportName).joinToString(" • "),
        releaseInfo = null,
        imdbRating = null,
        genres = listOfNotNull(sportName),
        addonBaseUrl = null
    )
}

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
    /** The banner's games: the live ones (up to five), else the next ones. */
    val heroEvents: List<SportsEvent> = emptyList(),
    /** Sports talk shows on TMDB (the most popular first), with their backdrops. */
    val sportsTalkShows: List<com.nuvio.tv.domain.model.MetaPreview> = emptyList(),
    val isLoadingMoreTalk: Boolean = false,
    val hasMoreTalk: Boolean = true,
    val continueWatching: List<SportsContinueWatchingItem> = emptyList(),
    val liveNow: List<SportsEvent> = emptyList(),
    val upcomingGames: List<SportsEvent> = emptyList(),
    val pickedForYou: List<SportsEvent> = emptyList(),
    val leagues: List<SportsLeague> = emptyList(),
    /** Fixed marquee picks for the home page's "Teams" row (Real Madrid, Barcelona, Lakers, Cowboys, Yankees) - not "whoever's playing this week". */
    val teams: List<SportsTeam> = emptyList(),
    /** Full pool of teams gathered from the curated leagues, used by the favorites picker (unlike [teams], not the fixed home-row picks). */
    val browsableTeams: List<SportsTeam> = emptyList(),
    val sportsTalk: List<SportsTalkItem> = emptyList(),
    /** Whole sports saved in "My Sports" before it held leagues (shown after [favoriteLeagues]). */
    val favoriteSportNames: Set<String> = emptySet(),
    /** The leagues in "My Sports" (also the Library's Sports Leagues). */
    val favoriteLeagues: List<SportsLeague> = emptyList(),
    /** "My Teams" (also the Library's Sports Teams). */
    val favoriteTeams: List<SportsTeam> = emptyList(),
    /** Actually-playable live/upcoming events sourced from the user's installed sports addons (phase 3). Separate from the ESPN rows above, which are metadata/schedule-only. */
    val addonEvents: List<SportsAddonEvent> = emptyList(),
    val isLoadingAddonEvents: Boolean = false,
    val selectedAddonEvent: SportsAddonEvent? = null,
    val addonEventStreams: List<Stream> = emptyList(),
    val isLoadingAddonStreams: Boolean = false,
    val addonStreamsError: String? = null,
    /** Non-null while the "hold to favorite" options dialog is showing for a long-pressed team/match/league. */
    val favoriteOptionsTarget: SportsFavoriteTarget? = null,
    /** IDs of matches bookmarked to the Library (see [SPORTS_EVENT_LIBRARY_TYPE]). */
    val libraryEventIds: Set<String> = emptySet()
)

@HiltViewModel
class SportsViewModel @Inject constructor(
    private val client: EspnSportsClient,
    private val preferences: SportsPreferencesDataStore,
    private val sportsAddonRepository: SportsAddonRepository,
    private val libraryPreferences: LibraryPreferences,
    private val sportsLibraryStore: com.nuvio.tv.data.local.SportsLibraryStore,
    private val tmdbApi: com.nuvio.tv.data.remote.api.TmdbApi,
    private val tmdbSettingsDataStore: com.nuvio.tv.data.local.TmdbSettingsDataStore,
    private val titleArtService: com.nuvio.tv.core.tmdb.TmdbTitleArtService,
    private val bananaBallLive: BananaBallLive
) : ViewModel() {

    /** Title art for the Sports Talk cards (the show's name on its backdrop). */
    val titleArt: com.nuvio.tv.domain.repository.TitleArtResolver get() = titleArtService

    /** When the games were last loaded. */
    @Volatile private var loadedAtMs = 0L

    private val _uiState = MutableStateFlow(SportsUiState())
    val uiState: StateFlow<SportsUiState> = _uiState.asStateFlow()

    init {
        // Back on Sports: the games it last loaded show at once (they load again quietly).
        lastLoaded?.let { (loaded, at) ->
            applyLoaded(loaded)
            loadedAtMs = at
        }
        viewModelScope.launch {
            combine(preferences.favoriteSports, preferences.favoriteTeamIds) { sports, teamIds -> sports to teamIds }
                .collect { (sports, teamIds) ->
                    _uiState.update { it.copy(favoriteSportNames = sports) }
                    refresh(favoriteTeamIds = teamIds, quiet = _uiState.value.leagues.isNotEmpty())
                }
        }
        viewModelScope.launch {
            sportsLibraryStore.leagues.collect { leagues ->
                _uiState.update { state -> state.copy(favoriteLeagues = leagues.map { it.league }) }
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
        loadAddonEvents()
        loadSportsTalk()
    }

    // ------------------------------------------------------------------ Sports Talk

    private var talkPage = 0
    private var talkTotalPages = 1
    private var talkJob: kotlinx.coroutines.Job? = null

    /** The most popular sports talk shows first, then TMDB's sports talk shows by popularity. */
    private fun loadSportsTalk() {
        talkJob = viewModelScope.launch {
            val language = tmdbLanguage()
            val curated = withContext(Dispatchers.IO) {
                kotlinx.coroutines.coroutineScope {
                    POPULAR_SPORTS_TALK.map { name ->
                        async {
                            runCatching {
                                tmdbApi.searchTv(TMDB_API_KEY, name, language).body()?.results.orEmpty()
                                    .firstOrNull { it.backdropPath != null && sameTitle(it.name ?: it.originalName, name) }
                            }.getOrNull()
                        }
                    }.mapNotNull { it.await() }
                }
            }
            _uiState.update { state ->
                state.copy(sportsTalkShows = (curated.mapNotNull { it.toShow() } + state.sportsTalkShows).distinctBy { it.id })
            }
            loadMoreSportsTalkNow()
        }
    }

    /** The next page of sports talk shows (See All, as it scrolls). */
    fun loadMoreSportsTalk() {
        if (talkJob?.isActive == true || !uiState.value.hasMoreTalk) return
        talkJob = viewModelScope.launch { loadMoreSportsTalkNow() }
    }

    private suspend fun loadMoreSportsTalkNow() {
        if (talkPage >= talkTotalPages) {
            _uiState.update { it.copy(hasMoreTalk = false) }
            return
        }
        _uiState.update { it.copy(isLoadingMoreTalk = true) }
        val language = tmdbLanguage()
        val response = withContext(Dispatchers.IO) {
            runCatching {
                tmdbApi.discoverTv(
                    apiKey = TMDB_API_KEY,
                    language = language,
                    page = talkPage + 1,
                    sortBy = "popularity.desc",
                    withGenres = TMDB_TALK_GENRE,
                    withKeywords = TMDB_SPORTS_TALK_KEYWORDS,
                    withOriginalLanguage = "en"
                ).body()
            }.getOrNull()
        }
        if (response != null) {
            talkPage = response.page ?: (talkPage + 1)
            talkTotalPages = response.totalPages ?: talkPage
        }
        val shows = response?.results.orEmpty().filter { it.backdropPath != null }.mapNotNull { it.toShow() }
        _uiState.update { state ->
            state.copy(
                sportsTalkShows = (state.sportsTalkShows + shows).distinctBy { it.id },
                isLoadingMoreTalk = false,
                hasMoreTalk = response != null && talkPage < talkTotalPages
            )
        }
    }

    private suspend fun tmdbLanguage(): String =
        runCatching { tmdbSettingsDataStore.settings.first().language }.getOrNull()?.ifBlank { null } ?: "en"

    private fun com.nuvio.tv.data.remote.api.TmdbDiscoverResult.toShow(): com.nuvio.tv.domain.model.MetaPreview? {
        val title = (name ?: originalName)?.takeIf { it.isNotBlank() } ?: return null
        val backdrop = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" }
        return com.nuvio.tv.domain.model.MetaPreview(
            id = "tmdb:$id",
            type = com.nuvio.tv.domain.model.ContentType.SERIES,
            name = title,
            poster = backdrop,
            posterShape = PosterShape.LANDSCAPE,
            background = backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" },
            logo = null,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = firstAirDate?.take(4),
            imdbRating = voteAverage?.toFloat()?.takeIf { it > 0f },
            genres = emptyList(),
            rawPosterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
        )
    }

    private fun sameTitle(a: String?, b: String): Boolean {
        // RCTV (MEM-07): letters and digits kept without a regular expression.
        fun norm(value: String) = value.lowercase(java.util.Locale.US).filter { it in 'a'..'z' || it in '0'..'9' }
        return a != null && norm(a) == norm(b)
    }

    /** Bookmarks or un-bookmarks a match to the Library. Local-only (see [SPORTS_EVENT_LIBRARY_TYPE]) - never touches Trakt/Simkl. */
    fun toggleLibraryEvent(event: SportsEvent) {
        viewModelScope.launch {
            if (event.id in uiState.value.libraryEventIds) {
                libraryPreferences.removeItem(itemId = event.id, itemType = SPORTS_EVENT_LIBRARY_TYPE)
            } else {
                libraryPreferences.addItem(item = event.toSavedLibraryItem())
            }
        }
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

    /** A team in or out of "My Teams" (and the Library); going in, its name and badge are kept. */
    fun toggleFavoriteTeam(teamId: String) {
        val state = uiState.value
        val team = (state.favoriteTeams + state.teams + state.browsableTeams).firstOrNull { it.id == teamId }
        viewModelScope.launch {
            if (team != null && teamId !in preferences.favoriteTeamIds.value) sportsLibraryStore.rememberTeams(listOf(team))
            preferences.toggleFavoriteTeam(teamId)
        }
    }

    /** A league in or out of "My Sports" (and the Library). */
    fun toggleFavoriteLeague(league: SportsLeague) {
        viewModelScope.launch { sportsLibraryStore.toggleLeague(league) }
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


    /**
     * While the page shows (call it from the page): every half minute, games whose start time has
     * come go live ("Up Next" → "Live Now"); every few minutes, while games are on or about to
     * start, the scores and states load again, quietly.
     */
    suspend fun keepCurrent() {
        while (true) {
            // Banana Ball's games on now (on TV, or streaming on YouTube): its schedule can't say.
            runCatching { _uiState.value.let { bananaBallLive.refresh(it.upcomingGames + it.liveNow) } }
            val now = System.currentTimeMillis()
            applyClock(now)
            val state = _uiState.value
            val gamesOn = state.liveNow.isNotEmpty() ||
                state.upcomingGames.any { game -> (game.startTimeMs ?: Long.MAX_VALUE) < now + QUIET_REFRESH_MS }
            if (!state.isLoading && gamesOn && loadedAtMs > 0 && now - loadedAtMs >= QUIET_REFRESH_MS) {
                refresh(favoriteTeamIds = preferences.favoriteTeamIds.value, quiet = true)
            }
            kotlinx.coroutines.delay(SPORTS_CLOCK_TICK_MS)
        }
    }

    /** Upcoming games whose start time has come: live now. */
    private fun applyClock(now: Long) {
        _uiState.update { state ->
            val started = state.upcomingGames.filter { it.isLiveAt(now) }
            if (started.isEmpty()) return@update state
            val startedIds = started.map { it.id }.toSet()
            val liveNow = (state.liveNow + started.map { it.withClock(now) })
                .distinctBy { it.id }
                .sortedBy { it.startTimeMs ?: Long.MAX_VALUE }
            val upcoming = state.upcomingGames.filterNot { it.id in startedIds }
            state.copy(
                liveNow = liveNow,
                upcomingGames = upcoming,
                pickedForYou = state.pickedForYou.map { it.withClock(now) },
                featuredEvent = liveNow.firstOrNull() ?: upcoming.firstOrNull(),
                // The banner shows games (two teams' logos): not golf tournaments.
                heroEvents = liveNow.filterNot { it.isGolf() }.take(5).ifEmpty { upcoming.filterNot { it.isGolf() }.take(5) }
            )
        }
    }

    private suspend fun refresh(favoriteTeamIds: Set<String>, quiet: Boolean = false) {
        if (!quiet) _uiState.update { it.copy(isLoading = true, error = null) }
        // The leagues come in a few at a time (the person's first): the page fills in as they do,
        // instead of "Loading Sports" until the last one is in.
        val arrived = java.util.concurrent.ConcurrentLinkedQueue<EspnSportsClient.FeedResult>()
        val progress = if (quiet) null else viewModelScope.launch {
            var shown = 0
            while (true) {
                kotlinx.coroutines.delay(PROGRESS_TICK_MS)
                val results = arrived.toList()
                if (results.size == shown || results.none { it.events.isNotEmpty() }) continue
                shown = results.size
                val partial = withContext(Dispatchers.Default) {
                    runCatching { aggregate(results, favoriteTeamIds, homeTeamRowPicks = emptyList()) }.getOrNull()
                } ?: continue
                applyLoaded(partial, final = false)
            }
        }
        // RCTV (RCTV-AUD-006/016): leaving the page cancels the load; that isn't a failed load.
        val result = try {
            withContext(Dispatchers.IO) {
                runCatchingCancellable { loadAll(favoriteTeamIds) { arrived += it } }
            }
        } finally {
            progress?.cancel()
        }
        // A quiet reload that fails keeps what the page shows.
        if (quiet && result.isFailure) return
        result.onSuccess { loaded ->
            loadedAtMs = System.currentTimeMillis()
            lastLoaded = loaded to loadedAtMs
            // The Library shows "My Teams" and "My Sports" with these names and badges.
            runCatching {
                sportsLibraryStore.rememberTeams(loaded.favoriteTeams)
                sportsLibraryStore.refreshLeagues(loaded.leagues)
            }
            applyLoaded(loaded)
        }.onFailure { throwable ->
            _uiState.update { it.copy(isLoading = false, error = throwable.message ?: "error") }
        }
    }

    /** Shows loaded games and leagues on the page ([final]: every league is in). */
    private fun applyLoaded(loaded: LoadedData, final: Boolean = true) {
        _uiState.update {
            it.copy(
                isLoading = if (final) false else it.isLoading,
                error = null,
                featuredEvent = loaded.liveNow.firstOrNull() ?: loaded.upcomingGames.firstOrNull(),
                heroEvents = loaded.liveNow.filterNot { it.isGolf() }.take(5)
                    .ifEmpty { loaded.upcomingGames.filterNot { it.isGolf() }.take(5) },
                liveNow = loaded.liveNow,
                upcomingGames = loaded.upcomingGames,
                pickedForYou = loaded.pickedForYou,
                leagues = loaded.leagues,
                // The marquee teams first, then every other team of the leagues followed.
                teams = (loaded.homeTeamRowPicks + loaded.teams).distinctBy { team -> team.id },
                browsableTeams = loaded.teams,
                sportsTalk = mockSportsTalk(),
                continueWatching = emptyList(),
                favoriteTeams = loaded.favoriteTeams
            )
        }
    }

    private data class LoadedData(
        val liveNow: List<SportsEvent>,
        val upcomingGames: List<SportsEvent>,
        val pickedForYou: List<SportsEvent>,
        val leagues: List<SportsLeague>,
        val teams: List<SportsTeam>,
        val homeTeamRowPicks: List<SportsTeam>,
        val favoriteTeams: List<SportsTeam>
    )

    /** Fetches every curated ESPN feed and aggregates events/teams/leagues across all of them (each feed already carries its own league + team metadata, so no separate lookups are needed). */
    private suspend fun loadAll(
        favoriteTeamIds: Set<String>,
        onResult: (EspnSportsClient.FeedResult) -> Unit = {}
    ): LoadedData = kotlinx.coroutines.coroutineScope {
        // The person's leagues load first (theirs and their teams').
        val state = _uiState.value
        val firstLeagues = (state.favoriteLeagues.map { it.id } + state.favoriteTeams.mapNotNull { it.leagueId }).toSet()
        // The marquee teams and the leagues' games, meanwhile.
        val picks = async(Dispatchers.IO) { runCatchingCancellable { client.fetchHomeTeamRowPicks() }.getOrDefault(emptyList()) }
        val results = withContext(Dispatchers.IO) {
            client.fetchAllFixtures(rangeDays = ROWS_DAYS, firstLeagueIds = firstLeagues, onResult = onResult)
        }
        aggregate(results, favoriteTeamIds, picks.await())
    }

    /** The rows from the leagues' games: Live Now, Up Next, Picked for You, the leagues and the teams. */
    private fun aggregate(
        results: List<EspnSportsClient.FeedResult>,
        favoriteTeamIds: Set<String>,
        homeTeamRowPicks: List<SportsTeam>
    ): LoadedData {
        val leagues = results.map { it.league }.distinctBy { it.id }.sortedBy { league -> leagueRank(league.id) }
        // As the clock has them: games whose start time has come are live.
        val now = System.currentTimeMillis()
        val allEvents = results.flatMap { it.events }.map { it.withClock(now) }.distinctBy { it.id }
        val allTeams = results.flatMap { it.teams }.distinctBy { it.id }

        val liveNow = allEvents.filter { it.status == SportsEventStatus.LIVE }
            .distinctBy { it.id }
            .sortedBy { it.startTimeMs ?: Long.MAX_VALUE }
            .fewPerTour()
        val upcomingGames = allEvents.filter { it.status == SportsEventStatus.UPCOMING }
            .distinctBy { it.id }
            .sortedBy { it.startTimeMs ?: Long.MAX_VALUE }
            .fewPerTour()
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
            homeTeamRowPicks = homeTeamRowPicks,
            favoriteTeams = favoriteTeams
        )
    }

    private companion object {
        /** The games and leagues Sports last loaded (and when), kept while the app runs. */
        @Volatile var lastLoaded: Pair<LoadedData, Long>? = null

        /** How often the games load again while some are on (scores, ends, new games live). */
        const val QUIET_REFRESH_MS = 2L * 60 * 1000
        /** The days of games the rows look at (Live Now, Up Next, a team's next game). */
        const val ROWS_DAYS = 6
        /** While the leagues come in, how often the page shows the ones in so far. */
        const val PROGRESS_TICK_MS = 600L
        val TMDB_API_KEY: String get() = com.nuvio.tv.BuildConfig.TMDB_API_KEY
        /** TMDB's Talk genre (TV). */
        const val TMDB_TALK_GENRE = "10767"
        /** TMDB keywords "sports talk" or "sports". */
        const val TMDB_SPORTS_TALK_KEYWORDS = "284973|6075"
        /** The most watched sports talk shows, first in the row. */
        val POPULAR_SPORTS_TALK = listOf(
            "First Take", "Inside the NBA", "Pardon the Interruption", "The Pat McAfee Show", "Undisputed",
            "Get Up", "The Herd with Colin Cowherd", "Around the Horn", "NFL Live", "Real Sports with Bryant Gumbel"
        )

        /** The order of the leagues (ESPN's league path, the part after ":" in the id): the US leagues first, then soccer, then the rest. */
        val LEAGUE_ORDER = listOf(
            "nfl", "nba", "wnba", "mlb", "nhl", "college-football", "mens-college-basketball", "college-baseball", "banana-ball",
            "lidom", "caribbean-series", "world-baseball-classic", "milb-aaa", "milb-aa", "milb-high-a", "milb-a",
            "ufl", "cfl",
            "usa.1", "eng.1", "esp.1", "uefa.champions", "uefa.europa", "ita.1", "ger.1", "fra.1", "mex.1",
            "conmebol.libertadores", "conmebol.sudamericana", "concacaf.champions", "fifa.world", "fifa.cwc",
            "uefa.nations", "conmebol.america",
            "ufc", "ppv-events", "boxing-wbc", "boxing-wba", "boxing-ibf", "boxing-wbo", "boxing-top-rank", "boxing-pbc",
            "boxing-matchroom",
            "f1", "nascar-premier", "nascar-secondary", "nascar-truck", "irl", "motogp",
            "atp", "wta", "australian-open", "roland-garros", "wimbledon", "us-open",
            "pga", "masters", "pga-championship", "golf-us-open", "the-open", "champions-tour", "lpga", "eur", "ntw", "liv"
        )

        /** Tennis plays dozens of matches a day: a few of each tour's in the rows, the rest on its page. */
        private const val MATCHES_PER_TOUR = 6

        private fun List<SportsEvent>.fewPerTour(): List<SportsEvent> {
            val counts = HashMap<String, Int>()
            return filter { event ->
                val league = event.leagueId?.takeIf { it.startsWith("tennis:") } ?: return@filter true
                val count = (counts[league] ?: 0) + 1
                counts[league] = count
                count <= MATCHES_PER_TOUR
            }
        }

        /** Where a league goes in [LEAGUE_ORDER]; leagues not in it go last, in the order they came. */
        fun leagueRank(leagueId: String): Int {
            val index = LEAGUE_ORDER.indexOf(leagueId.substringAfter(':'))
            return if (index >= 0) index else LEAGUE_ORDER.size
        }
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
