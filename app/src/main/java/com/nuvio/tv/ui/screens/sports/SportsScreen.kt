package com.nuvio.tv.ui.screens.sports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsLeague
import com.nuvio.tv.domain.model.SportsTeam
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.AppleLandscapeCard
import com.nuvio.tv.ui.components.AppleLandscapeCardHeight
import com.nuvio.tv.ui.components.AppleLandscapeCardWidth
import com.nuvio.tv.ui.components.AppleTvColors
import com.nuvio.tv.ui.components.LocalTitleArtResolver
import com.nuvio.tv.ui.components.TitlesSeeAllPage
import com.nuvio.tv.ui.screens.home.HomeTileRow
import com.nuvio.tv.ui.theme.NuvioTheme

/** The Sports rows that open a See All page here (Teams opens the teams page). */
private enum class SportsSeeAll { LIVE, UPCOMING, PICKED, LEAGUES, SPORTS_TALK, MY_SPORTS, MY_TEAMS }

/**
 * The Sports page: the banner (the games on now, else the next ones), then Continue Watching, Live
 * Games, Upcoming Games, Picked For You, Top Leagues, Teams, Sports Talk, My Sports and My Teams. Each row shows ten
 * cards and "See All" when it has more.
 */
@Composable
fun SportsScreen(
    modifier: Modifier = Modifier,
    onPlayEvent: (SportsEvent) -> Unit = {},
    onPlayStream: (Stream) -> Unit = {},
    onOpenTeamsSeeAll: () -> Unit = {},
    onOpenFavoritesPicker: () -> Unit = {},
    onOpenLeagueDetail: (SportsLeague) -> Unit = {},
    /** A title's page (a sports talk show): its id ("tmdb:123") and type ("series"). */
    onOpenDetail: (String, String) -> Unit = { _, _ -> },
    /** A team's page. */
    onOpenTeam: (SportsTeam) -> Unit = {},
    viewModel: SportsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    // While the page shows: games go live on time, and the scores stay current.
    LaunchedEffect(viewModel) { viewModel.keepCurrent() }
    var seeAllName by rememberSaveable { mutableStateOf<String?>(null) }
    val seeAll = seeAllName?.let { name -> SportsSeeAll.entries.firstOrNull { it.name == name } }
    val rowRequesters = remember { mutableMapOf<String, FocusRequester>() }
    var returnRow by remember { mutableStateOf<String?>(null) }
    // Kept here so the page keeps its place (and opens on the banner once) around See All pages.
    val listState = rememberLazyListState()
    var heroFocusDone by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = seeAll != null) {
        returnRow = seeAll?.name
        seeAllName = null
    }
    // Back from a See All page: focus on the row it was opened from.
    LaunchedEffect(seeAll, returnRow) {
        val row = returnRow ?: return@LaunchedEffect
        if (seeAll != null) return@LaunchedEffect
        rowRequesters[row]?.let { requestFocusSoon(it) }
        returnRow = null
    }

    CompositionLocalProvider(LocalTitleArtResolver provides viewModel.titleArt) {
        Box(modifier = modifier.fillMaxSize().background(AppleTvColors.Background)) {
            when {
                seeAll != null -> SportsSeeAllPage(
                    which = seeAll,
                    uiState = uiState,
                    onPlayEvent = onPlayEvent,
                    onOpenLeagueDetail = onOpenLeagueDetail,
                    onOpenDetail = onOpenDetail,
                    onOpenTeam = onOpenTeam,
                    onShowFavoriteOptions = viewModel::showFavoriteOptions,
                    onLoadMoreTalk = viewModel::loadMoreSportsTalk
                )

                uiState.isLoading && uiState.leagues.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.sports_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.TextSecondary
                        )
                    }
                }

                uiState.error != null && uiState.leagues.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.sports_load_error),
                                style = MaterialTheme.typography.titleMedium,
                                color = NuvioTheme.colors.TextPrimary
                            )
                            Text(
                                text = uiState.error.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextSecondary
                            )
                        }
                    }
                }

                else -> SportsContent(
                    uiState = uiState,
                    listState = listState,
                    heroFocusDone = heroFocusDone,
                    onHeroFocusDone = { heroFocusDone = true },
                    rowRequesters = rowRequesters,
                    onPlayEvent = onPlayEvent,
                    onOpenTeamsSeeAll = onOpenTeamsSeeAll,
                    onOpenFavoritesPicker = onOpenFavoritesPicker,
                    onOpenLeagueDetail = onOpenLeagueDetail,
                    onOpenDetail = onOpenDetail,
                    onOpenSeeAll = { seeAllName = it.name },
                    onOpenTeam = onOpenTeam,
                    onToggleFavoriteTeam = viewModel::toggleFavoriteTeam,
                    onSelectAddonEvent = viewModel::selectAddonEvent,
                    onShowFavoriteOptions = viewModel::showFavoriteOptions,
                    onToggleLibraryEvent = viewModel::toggleLibraryEvent
                )
            }

            uiState.selectedAddonEvent?.let { selected ->
                SportsAddonStreamPickerOverlay(
                    event = selected,
                    streams = uiState.addonEventStreams,
                    isLoading = uiState.isLoadingAddonStreams,
                    error = uiState.addonStreamsError,
                    onSelectStream = { stream ->
                        viewModel.dismissAddonEventPicker()
                        onPlayStream(stream)
                    },
                    onDismiss = viewModel::dismissAddonEventPicker
                )
            }

            uiState.favoriteOptionsTarget?.let { target ->
                val eventTarget = target as? SportsFavoriteTarget.EventTarget
                SportsFavoriteOptionsDialog(
                    target = target,
                    favoriteTeamIds = uiState.favoriteTeams.map { it.id }.toSet(),
                    favoriteSportNames = uiState.favoriteSportNames,
                    onToggleTeam = viewModel::toggleFavoriteTeam,
                    onToggleSport = viewModel::toggleFavoriteSport,
                    onDismiss = viewModel::dismissFavoriteOptions,
                    isEventInLibrary = eventTarget?.event?.id?.let { it in uiState.libraryEventIds } ?: false,
                    onToggleLibrary = eventTarget?.let { { viewModel.toggleLibraryEvent(it.event) } }
                )
            }
        }
    }
}

