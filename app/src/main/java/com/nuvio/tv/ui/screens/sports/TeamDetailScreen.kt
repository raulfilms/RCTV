@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.nuvio.tv.ui.screens.sports

import android.content.Intent
import android.view.KeyEvent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.SportsAthlete
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import com.nuvio.tv.domain.model.SportsNewsArticle
import com.nuvio.tv.domain.model.SportsTeam
import com.nuvio.tv.domain.model.SportsTeamGame
import com.nuvio.tv.domain.model.SportsTeamProfile
import com.nuvio.tv.ui.components.AppleTvColors
import com.nuvio.tv.ui.components.AppleTvSpacing
import com.nuvio.tv.ui.util.longPressable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PageMargin: Dp = AppleTvSpacing.SafeX
private val GameCardHeight = 208.dp
private val HeaderPlayerHeight = 236.dp

/** A line of the schedule: a game, or a week the team doesn't play (NFL bye). */
private sealed interface ScheduleRow {
    val key: String

    data class Game(val index: Int, val game: SportsTeamGame) : ScheduleRow {
        override val key: String get() = game.event.id
    }

    data class Bye(val week: Int) : ScheduleRow {
        override val key: String get() = "bye_$week"
    }
}

/**
 * A team's page, the same for every team of every league: the logo, the city in gray over the name
 * in white (soccer clubs: the whole name in white), the record and standing, Add to Library, the
 * team's star; then Schedule, Roster, Stats, News, Videos and History. The schedule opens on the game
 * (or week) on now: down are the ones to come, up the ones played.
 */
