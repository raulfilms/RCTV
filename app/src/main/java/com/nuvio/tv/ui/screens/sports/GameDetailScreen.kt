package com.nuvio.tv.ui.screens.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.SportsEventStatus
import com.nuvio.tv.domain.model.SportsGameStat
import com.nuvio.tv.ui.theme.NuvioTheme

/** Full-page detail view for a single game/match, tapped from any scoreboard card anywhere in Sports. */
@Composable
fun GameDetailScreen(
    modifier: Modifier = Modifier,
    viewModel: GameDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val event = viewModel.event
    val isOver = viewModel.isOver
    val recapRequester = remember { FocusRequester() }
    // A game that's over opens on Watch Recap.
    LaunchedEffect(isOver) { if (isOver) requestFocusSoon(recapRequester) }

    uiState.recap?.let { recap ->
        SportsRecapPlayer(
            title = recapTitle(event, uiState),
            videoUrl = recap.videoUrl,
            audioUrl = recap.audioUrl,
            isLoading = recap.isLoading,
            errorMessage = if (recap.notFound) stringResource(R.string.game_detail_recap_not_found) else null,
            onDismiss = viewModel::closeRecap,
            onRetry = viewModel::watchRecap
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = NuvioTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        item(key = "header") {
            GameDetailHeader(
                event = event,
                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg),
                // Golf has no recap to find (its highlights aren't one game's).
                recapButton = if (isOver && !event.isGolf()) {
                    {
                        WatchRecapButton(
                            onClick = viewModel::watchRecap,
                            modifier = Modifier.focusRequester(recapRequester)
                        )
                    }
                } else {
                    null
                }
            )
        }

        item(key = "actions") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NuvioTheme.spacing.lg)
            ) {
                GameActionButton(
                    label = stringResource(
                        if (uiState.isInLibrary) R.string.hero_remove_from_library else R.string.hero_add_to_library
                    ),
                    icon = if (uiState.isInLibrary) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder,
                    onClick = viewModel::toggleLibrary
                )
                event.homeTeamId?.let { homeTeamId ->
                    val isFavorite = homeTeamId in uiState.favoriteTeamIds
                    GameActionButton(
                        label = stringResource(
                            if (isFavorite) R.string.sports_remove_team_from_my_teams else R.string.sports_add_team_to_my_teams,
                            event.homeTeamName
                        ),
                        icon = if (isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                        onClick = { viewModel.toggleFavoriteTeam(homeTeamId) }
                    )
                }
                event.awayTeamId?.let { awayTeamId ->
                    val isFavorite = awayTeamId in uiState.favoriteTeamIds
                    GameActionButton(
                        label = stringResource(
                            if (isFavorite) R.string.sports_remove_team_from_my_teams else R.string.sports_add_team_to_my_teams,
                            event.awayTeamName
                        ),
                        icon = if (isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                        onClick = { viewModel.toggleFavoriteTeam(awayTeamId) }
                    )
                }
            }
        }

        if (event.isGolf()) {
            golfLeaderboardItems(uiState)
            return@LazyColumn
        }
        if (event.isRace()) {
            raceResultsItems(uiState, live = event.status == SportsEventStatus.LIVE)
            return@LazyColumn
        }
        if (event.isPpv()) {
            showDetailsItems(uiState)
            return@LazyColumn
        }

        item(key = "stats_title") {
            Text(
                text = stringResource(R.string.game_detail_stats_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg)
            )
        }

        when {
            uiState.isLoadingStats -> item(key = "stats_loading") {
                Text(
                    text = stringResource(R.string.game_detail_loading_stats),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg)
                )
            }
            uiState.stats.isEmpty() -> item(key = "stats_empty") {
                Text(
                    text = stringResource(R.string.game_detail_no_stats),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg)
                )
            }
            else -> {
                item(key = "stats_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.xs)
                    ) {
                        Text(
                            text = event.homeTeamName,
                            style = MaterialTheme.typography.labelSmall,
                            color = NuvioTheme.colors.TextTertiary,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Start
                        )
                        Spacer(modifier = Modifier.width(80.dp))
                        Text(
                            text = event.awayTeamName,
                            style = MaterialTheme.typography.labelSmall,
                            color = NuvioTheme.colors.TextTertiary,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.End
                        )
                    }
                }
                items(uiState.stats.distinctBy { it.label }, key = { it.label }) { stat ->
                    GameStatRow(stat = stat, modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg))
                }
            }
        }
    }
}