@Composable
private fun SportsContent(
    uiState: SportsUiState,
    listState: LazyListState,
    heroFocusDone: Boolean,
    onHeroFocusDone: () -> Unit,
    rowRequesters: MutableMap<String, FocusRequester>,
    onPlayEvent: (SportsEvent) -> Unit,
    onOpenTeamsSeeAll: () -> Unit,
    onOpenFavoritesPicker: () -> Unit,
    onOpenLeagueDetail: (SportsLeague) -> Unit,
    onOpenDetail: (String, String) -> Unit,
    onOpenSeeAll: (SportsSeeAll) -> Unit,
    onOpenTeam: (SportsTeam) -> Unit,
    onToggleFavoriteTeam: (String) -> Unit,
    onSelectAddonEvent: (com.nuvio.tv.domain.model.SportsAddonEvent) -> Unit,
    onShowFavoriteOptions: (SportsFavoriteTarget) -> Unit,
    onToggleLibraryEvent: (SportsEvent) -> Unit
) {
    val favoriteTeamIds = remember(uiState.favoriteTeams) { uiState.favoriteTeams.map { it.id }.toSet() }
    val favoriteLeagueIds = remember(uiState.favoriteLeagues) { uiState.favoriteLeagues.map { it.id }.toSet() }
    val heroRequester = remember { FocusRequester() }
    val requester: (String) -> FocusRequester = { key -> rowRequesters.getOrPut(key) { FocusRequester() } }
    // The page opens on the banner's Watch button (once).
    LaunchedEffect(uiState.heroEvents.isNotEmpty()) {
        if (heroFocusDone || uiState.heroEvents.isEmpty()) return@LaunchedEffect
        onHeroFocusDone()
        requestFocusSoon(heroRequester)
    }
    val rowGap = Modifier.padding(top = 26.dp)

    // Back down the page: first back up to the top (the banner); from there Back is the app's
    // (the menu, then Home). Only while the page has focus (not the menu).
    var contentFocused by remember { mutableStateOf(false) }
    var heroFocused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = contentFocused && !heroFocused && uiState.heroEvents.isNotEmpty()) {
        scope.launch {
            runCatching { listState.animateScrollToItem(0) }
            requestFocusSoon(heroRequester)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { contentFocused = it.hasFocus },
        contentPadding = PaddingValues(bottom = 56.dp)
    ) {
        if (uiState.heroEvents.isNotEmpty()) {
            item(key = "hero") {
                Box(modifier = Modifier.onFocusChanged { heroFocused = it.hasFocus }) {
                    SportsHeroCarousel(
                        events = uiState.heroEvents,
                        libraryEventIds = uiState.libraryEventIds,
                        primaryFocusRequester = heroRequester,
                        onWatch = onPlayEvent,
                        onToggleLibrary = onToggleLibraryEvent
                    )
                }
            }
        }

        // Continue Watching
        if (uiState.continueWatching.isNotEmpty()) {
            item(key = "continue_watching") {
                val items = uiState.continueWatching
                SportsRow(
                    title = stringResource(R.string.sports_row_continue_watching),
                    count = items.size.coerceAtMost(SPORTS_ROW_MAX),
                    hasSeeAll = false,
                    keyOf = { items[it].id },
                    rowFocusRequester = requester("continue_watching"),
                    seeAllWidth = SportsGameCardWidth,
                    seeAllHeight = SportsGameCardHeight,
                    onSeeAll = {},
                    modifier = rowGap
                ) { index, itemModifier ->
                    val item = items[index]
                    SportsContinueCard(item = item, onClick = { item.event?.let(onPlayEvent) }, modifier = itemModifier)
                }
            }
        }

        // Live Games
        if (uiState.liveNow.isNotEmpty()) {
            item(key = "live_games") {
                GamesRow(
                    title = stringResource(R.string.sports_row_live_games),
                    games = uiState.liveNow,
                    rowFocusRequester = requester(SportsSeeAll.LIVE.name),
                    onPlayEvent = onPlayEvent,
                    onShowFavoriteOptions = onShowFavoriteOptions,
                    onSeeAll = { onOpenSeeAll(SportsSeeAll.LIVE) },
                    modifier = rowGap
                )
            }
        }

        // Games from the person's sports add-ons (they play here).
        if (uiState.addonEvents.isNotEmpty()) {
            item(key = "live_streams") {
                val events = uiState.addonEvents
                SportsRow(
                    title = stringResource(R.string.sports_row_live_streams),
                    count = events.size,
                    hasSeeAll = false,
                    keyOf = { events[it].id },
                    rowFocusRequester = requester("live_streams"),
                    seeAllWidth = SportsGameCardWidth,
                    seeAllHeight = SportsGameCardHeight,
                    onSeeAll = {},
                    modifier = rowGap
                ) { index, itemModifier ->
                    SportsAddonEventCard(event = events[index], onClick = { onSelectAddonEvent(events[index]) }, modifier = itemModifier)
                }
            }
        }

        // Upcoming Games
        if (uiState.upcomingGames.isNotEmpty()) {
            item(key = "upcoming_games") {
                GamesRow(
                    title = stringResource(R.string.sports_row_upcoming_games),
                    games = uiState.upcomingGames,
                    rowFocusRequester = requester(SportsSeeAll.UPCOMING.name),
                    onPlayEvent = onPlayEvent,
                    onShowFavoriteOptions = onShowFavoriteOptions,
                    onSeeAll = { onOpenSeeAll(SportsSeeAll.UPCOMING) },
                    modifier = rowGap
                )
            }
        }

        // Picked For You: the games of the person's teams (else the next big games).
        if (uiState.pickedForYou.isNotEmpty()) {
            item(key = "picked_for_you") {
                GamesRow(
                    title = stringResource(R.string.sports_row_picked_for_you),
                    games = uiState.pickedForYou,
                    rowFocusRequester = requester(SportsSeeAll.PICKED.name),
                    onPlayEvent = onPlayEvent,
                    onShowFavoriteOptions = onShowFavoriteOptions,
                    onSeeAll = { onOpenSeeAll(SportsSeeAll.PICKED) },
                    modifier = rowGap
                )
            }
        }

        // Top Leagues
        if (uiState.leagues.isNotEmpty()) {
            item(key = "top_leagues") {
                LeaguesRow(
                    title = stringResource(R.string.sports_row_top_leagues),
                    leagues = uiState.leagues,
                    favoriteLeagueIds = favoriteLeagueIds,
                    rowFocusRequester = requester(SportsSeeAll.LEAGUES.name),
                    onOpenLeagueDetail = onOpenLeagueDetail,
                    onShowFavoriteOptions = onShowFavoriteOptions,
                    onSeeAll = { onOpenSeeAll(SportsSeeAll.LEAGUES) },
                    modifier = rowGap,
                    alwaysSeeAll = true
                )
            }
        }

        // Teams
        if (uiState.teams.isNotEmpty()) {
            item(key = "teams") {
                TeamsRow(
                    title = stringResource(R.string.sports_row_teams),
                    teams = uiState.teams,
                    favoriteTeamIds = favoriteTeamIds,
                    rowFocusRequester = requester("teams"),
                    // A team: its page (holding OK: My Teams and the rest).
                    onClick = onOpenTeam,
                    onShowFavoriteOptions = onShowFavoriteOptions,
                    onSeeAll = onOpenTeamsSeeAll,
                    modifier = rowGap
                )
            }
        }

        // Sports Talk
        if (uiState.sportsTalkShows.isNotEmpty()) {
            item(key = "sports_talk") {
                val shows = uiState.sportsTalkShows
                SportsRow(
                    title = stringResource(R.string.sports_row_sports_talk),
                    count = shows.size.coerceAtMost(SPORTS_ROW_MAX),
                    hasSeeAll = shows.size > SPORTS_ROW_MAX || uiState.hasMoreTalk,
                    keyOf = { shows[it].id },
                    rowFocusRequester = requester(SportsSeeAll.SPORTS_TALK.name),
                    seeAllWidth = AppleLandscapeCardWidth,
                    seeAllHeight = AppleLandscapeCardHeight,
                    onSeeAll = { onOpenSeeAll(SportsSeeAll.SPORTS_TALK) },
                    modifier = rowGap
                ) { index, itemModifier ->
                    val show = shows[index]
                    AppleLandscapeCard(
                        item = show,
                        onClick = { onOpenDetail(show.id, show.apiType) },
                        modifier = itemModifier
                    )
                }
            }
        }

        // My Sports: the person's leagues (also the Library's Sports Leagues).
        item(key = "my_sports") {
            val leagues = uiState.favoriteLeagues
            if (leagues.isEmpty()) {
                SportsRow(
                    title = stringResource(R.string.sports_row_my_sports),
                    count = 1,
                    hasSeeAll = false,
                    keyOf = { "add_sports" },
                    rowFocusRequester = requester(SportsSeeAll.MY_SPORTS.name),
                    seeAllWidth = SportsTileWidth,
                    seeAllHeight = SportsTileHeight,
                    onSeeAll = {},
                    modifier = rowGap
                ) { _, itemModifier ->
                    SportsAddTile(label = stringResource(R.string.sports_add_favorite_sports), onClick = onOpenFavoritesPicker, modifier = itemModifier)
                }
            } else {
                LeaguesRow(
                    title = stringResource(R.string.sports_row_my_sports),
                    leagues = leagues,
                    favoriteLeagueIds = favoriteLeagueIds,
                    rowFocusRequester = requester(SportsSeeAll.MY_SPORTS.name),
                    onOpenLeagueDetail = onOpenLeagueDetail,
                    onShowFavoriteOptions = onShowFavoriteOptions,
                    onSeeAll = { onOpenSeeAll(SportsSeeAll.MY_SPORTS) },
                    modifier = rowGap
                )
            }
        }

        // My Teams (also the Library's Sports Teams).
        item(key = "my_teams") {
            val teams = uiState.favoriteTeams
            if (teams.isEmpty()) {
                SportsRow(
                    title = stringResource(R.string.sports_row_my_teams),
                    count = 1,
                    hasSeeAll = false,
                    keyOf = { "add_teams" },
                    rowFocusRequester = requester(SportsSeeAll.MY_TEAMS.name),
                    seeAllWidth = SportsTileWidth,
                    seeAllHeight = SportsTileHeight,
                    onSeeAll = {},
                    modifier = rowGap
                ) { _, itemModifier ->
                    SportsAddTile(label = stringResource(R.string.sports_add_favorite_teams), onClick = onOpenFavoritesPicker, modifier = itemModifier)
                }
            } else {
                TeamsRow(
                    title = stringResource(R.string.sports_row_my_teams),
                    teams = teams,
                    favoriteTeamIds = favoriteTeamIds,
                    rowFocusRequester = requester(SportsSeeAll.MY_TEAMS.name),
                    // A team of My Teams: its page (holding OK: its options).
                    onClick = onOpenTeam,
                    onShowFavoriteOptions = onShowFavoriteOptions,
                    onSeeAll = { onOpenSeeAll(SportsSeeAll.MY_TEAMS) },
                    modifier = rowGap
                )
            }
        }
    }
}

