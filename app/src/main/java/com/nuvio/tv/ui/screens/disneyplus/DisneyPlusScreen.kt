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
import androidx.annotation.DrawableRes
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.domain.model.StreamingService
import com.nuvio.tv.ui.components.AppleStreamingServicesRow
import com.nuvio.tv.R
import com.nuvio.tv.data.disneyplus.DisneyPlusHub
import com.nuvio.tv.data.disneyplus.HubService
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
 * Disney+, Paramount+, discovery+ and HBO Max pages. The same screen renders a service's main page (hero, brand tiles,
 * Continue Watching and all rows) and each brand page reached from a tile (hero + that brand's rows).
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
    val isMain = uiState.hub.isMain
    val heroFocusRequester = remember { FocusRequester() }
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }

    // Every page is opened from a tile (Home's streaming tiles or a brand tile), so put focus on
    // the hero once it's there. Coming back to the page keeps focus where the person left it.
    LaunchedEffect(uiState.heroItems.isNotEmpty()) {
        if (!initialFocusDone && uiState.heroItems.isNotEmpty()) {
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
                        title = stringResource(
                            when (uiState.hub.service) {
                                HubService.DISNEY -> R.string.disney_empty_title
                                HubService.PARAMOUNT -> R.string.paramount_empty_title
                                HubService.DISCOVERY -> R.string.discovery_empty_title
                                HubService.HBO_MAX -> R.string.hbomax_empty_title
                            }
                        ),
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
                            DisneyBrandTiles(service = uiState.hub.service, onOpenHub = onOpenHub)
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

/**
 * The brand tiles under the hero, the same tiles as Home's streaming services: the app's contrast
 * color with each brand's logo centered on it. Disney+: Disney, Pixar, Marvel, Star Wars, National
 * Geographic, Hulu, ESPN. Paramount+: CBS, BET, Comedy Central, MTV, Nickelodeon, Smithsonian Channel.
 * discovery+: discovery+ Originals, HGTV, Food Network, TLC, ID, OWN, Animal Planet, Magnolia Network.
 * HBO Max: HBO, Max Originals, DC, Looney Tunes, Warner Bros. Animation, Sesame Workshop, Crunchyroll.
 * Each opens that brand's page.
 */
@Composable
private fun DisneyBrandTiles(service: HubService, onOpenHub: (DisneyPlusHub) -> Unit) {
    val context = LocalContext.current
    val brandHubs = remember(service) { DisneyPlusHub.brandTiles(service) }
    val tiles = remember(context, brandHubs) {
        brandHubs.map { hub ->
            StreamingService(
                key = hub.key,
                name = context.getString(hub.titleRes),
                logoRes = hub.brandLogoRes(),
                // discovery+ Originals is a full picture, not a logo on the gray tile.
                fullBleed = hub == DisneyPlusHub.D_ORIGINALS
            )
        }
    }
    AppleStreamingServicesRow(
        title = null,
        services = tiles,
        onServiceClick = { tile -> brandHubs.firstOrNull { it.key == tile.key }?.let(onOpenHub) },
        // Lined up with the rest of the page.
        contentStart = NuvioTheme.spacing.xxxl
    )
}

/** Each brand tile's logo (bundled, drawn for dark tiles). */
@DrawableRes
private fun DisneyPlusHub.brandLogoRes(): Int = when (this) {
    DisneyPlusHub.PIXAR -> R.drawable.disney_brand_logo_pixar
    DisneyPlusHub.MARVEL -> R.drawable.disney_brand_logo_marvel
    DisneyPlusHub.STAR_WARS -> R.drawable.disney_brand_logo_starwars
    DisneyPlusHub.NAT_GEO -> R.drawable.disney_brand_logo_natgeo
    DisneyPlusHub.HULU -> R.drawable.disney_brand_logo_hulu
    DisneyPlusHub.ESPN -> R.drawable.disney_brand_logo_espn
    DisneyPlusHub.P_CBS -> R.drawable.paramount_brand_logo_cbs
    DisneyPlusHub.P_BET -> R.drawable.paramount_brand_logo_bet
    DisneyPlusHub.P_COMEDY_CENTRAL -> R.drawable.paramount_brand_logo_comedycentral
    DisneyPlusHub.P_MTV -> R.drawable.paramount_brand_logo_mtv
    DisneyPlusHub.P_NICKELODEON -> R.drawable.paramount_brand_logo_nickelodeon
    DisneyPlusHub.P_SMITHSONIAN -> R.drawable.paramount_brand_logo_smithsonian
    DisneyPlusHub.D_ORIGINALS -> R.drawable.discovery_brand_originals
    DisneyPlusHub.D_HGTV -> R.drawable.discovery_brand_logo_hgtv
    DisneyPlusHub.D_FOOD_NETWORK -> R.drawable.discovery_brand_logo_food
    DisneyPlusHub.D_TLC -> R.drawable.discovery_brand_logo_tlc
    DisneyPlusHub.D_ID -> R.drawable.discovery_brand_logo_id
    DisneyPlusHub.D_OWN -> R.drawable.discovery_brand_logo_own
    DisneyPlusHub.D_ANIMAL_PLANET -> R.drawable.discovery_brand_logo_animalplanet
    DisneyPlusHub.D_MAGNOLIA -> R.drawable.discovery_brand_logo_magnolia
    DisneyPlusHub.H_HBO -> R.drawable.hbomax_brand_logo_hbo
    DisneyPlusHub.H_MAX_ORIGINALS -> R.drawable.hbomax_brand_logo_maxoriginals
    DisneyPlusHub.H_DC -> R.drawable.hbomax_brand_logo_dc
    DisneyPlusHub.H_LOONEY_TUNES -> R.drawable.hbomax_brand_logo_looneytunes
    DisneyPlusHub.H_WB_ANIMATION -> R.drawable.hbomax_brand_logo_wbanimation
    DisneyPlusHub.H_SESAME -> R.drawable.hbomax_brand_logo_sesame
    DisneyPlusHub.H_CRUNCHYROLL -> R.drawable.hbomax_brand_logo_crunchyroll
    else -> R.drawable.disney_brand_logo_disney
}

private fun DisneyPlusHub.accentColor(): Color = when (this) {
    DisneyPlusHub.DISNEY -> Color(0xFF4FA3FF)
    DisneyPlusHub.PIXAR -> Color(0xFFE8E8E8)
    DisneyPlusHub.MARVEL -> Color(0xFFE62429)
    DisneyPlusHub.STAR_WARS -> Color(0xFFFFE81F)
    DisneyPlusHub.NAT_GEO -> Color(0xFFFFCE00)
    DisneyPlusHub.HULU -> Color(0xFF1CE783)
    DisneyPlusHub.ESPN -> Color(0xFFE41E26)
    DisneyPlusHub.COL_WDAS -> Color(0xFF1E3A8A)
    DisneyPlusHub.COL_DISNEY_JR -> Color(0xFF0E7C86)
    DisneyPlusHub.COL_DISNEY_CHANNEL -> Color(0xFF4C1D95)
    DisneyPlusHub.COL_DISNEY_XD -> Color(0xFF3B0764)
    DisneyPlusHub.COL_DISNEYNATURE -> Color(0xFF0F2A33)
    DisneyPlusHub.COL_DCOM -> Color(0xFF1E1B6B)
    DisneyPlusHub.MAIN -> Color(0xFF4FA3FF)
    DisneyPlusHub.PARAMOUNT_MAIN -> Color(0xFF0064FF)
    DisneyPlusHub.P_CBS -> Color(0xFF1C3F94)
    DisneyPlusHub.P_BET -> Color(0xFF6B1E8C)
    DisneyPlusHub.P_COMEDY_CENTRAL -> Color(0xFFFFD100)
    DisneyPlusHub.P_MTV -> Color(0xFFE5007E)
    DisneyPlusHub.P_NICKELODEON -> Color(0xFFFF6600)
    DisneyPlusHub.P_SMITHSONIAN -> Color(0xFFF2A900)
    DisneyPlusHub.DISCOVERY_MAIN -> Color(0xFF0050FF)
    DisneyPlusHub.D_ORIGINALS -> Color(0xFF0050FF)
    DisneyPlusHub.D_HGTV -> Color(0xFF2E7D32)
    DisneyPlusHub.D_FOOD_NETWORK -> Color(0xFFD32F2F)
    DisneyPlusHub.D_TLC -> Color(0xFF6A1B9A)
    DisneyPlusHub.D_ID -> Color(0xFFE53935)
    DisneyPlusHub.D_OWN -> Color(0xFFC9A227)
    DisneyPlusHub.D_ANIMAL_PLANET -> Color(0xFF0074FF)
    DisneyPlusHub.D_MAGNOLIA -> Color(0xFF3E4A3D)
    DisneyPlusHub.HBOMAX_MAIN -> Color(0xFF5822B4)
    DisneyPlusHub.H_HBO -> Color(0xFF3A3A3A)
    DisneyPlusHub.H_MAX_ORIGINALS -> Color(0xFF002BE7)
    DisneyPlusHub.H_DC -> Color(0xFF0376F2)
    DisneyPlusHub.H_LOONEY_TUNES -> Color(0xFFD62B2F)
    DisneyPlusHub.H_WB_ANIMATION -> Color(0xFFED1C24)
    DisneyPlusHub.H_SESAME -> Color(0xFF00A651)
    DisneyPlusHub.H_CRUNCHYROLL -> Color(0xFFF78C25)
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
