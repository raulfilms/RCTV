package com.nuvio.tv.ui.screens.sports

import com.nuvio.tv.domain.model.hasTeam
import com.nuvio.tv.domain.model.SportsTeamKey
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsNewsArticle
import com.nuvio.tv.domain.model.SportsStandingsGroup
import com.nuvio.tv.domain.model.SportsTeam
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.nuvio.tv.ui.components.tileSurfaceBorder
import kotlinx.coroutines.launch

/** A league's own page (tapped from the Leagues row): hero + a tab bar of Home/Games/Teams/Standings/Schedule/News/Highlights/Videos. */
@Composable
fun LeagueDetailScreen(
    modifier: Modifier = Modifier,
    onPlayEvent: (SportsEvent) -> Unit = {},
    /** A team's page. */
    onOpenTeam: (com.nuvio.tv.domain.model.SportsTeam) -> Unit = {},
    /** RCTV: one of the league's channels (or replay channels), ready to play. */
    onPlayChannel: (com.nuvio.tv.domain.model.LiveTvChannel) -> Unit = {},
    /** RCTV: a highlights video (YouTube id, title), played inside RCTV. */
    onPlayVideo: (videoId: String, title: String) -> Unit = { _, _ -> },
    viewModel: LeagueDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    // While the page shows: games go live on time.
    androidx.compose.runtime.LaunchedEffect(viewModel) { viewModel.keepCurrent() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    // RCTV: a league channel plays on the Guide's player; one whose address can't be had says so.
    val playChannel: (LeagueChannelItem) -> Unit = { item ->
        scope.launch {
            val channel = viewModel.playable(item)
            if (channel != null) {
                onPlayChannel(channel)
            } else {
                android.widget.Toast.makeText(context, R.string.league_channel_failed, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            when {
                uiState.isLoading && uiState.featuredEvent == null && uiState.allEvents.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.league_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.TextSecondary
                        )
                    }
                }
                uiState.error != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.league_load_error),
                            style = MaterialTheme.typography.titleMedium,
                            color = NuvioTheme.colors.TextPrimary
                        )
                    }
                }
                else -> {
                    uiState.featuredEvent?.let { featured ->
                        SportsHeroBanner(
                            event = featured,
                            onWatchLive = { onPlayEvent(featured) },
                            isInLibrary = featured.id in uiState.libraryEventIds,
                            onToggleLibrary = { viewModel.toggleLibraryEvent(featured) },
                            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.md)
                        )
                    } ?: uiState.league?.let { league ->
                        LeagueSimpleHeader(name = league.name, badgeUrl = league.badgeUrl)
                    }

                    LeagueTabRow(selectedTab = uiState.selectedTab, onSelectTab = viewModel::selectTab)

                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

                    Box(modifier = Modifier.fillMaxSize()) {
                        when (uiState.selectedTab) {
                            LeagueDetailTab.HOME -> LeagueHomeContent(
                                uiState = uiState,
                                onPlayEvent = onPlayEvent,
                                onShowFavoriteOptions = viewModel::showFavoriteOptions,
                                onChangeWeek = viewModel::changeScheduleWeek,
                                onPlayChannel = playChannel,
                                onPlayVideo = onPlayVideo
                            )
                            LeagueDetailTab.GAMES -> LeagueGamesContent(
                                uiState = uiState,
                                onPlayEvent = onPlayEvent,
                                onShowFavoriteOptions = viewModel::showFavoriteOptions,
                                onSelectFilter = viewModel::selectGamesFilter,
                                onChangeWeek = viewModel::changeScheduleWeek
                            )
                            LeagueDetailTab.TEAMS -> LeagueTeamsContent(
                                uiState = uiState,
                                onOpenTeam = onOpenTeam,
                                onToggleFavoriteTeam = viewModel::toggleFavoriteTeam,
                                onShowFavoriteOptions = viewModel::showFavoriteOptions
                            )
                            LeagueDetailTab.STANDINGS -> LeagueStandingsContent(uiState = uiState)
                            LeagueDetailTab.NEWS -> LeagueNewsContent(uiState = uiState)
                            LeagueDetailTab.HIGHLIGHTS -> LeagueHighlightsContent(
                                uiState = uiState,
                                onPlayVideo = onPlayVideo
                            )
                        }
                    }
                }
            }
        }

        uiState.favoriteOptionsTarget?.let { target ->
            val eventTarget = target as? SportsFavoriteTarget.EventTarget
            SportsFavoriteOptionsDialog(
                target = target,
                favoriteTeamIds = uiState.favoriteTeamIds,
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

@Composable
private fun LeagueSimpleHeader(name: String, badgeUrl: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.md)
    ) {
        if (!badgeUrl.isNullOrBlank()) {
            SportsLogoImage(
                url = badgeUrl,
                background = SportsLogoDarkBackground,
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
        }
        Text(
            text = name.uppercase(),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextPrimary
        )
    }
}

@Composable
private fun LeagueTabRow(selectedTab: LeagueDetailTab, onSelectTab: (LeagueDetailTab) -> Unit) {
    // RCTV (owner's menu): no Schedule (it's Games' By week) and no Videos.
    val tabs = listOf(
        LeagueDetailTab.HOME to R.string.league_tab_home,
        LeagueDetailTab.GAMES to R.string.league_tab_games,
        LeagueDetailTab.TEAMS to R.string.league_tab_teams,
        LeagueDetailTab.STANDINGS to R.string.league_tab_standings,
        LeagueDetailTab.NEWS to R.string.league_tab_news,
        LeagueDetailTab.HIGHLIGHTS to R.string.league_tab_highlights
    )
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.lg)
    ) {
        items(tabs, key = { it.first.name }) { (tab, labelRes) ->
            val selected = tab == selectedTab
            Surface(
                onClick = { onSelectTab(tab) },
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundElevated,
                    contentColor = if (selected) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextSecondary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    focusedContentColor = NuvioTheme.colors.Primary
                ),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.full)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f)
            ) {
                Text(
                    text = stringResource(labelRes).uppercase(),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs)
                )
            }
        }
    }
}

