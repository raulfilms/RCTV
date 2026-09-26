package com.nuvio.tv.ui.screens.sports

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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

/** A league's own page (tapped from the Leagues row): hero + a tab bar of Home/Games/Teams/Standings/Schedule/News/Highlights/Videos. */
@Composable
fun LeagueDetailScreen(
    modifier: Modifier = Modifier,
    onPlayEvent: (SportsEvent) -> Unit = {},
    viewModel: LeagueDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

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
                                onShowFavoriteOptions = viewModel::showFavoriteOptions
                            )
                            LeagueDetailTab.GAMES -> LeagueGamesContent(
                                uiState = uiState,
                                onPlayEvent = onPlayEvent,
                                onShowFavoriteOptions = viewModel::showFavoriteOptions
                            )
                            LeagueDetailTab.TEAMS -> LeagueTeamsContent(
                                uiState = uiState,
                                onToggleFavoriteTeam = viewModel::toggleFavoriteTeam,
                                onShowFavoriteOptions = viewModel::showFavoriteOptions
                            )
                            LeagueDetailTab.STANDINGS -> LeagueStandingsContent(uiState = uiState)
                            LeagueDetailTab.SCHEDULE -> LeagueScheduleContent(
                                uiState = uiState,
                                onPlayEvent = onPlayEvent,
                                onShowFavoriteOptions = viewModel::showFavoriteOptions,
                                onChangeWeek = viewModel::changeScheduleWeek
                            )
                            LeagueDetailTab.NEWS -> LeagueNewsContent(uiState = uiState)
                            LeagueDetailTab.HIGHLIGHTS -> LeagueComingSoonContent(
                                message = stringResource(R.string.league_highlights_coming_soon)
                            )
                            LeagueDetailTab.VIDEOS -> LeagueComingSoonContent(
                                message = stringResource(R.string.league_videos_coming_soon)
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
            AsyncImage(
                model = badgeUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
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
    val tabs = listOf(
        LeagueDetailTab.HOME to R.string.league_tab_home,
        LeagueDetailTab.GAMES to R.string.league_tab_games,
        LeagueDetailTab.TEAMS to R.string.league_tab_teams,
        LeagueDetailTab.STANDINGS to R.string.league_tab_standings,
        LeagueDetailTab.SCHEDULE to R.string.league_tab_schedule,
        LeagueDetailTab.NEWS to R.string.league_tab_news,
        LeagueDetailTab.HIGHLIGHTS to R.string.league_tab_highlights,
        LeagueDetailTab.VIDEOS to R.string.league_tab_videos
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

@Composable
private fun LeagueHomeContent(
    uiState: LeagueDetailUiState,
    onPlayEvent: (SportsEvent) -> Unit,
    onShowFavoriteOptions: (SportsFavoriteTarget) -> Unit
) {
    val leagueName = uiState.league?.name.orEmpty()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)
    ) {
        item(key = "live") {
            SportsRowSection(title = stringResource(R.string.league_row_live, leagueName)) {
                if (uiState.liveEvents.isEmpty()) {
                    item(key = "live_empty") { LeagueEmptyRowNote(stringResource(R.string.league_no_live_games)) }
                } else {
                    items(uiState.liveEvents, key = { "live_${it.id}" }) { event ->
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
            SportsRowSection(title = stringResource(R.string.league_row_schedule, leagueName)) {
                if (uiState.upcomingEvents.isEmpty()) {
                    item(key = "schedule_empty") { LeagueEmptyRowNote(stringResource(R.string.league_no_games_scheduled)) }
                } else {
                    items(uiState.upcomingEvents, key = { "up_${it.id}" }) { event ->
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
}

@Composable
private fun LeagueGamesContent(
    uiState: LeagueDetailUiState,
    onPlayEvent: (SportsEvent) -> Unit,
    onShowFavoriteOptions: (SportsFavoriteTarget) -> Unit
) {
    if (uiState.allEvents.isEmpty()) {
        LeagueEmptyState(stringResource(R.string.league_no_games_scheduled))
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 260.dp),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm),
        modifier = Modifier.fillMaxSize()
    ) {
        gridItems(uiState.allEvents, key = { it.id }) { event ->
            ScoreboardCard(
                event = event,
                onClick = { onPlayEvent(event) },
                onLongPress = { onShowFavoriteOptions(SportsFavoriteTarget.EventTarget(event)) }
            )
        }
    }
}

@Composable
private fun LeagueTeamsContent(
    uiState: LeagueDetailUiState,
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
                gridItems(uiState.teams, key = { it.id }) { team ->
                    TeamGridCard(
                        team = team,
                        isFavorite = team.id in uiState.favoriteTeamIds,
                        onClick = { onToggleFavoriteTeam(team.id) },
                        onToggleFavorite = { onToggleFavoriteTeam(team.id) },
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
                items(uiState.standings, key = { it.name }) { group ->
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
                        AsyncImage(
                            model = entry.team.badgeUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
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
private fun LeagueScheduleContent(
    uiState: LeagueDetailUiState,
    onPlayEvent: (SportsEvent) -> Unit,
    onShowFavoriteOptions: (SportsFavoriteTarget) -> Unit,
    onChangeWeek: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm)
        ) {
            Text(
                text = if (uiState.scheduleWeekOffset == 0)
                    stringResource(R.string.league_schedule_this_week)
                else
                    stringResource(R.string.league_schedule_week_label, uiState.scheduleWeekOffset + 1),
                style = MaterialTheme.typography.titleSmall,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.weight(1f)
            )
            WeekNavButton(label = "<", onClick = { onChangeWeek(-1) })
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
            WeekNavButton(label = ">", onClick = { onChangeWeek(1) })
        }

        when {
            uiState.isLoadingSchedule -> LeagueEmptyState(stringResource(R.string.league_loading))
            uiState.scheduleEvents.isEmpty() -> LeagueEmptyState(stringResource(R.string.league_no_games_scheduled))
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 260.dp),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                    contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm),
                    modifier = Modifier.fillMaxSize()
                ) {
                    gridItems(uiState.scheduleEvents, key = { it.id }) { event ->
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
                items(uiState.news, key = { it.id }) { article ->
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
private fun LeagueComingSoonContent(message: String) {
    Box(modifier = Modifier.fillMaxSize().padding(NuvioTheme.spacing.lg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.league_coming_soon_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = NuvioTheme.colors.TextPrimary
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
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
