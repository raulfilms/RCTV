package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.screens.detail.formatReleaseDate
import com.nuvio.tv.ui.util.localizeEpisodeTitle

/**
 * RCTV (owner, as the live player's Episodes and YouTube TV): a series' episodes in its player, at
 * the bottom of the picture instead of a big panel at the side: the seasons above (moving onto one
 * shows its episodes), the episodes in a row of pictures (the one playing marked "Playing", one
 * started showing how far it went). Choosing one plays it at once. The player's own buttons and
 * icons stay as they are; only how the episodes show changed.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun EpisodesBottomRow(
    uiState: PlayerUiState,
    onSeasonSelected: (Int) -> Unit,
    onEpisodeSelected: (Video) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val seasons = remember(uiState.episodesAvailableSeasons) {
        uiState.episodesAvailableSeasons.filter { it > 0 }.sorted() +
            uiState.episodesAvailableSeasons.filter { it == 0 }
    }
    val episodes = uiState.episodes
    val listState = rememberLazyListState()
    val entry = remember { FocusRequester() }
    val selectedSeasonFocus = remember { FocusRequester() }
    var rowHasFocus by remember { mutableStateOf(false) }
    // Where focus lands: the episode playing (when it's in the season shown), else the first.
    val entryIndex = remember(episodes, uiState.currentSeason, uiState.currentEpisode) {
        episodes.indexOfFirst { it.season == uiState.currentSeason && it.episode == uiState.currentEpisode }
            .takeIf { it >= 0 } ?: 0
    }
    // Opening: the episode playing in view and focused (the player may take focus back the
    // first frames, so it's asked a few times until the row has it).
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(episodes.isNotEmpty()) {
        if (episodes.isEmpty() || landed) return@LaunchedEffect
        runCatching { listState.scrollToItem((entryIndex - 1).coerceAtLeast(0)) }
        repeat(30) {
            withFrameNanos { }
            if (rowHasFocus) {
                landed = true
                return@LaunchedEffect
            }
            runCatching { entry.requestFocus() }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.35f to Color.Black.copy(alpha = 0.72f),
                    1f to Color.Black.copy(alpha = 0.92f)
                )
            )
            .padding(start = 48.dp, end = 48.dp, top = 56.dp, bottom = 28.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.episodes_panel_title),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(10.dp))
            when {
                uiState.isLoadingEpisodes && episodes.isEmpty() -> RowNote(stringResource(R.string.livetv_library_looking_up))
                uiState.episodesError != null && episodes.isEmpty() -> RowNote(uiState.episodesError.orEmpty())
                episodes.isEmpty() -> RowNote(stringResource(R.string.player_live_no_episodes))
                else -> {
                    if (seasons.size > 1) {
                        // The seasons: moving onto one shows its episodes (as the live player's).
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .focusGroup()
                                .onPreviewKeyEvent { event ->
                                    // Nothing above the seasons: focus stays here.
                                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp
                                },
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            seasons.forEach { season ->
                                val selected = season == uiState.episodesSelectedSeason
                                Surface(
                                    onClick = { onSeasonSelected(season) },
                                    modifier = Modifier
                                        .then(if (selected) Modifier.focusRequester(selectedSeasonFocus) else Modifier)
                                        .height(30.dp)
                                        .onFocusChanged { if (it.isFocused && !selected) onSeasonSelected(season) },
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
                                            .padding(horizontal = 14.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (season == 0) stringResource(R.string.episodes_specials)
                                            else stringResource(R.string.episodes_season, season),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    // A season just chosen: its first episodes in view.
                    LaunchedEffect(uiState.episodesSelectedSeason) {
                        if (landed) runCatching { listState.scrollToItem(0) }
                    }
                    LazyRow(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusProperties { if (seasons.size > 1) up = selectedSeasonFocus }
                            .onFocusChanged { rowHasFocus = it.hasFocus }
                            .onPreviewKeyEvent { event ->
                                // Nothing below the episodes (nor above, with one season): focus stays on them.
                                event.type == KeyEventType.KeyDown && (
                                    event.key == Key.DirectionDown || (event.key == Key.DirectionUp && seasons.size <= 1)
                                )
                            },
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        itemsIndexed(episodes, key = { _, item -> item.id }) { index, episode ->
                            EpisodeTile(
                                episode = episode,
                                uiState = uiState,
                                title = episode.title.localizeEpisodeTitle(context)
                                    .ifBlank { context.getString(R.string.episodes_episode) },
                                modifier = if (index == entryIndex) Modifier.focusRequester(entry) else Modifier,
                                onClick = { onEpisodeSelected(episode) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowNote(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.7f),
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 6.dp, bottom = 40.dp)
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeTile(
    episode: Video,
    uiState: PlayerUiState,
    title: String,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val isCurrent = episode.season == uiState.currentSeason && episode.episode == uiState.currentEpisode
    val key = episode.season?.let { s -> episode.episode?.let { e -> s to e } }
    val progress = key?.let { uiState.episodeWatchProgressMap[it] }
    val isWatched = key != null && (progress?.isCompleted() == true || uiState.watchedEpisodeKeys.contains(key))
    val shouldBlur = uiState.blurUnwatchedEpisodes && !isWatched && !isCurrent
    val shape = RoundedCornerShape(10.dp)
    val date = remember(episode.released) {
        episode.released?.let { runCatching { formatReleaseDate(it) }.getOrNull() }?.takeIf { it.isNotBlank() }
    }
    Column(modifier = Modifier.width(TILE_WIDTH)) {
        Card(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .height(TILE_HEIGHT),
            shape = CardDefaults.shape(shape = shape),
            colors = CardDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.14f),
                focusedContainerColor = Color.White.copy(alpha = 0.22f)
            ),
            border = CardDefaults.border(
                focusedBorder = Border(border = BorderStroke(2.dp, Color.White), shape = shape)
            ),
            scale = CardDefaults.scale(focusedScale = 1.04f)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (!episode.thumbnail.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(episode.thumbnail)
                            .crossfade(true)
                            .apply {
                                if (shouldBlur) transformations(com.nuvio.tv.ui.util.BlurTransformation())
                            }
                            .build(),
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (isCurrent) {
                    // "Playing" on the episode playing, as YouTube TV.
                    Text(
                        text = stringResource(R.string.player_live_playing),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                // How far an episode started went (as Netflix).
                val fraction = progress?.takeIf { !isWatched }?.progressPercentage?.coerceIn(0f, 1f)
                if (fraction != null && fraction > 0.01f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.3f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .background(Color(0xFFE5202E))
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        val code = listOfNotNull(
            episode.season?.let { "S$it" },
            episode.episode?.let { "E$it" }
        ).joinToString(" ")
        Text(
            text = listOf(code, title).filter { it.isNotBlank() }.joinToString(" · "),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (date != null) {
            // When it aired, under its name (as the series' page: "Aired: November 17, 2026").
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = stringResource(R.string.episodes_aired_on, date),
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 11.sp,
                maxLines = 1
            )
        }
    }
}

private val TILE_WIDTH = 208.dp
private val TILE_HEIGHT = 117.dp
