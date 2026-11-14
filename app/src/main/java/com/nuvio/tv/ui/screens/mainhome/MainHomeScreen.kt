package com.nuvio.tv.ui.screens.mainhome

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.tv.material3.Border
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
import kotlinx.coroutines.delay
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

private val Background = Color(0xFF070B18)
private val CardColor = Color(0xFF151C2E)
private val CardBorder = Color(0x33FFFFFF)
private val LiveRed = Color(0xFFE5232B)
private val Dim = Color(0xB3FFFFFF)
private const val HERO_EVERY_MS = 8_000L

/**
 * RCTV (owner): the whole app's Home, laid out like the owner's design: a big banner on top (what's
 * on now, featured titles and the website's banners, in turn) with the next one beside it, then
 * Live Now, Continue Watching, Recommended for You, Browse by Category, Trending on RCTV, Recently
 * Added, Favorite Networks, Popular on RCTV and a Featured Collection; the website's banners go
 * after the row each one names. Movies & TV keeps the Home of movies and series.
 */
@Composable
fun MainHomeScreen(
    continueWatching: List<ContinueWatchingItem>,
    onOpenTitle: (MetaPreview) -> Unit,
    onContinueWatching: (ContinueWatchingItem) -> Unit,
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

    // Recommended for You: like what's being watched.
    val last = continueWatching.firstOrNull()
    LaunchedEffect(last?.let(::contentIdOf)) {
        viewModel.recommendFor(last?.let(::contentIdOf), last?.let(::isSeriesOf) ?: false)
    }
    LaunchedEffect(Unit) {
        repeat(6) {
            delay(150)
            if (runCatching { firstFocus.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    fun playLive(card: LiveNowCard) {
        when (card) {
            is LiveNowCard.Game -> onOpenGame(card.event)
            is LiveNowCard.Channel -> scope.launch { onPlayChannel(viewModel.playable(card.channel)) }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Background),
        contentPadding = PaddingValues(top = 24.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp)
    ) {
        if (state.hero.isNotEmpty()) {
            item(key = "hero") {
                HeroSection(
                    slides = state.hero,
                    firstFocus = firstFocus,
                    onSlide = { slide ->
                        when (slide) {
                            is HeroSlide.Live -> playLive(slide.card)
                            is HeroSlide.Title -> onOpenTitle(slide.item)
                            is HeroSlide.Banner -> onBannerAction(slide.banner.action)
                        }
                    }
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
                    HomeRow(stringResource(R.string.main_home_continue_watching), continueWatching, { "cw_${contentIdOf(it)}_${videoIdOf(it)}" }) { item, modifier ->
                        ContinueCard(item, modifier, onClick = { onContinueWatching(item) })
                    }
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
                    val items = collection.items.take(20)
                    HomeRow(collection.name.ifBlank { stringResource(R.string.main_home_featured) }, items, { "fc_${it.tmdbId}_${it.isSeries}" }) { item, modifier ->
                        WideTile(
                            title = item.title,
                            imageUrl = (item.backdropPath ?: item.posterPath)?.let { "https://image.tmdb.org/t/p/w780$it" },
                            modifier = modifier,
                            onClick = { onOpenCollection(collection.id) }
                        )
                    }
                }
            }
        }
    }
}

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
                WideTile(
                    title = meta.name,
                    imageUrl = meta.landscapePoster ?: meta.background ?: meta.poster,
                    modifier = modifier,
                    onClick = { onOpenTitle(meta) }
                )
            }
        }
    }
}

@Composable
private fun <T> HomeRow(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    content: @Composable (T, Modifier) -> Unit
) {
    Column {
        Text(
            text = title,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 48.dp, bottom = 12.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(items, key = key) { item -> content(item, Modifier) }
        }
    }
}

// ------------------------------------------------------------------------------- Hero

