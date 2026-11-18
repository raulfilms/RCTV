package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewCarousel
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
import com.nuvio.tv.ui.components.tileCardBorder

/** The live player's tabs: icons on the left, "Networks" after the divider (as on YouTube TV). */
private enum class LiveTab { MORE, QUALITY, AUDIO, CAPTIONS, LIBRARY, NETWORKS, CATCH_UP, EPISODES, MORE_TO_WATCH }

/**
 * RCTV (owner): a La Liga or Champions League game's audio: English (its channel in English) or
 * Español (the same game on a Spanish channel). Every other game is in English only.
 */
internal class LiveGameLanguage(val spanish: Boolean, val onChoose: (spanish: Boolean) -> Unit)

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
 * - Catch Up (a channel the IPTV provider records only): its recorded programs, newest first;
 *   choosing one plays it from the recording (the one on now starts over);
 * - Episodes (a series on TMDB only): its seasons and episodes, the one airing marked; choosing
 *   one opens the series' page on it;
 * - More to Watch (always last): titles like what's on and what was watched; choosing one opens its page.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun LiveTvPlayerOverlay(
    uiState: PlayerUiState,
    liveViewModel: LiveTvPlayerViewModel,
    /** Where focus goes when the controls show (the Networks tab). */
    initialFocusRequester: FocusRequester,
    /**
     * RCTV (as YouTube TV): the timeline, which Up reaches from the tabs (and from full screen); Up
     * again goes to the game's stats, else back to full screen.
     */
    progressFocusRequester: FocusRequester,
    onEvent: (PlayerEvent) -> Unit,
    onResetHideTimer: () -> Unit,
    onHideControls: () -> Unit,
    onPlayChannel: (LiveTvChannel) -> Unit,
    onOpenTitle: (LiveProgramMatch) -> Unit,
    onOpenEpisode: (LiveProgramMatch, LiveEpisode) -> Unit,
    /** RCTV: the game center (YouTube TV's stats panel); null when what's on isn't a known game. */
    onOpenGameCenter: (() -> Unit)? = null,
    /** RCTV: English or Español, for a La Liga or Champions League game; null for any other. */
    gameLanguage: LiveGameLanguage? = null,
    /** RCTV (owner): the bug report's list ("Report a Problem", after Stats for nerds). */
    onReportProblem: (() -> Unit)? = null,
    /** RCTV (owner): a title chosen in More to Watch (its page opens). */
    onOpenMoreToWatch: (com.nuvio.tv.domain.model.MetaPreview) -> Unit = {},
    /** RCTV (owner): a recorded program chosen in Catch Up (it plays from the provider's recording). */
    onPlayCatchUp: (LiveTvChannel, com.nuvio.tv.domain.model.LiveCatchUpProgram) -> Unit = { _, _ -> },
    /**
     * RCTV (owner, the DVR as YouTube TV's): a channel the provider records: the timeline moves back
     * and forward through it; null for any other channel (the timeline stays put).
     */
    dvr: LiveDvrUi? = null
) {
    val guide by liveViewModel.guide.collectAsState()
    val nowMs by liveViewModel.nowMs.collectAsState()
    val programState by liveViewModel.program.collectAsState()
    val episodesState by liveViewModel.episodes.collectAsState()
    val recentIds by liveViewModel.recentChannelIds.collectAsState()
    val moreState by liveViewModel.moreToWatch.collectAsState()
    val catchUpState by liveViewModel.catchUp.collectAsState()
    val channel = remember(guide, uiState.title, uiState.logo) {
        liveViewModel.channelFor(uiState.title, uiState.logo, guide)
    }
    // RCTV (owner): Catch Up only on a channel the provider records.
    val hasCatchUp = (channel?.archiveDays ?: 0) > 0
    // RCTV (owner, the DVR): what's on where the timeline is (behind live: what was on then).
    val atMs = dvr?.let { it.previewMs ?: it.positionMs } ?: nowMs
    val program = channel?.let { if (dvr != null) liveViewModel.programAt(it, atMs) else guide.programAt(it, nowMs) }
    LaunchedEffect(program) { liveViewModel.bindProgram(program) }
    // The provider's recordings: what was on before the guide's hours.
    LaunchedEffect(channel?.id, dvr != null) { if (dvr != null) liveViewModel.ensureCatchUp(channel) }

    var selectedTab by remember { mutableStateOf(LiveTab.NETWORKS) }
    // Episodes: only for a series on TMDB (not a movie, a game or a newscast).
    val seriesMatch = programState.match?.takeIf { !it.isMovie }
    val shownTab = when {
        selectedTab == LiveTab.EPISODES && seriesMatch == null -> LiveTab.NETWORKS
        selectedTab == LiveTab.CATCH_UP && !hasCatchUp -> LiveTab.NETWORKS
        else -> selectedTab
    }
    LaunchedEffect(shownTab, channel?.id) {
        if (shownTab == LiveTab.CATCH_UP) liveViewModel.ensureCatchUp(channel)
    }
    LaunchedEffect(shownTab, seriesMatch?.tmdbId) {
        if (shownTab == LiveTab.EPISODES) liveViewModel.ensureEpisodes()
    }
    LaunchedEffect(shownTab, programState.isLoading, programState.match?.itemId, program?.title) {
        if (shownTab == LiveTab.MORE_TO_WATCH) liveViewModel.ensureMoreToWatch()
    }
    val tabRequesters = remember { LiveTab.entries.associateWith { FocusRequester() } }
    val contentEntry = remember { FocusRequester() }
    val statsRequester = remember { FocusRequester() }
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
                // RCTV (owner, the DVR): behind live, the time playing instead of LIVE.
                if (dvr != null && dvr.isBehind(atMs)) DvrTimeBadge(atMs = atMs, nowMs = dvr.nowMs) else LiveBadge()
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(modifier = Modifier.weight(1f)) {
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
                }
                // RCTV (as YouTube TV): a game on now: its stats and the league's games, at the right
                // above the timeline; Down goes back to the timeline, Up to full screen.
                if (onOpenGameCenter != null) {
                    Spacer(modifier = Modifier.width(16.dp))
                    IconButton(
                        onClick = onOpenGameCenter,
                        modifier = Modifier
                            .size(44.dp)
                            .focusRequester(statsRequester)
                            .focusProperties { down = progressFocusRequester }
                            .onPreviewKeyEvent { event ->
                                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                    event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP
                                ) {
                                    onHideControls()
                                    true
                                } else {
                                    false
                                }
                            }
                            .onFocusChanged { if (it.isFocused) onResetHideTimer() },
                        colors = IconButtonDefaults.colors(
                            containerColor = Color.Black.copy(alpha = 0.45f),
                            focusedContainerColor = Color.White,
                            contentColor = Color.White,
                            focusedContentColor = Color.Black
                        ),
                        border = IconButtonDefaults.border(
                            border = androidx.tv.material3.Border(
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                                shape = CircleShape
                            )
                        ),
                        shape = IconButtonDefaults.shape(shape = CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.BarChart,
                            contentDescription = stringResource(R.string.player_live_game_stats),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            // RCTV (as YouTube TV): the timeline can be selected (a live channel doesn't move along
            // it): Up from it goes to the game's stats, or back to full screen; Down to the tabs.
            LiveTimeline(
                program = program,
                nowMs = dvr?.nowMs ?: nowMs,
                dvr = dvr,
                atMs = atMs,
                onFocused = onResetHideTimer,
                modifier = Modifier
                    .focusRequester(progressFocusRequester)
                    .focusProperties { down = tabFocusRequester(selectedTab) }
                    .onPreviewKeyEvent { event ->
                        if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                        when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                if (onOpenGameCenter != null) {
                                    runCatching { statsRequester.requestFocus() }.onFailure { onHideControls() }
                                } else {
                                    onHideControls()
                                }
                                true
                            }
                            // RCTV (owner, the DVR): back and forward through the recording, faster
                            // while held; it plays from there a moment after letting go (or on OK).
                            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                onResetHideTimer()
                                dvr?.onScrub(
                                    PlayerScrubRates.deltaMsForKeyRepeat(
                                        repeatCount = event.nativeKeyEvent.repeatCount,
                                        forward = event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                                    )
                                )
                                // Without one, nothing to move to on a live channel: it stays selected.
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                if (dvr != null && dvr.previewMs != null) {
                                    dvr.onCommit()
                                    true
                                } else {
                                    false
                                }
                            }
                            else -> false
                        }
                    }
            )
            Spacer(modifier = Modifier.height(4.dp))

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
                    LiveTab.MORE_TO_WATCH -> moreState.items.isNotEmpty()
                    LiveTab.CATCH_UP -> catchUpState.programs.isNotEmpty()
                    else -> true
                }
                val tabModifier: (LiveTab) -> Modifier = { tab ->
                    Modifier
                        .focusRequester(tabFocusRequester(tab))
                        .focusProperties {
                            if (contentFocusable) down = contentEntry
                            up = progressFocusRequester
                        }
                        .onFocusChanged {
                            if (it.isFocused) {
                                selectedTab = tab
                                onResetHideTimer()
                            }
                        }
                }
                // RCTV (owner, the DVR as YouTube TV): behind live, back to live in one press.
                if (dvr != null && dvr.isBehind(atMs)) {
                    DvrGoLiveButton(
                        onClick = dvr.onGoLive,
                        modifier = Modifier.focusProperties { up = progressFocusRequester }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
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
                if (gameLanguage != null) {
                    LiveIconTab(
                        icon = Icons.Default.Language,
                        contentDescription = stringResource(R.string.player_live_game_audio),
                        selected = shownTab == LiveTab.AUDIO,
                        onClick = { runCatching { contentEntry.requestFocus() } },
                        modifier = tabModifier(LiveTab.AUDIO)
                    )
                }
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
                if (hasCatchUp) {
                    LiveTextTab(
                        icon = Icons.Default.History,
                        label = stringResource(R.string.player_live_catch_up),
                        selected = shownTab == LiveTab.CATCH_UP,
                        onClick = { runCatching { contentEntry.requestFocus() } },
                        modifier = tabModifier(LiveTab.CATCH_UP)
                    )
                }
                if (seriesMatch != null) {
                    LiveTextTab(
                        icon = Icons.AutoMirrored.Filled.List,
                        label = stringResource(R.string.player_live_episodes),
                        selected = shownTab == LiveTab.EPISODES,
                        onClick = { runCatching { contentEntry.requestFocus() } },
                        modifier = tabModifier(LiveTab.EPISODES)
                    )
                }
                // RCTV (owner): always the last tab.
                LiveTextTab(
                    icon = Icons.Default.ViewCarousel,
                    label = stringResource(R.string.player_live_more_to_watch),
                    selected = shownTab == LiveTab.MORE_TO_WATCH,
                    onClick = { runCatching { contentEntry.requestFocus() } },
                    modifier = tabModifier(LiveTab.MORE_TO_WATCH)
                )
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
                    LiveTab.CATCH_UP -> CatchUpRow(
                        channel = channel,
                        state = catchUpState,
                        nowMs = nowMs,
                        entry = contentEntry,
                        itemModifier = upToTab,
                        onFocused = onResetHideTimer,
                        onChoose = { program -> channel?.let { onPlayCatchUp(it, program) } }
                    )
                    LiveTab.MORE_TO_WATCH -> MoreToWatchRow(
                        items = moreState.items,
                        isLoading = moreState.isLoading || programState.isLoading,
                        entry = contentEntry,
                        itemModifier = upToTab,
                        onFocused = onResetHideTimer,
                        onChoose = onOpenMoreToWatch
                    )
                    LiveTab.NETWORKS -> NetworksRow(
                        guide = guide,
                        current = channel,
                        recentIds = recentIds,
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
                    LiveTab.AUDIO -> OptionsRow(
                        options = listOf(
                            LiveOption(glyph = "EN", label = "English", checked = gameLanguage?.spanish == false) {
                                if (gameLanguage?.spanish == true) gameLanguage.onChoose(false)
                            },
                            LiveOption(glyph = "ES", label = "Español", checked = gameLanguage?.spanish == true) {
                                if (gameLanguage?.spanish == false) gameLanguage.onChoose(true)
                            }
                        ),
                        entry = contentEntry,
                        itemModifier = upToTab,
                        onFocused = onResetHideTimer
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
                            onReportProblem?.let { report ->
                                add(LiveOption(icon = Icons.Default.BugReport, label = stringResource(R.string.bug_report_title)) {
                                    report()
                                })
                            }
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

/**
 * How far along the program on now is (the player's accent bar), with its start and end times; the
 * whole bar when the guide has nothing for the channel. RCTV (as YouTube TV): it can be selected:
 * it grows, with a white dot where the channel is now.
 */
@Composable
private fun LiveTimeline(
    program: EpgProgram?,
    nowMs: Long,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    /** RCTV (owner, the DVR): a recorded channel: [atMs] is where it plays (or was moved to). */
    dvr: LiveDvrUi? = null,
    atMs: Long = nowMs
) {
    val accent = NuvioTheme.palette.accentBrush()
    var focused by remember { mutableStateOf(false) }
    // RCTV (owner, the DVR): the playhead where it plays; the part recorded so far (up to live)
    // shows lighter after it, as YouTube TV's.
    val progress = program?.let { p ->
        if (dvr == null) p.progressAt(nowMs)
        else ((atMs - p.startMs).toFloat() / (p.endMs - p.startMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
    } ?: 1f
    val recordedUpTo = program?.takeIf { dvr != null }?.let { p ->
        ((nowMs - p.startMs).toFloat() / (p.endMs - p.startMs).coerceAtLeast(1L)).coerceIn(progress, 1f)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable()
    ) {
        BoxWithConstraints(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .weight(1f)
                .height(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (focused) 6.dp else 4.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = if (focused) 0.45f else 0.3f))
            ) {
                if (recordedUpTo != null && recordedUpTo > progress) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(recordedUpTo)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.55f))
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .clip(RoundedCornerShape(3.dp))
                        .background(accent)
                )
            }
            if (focused || (dvr != null && dvr.isBehind(atMs))) {
                Box(
                    modifier = Modifier
                        .offset(x = (maxWidth * progress - 7.dp).coerceAtLeast(0.dp))
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
        if (dvr != null && (dvr.previewMs != null || dvr.isBehind(atMs))) {
            // RCTV (owner, the DVR): the time there and how far behind live ("9:14 PM · −12:30").
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = dvrPositionLabel(atMs, nowMs),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        } else if (program != null) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = timeRange(program),
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 12.sp
            )
        }
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

/**
 * RCTV (owner): More to Watch: the titles as Home's cards (their picture, their name on it); choosing
 * one opens its page. While they're picked: "Looking up…".
 */
@Composable
private fun MoreToWatchRow(
    items: List<com.nuvio.tv.domain.model.MetaPreview>,
    isLoading: Boolean,
    entry: FocusRequester,
    itemModifier: Modifier,
    onFocused: () -> Unit,
    onChoose: (com.nuvio.tv.domain.model.MetaPreview) -> Unit
) {
    if (items.isEmpty()) {
        Text(
            text = stringResource(if (isLoading) R.string.livetv_library_looking_up else R.string.player_live_more_to_watch_empty),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
        return
    }
    LazyRow(
        modifier = Modifier.bleedHorizontally(RowBleed),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        // Room above and below for a card growing on focus.
        contentPadding = PaddingValues(horizontal = RowBleed, vertical = 10.dp)
    ) {
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            com.nuvio.tv.ui.components.AppleLandscapeCard(
                item = item,
                onClick = { onChoose(item) },
                modifier = itemModifier,
                focusRequester = if (index == 0) entry else null,
                onFocus = { onFocused() }
            )
        }
    }
}

/**
 * RCTV (owner): Catch Up: the programs the IPTV provider recorded on this channel, newest first, as
 * the Networks cards (the channel's logo, the day and time, the program's name). The one on now
 * says "Start over". Choosing one plays it from the recording.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CatchUpRow(
    channel: LiveTvChannel?,
    state: LivePlayerCatchUpState,
    nowMs: Long,
    entry: FocusRequester,
    itemModifier: Modifier,
    onFocused: () -> Unit,
    onChoose: (com.nuvio.tv.domain.model.LiveCatchUpProgram) -> Unit
) {
    if (state.programs.isEmpty()) {
        Text(
            text = stringResource(if (state.isLoading) R.string.player_live_catch_up_loading else R.string.player_live_catch_up_empty),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
        return
    }
    LazyRow(
        modifier = Modifier.bleedHorizontally(RowBleed),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(horizontal = RowBleed, vertical = 8.dp)
    ) {
        itemsIndexed(state.programs, key = { _, item -> item.startMs }) { index, item ->
            val airing = item.isAiringAt(nowMs)
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
                    border = tileCardBorder(RoundedCornerShape(10.dp)),
                    scale = CardDefaults.scale(focusedScale = 1.04f)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (!channel?.logoUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = channel?.logoUrl,
                                contentDescription = channel?.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 36.dp, vertical = 22.dp)
                            )
                        } else {
                            Text(
                                text = channel?.name.orEmpty(),
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(6.dp)
                                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (airing) stringResource(R.string.player_live_catch_up_start_over) else "${item.durationMinutes} min",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                        if (airing) {
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
                                        .fillMaxWidth(
                                            ((nowMs - item.startMs).toFloat() / (item.endMs - item.startMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
                                        )
                                        .background(NuvioTheme.palette.accentBrush())
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = LiveProgramLibrary.cleanTitle(item.title),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = catchUpDayLabel(item),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** "Today, 9:00 PM", "Yesterday, 9:00 PM" or "Nov 15, 9:00 PM". */
@Composable
private fun catchUpDayLabel(program: com.nuvio.tv.domain.model.LiveCatchUpProgram): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(program.startMs).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(program.startMs))
    return when (day) {
        today -> stringResource(R.string.livetv_details_today, time)
        today.minusDays(1) -> stringResource(R.string.player_live_catch_up_yesterday, time)
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(program.startMs)) + ", " + time
    }
}

/**
 * The Networks row: the channel playing (marked Playing), the recent channels, the favorites, then
 * the rest, each with what's on (its art when the guide has it), its times, how far along and
 * what's next. Only the cards on screen are built and load their images.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NetworksRow(
    guide: LiveTvPlayerSession.Guide,
    current: LiveTvChannel?,
    recentIds: List<String>,
    nowMs: Long,
    entry: FocusRequester,
    itemModifier: Modifier,
    onFocused: () -> Unit,
    onChoose: (LiveTvChannel) -> Unit
) {
    val channels = remember(guide.channels, guide.customChannelIds, current?.id, recentIds) {
        networksOrder(guide.channels, current?.id, recentIds, guide.customChannelIds)
    }
    LazyRow(
        modifier = Modifier.bleedHorizontally(RowBleed),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(horizontal = RowBleed, vertical = 8.dp)
    ) {
        itemsIndexed(channels, key = { _, item -> item.id }) { index, item ->
            val onNow = guide.programAt(item, nowMs)
            val next = remember(item.id, guide.epgByChannel, onNow?.endMs ?: (nowMs / 60_000L)) {
                guide.programsOf(item).firstOrNull { it.startMs >= (onNow?.endMs ?: nowMs) }
            }
            val art = onNow?.imageUrl?.takeIf { it.isNotBlank() }
            val isPlaying = item.id == current?.id
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
                    border = tileCardBorder(RoundedCornerShape(10.dp)),
                    scale = CardDefaults.scale(focusedScale = 1.04f)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (art != null) {
                            // What's on, darkened, with the channel's logo in the corner.
                            AsyncImage(
                                model = art,
                                contentDescription = onNow?.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.35f))
                            )
                            if (!item.logoUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = item.logoUrl,
                                    contentDescription = item.name,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(8.dp)
                                        .height(20.dp)
                                        .widthIn(max = 64.dp)
                                )
                            }
                        } else if (!item.logoUrl.isNullOrBlank()) {
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
                        if (isPlaying) {
                            // RCTV: the channel on now, as YouTube TV.
                            Text(
                                text = stringResource(R.string.player_live_playing),
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
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
                    maxLines = if (next != null) 1 else 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(item.name, onNow?.let { airingLabel(it) }).joinToString(" · "),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (next != null) {
                    Text(
                        text = stringResource(
                            R.string.player_live_next_program,
                            LiveProgramLibrary.cleanTitle(next.title)
                        ),
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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
                    border = tileCardBorder(RoundedCornerShape(10.dp)),
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
    val listState = rememberLazyListState()
    val airing = state.airing
    // RCTV (owner's rule, as YouTube TV): going into Episodes lands on the episode airing now,
    // marked "Playing", when it's in the season shown.
    val airingIndex = remember(state.episodes, airing) {
        if (airing == null) -1 else state.episodes.indexOfFirst { it.season == airing.season && it.episode == airing.episode }
    }
    val airingInView by remember(listState, airingIndex) {
        androidx.compose.runtime.derivedStateOf {
            airingIndex >= 0 && listState.layoutInfo.visibleItemsInfo.any { it.index == airingIndex }
        }
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
                        // RCTV: the airing episode takes focus first when it's in view (below).
                        .then(if (selected && !airingInView) Modifier.focusRequester(entry) else Modifier)
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
            itemsIndexed(state.episodes, key = { _, item -> "${item.season}:${item.episode}" }) { index, episode ->
                val isAiring = airing != null && airing.season == episode.season && airing.episode == episode.episode
                Column(modifier = Modifier.width(176.dp)) {
                    Card(
                        onClick = { onChoose(episode) },
                        modifier = Modifier
                            .then(if (index == airingIndex && airingInView) Modifier.focusRequester(entry) else Modifier)
                            .fillMaxWidth()
                            .height(99.dp)
                            .onFocusChanged { if (it.isFocused) onFocused() },
                        shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp)),
                        colors = CardDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.14f),
                            focusedContainerColor = Color.White.copy(alpha = 0.22f)
                        ),
                        border = tileCardBorder(RoundedCornerShape(10.dp)),
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
                                // RCTV: "Playing" on the episode on now, as YouTube TV.
                                Text(
                                    text = stringResource(R.string.player_live_playing),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                )
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
