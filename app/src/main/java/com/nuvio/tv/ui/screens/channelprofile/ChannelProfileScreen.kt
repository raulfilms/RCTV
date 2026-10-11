@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nuvio.tv.ui.screens.channelprofile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.AppleGlassButton
import com.nuvio.tv.ui.components.AppleGlassIconButton
import com.nuvio.tv.ui.components.AppleTvCardSpacing
import com.nuvio.tv.ui.components.AppleTvColors
import com.nuvio.tv.ui.components.AppleTvContentStart
import com.nuvio.tv.ui.components.AppleTvFocusGlow
import com.nuvio.tv.ui.components.AppleTvFocusScale
import com.nuvio.tv.ui.components.AppleTvPosterHeight
import com.nuvio.tv.ui.components.AppleTvPosterWidth
import com.nuvio.tv.ui.components.AppleTvRadius
import com.nuvio.tv.ui.components.AppleTvSpacing
import com.nuvio.tv.ui.components.AppleTvType
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.ContinueWatchingSection
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsViewModel
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.livetv.ChannelLogoLibrary
import com.nuvio.tv.ui.screens.livetv.LiveTvProgramDetailsOverlay
import com.nuvio.tv.ui.screens.livetv.LiveTvViewModel
import com.nuvio.tv.ui.screens.livetv.cleanProgramTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale

// Sizes in dp (the 960 x 540 TV layout): the design's 1408 px page scaled to fit.
private val HeaderHeight = 84.dp
private val HeaderLogoHeight = 52.dp
private val HeroHeight = 290.dp
private val HeroInfoWidth = 400.dp
private val HeroShape = RoundedCornerShape(AppleTvRadius.Panel)
private val LiveCardWidth = AppleTvSpacing.Col4
private val LiveCardArtHeight = AppleTvSpacing.Col4 * 9f / 16f
private val LiveCardShape = RoundedCornerShape(10.dp)
private val CwCardHeight = 138.dp
private val RowGap = 22.dp
private val BadgeRed = Color(0xFFD42A20)
private const val HERO_AUTO_ADVANCE_MS = 9_000L

/**
 * A channel's profile page, opened from its logo in the Guide. Top to bottom: the channel's
 * logo, a featured show, its live channels (ESPN: ESPN, ESPN2, ESPNU, SEC Network, ACC
 * Network...), its local stations (ABC, CBS, NBC, FOX), the person's Continue Watching for
 * this channel, then its TV shows and movies (each row only when it has titles).
 */