/**
 * RCTV (owner's rows, every league): Live, Schedule (by week), Channels, Game Replays, Highlights.
 * A league without official channels, replay channels or highlights has no such row.
 */
@Composable
private fun LeagueHomeContent(
    uiState: LeagueDetailUiState,
    onPlayEvent: (SportsEvent) -> Unit,
    onShowFavoriteOptions: (SportsFavoriteTarget) -> Unit,
    onChangeWeek: (Int) -> Unit,
    onPlayChannel: (LeagueChannelItem) -> Unit,
    onPlayVideo: (String, String) -> Unit
) {
    val leagueName = uiState.league?.name.orEmpty()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)
    ) {
        item(key = "live") {
            SportsRowSection(title = stringResource(R.string.league_row_live, leagueName).uppercase()) {
                if (uiState.liveEvents.isEmpty()) {
                    item(key = "live_empty") { LeagueEmptyRowNote(stringResource(R.string.league_no_live_games)) }
                } else {
                    items(uiState.liveEvents.distinctBy { it.id }, key = { "live_${it.id}" }) { event ->
                        ScoreboardCard(
                            event = event,
                            onClick = { onPlayEvent(event) },
                            onLongPress = { onShowFavoriteOptions(SportsFavoriteTarget.EventTarget(event)) }
                        )
                    }
                }
            }
        }
        item(key = "schedule") {
            Column(modifier = Modifier.fillMaxWidth()) {
                LeagueWeekHeader(
                    title = stringResource(R.string.league_row_schedule, leagueName).uppercase(),
                    weekLabel = scheduleWeekLabel(uiState),
                    onChangeWeek = onChangeWeek
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                    contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.lg),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when {
                        uiState.isLoadingSchedule && uiState.scheduleEvents.isEmpty() ->
                            item(key = "schedule_loading") { LeagueEmptyRowNote(stringResource(R.string.league_loading)) }
                        uiState.scheduleEvents.isEmpty() ->
                            item(key = "schedule_empty") { LeagueEmptyRowNote(stringResource(R.string.league_no_games_scheduled)) }
                        else -> items(uiState.scheduleEvents.distinctBy { it.id }, key = { "week_${it.id}" }) { event ->
                            ScoreboardCard(
                                event = event,
                                onClick = { onPlayEvent(event) },
                                onLongPress = { onShowFavoriteOptions(SportsFavoriteTarget.EventTarget(event)) }
                            )
                        }
                    }
                }
            }
        }
        if (uiState.channels.isNotEmpty()) {
            item(key = "channels") {
                SportsRowSection(title = stringResource(R.string.league_row_channels, leagueName).uppercase()) {
                    items(uiState.channels, key = { "ch_${it.channel.id}" }) { item ->
                        LeagueChannelCard(item = item, program = uiState.channelPrograms[item.channel.id], onClick = { onPlayChannel(item) })
                    }
                }
            }
        }
        if (uiState.replays.isNotEmpty()) {
            item(key = "replays") {
                SportsRowSection(title = stringResource(R.string.league_row_replays, leagueName).uppercase()) {
                    items(uiState.replays, key = { "rp_${it.channel.id}" }) { item ->
                        LeagueChannelCard(item = item, program = uiState.channelPrograms[item.channel.id], onClick = { onPlayChannel(item) })
                    }
                }
            }
        }
        if (uiState.highlights.isNotEmpty()) {
            item(key = "highlights") {
                SportsRowSection(title = stringResource(R.string.league_row_highlights, leagueName).uppercase()) {
                    items(uiState.highlights, key = { "hl_${it.videoId}" }) { video ->
                        HighlightVideoCard(video = video, onClick = { onPlayVideo(video.videoId, video.title) })
                    }
                }
            }
        }
    }
}