@Composable
fun TeamDetailScreen(
    onOpenGame: (SportsEvent) -> Unit,
    onOpenTeam: (SportsTeam) -> Unit,
    viewModel: TeamDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val profile = uiState.profile
    val teamColor = sportsTeamColor(profile?.color ?: viewModel.initialColor, viewModel.teamId)

    var selectedTabName by rememberSaveable { mutableStateOf(TeamPageTab.SCHEDULE.name) }
    val selectedTab = TeamPageTab.entries.firstOrNull { it.name == selectedTabName } ?: TeamPageTab.SCHEDULE
    var contentFocused by remember { mutableStateOf(false) }
    var optionsEvent by remember { mutableStateOf<SportsEvent?>(null) }
    // The header makes room for the lists, except the schedule's (one game at a time under it).
    val headerVisible = !(contentFocused && selectedTab != TeamPageTab.SCHEDULE)

    val libraryRequester = remember { FocusRequester() }
    val tabRequesters = remember { TeamPageTab.entries.associateWith { FocusRequester() } }
    val scheduleState = rememberLazyListState()
    val gameRequesters = remember { mutableMapOf<String, FocusRequester>() }
    var positioned by rememberSaveable { mutableStateOf(false) }
    var lastFocusedGameId by rememberSaveable { mutableStateOf<String?>(null) }
    var libraryFocused by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTab) { viewModel.onTabShown(selectedTab) }
    // Something to focus while the schedule loads.
    LaunchedEffect(Unit) { if (!positioned) requestFocusSoon(libraryRequester) }

    val rows = remember(uiState.games) { scheduleRowsOf(uiState.games) }

    Box(modifier = Modifier.fillMaxSize().background(AppleTvColors.Background)) {
        TeamBackdrop(color = teamColor, venueImageUrl = profile?.venueImageUrl)

        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = headerVisible,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                TeamHeader(
                    profile = profile,
                    initialName = viewModel.initialName,
                    initialBadgeUrl = viewModel.initialBadgeUrl,
                    isSoccer = viewModel.isSoccer,
                    sportPath = viewModel.leagueId.substringBefore(':'),
                    featuredPlayer = uiState.featuredPlayer,
                    isInLibrary = uiState.isInLibrary,
                    libraryRequester = libraryRequester,
                    onLibraryFocus = { libraryFocused = it },
                    onToggleLibrary = viewModel::toggleLibrary
                )
            }

            TeamTabsRow(
                selected = selectedTab,
                requesters = tabRequesters,
                onSelect = { tab -> selectedTabName = tab.name }
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onFocusChanged { contentFocused = it.hasFocus }
            ) {
                when (selectedTab) {
                    TeamPageTab.SCHEDULE -> ScheduleTab(
                        uiState = uiState,
                        rows = rows,
                        listState = scheduleState,
                        leagueLabel = viewModel.leagueLabel,
                        ownTeamId = viewModel.teamId,
                        requesterFor = { id -> gameRequesters.getOrPut(id) { FocusRequester() } },
                        onGameFocused = { id -> lastFocusedGameId = id },
                        onShowGame = viewModel::loadGamePlayers,
                        onOpenGame = onOpenGame,
                        onLongPressGame = { optionsEvent = it },
                        // Left / Right on a game: up to the tabs (Left: Schedule; Right: the next tab, Roster).
                        onSideFromGame = { toRight ->
                            val tab = if (toRight) TeamPageTab.ROSTER else TeamPageTab.SCHEDULE
                            runCatching { tabRequesters.getValue(tab).requestFocus() }
                        },
                        onOpenTeam = onOpenTeam
                    )
                    TeamPageTab.ROSTER -> RosterTab(uiState = uiState, teamColor = teamColor)
                    TeamPageTab.STATS -> StatsTab(uiState = uiState)
                    TeamPageTab.NEWS -> NewsTab(uiState = uiState)
                    TeamPageTab.VIDEOS -> TeamTabNote(stringResource(R.string.sports_team_videos_soon))
                    TeamPageTab.HISTORY -> HistoryTab(uiState = uiState, leagueLabel = viewModel.leagueLabel)
                }
            }
        }
    }

    // The schedule loaded: open it on the game on now. Back on this page (from a game), focus goes
    // back to the game that was opened.
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.isLoadingSchedule, rows, selectedTab) {
        if (uiState.isLoadingSchedule || selectedTab != TeamPageTab.SCHEDULE) return@LaunchedEffect
        val currentId = uiState.games.getOrNull(uiState.currentGameIndex)?.event?.id
        if (!positioned) {
            positioned = true
            restored = true
            val targetId = currentId ?: return@LaunchedEffect
            val rowIndex = rows.indexOfFirst { it.key == targetId }.coerceAtLeast(0)
            runCatching { scheduleState.scrollToItem(rowIndex) }
            // Only from the library button (where focus waits while it loads), not from where the person went.
            if (libraryFocused) requestFocusSoon(gameRequesters.getOrPut(targetId) { FocusRequester() })
        } else if (!restored) {
            restored = true
            val lastId = lastFocusedGameId?.takeIf { id -> rows.any { it.key == id } }
            if (lastId != null) {
                requestFocusSoon(gameRequesters.getOrPut(lastId) { FocusRequester() })
            } else {
                requestFocusSoon(libraryRequester)
            }
        }
    }

    optionsEvent?.let { event ->
        SportsFavoriteOptionsDialog(
            target = SportsFavoriteTarget.EventTarget(event),
            favoriteTeamIds = uiState.favoriteTeamIds,
            favoriteSportNames = uiState.favoriteSportNames,
            onToggleTeam = viewModel::toggleFavoriteTeam,
            onToggleSport = viewModel::toggleFavoriteSport,
            onDismiss = { optionsEvent = null },
            isEventInLibrary = event.id in uiState.libraryEventIds,
            onToggleLibrary = { viewModel.toggleLibraryEvent(event) }
        )
    }
}

/** The schedule's lines: the games, with the NFL's bye weeks between them. */
private fun scheduleRowsOf(games: List<SportsTeamGame>): List<ScheduleRow> {
    val rows = ArrayList<ScheduleRow>(games.size + 2)
    var lastRegularWeek: Int? = null
    games.forEachIndexed { index, game ->
        val week = game.weekNumber
        if (game.seasonType == 2 && week != null) {
            val previous = lastRegularWeek
            if (previous != null && week > previous + 1) {
                for (bye in (previous + 1) until week) rows += ScheduleRow.Bye(bye)
            }
            lastRegularWeek = week
        }
        rows += ScheduleRow.Game(index, game)
    }
    return rows
}

// ------------------------------------------------------------------ Backdrop and header

