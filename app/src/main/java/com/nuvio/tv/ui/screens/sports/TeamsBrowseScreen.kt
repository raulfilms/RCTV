@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.SportsTeam
import com.nuvio.tv.ui.components.AppleTvColors
import com.nuvio.tv.ui.components.AppleTvSpacing

/** Team tiles across the page: as many as the Leagues page shows. */
private const val TEAM_COLUMNS = 4
private val PageSideMargin: Dp = AppleTvSpacing.SafeX
private val FavoriteStar = Color(0xFFFFC53D)

/**
 * The Teams page: a big "TEAMS" banner with a collage of team logos, the leagues to filter by, My
 * Teams, then every league's teams under its name — in rows going down the page. Choosing a team
 * opens its page; holding OK opens its options (My Teams).
 */
@Composable
fun TeamsBrowseScreen(
    modifier: Modifier = Modifier,
    onTeamClick: (SportsTeam) -> Unit = {},
    viewModel: TeamsBrowseViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var longPressedTeam by remember { mutableStateOf<SportsTeam?>(null) }
    val gridState = rememberLazyGridState()
    val firstFilterRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusSoon(firstFilterRequester) }

    // The banner's collage: the person's teams first, then the leagues' teams with a logo.
    val collage = remember(uiState.favoriteTeams, uiState.sections) {
        (uiState.favoriteTeams + uiState.sections.flatMap { section -> section.teams.take(2) })
            .filter { !it.badgeUrl.isNullOrBlank() }
            .distinctBy { it.id }
            .take(6)
    }

    Box(modifier = modifier.fillMaxSize().background(AppleTvColors.Background)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(TEAM_COLUMNS),
            state = gridState,
            contentPadding = PaddingValues(start = PageSideMargin, end = PageSideMargin, bottom = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(AppleTvSpacing.GridGap),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                TeamsBanner(collage = collage)
            }

            item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
                TeamsFilterRow(
                    filters = viewModel.filters,
                    selectedId = uiState.selectedFilterId,
                    firstRequester = firstFilterRequester,
                    onSelect = viewModel::selectFilter
                )
            }

            // My Teams
            item(key = "my_teams_title", span = { GridItemSpan(maxLineSpan) }) {
                TeamsSectionTitle(
                    text = stringResource(R.string.sports_row_my_teams),
                    trailingStar = true
                )
            }
            if (uiState.favoriteTeams.isEmpty()) {
                item(key = "my_teams_hint") { FollowTeamsHintCard() }
            } else {
                items(uiState.favoriteTeams.distinctBy { it.id }, key = { "fav_${it.id}" }) { team ->
                    TeamBrowseTile(
                        team = team,
                        isFavorite = true,
                        subtitle = team.leagueName,
                        onClick = { onTeamClick(team) },
                        onLongPress = { longPressedTeam = team }
                    )
                }
            }

            if (uiState.isLoading && uiState.sections.isEmpty()) {
                item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(R.string.sports_loading),
                        color = AppleTvColors.LabelSecondary,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }

            // Every league's teams, in rows going down.
            // A league listed twice, or a team twice in one, would bring the page down: once each.
            uiState.sections.distinctBy { it.key }.forEach { section ->
                item(key = "title_${section.key}", span = { GridItemSpan(maxLineSpan) }) {
                    TeamsSectionTitle(text = listOf(section.emoji, section.label).filter { it.isNotBlank() }.joinToString(" "))
                }
                items(section.teams.distinctBy { it.id }, key = { "${section.key}_${it.id}" }) { team ->
                    TeamBrowseTile(
                        team = team,
                        isFavorite = team.id in uiState.favoriteTeamIds,
                        subtitle = null,
                        onClick = { onTeamClick(team) },
                        onLongPress = { longPressedTeam = team }
                    )
                }
            }
        }
    }

    longPressedTeam?.let { team ->
        SportsFavoriteOptionsDialog(
            target = SportsFavoriteTarget.TeamTarget(team),
            favoriteTeamIds = uiState.favoriteTeamIds,
            favoriteSportNames = emptySet(),
            onToggleTeam = viewModel::toggleFavoriteTeam,
            onToggleSport = { },
            onDismiss = { longPressedTeam = null }
        )
    }
}

/** Lets a grid item reach past the page's side margins (the banner runs edge to edge). */
internal fun Modifier.bleed(side: Dp): Modifier = layout { measurable, constraints ->
    val extra = (side * 2).roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.maxWidth + extra,
            maxWidth = constraints.maxWidth + extra
        )
    )
    layout(constraints.maxWidth, placeable.height) {
        placeable.place(-extra / 2, 0)
    }
}

/** One tile of a banner's collage: a team's or a league's color and logo. */
internal data class SportsCollageItem(val name: String, val logoUrl: String?, val color: Color)

