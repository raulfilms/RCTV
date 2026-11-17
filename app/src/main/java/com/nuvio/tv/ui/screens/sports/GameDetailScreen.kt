package com.nuvio.tv.ui.screens.sports

import com.nuvio.tv.domain.model.hasTeam
import com.nuvio.tv.domain.model.SportsTeamKey
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
            onUnplayable = viewModel::recapUnplayable,
            onRetry = viewModel::watchRecap
        )
    }

    // RCTV (owner): which boxes are open (Team Comparison, each team's players' boxes).
    var openBoxes by remember { mutableStateOf(emptySet<String>()) }
    fun toggleBox(key: String) {
        openBoxes = if (key in openBoxes) openBoxes - key else openBoxes + key
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().gameGlassBackground(),
        contentPadding = PaddingValues(vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item(key = "header") {
            GameDetailHeader(
                event = event,
                modifier = Modifier.padding(horizontal = GlassPageSide),
                // Golf has no recap to find (its highlights aren't one game's).
                recapButton = if (isOver && !event.isGolf()) {
                    {
                        GlassRecapButton(
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
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GlassPageSide)
            ) {
                GlassPillButton(
                    label = stringResource(
                        if (uiState.isInLibrary) R.string.hero_remove_from_library else R.string.hero_add_to_library
                    ),
                    icon = if (uiState.isInLibrary) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder,
                    onClick = viewModel::toggleLibrary
                )
                event.homeTeamId?.let { homeTeamId ->
                    val isFavorite = uiState.favoriteTeamIds.hasTeam(event.leagueId, homeTeamId)
                    GlassPillButton(
                        label = stringResource(
                            if (isFavorite) R.string.sports_remove_team_from_my_teams else R.string.sports_add_team_to_my_teams,
                            event.homeTeamName
                        ),
                        icon = if (isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                        onClick = { viewModel.toggleFavoriteTeam(SportsTeamKey.of(event.leagueId, homeTeamId)) }
                    )
                }
                event.awayTeamId?.let { awayTeamId ->
                    val isFavorite = uiState.favoriteTeamIds.hasTeam(event.leagueId, awayTeamId)
                    GlassPillButton(
                        label = stringResource(
                            if (isFavorite) R.string.sports_remove_team_from_my_teams else R.string.sports_add_team_to_my_teams,
                            event.awayTeamName
                        ),
                        icon = if (isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                        onClick = { viewModel.toggleFavoriteTeam(SportsTeamKey.of(event.leagueId, awayTeamId)) }
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

        val stats = uiState.stats.distinctBy { it.label }
        when {
            uiState.isLoadingStats -> item(key = "stats_loading") {
                Text(
                    text = stringResource(R.string.game_detail_loading_stats),
                    fontSize = 16.sp,
                    color = GlassDim,
                    modifier = Modifier.padding(horizontal = GlassPageSide)
                )
            }
            stats.isEmpty() -> item(key = "stats_empty") {
                Text(
                    text = stringResource(R.string.game_detail_no_stats),
                    fontSize = 16.sp,
                    color = GlassDim,
                    modifier = Modifier.padding(horizontal = GlassPageSide)
                )
            }
            else -> item(key = "comparison") {
                GlassExpandablePanel(
                    title = stringResource(R.string.game_detail_stats_title),
                    expanded = "comparison" in openBoxes,
                    onToggle = { toggleBox("comparison") },
                    rowCount = stats.size,
                    modifier = Modifier.padding(horizontal = GlassPageSide),
                    subHeader = { ComparisonTeams(left = event.homeTeamName, right = event.awayTeamName) }
                ) { index -> ComparisonLine(stats[index]) }
            }
        }

        // RCTV (owner): each team's players, the header's left team first: one box a part of the game
        // (passing, rushing, receiving, defense...; the players in basketball), each opening whole.
        val boxes = uiState.boxScores.sortedBy { box ->
            when (box.team.id) {
                event.homeTeamId -> 0
                event.awayTeamId -> 1
                else -> 2
            }
        }
        boxes.forEach { box ->
            box.sections.filter { it.rows.isNotEmpty() }.forEachIndexed { sectionIndex, section ->
                val boxKey = "players_${box.team.id ?: box.team.name}_$sectionIndex"
                item(key = boxKey) {
                    GlassExpandablePanel(
                        title = listOf(box.team.name, section.title).filter { it.isNotBlank() }.joinToString("  ·  "),
                        expanded = boxKey in openBoxes,
                        onToggle = { toggleBox(boxKey) },
                        rowCount = section.rows.size,
                        modifier = Modifier.padding(horizontal = GlassPageSide),
                        leading = box.team.logoUrl?.takeIf { it.isNotBlank() }?.let { logo -> @Composable { GlassTeamLogo(logo) } },
                        subHeader = { BoxSectionHeader(section) }
                    ) { index -> BoxSectionLine(section, index) }
                }
            }
        }
    }
}

/** RCTV: the league's own logo, for the leagues ESPN keeps one at a known place. */
private fun leagueLogoUrl(leagueId: String?): String? {
    val league = leagueId?.substringAfter(':')?.takeIf { it.isNotBlank() } ?: return null
    return if (league in setOf("nba", "nfl", "mlb", "nhl", "wnba", "mls")) {
        "https://a.espncdn.com/i/teamlogos/leagues/500/$league.png"
    } else {
        null
    }
}

@Composable
private fun GameDetailHeader(
    event: com.nuvio.tv.domain.model.SportsEvent,
    modifier: Modifier = Modifier,
    /** Watch Recap, under when and where (a game that's over). */
    recapButton: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassPanel()
            .padding(horizontal = 26.dp, vertical = 22.dp)
    ) {
        // The league: its logo and its name, top left.
        Row(verticalAlignment = Alignment.CenterVertically) {
            leagueLogoUrl(event.leagueId)?.let { logo ->
                SportsLogoImage(url = logo, background = SportsLogoDarkBackground, contentDescription = null, modifier = Modifier.size(30.dp))
                Spacer(modifier = Modifier.width(10.dp))
            }
            Text(
                text = (event.leagueName ?: event.sportName).orEmpty(),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (event.isSoloEvent()) {
            // A golf tournament, a race, a PPV show: the tour's (series', promotion's) logo, its name, its leader.
            SoloEventContent(
                event = event,
                nameSize = 28.sp,
                textColor = Color.White,
                secondaryColor = GlassDim,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            // RCTV: "Hide scores" (Sports page).
            val scoresHidden = rememberScoresHidden()
            val homeScore = event.homeScore.takeUnless { scoresHidden }
            val awayScore = event.awayScore.takeUnless { scoresHidden }
            val hasScore = homeScore != null && awayScore != null
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                GameDetailTeam(name = event.homeTeamName, badgeUrl = event.homeTeamBadgeUrl, modifier = Modifier.weight(1f))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1.2f).padding(top = 18.dp)
                ) {
                    // VS, or the score once there's one.
                    Text(
                        text = if (hasScore) "$homeScore  –  $awayScore" else "VS",
                        fontSize = if (hasScore) 40.sp else 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = statusLine(event),
                        fontSize = 17.sp,
                        color = if (event.status == SportsEventStatus.LIVE) Color(0xFFFF5A5F) else GlassDim,
                        textAlign = TextAlign.Center
                    )
                    event.venue?.takeIf { it.isNotBlank() }?.let { venue ->
                        Text(text = venue, fontSize = 17.sp, color = GlassDim, textAlign = TextAlign.Center, maxLines = 1)
                    }
                }
                GameDetailTeam(name = event.awayTeamName, badgeUrl = event.awayTeamBadgeUrl, modifier = Modifier.weight(1f))
            }
        }
        if (recapButton != null) {
            Spacer(modifier = Modifier.height(18.dp))
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

@Composable
private fun GameDetailTeam(name: String, badgeUrl: String?, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(modifier = Modifier.size(84.dp), contentAlignment = Alignment.Center) {
            if (!badgeUrl.isNullOrBlank()) {
                SportsLogoImage(
                    url = badgeUrl,
                    background = SportsLogoDarkBackground,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.SportsBasketball,
                    contentDescription = null,
                    tint = GlassDim,
                    modifier = Modifier.size(44.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = name,
            fontSize = 19.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2
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
