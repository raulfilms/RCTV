@file:OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

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
import com.nuvio.tv.ui.components.HubHeroBanner
import com.nuvio.tv.ui.components.AppleRowTitle
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.nuvio.tv.ui.components.ROW_MAX_ITEMS
import com.nuvio.tv.ui.components.TitlesSeeAllPage
import com.nuvio.tv.ui.components.LocalTitleArtResolver
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.withFrameNanos
import com.nuvio.tv.ui.components.HubLogoHeader
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
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

/** Extra room above each row of a hub page (on top of the list's spacing), like Disney+. */
private val HubRowExtraSpacing = 16.dp

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
    // Disney+ (and its brand and collection pages): horizontal cards with the title's name on them.
    val landscapeTitles = uiState.hub.service == HubService.DISNEY
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

    // Kept out here so the page comes back where it was after a row's See All page closes.
    val listState = rememberLazyListState()
    var heroFocused by remember { mutableStateOf(false) }
    val rowFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    var returnFocusRowId by remember { mutableStateOf<String?>(null) }
    val seeAll = uiState.seeAll

    // Back on a See All page goes back to the row it came from.
    BackHandler(enabled = seeAll != null) {
        returnFocusRowId = seeAll?.rowId
        viewModel.closeSeeAll()
    }
    LaunchedEffect(seeAll == null, returnFocusRowId) {
        val rowId = returnFocusRowId ?: return@LaunchedEffect
        if (seeAll != null) return@LaunchedEffect
        repeat(12) {
            withFrameNanos { }
            val requester = rowFocusRequesters[rowId]
            if (requester != null && runCatching { requester.requestFocus() }.getOrDefault(false)) {
                returnFocusRowId = null
                return@LaunchedEffect
            }
        }
        returnFocusRowId = null
    }

    // Horizontal title cards (rows and See All) find their title art as they appear.
    CompositionLocalProvider(LocalTitleArtResolver provides viewModel.titleArt) {
    Box(modifier = Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        when {
            seeAll != null -> {
                TitlesSeeAllPage(
                    title = seeAll.title,
                    stateKey = "hub_${uiState.hub.key}_${seeAll.rowId}",
                    items = seeAll.items,
                    isLoadingMore = seeAll.isLoadingMore,
                    onItemClick = { item -> onNavigateToDetail(item.id, item.apiType, "") },
                    onItemLongPress = { item -> posterOptionsController.show(item, "") },
                    onLoadMore = viewModel::loadMoreSeeAll,
                    logoRes = uiState.hub.headerLogoRes(),
                    logoDescription = stringResource(uiState.hub.titleRes)
                )
            }

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
                                HubService.PEACOCK -> R.string.peacock_empty_title
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
                // While the hero has focus the page stays at the very top, so the platform logo above it
                // stays in view; every other row scrolls the usual way.
                LaunchedEffect(heroFocused) {
                    if (heroFocused) runCatching { listState.animateScrollToItem(0) }
                }
                val parentSpec = LocalBringIntoViewSpec.current
                val pageSpec = remember(parentSpec) {
                    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                    object : BringIntoViewSpec {
                        override val scrollAnimationSpec: AnimationSpec<Float> = parentSpec.scrollAnimationSpec
                        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                            if (heroFocused) 0f else parentSpec.calculateScrollDistance(offset, size, containerSize)
                    }
                }
                val headerLogoRes = uiState.hub.headerLogoRes()
                CompositionLocalProvider(LocalBringIntoViewSpec provides pageSpec) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = NuvioTheme.spacing.lg, bottom = NuvioTheme.spacing.xxxl),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
                ) {
                    // The platform's (or brand's) logo centered above the hero; collection pages,
                    // which have no logo, keep their name.
                    if (headerLogoRes != null) {
                        item(key = "hub_logo") {
                            HubLogoHeader(
                                logoRes = headerLogoRes,
                                contentDescription = stringResource(uiState.hub.titleRes)
                            )
                        }
                    } else if (!isMain) {
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
                            HubHeroBanner(
                                items = uiState.heroItems,
                                focusRequester = heroFocusRequester,
                                onItemClick = { item -> onNavigateToDetail(item.id, item.apiType, "") },
                                onFocusChange = { heroFocused = it }
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
                                        onOpenHub = onOpenHub,
                                        modifier = Modifier.padding(top = HubRowExtraSpacing)
                                    )
                                }
                            }
                            return@forEach
                        }
                        val row = rowState.row ?: return@forEach
                        val rowId = rowState.spec.id
                        item(key = "row_$rowId") {
                            // Ten titles, then "See All" with the rest of the row. Disney+ pages use
                            // horizontal cards with each title's name (logo) on the artwork, like Disney+.
                            CatalogRowSection(
                                catalogRow = row,
                                onItemClick = { id, type, addonBaseUrl -> onNavigateToDetail(id, type, addonBaseUrl) },
                                showSeeAll = row.items.size > ROW_MAX_ITEMS,
                                maxItems = ROW_MAX_ITEMS,
                                appleStyle = landscapeTitles,
                                appleLandscape = landscapeTitles,
                                appleMaxItems = ROW_MAX_ITEMS,
                                appleRankNumbers = false,
                                contentStart = if (landscapeTitles) NuvioTheme.spacing.xxxl else null,
                                appleSpaciousTitle = true,
                                // Rows get more room between them, like Disney+.
                                modifier = Modifier.padding(top = HubRowExtraSpacing),
                                onSeeAll = { viewModel.openSeeAll(rowId) },
                                rowFocusRequester = rowFocusRequesters.getOrPut(rowId) { FocusRequester() },
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
        }

        val posterOptionsState by posterOptionsController.state.collectAsState()
        PosterOptionsHost(
            state = posterOptionsState,
            controller = posterOptionsController,
            onNavigateToDetail = { id, type, addonBaseUrl -> onNavigateToDetail(id, type, addonBaseUrl) }
        )
    }
    }
}

