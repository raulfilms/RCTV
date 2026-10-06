@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.disneyplus

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.data.disneyplus.DisneyPlusHub
import com.nuvio.tv.data.disneyplus.DisneyRowKind
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.ContinueWatchingSection
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsViewModel
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

private const val HERO_AUTO_ADVANCE_MS = 8_000L

/**
 * Disney+ page. The same screen renders the main page (hero, brand tiles, Continue Watching and
 * all rows) and each brand page reached from a tile (hero + that brand's rows).
 */
@Composable
fun DisneyPlusScreen(
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String) -> Unit,
    onOpenHub: (DisneyPlusHub) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    viewModel: DisneyPlusViewModel = hiltViewModel(),
    posterOptionsViewModel: PosterOptionsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val posterOptionsController = posterOptionsViewModel.controller
    val isMain = uiState.hub == DisneyPlusHub.MAIN
    val heroFocusRequester = remember { FocusRequester() }
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }

    // Brand pages are opened from a tile, so put focus on their hero once it's there.
    // The main page is reached from the sidebar, which keeps focus where the person left it.
    LaunchedEffect(uiState.heroItems.isNotEmpty()) {
        if (!isMain && !initialFocusDone && uiState.heroItems.isNotEmpty()) {
            delay(50)
            runCatching { heroFocusRequester.requestFocus() }
            initialFocusDone = true
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        when {
            uiState.isInitialLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            }

            uiState.isFinishedLoading && !uiState.hasAnyContent -> {
                val retryFocusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { retryFocusRequester.requestFocus() } }
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    EmptyScreenState(
                        title = stringResource(R.string.disney_empty_title),
                        subtitle = stringResource(R.string.disney_empty_subtitle),
                        icon = Icons.Default.Movie,
                        height = 240.dp
                    )
                    Button(
                        onClick = viewModel::retry,
                        modifier = Modifier.focusRequester(retryFocusRequester)
                    ) {
                        Text(text = stringResource(R.string.disney_retry))
                    }
                }
            }

            else -> {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = NuvioTheme.spacing.lg, bottom = NuvioTheme.spacing.xxxl),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
                ) {
                    if (!isMain) {
                        item(key = "hub_title") {
                            Text(
                                text = stringResource(uiState.hub.titleRes),
                                style = MaterialTheme.typography.headlineLarge,
                                color = NuvioTheme.colors.TextPrimary,
                                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xxxl)
                            )
                        }
                    }

                    if (uiState.heroItems.isNotEmpty()) {
                        item(key = "hero") {
                            DisneyHeroBanner(
                                items = uiState.heroItems,
                                focusRequester = heroFocusRequester,
                                onItemClick = { item -> onNavigateToDetail(item.id, item.apiType, "") }
                            )
                        }
                    }

                    if (isMain) {
                        item(key = "brand_tiles") {
                            DisneyBrandTiles(onOpenHub = onOpenHub)
                        }
                    }

                    if (uiState.continueWatching.isNotEmpty()) {
                        item(key = "continue_watching") {
                            ContinueWatchingSection(
                                items = uiState.continueWatching,
                                title = stringResource(R.string.disney_row_continue_watching),
                                onItemClick = onContinueWatchingClick,
                                onDetailsClick = { item ->
                                    when (item) {
                                        is ContinueWatchingItem.InProgress -> onNavigateToDetail(
                                            item.progress.contentId,
                                            item.progress.contentType,
                                            item.progress.addonBaseUrl.orEmpty()
                                        )
                                        is ContinueWatchingItem.NextUp -> onNavigateToDetail(
                                            item.info.contentId,
                                            item.info.contentType,
                                            ""
                                        )
                                    }
                                },
                                onRemoveItem = viewModel::removeContinueWatching
                            )
                        }
                    }

                    uiState.rows.forEach { rowState ->
                        if (rowState.spec.kind == DisneyRowKind.COLLECTIONS) {
                            if (rowState.collectionTiles.isNotEmpty()) {
                                item(key = "row_${rowState.spec.id}") {
                                    DisneyCollectionsRow(
                                        title = rowState.title,
                                        tiles = rowState.collectionTiles,
                                        onOpenHub = onOpenHub
                                    )
                                }
                            }
                            return@forEach
                        }
                        val row = rowState.row ?: return@forEach
                        item(key = "row_${rowState.spec.id}") {
                            CatalogRowSection(
                                catalogRow = row,
                                onItemClick = { id, type, addonBaseUrl -> onNavigateToDetail(id, type, addonBaseUrl) },
                                showSeeAll = false,
                                showAddonName = false,
                                showCatalogTypeSuffix = false,
                                onItemLongPress = { item, addonBaseUrl -> posterOptionsController.show(item, addonBaseUrl) }
                            )
                        }
                    }

                    if (!uiState.isFinishedLoading) {
                        item(key = "rows_loading") {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(120.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                LoadingIndicator()
                            }
                        }
                    }
                }
            }
        }

        val posterOptionsState by posterOptionsController.state.collectAsState()
        PosterOptionsHost(
            state = posterOptionsState,
            controller = posterOptionsController,
            onNavigateToDetail = { id, type, addonBaseUrl -> onNavigateToDetail(id, type, addonBaseUrl) }
        )
    }
}