@Composable
private fun GamesRow(
    title: String,
    games: List<SportsEvent>,
    rowFocusRequester: FocusRequester,
    onPlayEvent: (SportsEvent) -> Unit,
    onShowFavoriteOptions: (SportsFavoriteTarget) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    SportsRow(
        title = title,
        count = games.size.coerceAtMost(SPORTS_ROW_MAX),
        hasSeeAll = games.size > SPORTS_ROW_MAX,
        keyOf = { games[it].id },
        rowFocusRequester = rowFocusRequester,
        seeAllWidth = SportsGameCardWidth,
        seeAllHeight = SportsGameCardHeight,
        onSeeAll = onSeeAll,
        modifier = modifier
    ) { index, itemModifier ->
        val game = games[index]
        SportsGameCard(
            event = game,
            onClick = { onPlayEvent(game) },
            onLongPress = { onShowFavoriteOptions(SportsFavoriteTarget.EventTarget(game)) },
            modifier = itemModifier
        )
    }
}

@Composable
private fun LeaguesRow(
    title: String,
    leagues: List<SportsLeague>,
    favoriteLeagueIds: Set<String>,
    rowFocusRequester: FocusRequester,
    onOpenLeagueDetail: (SportsLeague) -> Unit,
    onShowFavoriteOptions: (SportsFavoriteTarget) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
    /** See All even with ten leagues or fewer (Top Leagues opens the Leagues page). */
    alwaysSeeAll: Boolean = false
) {
    SportsRow(
        title = title,
        count = leagues.size.coerceAtMost(SPORTS_ROW_MAX),
        hasSeeAll = alwaysSeeAll || leagues.size > SPORTS_ROW_MAX,
        keyOf = { leagues[it].id },
        rowFocusRequester = rowFocusRequester,
        seeAllWidth = SportsTileWidth,
        seeAllHeight = SportsTileHeight,
        onSeeAll = onSeeAll,
        modifier = modifier
    ) { index, itemModifier ->
        LeagueTile(leagues[index], favoriteLeagueIds, onOpenLeagueDetail, onShowFavoriteOptions, itemModifier)
    }
}