@Composable
private fun GameDetailHeader(
    event: com.nuvio.tv.domain.model.SportsEvent,
    modifier: Modifier = Modifier,
    /** Watch Recap, big under the score (a game that's over). */
    recapButton: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NuvioTheme.radii.panel))
            .background(NuvioTheme.colors.BackgroundElevated)
            .padding(NuvioTheme.spacing.xl)
    ) {
        Text(
            text = (event.leagueName ?: event.sportName).orEmpty().uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.colors.TextSecondary
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        if (event.isSoloEvent()) {
            // A golf tournament, a race, a PPV show: the tour's (series', promotion's) logo, its name, its leader.
            SoloEventContent(
                event = event,
                nameSize = 28.sp,
                textColor = NuvioTheme.colors.TextPrimary,
                secondaryColor = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.fillMaxWidth()
            )
        } else Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            GameDetailTeam(
                name = event.homeTeamName,
                badgeUrl = event.homeTeamBadgeUrl,
                score = event.homeScore,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "VS",
                style = MaterialTheme.typography.titleSmall,
                color = NuvioTheme.colors.TextTertiary,
                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md)
            )
            GameDetailTeam(
                name = event.awayTeamName,
                badgeUrl = event.awayTeamBadgeUrl,
                score = event.awayScore,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        Text(
            text = statusLine(event),
            style = MaterialTheme.typography.titleSmall,
            color = if (event.status == SportsEventStatus.LIVE) NuvioTheme.colors.Error else NuvioTheme.colors.TextPrimary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        event.venue?.takeIf { it.isNotBlank() }?.let { venue ->
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
            Text(
                text = venue,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
        if (recapButton != null) {
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { recapButton() }
        }
    }
}

/**
 * The highlights' title: the teams playing ("Tampa Bay Buccaneers vs Dallas Cowboys"; soccer home
 * first: "Fulham vs Chelsea"), and for the NFL the week ("• Week 5", "• Wild Card"...).
 */
@Composable
private fun recapTitle(event: com.nuvio.tv.domain.model.SportsEvent, uiState: GameDetailUiState): String {
    val home = uiState.homeTeamFullName ?: event.homeTeamName
    val away = uiState.awayTeamFullName ?: event.awayTeamName
    val soccer = event.leagueId?.startsWith("soccer") == true
    val matchup = if (soccer) "$home vs $away" else "$away vs $home"
    val week = uiState.week
    val weekLabel = if (event.leagueId == "football:nfl" && week != null) {
        when (uiState.seasonType) {
            1 -> stringResource(R.string.sports_recap_preseason_week, week)
            3 -> when (week) {
                1 -> stringResource(R.string.sports_recap_wild_card)
                2 -> stringResource(R.string.sports_recap_divisional)
                3 -> stringResource(R.string.sports_recap_conference)
                5 -> stringResource(R.string.sports_recap_super_bowl)
                else -> stringResource(R.string.sports_recap_playoffs)
            }
            else -> stringResource(R.string.sports_recap_week, week)
        }
    } else {
        null
    }
    return listOfNotNull(matchup, weekLabel).joinToString("  •  ")
}

/** YouTube's red. */
private val RecapRed = Color(0xFFFF0033)

/**
 * "Watch Recap": red like YouTube's, a compact pill centered under the score; the game's
 * highlights. The play icon and the words sit together in the very middle of the red (the text
 * without its font's extra top and bottom).
 */
@Composable
private fun WatchRecapButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    // RCTV: a compact pill in the middle, a little bigger (still in the middle) when focused.
    val shape = RoundedCornerShape(12.dp)
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier
            .height(44.dp)
            .widthIn(min = 210.dp),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = RecapRed,
            contentColor = Color.White,
            focusedContainerColor = RecapRed,
            focusedContentColor = Color.White
        ),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, Color.White),
                shape = shape
            )
        ),
        // Grows from its middle, so it stays centered.
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.1f)
    ) {
        Box(
            // As wide as its words (not the whole width of the page): it filled the width before.
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 22.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.game_detail_watch_recap).uppercase(),
                    color = Color.White,
                    maxLines = 1,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        lineHeight = 15.sp,
                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                            alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                            trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both
                        )
                    )
                )
            }
        }
    }
}

@Composable
private fun GameDetailTeam(name: String, badgeUrl: String?, score: Int?, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(NuvioTheme.colors.SurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (!badgeUrl.isNullOrBlank()) {
                SportsLogoImage(
                    url = badgeUrl,
                    background = SportsLogoDarkBackground,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.SportsBasketball,
                    contentDescription = null,
                    tint = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
        score?.let {
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
            Text(
                text = "$it",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = NuvioTheme.colors.TextPrimary
            )
        }
    }
}

private fun statusLine(event: com.nuvio.tv.domain.model.SportsEvent): String {
    if (event.isGolf()) return golfStatusText(event)
    if (event.status == SportsEventStatus.LIVE) {
        return event.statusDetail?.takeIf { it.isNotBlank() } ?: "Live"
    }
    val startTimeMs = event.startTimeMs
    if (startTimeMs != null) {
        if (event.startTimeTba()) return tbaStartLabel(startTimeMs)
        val format = java.text.SimpleDateFormat("EEE, MMM d • h:mm a", java.util.Locale.getDefault())
        return format.format(java.util.Date(startTimeMs))
    }
    return event.statusDetail ?: "Scheduled"
}

@Composable
private fun GameActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.TextPrimary
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
        Text(text = label, maxLines = 1)
    }
}