/** The page's top: the team's stadium dimmed, its color, and lights like a stadium's at night. */
@Composable
private fun TeamBackdrop(color: Color, venueImageUrl: String?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
    ) {
        if (!venueImageUrl.isNullOrBlank()) {
            AsyncImage(
                model = venueImageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.22f)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0.18f), Color.Transparent),
                        center = Offset(220f, 40f),
                        radius = 1300f
                    )
                )
        )
        // Stadium lights.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val lights = listOf(0.12f to 0.06f, 0.24f to 0.03f, 0.46f to 0.05f, 0.63f to 0.02f, 0.78f to 0.07f, 0.92f to 0.04f)
            lights.forEach { (x, y) ->
                val center = Offset(size.width * x, size.height * y)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.06f), Color.Transparent),
                        center = center,
                        radius = 90f
                    ),
                    radius = 90f,
                    center = center
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.55f to AppleTvColors.Background.copy(alpha = 0.35f),
                            1f to AppleTvColors.Background
                        )
                    )
                )
        )
    }
}

@Composable
private fun TeamHeader(
    profile: SportsTeamProfile?,
    initialName: String,
    initialBadgeUrl: String?,
    isSoccer: Boolean,
    sportPath: String,
    featuredPlayer: SportsAthlete?,
    isInLibrary: Boolean,
    libraryRequester: FocusRequester,
    onLibraryFocus: (Boolean) -> Unit,
    onToggleLibrary: () -> Unit
) {
    val displayName = profile?.displayName ?: initialName
    // US teams: the city in gray, the team's name in white. Soccer clubs: the whole name in white.
    val city = if (isSoccer) null else profile?.location ?: displayName.substringBeforeLast(' ', "").takeIf { it.isNotBlank() }
    val name = if (isSoccer) displayName else profile?.nickname ?: displayName.substringAfterLast(' ')

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HeaderPlayerHeight)
    ) {
        // The team's star, on the right.
        val headshot = featuredPlayer?.headshotUrl
        if (!headshot.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp)
                    .height(HeaderPlayerHeight - 6.dp)
                    .aspectRatio(350f / 254f)
            ) {
                AsyncImage(
                    model = headshot,
                    contentDescription = featuredPlayer?.name,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomCenter,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(46.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, AppleTvColors.Background.copy(alpha = 0.9f))))
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = PageMargin, top = 14.dp, end = 340.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TeamLogo(url = profile?.logoUrl ?: initialBadgeUrl, size = 128.dp)
            Spacer(modifier = Modifier.width(26.dp))
            Column {
                if (!city.isNullOrBlank()) {
                    Text(
                        text = city.uppercase(),
                        color = Color.White.copy(alpha = 0.58f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = name.uppercase(),
                    color = Color.White,
                    fontSize = when {
                        name.length > 22 -> 28.sp
                        name.length > 15 -> 34.sp
                        else -> 44.sp
                    },
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(10.dp))
                TeamStatsLine(profile = profile, isSoccer = isSoccer, sportPath = sportPath)
                Spacer(modifier = Modifier.height(16.dp))
                HeroPillButton(
                    text = stringResource(if (isInLibrary) R.string.livetv_details_in_library else R.string.hero_add_to_library),
                    icon = if (isInLibrary) Icons.Default.Check else Icons.Default.Add,
                    primary = !isInLibrary,
                    onClick = onToggleLibrary,
                    modifier = Modifier
                        .focusRequester(libraryRequester)
                        .onFocusChanged { onLibraryFocus(it.isFocused) }
                )
            }
        }
    }
}

