package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import android.view.KeyEvent
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioTheme

/** RCTV: a movie gets the movie controls. */
internal val PlayerUiState.usesMovieControls: Boolean
    get() = contentType.equals("movie", ignoreCase = true) && currentSeason == null && currentEpisode == null

/** RCTV: a show's episode. */
internal val PlayerUiState.isEpisodeContent: Boolean
    get() = (contentType.equals("series", ignoreCase = true) || contentType.equals("tv", ignoreCase = true)) &&
        currentSeason != null && currentEpisode != null

/** RCTV: a movie or an episode gets the designed controls (a TV channel and the rest keep theirs). */
internal val PlayerUiState.usesTitleControls: Boolean
    get() = usesMovieControls || isEpisodeContent

/** RCTV: the seek of the movie controls' ⟲15 and 15⟳. */
internal const val MOVIE_SKIP_MS = 15_000L

/**
 * RCTV: the player's controls for a movie or an episode, as designed: the title's logo at the top
 * left and Settings at the top right; at the bottom the timeline (watched in white), the time,
 * ⟲15 / play / 15⟳ in the middle, and the row: (an episode: Next episode, All episodes,) Audio &
 * subtitles, Details, Watchlist, then (opened with the arrow) Sources and Playback speed; the
 * picture's quality at the bottom right. Everything white; a focused button turns into a white
 * circle. An episode's longer row, opened, takes the middle's place: ⟲15 / play / 15⟳ go away
 * until it's closed (with the arrow or Back).
 */