/**
 * One stat, both teams' values at the sides. It takes focus (lighter when focused), so Down goes
 * through the comparison and the page scrolls with it.
 */
@Composable
private fun GameStatRow(stat: SportsGameStat, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(horizontal = 12.dp, vertical = NuvioTheme.spacing.xs)
    ) {
        Text(
            text = stat.homeValue,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextPrimary,
            textAlign = TextAlign.Start,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stat.label,
            style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = stat.awayValue,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

/** A golf tournament's page: its leaderboard (position, player, to par, thru, rounds). */
private fun androidx.compose.foundation.lazy.LazyListScope.golfLeaderboardItems(uiState: GameDetailUiState) {
    item(key = "leaderboard_title") {
        Text(
            text = stringResource(R.string.game_detail_leaderboard),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg)
        )
    }
    when {
        uiState.isLoadingStats -> item(key = "leaderboard_loading") {
            Text(
                text = stringResource(R.string.game_detail_loading_leaderboard),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg)
            )
        }
        uiState.golfPlayers.isEmpty() -> item(key = "leaderboard_empty") {
            Text(
                text = stringResource(R.string.game_detail_no_leaderboard),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg)
            )
        }
        else -> {
            val columns = GolfColumns.of(uiState.golfPlayers)
            item(key = "leaderboard_header") {
                GolfLeaderboardHeader(columns = columns, modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg))
            }
            items(uiState.golfPlayers.distinctBy { "${it.id}_${it.name}" }, key = { "golfer_${it.id}_${it.name}" }) { player ->
                GolfLeaderboardRow(player = player, columns = columns, modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg))
            }
        }
    }
}

/** A race's page: its results (or the running order while it's on), car badge and driver a line. */
private fun androidx.compose.foundation.lazy.LazyListScope.raceResultsItems(uiState: GameDetailUiState, live: Boolean) {
    item(key = "results_title") {
        Text(
            text = stringResource(if (live) R.string.game_detail_running_order else R.string.game_detail_results),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg)
        )
    }
    when {
        uiState.isLoadingStats -> item(key = "results_loading") {
            Text(
                text = stringResource(R.string.game_detail_loading_results),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg)
            )
        }
        uiState.raceResults.isEmpty() -> item(key = "results_empty") {
            Text(
                text = stringResource(R.string.game_detail_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg)
            )
        }
        else -> {
            // Only the columns the race's source has (the running order has no points yet).
            val columns = RaceColumns.of(uiState.raceResults)
            item(key = "results_header") {
                RaceResultsHeader(columns = columns, modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg))
            }
            items(uiState.raceResults.distinctBy { "${it.position}_${it.carNumber}_${it.driver}" }, key = { "driver_${it.position}_${it.carNumber}_${it.driver}" }) { result ->
                RaceResultRow(result = result, columns = columns, modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg))
            }
        }
    }
}

/** A PPV show's page: its poster and what it's about, then its card (the fights or matches). */
private fun androidx.compose.foundation.lazy.LazyListScope.showDetailsItems(uiState: GameDetailUiState) {
    val details = uiState.showDetails
    if (uiState.isLoadingStats) {
        item(key = "show_loading") {
            Text(
                text = stringResource(R.string.game_detail_loading_card),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg)
            )
        }
        return
    }
    if (details != null && (details.posterUrl != null || details.imageUrl != null || !details.description.isNullOrBlank())) {
        item(key = "show_about") {
            ShowAbout(
                posterUrl = details.posterUrl,
                imageUrl = details.imageUrl,
                description = details.description,
                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg)
            )
        }
    }
    item(key = "show_card_title") {
        Text(
            text = stringResource(R.string.game_detail_card),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg)
        )
    }
    val card = details?.card.orEmpty()
    if (card.isEmpty()) {
        item(key = "show_card_empty") {
            Text(
                text = stringResource(R.string.game_detail_no_card),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg)
            )
        }
    } else {
        itemsIndexed(card, key = { index, _ -> "card_line_$index" }) { _, line ->
            ShowCardLine(line = line, modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg))
        }
    }
}