@Composable
private fun TeamsRow(
    title: String,
    teams: List<SportsTeam>,
    favoriteTeamIds: Set<String>,
    rowFocusRequester: FocusRequester,
    onClick: (SportsTeam) -> Unit,
    onShowFavoriteOptions: (SportsFavoriteTarget) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    SportsRow(
        title = title,
        count = teams.size.coerceAtMost(SPORTS_ROW_MAX),
        hasSeeAll = teams.size > SPORTS_ROW_MAX,
        keyOf = { teams[it].id },
        rowFocusRequester = rowFocusRequester,
        seeAllWidth = SportsTileWidth,
        seeAllHeight = SportsTileHeight,
        onSeeAll = onSeeAll,
        modifier = modifier
    ) { index, itemModifier ->
        TeamTile(teams[index], favoriteTeamIds, onClick, onShowFavoriteOptions, itemModifier)
    }
}

@Composable
private fun LeagueTile(
    league: SportsLeague,
    favoriteLeagueIds: Set<String>,
    onOpenLeagueDetail: (SportsLeague) -> Unit,
    onShowFavoriteOptions: (SportsFavoriteTarget) -> Unit,
    modifier: Modifier
) {
    SportsLogoTile(
        name = sportsLeagueShortName(league),
        logoUrl = league.badgeUrl,
        color = sportsLeagueColor(league.id, league.name),
        isFavorite = league.id in favoriteLeagueIds,
        onClick = { onOpenLeagueDetail(league) },
        onLongPress = { onShowFavoriteOptions(SportsFavoriteTarget.LeagueTarget(league)) },
        modifier = modifier
    )
}

