package com.nuvio.tv.ui.screens.mainhome

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.rctvcontrol.RctvBanner
import com.nuvio.tv.core.rctvcontrol.RctvBannerAction
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.RctvHomeBanner
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import com.nuvio.tv.core.poster.withCustomPosterUrls
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.AppleHeroCarousel
import com.nuvio.tv.ui.components.AppleLandscapeCard
import com.nuvio.tv.ui.components.AppleLandscapeCardHeight
import com.nuvio.tv.ui.components.AppleLandscapeCardWidth
import com.nuvio.tv.ui.components.AppleRowTitle
import com.nuvio.tv.ui.components.AppleTvCardSpacing
import com.nuvio.tv.ui.components.AppleTvColors
import com.nuvio.tv.ui.components.AppleTvContentStart
import com.nuvio.tv.ui.components.AppleTvFocusGlow
import com.nuvio.tv.ui.components.AppleTvFocusScale
import com.nuvio.tv.ui.components.AppleTvRadius
import com.nuvio.tv.ui.components.AppleTvSpacing
import com.nuvio.tv.ui.components.AppleTvType
import com.nuvio.tv.ui.components.ContinueWatchingSection
import com.nuvio.tv.ui.components.LocalTitleArtResolver
import com.nuvio.tv.ui.components.tileSurfaceBorder
import com.nuvio.tv.ui.screens.home.HomeUiState
import com.nuvio.tv.ui.screens.home.homeItemStatusKey
import com.nuvio.tv.ui.util.asStable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** "Browse by Category": where each tile goes. */
enum class MainHomeCategory(@StringRes val label: Int, val icon: ImageVector, val color: Color) {
    LIVE_TV(R.string.main_home_cat_live_tv, Icons.Default.Tv, Color(0xFF1E4FA8)),
    SHOWS(R.string.main_home_cat_shows, Icons.Default.PlayArrow, Color(0xFF5B2A9E)),
    NEWS(R.string.main_home_cat_news, Icons.Default.Newspaper, Color(0xFFB3202A)),
    SPORTS(R.string.main_home_cat_sports, Icons.Default.SportsSoccer, Color(0xFF16713F)),
    MOVIES(R.string.main_home_cat_movies, Icons.Default.Movie, Color(0xFF8A6418)),
    DOCUMENTARIES(R.string.main_home_cat_documentaries, Icons.Default.Videocam, Color(0xFF3B2D9A)),
    KIDS(R.string.main_home_cat_kids, Icons.Default.ChildCare, Color(0xFF1659B8))
}

private val LiveRed = Color(0xFFE5232B)
private val Dim = Color(0xB3FFFFFF)

/** How far the page scrolls before the banner's picture has faded away (as on Movies & TV). */
private val HERO_FADE_DISTANCE = 200.dp

/** Keys of the banner's slides that aren't titles (live channels, the website's banners). */
private const val HERO_SLIDE_PREFIX = "rctv_home_slide:"

/**
 * RCTV (owner): the whole app's Home, as Movies & TV looks (its big banner with the picture behind
 * the page, its rows and cards): the banner (what's on now, featured titles and the website's
 * banners, in turn), then Live Now, Continue Watching, Recommended for You, Browse by Category,
 * Trending on RCTV, Recently Added, a row per Guide category live now (Entertainment, Movies &
 * Events, News & Information, Family & Kids, Latino, Sports, Premium), Favorite Networks, Popular
 * on RCTV and a Featured Collection; the website's banners go after the row each one names. Every
 * card of a movie or series shows its TMDB title art (its name on the picture), as on Movies & TV;
 * live channels show their own picture and logo.
 */