/** The schedule's week: "Week 3" (football, as ESPN numbers it), "This week", or its days. */
@Composable
private fun scheduleWeekLabel(uiState: LeagueDetailUiState): String {
    val week = uiState.scheduleWeekNumber
    if (week != null) {
        return when (uiState.scheduleSeasonType) {
            1 -> stringResource(R.string.league_week_preseason, week)
            3 -> stringResource(R.string.league_week_postseason, week)
            else -> stringResource(R.string.league_schedule_week_label, week)
        }
    }
    if (uiState.scheduleWeekOffset == 0) return stringResource(R.string.league_schedule_this_week)
    val start = uiState.scheduleRangeStartMs ?: return stringResource(R.string.league_schedule_this_week)
    val format = SimpleDateFormat("MMM d", Locale.getDefault())
    return "${format.format(Date(start))} – ${format.format(Date(start + 6L * 24 * 60 * 60 * 1000))}"
}

/** A row's title with its week under it, and "< WEEK >" to move it (as the owner's concept). */
@Composable
private fun LeagueWeekHeader(title: String, weekLabel: String, onChangeWeek: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTheme.spacing.lg)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = NuvioTheme.colors.TextPrimary
            )
            Text(
                text = weekLabel.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextSecondary
            )
        }
        WeekNavButton(label = "<", onClick = { onChangeWeek(-1) })
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
        Text(
            text = stringResource(R.string.league_week_button).uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextSecondary
        )
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
        WeekNavButton(label = ">", onClick = { onChangeWeek(1) })
    }
}