@Composable
fun ChannelProfileScreen(
    channelId: String,
    liveTvViewModel: LiveTvViewModel,
    onPlayChannel: (LiveTvChannel) -> Unit,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String) -> Unit,
    onPlayTitle: (MetaPreview) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    viewModel: ChannelProfileViewModel = hiltViewModel(),
    posterOptionsViewModel: PosterOptionsViewModel = hiltViewModel()
) {
    val liveState by liveTvViewModel.uiState.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val posterOptionsController = posterOptionsViewModel.controller

    val selected = remember(liveState.channels, channelId) { liveState.channels.firstOrNull { it.id == channelId } }
    val brand = remember(selected?.id, selected?.name) { selected?.let(ChannelBrands::brandOf) }
    val epg = liveState.epgByChannel
    val members = remember(brand, liveState.channels, epg) {
        brand?.let { found ->
            ChannelBrands.members(found, liveState.channels, selected) { channel ->
                channel.epgChannelId?.let { epg[it] }?.isNotEmpty() == true
            }
        }
    }
    // What the family airs: helps find its shows and movies on TMDB.
    val epgSeries = remember(members, epg) { members?.let { ChannelGuideTitles.series(it.live, epg) }.orEmpty() }
    val epgMovies = remember(members, epg) {
        members?.let { ChannelGuideTitles.movies(it.all, epg, System.currentTimeMillis()) }.orEmpty()
    }
    LaunchedEffect(brand, epgSeries, epgMovies) {
        brand?.let { viewModel.bind(it, epgSeries, epgMovies) }
    }

    val logoUrl = remember(brand, selected) {
        brand?.logoName?.let { name -> runCatching { ChannelLogoLibrary.logoUriFor(name, null) }.getOrNull() }
            ?: selected?.logoUrl
    }

    fun openChannel(channel: LiveTvChannel, program: EpgProgram?) {
        if (liveState.isPreviewGuide) {
            // Sample guide channels have no stream: show what's on instead.
            (program ?: liveState.currentProgram(channel))?.let { liveTvViewModel.openProgramDetails(channel, it) }
        } else {
            scope.launch {
                val url = liveTvViewModel.resolvePlaybackUrl(channel)
                onPlayChannel(channel.copy(streamUrl = url))
            }
        }
    }

    // A program opened here closes with Back, and never stays open on the Guide.
    val detailsOpen = liveState.selectedProgram != null && liveState.selectedProgramChannel != null
    BackHandler(enabled = detailsOpen) { liveTvViewModel.dismissProgramDetails() }
    DisposableEffect(Unit) { onDispose { liveTvViewModel.dismissProgramDetails() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppleTvColors.Background)
    ) {
        if (selected == null || brand == null || members == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
        } else {
            ChannelProfileContent(
                brand = brand,
                logoUrl = logoUrl,
                members = members,
                epg = epg,
                nowMs = liveState.nowMs,
                uiState = uiState,
                onOpenChannel = ::openChannel,
                onPlayTitle = onPlayTitle,
                onNavigateToDetail = onNavigateToDetail,
                onToggleLibrary = viewModel::toggleLibrary,
                onContinueWatchingClick = onContinueWatchingClick,
                onRemoveContinueWatching = viewModel::removeContinueWatching,
                onItemLongPress = { item, addonBaseUrl -> posterOptionsController.show(item, addonBaseUrl) }
            )
        }

        val program = liveState.selectedProgram
        val programChannel = liveState.selectedProgramChannel
        if (program != null && programChannel != null) {
            LiveTvProgramDetailsOverlay(
                channel = programChannel,
                program = program,
                nowMs = liveState.nowMs,
                onWatch = if (liveState.isPreviewGuide) {
                    null
                } else {
                    {
                        liveTvViewModel.dismissProgramDetails()
                        openChannel(programChannel, program)
                    }
                },
                onDismiss = liveTvViewModel::dismissProgramDetails
            )
        }

        val posterOptionsState by posterOptionsController.state.collectAsState()
        PosterOptionsHost(
            state = posterOptionsState,
            controller = posterOptionsController,
            onNavigateToDetail = { id, type, addonBaseUrl -> onNavigateToDetail(id, type, addonBaseUrl) }
        )
    }
}