@Composable
fun MainHomeScreen(
    homeState: HomeUiState,
    onOpenTitle: (MetaPreview) -> Unit,
    /** The banner's Play on a title: its page, playing. */
    onPlayTitle: (MetaPreview) -> Unit,
    onToggleLibrary: (MetaPreview) -> Unit,
    onTitleShown: (MetaPreview) -> Unit,
    onContinueWatching: (ContinueWatchingItem) -> Unit,
    onContinueWatchingDetails: (ContinueWatchingItem) -> Unit,
    onContinueWatchingFromBeginning: (ContinueWatchingItem) -> Unit,
    onRemoveContinueWatching: (ContinueWatchingItem) -> Unit,
    onOpenGame: (SportsEvent) -> Unit,
    onPlayChannel: (LiveTvChannel) -> Unit,
    onOpenChannelProfile: (LiveTvChannel) -> Unit,
    onBannerAction: (RctvBannerAction) -> Unit,
    onOpenCollection: (String) -> Unit,
    onCategory: (MainHomeCategory) -> Unit,
    viewModel: MainHomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }
    val continueWatching = if (homeState.continueWatchingEnabled) homeState.continueWatchingItems else emptyList()
    // RCTV: "Creating Your Experience" waits for this Home (the old Home said when it was in; this
    // one says it once TMDB's rows are in, or after a while), then for the rest it loads.
    val startupLoading = com.nuvio.tv.ui.components.LocalStartupLoadingState.current
    val rowsIn = state.trending.isNotEmpty() || state.popular.isNotEmpty() || state.recentlyAdded.isNotEmpty()
    LaunchedEffect(startupLoading, rowsIn) {
        val loading = startupLoading ?: return@LaunchedEffect
        if (loading.complete) return@LaunchedEffect
        if (!rowsIn) delay(HOME_READY_MAX_MS)
        loading.complete = true
    }
    // RCTV (owner): the app left on a channel opens on it again, once "Creating Your Experience" is gone.
    val splashOn = com.nuvio.tv.ui.components.LocalStartupSplashEnabled.current
    LaunchedEffect(Unit) {
        startupLoading?.let { loading ->
            androidx.compose.runtime.snapshotFlow { loading.complete && (loading.experienceReady || !splashOn) }.first { it }
        }
        viewModel.resumeChannel()?.let(onPlayChannel)
    }

    // Recommended for You: like what's being watched.
    val last = continueWatching.firstOrNull()
    LaunchedEffect(last?.let(::contentIdOf)) {
        viewModel.recommendFor(last?.let(::contentIdOf), last?.let(::isSeriesOf) ?: false)
    }

    fun playLive(card: LiveNowCard) {
        when (card) {
            is LiveNowCard.Game -> onOpenGame(card.event)
            is LiveNowCard.Channel -> scope.launch { onPlayChannel(viewModel.playable(card.channel)) }
        }
    }

    // The banner, as Movies & TV's: each slide a picture with its name (a title's logo) and buttons.
    val liveLabel = stringResource(R.string.main_home_live)
    val heroSlides = remember(state.hero) { state.hero.filter { it.heroPicture() != null } }
    val heroItems = remember(heroSlides, liveLabel) { heroSlides.map { it.asHeroItem(liveLabel) } }
    fun slideOf(item: MetaPreview): HeroSlide? =
        if (item.id.startsWith(HERO_SLIDE_PREFIX)) heroSlides.firstOrNull { it.key == item.id.removePrefix(HERO_SLIDE_PREFIX) } else null
    val heroVisible = heroItems.isNotEmpty()

    LaunchedEffect(heroVisible) {
        if (!heroVisible) return@LaunchedEffect
        repeat(8) {
            delay(150)
            if (runCatching { firstFocus.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val fadePx = remember(density) { with(density) { HERO_FADE_DISTANCE.toPx() } }
    val heroScrollFraction: () -> Float = remember(listState, fadePx) {
        {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / fadePx).coerceIn(0f, 1f)
        }
    }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    // Like Movies & TV: the banner fills most of the screen and the first row peeks in at the bottom.
    val heroHeight = remember(screenHeight) { (screenHeight - 140.dp).coerceAtLeast(300.dp) }
    val titleArt = viewModel.titleArt

    Box(modifier = Modifier.fillMaxSize().background(AppleTvColors.Background)) {
        CompositionLocalProvider(LocalTitleArtResolver provides titleArt) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = if (heroVisible) 0.dp else AppleTvSpacing.SafeY, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp)
            ) {
                if (heroVisible) {
                    item(key = "hero") {
                        AppleHeroCarousel(
                            items = heroItems.asStable(),
                            heroHeight = heroHeight,
                            imageHeight = screenHeight,
                            scrollFraction = heroScrollFraction,
                            playFocusRequester = firstFocus,
                            onPlay = { item ->
                                when (val slide = slideOf(item)) {
                                    is HeroSlide.Live -> playLive(slide.card)
                                    is HeroSlide.Banner -> onBannerAction(slide.banner.action)
                                    else -> onPlayTitle(item)
                                }
                            },
                            onDetails = { item ->
                                when (val slide = slideOf(item)) {
                                    is HeroSlide.Live -> when (val card = slide.card) {
                                        is LiveNowCard.Game -> onOpenGame(card.event)
                                        is LiveNowCard.Channel -> onOpenChannelProfile(card.channel)
                                    }
                                    is HeroSlide.Banner -> onBannerAction(slide.banner.action)
                                    else -> onOpenTitle(item)
                                }
                            },
                            onToggleLibrary = { item -> if (slideOf(item) == null) onToggleLibrary(item) },
                            isInLibrary = { item ->
                                homeState.posterLibraryMembership[homeItemStatusKey(item.id, item.apiType)] == true
                            },
                            showLibraryButton = { item -> slideOf(item) == null },
                            onActiveItemChanged = { item -> if (slideOf(item) == null) onTitleShown(item) }
                        )
                    }
                }
                rowWithBanners(MainHomeRows.LIVE_NOW, state.banners, onBannerAction) {
                    if (state.liveNow.isNotEmpty()) {
                        item(key = "live_now") {
                            HomeRow(stringResource(R.string.main_home_live_now), state.liveNow, { it.key }) { card, modifier ->
                                LiveCard(card, modifier, onClick = { playLive(card) })
                            }
                        }
                    }
                }
                rowWithBanners(MainHomeRows.CONTINUE_WATCHING, state.banners, onBannerAction) {
                    if (continueWatching.isNotEmpty()) {
                        item(key = "continue_watching") {
                            // Movies & TV's own row: the title's logo on its picture and the progress on it.
                            ContinueWatchingSection(
                                items = continueWatching.withCustomPosterUrls(homeState.customPosterUrlPattern),
                                title = stringResource(R.string.main_home_continue_watching),
                                onItemClick = onContinueWatching,
                                onDetailsClick = onContinueWatchingDetails,
                                onStartFromBeginning = onContinueWatchingFromBeginning,
                                onRemoveItem = onRemoveContinueWatching,
                                blurUnwatchedEpisodes = homeState.blurUnwatchedEpisodes,
                                useEpisodeThumbnails = homeState.useEpisodeThumbnailsInCw,
                                cardWidth = AppleLandscapeCardWidth,
                                imageHeight = AppleLandscapeCardHeight,
                                cardStyle = homeState.continueWatchingCardStyle,
                                cornerRadius = AppleTvRadius.Poster,
                                appleStyle = true
                            )
                        }
                    }
                }
                titlesRow(MainHomeRows.RECOMMENDED, R.string.main_home_recommended, state.recommended, state.banners, onOpenTitle, onBannerAction)
                rowWithBanners(MainHomeRows.CATEGORIES, state.banners, onBannerAction) {
                    item(key = "categories") {
                        val categories = MainHomeCategory.entries.filter { state.withLiveTv || (it != MainHomeCategory.LIVE_TV && it != MainHomeCategory.NEWS) }
                        HomeRow(stringResource(R.string.main_home_categories), categories, { it.name }) { category, modifier ->
                            CategoryTile(category, modifier, onClick = { onCategory(category) })
                        }
                    }
                }
                titlesRow(MainHomeRows.TRENDING, R.string.main_home_trending, state.trending, state.banners, onOpenTitle, onBannerAction)
                titlesRow(MainHomeRows.RECENTLY_ADDED, R.string.main_home_recently_added, state.recentlyAdded, state.banners, onOpenTitle, onBannerAction)
                // RCTV (owner, like YouTube TV's Home): a row per Guide category with its channels live now,
                // in the Guide's order (Entertainment, Movies & Events, News & Information, Family & Kids,
                // Latino, Sports, Premium).
                state.liveCategories.forEach { row ->
                    item(key = "live_${row.category.name}") {
                        HomeRow(
                            stringResource(R.string.main_home_live_category, stringResource(row.category.labelRes)),
                            row.cards,
                            { "live_${row.category.name}_${it.channel.id}" }
                        ) { card, modifier ->
                            LiveCard(
                                card,
                                modifier,
                                onClick = { scope.launch { onPlayChannel(viewModel.playable(card.channel)) } },
                                onLongClick = { onOpenChannelProfile(card.channel) },
                                channelLogo = true
                            )
                        }
                    }
                }
                rowWithBanners(MainHomeRows.FAVORITE_NETWORKS, state.banners, onBannerAction) {
                    if (state.favoriteNetworks.isNotEmpty()) {
                        item(key = "favorite_networks") {
                            HomeRow(stringResource(R.string.main_home_favorite_networks), state.favoriteNetworks, { "fav_${it.channel.id}" }) { card, modifier ->
                                NetworkTile(
                                    card,
                                    modifier,
                                    onClick = { scope.launch { onPlayChannel(viewModel.playable(card.channel)) } },
                                    onLongClick = { onOpenChannelProfile(card.channel) }
                                )
                            }
                        }
                    }
                }
                titlesRow(MainHomeRows.POPULAR, R.string.main_home_popular, state.popular, state.banners, onOpenTitle, onBannerAction)
                state.featuredCollection?.let { collection ->
                    rowWithBanners(MainHomeRows.FEATURED_COLLECTION, state.banners, onBannerAction) {
                        item(key = "featured_collection") {
                            val items = remember(collection) { collection.items.take(ROW_SIZE).map { it.asTitle() } }
                            HomeRow(collection.name.ifBlank { stringResource(R.string.main_home_featured) }, items, { "fc_${it.id}" }) { item, modifier ->
                                AppleLandscapeCard(item = item, onClick = { onOpenCollection(collection.id) }, modifier = modifier)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The banner slide's picture (a game without one isn't a slide: the banner is its picture). */
private fun HeroSlide.heroPicture(): String? = when (this) {
    is HeroSlide.Title -> item.background ?: item.landscapePoster
    is HeroSlide.Live -> (card as? LiveNowCard.Channel)?.program?.imageUrl
    is HeroSlide.Banner -> banner.imageUrl
}?.takeIf { it.isNotBlank() }

/** A slide as the banner shows it: a title as it is; a live channel or a website banner as one. */
private fun HeroSlide.asHeroItem(liveLabel: String): MetaPreview = when (this) {
    is HeroSlide.Title -> item
    is HeroSlide.Live -> {
        val channel = (card as LiveNowCard.Channel)
        val program = channel.program
        MetaPreview(
            id = HERO_SLIDE_PREFIX + key,
            type = ContentType.TV,
            name = program?.title ?: channel.channel.name,
            poster = null,
            posterShape = PosterShape.LANDSCAPE,
            background = heroPicture(),
            logo = null,
            description = program?.description?.takeIf { it.isNotBlank() } ?: timeRange(program?.startMs, program?.endMs),
            releaseInfo = null,
            imdbRating = null,
            genres = listOf(channel.channel.name),
            ageRating = liveLabel
        )
    }
    is HeroSlide.Banner -> MetaPreview(
        id = HERO_SLIDE_PREFIX + key,
        type = ContentType.UNKNOWN,
        rawType = "",
        name = banner.title,
        poster = null,
        posterShape = PosterShape.LANDSCAPE,
        background = heroPicture(),
        logo = null,
        description = banner.subtitle,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )
}

/** A Featured Collection title as a card finds its title art (TMDB's id). */
private fun com.nuvio.tv.core.rctvcontrol.RctvCollectionItem.asTitle(): MetaPreview = MetaPreview(
    id = "tmdb:$tmdbId",
    type = if (isSeries) ContentType.SERIES else ContentType.MOVIE,
    name = title,
    poster = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
    posterShape = PosterShape.LANDSCAPE,
    background = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" },
    logo = null,
    description = null,
    releaseInfo = year?.toString(),
    imdbRating = null,
    genres = emptyList()
)

// ------------------------------------------------------------------------------- Rows

/** A row's items, then the website's banners that go after it. */
private fun LazyListScope.rowWithBanners(
    rowId: String,
    banners: Map<String, List<RctvBanner>>,
    onBannerAction: (RctvBannerAction) -> Unit,
    row: LazyListScope.() -> Unit
) {
    row()
    banners[rowId].orEmpty().forEach { banner ->
        item(key = "banner_${banner.id}") {
            val requester = remember { FocusRequester() }
            RctvHomeBanner(
                banner = banner,
                focusRequester = requester,
                requestFocus = false,
                onClick = { onBannerAction(banner.action) },
                onFocused = {}
            )
        }
    }
}

private fun LazyListScope.titlesRow(
    rowId: String,
    @StringRes title: Int,
    items: List<MetaPreview>,
    banners: Map<String, List<RctvBanner>>,
    onOpenTitle: (MetaPreview) -> Unit,
    onBannerAction: (RctvBannerAction) -> Unit
) = rowWithBanners(rowId, banners, onBannerAction) {
    if (items.isNotEmpty()) {
        item(key = rowId) {
            HomeRow(stringResource(title), items, { "${rowId}_${it.id}" }) { meta, modifier ->
                // Movies & TV's card: the title's name on its picture (TMDB's title art).
                AppleLandscapeCard(item = meta, onClick = { onOpenTitle(meta) }, modifier = modifier)
            }
        }
    }
}

/** A row as Movies & TV's: its name, then its cards from the page's edge. */
@Composable
private fun <T> HomeRow(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    content: @Composable (T, Modifier) -> Unit
) {
    Column {
        AppleRowTitle(text = title, bottomGap = 0.dp)
        LazyRow(
            // Room above and below for a card growing on focus.
            contentPadding = PaddingValues(horizontal = AppleTvContentStart, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(AppleTvCardSpacing)
        ) {
            items(items, key = key) { item -> content(item, Modifier) }
        }
    }
}

// ------------------------------------------------------------------------------- Cards

/** A card as Movies & TV's: no outline at rest; it grows, lifts and gets the white ring on focus. */
@Composable
private fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(AppleTvRadius.Poster)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppleTvColors.Surface2,
            focusedContainerColor = AppleTvColors.Surface2,
            contentColor = Color.White,
            focusedContentColor = Color.White
        ),
        border = tileSurfaceBorder(shape),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = AppleTvFocusScale),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = AppleTvFocusGlow),
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxSize().clip(shape), content = content)
    }
}

/** A live card: the size of Movies & TV's wide cards. */
private val LiveWidth: Dp = AppleLandscapeCardWidth
private val LiveHeight: Dp = AppleLandscapeCardHeight

@Composable
private fun LiveCard(
    card: LiveNowCard,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    /** RCTV: the channel's logo in the corner when the card shows the program's picture. */
    channelLogo: Boolean = false
) {
    FocusCard(onClick = onClick, onLongClick = onLongClick, modifier = modifier.width(LiveWidth).height(LiveHeight)) {
        when (card) {
            is LiveNowCard.Game -> GameArt(card.event, Modifier.fillMaxSize())
            is LiveNowCard.Channel -> {
                val art = card.program?.imageUrl
                if (art != null) {
                    AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    AsyncImage(
                        model = card.channel.logoUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.5f).height(48.dp).padding(bottom = 18.dp)
                    )
                }
            }
        }
        BottomShade()
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) { LiveBadge(false) }
        if (channelLogo && card is LiveNowCard.Channel && card.program?.imageUrl != null) {
            AsyncImage(
                model = card.channel.logoUrl,
                contentDescription = card.channel.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).width(48.dp).height(24.dp)
            )
        }
        val (title, line) = when (card) {
            is LiveNowCard.Game -> card.event.name to (card.event.leagueName ?: "")
            is LiveNowCard.Channel -> (card.program?.title ?: card.channel.name) to timeRange(card.program?.startMs, card.program?.endMs)
        }
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) {
            Text(title, color = Color.White, style = AppleTvType.Caption1, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line.isNotBlank()) Text(line, color = Dim, style = AppleTvType.Caption2, maxLines = 1)
            val program = (card as? LiveNowCard.Channel)?.program
            if (program != null) {
                Spacer(Modifier.height(4.dp))
                val now = System.currentTimeMillis()
                val fraction = ((now - program.startMs).toFloat() / (program.endMs - program.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                ProgressLine(fraction)
            }
        }
    }
}

