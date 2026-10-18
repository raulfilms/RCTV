@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.tv.material3.ExperimentalTvMaterial3Api::class
)

package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.data.disneyplus.GenreTile
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.CustomLiveChannel
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

/** Where one of Home's TMDB rows leads. */
sealed interface HomeLink {
    /** A row's whole list (or a franchise's movies), with Type / Genre / Year filters. */
    data class SeeAll(val rowId: String) : HomeLink

    /** Discover on one genre: a movie genre (movies and series) or a series-only genre. */
    data class Genre(val tile: GenreTile) : HomeLink

    /** A live channel's page (one of the Guide's "Custom" channels, from Top Channels). */
    data class LiveChannel(val channelId: String) : HomeLink
}

private val GenreTileWidth: Dp = AppleTvSpacing.Col5
private val GenreTileHeight: Dp = AppleTvSpacing.Col5 * (9f / 16f)

/** Genre card colors (deep, so the white name reads), picked by the genre's id. */
private val GenreColors = listOf(
    Color(0xFF8E2DE2), Color(0xFFC0392B), Color(0xFF16A085), Color(0xFFD35400), Color(0xFF2C3E91),
    Color(0xFF7D3C98), Color(0xFF117A65), Color(0xFFB03A2E), Color(0xFF1F618D), Color(0xFF6E2C00),
    Color(0xFF0E6655), Color(0xFF884EA0), Color(0xFF943126), Color(0xFF1A5276)
)