/**
 * The brand tiles under the hero, the same tiles as Home's streaming services: the app's contrast
 * color with each brand's logo centered on it. Disney+: Disney, Pixar, Marvel, Star Wars, National
 * Geographic, Hulu, ESPN. Paramount+: CBS, BET, Comedy Central, MTV, Nickelodeon, Smithsonian Channel.
 * discovery+: discovery+ Originals, HGTV, Food Network, TLC, ID, OWN, Animal Planet, Magnolia Network.
 * HBO Max: HBO, Max Originals, DC, Looney Tunes, Warner Bros. Animation, Sesame Workshop, TCM, Crunchyroll.
 * Peacock: Peacock Originals, NBC, Bravo, Telemundo, WWE, NBC Sports, Universal Pictures, Illumination,
 * DreamWorks Animation, Focus Features, Working Title Films, Amblin Partners.
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

/**
 * The logo centered above the hero: the platform's own logo on a service's main page, the brand's logo
 * on a brand page; null for collection pages (they keep their name as text).
 */
@DrawableRes
private fun DisneyPlusHub.headerLogoRes(): Int? = when {
    isMain -> when (service) {
        HubService.DISNEY -> R.drawable.streaming_logo_disney
        HubService.PARAMOUNT -> R.drawable.streaming_logo_paramount
        HubService.DISCOVERY -> R.drawable.streaming_logo_discovery
        HubService.HBO_MAX -> R.drawable.streaming_logo_hbomax
        HubService.PEACOCK -> R.drawable.streaming_logo_peacock
    }
    // Its tile is a full picture, so the page header uses the discovery+ logo.
    this == DisneyPlusHub.D_ORIGINALS -> R.drawable.streaming_logo_discovery
    this in DisneyPlusHub.brandTiles(service) -> brandLogoRes()
    else -> null
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
    DisneyPlusHub.H_TCM -> R.drawable.hbomax_brand_logo_tcm
    DisneyPlusHub.H_CRUNCHYROLL -> R.drawable.hbomax_brand_logo_crunchyroll
    DisneyPlusHub.PK_ORIGINALS -> R.drawable.peacock_brand_logo_originals
    DisneyPlusHub.PK_NBC -> R.drawable.peacock_brand_logo_nbc
    DisneyPlusHub.PK_BRAVO -> R.drawable.peacock_brand_logo_bravo
    DisneyPlusHub.PK_TELEMUNDO -> R.drawable.peacock_brand_logo_telemundo
    DisneyPlusHub.PK_WWE -> R.drawable.peacock_brand_logo_wwe
    DisneyPlusHub.PK_NBC_SPORTS -> R.drawable.peacock_brand_logo_nbcsports
    DisneyPlusHub.PK_UNIVERSAL -> R.drawable.peacock_brand_logo_universal
    DisneyPlusHub.PK_ILLUMINATION -> R.drawable.peacock_brand_logo_illumination
    DisneyPlusHub.PK_DREAMWORKS -> R.drawable.peacock_brand_logo_dreamworks
    DisneyPlusHub.PK_FOCUS -> R.drawable.peacock_brand_logo_focus
    DisneyPlusHub.PK_WORKING_TITLE -> R.drawable.peacock_brand_logo_workingtitle
    DisneyPlusHub.PK_AMBLIN -> R.drawable.peacock_brand_logo_amblin
    else -> R.drawable.disney_brand_logo_disney
}

