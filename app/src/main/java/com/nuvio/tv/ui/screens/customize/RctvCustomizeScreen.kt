@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.customize

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.SportsTeamKey
import com.nuvio.tv.ui.screens.account.GlowBackground
import kotlinx.coroutines.delay

private val PageBackground = Color(0xFF060816)
private val Accent = Color(0xFF2563EB)
private val AccentFocused = Color(0xFF3B82F6)
private val TileColor = Color.White.copy(alpha = 0.06f)
private val TileFocusedColor = Color.White.copy(alpha = 0.14f)
private val TileBorder = Color.White.copy(alpha = 0.12f)
private val Muted = Color(0xFFB4B8CC)

/** The list on the left of a page: its first entry is where the page starts. */
private const val ALL_KEY = "all"
private const val FOCUS_DELAY_MS = 120L

/**
 * RCTV (owner's design), "Customize your experience": the first time a profile opens on the TV
 * (Full plan) it picks its sports leagues, its teams, its favorite TV networks and series, with
 * Continue and Back at the bottom right and "Skip for now" under them. Finish (or Skip) and
 * [onDone]: "Creating Your Experience" comes next. In the app's own look (the activation page's).
 */
@Composable
fun RctvCustomizeScreen(
    profileId: Int,
    onDone: () -> Unit,
    viewModel: RctvCustomizeViewModel = hiltViewModel()
) {
    LaunchedEffect(profileId) { viewModel.start(profileId) }
    val state by viewModel.uiState.collectAsState()
    // Back goes to the page before; on the first page it does nothing ("Skip for now" leaves).
    BackHandler { viewModel.back() }

    val continueFocus = remember { FocusRequester() }
    val firstFocus = remember(state.step) { FocusRequester() }
    LaunchedEffect(state.step) {
        delay(FOCUS_DELAY_MS)
        if (runCatching { firstFocus.requestFocus() }.isFailure) runCatching { continueFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBackground)
    ) {
        GlowBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp, vertical = 18.dp)
        ) {
            Header(steps = state.steps, current = state.step)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(titleOf(state.step)),
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(subtitleOf(state.step)),
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (state.step) {
                    CustomizeStep.LEAGUES -> LeaguesPage(state, viewModel, firstFocus)
                    CustomizeStep.TEAMS -> TeamsPage(state, viewModel, firstFocus)
                    CustomizeStep.NETWORKS -> NetworksPage(state, viewModel, firstFocus)
                    CustomizeStep.SERIES -> SeriesPage(state, viewModel, firstFocus)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    SelectedRow(state, viewModel)
                }
                Spacer(modifier = Modifier.width(24.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!state.isFirstStep) {
                            PageButton(
                                text = stringResource(R.string.rctv_customize_back),
                                primary = false,
                                onClick = { viewModel.back() }
                            )
                        }
                        PageButton(
                            text = stringResource(if (state.isLastStep) R.string.rctv_customize_finish else R.string.rctv_customize_continue),
                            primary = true,
                            onClick = {
                                if (state.isLastStep) viewModel.finish(onDone) else viewModel.next()
                            },
                            modifier = Modifier.focusRequester(continueFocus)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    SkipButton(onClick = { viewModel.skip(onDone) })
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- Top

@StringRes
private fun stepLabelOf(step: CustomizeStep): Int = when (step) {
    CustomizeStep.LEAGUES -> R.string.rctv_customize_step_leagues
    CustomizeStep.TEAMS -> R.string.rctv_customize_step_teams
    CustomizeStep.NETWORKS -> R.string.rctv_customize_step_networks
    CustomizeStep.SERIES -> R.string.rctv_customize_step_series
}

@StringRes
private fun titleOf(step: CustomizeStep): Int = when (step) {
    CustomizeStep.LEAGUES -> R.string.rctv_customize_title_leagues
    CustomizeStep.TEAMS -> R.string.rctv_customize_title_teams
    CustomizeStep.NETWORKS -> R.string.rctv_customize_title_networks
    CustomizeStep.SERIES -> R.string.rctv_customize_title_series
}

@StringRes
private fun subtitleOf(step: CustomizeStep): Int = when (step) {
    CustomizeStep.LEAGUES -> R.string.rctv_customize_subtitle_leagues
    CustomizeStep.TEAMS -> R.string.rctv_customize_subtitle_teams
    CustomizeStep.NETWORKS -> R.string.rctv_customize_subtitle_networks
    CustomizeStep.SERIES -> R.string.rctv_customize_subtitle_series
}

/** The RCTV logo on the left; the pages, numbered, in the middle (done ones with a check). */
@Composable
private fun Header(steps: List<CustomizeStep>, current: CustomizeStep) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.rctv_activation_logo),
            contentDescription = "RCTV",
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(112.dp)
        )
        val currentIndex = steps.indexOf(current)
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically
        ) {
            steps.forEachIndexed { index, step ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 10.dp)
                            .width(28.dp)
                            .height(2.dp)
                            .background(if (index <= currentIndex) Accent else Color.White.copy(alpha = 0.18f))
                    )
                }
                val done = index < currentIndex
                val active = index == currentIndex
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(if (done || active) Accent else Color.Transparent, CircleShape)
                        .border(1.5.dp, if (done || active) Accent else Color.White.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (done) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                    } else {
                        Text(
                            text = "${index + 1}",
                            color = if (active) Color.White else Muted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(stepLabelOf(step)),
                    color = if (active) Color.White else Muted,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- Pages

@Composable
private fun LeaguesPage(state: CustomizeUiState, viewModel: RctvCustomizeViewModel, firstFocus: FocusRequester) {
    val selected = remember(state.selectedLeagueIds) { state.selectedLeagueIds.toSet() }
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(6.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(state.leagues, key = { _, league -> league.id }) { index, league ->
            PickTile(
                selected = league.id in selected,
                onClick = { viewModel.toggleLeague(league.id) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!league.badgeUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = league.badgeUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(48.dp)
                            )
                        } else {
                            Text(text = league.emoji, fontSize = 30.sp)
                        }
                    }
                    Text(
                        text = league.name,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun TeamsPage(state: CustomizeUiState, viewModel: RctvCustomizeViewModel, firstFocus: FocusRequester) {
    val leagues = state.teamLeagues
    val current = leagues.firstOrNull { it.id == state.teamLeagueId } ?: leagues.firstOrNull()
    val selected = remember(state.selectedTeamKeys) { state.selectedTeamKeys.toSet() }
    Row(modifier = Modifier.fillMaxSize()) {
        SideList(
            entries = leagues.map { SideEntry(key = it.id, label = it.name, imageUrl = it.badgeUrl, emoji = it.emoji) },
            selectedKey = current?.id,
            onSelect = { viewModel.selectTeamLeague(it) },
            firstFocus = firstFocus
        )
        Spacer(modifier = Modifier.width(20.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            GridHeader(
                title = stringResource(R.string.rctv_customize_teams_of, current?.name.orEmpty()),
                query = state.teamQuery,
                placeholder = stringResource(R.string.rctv_customize_search_teams),
                onQuery = { viewModel.setTeamQuery(it) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (state.teams.isEmpty()) {
                Message(stringResource(if (state.teamsLoading) R.string.rctv_customize_loading else R.string.rctv_customize_nothing))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.teams, key = { SportsTeamKey.of(it) }) { team ->
                        PickTile(
                            selected = SportsTeamKey.of(team) in selected,
                            onClick = { viewModel.toggleTeam(team) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(92.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!team.badgeUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = team.badgeUrl,
                                            contentDescription = null,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.size(42.dp)
                                        )
                                    } else {
                                        Text(
                                            text = team.name.take(1),
                                            color = Color.White,
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Text(
                                    text = team.name,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    lineHeight = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworksPage(state: CustomizeUiState, viewModel: RctvCustomizeViewModel, firstFocus: FocusRequester) {
    val selected = remember(state.selectedNetworkIds) { state.selectedNetworkIds.toSet() }
    val allLabel = stringResource(R.string.rctv_customize_all_networks)
    val entries = listOf(SideEntry(key = ALL_KEY, label = allLabel)) +
        state.networkCategories.map { SideEntry(key = it.name, label = stringResource(it.labelRes)) }
    val title = state.networkCategory?.let { stringResource(it.labelRes) } ?: allLabel
    Row(modifier = Modifier.fillMaxSize()) {
        SideList(
            entries = entries,
            selectedKey = state.networkCategory?.name ?: ALL_KEY,
            onSelect = { key ->
                viewModel.selectNetworkCategory(state.networkCategories.firstOrNull { it.name == key })
            },
            firstFocus = firstFocus
        )
        Spacer(modifier = Modifier.width(20.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            GridHeader(
                title = title,
                query = state.networkQuery,
                placeholder = stringResource(R.string.rctv_customize_search_networks),
                onQuery = { viewModel.setNetworkQuery(it) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (state.networks.isEmpty()) {
                Message(stringResource(if (state.networksLoading) R.string.rctv_customize_loading_channels else R.string.rctv_customize_nothing))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.networks, key = { it.id }) { network ->
                        PickTile(
                            selected = network.id in selected,
                            onClick = { viewModel.toggleNetwork(network) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(76.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (!network.logoUrl.isNullOrBlank()) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        AsyncImage(
                                            model = network.logoUrl,
                                            contentDescription = null,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(36.dp)
                                        )
                                    }
                                    Text(
                                        text = network.name,
                                        color = Muted,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = network.name,
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            lineHeight = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesPage(state: CustomizeUiState, viewModel: RctvCustomizeViewModel, firstFocus: FocusRequester) {
    val selected = remember(state.selectedSeries) { state.selectedSeries.map { it.id }.toSet() }
    val genres = RctvCustomizeViewModel.SERIES_GENRES
    val entries = genres.map { SideEntry(key = it.genre ?: ALL_KEY, label = stringResource(it.labelRes)) }
    val current = genres.firstOrNull { it.genre == state.seriesGenre } ?: genres.first()
    Row(modifier = Modifier.fillMaxSize()) {
        SideList(
            entries = entries,
            selectedKey = state.seriesGenre ?: ALL_KEY,
            onSelect = { key -> viewModel.selectSeriesGenre(key.takeIf { it != ALL_KEY }) },
            firstFocus = firstFocus
        )
        Spacer(modifier = Modifier.width(20.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            GridHeader(
                title = stringResource(current.labelRes),
                query = state.seriesQuery,
                placeholder = stringResource(R.string.rctv_customize_search_series),
                onQuery = { viewModel.setSeriesQuery(it) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (state.series.isEmpty()) {
                Message(stringResource(if (state.seriesLoading) R.string.rctv_customize_loading else R.string.rctv_customize_nothing))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(7),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.series, key = { it.id }) { meta ->
                        PickTile(
                            selected = meta.id in selected,
                            onClick = { viewModel.toggleSeries(meta) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f)
                        ) {
                            // The name shows until (or unless) the poster does.
                            Text(
                                text = meta.name,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(6.dp)
                            )
                            if (!meta.poster.isNullOrBlank()) {
                                AsyncImage(
                                    model = meta.poster,
                                    contentDescription = meta.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- Pieces

/** An entry of the list on the left (a league, a category, a genre). */
private data class SideEntry(val key: String, val label: String, val imageUrl: String? = null, val emoji: String? = null)

@Composable
private fun SideList(
    entries: List<SideEntry>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    firstFocus: FocusRequester
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(4.dp),
        modifier = Modifier
            .width(196.dp)
            .fillMaxHeight()
    ) {
        itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
            val selected = entry.key == selectedKey
            val shape = RoundedCornerShape(12.dp)
            Surface(
                onClick = { onSelect(entry.key) },
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (selected) Accent.copy(alpha = 0.22f) else TileColor,
                    focusedContainerColor = if (selected) Accent.copy(alpha = 0.36f) else TileFocusedColor,
                    contentColor = Color.White,
                    focusedContentColor = Color.White
                ),
                border = ClickableSurfaceDefaults.border(
                    border = Border(
                        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Accent else TileBorder),
                        shape = shape
                    ),
                    focusedBorder = Border(border = BorderStroke(2.dp, Color.White), shape = shape)
                ),
                shape = ClickableSurfaceDefaults.shape(shape),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!entry.imageUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = entry.imageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(26.dp)
                        )
                    } else if (!entry.emoji.isNullOrBlank()) {
                        Text(text = entry.emoji, fontSize = 18.sp)
                    }
                    Text(
                        text = entry.label,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** The page's title on the left of its grid, the search box on the right. */
@Composable
private fun GridHeader(title: String, query: String, placeholder: String, onQuery: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        SearchField(
            value = query,
            placeholder = placeholder,
            onValueChange = onQuery,
            modifier = Modifier
                .width(280.dp)
                .height(40.dp)
        )
    }
}

/** The search box: OK starts typing (the TV's keyboard opens), as on the activation page. */
@Composable
private fun SearchField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val textFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (editing) {
            runCatching { textFocus.requestFocus() }
            keyboard?.show()
        }
    }
    val stopEditing: () -> Unit = {
        editing = false
        keyboard?.hide()
    }
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = { editing = true },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.06f),
            focusedContainerColor = Color.White.copy(alpha = 0.12f),
            contentColor = Color.White,
            focusedContentColor = Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)), shape = shape),
            focusedBorder = Border(border = BorderStroke(2.dp, Color.White), shape = shape)
        ),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp)
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(if (editing) Color.White else Color.Transparent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { stopEditing() },
                    onDone = { stopEditing() }
                ),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(textFocus)
                    .onFocusChanged {
                        if (!it.isFocused && editing) stopEditing()
                    },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 15.sp,
                                maxLines = 1
                            )
                        }
                        inner()
                    }
                }
            )
        }
    }
}

/** A tile to pick: blue outline and a check while picked, white outline while focused. */
@Composable
private fun PickTile(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Accent.copy(alpha = 0.18f) else TileColor,
            focusedContainerColor = if (selected) Accent.copy(alpha = 0.30f) else TileFocusedColor,
            contentColor = Color.White,
            focusedContentColor = Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Accent else TileBorder),
                shape = shape
            ),
            focusedBorder = Border(border = BorderStroke(2.5.dp, Color.White), shape = shape)
        ),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .size(22.dp)
                        .background(Accent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

/** A picked one, under the page: choosing it takes it out. */
private data class ChipItem(val key: String, val label: String, val imageUrl: String?, val onRemove: () -> Unit)

@Composable
private fun SelectedRow(state: CustomizeUiState, viewModel: RctvCustomizeViewModel) {
    val labelRes: Int = when (state.step) {
        CustomizeStep.LEAGUES -> R.string.rctv_customize_selected_leagues
        CustomizeStep.TEAMS -> R.string.rctv_customize_selected_teams
        CustomizeStep.NETWORKS -> R.string.rctv_customize_selected_networks
        CustomizeStep.SERIES -> R.string.rctv_customize_selected_series
    }
    val chips: List<ChipItem> = when (state.step) {
        CustomizeStep.LEAGUES -> state.selectedLeagueIds.mapNotNull { id ->
            state.leagues.firstOrNull { it.id == id }?.let { league ->
                ChipItem(league.id, league.name, league.badgeUrl) { viewModel.toggleLeague(league.id) }
            }
        }
        CustomizeStep.TEAMS -> state.selectedTeams.map { team ->
            ChipItem(SportsTeamKey.of(team), team.name, team.badgeUrl) { viewModel.toggleTeam(team) }
        }
        CustomizeStep.NETWORKS -> state.selectedNetworks.map { network ->
            ChipItem(network.id, network.name, network.logoUrl) { viewModel.toggleNetwork(network) }
        }
        CustomizeStep.SERIES -> state.selectedSeries.map { meta ->
            ChipItem(meta.id, meta.name, null) { viewModel.toggleSeries(meta) }
        }
    }
    Column {
        Text(
            text = stringResource(labelRes, chips.size),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
        ) {
            items(chips, key = { it.key }) { chip ->
                val shape = RoundedCornerShape(50)
                Surface(
                    onClick = chip.onRemove,
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.06f),
                        focusedContainerColor = Color.White.copy(alpha = 0.16f),
                        contentColor = Color.White,
                        focusedContentColor = Color.White
                    ),
                    border = ClickableSurfaceDefaults.border(
                        border = Border(border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)), shape = shape),
                        focusedBorder = Border(border = BorderStroke(2.dp, Color.White), shape = shape)
                    ),
                    shape = ClickableSurfaceDefaults.shape(shape),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                    modifier = Modifier.height(36.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!chip.imageUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = chip.imageUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(text = chip.label, fontSize = 13.sp, maxLines = 1)
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Continue (blue) or Back. */
@Composable
private fun PageButton(text: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Accent else Color.White.copy(alpha = 0.08f),
            focusedContainerColor = if (primary) AccentFocused else Color.White,
            contentColor = Color.White,
            focusedContentColor = if (primary) Color.White else Color.Black
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(1.dp, if (primary) Accent else Color.White.copy(alpha = 0.3f)), shape = shape),
            focusedBorder = Border(border = BorderStroke(2.dp, Color.White), shape = shape)
        ),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = modifier
            .width(if (primary) 190.dp else 140.dp)
            .height(44.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** "Skip for now", under Continue and Back. */
@Composable
private fun SkipButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.12f),
            contentColor = Color.White.copy(alpha = 0.7f),
            focusedContentColor = Color.White
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Text(
            text = stringResource(R.string.rctv_customize_skip),
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun Message(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}
