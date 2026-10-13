package com.nuvio.tv.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.util.buildMetaGenreYearFilter
import com.nuvio.tv.ui.util.filterTypeLabel
import com.nuvio.tv.ui.util.localizedGenreLabel
import kotlinx.coroutines.launch

private const val SEE_ALL_FILTER_TYPE = "type"
private const val SEE_ALL_FILTER_GENRE = "genre"
private const val SEE_ALL_FILTER_YEAR = "year"

/**
 * A row's "See All" page: the row's (or brand's) logo and name at the top, a Type / Genre / Year filter
 * at the top right like Home's See All (Type — Movies or TV Shows — only when the list has both), and every title in a grid of 16:9 cards, loading more as the person
 * nears the end. Used by the channel profiles, the streaming hubs and the Netflix / Prime style pages.
 *
 * [stateKey] ties the remembered filter and focused card to this list, so they survive a trip to a
 * title's page and reset for a different row.
 */
@Composable
fun TitlesSeeAllPage(
    title: String,
    stateKey: String,
    items: List<MetaPreview>,
    isLoadingMore: Boolean,
    onItemClick: (MetaPreview) -> Unit,
    onItemLongPress: (MetaPreview) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    logoUrl: String? = null,
    @DrawableRes logoRes: Int? = null,
    logoDescription: String? = null
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    // Type / Genre / Year filter, kept while a title's page is open.
    var selectedType by rememberSaveable(stateKey) { mutableStateOf<String?>(null) }
    var selectedGenre by rememberSaveable(stateKey) { mutableStateOf<String?>(null) }
    var selectedYear by rememberSaveable(stateKey) { mutableStateOf<String?>(null) }
    val filter = remember(items, selectedType, selectedGenre, selectedYear) {
        buildMetaGenreYearFilter(
            items = items,
            selectedType = selectedType,
            selectedGenre = selectedGenre,
            selectedYear = selectedYear
        )
    }
    // Movies / TV Shows: only worth offering when the list has both.
    val typeOptions = if (filter.typeOptions.size > 1 || selectedType != null) filter.typeOptions else emptyList()
    val shownItems = filter.filteredItems

    val currentItems by rememberUpdatedState(shownItems)
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    // The card that last had focus, kept while a title's page is open.
    var focusIndex by rememberSaveable(stateKey) { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    var logoFailed by remember(logoUrl) { mutableStateOf(false) }

    LaunchedEffect(stateKey) {
        val target = focusIndex.coerceIn(0, (shownItems.size - 1).coerceAtLeast(0))
        repeat(12) { attempt ->
            withFrameNanos { }
            if (runCatching { focusRequester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            if (attempt == 2 && gridState.layoutInfo.visibleItemsInfo.none { it.index == target }) {
                runCatching { gridState.scrollToItem(target) }
            }
        }
    }
    // Near the end of what's shown: ask for the next page (with a filter on, that finds more matches).
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= currentItems.size - 8) currentOnLoadMore() }
    }
    fun filterChanged() {
        focusIndex = 0
        scope.launch { runCatching { gridState.scrollToItem(0) } }
        // Few matches on screen: look further.
        onLoadMore()
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = AppleTvSpacing.SafeX, end = AppleTvSpacing.SafeX, top = AppleTvSpacing.SafeY),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (logoRes != null) {
                Image(
                    painter = painterResource(id = logoRes),
                    contentDescription = logoDescription,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .height(30.dp)
                        .widthIn(max = 120.dp)
                )
                Spacer(modifier = Modifier.width(AppleTvSpacing.Space3 + 2.dp))
            } else if (logoUrl != null && !logoFailed) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = logoDescription,
                    contentScale = ContentScale.Fit,
                    onError = { logoFailed = true },
                    modifier = Modifier
                        .height(30.dp)
                        .widthIn(max = 120.dp)
                )
                Spacer(modifier = Modifier.width(AppleTvSpacing.Space3 + 2.dp))
            }
            Text(
                text = title,
                style = AppleTvType.Title2,
                color = AppleTvColors.Label,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )

            // Type / Genre / Year filter, top right: one menu with every section and its counts.
            if (typeOptions.isNotEmpty() || filter.genreOptions.isNotEmpty() || filter.yearOptions.isNotEmpty()) {
                val filterContext = LocalContext.current
                val allLabel = stringResource(R.string.apple_filter_all)
                val typeTitle = stringResource(R.string.library_filter_type)
                val genreTitle = stringResource(R.string.library_filter_genre)
                val yearTitle = stringResource(R.string.library_filter_year)
                val sections = remember(
                    filter, typeOptions, selectedType, selectedGenre, selectedYear,
                    allLabel, typeTitle, genreTitle, yearTitle, filterContext
                ) {
                    buildList {
                        if (typeOptions.isNotEmpty()) {
                            add(
                                AppleFilterSection(
                                    key = SEE_ALL_FILTER_TYPE,
                                    title = typeTitle,
                                    selectedValue = selectedType,
                                    options = listOf(AppleFilterOption(allLabel, null)) +
                                        typeOptions.map {
                                            AppleFilterOption(filterTypeLabel(filterContext, it.label), it.key, it.count)
                                        }
                                )
                            )
                        }
                        if (filter.genreOptions.isNotEmpty()) {
                            add(
                                AppleFilterSection(
                                    key = SEE_ALL_FILTER_GENRE,
                                    title = genreTitle,
                                    selectedValue = selectedGenre,
                                    options = listOf(AppleFilterOption(allLabel, null)) +
                                        filter.genreOptions.map {
                                            AppleFilterOption(localizedGenreLabel(filterContext, it.label), it.key, it.count)
                                        }
                                )
                            )
                        }
                        if (filter.yearOptions.isNotEmpty()) {
                            add(
                                AppleFilterSection(
                                    key = SEE_ALL_FILTER_YEAR,
                                    title = yearTitle,
                                    selectedValue = selectedYear,
                                    options = listOf(AppleFilterOption(allLabel, null)) +
                                        filter.yearOptions.map { AppleFilterOption(it.label, it.key, it.count) }
                                )
                            )
                        }
                    }
                }
                // The pill shows what is picked ("Movies · Comedy · 2024"), or "Filters" when nothing is.
                val summary = listOfNotNull(
                    selectedType?.let { filterTypeLabel(filterContext, it) },
                    selectedGenre?.let { localizedGenreLabel(filterContext, it) },
                    selectedYear
                ).joinToString(" · ")
                AppleFilterMenuButton(
                    label = summary.ifEmpty { stringResource(R.string.apple_filters) },
                    sections = sections,
                    hasActiveFilter = selectedType != null || selectedGenre != null || selectedYear != null,
                    clearLabel = stringResource(R.string.apple_filter_clear),
                    onSelect = { sectionKey, option ->
                        when (sectionKey) {
                            SEE_ALL_FILTER_TYPE -> selectedType = option.value
                            SEE_ALL_FILTER_GENRE -> selectedGenre = option.value
                            SEE_ALL_FILTER_YEAR -> selectedYear = option.value
                        }
                        filterChanged()
                    },
                    onClearAll = {
                        selectedType = null
                        selectedGenre = null
                        selectedYear = null
                        filterChanged()
                    }
                )
            }
        }

        if (shownItems.isEmpty() && items.isNotEmpty() && !isLoadingMore) {
            // The filter matches nothing that's loaded.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppleTvSpacing.SafeX),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.catalog_filter_empty_title),
                    style = AppleTvType.Headline,
                    color = AppleTvColors.Label
                )
                Spacer(modifier = Modifier.height(AppleTvSpacing.Space2))
                Text(
                    text = stringResource(R.string.catalog_filter_empty_subtitle),
                    style = AppleTvType.Body,
                    color = AppleTvColors.LabelSecondary
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = gridState,
                contentPadding = PaddingValues(
                    start = AppleTvSpacing.SafeX,
                    end = AppleTvSpacing.SafeX,
                    top = 20.dp,
                    bottom = 48.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(AppleTvSpacing.GridGap),
                verticalArrangement = Arrangement.spacedBy(28.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(shownItems, key = { _, item -> item.id }) { index, item ->
                    AppleLandscapeCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        width = AppleLandscapeCardWidth,
                        height = AppleLandscapeCardHeight,
                        cornerRadius = AppleTvRadius.Poster,
                        focusRequester = if (index == focusIndex) focusRequester else null,
                        onFocus = { focusIndex = index },
                        onLongPress = { onItemLongPress(item) }
                    )
                }
                if (isLoadingMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp),
                            contentAlignment = Alignment.Center
                        ) { LoadingIndicator() }
                    }
                }
            }
        }
    }
}