/** A collection's tile picture (bundled, as on Disney+); null draws the tinted backdrop card instead. */
@DrawableRes
private fun DisneyPlusHub.collectionArtRes(): Int? = when (this) {
    DisneyPlusHub.COL_WDAS -> R.drawable.disney_collection_wdas
    DisneyPlusHub.COL_DISNEY_JR -> R.drawable.disney_collection_disney_jr
    DisneyPlusHub.COL_DISNEY_CHANNEL -> R.drawable.disney_collection_disney_channel
    DisneyPlusHub.COL_DISNEY_XD -> R.drawable.disney_collection_disney_xd
    DisneyPlusHub.COL_DISNEYNATURE -> R.drawable.disney_collection_disneynature
    DisneyPlusHub.COL_DCOM -> R.drawable.disney_collection_dcom
    DisneyPlusHub.COL_TOY_STORY -> R.drawable.disney_collection_toy_story
    DisneyPlusHub.COL_CARS -> R.drawable.disney_collection_cars
    DisneyPlusHub.COL_INCREDIBLES -> R.drawable.disney_collection_incredibles
    DisneyPlusHub.COL_INSIDE_OUT -> R.drawable.disney_collection_inside_out
    DisneyPlusHub.COL_PIXAR_SHORTS -> R.drawable.disney_collection_pixar_shorts
    DisneyPlusHub.COL_SPARKSHORTS -> R.drawable.disney_collection_sparkshorts
    DisneyPlusHub.COL_FORKY -> R.drawable.disney_collection_forky
    DisneyPlusHub.COL_INFINITY_SAGA -> R.drawable.disney_collection_infinity_saga
    DisneyPlusHub.COL_AVENGERS -> R.drawable.disney_collection_avengers
    DisneyPlusHub.COL_IRON_SUIT -> R.drawable.disney_collection_iron_suit
    DisneyPlusHub.COL_SPIDER_MAN -> R.drawable.disney_collection_spider_man
    DisneyPlusHub.COL_THOR -> R.drawable.disney_collection_thor
    DisneyPlusHub.COL_BLACK_PANTHER -> R.drawable.disney_collection_black_panther
    DisneyPlusHub.COL_BLACK_WIDOW -> R.drawable.disney_collection_black_widow
    DisneyPlusHub.COL_ANT_MAN -> R.drawable.disney_collection_ant_man
    DisneyPlusHub.COL_DOCTOR_STRANGE -> R.drawable.disney_collection_doctor_strange
    DisneyPlusHub.COL_LOKI -> R.drawable.disney_collection_loki
    DisneyPlusHub.COL_WANDA_VISION -> R.drawable.disney_collection_wanda_vision
    DisneyPlusHub.COL_FALCON_WINTER_SOLDIER -> R.drawable.disney_collection_falcon_winter_soldier
    DisneyPlusHub.COL_DAREDEVIL -> R.drawable.disney_collection_daredevil
    DisneyPlusHub.COL_SKYWALKER_SAGA -> R.drawable.disney_collection_skywalker_saga
    DisneyPlusHub.COL_MANDALORIAN_GROGU -> R.drawable.disney_collection_mandalorian_grogu
    DisneyPlusHub.COL_DARTH_VADER -> R.drawable.disney_collection_darth_vader
    DisneyPlusHub.COL_OBI_WAN -> R.drawable.disney_collection_obi_wan
    DisneyPlusHub.COL_STAR_WARS_TALES -> R.drawable.disney_collection_star_wars_tales
    DisneyPlusHub.COL_LUCASFILM_ANIMATION -> R.drawable.disney_collection_lucasfilm_animation
    DisneyPlusHub.COL_STAR_WARS_VINTAGE -> R.drawable.disney_collection_star_wars_vintage
    DisneyPlusHub.COL_NG_ANIMALS_NATURE -> R.drawable.disney_collection_animals_nature
    DisneyPlusHub.COL_NG_EXPLORING -> R.drawable.disney_collection_exploring_world
    DisneyPlusHub.COL_NG_SPACE -> R.drawable.disney_collection_space_exploration
    DisneyPlusHub.COL_NG_EARTH_MONTH -> R.drawable.disney_collection_earth_month
    DisneyPlusHub.COL_NG_OUR_HOME -> R.drawable.disney_collection_our_home
    DisneyPlusHub.COL_NG_VETS_PETS -> R.drawable.disney_collection_vets_pets
    DisneyPlusHub.COL_NG_SHARKS -> R.drawable.disney_collection_sharks
    DisneyPlusHub.COL_DISNEY100 -> R.drawable.disney_collection_disney100
    DisneyPlusHub.COL_AVATAR -> R.drawable.disney_collection_avatar
    DisneyPlusHub.COL_BLUEY -> R.drawable.disney_collection_bluey
    DisneyPlusHub.COL_SIMPSONS -> R.drawable.disney_collection_simpsons
    DisneyPlusHub.COL_TREEHOUSE_OF_HORROR -> R.drawable.disney_collection_treehouse_of_horror
    DisneyPlusHub.COL_PHINEAS_FERB -> R.drawable.disney_collection_phineas_ferb
    DisneyPlusHub.COL_LILO_STITCH -> R.drawable.disney_collection_lilo_stitch
    DisneyPlusHub.COL_ZOOTOPIA -> R.drawable.disney_collection_zootopia
    DisneyPlusHub.COL_DESCENDANTS -> R.drawable.disney_collection_descendants
    DisneyPlusHub.COL_CHIP_DALE -> R.drawable.disney_collection_chip_dale
    DisneyPlusHub.COL_DISNEY_FAIRIES -> R.drawable.disney_collection_disney_fairies
    DisneyPlusHub.COL_TRON -> R.drawable.disney_collection_tron
    DisneyPlusHub.COL_AIR_BUD -> R.drawable.disney_collection_air_bud
    DisneyPlusHub.COL_PRINCESSES -> R.drawable.disney_collection_princesses
    DisneyPlusHub.COL_THROWBACKS -> R.drawable.disney_collection_throwbacks
    DisneyPlusHub.COL_MUSICALS -> R.drawable.disney_collection_musicals
    DisneyPlusHub.COL_PRIDE -> R.drawable.disney_collection_pride
    DisneyPlusHub.COL_BLACK_STORIES -> R.drawable.disney_collection_black_stories
    DisneyPlusHub.COL_HALLOWEEN -> R.drawable.disney_collection_halloween
    DisneyPlusHub.COL_DISNEY_PARKS -> R.drawable.disney_collection_disney_parks
    DisneyPlusHub.COL_DISNEY_PLUS_INSIDER -> R.drawable.disney_collection_disney_plus_insider
    else -> null
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
    DisneyPlusHub.H_TCM -> Color(0xFF3A3A3A)
    DisneyPlusHub.H_CRUNCHYROLL -> Color(0xFFF78C25)
    DisneyPlusHub.PEACOCK_MAIN -> Color(0xFF6E55DC)
    DisneyPlusHub.PK_ORIGINALS -> Color(0xFF6E55DC)
    DisneyPlusHub.PK_NBC -> Color(0xFF069DE0)
    DisneyPlusHub.PK_BRAVO -> Color(0xFF3A3A3A)
    DisneyPlusHub.PK_TELEMUNDO -> Color(0xFFEB1010)
    DisneyPlusHub.PK_WWE -> Color(0xFFD71920)
    DisneyPlusHub.PK_NBC_SPORTS -> Color(0xFFFCCC12)
    DisneyPlusHub.PK_UNIVERSAL -> Color(0xFF1B3A6B)
    DisneyPlusHub.PK_ILLUMINATION -> Color(0xFFF5C400)
    DisneyPlusHub.PK_DREAMWORKS -> Color(0xFF0B2E6E)
    DisneyPlusHub.PK_FOCUS -> Color(0xFF3A3A3A)
    DisneyPlusHub.PK_WORKING_TITLE -> Color(0xFF3A3A3A)
    DisneyPlusHub.PK_AMBLIN -> Color(0xFF2B2B2B)
    else -> Color(0xFF1E3A8A)
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
    onOpenHub: (DisneyPlusHub) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Same row name as the title rows: bold, with room before the cards.
        AppleRowTitle(
            text = title,
            contentStart = NuvioTheme.spacing.xxxl,
            bottomGap = 10.dp,
            bold = true
        )
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
        val artRes = tile.hub.collectionArtRes()
        if (artRes != null) {
            // The collection's own tile picture, name and "COLLECTION" already on it.
            Image(
                painter = painterResource(id = artRes),
                contentDescription = stringResource(tile.hub.titleRes),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else Box(modifier = Modifier.fillMaxSize()) {
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
