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
import com.nuvio.tv.domain.model.SportsLeague
import com.nuvio.tv.domain.model.SportsNewsArticle
import com.nuvio.tv.domain.model.SportsStandingsGroup
import com.nuvio.tv.domain.model.SportsTeam
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Which section of a league's page is showing (RCTV, owner's menu: Home, Games, Teams, Standings,
 * News, Highlights; the schedule is Games' "By week").
 */
enum class LeagueDetailTab { HOME, GAMES, TEAMS, STANDINGS, NEWS, HIGHLIGHTS }

/** RCTV: the Games tab's filters. */
enum class LeagueGamesFilter { LIVE, UPCOMING, COMPLETED, BY_WEEK }

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

    /** RCTV: the Games tab's filter (null: Live when games are on, else Upcoming). */
    val gamesFilter: LeagueGamesFilter? = null,
    /** RCTV: the games played lately, the latest first (Games' Completed). */
    val completedEvents: List<SportsEvent> = emptyList(),

    // Schedule by week (Home's schedule row and Games' By week).
    val scheduleWeekOffset: Int = 0,
    /** A football league's week and season part (1 pre, 2 regular, 3 post), as ESPN numbers them. */
    val scheduleWeekNumber: Int? = null,
    val scheduleSeasonType: Int? = null,
    /** The week's first and last day shown (leagues not played by weeks). */
    val scheduleRangeStartMs: Long? = null,
    val scheduleEvents: List<SportsEvent> = emptyList(),
    val isLoadingSchedule: Boolean = false,
    val scheduleLoaded: Boolean = false,

    // RCTV (owner's rows): the league's channels and game replays in the IPTV lineup, and what's on them.
    val channels: List<LeagueChannelItem> = emptyList(),
    val replays: List<LeagueChannelItem> = emptyList(),
    val channelPrograms: Map<String, String> = emptyMap(),

    // RCTV: the league's latest official highlights (YouTube).
    val highlights: List<com.nuvio.tv.data.trailer.YouTubeSearchResult> = emptyList(),
    val isLoadingHighlights: Boolean = false,
    val highlightsLoaded: Boolean = false,

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
    private val guideLoader: com.nuvio.tv.ui.screens.livetv.LiveTvGuideLoader,
    private val liveTvDataStore: com.nuvio.tv.data.local.LiveTvDataStore,
    private val youTubeSearch: com.nuvio.tv.data.trailer.YouTubeVideoSearch,
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
        watchLineup()
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
                // Between seasons (or a tournament's editions): its next games, else its last ones.
                runCatching { client.fetchLeagueHome(currentFeed) }
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
                val completedEvents = feedResult.events.filter { it.status == SportsEventStatus.FINISHED }
                    .sortedByDescending { it.startTimeMs ?: Long.MIN_VALUE }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = null,
                        league = league,
                        featuredEvent = liveEvents.firstOrNull() ?: upcomingEvents.firstOrNull(),
                        liveEvents = liveEvents,
                        upcomingEvents = upcomingEvents,
                        completedEvents = completedEvents,
                        allEvents = feedResult.events.sortedBy { event -> event.startTimeMs ?: Long.MAX_VALUE }
                    )
                }
                // RCTV (owner's rows): Home shows the week's schedule and the league's highlights too.
                loadScheduleIfNeeded()
                loadHighlightsIfNeeded()
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
            LeagueDetailTab.GAMES -> loadScheduleIfNeeded()
            LeagueDetailTab.NEWS -> loadNewsIfNeeded()
            LeagueDetailTab.HIGHLIGHTS -> loadHighlightsIfNeeded()
            else -> Unit
        }
    }

    /** RCTV: the Games tab's filter. */
    fun selectGamesFilter(filter: LeagueGamesFilter) {
        _uiState.update { it.copy(gamesFilter = filter) }
        if (filter == LeagueGamesFilter.BY_WEEK) loadScheduleIfNeeded()
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
        // RCTV: football is played by weeks ("Week 3"): ESPN's current week.
        val currentFeed = feed
        if (currentFeed != null && client.isWeekly(currentFeed)) {
            loadFootballWeek(week = null, seasonType = null)
            return
        }
        var weekOffset = uiState.value.scheduleWeekOffset
        // Between seasons: the schedule opens on the week its next game is in (else its last game's).
        if (weekOffset == 0) {
            val now = System.currentTimeMillis()
            val starts = uiState.value.allEvents.mapNotNull { it.startTimeMs }
            val nextGame = starts.filter { it > now }.minOrNull()
            val lastGame = starts.filter { it <= now }.maxOrNull()
            val target = when {
                nextGame != null && nextGame - now >= WEEK_MS -> nextGame
                nextGame == null && lastGame != null && now - lastGame >= WEEK_MS -> lastGame
                else -> null
            }
            if (target != null) weekOffset = Math.floorDiv(target - now, WEEK_MS).toInt()
        }
        loadScheduleWeek(weekOffset)
    }

    /** Moves the Schedule tab by [delta] weeks (e.g. -1/+1 for the mockup's "< WEEK >" control) and reloads. */
    fun changeScheduleWeek(delta: Int) {
        val state = uiState.value
        val currentFeed = feed
        val week = state.scheduleWeekNumber
        if (currentFeed != null && client.isWeekly(currentFeed) && week != null) {
            val (nextWeek, nextType) = FootballWeeks.step(currentFeed.leaguePath, week, state.scheduleSeasonType ?: 2, delta)
            if (nextWeek != week || nextType != state.scheduleSeasonType) loadFootballWeek(nextWeek, nextType)
            return
        }
        loadScheduleWeek(state.scheduleWeekOffset + delta)
    }

    private var scheduleJob: kotlinx.coroutines.Job? = null

    /** A football league's [week] of [seasonType] (null: the current one), as ESPN numbers them. */
    private fun loadFootballWeek(week: Int?, seasonType: Int?) {
        val currentFeed = feed ?: return
        scheduleJob?.cancel()
        _uiState.update { it.copy(isLoadingSchedule = true, scheduleWeekNumber = week ?: it.scheduleWeekNumber, scheduleSeasonType = seasonType ?: it.scheduleSeasonType) }
        scheduleJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.fetchFeedWeek(currentFeed, week, seasonType) }.getOrNull()
            }
            val now = System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    isLoadingSchedule = false,
                    scheduleLoaded = true,
                    scheduleWeekNumber = result?.weekNumber ?: week ?: it.scheduleWeekNumber,
                    scheduleSeasonType = result?.seasonType ?: seasonType ?: it.scheduleSeasonType,
                    scheduleEvents = result?.events.orEmpty()
                        .map { event -> event.withClock(now) }
                        .sortedBy { event -> event.startTimeMs ?: Long.MAX_VALUE }
                )
            }
        }
    }

    private fun loadScheduleWeek(weekOffset: Int) {
        val currentFeed = feed ?: return
        scheduleJob?.cancel()
        _uiState.update {
            it.copy(
                isLoadingSchedule = true,
                scheduleWeekOffset = weekOffset,
                scheduleRangeStartMs = System.currentTimeMillis() + weekOffset * WEEK_MS
            )
        }
        scheduleJob = viewModelScope.launch {
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

    // ------------------------------------------------------------------ RCTV: channels and replays

    /** The IPTV sources the lineup came from (to play a channel). */
    private var lineupSources: List<com.nuvio.tv.domain.model.LiveTvSource> = emptyList()
    private var lineupPrograms: Map<String, List<com.nuvio.tv.domain.model.EpgProgram>> = emptyMap()

    /**
     * The league's official channels and replay channels in the IPTV lineup (none: no IPTV, or the
     * league has none). The lineup is the Guide's own (loaded once for the whole app).
     */
    private fun watchLineup() {
        if (!LeagueChannels.hasOfficialChannels(leagueId) && !LeagueChannels.hasReplays(leagueId)) return
        viewModelScope.launch {
            val sources = runCatching { liveTvDataStore.sources.first() }.getOrDefault(emptyList()).filter { it.enabled }
            if (sources.isEmpty()) return@launch
            lineupSources = sources
            guideLoader.ensure(sources)
            guideLoader.guide
                .filterNotNull()
                .filter { !it.isPreview && it.channels.isNotEmpty() }
                .distinctUntilChanged { old, new -> old.channels === new.channels && old.epgByChannel === new.epgByChannel }
                .collectLatest { guide ->
                    val (official, replays) = withContext(Dispatchers.Default) {
                        LeagueChannels.official(leagueId, guide.allChannels) to LeagueChannels.replays(leagueId, guide.allChannels)
                    }
                    lineupPrograms = guide.epgByChannel
                    _uiState.update {
                        it.copy(
                            channels = official,
                            replays = replays,
                            channelPrograms = programsNow(official + replays, System.currentTimeMillis())
                        )
                    }
                }
        }
    }

    /** What's on each channel now (by channel id). */
    private fun programsNow(items: List<LeagueChannelItem>, now: Long): Map<String, String> =
        items.mapNotNull { item ->
            val channel = item.channel
            lineupPrograms[channel.epgChannelId ?: channel.id]
                ?.firstOrNull { it.isAiringAt(now) }
                ?.title
                ?.let { channel.id to it }
        }.toMap()

    /** [item]'s channel, ready to play; null when its address can't be had. */
    suspend fun playable(item: LeagueChannelItem): com.nuvio.tv.domain.model.LiveTvChannel? {
        val sources = lineupSources.ifEmpty { return null }
        return try {
            val url = guideLoader.resolvePlaybackUrl(item.channel, sources)
            item.channel.copy(streamUrl = url).takeIf { url.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ RCTV: highlights

    /**
     * The league's latest official highlights: its own YouTube channel's newest (NFL, NBA, LaLiga...),
     * else a YouTube search kept to official channels. Never Shorts (16:9 only).
     */
    private fun loadHighlightsIfNeeded() {
        val state = uiState.value
        if (state.highlightsLoaded || state.isLoadingHighlights) return
        _uiState.update { it.copy(isLoadingHighlights = true) }
        viewModelScope.launch {
            val channel = OfficialRecapChannels.ofLeague(leagueId)
            val leagueName = uiState.value.league?.name?.takeIf { it.isNotBlank() } ?: leagueNameArg
            val spanish = channel?.spanish ?: (uiState.value.allEvents.firstOrNull()?.let(SportsRecapPicker::isSpanish) == true)
            val results = try {
                if (channel != null) {
                    youTubeSearch.latestOfChannel(channel.handle)
                } else {
                    youTubeSearch.search("$leagueName ${if (spanish) "resumen" else "highlights"}", spanish = spanish)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            val picked = SportsRecapPicker.leagueHighlights(results, channel)
            // RCTV (owner's rule): 16:9 only: YouTube is asked whether each is a Short.
            val horizontal = coroutineScope {
                picked.map { video ->
                    async { video to runCatching { youTubeSearch.isShort(video.videoId) }.getOrNull() }
                }.awaitAll()
            }.filter { (_, short) -> short != true }.map { it.first }
            _uiState.update { it.copy(highlights = horizontal, isLoadingHighlights = false, highlightsLoaded = true) }
        }
    }

    /** While the page shows (call it from the page): games whose start time has come go live. */
    suspend fun keepCurrent() {
        while (true) {
            kotlinx.coroutines.delay(SPORTS_CLOCK_TICK_MS)
            // Banana Ball's games on now (on TV, or streaming on YouTube).
            runCatching { _uiState.value.let { bananaBallLive.refresh(it.upcomingEvents + it.scheduleEvents + it.liveEvents) } }
            val now = System.currentTimeMillis()
            // RCTV: what's on the league's channels now.
            _uiState.value.let { state ->
                if (state.channels.isNotEmpty() || state.replays.isNotEmpty()) {
                    val programs = programsNow(state.channels + state.replays, now)
                    if (programs != state.channelPrograms) _uiState.update { it.copy(channelPrograms = programs) }
                }
            }
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
    /** "My Teams" by team and league ([SportsTeamKey]): [teamKey] is the team with its league. */
    fun toggleFavoriteTeam(teamKey: String) {
        val state = uiState.value
        val teamId = SportsTeamKey.teamIdOf(teamKey)
        val team = (state.teams + state.standings.flatMap { group -> group.entries.map { it.team } }).firstOrNull { SportsTeamKey.of(it) == teamKey }
            ?: (state.allEvents + state.scheduleEvents).firstNotNullOfOrNull { event ->
                event.teamOf(teamId)?.takeIf { SportsTeamKey.of(it) == teamKey }
            }
        viewModelScope.launch {
            val saved = preferences.favoriteTeamIds.value
            val isFavorite = teamKey in saved || teamId in saved
            if (team != null && !isFavorite) sportsLibraryStore.rememberTeams(listOf(team))
            preferences.toggleFavoriteTeam(teamKey)
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