@Composable
private fun HeroSection(slides: List<HeroSlide>, firstFocus: FocusRequester, onSlide: (HeroSlide) -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val count = slides.size
    // Turns on its own (not while the person is on it).
    LaunchedEffect(count, focused) {
        if (count < 2 || focused) return@LaunchedEffect
        while (true) {
            delay(HERO_EVERY_MS)
            index = (index + 1) % count
        }
    }
    val current = slides[index % count]
    val next = slides[(index + 1) % count]
    Row(
        modifier = Modifier.fillMaxWidth().height(300.dp).padding(horizontal = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        FocusCard(
            onClick = { onSlide(current) },
            modifier = Modifier
                .weight(2f)
                .fillMaxHeight()
                .focusRequester(firstFocus)
                .onFocusChanged { focused = it.hasFocus || it.isFocused },
            focusedScale = 1.02f
        ) {
            HeroContent(current, big = true)
            if (count > 1) {
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    repeat(count) { i ->
                        Box(
                            modifier = Modifier
                                .size(if (i == index % count) 9.dp else 7.dp)
                                .background(if (i == index % count) Color.White else Dim.copy(alpha = 0.5f), CircleShape)
                        )
                    }
                }
            }
        }
        FocusCard(
            onClick = { index = (index + 1) % count },
            modifier = Modifier.weight(1f).fillMaxHeight(),
            focusedScale = 1.03f
        ) {
            HeroContent(next, big = false)
        }
    }
}

@Composable
private fun BoxScope.HeroContent(slide: HeroSlide, big: Boolean) {
    val image: String?
    val title: String
    val subtitle: String?
    val live: Boolean
    val button: String
    when (slide) {
        is HeroSlide.Live -> when (val card = slide.card) {
            is LiveNowCard.Game -> {
                image = null
                title = card.event.name
                subtitle = card.event.leagueName
                live = true
                button = stringResource(R.string.main_home_watch_live)
            }
            is LiveNowCard.Channel -> {
                image = card.program?.imageUrl
                title = card.program?.title ?: card.channel.name
                subtitle = card.channel.name
                live = true
                button = stringResource(R.string.main_home_watch_live)
            }
        }
        is HeroSlide.Title -> {
            image = slide.item.background ?: slide.item.landscapePoster
            title = slide.item.name
            subtitle = slide.item.description
            live = false
            button = stringResource(R.string.main_home_more_info)
        }
        is HeroSlide.Banner -> {
            image = slide.banner.imageUrl
            title = slide.banner.title
            subtitle = slide.banner.subtitle
            live = false
            button = slide.banner.button.ifBlank { stringResource(R.string.main_home_more_info) }
        }
    }
    if (image != null) {
        AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else if (slide is HeroSlide.Live && slide.card is LiveNowCard.Game) {
        GameArt(slide.card.event, Modifier.fillMaxSize())
    }
    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.horizontalGradient(listOf(Color(0xE6070B18), Color(0x99070B18), Color.Transparent))
        )
    )
    Column(
        modifier = Modifier.align(Alignment.CenterStart).padding(start = if (big) 32.dp else 20.dp, end = 20.dp).fillMaxWidth(if (big) 0.6f else 0.9f)
    ) {
        if (live) LiveBadge(big)
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = if (big) 34.sp else 24.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = if (big) 38.sp else 28.sp
        )
        subtitle?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Dim, fontSize = if (big) 16.sp else 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (big) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.background(Color.White, RoundedCornerShape(10.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(if (live) Icons.Default.PlayArrow else Icons.Default.Info, contentDescription = null, tint = Color.Black, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(button, color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ------------------------------------------------------------------------------- Cards

@Composable
private fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    focusedScale: Float = 1.05f,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = CardColor,
            focusedContainerColor = CardColor,
            contentColor = Color.White,
            focusedContentColor = Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(1.dp, CardBorder), shape = shape),
            focusedBorder = Border(border = BorderStroke(3.dp, Color.White), shape = shape)
        ),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxSize().clip(shape), content = content)
    }
}

private val WideWidth: Dp = 300.dp
private val WideHeight: Dp = 169.dp