/** RCTV (owner): Browse by Category's tiles, small (a five-column card) with a small icon. */
@Composable
private fun CategoryTile(category: MainHomeCategory, modifier: Modifier, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = modifier.width(CategoryWidth).height(CategoryHeight)) {
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(category.color, category.color.copy(alpha = 0.75f)))))
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(category.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(4.dp))
            Text(stringResource(category.label), color = Color.White, style = AppleTvType.Caption1, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

private val CategoryWidth: Dp = AppleTvSpacing.Col5
private val CategoryHeight: Dp = AppleTvSpacing.Col5 * (9f / 16f)

@Composable
private fun NetworkTile(card: LiveNowCard.Channel, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    FocusCard(onClick = onClick, onLongClick = onLongClick, modifier = modifier.width(LiveWidth).height(LiveHeight)) {
        AsyncImage(
            model = card.channel.logoUrl,
            contentDescription = card.channel.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp).fillMaxWidth(0.55f).height(44.dp)
        )
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) {
            Text(card.program?.title ?: card.channel.name, color = Color.White, style = AppleTvType.Caption1, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            card.program?.let { program ->
                val now = System.currentTimeMillis()
                Spacer(Modifier.height(4.dp))
                ProgressLine(((now - program.startMs).toFloat() / (program.endMs - program.startMs).coerceAtLeast(1)).coerceIn(0f, 1f))
            }
        }
    }
}

