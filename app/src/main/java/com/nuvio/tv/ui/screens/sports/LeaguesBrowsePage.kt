package com.nuvio.tv.ui.screens.sports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.SportsLeague
import com.nuvio.tv.ui.components.AppleTvColors
import com.nuvio.tv.ui.components.AppleTvSpacing
import kotlinx.coroutines.launch

/** As many leagues on a line as a row's See All shows landscape cards. */
private const val LEAGUE_COLUMNS = 4

/**
 * The Leagues page (Top Leagues' See All): a "LEAGUES" banner like the Teams page's, then every
 * league in a grid like any row's See All, going down the page: NFL, NBA, MLB, NHL, then soccer and
 * the rest. Choosing a league opens it; holding OK opens its options (My Sports).
 */
@Composable
internal fun LeaguesBrowsePage(
    leagues: List<SportsLeague>,
    favoriteLeagueIds: Set<String>,
    onOpenLeague: (SportsLeague) -> Unit,
    onLongPressLeague: (SportsLeague) -> Unit
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requesterFor(id: String) = requesters.getOrPut(id) { FocusRequester() }
    // Back from a league: on the league it opened, else on the first one.
    var focusedLeagueId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(leagues.isNotEmpty()) {
        if (leagues.isEmpty()) return@LaunchedEffect
        val target = focusedLeagueId?.takeIf { id -> leagues.any { it.id == id } } ?: leagues.first().id
        requestFocusSoon(requesterFor(target))
    }
    // Like a row's See All: Back down the page goes back up to the first league; from there, Back
    // leaves the page (back to Top Leagues).
    val gridAtTop by remember {
        derivedStateOf { gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0 }
    }
    val onFirstLeague = focusedLeagueId == null || focusedLeagueId == leagues.firstOrNull()?.id
    BackHandler(enabled = leagues.isNotEmpty() && !(gridAtTop && onFirstLeague)) {
        val first = leagues.firstOrNull() ?: return@BackHandler
        scope.launch {
            runCatching { gridState.scrollToItem(0) }
            requestFocusSoon(requesterFor(first.id))
        }
    }

    // The banner's collage: the first leagues with a logo, in their colors.
    val collage = remember(leagues) {
        leagues
            .filter { !it.badgeUrl.isNullOrBlank() }
            .take(6)
            .map { league -> SportsCollageItem(league.name, league.badgeUrl, sportsLeagueColor(league.id, league.name)) }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(LEAGUE_COLUMNS),
        state = gridState,
        contentPadding = PaddingValues(start = AppleTvSpacing.SafeX, end = AppleTvSpacing.SafeX, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(AppleTvSpacing.GridGap),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(AppleTvColors.Background)
    ) {
        item(key = "banner", span = { GridItemSpan(maxLineSpan) }) {
            SportsCollageBanner(
                title = stringResource(R.string.sports_leagues_title),
                subtitle = stringResource(R.string.sports_leagues_subtitle),
                items = collage,
                sideMargin = AppleTvSpacing.SafeX
            )
        }

        itemsIndexed(leagues.distinctBy { it.id }, key = { _, league -> league.id }) { index, league ->
            val firstLine = index < LEAGUE_COLUMNS
            SportsLogoTile(
                name = sportsLeagueShortName(league),
                logoUrl = league.badgeUrl,
                color = sportsLeagueColor(league.id, league.name),
                isFavorite = league.id in favoriteLeagueIds,
                onClick = { onOpenLeague(league) },
                onLongPress = { onLongPressLeague(league) },
                modifier = Modifier
                    .focusRequester(requesterFor(league.id))
                    .onFocusChanged { state ->
                        if (!state.hasFocus) return@onFocusChanged
                        focusedLeagueId = league.id
                        // Back up on the first line: the banner shows again.
                        if (firstLine && (gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0)) {
                            scope.launch { gridState.animateScrollToItem(0) }
                        }
                    }
            )
        }
    }
}