@Composable
private fun LiveCard(card: LiveNowCard, modifier: Modifier, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = modifier.width(WideWidth).height(WideHeight)) {
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
                        modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.5f).height(70.dp).padding(bottom = 24.dp)
                    )
                }
            }
        }
        BottomShade()
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)) { LiveBadge(false) }
        val (title, line) = when (card) {
            is LiveNowCard.Game -> card.event.name to (card.event.leagueName ?: "")
            is LiveNowCard.Channel -> (card.program?.title ?: card.channel.name) to timeRange(card.program?.startMs, card.program?.endMs)
        }
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line.isNotBlank()) Text(line, color = Dim, fontSize = 13.sp, maxLines = 1)
            val program = (card as? LiveNowCard.Channel)?.program
            if (program != null) {
                Spacer(Modifier.height(6.dp))
                val now = System.currentTimeMillis()
                val fraction = ((now - program.startMs).toFloat() / (program.endMs - program.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                ProgressLine(fraction)
            }
        }
    }
}

@Composable
private fun ContinueCard(item: ContinueWatchingItem, modifier: Modifier, onClick: () -> Unit) {
    val (name, image, line, fraction) = when (item) {
        is ContinueWatchingItem.InProgress -> {
            val p = item.progress
            val percent = p.progressPercent?.div(100f)
                ?: if (p.duration > 0) p.position.toFloat() / p.duration else 0f
            Quad(
                p.name,
                item.customLandscapePoster ?: item.episodeThumbnail ?: p.backdrop ?: p.poster,
                if (p.season != null && p.episode != null) "S${p.season} E${p.episode}" + (p.episodeTitle?.let { " · $it" } ?: "") else (item.releaseInfo ?: ""),
                percent.coerceIn(0f, 1f)
            )
        }
        is ContinueWatchingItem.NextUp -> Quad(
            item.info.name,
            item.customLandscapePoster ?: item.info.thumbnail ?: item.info.backdrop ?: item.info.poster,
            "S${item.info.season} E${item.info.episode}" + (item.info.episodeTitle?.let { " · $it" } ?: ""),
            0f
        )
    }
    FocusCard(onClick = onClick, modifier = modifier.width(WideWidth).height(WideHeight)) {
        AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        BottomShade()
        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (line.isNotBlank()) Text(line, color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                ProgressLine(fraction)
            }
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier.size(34.dp).background(Color(0x66000000), CircleShape).padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private data class Quad(val a: String, val b: String?, val c: String, val d: Float)

@Composable
private fun WideTile(title: String, imageUrl: String?, modifier: Modifier, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = modifier.width(WideWidth).height(WideHeight)) {
        AsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        BottomShade()
        Text(
            text = title,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(14.dp)
        )
    }
}

@Composable
private fun CategoryTile(category: MainHomeCategory, modifier: Modifier, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = modifier.width(200.dp).height(110.dp)) {
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(category.color, category.color.copy(alpha = 0.75f)))))
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(category.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(category.label), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun NetworkTile(card: LiveNowCard.Channel, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    FocusCard(onClick = onClick, onLongClick = onLongClick, modifier = modifier.width(220.dp).height(140.dp)) {
        AsyncImage(
            model = card.channel.logoUrl,
            contentDescription = card.channel.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp).fillMaxWidth(0.6f).height(56.dp)
        )
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
            Text(card.program?.title ?: card.channel.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            card.program?.let { program ->
                val now = System.currentTimeMillis()
                Spacer(Modifier.height(5.dp))
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
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 30.dp)) {
            AsyncImage(model = event.awayTeamBadgeUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(64.dp))
            Text("vs", color = Dim, fontSize = 16.sp)
            AsyncImage(model = event.homeTeamBadgeUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(64.dp))
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

private fun videoIdOf(item: ContinueWatchingItem): String = when (item) {
    is ContinueWatchingItem.InProgress -> item.progress.videoId
    is ContinueWatchingItem.NextUp -> item.info.videoId
}

private fun isSeriesOf(item: ContinueWatchingItem): Boolean = when (item) {
    is ContinueWatchingItem.InProgress -> item.progress.contentType.equals("series", ignoreCase = true)
    is ContinueWatchingItem.NextUp -> true
}