/** Record, conference rank and division rank (soccer: record, league rank and points). */
@Composable
private fun TeamStatsLine(profile: SportsTeamProfile?, isSoccer: Boolean, sportPath: String) {
    val standing = parseStanding(profile?.standingSummary)
    val items = ArrayList<Pair<String, String>>()
    items += stringResource(R.string.sports_team_record) to (profile?.recordSummary ?: "—")
    if (isSoccer) {
        standing?.let { items += stringResource(R.string.sports_team_league_rank) to ordinal(it.rank) }
        profile?.points?.let { items += stringResource(R.string.sports_team_points) to it.toString() }
    } else {
        val conference = standing?.let { conferenceOf(sportPath, it.group) }
        val seed = profile?.playoffSeed
        if (conference != null && seed != null) {
            items += stringResource(R.string.sports_team_conference_rank) to "$conference ${ordinal(seed)}"
        }
        standing?.let { items += stringResource(R.string.sports_team_division_rank) to "${it.group} ${ordinal(it.rank)}" }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        items.forEachIndexed { index, (label, value) ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 14.dp)
                        .width(1.dp)
                        .height(34.dp)
                        .background(Color.White.copy(alpha = 0.22f))
                )
            }
            Column {
                Text(text = label, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1)
                Text(text = value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

private data class Standing(val rank: Int, val group: String)

/** "1st in AFC East" → 1, "AFC East"; "1st in Pacific Division" → 1, "Pacific". */
private fun parseStanding(summary: String?): Standing? {
    if (summary.isNullOrBlank()) return null
    val match = Regex("""^\s*(\d+)\D*\s+in\s+(.+?)\s*$""", RegexOption.IGNORE_CASE).find(summary) ?: return null
    val rank = match.groupValues[1].toIntOrNull() ?: return null
    val group = match.groupValues[2].removeSuffix(" Division").removeSuffix(" division").trim()
    return Standing(rank, group)
}

/** A division's conference: AFC/NFC, AL/NL, East/West. */
private fun conferenceOf(sportPath: String, division: String): String? {
    val d = division.lowercase(Locale.US)
    return when {
        d.startsWith("afc") -> "AFC"
        d.startsWith("nfc") -> "NFC"
        d.startsWith("al ") || d.startsWith("american") -> "AL"
        d.startsWith("nl ") || d.startsWith("national") -> "NL"
        sportPath == "basketball" && (d.startsWith("atlantic") || d.startsWith("central") || d.startsWith("southeast")) -> "East"
        sportPath == "basketball" && (d.startsWith("northwest") || d.startsWith("pacific") || d.startsWith("southwest")) -> "West"
        sportPath == "hockey" && (d.startsWith("atlantic") || d.startsWith("metropolitan")) -> "East"
        sportPath == "hockey" && (d.startsWith("central") || d.startsWith("pacific")) -> "West"
        else -> null
    }
}

/** 1st, 2nd, 3rd... (Spanish: 1.º, 2.º...). */
private fun ordinal(n: Int): String {
    if (Locale.getDefault().language == "es") return "$n.º"
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}

// ------------------------------------------------------------------ Tabs

@Composable
private fun TeamTabsRow(
    selected: TeamPageTab,
    requesters: Map<TeamPageTab, FocusRequester>,
    onSelect: (TeamPageTab) -> Unit
) {
    Row(
        modifier = Modifier
            .padding(start = PageMargin, end = PageMargin, top = 4.dp, bottom = 14.dp)
            .focusRestorer { requesters[selected] ?: FocusRequester.Default },
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TeamPageTab.entries.forEach { tab ->
            val isSelected = tab == selected
            val shape = RoundedCornerShape(8.dp)
            Surface(
                onClick = { onSelect(tab) },
                modifier = Modifier
                    .height(34.dp)
                    .focusRequester(requesters.getValue(tab))
                    // Like Apple TV's tabs: the one focused is the one shown.
                    .onFocusChanged { if (it.isFocused && !isSelected) onSelect(tab) },
                shape = ClickableSurfaceDefaults.shape(shape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isSelected) Color.White else Color.White.copy(alpha = 0.1f),
                    contentColor = if (isSelected) Color.Black else Color.White,
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color.Black
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f)
            ) {
                Box(modifier = Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(tabLabel(tab)).uppercase(),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                }
            }
        }
    }
}

private fun tabLabel(tab: TeamPageTab): Int = when (tab) {
    TeamPageTab.SCHEDULE -> R.string.sports_team_tab_schedule
    TeamPageTab.ROSTER -> R.string.sports_team_tab_roster
    TeamPageTab.STATS -> R.string.sports_team_tab_stats
    TeamPageTab.NEWS -> R.string.sports_team_tab_news
    TeamPageTab.VIDEOS -> R.string.sports_team_tab_videos
    TeamPageTab.HISTORY -> R.string.sports_team_tab_history
}

// ------------------------------------------------------------------ Schedule

@Composable
private fun ScheduleTab(
    uiState: TeamDetailUiState,
    rows: List<ScheduleRow>,
    listState: LazyListState,
    leagueLabel: String,
    ownTeamId: String,
    requesterFor: (String) -> FocusRequester,
    onGameFocused: (String) -> Unit,
    onShowGame: (SportsTeamGame) -> Unit,
    onOpenGame: (SportsEvent) -> Unit,
    onLongPressGame: (SportsEvent) -> Unit,
    onSideFromGame: (toRight: Boolean) -> Unit,
    onOpenTeam: (SportsTeam) -> Unit
) {
    if (uiState.isLoadingSchedule) {
        TeamTabNote(stringResource(R.string.sports_loading))
        return
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = PageMargin, end = PageMargin, top = 2.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (rows.isEmpty()) {
            item(key = "empty") { TeamTabNoteInline(stringResource(R.string.sports_team_no_games)) }
        }
        items(rows, key = { it.key }) { row ->
            when (row) {
                is ScheduleRow.Bye -> ScheduleLabel(
                    text = stringResource(R.string.sports_team_week, row.week) + " · " + stringResource(R.string.sports_team_bye)
                )
                is ScheduleRow.Game -> {
                    val game = row.game
                    LaunchedEffect(game.event.id) { onShowGame(game) }
                    Column {
                        ScheduleLabel(text = gameLabel(game))
                        Spacer(modifier = Modifier.height(10.dp))
                        TeamGameCard(
                            game = game,
                            label = gameLabel(game),
                            players = uiState.gamePlayers[game.event.id] ?: game.leaders,
                            isSaved = game.event.id in uiState.libraryEventIds,
                            onClick = { onOpenGame(game.event) },
                            onLongPress = { onLongPressGame(game.event) },
                            modifier = Modifier
                                .focusRequester(requesterFor(game.event.id))
                                .onFocusChanged { if (it.isFocused) onGameFocused(game.event.id) }
                                .onPreviewKeyEvent { event ->
                                    val native = event.nativeKeyEvent
                                    if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                                    when (native.keyCode) {
                                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                                            onSideFromGame(false)
                                            true
                                        }
                                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                            onSideFromGame(true)
                                            true
                                        }
                                        else -> false
                                    }
                                }
                        )
                    }
                }
            }
        }
        val others = uiState.leagueTeams.filter { it.id != ownTeamId }
        if (others.isNotEmpty()) {
            item(key = "all_teams") {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    ScheduleLabel(text = stringResource(R.string.sports_team_all_teams, leagueLabel))
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(AppleTvSpacing.GridGap),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(others, key = { it.id }) { team ->
                            SportsLogoTile(
                                name = team.name,
                                logoUrl = team.badgeUrl,
                                color = sportsTeamColor(team.color, team.id),
                                isFavorite = team.id in uiState.favoriteTeamIds,
                                onClick = { onOpenTeam(team) },
                                onLongPress = null
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = Color.White,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        maxLines = 1
    )
}

/** "Week 5", "Wild Card", or the game's day ("Friday, Oct 16") where games aren't in weeks. */
@Composable
private fun gameLabel(game: SportsTeamGame): String {
    val week = game.weekNumber
    if (game.seasonType == 2 && week != null) return stringResource(R.string.sports_team_week, week)
    game.weekLabel?.let { return it }
    val start = game.event.startTimeMs ?: return game.seasonTypeLabel.orEmpty()
    return SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date(start))
}

/**
 * A game: the teams' colors behind it, the away team on the left and the home team on the right
 * (logo, short name, score), how it's going in the middle (Final, the time, the channel), their
 * players at the corners, and Recap / Watch Live / Details.
 */
@Composable
private fun TeamGameCard(
    game: SportsTeamGame,
    label: String,
    players: Map<String, List<SportsAthlete>>,
    isSaved: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val event = game.event
    val awayColor = sportsTeamColor(event.awayTeamColor, event.awayTeamId ?: event.awayTeamName)
    val homeColor = sportsTeamColor(event.homeTeamColor, event.homeTeamId ?: event.homeTeamName)
    val shape = RoundedCornerShape(14.dp)
    val isLive = event.status == SportsEventStatus.LIVE
    val isFinal = event.status == SportsEventStatus.FINISHED

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(GameCardHeight)
            .longPressable(onLongPress),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to lerp(awayColor, Color.Black, 0.15f),
                            0.42f to lerp(awayColor, Color.Black, 0.62f),
                            0.58f to lerp(homeColor, Color.Black, 0.62f),
                            1f to lerp(homeColor, Color.Black, 0.15f)
                        )
                    )
                )
        ) {
            // The players, at the bottom corners (behind the rest).
            CardPlayers(players[event.awayTeamId].orEmpty(), Alignment.BottomStart)
            CardPlayers(players[event.homeTeamId].orEmpty(), Alignment.BottomEnd)

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLive) {
                    SportsLiveBadge()
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Box(modifier = Modifier.size(6.dp).clip(RoundedCornerShape(1.dp)).background(Color.White.copy(alpha = 0.7f)))
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(text = label.uppercase(), color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Icon(
                imageVector = if (isSaved) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 14.dp)
                    .size(18.dp)
            )

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "${event.awayTeamName} ${stringResource(R.string.sports_vs).lowercase()} ${event.homeTeamName}",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 520.dp)
                )
                // The competition: the league, the Champions League, a cup...
                if (game.competitionName != null || game.competitionLogoUrl != null) {
                    Spacer(modifier = Modifier.height(5.dp))
                    CompetitionChip(name = game.competitionName, logoUrl = game.competitionLogoUrl)
                    Spacer(modifier = Modifier.height(6.dp))
                } else {
                    Spacer(modifier = Modifier.height(10.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val awayWon = isFinal && (event.awayScore ?: 0) > (event.homeScore ?: 0)
                    val homeWon = isFinal && (event.homeScore ?: 0) > (event.awayScore ?: 0)
                    TeamLogo(url = event.awayTeamBadgeUrl, size = 62.dp)
                    Spacer(modifier = Modifier.width(16.dp))
                    CardTeamScore(
                        abbreviation = event.awayTeamAbbreviation ?: event.awayTeamName.take(3).uppercase(),
                        score = event.awayScore,
                        dimmed = homeWon,
                        scoreFirst = false
                    )
                    CardMiddle(event = event, broadcast = game.broadcast)
                    CardTeamScore(
                        abbreviation = event.homeTeamAbbreviation ?: event.homeTeamName.take(3).uppercase(),
                        score = event.homeScore,
                        dimmed = awayWon,
                        scoreFirst = true
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    TeamLogo(url = event.homeTeamBadgeUrl, size = 62.dp)
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color.White)
                    .padding(horizontal = 18.dp, vertical = 7.dp)
            ) {
                Text(
                    text = stringResource(
                        when {
                            isLive -> R.string.sports_watch_live
                            isFinal -> R.string.sports_team_recap
                            else -> R.string.sports_view_details
                        }
                    ).uppercase(),
                    color = Color.Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
            }
        }
    }
}