@Composable
private fun ChannelProfileContent(
    brand: ChannelBrand,
    logoUrl: String?,
    members: ChannelBrandMembers,
    epg: Map<String, List<EpgProgram>>,
    nowMs: Long,
    uiState: ChannelProfileUiState,
    onOpenChannel: (LiveTvChannel, EpgProgram?) -> Unit,
    onPlayTitle: (MetaPreview) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onToggleLibrary: (MetaPreview) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onRemoveContinueWatching: (ContinueWatchingItem) -> Unit,
    onItemLongPress: (MetaPreview, String) -> Unit
) {
    val context = LocalContext.current
    val timeFormat = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    fun timeRange(program: EpgProgram): String =
        timeFormat.format(Date(program.startMs)) + " – " + timeFormat.format(Date(program.endMs))

    val heroPlayRequester = remember { FocusRequester() }
    val firstLiveRequester = remember { FocusRequester() }
    var pageHasFocus by remember { mutableStateOf(false) }
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }

    suspend fun focusFirst(requester: FocusRequester): Boolean {
        repeat(10) {
            withFrameNanos { }
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) return true
        }
        return false
    }

    // Opening the page: focus the featured show's Play button once it's there (or the first
    // live channel when there's no featured show), unless the person already moved.
    LaunchedEffect(uiState.isHeroLoading, uiState.heroItems.isNotEmpty()) {
        if (initialFocusDone || pageHasFocus || uiState.isHeroLoading) return@LaunchedEffect
        val target = if (uiState.heroItems.isNotEmpty()) heroPlayRequester else firstLiveRequester
        initialFocusDone = focusFirst(target) || focusFirst(firstLiveRequester)
    }
    LaunchedEffect(Unit) {
        delay(2_500)
        if (!initialFocusDone && !pageHasFocus) initialFocusDone = focusFirst(firstLiveRequester)
    }

    val posterStyle = remember {
        PosterCardDefaults.Style.copy(
            width = AppleTvPosterWidth,
            height = AppleTvPosterHeight,
            cornerRadius = AppleTvRadius.Poster,
            focusedBorderWidth = 0.dp,
            focusedScale = AppleTvFocusScale
        )
    }

    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { pageHasFocus = it.hasFocus },
        contentPadding = PaddingValues(bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(RowGap)
    ) {
        item(key = "header") {
            ProfileHeader(brand = brand, logoUrl = logoUrl)
        }

        if (uiState.heroItems.isNotEmpty()) {
            item(key = "hero") {
                ChannelHero(
                    items = uiState.heroItems,
                    inLibrary = uiState.heroInLibrary,
                    playRequester = heroPlayRequester,
                    onPlay = onPlayTitle,
                    onDetails = { item -> onNavigateToDetail(item.id, item.apiType, "") },
                    onToggleLibrary = onToggleLibrary
                )
            }
        } else if (uiState.isHeroLoading) {
            item(key = "hero_loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppleTvSpacing.SafeX)
                        .height(HeroHeight)
                        .clip(HeroShape)
                        .background(AppleTvColors.ContrastDim),
                    contentAlignment = Alignment.Center
                ) { LoadingIndicator() }
            }
        }

        if (members.live.isNotEmpty()) {
            item(key = "live") {
                LiveChannelsRow(
                    title = stringResource(R.string.channel_profile_live_broadcasts),
                    showLiveDot = true,
                    channels = members.live,
                    epg = epg,
                    nowMs = nowMs,
                    timeRange = ::timeRange,
                    firstRequester = firstLiveRequester,
                    onOpen = onOpenChannel
                )
            }
        }

        if (brand.hasLocals && members.locals.isNotEmpty()) {
            item(key = "locals") {
                LiveChannelsRow(
                    title = stringResource(R.string.channel_profile_local_channels),
                    showLiveDot = false,
                    channels = members.locals,
                    epg = epg,
                    nowMs = nowMs,
                    timeRange = ::timeRange,
                    // With no main channels here, the first local takes the page's first focus.
                    firstRequester = if (members.live.isEmpty()) firstLiveRequester else null,
                    onOpen = onOpenChannel
                )
            }
        }

        if (uiState.continueWatching.isNotEmpty()) {
            item(key = "continue_watching") {
                ContinueWatchingSection(
                    items = uiState.continueWatching,
                    title = stringResource(R.string.channel_profile_continue_watching),
                    onItemClick = onContinueWatchingClick,
                    onDetailsClick = { item ->
                        when (item) {
                            is ContinueWatchingItem.InProgress -> onNavigateToDetail(
                                item.progress.contentId,
                                item.progress.contentType,
                                item.progress.addonBaseUrl.orEmpty()
                            )
                            is ContinueWatchingItem.NextUp -> onNavigateToDetail(item.info.contentId, item.info.contentType, "")
                        }
                    },
                    onRemoveItem = onRemoveContinueWatching,
                    cardWidth = AppleTvSpacing.Col4,
                    imageHeight = CwCardHeight,
                    cornerRadius = AppleTvRadius.Poster,
                    appleStyle = true
                )
            }
        }

        uiState.tvShows?.let { row ->
            item(key = "tv_shows") {
                CatalogRowSection(
                    catalogRow = row,
                    onItemClick = { id, type, addonBaseUrl -> onNavigateToDetail(id, type, addonBaseUrl) },
                    showSeeAll = false,
                    posterCardStyle = posterStyle,
                    showPosterLabels = false,
                    showAddonName = false,
                    showCatalogTypeSuffix = false,
                    onItemLongPress = onItemLongPress,
                    appleStyle = true,
                    // Every title of the channel, no "See All" card.
                    appleMaxItems = row.items.size
                )
            }
        }

        uiState.movies?.let { row ->
            item(key = "movies") {
                CatalogRowSection(
                    catalogRow = row,
                    onItemClick = { id, type, addonBaseUrl -> onNavigateToDetail(id, type, addonBaseUrl) },
                    showSeeAll = false,
                    posterCardStyle = posterStyle,
                    showPosterLabels = false,
                    showAddonName = false,
                    showCatalogTypeSuffix = false,
                    onItemLongPress = onItemLongPress,
                    appleStyle = true,
                    // Every title of the channel, no "See All" card.
                    appleMaxItems = row.items.size
                )
            }
        }

        if (uiState.isRowsLoading) {
            item(key = "rows_loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp),
                    contentAlignment = Alignment.Center
                ) { LoadingIndicator() }
            }
        }
    }
}