/** The Teams banner: "TEAMS" and the team collage. */
@Composable
private fun TeamsBanner(collage: List<SportsTeam>) {
    SportsCollageBanner(
        title = stringResource(R.string.sports_row_teams),
        subtitle = stringResource(R.string.sports_teams_subtitle),
        items = collage.map { team -> SportsCollageItem(team.name, team.badgeUrl, sportsTeamColor(team.color, team.id)) }
    )
}

/**
 * A big title ("TEAMS", "LEAGUES") over a red-to-blue glow, its line under it, and a slanted collage
 * of tiles (their colors, their logos) on the right. Runs edge to edge past the page's margins.
 */
@Composable
internal fun SportsCollageBanner(
    title: String,
    subtitle: String,
    items: List<SportsCollageItem>,
    sideMargin: Dp = PageSideMargin
) {
    Box(
        modifier = Modifier
            .bleed(sideMargin)
            .fillMaxWidth()
            .height(250.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF7A0F1C), Color(0xFF8C1530), Color(0xFF2A1F86), Color(0xFF0C2A86))
                    )
                )
        )
        // The collage, slanted, on the right.
        if (items.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(430.dp)
                    .offset(x = 30.dp)
                    .graphicsLayer {
                        rotationZ = -9f
                        scaleX = 1.18f
                        scaleY = 1.18f
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items.take(6).chunked(3).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowItems.forEach { item -> CollageTile(item) }
                        }
                    }
                }
            }
        }
        // Darker behind the title, and fading into the page at the bottom.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to Color.Black.copy(alpha = 0.35f),
                            0.45f to Color.Black.copy(alpha = 0.1f),
                            0.6f to Color.Transparent,
                            1f to Color.Transparent
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.62f to Color.Transparent,
                            1f to AppleTvColors.Background
                        )
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = sideMargin, top = 10.dp)
        ) {
            Text(
                text = title.uppercase(),
                color = Color.White,
                fontSize = 54.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun CollageTile(item: SportsCollageItem) {
    Box(
        modifier = Modifier
            .size(width = 130.dp, height = 110.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.linearGradient(listOf(lerp(item.color, Color.White, 0.12f), lerp(item.color, Color.Black, 0.4f)))
            ),
        contentAlignment = Alignment.Center
    ) {
        if (!item.logoUrl.isNullOrBlank()) {
            SportsLogoImage(
                url = item.logoUrl,
                background = item.color,
                contentDescription = item.name,
                modifier = Modifier.size(70.dp)
            )
        }
    }
}

/** The leagues to show: ALL, NFL, NBA, MLB... White when chosen or focused. */
@Composable
private fun TeamsFilterRow(
    filters: List<com.nuvio.tv.data.remote.espn.EspnSportsClient.TeamsFilterChip>,
    selectedId: String?,
    firstRequester: FocusRequester,
    onSelect: (String?) -> Unit
) {
    LazyRow(
        modifier = Modifier.bleed(PageSideMargin).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = PageSideMargin, vertical = 6.dp)
    ) {
        item(key = "filter_all") {
            FilterChip(
                label = stringResource(R.string.sports_filter_all),
                selected = selectedId == null,
                onClick = { onSelect(null) },
                modifier = Modifier.focusRequester(firstRequester)
            )
        }
        itemsIndexed(filters.distinctBy { it.id }, key = { _, filter -> filter.id }) { _, filter ->
            FilterChip(label = filter.label, selected = selectedId == filter.id, onClick = { onSelect(filter.id) })
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.height(34.dp),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White else AppleTvColors.Contrast,
            contentColor = if (selected) Color.Black else Color.White.copy(alpha = 0.85f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f)
    ) {
        Box(modifier = Modifier.fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(text = label.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, maxLines = 1)
        }
    }
}

/** A part of the page: "MY TEAMS ★", "🏈 NFL". */
@Composable
private fun TeamsSectionTitle(text: String, trailingStar: Boolean = false) {
    Row(
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text.uppercase(),
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        if (trailingStar) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(imageVector = Icons.Default.Star, contentDescription = null, tint = FavoriteStar, modifier = Modifier.size(20.dp))
        }
    }
}

/** My Teams with no team yet: a tile the size of the teams'. */
@Composable
private fun FollowTeamsHintCard() {
    Box(
        modifier = Modifier
            .size(width = SportsTileWidth, height = SportsTileHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(AppleTvColors.Contrast),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
            Icon(imageVector = Icons.Outlined.StarOutline, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.sports_follow_teams_hint),
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 3
            )
        }
    }
}

/**
 * A team, like the Leagues page's tiles: 16:9 in its color with its logo big in the middle (white
 * when it's too dark to see), a star when it's in My Teams, and its name under it, big and white.
 */
@Composable
private fun TeamBrowseTile(
    team: SportsTeam,
    isFavorite: Boolean,
    subtitle: String?,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    SportsLogoTile(
        name = team.name,
        logoUrl = team.badgeUrl,
        color = sportsTeamColor(team.color, team.id),
        isFavorite = isFavorite,
        onClick = onClick,
        onLongPress = onLongPress,
        modifier = modifier,
        prominentName = true,
        subtitle = subtitle
    )
}