@Composable
internal fun TitlePlayerControlsOverlay(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    playPauseFocusRequester: FocusRequester,
    progressBarFocusRequester: FocusRequester,
    progressBarUpFocusRequester: FocusRequester?,
    isInWatchlist: Boolean,
    videoQuality: String?,
    onPlayPause: () -> Unit,
    onPlayNextEpisode: () -> Unit,
    onShowEpisodesPanel: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onShowAudioAndSubtitles: () -> Unit,
    onShowDetails: () -> Unit,
    onToggleWatchlist: () -> Unit,
    onShowSourcesPanel: () -> Unit,
    onShowSpeedDialog: () -> Unit,
    onToggleMoreActions: () -> Unit,
    onOpenSettings: () -> Unit,
    onResetHideTimer: () -> Unit,
    onHideControls: () -> Unit
) {
    val playPainter = rememberRawSvgPainter(R.raw.ic_player_play)
    val pausePainter = rememberRawSvgPainter(R.raw.ic_player_pause)
    val subtitlePainter = rememberRawSvgPainter(R.raw.ic_player_subtitles)
    val sourcePainter = rememberRawSvgPainter(R.raw.ic_player_source)
    val settingsFocusRequester = remember { FocusRequester() }
    val detailsIcon = remember { movieDetailsIcon() }
    val episodesIcon = remember { allEpisodesIcon() }
    val isEpisode = uiState.isEpisodeContent
    val showNextEpisodeButton = isEpisode && uiState.nextEpisode?.hasAired == true &&
        (uiState.postPlayMode as? PostPlayMode.AutoPlay)?.let { !it.searching && it.countdownSec == null } != false
    // An episode's row, opened, reaches the middle: ⟲15 / play / 15⟳ step aside until it closes.
    // Play's focus then goes to the arrow (what the screen focuses when the controls show).
    val middleHidden = isEpisode && uiState.showMoreDialog
    val rowUpFocusRequester = if (middleHidden) progressBarFocusRequester else playPauseFocusRequester
    val rowFirstFocusRequester = remember { FocusRequester() }
    // A focused icon sits in a 48 dp circle: the row and the gear are moved by the circle's margin
    // so the icons themselves line up with the timeline's ends.
    val iconInset = 12.dp

    Box(modifier = Modifier.fillMaxSize()) {
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
                .height(220.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
        )

        // The title's logo (its name when it has none) and Settings.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(start = NuvioTheme.spacing.xxl, end = NuvioTheme.spacing.xxl - iconInset, top = NuvioTheme.spacing.xl),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    MovieTitleLogo(
                        title = if (isEpisode) uiState.contentName?.takeIf { it.isNotBlank() } ?: uiState.title else uiState.title,
                        logo = uiState.logo
                    )
                }
                ControlButton(
                    icon = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.nav_settings),
                    onClick = onOpenSettings,
                    focusRequester = settingsFocusRequester,
                    onDownKey = { runCatching { progressBarFocusRequester.requestFocus() } },
                    onFocused = onResetHideTimer
                )
            }
        }

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(start = NuvioTheme.spacing.xxl, end = NuvioTheme.spacing.xxl, bottom = NuvioTheme.spacing.lg)
            ) {
                PlayerControlsProgressBarHost(
                    viewModel = viewModel,
                    focusRequester = progressBarFocusRequester,
                    upFocusRequester = progressBarUpFocusRequester ?: settingsFocusRequester,
                    downFocusRequester = playPauseFocusRequester,
                    onUpKey = onHideControls,
                    onFocused = onResetHideTimer,
                    whiteStyle = true
                )

                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))

                // The time at the left; ⟲15, play, 15⟳ in the middle (its room kept while it's away).
                Box(modifier = Modifier.fillMaxWidth().heightIn(min = NuvioTheme.spacing.xxxl)) {
                    Box(modifier = Modifier.align(Alignment.TopStart)) {
                        PlayerControlsTimeTextHost(viewModel = viewModel, color = Color.White)
                    }
                    // Not the Column's own AnimatedVisibility: this one sits in the Box.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !middleHidden,
                        enter = fadeIn(animationSpec = tween(NuvioMotion.tokens.durations.fast)),
                        exit = fadeOut(animationSpec = tween(NuvioMotion.tokens.durations.fast)),
                        modifier = Modifier.align(Alignment.TopCenter)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SkipFifteenButton(
                                forward = false,
                                contentDescription = stringResource(R.string.cd_rewind_15),
                                onClick = { onSeekBy(-MOVIE_SKIP_MS) },
                                upFocusRequester = progressBarFocusRequester,
                                downFocusRequester = rowFirstFocusRequester,
                                onFocused = onResetHideTimer
                            )
                            ControlButton(
                                icon = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                iconPainter = if (uiState.isPlaying) pausePainter else playPainter,
                                contentDescription = if (uiState.isPlaying) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play),
                                onClick = onPlayPause,
                                focusRequester = playPauseFocusRequester,
                                upFocusRequester = progressBarFocusRequester,
                                onDownKey = { runCatching { rowFirstFocusRequester.requestFocus() } },
                                onFocused = onResetHideTimer
                            )
                            SkipFifteenButton(
                                forward = true,
                                contentDescription = stringResource(R.string.cd_forward_15),
                                onClick = { onSeekBy(MOVIE_SKIP_MS) },
                                upFocusRequester = progressBarFocusRequester,
                                downFocusRequester = rowFirstFocusRequester,
                                onFocused = onResetHideTimer
                            )
                        }
                    }
                }

                // The row, and the quality at the right.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = (-8).dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.offset(x = -iconInset),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (showNextEpisodeButton) {
                            ControlButton(
                                icon = Icons.Default.SkipNext,
                                contentDescription = stringResource(R.string.next_episode_label),
                                onClick = onPlayNextEpisode,
                                focusRequester = rowFirstFocusRequester,
                                upFocusRequester = rowUpFocusRequester,
                                onDownKey = onHideControls,
                                onFocused = onResetHideTimer
                            )
                        }
                        if (isEpisode) {
                            ControlButton(
                                icon = episodesIcon,
                                contentDescription = stringResource(R.string.cd_episodes),
                                onClick = onShowEpisodesPanel,
                                focusRequester = if (showNextEpisodeButton) null else rowFirstFocusRequester,
                                upFocusRequester = rowUpFocusRequester,
                                onDownKey = onHideControls,
                                onFocused = onResetHideTimer
                            )
                        }
                        ControlButton(
                            icon = Icons.Default.ClosedCaption,
                            iconPainter = subtitlePainter,
                            contentDescription = stringResource(R.string.cd_audio_and_subtitles),
                            onClick = onShowAudioAndSubtitles,
                            focusRequester = if (isEpisode) null else rowFirstFocusRequester,
                            upFocusRequester = rowUpFocusRequester,
                            onDownKey = onHideControls,
                            onFocused = onResetHideTimer
                        )
                        ControlButton(
                            icon = detailsIcon,
                            contentDescription = stringResource(R.string.detail_tab_details),
                            onClick = onShowDetails,
                            upFocusRequester = rowUpFocusRequester,
                            onDownKey = onHideControls,
                            onFocused = onResetHideTimer
                        )
                        ControlButton(
                            icon = if (isInWatchlist) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = stringResource(
                                if (isInWatchlist) R.string.cd_remove_from_watchlist else R.string.cd_add_to_watchlist
                            ),
                            onClick = onToggleWatchlist,
                            upFocusRequester = rowUpFocusRequester,
                            onDownKey = onHideControls,
                            onFocused = onResetHideTimer
                        )

                        AnimatedVisibility(
                            visible = uiState.showMoreDialog,
                            enter = slideInHorizontally(
                                animationSpec = tween(NuvioMotion.tokens.durations.fast),
                                initialOffsetX = { -it / 2 }
                            ) + fadeIn(animationSpec = tween(NuvioMotion.tokens.durations.fast)),
                            exit = slideOutHorizontally(
                                animationSpec = tween(160),
                                targetOffsetX = { -it / 2 }
                            ) + fadeOut(animationSpec = tween(160))
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ControlButton(
                                    icon = Icons.Default.SwapHoriz,
                                    iconPainter = sourcePainter,
                                    contentDescription = stringResource(R.string.cd_sources),
                                    onClick = onShowSourcesPanel,
                                    upFocusRequester = rowUpFocusRequester,
                                    onDownKey = onHideControls,
                                    onFocused = onResetHideTimer
                                )
                                ControlButton(
                                    icon = Icons.Default.Speed,
                                    contentDescription = stringResource(R.string.cd_playback_speed),
                                    onClick = onShowSpeedDialog,
                                    upFocusRequester = rowUpFocusRequester,
                                    onDownKey = onHideControls,
                                    onFocused = onResetHideTimer
                                )
                            }
                        }

                        ControlButton(
                            icon = if (uiState.showMoreDialog) {
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft
                            } else {
                                Icons.AutoMirrored.Filled.KeyboardArrowRight
                            },
                            contentDescription = if (uiState.showMoreDialog) {
                                stringResource(R.string.cd_close_more_actions)
                            } else {
                                stringResource(R.string.cd_more_actions)
                            },
                            onClick = onToggleMoreActions,
                            focusRequester = if (middleHidden) playPauseFocusRequester else null,
                            upFocusRequester = rowUpFocusRequester,
                            onDownKey = onHideControls,
                            onFocused = onResetHideTimer
                        )
                    }

                    if (!videoQuality.isNullOrBlank()) {
                        QualityBadge(label = videoQuality)
                    }
                }
            }
        }
    }
}