/** "Browse by Genre": one card per TMDB genre; each opens Discover on it. */
@Composable
fun HomeGenreRow(
    title: String,
    tiles: List<GenreTile>,
    onClick: (GenreTile) -> Unit,
    rowFocusRequester: FocusRequester,
    initialFocusIndex: Int,
    onItemFocused: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    HomeTileRow(
        title = title,
        count = tiles.size,
        keyOf = { "genre_${tiles[it].mediaType.value}_${tiles[it].genreId}" },
        rowFocusRequester = rowFocusRequester,
        initialFocusIndex = initialFocusIndex,
        onItemFocused = onItemFocused,
        modifier = modifier
    ) { index, itemModifier ->
        val tile = tiles[index]
        val color = GenreColors[(tile.genreId % GenreColors.size + GenreColors.size) % GenreColors.size]
        // The genre's thumbnail (its name is on it), else a colored card with its name.
        val thumbnail = com.nuvio.tv.ui.components.GenreThumbnails.forGenreId(tile.genreId)
        HomeCard(
            onClick = { onClick(tile) },
            width = GenreTileWidth,
            height = GenreTileHeight,
            containerColor = color,
            modifier = itemModifier
        ) {
            if (thumbnail != null) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(thumbnail),
                    contentDescription = tile.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.18f), Color.Black.copy(alpha = 0.25f))))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tile.label,
                    color = Color.White,
                    fontSize = 15.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (tile.mediaType == TmdbCollectionMediaType.TV) {
                    Text(
                        text = "TV",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

/** "Movie Collections & Franchises": one card per TMDB collection; each opens all its movies. */
@Composable
fun HomeFranchiseRow(
    title: String,
    rowIds: List<String>,
    art: Map<String, Pair<String, String?>>,
    /** Each collection's name as artwork (its film's logo), shown instead of the written name. */
    logos: Map<String, String> = emptyMap(),
    onClick: (String) -> Unit,
    rowFocusRequester: FocusRequester,
    initialFocusIndex: Int,
    onItemFocused: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    HomeTileRow(
        title = title,
        count = rowIds.size,
        keyOf = { rowIds[it] },
        rowFocusRequester = rowFocusRequester,
        initialFocusIndex = initialFocusIndex,
        onItemFocused = onItemFocused,
        modifier = modifier
    ) { index, itemModifier ->
        val rowId = rowIds[index]
        val (name, image) = art[rowId] ?: (rowId.removePrefix("franchise_").replace('_', ' ') to null)
        val logo = logos[rowId]
        var logoFailed by remember(logo) { mutableStateOf(false) }
        HomeCard(
            onClick = { onClick(rowId) },
            width = AppleLandscapeCardWidth,
            height = AppleLandscapeCardHeight,
            containerColor = AppleTvColors.Surface2,
            modifier = itemModifier
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (image != null) {
                    AsyncImage(
                        model = image,
                        contentDescription = name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.45f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.8f)
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
                ) {
                    if (!logo.isNullOrBlank() && !logoFailed) {
                        AsyncImage(
                            model = logo,
                            contentDescription = name,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.BottomStart,
                            onError = { logoFailed = true },
                            modifier = Modifier
                                .fillMaxWidth(0.62f)
                                .height(44.dp)
                                .padding(bottom = 3.dp)
                        )
                    } else {
                        Text(
                            text = name,
                            color = Color.White,
                            fontSize = 14.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "COLLECTION",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * "Top Channels": the channels the person put in the Guide's "Custom" list, each its logo on the
 * Guide's logo-tile color (the logos are made for dark screens); each opens that channel's page.
 */
@Composable
fun HomeLiveChannelRow(
    title: String,
    channels: List<CustomLiveChannel>,
    onClick: (CustomLiveChannel) -> Unit,
    rowFocusRequester: FocusRequester,
    initialFocusIndex: Int,
    onItemFocused: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    HomeTileRow(
        title = title,
        count = channels.size,
        keyOf = { "live_channel_${channels[it].id}" },
        rowFocusRequester = rowFocusRequester,
        initialFocusIndex = initialFocusIndex,
        onItemFocused = onItemFocused,
        modifier = modifier
    ) { index, itemModifier ->
        val channel = channels[index]
        val logo = channel.logoUrl
        var logoFailed by remember(logo) { mutableStateOf(false) }
        HomeCard(
            onClick = { onClick(channel) },
            width = GenreTileWidth,
            height = GenreTileHeight,
            containerColor = AppleTvColors.Contrast,
            modifier = itemModifier
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (logo != null && !logoFailed) {
                    AsyncImage(
                        model = logo,
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        onError = { logoFailed = true },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = channel.name,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** A card in the Apple TV style (no ring: it grows and lifts on focus). */
@Composable
internal fun HomeCard(
    onClick: () -> Unit,
    width: Dp,
    height: Dp,
    containerColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = remember { RoundedCornerShape(AppleTvRadius.Poster) }
    Card(
        onClick = onClick,
        modifier = modifier.size(width = width, height = height),
        shape = CardDefaults.shape(shape = shape),
        colors = CardDefaults.colors(containerColor = containerColor, focusedContainerColor = containerColor),
        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None),
        scale = CardDefaults.scale(focusedScale = AppleTvFocusScale),
        glow = CardDefaults.glow(focusedGlow = AppleTvFocusGlow)
    ) {
        Box(modifier = Modifier.fillMaxSize().clip(shape)) { content() }
    }
}

/**
 * A row of cards with its name above, like Home's other rows: the focused card stops at the
 * row's left edge, and focus comes back to the card last focused (or [initialFocusIndex] when
 * returning to Home).
 */
@Composable
internal fun HomeTileRow(
    title: String,
    count: Int,
    keyOf: (Int) -> String,
    rowFocusRequester: FocusRequester,
    initialFocusIndex: Int,
    onItemFocused: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Where the name and the first card start (the streaming hubs line up further in). */
    contentStart: Dp = AppleTvContentStart,
    /** Space between the name and the cards. */
    titleBottomGap: Dp = 10.dp,
    /** A bold name, like the streaming hubs' rows. */
    boldTitle: Boolean = false,
    tile: @Composable (index: Int, modifier: Modifier) -> Unit
) {
    if (count == 0) return
    val itemRequesters = remember(count) { List(count) { FocusRequester() } }
    var lastFocusedIndex by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(initialFocusIndex, count) {
        val target = initialFocusIndex
        if (target !in 0 until count) return@LaunchedEffect
        runCatching { listState.scrollToItem(target) }
        repeat(4) {
            withFrameNanos { }
            if (runCatching { itemRequesters[target].requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    val density = LocalDensity.current
    val parentSpec = LocalBringIntoViewSpec.current
    val startPx = with(density) { contentStart.toPx() }
    val horizontalSpec = remember(parentSpec, startPx) {
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float> = parentSpec.scrollAnimationSpec
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val childSize = kotlin.math.abs(size)
                val space = containerSize - startPx
                val leading = if (childSize <= containerSize && space < childSize) containerSize - childSize else startPx
                return offset - leading
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        AppleRowTitle(text = title, contentStart = contentStart, bottomGap = titleBottomGap, bold = boldTitle)
        CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalSpec) {
            LazyRow(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(rowFocusRequester)
                    .focusRestorer { itemRequesters.getOrNull(lastFocusedIndex) ?: FocusRequester.Default }
                    .focusGroup(),
                contentPadding = PaddingValues(start = contentStart, end = contentStart),
                horizontalArrangement = Arrangement.spacedBy(AppleTvCardSpacing)
            ) {
                itemsIndexed(items = List(count) { it }, key = { _, index -> keyOf(index) }) { index, _ ->
                    tile(
                        index,
                        Modifier
                            .focusRequester(itemRequesters[index])
                            .onFocusChanged {
                                if (it.isFocused) {
                                    lastFocusedIndex = index
                                    onItemFocused(index)
                                }
                            }
                    )
                }
            }
        }
    }
}
