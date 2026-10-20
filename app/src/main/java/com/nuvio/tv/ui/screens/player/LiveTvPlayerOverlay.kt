package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.data.livetv.LiveEpisode
import com.nuvio.tv.data.livetv.LiveProgramLibrary
import com.nuvio.tv.data.livetv.LiveProgramMatch
import com.nuvio.tv.data.livetv.LiveTvPlayerSession
import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.accentBrush
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** The live player's tabs: icons on the left, "Networks" after the divider (as on YouTube TV). */
private enum class LiveTab { MORE, QUALITY, CAPTIONS, LIBRARY, NETWORKS, EPISODES }

/** Channels in the Networks row (the one playing, the Guide's Custom ones, then the rest). */
private const val NETWORKS_ROW_LIMIT = 60

/**
 * The player's controls for a Live TV channel, in the player's own style (its circular buttons,
 * white on focus, its gradients and accent bar), laid out like YouTube TV: the channel's logo,
 * LIVE, what's on (title, time, how far along), then a row of tabs whose content shows below as
 * each one is focused:
 * - ⋮: audio, aspect ratio, player engine, stats;
 * - HD: the stream's qualities (Auto, 1080p60, 720p...);
 * - CC: no captions, each caption track, caption style;
 * - ✓: the program's TMDB card, Add to library and More info;
 * - Networks: the channels and what's on them; choosing one switches to it;
 * - Episodes (a series on TMDB only): its seasons and episodes, the one airing marked; choosing
 *   one opens the series' page on it.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun LiveTvPlayerOverlay(
    uiState: PlayerUiState,
    liveViewModel: LiveTvPlayerViewModel,
    /** Where focus goes when the controls show (the Networks tab). */
    initialFocusRequester: FocusRequester,
    onEvent: (PlayerEvent) -> Unit,
    onResetHideTimer: () -> Unit,
    onHideControls: () -> Unit,
    onPlayChannel: (LiveTvChannel) -> Unit,
    onOpenTitle: (LiveProgramMatch) -> Unit,
    onOpenEpisode: (LiveProgramMatch, LiveEpisode) -> Unit
) {
    val guide by liveViewModel.guide.collectAsState()
    val nowMs by liveViewModel.nowMs.collectAsState()
    val programState by liveViewModel.program.collectAsState()
    val episodesState by liveViewModel.episodes.collectAsState()
    val channel = remember(guide, uiState.title, uiState.logo) {
        liveViewModel.channelFor(uiState.title, uiState.logo, guide)
    }
    val program = channel?.let { guide.programAt(it, nowMs) }
    LaunchedEffect(program) { liveViewModel.bindProgram(program) }

    var selectedTab by remember { mutableStateOf(LiveTab.NETWORKS) }
    // Episodes: only for a series on TMDB (not a movie, a game or a newscast).
    val seriesMatch = programState.match?.takeIf { !it.isMovie }
    val shownTab = if (selectedTab == LiveTab.EPISODES && seriesMatch == null) LiveTab.NETWORKS else selectedTab
    LaunchedEffect(shownTab, seriesMatch?.tmdbId) {
        if (shownTab == LiveTab.EPISODES) liveViewModel.ensureEpisodes()
    }
    val tabRequesters = remember { LiveTab.entries.associateWith { FocusRequester() } }
    val contentEntry = remember { FocusRequester() }
    val tabFocusRequester: (LiveTab) -> FocusRequester = { tab ->
        if (tab == LiveTab.NETWORKS) initialFocusRequester else tabRequesters.getValue(tab)
    }
    // Up from a tab's content: back onto that tab.
    val upToTab = Modifier.onPreviewKeyEvent { event ->
        if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP
        ) {
            runCatching { tabFocusRequester(selectedTab).requestFocus() }
            true
        } else {
            false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // The player's gradients, the bottom one taller to hold the tabs' content.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.62f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.35f to Color.Black.copy(alpha = 0.55f),
                            1f to Color.Black.copy(alpha = 0.9f)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(start = 40.dp, end = 40.dp, top = 16.dp, bottom = 12.dp)
        ) {
            // The channel and what's on.
            Row(verticalAlignment = Alignment.CenterVertically) {
                val logo = channel?.logoUrl ?: uiState.logo
                if (!logo.isNullOrBlank()) {
                    AsyncImage(
                        model = logo,
                        contentDescription = channel?.name ?: uiState.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .height(28.dp)
                            .widthIn(max = 90.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }
                LiveBadge()
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = program?.let { LiveProgramLibrary.cleanTitle(it.title) } ?: uiState.title,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val secondLine = listOfNotNull(
                program?.subtitle?.takeIf { it.isNotBlank() },
                channel?.name?.takeIf { program != null }
            ).joinToString(" · ")
            if (secondLine.isNotBlank()) {
                Text(
                    text = secondLine,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (program != null) {
                ProgramProgress(program = program, nowMs = nowMs)
                Spacer(modifier = Modifier.height(8.dp))
            }

            // The tabs: focusing one shows its content below.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Down goes into the tab's content (when it has something to choose).
                val contentFocusable = when (shownTab) {
                    LiveTab.LIBRARY -> programState.isLoading || programState.match != null
                    LiveTab.NETWORKS -> guide.channels.isNotEmpty()
                    LiveTab.EPISODES -> episodesState.seasons.isNotEmpty()
                    else -> true
                }
                val tabModifier: (LiveTab) -> Modifier = { tab ->
                    Modifier
                        .focusRequester(tabFocusRequester(tab))
                        .focusProperties { if (contentFocusable) down = contentEntry }
                        .onFocusChanged {
                            if (it.isFocused) {
                                selectedTab = tab
                                onResetHideTimer()
                            }
                        }
                }
                LiveIconTab(
                    icon = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.cd_more_actions),
                    selected = shownTab == LiveTab.MORE,
                    onClick = { runCatching { contentEntry.requestFocus() } },
                    modifier = tabModifier(LiveTab.MORE)
                )
                LiveIconTab(
                    icon = Icons.Default.HighQuality,
                    contentDescription = stringResource(R.string.player_live_quality),
                    selected = shownTab == LiveTab.QUALITY,
                    onClick = { runCatching { contentEntry.requestFocus() } },
                    modifier = tabModifier(LiveTab.QUALITY)
                )
                LiveIconTab(
                    icon = Icons.Default.ClosedCaption,
                    contentDescription = stringResource(R.string.cd_subtitles),
                    selected = shownTab == LiveTab.CAPTIONS,
                    onClick = { runCatching { contentEntry.requestFocus() } },
                    modifier = tabModifier(LiveTab.CAPTIONS)
                )
                LiveIconTab(
                    icon = if (programState.inLibrary) Icons.Default.CheckCircle else Icons.Default.AddCircleOutline,
                    contentDescription = stringResource(R.string.hero_add_to_library),
                    selected = shownTab == LiveTab.LIBRARY,
                    onClick = { runCatching { contentEntry.requestFocus() } },
                    modifier = tabModifier(LiveTab.LIBRARY)
                )
                Box(
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .width(1.dp)
                        .height(20.dp)
                        .background(Color.White.copy(alpha = 0.4f))
                )
                LiveTextTab(
                    icon = Icons.Default.LiveTv,
                    label = stringResource(R.string.player_live_networks),
                    selected = shownTab == LiveTab.NETWORKS,
                    onClick = { runCatching { contentEntry.requestFocus() } },
                    modifier = tabModifier(LiveTab.NETWORKS)
                )
                if (seriesMatch != null) {
                    LiveTextTab(
                        icon = Icons.AutoMirrored.Filled.List,
                        label = stringResource(R.string.player_live_episodes),
                        selected = shownTab == LiveTab.EPISODES,
                        onClick = { runCatching { contentEntry.requestFocus() } },
                        modifier = tabModifier(LiveTab.EPISODES)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // The focused tab's content.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            ) {
                when (shownTab) {
                    LiveTab.EPISODES -> EpisodesContent(
                        state = episodesState,
                        fallbackArt = seriesMatch?.backdrop,
                        entry = contentEntry,
                        seasonModifier = upToTab,
                        onFocused = onResetHideTimer,
                        onSelectSeason = liveViewModel::selectSeason,
                        onChoose = { episode -> seriesMatch?.let { onOpenEpisode(it, episode) } }
                    )
                    LiveTab.NETWORKS -> NetworksRow(
                        guide = guide,
                        current = channel,
                        nowMs = nowMs,
                        entry = contentEntry,
                        itemModifier = upToTab,
                        onFocused = onResetHideTimer,
                        onChoose = { chosen ->
                            if (chosen.id == channel?.id) onHideControls() else onPlayChannel(chosen)
                        }
                    )
                    LiveTab.LIBRARY -> LibraryCard(
                        channel = channel,
                        program = program,
                        state = programState,
                        entry = contentEntry,
                        itemModifier = upToTab,
                        onFocused = onResetHideTimer,
                        onAdd = liveViewModel::addToLibrary,
                        onMoreInfo = onOpenTitle
                    )
                    LiveTab.CAPTIONS -> OptionsRow(
                        options = buildList {
                            val captionsOff = uiState.subtitleTracks.none { it.isSelected } && uiState.selectedAddonSubtitle == null
                            add(LiveOption(glyph = "CC", label = stringResource(R.string.player_live_no_captions), checked = captionsOff) {
                                onEvent(PlayerEvent.OnDisableSubtitles)
                            })
                            uiState.subtitleTracks.forEach { track ->
                                add(LiveOption(glyph = "CC", label = track.name, checked = track.isSelected) {
                                    onEvent(PlayerEvent.OnSelectSubtitleTrack(track.index))
                                })
                            }
                            add(LiveOption(icon = Icons.Default.Edit, label = stringResource(R.string.player_live_caption_style)) {
                                onEvent(PlayerEvent.OnOpenSubtitleStylePanel)
                            })
                        },
                        entry = contentEntry,
                        itemModifier = upToTab,
                        onFocused = onResetHideTimer
                    )
                    LiveTab.QUALITY -> OptionsRow(
                        options = buildList {
                            val autoLabel = stringResource(R.string.player_live_quality_auto)
                            add(
                                LiveOption(
                                    glyph = "A",
                                    label = uiState.playingVideoHeight
                                        ?.takeIf { uiState.selectedVideoHeight == null }
                                        ?.let { "$autoLabel ($it" + "p)" } ?: autoLabel,
                                    checked = uiState.selectedVideoHeight == null
                                ) { onEvent(PlayerEvent.OnSelectVideoQuality(null)) }
                            )
                            uiState.videoQualities.forEach { quality ->
                                add(
                                    LiveOption(
                                        glyph = if (quality.height >= 720) "HD" else "SD",
                                        label = qualityLabel(quality),
                                        checked = uiState.selectedVideoHeight == quality.height
                                    ) { onEvent(PlayerEvent.OnSelectVideoQuality(quality.height)) }
                                )
                            }
                        },
                        entry = contentEntry,
                        itemModifier = upToTab,
                        onFocused = onResetHideTimer
                    )
                    LiveTab.MORE -> OptionsRow(
                        options = buildList {
                            val audio = uiState.audioTracks.firstOrNull { it.isSelected }?.name
                            add(LiveOption(icon = Icons.Default.MusicNote, label = stringResource(R.string.player_live_audio), detail = audio) {
                                onEvent(PlayerEvent.OnShowAudioOverlay)
                            })
                            add(LiveOption(icon = Icons.Default.AspectRatio, label = stringResource(R.string.cd_aspect_ratio)) {
                                onEvent(PlayerEvent.OnToggleAspectRatio)
                            })
                            add(LiveOption(icon = Icons.Default.SwapHoriz, label = stringResource(R.string.cd_switch_player_engine)) {
                                onEvent(PlayerEvent.OnSwitchInternalPlayerEngine)
                            })
                            add(LiveOption(icon = Icons.Default.BarChart, label = stringResource(R.string.player_live_stats)) {
                                onEvent(PlayerEvent.OnShowStreamInfo)
                            })
                        },
                        entry = contentEntry,
                        itemModifier = upToTab,
                        onFocused = onResetHideTimer
                    )
                }
            }
        }
    }
}

/** The red LIVE badge. */
@Composable
private fun LiveBadge() {
    Text(
        text = stringResource(R.string.player_live_badge),
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0xFFE5202E))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

/** How far along the program on now is (the player's accent bar), with its start and end times. */
@Composable
private fun ProgramProgress(program: EpgProgram, nowMs: Long) {
    val accent = NuvioTheme.palette.accentBrush()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.3f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(program.progressAt(nowMs))
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = timeRange(program),
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 12.sp
        )
    }
}

/** A round icon tab, like the player's buttons: white when focused, a soft white when its content shows. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveIconTab(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(34.dp),
        colors = IconButtonDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent,
            focusedContainerColor = Color.White,
            contentColor = Color.White,
            focusedContentColor = Color.Black
        ),
        shape = IconButtonDefaults.shape(shape = CircleShape)
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp))
    }
}

/** A pill tab with an icon and a name ("Networks"). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveTextTab(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(34.dp),
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** The Networks row: the channels and what's on them; the one playing first. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NetworksRow(
    guide: LiveTvPlayerSession.Guide,
    current: LiveTvChannel?,
    nowMs: Long,
    entry: FocusRequester,
    itemModifier: Modifier,
    onFocused: () -> Unit,
    onChoose: (LiveTvChannel) -> Unit
) {
    val channels = remember(guide, current?.id) {
        // Stops as soon as the row is full (a playlist can have thousands of channels).
        val custom = guide.channels.asSequence().filter { it.id in guide.customChannelIds }
        (listOfNotNull(current).asSequence() + custom + guide.channels.asSequence())
            .distinctBy { it.id }
            .take(NETWORKS_ROW_LIMIT)
            .toList()
    }
    LazyRow(
        modifier = Modifier.bleedHorizontally(RowBleed),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(horizontal = RowBleed, vertical = 8.dp)
    ) {
        itemsIndexed(channels, key = { _, item -> item.id }) { index, item ->
            val onNow = guide.programAt(item, nowMs)
            Column(modifier = Modifier.width(176.dp)) {
                Card(
                    onClick = { onChoose(item) },
                    modifier = itemModifier
                        .then(if (index == 0) Modifier.focusRequester(entry) else Modifier)
                        .fillMaxWidth()
                        .height(99.dp)
                        .onFocusChanged { if (it.isFocused) onFocused() },
                    shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp)),
                    colors = CardDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.14f),
                        focusedContainerColor = Color.White.copy(alpha = 0.22f)
                    ),
                    border = CardDefaults.border(
                        border = Border.None,
                        focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(10.dp))
                    ),
                    scale = CardDefaults.scale(focusedScale = 1.04f)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (!item.logoUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = item.logoUrl,
                                contentDescription = item.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 36.dp, vertical = 22.dp)
                            )
                        } else {
                            Text(
                                text = item.name,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(12.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 8.dp, bottom = 8.dp)
                        ) { LiveBadge() }
                        if (onNow != null) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .background(Color.White.copy(alpha = 0.25f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(onNow.progressAt(nowMs))
                                        .background(NuvioTheme.palette.accentBrush())
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = onNow?.let { LiveProgramLibrary.cleanTitle(it.title) } ?: item.name,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(item.name, onNow?.let { airingLabel(it) }).joinToString(" · "),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** ✓: the program's card (TMDB artwork, when and what), with Add to library and More info. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryCard(
    channel: LiveTvChannel?,
    program: EpgProgram?,
    state: LivePlayerProgramState,
    entry: FocusRequester,
    itemModifier: Modifier,
    onFocused: () -> Unit,
    onAdd: () -> Unit,
    onMoreInfo: (LiveProgramMatch) -> Unit
) {
    val match = state.match
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .width(192.dp)
                .height(108.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.12f))
        ) {
            val image = match?.backdrop ?: program?.imageUrl?.takeIf { it.isNotBlank() }
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = match?.name ?: program?.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (!channel?.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = channel?.logoUrl,
                    contentDescription = channel?.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = match?.name ?: program?.let { LiveProgramLibrary.cleanTitle(it.title) } ?: channel?.name.orEmpty(),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (program != null) {
                val details = listOfNotNull(
                    program.subtitle?.takeIf { it.isNotBlank() },
                    match?.year?.takeIf { match.isMovie }?.toString(),
                    program.category?.takeIf { it.isNotBlank() }
                ).joinToString(" · ")
                if (details.isNotBlank()) {
                    Text(text = details, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(text = timeRange(program), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 1)
            }
            val description = program?.description?.takeIf { it.isNotBlank() } ?: match?.overview
            if (!description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            modifier = Modifier.width(190.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when {
                state.isLoading -> LivePillButton(
                    label = stringResource(R.string.livetv_library_looking_up),
                    onClick = {},
                    modifier = itemModifier
                        .focusRequester(entry)
                        .onFocusChanged { if (it.isFocused) onFocused() }
                )
                match != null -> {
                    LivePillButton(
                        label = stringResource(if (state.inLibrary) R.string.player_live_added_to_library else R.string.hero_add_to_library),
                        icon = if (state.inLibrary) Icons.Default.Check else null,
                        onClick = onAdd,
                        enabled = !state.isAdding,
                        modifier = itemModifier
                            .focusRequester(entry)
                            .onFocusChanged { if (it.isFocused) onFocused() }
                    )
                    LivePillButton(
                        label = stringResource(R.string.player_live_more_info),
                        icon = Icons.Default.Info,
                        onClick = { onMoreInfo(match) },
                        modifier = itemModifier.onFocusChanged { if (it.isFocused) onFocused() }
                    )
                }
                else -> Text(
                    text = stringResource(R.string.livetv_library_not_on_tmdb),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

/** A rounded button for the ✓ tab's actions, white on focus. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LivePillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(17.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.16f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            disabledContainerColor = Color.White.copy(alpha = 0.1f),
            disabledContentColor = Color.White.copy(alpha = 0.6f)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 34.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Room at a row's ends for its focused card (a bit bigger, with its white border). */
private val RowBleed = 14.dp

/**
 * Lets a row reach [bleed] past its sides while its first card stays lined up with the tabs: a
 * row cuts off what's outside it, so a focused card at either end (bigger, with its border) would
 * be cut. With the row's own padding of [bleed] at both ends, it shows whole.
 */
private fun Modifier.bleedHorizontally(bleed: Dp): Modifier = layout { measurable, constraints ->
    val extra = bleed.roundToPx()
    val wider = if (constraints.hasBoundedWidth) constraints.copy(maxWidth = constraints.maxWidth + extra * 2) else constraints
    val placeable = measurable.measure(wider)
    val width = (placeable.width - extra * 2).coerceIn(constraints.minWidth, constraints.maxWidth)
    layout(width, placeable.height) { placeable.place(-extra, 0) }
}

/** A choice in an options row (captions, quality, more): an icon or a glyph ("CC", "HD"), a check when chosen. */
private data class LiveOption(
    val icon: ImageVector? = null,
    val glyph: String? = null,
    val label: String,
    val detail: String? = null,
    val checked: Boolean = false,
    val onClick: () -> Unit
)

/** A row of square option cards with their names under them (as on YouTube TV). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OptionsRow(
    options: List<LiveOption>,
    entry: FocusRequester,
    itemModifier: Modifier,
    onFocused: () -> Unit
) {
    LazyRow(
        modifier = Modifier.bleedHorizontally(RowBleed),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = RowBleed, vertical = 8.dp)
    ) {
        itemsIndexed(options) { index, option ->
            Column(
                modifier = Modifier.width(108.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Card(
                    onClick = option.onClick,
                    modifier = itemModifier
                        .then(if (index == 0) Modifier.focusRequester(entry) else Modifier)
                        .fillMaxWidth()
                        .height(86.dp)
                        .onFocusChanged { if (it.isFocused) onFocused() },
                    shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp)),
                    colors = CardDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.14f),
                        focusedContainerColor = Color.White.copy(alpha = 0.26f)
                    ),
                    border = CardDefaults.border(
                        border = Border.None,
                        focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(10.dp))
                    ),
                    scale = CardDefaults.scale(focusedScale = 1.05f)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        when {
                            option.glyph != null -> Text(
                                text = option.glyph,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .border(1.5.dp, Color.White, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                            option.icon != null -> Icon(
                                imageVector = option.icon,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        if (option.checked) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                        } else if (option.detail != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = option.detail,
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = option.label,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Episodes: the series' seasons (focusing one shows its episodes) and the episodes, the one airing
 * now marked LIVE. Choosing an episode opens the series' page on it.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodesContent(
    state: LivePlayerEpisodesState,
    fallbackArt: String?,
    entry: FocusRequester,
    seasonModifier: Modifier,
    onFocused: () -> Unit,
    onSelectSeason: (Int) -> Unit,
    onChoose: (LiveEpisode) -> Unit
) {
    if (state.isLoading || state.seasons.isEmpty()) {
        Text(
            text = stringResource(if (state.isLoading) R.string.livetv_library_looking_up else R.string.player_live_no_episodes),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        // The seasons (all of them composed, so the chosen one can always take focus).
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.seasons.forEach { season ->
                val selected = season.number == state.selectedSeason
                val label = season.name ?: stringResource(R.string.player_live_season, season.number)
                Surface(
                    onClick = { onSelectSeason(season.number) },
                    modifier = seasonModifier
                        .then(if (selected) Modifier.focusRequester(entry) else Modifier)
                        .height(28.dp)
                        .onFocusChanged {
                            if (it.isFocused) {
                                onFocused()
                                onSelectSeason(season.number)
                            }
                        },
                    shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (selected) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.12f),
                        contentColor = Color.White,
                        focusedContainerColor = Color.White,
                        focusedContentColor = Color.Black
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        val listState = rememberLazyListState()
        val airing = state.airing
        // The episode airing now in view.
        LaunchedEffect(state.episodes, airing) {
            if (airing == null) return@LaunchedEffect
            val index = state.episodes.indexOfFirst { it.season == airing.season && it.episode == airing.episode }
            if (index >= 0) runCatching { listState.scrollToItem((index - 1).coerceAtLeast(0)) }
        }
        if (state.isLoadingEpisodes) {
            Text(
                text = stringResource(R.string.livetv_library_looking_up),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
        }
        LazyRow(
            state = listState,
            modifier = Modifier.bleedHorizontally(RowBleed),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = RowBleed, vertical = 6.dp)
        ) {
            itemsIndexed(state.episodes, key = { _, item -> "${item.season}:${item.episode}" }) { _, episode ->
                val isAiring = airing != null && airing.season == episode.season && airing.episode == episode.episode
                Column(modifier = Modifier.width(176.dp)) {
                    Card(
                        onClick = { onChoose(episode) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(99.dp)
                            .onFocusChanged { if (it.isFocused) onFocused() },
                        shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp)),
                        colors = CardDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.14f),
                            focusedContainerColor = Color.White.copy(alpha = 0.22f)
                        ),
                        border = CardDefaults.border(
                            border = Border.None,
                            focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = RoundedCornerShape(10.dp))
                        ),
                        scale = CardDefaults.scale(focusedScale = 1.04f)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            val image = episode.still ?: fallbackArt
                            if (image != null) {
                                AsyncImage(
                                    model = image,
                                    contentDescription = episode.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            if (isAiring) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(start = 8.dp, bottom = 8.dp)
                                ) { LiveBadge() }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = stringResource(R.string.player_live_episode_code, episode.episode) +
                            (episode.name?.let { " · $it" } ?: ""),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    episode.airDate?.let { date ->
                        Text(
                            text = runCatching {
                                LocalDate.parse(date).format(
                                    java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
                                )
                            }.getOrDefault(date),
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/** "1080p60", "720p", "480p". */
private fun qualityLabel(quality: VideoQualityOption): String =
    "${quality.height}p" + if (quality.frameRate >= 49f) "60" else ""

/** "12:00 PM – 6:30 PM". */
private fun timeRange(program: EpgProgram): String {
    val format = SimpleDateFormat("h:mm a", Locale.getDefault())
    return "${format.format(Date(program.startMs))} – ${format.format(Date(program.endMs))}"
}

/** "Today, 12:02 PM – 3:25 PM" (or "Tomorrow, …", or the date). */
@Composable
private fun airingLabel(program: EpgProgram): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(program.startMs).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    val range = timeRange(program)
    return when (day) {
        today -> stringResource(R.string.livetv_details_today, range)
        today.plusDays(1) -> stringResource(R.string.livetv_details_tomorrow, range)
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(program.startMs)) + ", " + range
    }
}