/** The title's logo, as large as the top allows; its name when it has no logo (or it won't load). */
@Composable
private fun MovieTitleLogo(title: String, logo: String?) {
    var logoFailed by remember(logo) { mutableStateOf(false) }
    if (!logo.isNullOrBlank() && !logoFailed) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(logo)
                .memoryCacheKey(logo)
                .crossfade(true)
                .build(),
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            modifier = Modifier
                .height(52.dp)
                .widthIn(max = 300.dp),
            onError = { logoFailed = true }
        )
    } else {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** ⟲15 / 15⟳: the circular arrow with "15" in it, a white circle when focused (as every button). */
@Composable
private fun SkipFifteenButton(
    forward: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    upFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester,
    onFocused: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(NuvioTheme.spacing.xxxl)
            .focusProperties {
                up = upFocusRequester
                down = downFocusRequester
            }
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (keyEvent.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        runCatching { upFocusRequester.requestFocus() }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        runCatching { downFocusRequester.requestFocus() }
                        true
                    }
                    else -> false
                }
            }
            .onFocusChanged { if (it.isFocused) onFocused() },
        colors = IconButtonDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White,
            contentColor = Color.White,
            focusedContentColor = Color.Black
        ),
        shape = IconButtonDefaults.shape(shape = CircleShape)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Replay,
                contentDescription = contentDescription,
                modifier = Modifier
                    .size(30.dp)
                    .graphicsLayer { if (forward) scaleX = -1f }
            )
            Text(
                text = "15",
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.offset(y = 1.5.dp)
            )
        }
    }
}

/**
 * The quality indicator: the playing picture's quality ("4K", "Full HD", "HD") in a white outline.
 * It only tells: it's never focused and does nothing when pressed.
 */
@Composable
private fun QualityBadge(label: String) {
    Box(
        modifier = Modifier
            .border(2.dp, Color.White, RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White
        )
    }
}

/**
 * "4K" (2160p), "Full HD" (1080p) or "HD" (720p) from the decoded picture's size, a wide film
 * counted by its width (3840×1600 is 4K, 1920×800 Full HD); null below 720p or while not known
 * (no indicator then).
 */
internal fun videoQualityLabel(width: Int?, height: Int?): String? {
    val w = width?.takeIf { it > 0 } ?: 0
    val h = height?.takeIf { it > 0 } ?: 0
    if (w == 0 && h == 0) return null
    val lines = maxOf(h, w * 9 / 16)
    return when {
        lines >= 2000 -> "4K"
        lines >= 1000 -> "Full HD"
        lines >= 700 -> "HD"
        else -> null
    }
}

