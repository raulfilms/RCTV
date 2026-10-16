@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.AppleAmbientBackdrop
import com.nuvio.tv.ui.components.AppleFilterOption
import com.nuvio.tv.ui.components.AppleFilterMenuButton
import com.nuvio.tv.ui.components.AppleFilterSection
import com.nuvio.tv.ui.components.AppleTvCardSpacing
import com.nuvio.tv.ui.components.AppleTvContentStart
import com.nuvio.tv.ui.components.AppleTvFocusScale
import com.nuvio.tv.ui.components.AppleTvTextShadow
import com.nuvio.tv.ui.components.AppleTvColors
import com.nuvio.tv.ui.components.AppleTvPosterHeight
import com.nuvio.tv.ui.components.AppleTvPosterWidth
import com.nuvio.tv.ui.components.AppleTvRadius
import com.nuvio.tv.ui.components.AppleTvSpacing
import com.nuvio.tv.ui.components.AppleTvType
import com.nuvio.tv.ui.components.ContentCard
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.isRowEnd
import com.nuvio.tv.ui.components.rightAtRowEndFocuses
import com.nuvio.tv.ui.components.FilterDropdownOption
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.LocalCardDepthStyle
import com.nuvio.tv.ui.components.nuvioCardDepth
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.screens.home.HomeEvent
import com.nuvio.tv.ui.screens.home.HomeViewModel
import com.nuvio.tv.ui.screens.search.SearchEvent
import com.nuvio.tv.ui.screens.search.SearchViewModel
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.legacyKey
import com.nuvio.tv.domain.model.stableItemKey
import com.nuvio.tv.domain.model.stableKey
import com.nuvio.tv.ui.util.buildMetaGenreYearFilter
import com.nuvio.tv.ui.util.localizedContentType
import com.nuvio.tv.ui.util.localizedGenreLabel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Stable focus key for a catalog poster (survives list reloads; not a grid index). */
private fun catalogItemFocusKey(item: MetaPreview): String = "${item.apiType}:${item.id}"

private const val FILTER_SECTION_TYPE = "type"
/** With a filter on the loaded titles, keep loading pages until this many match... */
private const val LOCAL_FILTER_TARGET = 24
/** ...or this many more pages were loaded for that pick. */
private const val LOCAL_FILTER_MAX_PAGES = 15
private const val FILTER_SECTION_GENRE = "genre"
private const val FILTER_SECTION_YEAR = "year"