/** The competition a game is in: its logo and its name, on a dark pill. */
@Composable
private fun CompetitionChip(name: String?, logoUrl: String?) {
    Row(
        modifier = Modifier
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!logoUrl.isNullOrBlank()) {
            AsyncImage(
                model = logoUrl,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(16.dp)
            )
            if (!name.isNullOrBlank()) Spacer(modifier = Modifier.width(6.dp))
        }
        if (!name.isNullOrBlank()) {
            Text(
                text = name,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** "JET 21" / "24 BUF": the short name, and the score once there is one. */
@Composable
private fun CardTeamScore(abbreviation: String, score: Int?, dimmed: Boolean, scoreFirst: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val abbreviationText: @Composable () -> Unit = {
            Text(text = abbreviation, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        val scoreText: @Composable () -> Unit = {
            Text(
                text = score?.toString().orEmpty(),
                color = Color.White.copy(alpha = if (dimmed) 0.6f else 1f),
                fontSize = 38.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1
            )
        }
        if (score == null) {
            abbreviationText()
        } else if (scoreFirst) {
            scoreText()
            Spacer(modifier = Modifier.width(12.dp))
            abbreviationText()
        } else {
            abbreviationText()
            Spacer(modifier = Modifier.width(12.dp))
            scoreText()
        }
    }
}

/** Between the teams: Final (or the clock, or the day), then the time and the channel. */
@Composable
private fun CardMiddle(event: SportsEvent, broadcast: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(modifier = Modifier.width(16.dp))
        Box(modifier = Modifier.width(1.dp).height(46.dp).background(Color.White.copy(alpha = 0.35f)))
        Column(
            modifier = Modifier.width(148.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val start = event.startTimeMs
            val (first, second) = when (event.status) {
                SportsEventStatus.LIVE -> sportsGameStatus(event) to null
                SportsEventStatus.FINISHED -> (event.statusDetail ?: stringResource(R.string.sports_final)) to
                    start?.let { SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(it)) }
                SportsEventStatus.UPCOMING -> (start?.let { SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(it)) } ?: "") to
                    start?.let { SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(it)) }
            }
            Text(text = first, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center)
            if (!second.isNullOrBlank()) {
                Text(text = second, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 1)
            }
            if (!broadcast.isNullOrBlank()) {
                Text(text = broadcast, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(modifier = Modifier.width(1.dp).height(46.dp).background(Color.White.copy(alpha = 0.35f)))
        Spacer(modifier = Modifier.width(16.dp))
    }
}

/** Two of a team's players, cut out, at a bottom corner of the game card (one partly behind the other). */
@Composable
private fun BoxScope.CardPlayers(players: List<SportsAthlete>, alignment: Alignment) {
    val shown = players.filter { !it.headshotUrl.isNullOrBlank() }.take(2)
    if (shown.isEmpty()) return
    val photoHeight = 84.dp
    val photoWidth = photoHeight * (350f / 254f)
    val step = 72.dp
    val atStart = alignment == Alignment.BottomStart
    Box(
        modifier = Modifier
            .align(alignment)
            .padding(horizontal = 4.dp)
            .width(photoWidth + step * (shown.size - 1))
            .height(photoHeight)
    ) {
        // The outer one first, so the one nearer the middle is in front.
        shown.asReversed().forEachIndexed { reversedIndex, athlete ->
            val index = shown.size - 1 - reversedIndex
            val x = if (atStart) step * index else step * (shown.size - 1 - index)
            AsyncImage(
                model = athlete.headshotUrl,
                contentDescription = athlete.name,
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomCenter,
                modifier = Modifier
                    .offset(x = x)
                    .width(photoWidth)
                    .height(photoHeight)
            )
        }
    }
}

// ------------------------------------------------------------------ Roster

@Composable
private fun RosterTab(uiState: TeamDetailUiState, teamColor: Color) {
    val roster = uiState.roster
    when {
        roster.isLoading || !roster.loaded -> TeamTabNote(stringResource(R.string.sports_loading))
        roster.items.isEmpty() -> TeamTabNote(stringResource(R.string.sports_team_roster_empty))
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            contentPadding = PaddingValues(start = PageMargin, end = PageMargin, top = 4.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            val groups = roster.items.groupBy { it.group }
            groups.forEach { (group, athletes) ->
                if (group != null && groups.size > 1) {
                    item(key = "group_$group", span = { GridItemSpan(maxLineSpan) }) { ScheduleLabel(text = group) }
                }
                items(athletes.distinctBy { it.id }, key = { "${group}_${it.id}" }) { athlete -> PlayerCard(athlete = athlete, color = teamColor) }
            }
        }
    }
}

/** A player: the photo on the team's color, the number behind it, the name and position under it. */
@Composable
private fun PlayerCard(athlete: SportsAthlete, color: Color) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = {},
        modifier = Modifier.fillMaxWidth().height(206.dp),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppleTvColors.Surface2,
            focusedContainerColor = AppleTvColors.Surface2
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Brush.verticalGradient(listOf(lerp(color, Color.White, 0.08f), lerp(color, Color.Black, 0.45f))))
            ) {
                athlete.jersey?.let { number ->
                    Text(
                        text = number,
                        color = Color.White.copy(alpha = 0.18f),
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp)
                    )
                }
                if (!athlete.headshotUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = athlete.headshotUrl,
                        contentDescription = athlete.name,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomCenter,
                        modifier = Modifier.fillMaxSize().padding(top = 10.dp)
                    )
                }
            }
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = athlete.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(athlete.position, athlete.jersey?.let { "#$it" }).joinToString(" · "),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
        }
    }
}

