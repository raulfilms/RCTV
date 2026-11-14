package com.nuvio.tv.ui.screens.customize

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.rctvcontrol.RctvCustomizeStore
import com.nuvio.tv.data.local.LiveTvDataStore
import com.nuvio.tv.data.local.SportsLibraryStore
import com.nuvio.tv.data.local.SportsPreferencesDataStore
import com.nuvio.tv.data.remote.espn.EspnSportsClient
import com.nuvio.tv.domain.model.CustomLiveChannel
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.LiveTvSource
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLeague
import com.nuvio.tv.domain.model.SportsTeam
import com.nuvio.tv.domain.model.SportsTeamKey
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.ui.screens.livetv.GuideBrowse
import com.nuvio.tv.ui.screens.livetv.LiveGuideSnapshot
import com.nuvio.tv.ui.screens.livetv.LiveTvGuideLoader
import com.nuvio.tv.ui.screens.livetv.LiveTvViewModel
import com.nuvio.tv.ui.screens.search.SportsSearchIndex
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import javax.inject.Inject

/** The pages of "Customize your experience", in order (Teams only with a league that has teams; Networks only with Live TV). */
enum class CustomizeStep { LEAGUES, TEAMS, NETWORKS, SERIES }

/** A league to pick: its short name ("NBA"), its sport's emoji until its logo is known. */
data class CustomizeLeague(
    val id: String,
    val name: String,
    val sportName: String,
    val emoji: String,
    val badgeUrl: String?,
    val hasTeams: Boolean
) {
    fun toLeague(): SportsLeague = SportsLeague(id = id, name = name, sportName = sportName, badgeUrl = badgeUrl)
}

/** A Live TV channel to pick, under its Guide category. */
data class CustomizeNetwork(
    val id: String,
    val name: String,
    val logoUrl: String?,
    val category: GuideBrowse,
    /** Its place in the Guide (Home's Top Channels follow the Guide's order). */
    val position: Int
)

/** A kind of series to browse: [genre] as Cinemeta names it, null for the most popular. */
data class SeriesGenre(val genre: String?, @StringRes val labelRes: Int)

data class CustomizeUiState(
    val step: CustomizeStep = CustomizeStep.LEAGUES,
    /** The account has Live TV: the networks page is there. */
    val withNetworks: Boolean = true,
    val leagues: List<CustomizeLeague> = emptyList(),
    val selectedLeagueIds: List<String> = emptyList(),
    val teamLeagueId: String? = null,
    val teamQuery: String = "",
    /** The teams shown: the league's, matching what's typed. */
    val teams: List<SportsTeam> = emptyList(),
    val teamsLoading: Boolean = true,
    val selectedTeamKeys: List<String> = emptyList(),
    /** The chosen teams known by name and badge (for the chips), in the order they were chosen. */
    val selectedTeams: List<SportsTeam> = emptyList(),
    val networkCategories: List<GuideBrowse> = emptyList(),
    /** The category shown; null: all of them. */
    val networkCategory: GuideBrowse? = null,
    val networkQuery: String = "",
    val networks: List<CustomizeNetwork> = emptyList(),
    val networksLoading: Boolean = true,
    val selectedNetworkIds: List<String> = emptyList(),
    val selectedNetworks: List<CustomizeNetwork> = emptyList(),
    val seriesGenre: String? = null,
    val seriesQuery: String = "",
    val series: List<MetaPreview> = emptyList(),
    val seriesLoading: Boolean = true,
    val selectedSeries: List<MetaPreview> = emptyList(),
    val finishing: Boolean = false
) {
    /** The chosen leagues that have teams, in the order chosen (the Teams page's list). */
    val teamLeagues: List<CustomizeLeague>
        get() = selectedLeagueIds.mapNotNull { id -> leagues.firstOrNull { it.id == id && it.hasTeams } }

    /** The pages this profile goes through. */
    val steps: List<CustomizeStep>
        get() = buildList {
            add(CustomizeStep.LEAGUES)
            if (teamLeagues.isNotEmpty()) add(CustomizeStep.TEAMS)
            if (withNetworks) add(CustomizeStep.NETWORKS)
            add(CustomizeStep.SERIES)
        }

    val isFirstStep: Boolean get() = step == steps.first()
    val isLastStep: Boolean get() = step == steps.last()
}