/** All episodes: a card in front of two others. */
private fun allEpisodesIcon(): ImageVector = ImageVector.Builder(
    name = "AllEpisodes",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    path(stroke = SolidColor(Color.White), strokeLineWidth = 1.7f) {
        moveTo(9f, 3.2f); lineTo(21.3f, 3.2f); quadTo(22.2f, 3.2f, 22.2f, 4.1f); lineTo(22.2f, 13.3f)
    }
    path(stroke = SolidColor(Color.White), strokeLineWidth = 1.7f) {
        moveTo(6f, 6.4f); lineTo(18.3f, 6.4f); quadTo(19.2f, 6.4f, 19.2f, 7.3f); lineTo(19.2f, 16.5f)
    }
    path(fill = SolidColor(Color.White)) {
        moveTo(3.2f, 9.6f); lineTo(15.2f, 9.6f); quadTo(16.4f, 9.6f, 16.4f, 10.8f); lineTo(16.4f, 19f)
        quadTo(16.4f, 20.2f, 15.2f, 20.2f); lineTo(3.2f, 20.2f); quadTo(2f, 20.2f, 2f, 19f); lineTo(2f, 10.8f)
        quadTo(2f, 9.6f, 3.2f, 9.6f); close()
    }
}.build()

/** Details: a screen with a list on it (the movie's page, over the player). */
private fun movieDetailsIcon(): ImageVector = ImageVector.Builder(
    name = "MovieDetails",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    // The screen.
    path(stroke = SolidColor(Color.White), strokeLineWidth = 1.8f) {
        moveTo(4f, 3f)
        lineTo(20f, 3f)
        quadTo(21.5f, 3f, 21.5f, 4.5f)
        lineTo(21.5f, 15.5f)
        quadTo(21.5f, 17f, 20f, 17f)
        lineTo(4f, 17f)
        quadTo(2.5f, 17f, 2.5f, 15.5f)
        lineTo(2.5f, 4.5f)
        quadTo(2.5f, 3f, 4f, 3f)
        close()
    }
    // Its list: three bullets and lines.
    listOf(6.2f, 9.2f, 12.2f).forEach { top ->
        path(fill = SolidColor(Color.White)) {
            moveTo(5.6f, top); lineTo(7.4f, top); lineTo(7.4f, top + 1.6f); lineTo(5.6f, top + 1.6f); close()
        }
        path(fill = SolidColor(Color.White)) {
            moveTo(9f, top); lineTo(18.5f, top); lineTo(18.5f, top + 1.6f); lineTo(9f, top + 1.6f); close()
        }
    }
    // Its stand.
    path(fill = SolidColor(Color.White)) {
        moveTo(7f, 19.5f); lineTo(17f, 19.5f); lineTo(17f, 21.3f); lineTo(7f, 21.3f); close()
    }
}.build()

/**
 * RCTV: the title controls' Settings: what isn't in the row (the audio's and the subtitles' own
 * settings, aspect ratio, the player engine, an external player, the stream's info, reporting a
 * problem).
 */
@Composable
internal fun MoviePlayerSettingsDialog(
    reportEnabled: Boolean,
    onAudioSettings: () -> Unit,
    onSubtitleSettings: () -> Unit,
    onToggleAspectRatio: () -> Unit,
    onSwitchPlayerEngine: () -> Unit,
    onOpenInExternalPlayer: () -> Unit,
    onShowStreamInfo: () -> Unit,
    onReportPlaybackIssue: () -> Unit,
    onDismiss: () -> Unit
) {
    val firstFocusRequester = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(380.dp)
                .clip(RoundedCornerShape(NuvioTheme.radii.xl))
                .background(NuvioTheme.colors.BackgroundElevated)
        ) {
            Column(
                modifier = Modifier.padding(NuvioTheme.spacing.xl),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.nav_settings),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary,
                    modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm)
                )
                MovieSettingsItem(
                    text = stringResource(R.string.player_audio_settings),
                    focusRequester = firstFocusRequester,
                    onClick = { onDismiss(); onAudioSettings() }
                )
                MovieSettingsItem(
                    text = stringResource(R.string.player_subtitle_settings),
                    onClick = { onDismiss(); onSubtitleSettings() }
                )
                MovieSettingsItem(
                    text = stringResource(R.string.player_more_aspect_ratio),
                    onClick = { onDismiss(); onToggleAspectRatio() }
                )
                MovieSettingsItem(
                    text = stringResource(R.string.cd_switch_player_engine),
                    onClick = { onDismiss(); onSwitchPlayerEngine() }
                )
                MovieSettingsItem(
                    text = stringResource(R.string.player_more_open_external),
                    onClick = { onDismiss(); onOpenInExternalPlayer() }
                )
                MovieSettingsItem(
                    text = stringResource(R.string.cd_stream_info),
                    onClick = { onDismiss(); onShowStreamInfo() }
                )
                if (reportEnabled) {
                    MovieSettingsItem(
                        text = stringResource(R.string.player_report_issue),
                        onClick = { onDismiss(); onReportPlaybackIssue() }
                    )
                }
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { firstFocusRequester.requestFocus() }
    }
}

@Composable
private fun MovieSettingsItem(
    text: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = 14.dp)
        )
    }
}