// ------------------------------------------------------------------ Stats

@Composable
private fun StatsTab(uiState: TeamDetailUiState) {
    val stats = uiState.stats
    when {
        stats.isLoading || !stats.loaded -> TeamTabNote(stringResource(R.string.sports_loading))
        stats.items.isEmpty() -> TeamTabNote(stringResource(R.string.sports_team_stats_empty))
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(start = PageMargin, end = PageMargin, top = 4.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            stats.items.forEachIndexed { categoryIndex, category ->
                item(key = "cat_$categoryIndex", span = { GridItemSpan(maxLineSpan) }) {
                    Box(modifier = Modifier.padding(top = 8.dp)) { ScheduleLabel(text = category.name) }
                }
                items(category.stats.take(8).withIndex().toList(), key = { "stat_${categoryIndex}_${it.index}" }) { (_, stat) ->
                    val shape = RoundedCornerShape(10.dp)
                    Surface(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth().height(78.dp),
                        shape = ClickableSurfaceDefaults.shape(shape),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = AppleTvColors.Surface2,
                            focusedContainerColor = Color.White.copy(alpha = 0.18f)
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f)
                    ) {
                        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(text = stat.value, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(
                                text = stat.label,
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ News

@Composable
private fun NewsTab(uiState: TeamDetailUiState) {
    val news = uiState.news
    when {
        news.isLoading || !news.loaded -> TeamTabNote(stringResource(R.string.sports_loading))
        news.items.isEmpty() -> TeamTabNote(stringResource(R.string.sports_team_news_empty))
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = PageMargin, end = PageMargin, top = 4.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(AppleTvSpacing.GridGap),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(news.items.distinctBy { it.id }, key = { it.id }) { article -> TeamNewsCard(article) }
        }
    }
}

@Composable
private fun TeamNewsCard(article: SportsNewsArticle) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    Column {
        Surface(
            onClick = {
                val link = article.link ?: return@Surface
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
            },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(containerColor = AppleTvColors.Surface2, focusedContainerColor = AppleTvColors.Surface2),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f)
        ) {
            if (!article.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = article.imageUrl,
                    contentDescription = article.headline,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = article.headline,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        article.publishedMs?.let { published ->
            Text(
                text = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(published)),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp
            )
        }
    }
}

// ------------------------------------------------------------------ History

@Composable
private fun HistoryTab(uiState: TeamDetailUiState, leagueLabel: String) {
    val profile = uiState.profile
    val history = uiState.history
    if (uiState.isLoadingHistory || (!uiState.historyLoaded && history == null)) {
        TeamTabNote(stringResource(R.string.sports_loading))
        return
    }
    val facts = buildList {
        profile?.venueName?.let { venue ->
            add(stringResource(R.string.sports_team_venue) to listOfNotNull(venue, profile?.venueCity).joinToString(" · "))
        }
        add(stringResource(R.string.sports_team_league) to leagueLabel)
        profile?.standingSummary?.let { add(stringResource(R.string.sports_team_standing) to it) }
        profile?.recordSummary?.let { add(stringResource(R.string.sports_team_record) to it) }
    }
    LazyColumn(
        contentPadding = PaddingValues(start = PageMargin, end = PageMargin, top = 4.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "summary") {
            val shape = RoundedCornerShape(14.dp)
            Surface(
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
                shape = ClickableSurfaceDefaults.shape(shape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = AppleTvColors.Surface2,
                    focusedContainerColor = AppleTvColors.Surface2
                ),
                border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.7f)), shape = shape)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                    val image = history?.imageUrl ?: profile?.logoUrl
                    if (!image.isNullOrBlank()) {
                        AsyncImage(
                            model = image,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.width(220.dp).height(160.dp)
                        )
                        Spacer(modifier = Modifier.width(22.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = history?.title ?: profile?.displayName.orEmpty(),
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = history?.summary ?: stringResource(R.string.sports_team_history_empty),
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 16.sp,
                            lineHeight = 23.sp
                        )
                    }
                }
            }
        }
        item(key = "facts") {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                facts.forEach { (label, value) ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AppleTvColors.Surface2)
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text(text = label, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1)
                        Text(
                            text = value,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Notes

/** A tab with nothing to show (yet): a line of text. */
@Composable
private fun TeamTabNote(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(start = PageMargin, end = PageMargin, top = 12.dp)) {
        TeamTabNoteInline(text)
    }
}

@Composable
private fun TeamTabNoteInline(text: String) {
    Text(text = text, color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)
}