/** RCTV: one of the league's channels (or replay channels): its logo, LIVE, its name and what's on. */
@Composable
private fun LeagueChannelCard(item: LeagueChannelItem, program: String?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = tileSurfaceBorder(RoundedCornerShape(NuvioTheme.radii.md)),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = Modifier.width(260.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(124.dp)
                    .background(NuvioTheme.colors.BackgroundElevated)
            ) {
                val logo = item.channel.logoUrl
                if (!logo.isNullOrBlank()) {
                    AsyncImage(
                        model = logo,
                        contentDescription = item.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 56.dp, vertical = 26.dp)
                    )
                } else {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md)
                    )
                }
                Box(modifier = Modifier.align(Alignment.TopStart).padding(10.dp)) { LiveBadge() }
            }
            Column(modifier = Modifier.padding(NuvioTheme.spacing.md)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = program.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** RCTV: a highlights video (16:9): its picture, its length and its title. */
@Composable
private fun HighlightVideoCard(
    video: com.nuvio.tv.data.trailer.YouTubeSearchResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = tileSurfaceBorder(RoundedCornerShape(NuvioTheme.radii.md)),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = modifier.width(300.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(androidx.compose.ui.graphics.Color.Black)
            ) {
                AsyncImage(
                    model = "https://i.ytimg.com/vi/${video.videoId}/mqdefault.jpg",
                    contentDescription = video.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                video.durationSeconds?.let { seconds ->
                    Text(
                        text = durationLabel(seconds),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.75f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = video.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(NuvioTheme.spacing.md)
            )
        }
    }
}

private fun durationLabel(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** RCTV (owner's menu): Games, with its filters: Live, Upcoming, Completed, By week. */
@Composable
private fun LeagueGamesContent(
    uiState: LeagueDetailUiState,
    onPlayEvent: (SportsEvent) -> Unit,
    onShowFavoriteOptions: (SportsFavoriteTarget) -> Unit,
    onSelectFilter: (LeagueGamesFilter) -> Unit,
    onChangeWeek: (Int) -> Unit
) {
    val filter = uiState.gamesFilter ?: if (uiState.liveEvents.isNotEmpty()) LeagueGamesFilter.LIVE else LeagueGamesFilter.UPCOMING
    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.lg),
            modifier = Modifier.fillMaxWidth()
        ) {
            val filters = listOf(
                LeagueGamesFilter.LIVE to R.string.league_games_live,
                LeagueGamesFilter.UPCOMING to R.string.league_games_upcoming,
                LeagueGamesFilter.COMPLETED to R.string.league_games_completed,
                LeagueGamesFilter.BY_WEEK to R.string.league_games_by_week
            )
            items(filters, key = { it.first.name }) { (entry, label) ->
                LeaguePill(
                    text = stringResource(label).uppercase(),
                    selected = entry == filter,
                    onClick = { onSelectFilter(entry) }
                )
            }
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        if (filter == LeagueGamesFilter.BY_WEEK) {
            LeagueWeekHeader(
                title = stringResource(R.string.league_row_schedule, uiState.league?.name.orEmpty()).uppercase(),
                weekLabel = scheduleWeekLabel(uiState),
                onChangeWeek = onChangeWeek
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
        }
        val (games, emptyText) = when (filter) {
            LeagueGamesFilter.LIVE -> uiState.liveEvents to R.string.league_no_live_games
            LeagueGamesFilter.UPCOMING -> uiState.upcomingEvents to R.string.league_no_games_scheduled
            LeagueGamesFilter.COMPLETED -> uiState.completedEvents to R.string.league_no_completed_games
            LeagueGamesFilter.BY_WEEK -> uiState.scheduleEvents to R.string.league_no_games_scheduled
        }
        when {
            filter == LeagueGamesFilter.BY_WEEK && uiState.isLoadingSchedule -> LeagueEmptyState(stringResource(R.string.league_loading))
            games.isEmpty() -> LeagueEmptyState(stringResource(emptyText))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 260.dp),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm),
                modifier = Modifier.fillMaxSize()
            ) {
                gridItems(games.distinctBy { it.id }, key = { it.id }) { event ->
                    ScoreboardCard(
                        event = event,
                        onClick = { onPlayEvent(event) },
                        onLongPress = { onShowFavoriteOptions(SportsFavoriteTarget.EventTarget(event)) }
                    )
                }
            }
        }
    }
}

/** RCTV: the Highlights tab: the league's latest official highlights. */
@Composable
private fun LeagueHighlightsContent(uiState: LeagueDetailUiState, onPlayVideo: (String, String) -> Unit) {
    when {
        uiState.isLoadingHighlights || !uiState.highlightsLoaded -> LeagueEmptyState(stringResource(R.string.league_loading))
        uiState.highlights.isEmpty() -> LeagueEmptyState(stringResource(R.string.league_no_highlights))
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 300.dp),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm),
            modifier = Modifier.fillMaxSize()
        ) {
            gridItems(uiState.highlights, key = { it.videoId }) { video ->
                HighlightVideoCard(video = video, onClick = { onPlayVideo(video.videoId, video.title) })
            }
        }
    }
}

/** A pill of the page's menus (the tabs, the Games filters). */
@Composable
private fun LeaguePill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundElevated,
            contentColor = if (selected) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextSecondary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.Primary
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.full)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs)
        )
    }
}