@Composable
private fun TeamTile(
    team: SportsTeam,
    favoriteTeamIds: Set<String>,
    onClick: (SportsTeam) -> Unit,
    onShowFavoriteOptions: (SportsFavoriteTarget) -> Unit,
    modifier: Modifier
) {
    SportsLogoTile(
        name = team.name,
        logoUrl = team.badgeUrl,
        color = sportsTeamColor(team.color, team.id),
        isFavorite = team.id in favoriteTeamIds,
        onClick = { onClick(team) },
        onLongPress = { onShowFavoriteOptions(SportsFavoriteTarget.TeamTarget(team)) },
        modifier = modifier,
        prominentName = true
    )
}

/** A row's See All page. */
@Composable
private fun SportsSeeAllPage(
    which: SportsSeeAll,
    uiState: SportsUiState,
    onPlayEvent: (SportsEvent) -> Unit,
    onOpenLeagueDetail: (SportsLeague) -> Unit,
    onOpenDetail: (String, String) -> Unit,
    onOpenTeam: (SportsTeam) -> Unit,
    onShowFavoriteOptions: (SportsFavoriteTarget) -> Unit,
    onLoadMoreTalk: () -> Unit
) {
    val favoriteTeamIds = remember(uiState.favoriteTeams) { uiState.favoriteTeams.map { it.id }.toSet() }
    val favoriteLeagueIds = remember(uiState.favoriteLeagues) { uiState.favoriteLeagues.map { it.id }.toSet() }
    when (which) {
        SportsSeeAll.LIVE, SportsSeeAll.UPCOMING, SportsSeeAll.PICKED -> {
            val games = when (which) {
                SportsSeeAll.LIVE -> uiState.liveNow
                SportsSeeAll.PICKED -> uiState.pickedForYou
                else -> uiState.upcomingGames
            }
            SportsGridPage(
                title = stringResource(
                    when (which) {
                        SportsSeeAll.LIVE -> R.string.sports_row_live_games
                        SportsSeeAll.PICKED -> R.string.sports_row_picked_for_you
                        else -> R.string.sports_row_upcoming_games
                    }
                ),
                count = games.size,
                columns = 3,
                keyOf = { games[it].id }
            ) { index, itemModifier ->
                val game = games[index]
                SportsGameCard(
                    event = game,
                    onClick = { onPlayEvent(game) },
                    onLongPress = { onShowFavoriteOptions(SportsFavoriteTarget.EventTarget(game)) },
                    modifier = itemModifier
                )
            }
        }

        // Every league, going down the page under a "LEAGUES" banner.
        SportsSeeAll.LEAGUES -> LeaguesBrowsePage(
            leagues = uiState.leagues,
            favoriteLeagueIds = favoriteLeagueIds,
            onOpenLeague = onOpenLeagueDetail,
            onLongPressLeague = { league -> onShowFavoriteOptions(SportsFavoriteTarget.LeagueTarget(league)) }
        )

        SportsSeeAll.MY_SPORTS -> {
            val leagues = uiState.favoriteLeagues
            SportsGridPage(
                title = stringResource(R.string.sports_row_my_sports),
                count = leagues.size,
                columns = 4,
                keyOf = { leagues[it].id }
            ) { index, itemModifier ->
                LeagueTile(leagues[index], favoriteLeagueIds, onOpenLeagueDetail, onShowFavoriteOptions, itemModifier)
            }
        }

        SportsSeeAll.MY_TEAMS -> {
            val teams = uiState.favoriteTeams
            SportsGridPage(
                title = stringResource(R.string.sports_row_my_teams),
                count = teams.size,
                columns = 4,
                keyOf = { teams[it].id }
            ) { index, itemModifier ->
                TeamTile(
                    team = teams[index],
                    favoriteTeamIds = favoriteTeamIds,
                    onClick = onOpenTeam,
                    onShowFavoriteOptions = onShowFavoriteOptions,
                    modifier = itemModifier
                )
            }
        }

        SportsSeeAll.SPORTS_TALK -> TitlesSeeAllPage(
            title = stringResource(R.string.sports_row_sports_talk),
            stateKey = "sports_talk",
            items = uiState.sportsTalkShows,
            isLoadingMore = uiState.isLoadingMoreTalk,
            onItemClick = { show -> onOpenDetail(show.id, show.apiType) },
            onItemLongPress = {},
            onLoadMore = onLoadMoreTalk
        )
    }
}