/** The channel's logo, centered at the top (its name when there's no logo). */
@Composable
private fun ProfileHeader(brand: ChannelBrand, logoUrl: String?) {
    var logoFailed by remember(logoUrl) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HeaderHeight)
            .padding(top = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        if (logoUrl != null && !logoFailed) {
            AsyncImage(
                model = logoUrl,
                contentDescription = brand.name,
                contentScale = ContentScale.Fit,
                onError = { logoFailed = true },
                modifier = Modifier
                    .height(HeaderLogoHeight)
                    .widthIn(max = 260.dp)
            )
        } else {
            Text(
                text = brand.name,
                style = AppleTvType.Title2,
                color = AppleTvColors.Label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Row name; the live row has a red dot before it, like the design. */
@Composable
private fun ProfileRowHeader(text: String, showLiveDot: Boolean) {
    Row(
        modifier = Modifier.padding(start = AppleTvContentStart, end = AppleTvContentStart, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showLiveDot) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(AppleTvColors.Destructive)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = AppleTvType.Headline,
            color = AppleTvColors.Label,
            maxLines = 1
        )
    }
}

@Composable
private fun LiveChannelsRow(
    title: String,
    showLiveDot: Boolean,
    channels: List<LiveTvChannel>,
    epg: Map<String, List<EpgProgram>>,
    nowMs: Long,
    timeRange: (EpgProgram) -> String,
    firstRequester: FocusRequester?,
    onOpen: (LiveTvChannel, EpgProgram?) -> Unit
) {
    Column {
        ProfileRowHeader(text = title, showLiveDot = showLiveDot)
        LazyRow(
            contentPadding = PaddingValues(horizontal = AppleTvContentStart, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(AppleTvCardSpacing)
        ) {
            itemsIndexed(channels, key = { _, channel -> channel.id }) { index, channel ->
                val program = channel.epgChannelId?.let { epg[it] }?.firstOrNull { it.isAiringAt(nowMs) }
                LiveBroadcastCard(
                    channel = channel,
                    program = program,
                    nowMs = nowMs,
                    timeText = program?.let(timeRange),
                    onClick = { onOpen(channel, program) },
                    modifier = if (index == 0 && firstRequester != null) Modifier.focusRequester(firstRequester) else Modifier
                )
            }
        }
    }
}

/**
 * One live channel: what's on now (its art, or the channel's logo), a red progress line, then
 * the show and "ESPN2 · 2:00 – 3:00 PM". Grows with a soft shadow when focused.
 */
@Composable
private fun LiveBroadcastCard(
    channel: LiveTvChannel,
    program: EpgProgram?,
    nowMs: Long,
    timeText: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val channelName = remember(channel.name) { ChannelBrands.displayName(channel.name) }
    val logo = channel.logoUrl?.takeIf { it.isNotBlank() }
    val art = program?.imageUrl?.takeIf { it.isNotBlank() }
    var artFailed by remember(art) { mutableStateOf(false) }
    val liveLabel = stringResource(R.string.livetv_guide_live_badge)
    val noInfo = stringResource(R.string.livetv_guide_no_info)

    Card(
        onClick = onClick,
        modifier = modifier.width(LiveCardWidth),
        shape = CardDefaults.shape(shape = LiveCardShape),
        colors = CardDefaults.colors(
            containerColor = AppleTvColors.Contrast,
            focusedContainerColor = AppleTvColors.ContrastHigh
        ),
        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None),
        scale = CardDefaults.scale(focusedScale = AppleTvFocusScale),
        glow = CardDefaults.glow(focusedGlow = AppleTvFocusGlow)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LiveCardArtHeight)
                    .background(AppleTvColors.ContrastDim)
            ) {
                if (art != null && !artFailed) {
                    AsyncImage(
                        model = art,
                        contentDescription = program?.title,
                        contentScale = ContentScale.Crop,
                        onError = { artFailed = true },
                        modifier = Modifier.fillMaxSize()
                    )
                    if (logo != null) {
                        // Which channel it is, on the art (big enough to read from the couch).
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(7.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color.Black.copy(alpha = 0.55f))
                                .padding(horizontal = 7.dp, vertical = 4.dp)
                        ) {
                            AsyncImage(
                                model = logo,
                                contentDescription = channelName,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .height(24.dp)
                                    .widthIn(max = 84.dp)
                            )
                        }
                    }
                } else if (logo != null) {
                    AsyncImage(
                        model = logo,
                        contentDescription = channelName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth(0.62f)
                            .fillMaxHeight(0.5f)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.LiveTv,
                        contentDescription = channelName,
                        tint = AppleTvColors.LabelOnContrast.copy(alpha = 0.5f),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(30.dp)
                    )
                }
                if (program?.isLive == true) {
                    Text(
                        text = liveLabel.uppercase(Locale.getDefault()),
                        style = AppleTvType.Caption2,
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(BadgeRed)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
            // How far into the show it is.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.16f))
            ) {
                if (program != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(program.progressAt(nowMs))
                            .fillMaxHeight()
                            .background(AppleTvColors.Destructive)
                    )
                }
            }
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                Text(
                    text = program?.title?.let(::cleanProgramTitle) ?: channelName,
                    style = AppleTvType.Caption1,
                    color = AppleTvColors.Label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (program != null) listOfNotNull(channelName, timeText).joinToString(" · ") else noInfo,
                    style = AppleTvType.Caption2,
                    color = AppleTvColors.LabelOnContrast,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * The featured banner under the logo: a show's (or movie's) backdrop with its title logo,
 * a line about it, and Play / My List / More Info. It changes every few seconds while the
 * buttons aren't focused, and "›" shows the next one.
 */
@Composable
private fun ChannelHero(
    items: List<MetaPreview>,
    inLibrary: Map<String, Boolean>,
    playRequester: FocusRequester,
    onPlay: (MetaPreview) -> Unit,
    onDetails: (MetaPreview) -> Unit,
    onToggleLibrary: (MetaPreview) -> Unit
) {
    var activeIndex by remember(items) { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val safeIndex = activeIndex.coerceIn(0, items.size - 1)
    val active = items[safeIndex]

    fun showNext() {
        if (items.size > 1) activeIndex = (safeIndex + 1) % items.size
    }

    LaunchedEffect(focused, items.size) {
        if (items.size <= 1 || focused) return@LaunchedEffect
        while (true) {
            delay(HERO_AUTO_ADVANCE_MS)
            activeIndex = (activeIndex + 1) % items.size
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppleTvSpacing.SafeX)
            .height(HeroHeight)
            .clip(HeroShape)
            .background(AppleTvColors.ContrastDim)
            .onFocusChanged { focused = it.hasFocus }
    ) {
        Crossfade(targetState = safeIndex, animationSpec = tween(durationMillis = 450), label = "channelHeroArt") { index ->
            AsyncImage(
                model = items.getOrNull(index)?.background,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.CenterEnd,
                modifier = Modifier.fillMaxSize()
            )
        }
        // The left side darkens into the page color so the text reads, like the design.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to AppleTvColors.Background.copy(alpha = 0.95f),
                        0.4f to AppleTvColors.Background.copy(alpha = 0.82f),
                        0.7f to Color.Transparent
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to AppleTvColors.Background.copy(alpha = 0.55f)
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 28.dp, end = 28.dp)
                .width(HeroInfoWidth)
        ) {
            Crossfade(targetState = safeIndex, animationSpec = tween(durationMillis = 350), label = "channelHeroInfo") { index ->
                items.getOrNull(index)?.let { HeroInfo(item = it) }
            }
            Spacer(modifier = Modifier.height(16.dp))
            // The buttons stay put while the slides change, so focus never jumps.
            Row(
                modifier = Modifier
                    .focusRestorer { playRequester }
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(AppleTvSpacing.Space3),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppleGlassButton(
                    onClick = { onPlay(active) },
                    shape = AppleTvRadius.Pill,
                    minWidth = 116.dp,
                    modifier = Modifier
                        .focusRequester(playRequester)
                        .height(38.dp)
                ) { color ->
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = color,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(AppleTvSpacing.Space1))
                        Text(
                            text = stringResource(R.string.hero_play),
                            color = color,
                            style = AppleTvType.Callout,
                            maxLines = 1
                        )
                    }
                }
                AppleGlassIconButton(
                    icon = if (inLibrary[active.id] == true) Icons.Filled.Check else Icons.Filled.Add,
                    onClick = { onToggleLibrary(active) }
                )
                AppleGlassIconButton(
                    icon = Icons.Outlined.Info,
                    onClick = { onDetails(active) }
                )
                if (items.size > 1) {
                    AppleGlassIconButton(
                        icon = Icons.Filled.ChevronRight,
                        onClick = { showNext() },
                        bare = true,
                        modifier = Modifier.onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                                showNext()
                                true
                            } else {
                                false
                            }
                        }
                    )
                }
            }
        }

        if (items.size > 1) {
            HeroDots(
                count = items.size,
                activeIndex = safeIndex,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = 14.dp)
            )
        }
    }
}