@Composable
private fun LeagueTeamsContent(
    uiState: LeagueDetailUiState,
    onOpenTeam: (com.nuvio.tv.domain.model.SportsTeam) -> Unit,
    onToggleFavoriteTeam: (String) -> Unit,
    onShowFavoriteOptions: (SportsFavoriteTarget) -> Unit
) {
    when {
        uiState.isLoadingTeams -> LeagueEmptyState(stringResource(R.string.league_loading))
        uiState.teams.isEmpty() && uiState.teamsLoaded -> LeagueEmptyState(stringResource(R.string.league_no_teams))
        else -> {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm),
                modifier = Modifier.fillMaxSize()
            ) {
                gridItems(uiState.teams.distinctBy { it.id }, key = { it.id }) { team ->
                    TeamGridCard(
                        team = team,
                        isFavorite = uiState.favoriteTeamIds.hasTeam(team),
                        onClick = { onOpenTeam(team) },
                        onToggleFavorite = { onToggleFavoriteTeam(SportsTeamKey.of(team)) },
                        onLongPress = { onShowFavoriteOptions(SportsFavoriteTarget.TeamTarget(team)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LeagueStandingsContent(uiState: LeagueDetailUiState) {
    when {
        uiState.isLoadingStandings -> LeagueEmptyState(stringResource(R.string.league_loading))
        uiState.standings.isEmpty() && uiState.standingsLoaded -> LeagueEmptyState(stringResource(R.string.league_no_standings))
        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
            ) {
                // Two groups can share a name (the North of two leagues): keyed by their place too.
                val keys = uniqueKeys(uiState.standings.size) { uiState.standings[it].name }
                itemsIndexed(uiState.standings, key = { index, _ -> keys[index] }) { _, group ->
                    StandingsGroupTable(group = group)
                }
            }
        }
    }
}

@Composable
private fun StandingsGroupTable(group: SportsStandingsGroup) {
    val statLabels = group.entries.firstOrNull()?.stats?.map { it.label } ?: emptyList()
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = group.name,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm)
        )
        if (statLabels.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = NuvioTheme.spacing.xs)) {
                Text(
                    text = "",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall
                )
                statLabels.forEach { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = NuvioTheme.colors.TextTertiary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.width(48.dp)
                    )
                }
            }
        }
        group.entries.forEach { entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = NuvioTheme.spacing.xs)
            ) {
                Text(
                    text = "${entry.rank}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.width(24.dp)
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(NuvioTheme.colors.SurfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (!entry.team.badgeUrl.isNullOrBlank()) {
                        SportsLogoImage(
                            url = entry.team.badgeUrl,
                            background = SportsLogoDarkBackground,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                Text(
                    text = entry.team.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                entry.stats.forEach { stat ->
                    Text(
                        text = stat.value,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.width(48.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekNavButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.Primary
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.sm))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs)
        )
    }
}

@Composable
private fun LeagueNewsContent(uiState: LeagueDetailUiState) {
    when {
        uiState.isLoadingNews -> LeagueEmptyState(stringResource(R.string.league_loading))
        uiState.news.isEmpty() && uiState.newsLoaded -> LeagueEmptyState(stringResource(R.string.league_no_news))
        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                items(uiState.news.distinctBy { it.id }, key = { it.id }) { article ->
                    NewsArticleCard(article = article)
                }
            }
        }
    }
}

@Composable
private fun NewsArticleCard(article: SportsNewsArticle) {
    val context = LocalContext.current
    Surface(
        onClick = {
            val link = article.link ?: return@Surface
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
        },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        // RCTV: the white outline every tile has while focused (see TileFocus.kt).
        border = tileSurfaceBorder(RoundedCornerShape(NuvioTheme.radii.md)),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(NuvioTheme.spacing.md)) {
            if (!article.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = article.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 140.dp, height = 90.dp)
                        .clip(RoundedCornerShape(NuvioTheme.radii.sm))
                )
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = article.headline,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                article.description?.let { description ->
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                article.publishedMs?.let { publishedMs ->
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
                    Text(
                        text = SimpleDateFormat("EEE, MMM d • h:mm a", Locale.getDefault()).format(Date(publishedMs)),
                        style = MaterialTheme.typography.labelSmall,
                        color = NuvioTheme.colors.TextTertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun LeagueEmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
    }
}

@Composable
private fun LeagueEmptyRowNote(message: String) {
    Box(
        modifier = Modifier
            .height(120.dp)
            .padding(horizontal = NuvioTheme.spacing.sm),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextTertiary
        )
    }
}