@Composable
fun CatalogSeeAllScreen(
    catalogId: String,
    addonId: String,
    type: String,
    searchViewModel: SearchViewModel? = null,
    viewModel: HomeViewModel = hiltViewModel(),
    posterOptionsViewModel: com.nuvio.tv.ui.components.posteroptions.PosterOptionsViewModel = hiltViewModel(),
    genreFilterViewModel: CatalogGenreFilterViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onBackPress: () -> Unit
) {
    val posterOptionsController = searchViewModel?.posterOptions ?: posterOptionsViewModel.controller
    val uiState by viewModel.uiState.collectAsState()
    val fullCatalogRows by viewModel.fullCatalogRows.collectAsState()
    // Apple TV sheet: 2:3 posters at the 6-column width, poster radius, focus by scale and shadow.
    val posterCardStyle = PosterCardStyle(
        width = AppleTvPosterWidth,
        height = AppleTvPosterHeight,
        cornerRadius = AppleTvRadius.Poster,
        focusedBorderWidth = 0.dp,
        focusedScale = AppleTvFocusScale
    )

    BackHandler { onBackPress() }

    val isSearchMode = searchViewModel != null
    val catalogKey = "${addonId}_${type}_${catalogId}"

    // In search mode, get the catalog row from SearchViewModel's existing results.
    // Otherwise fall back to HomeViewModel's fullCatalogRows (home screen catalogs).
    val searchUiState = searchViewModel?.uiState?.collectAsState()
    val searchWatchedMovieIds = searchViewModel?.watchedMovieIds?.collectAsState()
    val searchWatchedSeriesIds = searchViewModel?.watchedSeriesIds?.collectAsState()
    val searchCatalogRow = searchUiState?.value?.catalogRows?.find {
        it.legacyKey() == catalogKey
    }
    val homeCatalogRow = fullCatalogRows.find {
        it.legacyKey() == catalogKey
    }
    val catalogRow = if (isSearchMode) searchCatalogRow else homeCatalogRow

    LaunchedEffect(catalogKey, isSearchMode, catalogRow != null) {
        if (!isSearchMode && catalogRow == null) {
            viewModel.requestLazyCatalogLoad(catalogKey)
        }
    }

    // The addon's own genre (or year) filter, when its manifest lists one for this catalog: the
    // addon is asked for the filtered catalog, so the filter covers everything it has.
    LaunchedEffect(catalogKey, isSearchMode) {
        if (!isSearchMode) genreFilterViewModel.bind(addonId, type, catalogId)
    }
    val remoteFilter by genreFilterViewModel.state.collectAsState()
    val remoteGenres = !isSearchMode && remoteFilter.genres.isNotEmpty()
    val remoteYears = !isSearchMode && remoteFilter.years.isNotEmpty()
    val remoteActive = !isSearchMode && remoteFilter.isActive
    // What the filter works on: the addon's filtered list while one of its genres (years) is
    // picked, otherwise the catalog as loaded so far.
    val baseItems = if (remoteActive) remoteFilter.items else catalogRow?.items.orEmpty()
    val baseLoading = if (remoteActive) remoteFilter.isLoading else catalogRow?.isLoading == true
    val baseHasMore = if (remoteActive) remoteFilter.hasMore else catalogRow?.hasMore == true
    // Counts are only shown once every title is in; part of a catalog would give wrong totals.
    val baseComplete = catalogRow != null && !baseHasMore

    // Type/genre/year filtering over the titles loaded (the addon's own filter is applied by the addon).
    var selectedType by rememberSaveable(catalogKey) { mutableStateOf<String?>(null) }
    var selectedGenre by rememberSaveable(catalogKey) { mutableStateOf<String?>(null) }
    var selectedYear by rememberSaveable(catalogKey) { mutableStateOf<String?>(null) }
    val genreYearFilter = remember(baseItems, selectedType, selectedGenre, selectedYear, remoteGenres, remoteYears) {
        buildMetaGenreYearFilter(
            items = baseItems,
            selectedType = selectedType,
            selectedGenre = if (remoteGenres) null else selectedGenre,
            selectedYear = if (remoteYears) null else selectedYear
        )
    }
    fun loadMoreBase() {
        if (remoteActive) {
            genreFilterViewModel.loadMore()
            return
        }
        val row = catalogRow ?: return
        if (!row.hasMore || row.isLoading) return
        if (isSearchMode) {
            searchViewModel?.onEvent(SearchEvent.LoadMoreCatalog(row.catalogId, row.addonId, row.apiType))
        } else {
            viewModel.onEvent(HomeEvent.OnLoadMoreCatalog(row.catalogId, row.addonId, row.apiType))
        }
    }
    // Only worth offering the Type facet when this catalog actually mixes more than one type.
    val typeFilterOptions = if (genreYearFilter.typeOptions.size > 1) genreYearFilter.typeOptions else emptyList()
    val filteredItems = genreYearFilter.filteredItems
    val filteredItemKeys = remember(catalogRow, filteredItems) {
        val row = catalogRow
        if (row == null) {
            emptyList()
        } else {
            val seen = HashMap<String, Int>()
            filteredItems.map { item ->
                val identity = "${item.apiType}:${item.id}"
                val occurrence = seen.getOrDefault(identity, 0)
                seen[identity] = occurrence + 1
                row.stableItemKey(item, occurrence)
            }
        }
    }
    val hasAnyFilterOptions = typeFilterOptions.isNotEmpty() ||
        genreYearFilter.genreOptions.isNotEmpty() ||
        genreYearFilter.yearOptions.isNotEmpty() ||
        remoteGenres || remoteYears

    // A filter on the loaded titles with few matches: keep loading the catalog so it finds more
    // (a limited number of pages per pick, so a rare match can't load forever).
    val localFilterActive = selectedType != null ||
        (selectedGenre != null && !remoteGenres) ||
        (selectedYear != null && !remoteYears)
    val autoLoadCount = remember(catalogKey, selectedType, selectedGenre, selectedYear) { intArrayOf(0) }
    LaunchedEffect(localFilterActive, filteredItems.size, baseItems.size, baseLoading, baseHasMore) {
        if (!localFilterActive || filteredItems.size >= LOCAL_FILTER_TARGET || !baseHasMore || baseLoading) return@LaunchedEffect
        if (autoLoadCount[0] >= LOCAL_FILTER_MAX_PAGES) return@LaunchedEffect
        autoLoadCount[0]++
        loadMoreBase()
    }

    val gridState = rememberLazyGridState()
    val filterScope = rememberCoroutineScope()
    // The filter pill, which Right at the end of any row of the grid jumps up to.
    val filterRequester = remember { FocusRequester() }
    val restoreFocusRequester = remember { FocusRequester() }
    // Persist the focused catalog item by stable id (not grid index) so return-from-Details
    // can re-focus the same poster even if the row reloads or the first cell steals focus.
    var focusedItemKey by rememberSaveable(catalogKey) { mutableStateOf<String?>(null) }
    var shouldRestoreFocus by rememberSaveable(catalogKey) { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val focusedItemIndex = remember(filteredItems, focusedItemKey) {
        if (filteredItems.isEmpty()) return@remember 0
        val key = focusedItemKey
        if (key.isNullOrBlank()) return@remember 0
        filteredItems.indexOfFirst { catalogItemFocusKey(it) == key }.takeIf { it >= 0 } ?: 0
    }

    // Load more when scrolling near the bottom
    LaunchedEffect(gridState, catalogRow?.items?.size, remoteActive, remoteFilter.items.size) {
        snapshotFlow {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = gridState.layoutInfo.totalItemsCount
            lastVisible to total
        }
            .distinctUntilChanged()
            .collect { (lastVisible, total) ->
                if (total > 0 && lastVisible >= total - 10) loadMoreBase()
            }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                shouldRestoreFocus = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(shouldRestoreFocus, filteredItems.size, focusedItemKey) {
        if (!shouldRestoreFocus) return@LaunchedEffect
        val items = filteredItems
        if (items.isEmpty()) return@LaunchedEffect

        val targetIndex = focusedItemIndex.coerceIn(0, items.lastIndex)
        val isTargetVisible = gridState.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }
        if (!isTargetVisible) {
            gridState.animateScrollToItem(targetIndex)
        }
        repeat(2) { withFrameNanos { } }
        try {
            restoreFocusRequester.requestFocus()
            shouldRestoreFocus = false
        } catch (_: IllegalStateException) {
        }
    }

    // Back down the list goes all the way back up, to its first title; at the top it opens the app's
    // menu; with the menu open, Back leaves the list (back where it was opened from).
    val menuBridge = com.nuvio.tv.ui.components.LocalAppMenuBridge.current
    val menuOpen = com.nuvio.tv.LocalSidebarExpanded.current
    DisposableEffect(menuBridge) {
        menuBridge?.registerPage()
        onDispose {
            menuBridge?.unregisterPage()
            if (menuBridge?.isOpen?.invoke() == true) menuBridge.close()
        }
    }
    val gridAtTop by remember {
        derivedStateOf { gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0 }
    }
    val atListTop = filteredItems.isEmpty() || (gridAtTop && focusedItemIndex == 0)
    BackHandler(enabled = !menuOpen && !atListTop) {
        focusedItemKey = filteredItems.firstOrNull()?.let(::catalogItemFocusKey)
        filterScope.launch {
            runCatching { gridState.scrollToItem(0) }
            repeat(8) {
                withFrameNanos { }
                if (runCatching { restoreFocusRequester.requestFocus(); true }.getOrDefault(false)) return@launch
            }
        }
    }
    BackHandler(enabled = !menuOpen && atListTop && menuBridge != null) {
        menuBridge?.open?.invoke()
    }
    // The menu closed without leaving the list (Right): focus back on the list.
    var menuWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(menuOpen) {
        if (menuOpen) {
            menuWasOpen = true
            return@LaunchedEffect
        }
        if (!menuWasOpen) return@LaunchedEffect
        menuWasOpen = false
        repeat(8) {
            withFrameNanos { }
            if (runCatching { restoreFocusRequester.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    // Same blurred wash as the Apple TV style home, taken from the catalog's first title.
    AppleAmbientBackdrop(
        imageUrl = catalogRow?.items?.firstOrNull { !it.id.startsWith("__placeholder_") }?.backdropUrl,
        modifier = Modifier.fillMaxSize()
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = AppleTvSpacing.SafeY)
    ) {
        val hasRawItems = catalogRow?.items?.isNotEmpty() == true

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppleTvContentStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = catalogRow?.catalogName?.replaceFirstChar { it.uppercase() }
                        ?: stringResource(R.string.catalog_see_all_title_fallback),
                    color = AppleTvColors.Label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = AppleTvType.Title2.copy(shadow = AppleTvTextShadow)
                )
                if (uiState.catalogAddonNameEnabled) {
                    catalogRow?.addonName?.let { addonName ->
                        // White on the gray backdrop: a dimmer secondary gray would not read there.
                        Text(
                            text = stringResource(R.string.catalog_see_all_from, addonName),
                            color = AppleTvColors.Label,
                            style = AppleTvType.Caption1,
                            maxLines = 1
                        )
                    }
                }
            }

            // Type / Genre / Year filter, top right: one menu with every section and its counts.
            if (hasRawItems && hasAnyFilterOptions) {
                val filterContext = androidx.compose.ui.platform.LocalContext.current
                val allLabel = stringResource(R.string.apple_filter_all)
                val typeTitle = stringResource(R.string.library_filter_type)
                val genreTitle = stringResource(R.string.library_filter_genre)
                val yearTitle = stringResource(R.string.library_filter_year)
                val filtersLabel = stringResource(R.string.apple_filters)
                val filterSections = remember(
                    typeFilterOptions,
                    genreYearFilter.genreOptions,
                    genreYearFilter.yearOptions,
                    remoteFilter.genres,
                    remoteFilter.years,
                    remoteGenres,
                    remoteYears,
                    baseComplete,
                    selectedType,
                    selectedGenre,
                    selectedYear,
                    allLabel,
                    typeTitle,
                    genreTitle,
                    yearTitle,
                    filterContext
                ) {
                    fun count(value: Int): Int? = if (baseComplete) value else null
                    buildList {
                        if (typeFilterOptions.isNotEmpty()) {
                            add(
                                AppleFilterSection(
                                    key = FILTER_SECTION_TYPE,
                                    title = typeTitle,
                                    selectedValue = selectedType,
                                    options = listOf(AppleFilterOption(allLabel, null)) +
                                        typeFilterOptions.map {
                                            AppleFilterOption(com.nuvio.tv.ui.util.filterTypeLabel(filterContext, it.label), it.key, count(it.count))
                                        }
                                )
                            )
                        }
                        if (remoteGenres) {
                            // Every genre the addon can filter this catalog by.
                            add(
                                AppleFilterSection(
                                    key = FILTER_SECTION_GENRE,
                                    title = genreTitle,
                                    selectedValue = selectedGenre,
                                    options = listOf(AppleFilterOption(allLabel, null)) +
                                        remoteFilter.genres.map {
                                            AppleFilterOption(localizedGenreLabel(filterContext, it), it)
                                        }
                                )
                            )
                        } else if (genreYearFilter.genreOptions.isNotEmpty()) {
                            add(
                                AppleFilterSection(
                                    key = FILTER_SECTION_GENRE,
                                    title = genreTitle,
                                    selectedValue = selectedGenre,
                                    options = listOf(AppleFilterOption(allLabel, null)) +
                                        genreYearFilter.genreOptions.map {
                                            AppleFilterOption(localizedGenreLabel(filterContext, it.label), it.key, count(it.count))
                                        }
                                )
                            )
                        }
                        if (remoteYears) {
                            // Every year the addon can filter this catalog by.
                            add(
                                AppleFilterSection(
                                    key = FILTER_SECTION_YEAR,
                                    title = yearTitle,
                                    selectedValue = selectedYear,
                                    options = listOf(AppleFilterOption(allLabel, null)) +
                                        remoteFilter.years.map { AppleFilterOption(it, it) }
                                )
                            )
                        } else if (genreYearFilter.yearOptions.isNotEmpty()) {
                            add(
                                AppleFilterSection(
                                    key = FILTER_SECTION_YEAR,
                                    title = yearTitle,
                                    selectedValue = selectedYear,
                                    options = listOf(AppleFilterOption(allLabel, null)) +
                                        genreYearFilter.yearOptions.map {
                                            AppleFilterOption(it.label, it.key, count(it.count))
                                        }
                                )
                            )
                        }
                    }
                }
                // The pill shows what is picked ("Action · 2024"), or "Filters" when nothing is.
                val activeSummary = listOfNotNull(
                    selectedType?.let { com.nuvio.tv.ui.util.filterTypeLabel(filterContext, it) },
                    selectedGenre?.let { localizedGenreLabel(filterContext, it) },
                    selectedYear
                ).joinToString(" · ")
                AppleFilterMenuButton(
                    label = activeSummary.ifEmpty { filtersLabel },
                    sections = filterSections,
                    hasActiveFilter = selectedType != null || selectedGenre != null || selectedYear != null,
                    clearLabel = stringResource(R.string.apple_filter_clear),
                    onSelect = { sectionKey, option ->
                        when (sectionKey) {
                            FILTER_SECTION_TYPE -> selectedType = option.value
                            FILTER_SECTION_GENRE -> {
                                selectedGenre = option.value
                                if (remoteGenres) genreFilterViewModel.select(option.value)
                            }
                            FILTER_SECTION_YEAR -> {
                                selectedYear = option.value
                                if (remoteYears) genreFilterViewModel.select(option.value)
                            }
                        }
                        focusedItemKey = null
                        filterScope.launch { runCatching { gridState.scrollToItem(0) } }
                    },
                    onClearAll = {
                        selectedType = null
                        selectedGenre = null
                        selectedYear = null
                        genreFilterViewModel.select(null)
                        focusedItemKey = null
                        filterScope.launch { runCatching { gridState.scrollToItem(0) } }
                    },
                    buttonFocusRequester = filterRequester
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        val hasItems = filteredItems.isNotEmpty()
        val isCatalogLoading = catalogRow == null || baseLoading
        val isFilteredEmpty = hasRawItems && !isCatalogLoading && filteredItems.isEmpty()

        if (hasItems) {
            // filteredItems is only non-empty when catalogRow is non-null (see genreYearFilter above).
            val row = checkNotNull(catalogRow)
            // Same as the row it came from: horizontal 16:9 cards with the title on the picture, or
            // vertical posters when the website set that row to vertical.
            val rowLayout = com.nuvio.tv.ui.components.LocalRowLayout.current
            val verticalGrid = remember(rowLayout, isSearchMode, row.catalogId, row.catalogName) {
                rowLayout.isVertical(
                    if (isSearchMode) com.nuvio.tv.ui.components.RowLayoutPages.SEARCH
                    else com.nuvio.tv.ui.components.RowLayoutPages.HOME,
                    row.catalogId,
                    row.catalogName
                )
            }
            val gridCardWidth = if (verticalGrid) posterCardStyle.width else com.nuvio.tv.ui.components.AppleLandscapeCardWidth
            val gridCardHeight = if (verticalGrid) posterCardStyle.height else com.nuvio.tv.ui.components.AppleLandscapeCardHeight
            Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = gridCardWidth),
                    modifier = Modifier.dpadRepeatThrottle(),
                    contentPadding = PaddingValues(
                        start = AppleTvContentStart,
                        end = AppleTvContentStart,
                        top = 14.dp,
                        bottom = 60.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(AppleTvSpacing.GridGap),
                    verticalArrangement = Arrangement.spacedBy(AppleTvSpacing.RowGap)
                ) {
                    itemsIndexed(
                        items = filteredItems,
                        key = { index, _ -> filteredItemKeys.getOrElse(index) { "${row.stableKey()}_$index" } }
                    ) { index, item ->
                        val isWatched = if (isSearchMode) {
                            val isSeries = item.apiType.equals("series", ignoreCase = true) || item.apiType.equals("tv", ignoreCase = true)
                            if (isSeries) item.id in (searchWatchedSeriesIds?.value ?: emptySet())
                            else item.id in (searchWatchedMovieIds?.value ?: emptySet())
                        } else {
                            uiState.movieWatchedStatus[
                                com.nuvio.tv.ui.screens.home.homeItemStatusKey(item.id, item.apiType)
                            ] == true
                        }
                        val itemFocusKey = catalogItemFocusKey(item)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                // Right on the last title of a row goes up to the filter.
                                .rightAtRowEndFocuses(if (hasRawItems && hasAnyFilterOptions) filterRequester else null) {
                                    gridState.isRowEnd(index, filteredItems.size)
                                },
                            contentAlignment = Alignment.TopCenter
                        ) {
                        val onCardFocus: (com.nuvio.tv.domain.model.MetaPreview) -> Unit = {
                            // While restoring after Details/resume, ignore transient focus on the
                            // first/leftmost cell so it cannot overwrite the saved poster key.
                            if (!(shouldRestoreFocus &&
                                    focusedItemKey != null &&
                                    itemFocusKey != focusedItemKey)
                            ) {
                                focusedItemKey = itemFocusKey
                            }
                        }
                        val onCardClick: () -> Unit = {
                            focusedItemKey = itemFocusKey
                            onNavigateToDetail(
                                item.id,
                                item.apiType,
                                row.addonBaseUrl
                            )
                        }
                        val onCardLongPress: () -> Unit = {
                            focusedItemKey = itemFocusKey
                            posterOptionsController.show(item, row.addonBaseUrl)
                        }
                        if (verticalGrid) {
                            ContentCard(
                                item = item,
                                posterCardStyle = posterCardStyle,
                                showLabels = false,
                                isWatched = isWatched,
                                appleStyle = true,
                                focusRequester = if (index == focusedItemIndex) restoreFocusRequester else null,
                                onFocus = onCardFocus,
                                onClick = onCardClick,
                                onLongPress = onCardLongPress
                            )
                        } else
                        com.nuvio.tv.ui.components.AppleLandscapeCard(
                            item = item,
                            cornerRadius = posterCardStyle.cornerRadius,
                            isWatched = isWatched,
                            focusRequester = if (index == focusedItemIndex) restoreFocusRequester else null,
                            onFocus = {
                                // While restoring after Details/resume, ignore transient focus on the
                                // first/leftmost cell so it cannot overwrite the saved poster key.
                                if (!(shouldRestoreFocus &&
                                        focusedItemKey != null &&
                                        itemFocusKey != focusedItemKey)
                                ) {
                                    focusedItemKey = itemFocusKey
                                }
                            },
                            onClick = {
                                focusedItemKey = itemFocusKey
                                onNavigateToDetail(
                                    item.id,
                                    item.apiType,
                                    row.addonBaseUrl
                                )
                            },
                            onLongPress = {
                                focusedItemKey = itemFocusKey
                                posterOptionsController.show(item, row.addonBaseUrl)
                            }
                        )
                        }
                    }

                    if (baseLoading) {
                        item(key = "loading_more") {
                            val cardShape = remember(posterCardStyle.cornerRadius) {
                                androidx.compose.foundation.shape.RoundedCornerShape(posterCardStyle.cornerRadius)
                            }
                            val cardDepthStyle = com.nuvio.tv.ui.components.LocalCardDepthStyle.current
                            Column(
                                modifier = Modifier
                                    .width(gridCardWidth)
                            ) {
                                androidx.tv.material3.Card(
                                    onClick = {},
                                    modifier = Modifier
                                        .width(gridCardWidth)
                                        .height(gridCardHeight)
                                        .then(Modifier.focusProperties { canFocus = false }),
                                    shape = androidx.tv.material3.CardDefaults.shape(shape = cardShape),
                                    colors = androidx.tv.material3.CardDefaults.colors(
                                        containerColor = NuvioTheme.colors.BackgroundCard,
                                        focusedContainerColor = NuvioTheme.colors.BackgroundCard
                                    )
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(cardShape)
                                            .then(
                                                Modifier.nuvioCardDepth(
                                                    shape = cardShape,
                                                    surface = com.nuvio.tv.domain.model.CardDepthSurface.POSTERS,
                                                    style = cardDepthStyle
                                                )
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        LoadingIndicator()
                                    }
                                }
                                Spacer(
                                    modifier = Modifier
                                        .width(posterCardStyle.width)
                                        .padding(top = NuvioTheme.spacing.sm)
                                        .height(MaterialTheme.typography.titleMedium.lineHeight.value.dp)
                                )
                            }
                        }
                    }
                }
            }
        } else if (isCatalogLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator()
            }
        } else if (isFilteredEmpty) {
            EmptyScreenState(
                title = stringResource(R.string.catalog_filter_empty_title),
                subtitle = stringResource(R.string.catalog_filter_empty_subtitle),
                icon = Icons.Default.GridView
            )
        } else {
            EmptyScreenState(
                title = stringResource(R.string.catalog_see_all_empty_title),
                subtitle = stringResource(R.string.catalog_see_all_empty_subtitle),
                icon = Icons.Default.GridView
            )
        }

        val posterOptionsState by posterOptionsController.state.collectAsState()
        com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost(
            state = posterOptionsState,
            controller = posterOptionsController,
            onNavigateToDetail = { id, type2, addonBaseUrl ->
                onNavigateToDetail(id, type2, addonBaseUrl)
            }
        )
    }
    } // ambient Box
}
