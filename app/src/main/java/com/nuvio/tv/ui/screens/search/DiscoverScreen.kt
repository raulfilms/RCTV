package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.screens.home.HeroBackdropState

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.theme.NuvioColors
import kotlin.math.roundToInt

@Composable
fun DiscoverScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    discoverViewModel: TmdbDiscoverViewModel = hiltViewModel(),
    showBuiltInHeader: Boolean = true,
    onNavigateToDetail: (String, String, String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    // The page's titles: TMDB's whole catalog, by Type, Category, Genre and Year.
    val discoverState by discoverViewModel.uiState.collectAsState()
    val watchedMovieIds by viewModel.watchedMovieIds.collectAsState()
    val watchedSeriesIds by viewModel.watchedSeriesIds.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val discoverFirstItemFocusRequester = remember { FocusRequester() }
    var discoverFocusedItemIndex by rememberSaveable { mutableStateOf(0) }
    var restoreDiscoverFocus by rememberSaveable { mutableStateOf(false) }
    var pendingDiscoverRestoreOnResume by rememberSaveable { mutableStateOf(false) }

    val posterCardStyle = remember(uiState.posterCardWidthDp, uiState.posterCardCornerRadiusDp) {
        val computedHeightDp = (uiState.posterCardWidthDp * 1.5f).roundToInt()
        PosterCardStyle(
            width = uiState.posterCardWidthDp.dp,
            height = computedHeightDp.dp,
            cornerRadius = uiState.posterCardCornerRadiusDp.dp,
            focusedBorderWidth = PosterCardDefaults.Style.focusedBorderWidth,
            focusedScale = PosterCardDefaults.Style.focusedScale
        )
    }

    val latestPendingDiscoverRestore by rememberUpdatedState(pendingDiscoverRestoreOnResume)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && latestPendingDiscoverRestore) {
                restoreDiscoverFocus = true
                pendingDiscoverRestoreOnResume = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
    ) {
        if (uiState.discoverLocation == DiscoverLocation.OFF) {
            EmptyScreenState(
                title = stringResource(R.string.discover_disabled_title),
                subtitle = stringResource(R.string.discover_disabled_subtitle),
                icon = Icons.Default.Search
            )
        } else {
            TmdbDiscoverSection(
                state = discoverState,
                posterCardStyle = posterCardStyle,
                watchedMovieIds = watchedMovieIds,
                watchedSeriesIds = watchedSeriesIds,
                showBuiltInHeader = showBuiltInHeader,
                firstItemFocusRequester = discoverFirstItemFocusRequester,
                focusedItemIndex = discoverFocusedItemIndex,
                shouldRestoreFocusedItem = restoreDiscoverFocus,
                blockFilterFocus = restoreDiscoverFocus || pendingDiscoverRestoreOnResume,
                onRestoreFocusedItemHandled = { restoreDiscoverFocus = false },
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    pendingDiscoverRestoreOnResume = true
                    onNavigateToDetail(itemId, itemType, addonBaseUrl)
                },
                onItemFocused = { index -> discoverFocusedItemIndex = index },
                onSelectType = {
                    discoverFocusedItemIndex = 0
                    discoverViewModel.selectMediaFilter(it)
                },
                onSelectCategory = {
                    discoverFocusedItemIndex = 0
                    discoverViewModel.selectCategory(it)
                },
                onSelectGenre = {
                    discoverFocusedItemIndex = 0
                    discoverViewModel.selectGenre(it)
                },
                onSelectYear = {
                    discoverFocusedItemIndex = 0
                    discoverViewModel.selectYear(it)
                },
                onLoadMore = discoverViewModel::retry,
                onItemLongPress = { item -> viewModel.posterOptions.show(item, "") },
                modifier = Modifier.padding(top = NuvioTheme.spacing.lg)
            )
        }

        val posterOptionsState by viewModel.posterOptions.state.collectAsState()
        com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost(
            state = posterOptionsState,
            controller = viewModel.posterOptions,
            onNavigateToDetail = { id, type, addonBaseUrl ->
                pendingDiscoverRestoreOnResume = true
                val clickedItem = discoverState.items.firstOrNull { it.id == id }
                HeroBackdropState.update(clickedItem?.backdropUrl)
                onNavigateToDetail(id, type, addonBaseUrl)
            }
        )
    }
}