/**
 * Rounded, inset hero banner (like the Disney+ home): backdrop, title logo (or name), a short
 * description, and page dots underneath. Left/Right switch slides while focused, OK opens the title,
 * and it advances on its own every few seconds when not focused.
 */
@Composable
private fun DisneyHeroBanner(
    items: List<MetaPreview>,
    focusRequester: FocusRequester,
    onItemClick: (MetaPreview) -> Unit
) {
    val currentOnItemClick by rememberUpdatedState(onItemClick)
    var activeIndex by remember(items) { mutableIntStateOf(0) }
    var isFocused by remember { mutableStateOf(false) }
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val bannerHeight = (screenHeight * 0.46f).dp.coerceIn(220.dp, 420.dp)
    val shape = RoundedCornerShape(NuvioTheme.radii.lg)

    LaunchedEffect(isFocused, items.size) {
        if (items.size <= 1 || isFocused) return@LaunchedEffect
        while (true) {
            delay(HERO_AUTO_ADVANCE_MS)
            activeIndex = (activeIndex + 1) % items.size
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTheme.spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(bannerHeight)
                .focusRequester(focusRequester)
                .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
                .focusable()
                .onPreviewKeyEvent { event ->
                    when {
                        event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft ->
                            if (activeIndex > 0) { activeIndex--; true } else false
                        event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight ->
                            if (activeIndex < items.size - 1) { activeIndex++; true } else false
                        event.type == KeyEventType.KeyUp && (event.key == Key.DirectionCenter || event.key == Key.Enter) -> {
                            items.getOrNull(activeIndex)?.let(currentOnItemClick)
                            true
                        }
                        else -> false
                    }
                }
                .clip(shape)
                .then(
                    if (isFocused) Modifier.border(NuvioTheme.focusRing.border(3.dp), shape)
                    else Modifier.border(BorderStroke(1.dp, NuvioTheme.colors.Border), shape)
                )
                .background(NuvioTheme.colors.BackgroundCard)
        ) {
            Crossfade(targetState = activeIndex, animationSpec = tween(400), label = "disneyHero") { index ->
                val item = items.getOrNull(index) ?: return@Crossfade
                DisneyHeroSlide(item = item)
            }
        }

        if (items.size > 1) {
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                repeat(items.size) { index ->
                    val active = index == activeIndex
                    Box(
                        modifier = Modifier
                            .size(if (active) 8.dp else 6.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(if (active) Color.White else Color.White.copy(alpha = 0.35f))
                    )
                }
            }
        }
    }
}