@Composable
private fun HeroInfo(item: MetaPreview) {
    val isMovie = item.type == ContentType.MOVIE
    var logoFailed by remember(item.logo) { mutableStateOf(false) }
    Column {
        Text(
            text = stringResource(if (isMovie) R.string.channel_profile_featured_movie else R.string.channel_profile_featured_show),
            style = AppleTvType.Caption2.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
            color = Color.White,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(AppleTvColors.Contrast)
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        val logo = item.logo?.takeIf { it.isNotBlank() }
        if (logo != null && !logoFailed) {
            AsyncImage(
                model = logo,
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                onError = { logoFailed = true },
                modifier = Modifier
                    .height(64.dp)
                    .widthIn(max = 300.dp)
            )
        } else {
            Text(
                text = item.name,
                style = AppleTvType.Title2,
                color = AppleTvColors.Label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        val meta = listOfNotNull(
            item.releaseInfo?.takeIf { it.isNotBlank() },
            stringResource(if (isMovie) R.string.channel_profile_movie else R.string.channel_profile_series),
            item.imdbRating?.let { String.format(Locale.US, "★ %.1f", it) }
        ).joinToString("  ·  ")
        Text(
            text = meta,
            style = AppleTvType.Caption1,
            color = AppleTvColors.LabelSecondary,
            maxLines = 1
        )
        item.description?.takeIf { it.isNotBlank() }?.let { description ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = description,
                style = AppleTvType.Body,
                color = AppleTvColors.LabelSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HeroDots(count: Int, activeIndex: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { index ->
            val active = index == activeIndex
            val width by animateDpAsState(
                targetValue = if (active) 16.dp else 5.dp,
                animationSpec = tween(durationMillis = 250),
                label = "channelHeroDot"
            )
            Box(
                modifier = Modifier
                    .size(width = width, height = 5.dp)
                    .clip(CircleShape)
                    .background(if (active) Color.White else Color.White.copy(alpha = 0.45f))
            )
        }
    }
}