/**
 * RCTV (owner's design), "Customize your experience": after a profile opens for the first time on
 * the TV (Full plan), it picks its leagues, teams, TV networks and series. What it picks goes where
 * the app already keeps it: the leagues in "My Sports", the teams in "My Teams" (both in the
 * Library too), the networks in the Guide's Custom list (Home's Top Channels) and the series in
 * the Library. Only what's changed here is saved: anything picked before stays.
 */
@HiltViewModel
class RctvCustomizeViewModel @Inject constructor(
    private val sportsIndex: SportsSearchIndex,
    private val sportsPreferences: SportsPreferencesDataStore,
    private val sportsLibrary: SportsLibraryStore,
    private val liveTvDataStore: LiveTvDataStore,
    private val guideLoader: LiveTvGuideLoader,
    private val catalogRepository: CatalogRepository,
    private val libraryRepository: LibraryRepository,
    private val store: RctvCustomizeStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(CustomizeUiState())
    val uiState: StateFlow<CustomizeUiState> = _uiState.asStateFlow()

    private var profileId: Int? = null
    private val jobs = mutableListOf<Job>()
    private var seriesJob: Job? = null

    /** What the profile had when it opened: only what's changed here is saved. */
    private var initialLeagueIds: Set<String> = emptySet()
    private var initialTeamKeys: Set<String> = emptySet()
    private var initialNetworkIds: Set<String> = emptySet()

    /** Every team Sports knows (the shown ones are the league's) and every channel to pick. */
    private var allTeams: List<SportsTeam> = emptyList()
    private var allNetworks: List<CustomizeNetwork> = emptyList()
    /** Chosen teams and channels by key, for their chips (kept even when another league or category shows). */
    private val knownTeams = LinkedHashMap<String, SportsTeam>()
    private val knownNetworks = LinkedHashMap<String, CustomizeNetwork>()

    /** Starts over for [profileId] (the profile now open); nothing when it's already showing for it. */
    fun start(profileId: Int) {
        if (this.profileId == profileId && jobs.any { it.isActive }) return
        stop()
        this.profileId = profileId

        val savedLeagueIds = sportsLibrary.leagues.value.map { it.league.id }.distinct()
        initialLeagueIds = savedLeagueIds.toSet()
        val savedTeamKeys = sportsPreferences.favoriteTeamIds.value.filterNot(SportsTeamKey::isLegacy)
        initialTeamKeys = savedTeamKeys.toSet()
        sportsLibrary.teams.value.forEach { saved -> knownTeams[SportsTeamKey.of(saved.team)] = saved.team }
        val savedNetworkIds = liveTvDataStore.customChannelIds.value.toList()
        initialNetworkIds = savedNetworkIds.toSet()

        val leagues = leaguesOf(sportsIndex.state.value)
        _uiState.value = CustomizeUiState(
            leagues = leagues,
            selectedLeagueIds = savedLeagueIds,
            selectedTeamKeys = savedTeamKeys,
            selectedTeams = savedTeamKeys.mapNotNull { knownTeams[it] },
            selectedNetworkIds = savedNetworkIds
        )

        // The sports Search knows every league's logo and every team: loaded in the background.
        sportsIndex.ensure()
        jobs += viewModelScope.launch {
            sportsIndex.state.collect { snapshot ->
                allTeams = snapshot.teams.map { it.team }
                val withLogos = leaguesOf(snapshot)
                _uiState.update { it.copy(leagues = withLogos) }
                refreshTeams()
            }
        }
        // "Creating Your Experience" may have paused those loads as this page opened: again until they're in.
        jobs += viewModelScope.launch {
            while (allTeams.isEmpty()) {
                delay(TEAMS_CHECK_MS)
                sportsIndex.ensure()
                refreshTeams()
            }
        }
        // The Live TV channels (the account's IPTV): loaded meanwhile, for the networks page.
        jobs += viewModelScope.launch { watchNetworks() }
        // The most popular series, ready for their page.
        loadSeries()
    }

    // ------------------------------------------------------------------ Pages

    // By their order, not their place in the list: a page can drop out while it shows (no Live TV after all).
    fun next() {
        val state = _uiState.value
        val step = state.steps.firstOrNull { it.ordinal > state.step.ordinal } ?: return
        goTo(step)
    }

    fun back() {
        val state = _uiState.value
        val step = state.steps.lastOrNull { it.ordinal < state.step.ordinal } ?: return
        goTo(step)
    }

    private fun goTo(step: CustomizeStep) {
        _uiState.update { state ->
            val teamLeagueId = if (step == CustomizeStep.TEAMS) {
                state.teamLeagueId?.takeIf { id -> state.teamLeagues.any { it.id == id } }
                    ?: state.teamLeagues.firstOrNull()?.id
            } else {
                state.teamLeagueId
            }
            state.copy(step = step, teamLeagueId = teamLeagueId)
        }
        if (step == CustomizeStep.TEAMS) refreshTeams()
    }

    // ------------------------------------------------------------------ Leagues

    fun toggleLeague(leagueId: String) {
        _uiState.update { state ->
            val selected = if (leagueId in state.selectedLeagueIds) state.selectedLeagueIds - leagueId else state.selectedLeagueIds + leagueId
            state.copy(selectedLeagueIds = selected)
        }
    }

    private fun leaguesOf(snapshot: SportsSearchIndex.Snapshot): List<CustomizeLeague> {
        val known = snapshot.leagues.associateBy { it.league.id }
        val withTeams = EspnSportsClient.TEAMS_FILTERS.map { it.id }.toSet()
        return LEAGUE_ORDER.mapNotNull { path ->
            val feed = EspnSportsClient.FEEDS.firstOrNull { it.leaguePath == path } ?: return@mapNotNull null
            val id = "${feed.sportPath}:${feed.leaguePath}"
            val league = known[id]?.league
            CustomizeLeague(
                id = id,
                name = EspnSportsClient.feedLabel(path) ?: league?.name ?: path,
                sportName = feed.sportLabel,
                emoji = feed.emoji,
                badgeUrl = league?.badgeUrl,
                hasTeams = id in withTeams
            )
        }.distinctBy { it.id }
    }

    // ------------------------------------------------------------------ Teams

    fun selectTeamLeague(leagueId: String) {
        _uiState.update { it.copy(teamLeagueId = leagueId, teamQuery = "") }
        refreshTeams()
    }

    fun setTeamQuery(query: String) {
        _uiState.update { it.copy(teamQuery = query.take(MAX_QUERY)) }
        refreshTeams()
    }

    fun toggleTeam(team: SportsTeam) {
        val key = SportsTeamKey.of(team)
        knownTeams[key] = team
        _uiState.update { state ->
            val keys = if (key in state.selectedTeamKeys) state.selectedTeamKeys - key else state.selectedTeamKeys + key
            state.copy(selectedTeamKeys = keys, selectedTeams = keys.mapNotNull { knownTeams[it] })
        }
    }

    private fun refreshTeams() {
        val state = _uiState.value
        val leagueId = state.teamLeagueId
        val query = normalize(state.teamQuery)
        val teams = if (leagueId == null) {
            emptyList()
        } else {
            allTeams.asSequence()
                .filter { it.leagueId == leagueId }
                .filter { team ->
                    query.isEmpty() || normalize(team.name).contains(query) ||
                        team.shortName?.let { normalize(it).contains(query) } == true
                }
                .distinctBy { SportsTeamKey.of(it) }
                .sortedBy { it.name.lowercase() }
                .toList()
        }
        val loading = allTeams.isEmpty() && sportsIndex.isLoading
        _uiState.update { it.copy(teams = teams, teamsLoading = loading) }
    }

    // ------------------------------------------------------------------ Networks

    fun selectNetworkCategory(category: GuideBrowse?) {
        _uiState.update { it.copy(networkCategory = category, networkQuery = "") }
        refreshNetworks()
    }

    fun setNetworkQuery(query: String) {
        _uiState.update { it.copy(networkQuery = query.take(MAX_QUERY)) }
        refreshNetworks()
    }

    fun toggleNetwork(network: CustomizeNetwork) {
        knownNetworks[network.id] = network
        _uiState.update { state ->
            val ids = if (network.id in state.selectedNetworkIds) state.selectedNetworkIds - network.id else state.selectedNetworkIds + network.id
            state.copy(selectedNetworkIds = ids, selectedNetworks = ids.mapNotNull { knownNetworks[it] })
        }
    }

    private suspend fun watchNetworks() {
        val sources: List<LiveTvSource> = (
            liveTvDataStore.sources.value.takeIf { it.isNotEmpty() }
                // Just opened: the account's IPTV login may still be on its way from storage.
                ?: withTimeoutOrNull(sourcesWaitMs()) { liveTvDataStore.sources.first { it.isNotEmpty() } }
                ?: emptyList()
            ).filter { it.enabled }
        if (sources.isEmpty()) {
            _uiState.update { it.copy(withNetworks = false, networksLoading = false) }
            return
        }
        val key = guideLoader.keyOf(sources)
        guideLoader.ensure(sources)
        var builtFrom: LiveGuideSnapshot? = null
        guideLoader.guide.collect { guide ->
            if (guide == null || guide.key != key) return@collect
            if (guide.channels.isNotEmpty() && builtFrom?.channels !== guide.channels) {
                builtFrom = guide
                val networks = withContext(Dispatchers.Default) { networksOf(guide) }
                allNetworks = networks
                networks.forEach { network ->
                    if (network.id in _uiState.value.selectedNetworkIds) knownNetworks[network.id] = network
                }
                val categories = NETWORK_CATEGORIES.filter { category -> networks.any { it.category == category } }
                _uiState.update { state ->
                    state.copy(
                        networkCategories = categories,
                        networkCategory = state.networkCategory?.takeIf { it in categories },
                        selectedNetworks = state.selectedNetworkIds.mapNotNull { knownNetworks[it] }
                    )
                }
                refreshNetworks()
            }
            val loading = guide.channels.isEmpty() && guide.isLoading
            _uiState.update { it.copy(networksLoading = loading) }
        }
    }

    /**
     * The Guide's channels to pick from: the United States' and Puerto Rico's by category (not the
     * local stations, the 24/7 channels or the events), each name once, the best-known first.
     */
    private fun networksOf(guide: LiveGuideSnapshot): List<CustomizeNetwork> {
        val seen = HashSet<String>()
        val found = ArrayList<CustomizeNetwork>()
        for ((index, channel) in guide.channels.withIndex()) {
            val category = categoryOf(channel, guide) ?: continue
            val name = LiveTvViewModel.stripCountryTag(channel.name).trim()
            if (name.isEmpty() || !seen.add(normalize(name))) continue
            found += CustomizeNetwork(id = channel.id, name = name, logoUrl = channel.logoUrl, category = category, position = index)
            if (found.size >= MAX_NETWORKS) break
        }
        return found.sortedWith(
            compareBy<CustomizeNetwork> { popularRank(it.name) }.thenBy { it.name.lowercase() }
        )
    }

    private fun categoryOf(channel: LiveTvChannel, guide: LiveGuideSnapshot): GuideBrowse? {
        if (guide.curated) {
            val name = channel.guideCategory ?: return null
            return NETWORK_CATEGORIES.firstOrNull { it.name == name }
        }
        val categories = guide.channelCategories[channel.id] ?: return null
        return NETWORK_CATEGORIES.firstOrNull { it in categories }
    }

    private fun popularRank(name: String): Int {
        val index = POPULAR_NETWORKS.indexOf(normalize(name).replace(" ", ""))
        return if (index >= 0) index else POPULAR_NETWORKS.size
    }

    private fun refreshNetworks() {
        val state = _uiState.value
        val query = normalize(state.networkQuery)
        val shown = allNetworks.filter { network ->
            (state.networkCategory == null || network.category == state.networkCategory) &&
                (query.isEmpty() || normalize(network.name).contains(query))
        }
        _uiState.update { it.copy(networks = shown) }
    }

    // ------------------------------------------------------------------ Series

    fun selectSeriesGenre(genre: String?) {
        _uiState.update { it.copy(seriesGenre = genre, seriesQuery = "") }
        loadSeries()
    }

    fun setSeriesQuery(query: String) {
        _uiState.update { it.copy(seriesQuery = query.take(MAX_QUERY)) }
        loadSeries(typed = true)
    }

    fun toggleSeries(meta: MetaPreview) {
        _uiState.update { state ->
            val selected = if (state.selectedSeries.any { it.id == meta.id }) {
                state.selectedSeries.filterNot { it.id == meta.id }
            } else {
                state.selectedSeries + meta
            }
            state.copy(selectedSeries = selected)
        }
    }

    /** Cinemeta's most popular series: of the genre picked, or matching what's typed. */
    private fun loadSeries(typed: Boolean = false) {
        seriesJob?.cancel()
        _uiState.update { it.copy(seriesLoading = true) }
        seriesJob = viewModelScope.launch {
            // Typing: once it stops for a moment.
            if (typed) delay(SEARCH_WAIT_MS)
            val state = _uiState.value
            val query = state.seriesQuery.trim()
            val extra = when {
                query.isNotEmpty() -> mapOf("search" to query)
                state.seriesGenre != null -> mapOf("genre" to state.seriesGenre)
                else -> emptyMap()
            }
            val items = try {
                val result = catalogRepository.getCatalog(
                    addonBaseUrl = CINEMETA_URL,
                    addonId = CINEMETA_ID,
                    addonName = CINEMETA_NAME,
                    catalogId = SERIES_CATALOG_ID,
                    catalogName = CINEMETA_NAME,
                    type = SERIES_TYPE,
                    extraArgs = extra
                ).firstOrNull { it !is NetworkResult.Loading }
                when (result) {
                    is NetworkResult.Success -> result.data.items.filter { it.name.isNotBlank() }.take(MAX_SERIES)
                    else -> emptyList()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            _uiState.update { it.copy(series = items, seriesLoading = false) }
        }
    }

    // ------------------------------------------------------------------ Done

    /** Finish: saves what was picked, then [onDone] ("Creating Your Experience" comes next). */
    fun finish(onDone: () -> Unit) {
        val state = _uiState.value
        if (state.finishing) return
        _uiState.update { it.copy(finishing = true) }
        val id = profileId
        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    save(state)
                } catch (e: Exception) {
                    // What couldn't be saved can still be picked in Sports, the Guide and the Library.
                }
                if (id != null) store.markDone(id)
            }
            stop()
            onDone()
        }
    }

    /** Skip for now: nothing saved, not shown again on this TV. */
    fun skip(onDone: () -> Unit) {
        profileId?.let(store::markDone)
        stop()
        onDone()
    }

    private suspend fun save(state: CustomizeUiState) {
        // My Sports: the leagues picked or taken out here.
        val pickedLeagues = state.selectedLeagueIds.toSet()
        val savedLeagues = sportsLibrary.leagues.value.map { it.league.id }.toSet()
        state.leagues.forEach { league ->
            val wanted = league.id in pickedLeagues
            val changedHere = wanted != (league.id in initialLeagueIds)
            if (changedHere && wanted != (league.id in savedLeagues)) {
                runCatching { sportsLibrary.toggleLeague(league.toLeague()) }
            }
        }

        // My Teams.
        val pickedTeams = state.selectedTeamKeys.toSet()
        val addedTeams = pickedTeams - initialTeamKeys
        val removedTeams = initialTeamKeys - pickedTeams
        if (addedTeams.isNotEmpty() || removedTeams.isNotEmpty()) {
            val current = sportsPreferences.favoriteTeamIds.value
            val removing = removedTeams + removedTeams.map(SportsTeamKey::teamIdOf)
            runCatching { sportsPreferences.setFavoriteTeams(current - removing + addedTeams) }
            val named = addedTeams.mapNotNull { knownTeams[it] }
            if (named.isNotEmpty()) runCatching { sportsLibrary.rememberTeams(named) }
        }

        // The Guide's Custom list (Home's Top Channels).
        val pickedNetworks = state.selectedNetworkIds.toSet()
        val currentNetworks = liveTvDataStore.customChannelIds.value
        (pickedNetworks - initialNetworkIds).forEach { channelId ->
            if (channelId !in currentNetworks) {
                val info = knownNetworks[channelId]?.let {
                    CustomLiveChannel(id = it.id, name = it.name, logoUrl = it.logoUrl, position = it.position)
                }
                runCatching { liveTvDataStore.toggleCustomChannel(channelId, info) }
            }
        }
        (initialNetworkIds - pickedNetworks).forEach { channelId ->
            if (channelId in currentNetworks) runCatching { liveTvDataStore.toggleCustomChannel(channelId) }
        }

        // The series, into the Library.
        state.selectedSeries.forEach { meta ->
            runCatching {
                if (!libraryRepository.isInLibrary(meta.id, SERIES_TYPE).first()) {
                    libraryRepository.toggleDefault(libraryEntryOf(meta))
                }
            }
        }
    }

    private fun libraryEntryOf(meta: MetaPreview): LibraryEntryInput = LibraryEntryInput(
        itemId = meta.id,
        itemType = SERIES_TYPE,
        title = meta.name,
        year = meta.releaseInfo?.let { YEAR.find(it)?.value?.toIntOrNull() },
        imdbId = meta.id.takeIf { it.startsWith("tt") },
        poster = meta.poster,
        posterShape = PosterShape.POSTER,
        background = meta.background,
        logo = meta.logo,
        description = meta.description,
        releaseInfo = meta.releaseInfo,
        imdbRating = meta.imdbRating,
        genres = meta.genres,
        addonBaseUrl = CINEMETA_URL
    )

    /** Its loads stop and what it held goes (the page is gone). */
    private fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        seriesJob?.cancel()
        seriesJob = null
        allTeams = emptyList()
        allNetworks = emptyList()
        knownTeams.clear()
        knownNetworks.clear()
        _uiState.update { state -> CustomizeUiState(finishing = state.finishing) }
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    private fun sourcesWaitMs(): Long =
        if (liveTvDataStore.managedByAccount) ACCOUNT_SOURCES_WAIT_MS else SOURCES_WAIT_MS

    companion object {
        private const val CINEMETA_URL = "https://v3-cinemeta.strem.io"
        private const val CINEMETA_ID = "com.linvo.cinemeta"
        private const val CINEMETA_NAME = "Cinemeta"
        private const val SERIES_CATALOG_ID = "top"
        private const val SERIES_TYPE = "series"
        private const val MAX_SERIES = 60
        private const val MAX_NETWORKS = 1_500
        private const val MAX_QUERY = 60
        private const val SEARCH_WAIT_MS = 500L
        private const val SOURCES_WAIT_MS = 5_000L

        /**
         * With the account's IPTV set on the website, its source is there for sure, only still on
         * its way as the app opens (on a busy Mi Box that took longer than the old 1.5 s, and the
         * Networks page didn't show): waited for longer.
         */
        private const val ACCOUNT_SOURCES_WAIT_MS = 30_000L
        private const val TEAMS_CHECK_MS = 1_000L
        private val YEAR = Regex("\\d{4}")

        /** The leagues offered, in this order (the ones the app follows: a league it doesn't is left out). */
        private val LEAGUE_ORDER = listOf(
            "nfl", "nba", "mlb", "nhl", "usa.1", "ufc", "f1", "pga", "atp", "wta",
            "college-football", "mens-college-basketball", "college-baseball",
            "eng.1", "esp.1", "uefa.champions", "mex.1", "ita.1", "ger.1", "fra.1", "uefa.europa",
            "conmebol.libertadores", "concacaf.champions", "fifa.world",
            "wnba", "lidom", "caribbean-series", "world-baseball-classic", "banana-ball",
            "ppv-events", "nascar-premier", "irl", "motogp", "lpga", "liv", "ufl"
        )

        /** The Guide's categories offered for networks, in this order (not Local, 24/7, events or International). */
        val NETWORK_CATEGORIES = listOf(
            // RCTV: the owner's categories (his channel list), then another provider's.
            GuideBrowse.ENTERTAINMENT, GuideBrowse.MOVIES_EVENTS, GuideBrowse.NEWS_INFO, GuideBrowse.FAMILY_KIDS,
            GuideBrowse.PREMIUM,
            GuideBrowse.TV_SHOWS, GuideBrowse.NEWS, GuideBrowse.SPORTS, GuideBrowse.KIDS,
            GuideBrowse.LIFESTYLE, GuideBrowse.CRIME_REALITY, GuideBrowse.MOVIES, GuideBrowse.DOCUMENTARIES,
            GuideBrowse.MUSIC, GuideBrowse.GAME_SHOWS_CLASSICS, GuideBrowse.LATINO, GuideBrowse.PUERTO_RICO
        )

        /** The best-known networks, shown first (names as [normalize] leaves them, without spaces). */
        private val POPULAR_NETWORKS = listOf(
            "espn", "espn2", "foxsports1", "tnt", "tbs", "fx", "cnn", "cnbc", "foxnewschannel", "msnbc",
            "hgtv", "foodnetwork", "discovery", "discoverychannel", "history", "historychannel", "tlc", "bravo", "ae",
            "univision", "telemundo", "wapa", "nbc", "wiprcanal6", "wapapr", "telemundopr", "wipr", "teleoncepr",
            "espndeportes", "foxdeportes", "tudn",
            "nflnetwork", "nbatv", "mlbnetwork", "nhlnetwork", "golfchannel", "usanetwork", "amc",
            "disneychannel", "nickelodeon", "cartoonnetwork", "hbo", "showtime", "starz", "cinemax",
            "lifetime", "comedycentral", "syfy", "animalplanet", "nationalgeographic", "investigationdiscovery",
            "travelchannel", "theweatherchannel", "bet", "paramountnetwork", "galavision", "unimas", "cnnenespanol"
        )

        /** Lower case, without accents or marks, for comparing names ("Telemundo Puerto Rico", "A&E"). */
        fun normalize(text: String): String {
            val plain = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            val out = StringBuilder(plain.length)
            for (c in plain) {
                when {
                    c.isLetterOrDigit() -> out.append(c)
                    c == ' ' && out.isNotEmpty() && out.last() != ' ' -> out.append(' ')
                }
            }
            return out.toString().trim()
        }

        /** The series pages, by genre as Cinemeta names them. */
        val SERIES_GENRES = listOf(
            SeriesGenre(null, R.string.rctv_customize_genre_popular),
            SeriesGenre("Drama", R.string.rctv_customize_genre_drama),
            SeriesGenre("Comedy", R.string.rctv_customize_genre_comedy),
            SeriesGenre("Crime", R.string.rctv_customize_genre_crime),
            SeriesGenre("Action", R.string.rctv_customize_genre_action),
            SeriesGenre("Sci-Fi", R.string.rctv_customize_genre_scifi),
            SeriesGenre("Mystery", R.string.rctv_customize_genre_mystery),
            SeriesGenre("Reality-TV", R.string.rctv_customize_genre_reality),
            SeriesGenre("Animation", R.string.rctv_customize_genre_animation),
            SeriesGenre("Documentary", R.string.rctv_customize_genre_documentary),
            SeriesGenre("Family", R.string.rctv_customize_genre_family)
        )
    }
}