@Composable
private fun DisneyHeroSlide(item: MetaPreview) {
    var logoFailed by remember(item.logo) { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model = item.backdropUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize()
        )
        // Darken the left side and bottom so the logo and text stay readable on any artwork.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.82f),
                        0.45f to Color.Black.copy(alpha = 0.45f),
                        0.75f to Color.Transparent
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.6f)
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.42f)
                .padding(start = NuvioTheme.spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            if (!item.logo.isNullOrBlank() && !logoFailed) {
                AsyncImage(
                    model = item.logo,
                    contentDescription = item.name,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    onError = { logoFailed = true },
                    modifier = Modifier.height(88.dp).widthIn(max = 280.dp).fillMaxWidth()
                )
            } else {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val meta = listOfNotNull(
                item.releaseInfo,
                item.imdbRating?.let { String.format(java.util.Locale.US, "★ %.1f", it) }
            ).joinToString("  •  ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1
                )
            }
            item.description?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** The six brand tiles under the hero: Disney, Pixar, Marvel, Star Wars, National Geographic, Hulu. */
@Composable
private fun DisneyBrandTiles(onOpenHub: (DisneyPlusHub) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTheme.spacing.xxxl),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        DisneyPlusHub.brandTiles.forEach { hub ->
            DisneyBrandTile(
                hub = hub,
                onClick = { onOpenHub(hub) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun DisneyBrandTile(
    hub: DisneyPlusHub,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)), shape = shape),
            focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
        ),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        modifier = modifier
            .height(76.dp)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            if (focused) Color(0xFF3A4152) else Color(0xFF2E3340),
                            if (focused) Color(0xFF232836) else Color(0xFF1C2029)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(hub.titleRes),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = if (hub == DisneyPlusHub.NAT_GEO) 13.sp else 18.sp,
                letterSpacing = if (hub == DisneyPlusHub.PIXAR || hub == DisneyPlusHub.STAR_WARS) 2.sp else 0.5.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.sm)
            )
            // Thin brand-colored accent along the bottom edge.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.4f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(hub.accentColor().copy(alpha = if (focused) 1f else 0.6f))
            )
        }
    }
}

private fun DisneyPlusHub.accentColor(): Color = when (this) {
    DisneyPlusHub.DISNEY -> Color(0xFF4FA3FF)
    DisneyPlusHub.PIXAR -> Color(0xFFE8E8E8)
    DisneyPlusHub.MARVEL -> Color(0xFFE62429)
    DisneyPlusHub.STAR_WARS -> Color(0xFFFFE81F)
    DisneyPlusHub.NAT_GEO -> Color(0xFFFFCE00)
    DisneyPlusHub.HULU -> Color(0xFF1CE783)
    DisneyPlusHub.COL_WDAS -> Color(0xFF1E3A8A)
    DisneyPlusHub.COL_DISNEY_JR -> Color(0xFF0E7C86)
    DisneyPlusHub.COL_DISNEY_CHANNEL -> Color(0xFF4C1D95)
    DisneyPlusHub.COL_DISNEY_XD -> Color(0xFF3B0764)
    DisneyPlusHub.COL_DISNEYNATURE -> Color(0xFF0F2A33)
    DisneyPlusHub.COL_DCOM -> Color(0xFF1E1B6B)
    DisneyPlusHub.MAIN -> Color(0xFF4FA3FF)
}

/**
 * The Disney page's "Collections" row: wide cards (Walt Disney Animation Studios, Disney Jr., Disney Channel,
 * Disney XD, Disneynature, Disney Channel Original Movie). Each card shows a strip of backdrops from that
 * collection's titles under a tinted gradient, with the collection name and "COLLECTION" on top.
 */
@Composable
private fun DisneyCollectionsRow(
    title: String,
    tiles: List<DisneyCollectionTile>,
    onOpenHub: (DisneyPlusHub) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xxxl)
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        // Three cards across the screen, like Disney+.
        val screenWidth = LocalConfiguration.current.screenWidthDp.dp
        val cardWidth = ((screenWidth - NuvioTheme.spacing.xxxl * 2 - NuvioTheme.spacing.lg * 2) / 3).coerceIn(200.dp, 420.dp)
        LazyRow(
            contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            items(tiles, key = { it.hub.key }) { tile ->
                DisneyCollectionCard(
                    tile = tile,
                    onClick = { onOpenHub(tile.hub) },
                    modifier = Modifier.width(cardWidth).aspectRatio(16f / 9f)
                )
            }
        }
    }
}

@Composable
private fun DisneyCollectionCard(
    tile: DisneyCollectionTile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.lg)
    val tint = tile.hub.accentColor()
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = tint,
            focusedContainerColor = tint
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), shape = shape),
            focusedBorder = Border(border = BorderStroke(4.dp, Color.White), shape = shape)
        ),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (tile.backdrops.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxSize()) {
                    tile.backdrops.take(4).forEach { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.weight(1f).fillMaxSize()
                        )
                    }
                }
            }
            // Brand-tinted wash so the strip reads as one card and the text stays legible.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to tint.copy(alpha = 0.25f),
                            0.55f to tint.copy(alpha = 0.65f),
                            1f to tint.copy(alpha = 0.95f)
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
            ) {
                Text(
                    text = stringResource(tile.hub.titleRes),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.disney_collection_label),
                    color = Color.White.copy(alpha = 0.9f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    letterSpacing = 4.sp,
                    maxLines = 1
                )
            }
        }
    }
}