/** A game with no picture: its two teams' logos on the league's dark. */
@Composable
private fun GameArt(event: SportsEvent, modifier: Modifier) {
    Box(
        modifier = modifier.background(Brush.linearGradient(listOf(Color(0xFF0E2A5C), Color(0xFF0A1530), Color(0xFF3A0E1E)))),
        contentAlignment = Alignment.Center
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 22.dp)) {
            AsyncImage(model = event.awayTeamBadgeUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(42.dp))
            Text("vs", color = Dim, fontSize = 13.sp)
            AsyncImage(model = event.homeTeamBadgeUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(42.dp))
        }
    }
}

@Composable
private fun BoxScope.BottomShade() {
    Box(
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.65f)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
    )
}

@Composable
private fun LiveBadge(big: Boolean) {
    Row(
        modifier = Modifier.background(LiveRed, RoundedCornerShape(6.dp)).padding(horizontal = if (big) 10.dp else 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (big) {
            Icon(Icons.Default.LiveTv, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(stringResource(R.string.main_home_live), color = Color.White, fontSize = if (big) 15.sp else 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ProgressLine(fraction: Float) {
    Box(modifier = Modifier.fillMaxWidth().height(4.dp).background(Color(0x55FFFFFF), RoundedCornerShape(2.dp))) {
        Box(modifier = Modifier.fillMaxWidth(fraction).fillMaxHeight().background(LiveRed, RoundedCornerShape(2.dp)))
    }
}

private fun timeRange(start: Long?, end: Long?): String {
    if (start == null || end == null) return ""
    val format = DateFormat.getTimeInstance(DateFormat.SHORT)
    return "${format.format(Date(start))} – ${format.format(Date(end))}"
}

internal fun contentIdOf(item: ContinueWatchingItem): String = when (item) {
    is ContinueWatchingItem.InProgress -> item.progress.contentId
    is ContinueWatchingItem.NextUp -> item.info.contentId
}

private fun isSeriesOf(item: ContinueWatchingItem): Boolean = when (item) {
    is ContinueWatchingItem.InProgress -> item.progress.contentType.equals("series", ignoreCase = true)
    is ContinueWatchingItem.NextUp -> true
}

/** The longest "Creating Your Experience" waits for this Home's rows. */
private const val HOME_READY_MAX_MS = 20_000L

/** At most this many cards in a row (as the other rows). */
private const val ROW_SIZE = 20
