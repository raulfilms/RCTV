package com.nuvio.tv.ui.screens.news

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
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
import com.nuvio.tv.ui.components.tileSurfaceBorder
import com.nuvio.tv.data.livetv.LiveProgramMatch
import com.nuvio.tv.domain.model.LiveTvChannel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LiveRed = Color(0xFFE5232B)
private val Dim = Color(0xB3FFFFFF)
private val ChipColor = Color(0x26FFFFFF)
private val ChipSelected = Color(0xFF1E4FA8)

/**
 * RCTV (owner, like YouTube TV's News, in RCTV's look): chips on top (All, Political news, the main
 * news channels, Finance news, Gossip & entertainment, Crime, Weather), then Picked for you (the
 * shows on the news channels), On now & upcoming, and a row per news channel. A show opens its
 * page; what's on now plays its channel; a long press opens the channel's page.
 */
@Composable
fun NewsScreen(
    onPlayChannel: (LiveTvChannel) -> Unit,
    onOpenTitle: (itemId: String, itemType: String) -> Unit,
    onOpenChannelProfile: (LiveTvChannel) -> Unit,
    viewModel: NewsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }
    // The page opens with All picked and focused (the chips' row is drawn a moment later).
    LaunchedEffect(Unit) {
        repeat(FOCUS_TRIES) {
            if (runCatching { firstFocus.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
            kotlinx.coroutines.delay(FOCUS_RETRY_MS)
        }
    }

    fun play(channel: LiveTvChannel) {
        scope.launch { onPlayChannel(viewModel.playable(channel)) }
    }

    fun open(card: NewsCard, preferShow: Boolean) {
        val match = state.titles[NewsViewModel.titleKey(card.program.title)]
        when {
            card.live && !preferShow -> play(card.channel)
            match != null -> onOpenTitle(match.itemId, match.itemType)
            else -> play(card.channel)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(AppleTvColors.Background),
        contentPadding = PaddingValues(top = AppleTvSpacing.SafeY, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item(key = "header") {
            Column {
                // RCTV (owner): in the app's style and size (as Home's rows).
                Text(
                    text = stringResource(R.string.news_title),
                    color = AppleTvColors.Label,
                    style = AppleTvType.Title3,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = AppleTvContentStart, bottom = 10.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = AppleTvContentStart, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item(key = "all") {
                        NewsChipButton(
                            label = stringResource(R.string.news_all),
                            selected = state.selectedChip == null,
                            modifier = Modifier.focusRequester(firstFocus),
                            onClick = { viewModel.selectChip(null) }
                        )
                    }
                    items(state.chips, key = { it.key }) { chip ->
                        NewsChipButton(
                            label = chip.label ?: chip.labelRes?.let { stringResource(it) }.orEmpty(),
                            selected = state.selectedChip == chip.key,
                            onClick = { viewModel.selectChip(chip.key) }
                        )
                    }
                }
            }
        }

        if (state.picked.isEmpty() && state.onNow.isEmpty() && state.channelRows.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(if (state.loading) R.string.news_loading else R.string.news_empty),
                    color = Dim,
                    style = AppleTvType.Callout,
                    modifier = Modifier.padding(start = AppleTvContentStart, top = 16.dp)
                )
            }
        }

        if (state.picked.isNotEmpty()) {
            item(key = "picked") {
                NewsRow(stringResource(R.string.news_picked), state.picked) { card ->
                    NewsTile(
                        card = card,
                        match = state.titles[NewsViewModel.titleKey(card.program.title)],
                        showName = true,
                        onClick = { open(card, preferShow = true) },
                        onLongClick = { onOpenChannelProfile(card.channel) }
                    )
                }
            }
        }

        if (state.onNow.isNotEmpty()) {
            item(key = "on_now") {
                NewsRow(stringResource(R.string.news_on_now), state.onNow) { card ->
                    NewsTile(
                        card = card,
                        match = state.titles[NewsViewModel.titleKey(card.program.title)],
                        showName = false,
                        onClick = { open(card, preferShow = false) },
                        onLongClick = { onOpenChannelProfile(card.channel) }
                    )
                }
            }
        }

        state.channelRows.forEach { row ->
            item(key = "channel_${row.channel.id}") {
                NewsRow(row.channel.name, row.cards) { card ->
                    NewsTile(
                        card = card,
                        match = state.titles[NewsViewModel.titleKey(card.program.title)],
                        showName = false,
                        onClick = { open(card, preferShow = false) },
                        onLongClick = { onOpenChannelProfile(card.channel) }
                    )
                }
            }
        }
    }
}

/** A row as Home's: its name, then its cards from the page's edge (room for a card growing on focus). */
@Composable
private fun NewsRow(title: String, cards: List<NewsCard>, card: @Composable (NewsCard) -> Unit) {
    Column {
        AppleRowTitle(text = title, bottomGap = 0.dp)
        LazyRow(
            contentPadding = PaddingValues(horizontal = AppleTvContentStart, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(AppleTvCardSpacing)
        ) {
            items(cards, key = { it.key }) { card(it) }
        }
    }
}

@Composable
private fun NewsChipButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) ChipSelected else ChipColor,
            focusedContainerColor = Color.White,
            contentColor = Color.White,
            focusedContentColor = Color.Black
        ),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        modifier = modifier
    ) {
        Text(
            text = label,
            style = AppleTvType.Caption1,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}

/**
 * RCTV (owner): a News card exactly like Home's live cards (same size, outline and focus): the
 * program's picture (TMDB's when the guide has none, else the channel's logo), the channel's logo
 * in the corner, LIVE and how far along, its name and when (and on which channel) at the bottom.
 * [showName]: the show's name (TMDB's) rather than the guide's program title.
 */
@Composable
private fun NewsTile(
    card: NewsCard,
    match: LiveProgramMatch?,
    showName: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
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
        modifier = Modifier.width(AppleLandscapeCardWidth).height(AppleLandscapeCardHeight)
    ) {
        Box(modifier = Modifier.fillMaxSize().clip(shape)) {
            val picture = card.program.imageUrl ?: match?.backdrop
            if (picture != null) {
                AsyncImage(model = picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                AsyncImage(
                    model = card.channel.logoUrl,
                    contentDescription = card.channel.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.5f).height(48.dp).padding(bottom = 18.dp)
                )
            }
            Box(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.65f)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            )
            if (picture != null && !card.channel.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = card.channel.logoUrl,
                    contentDescription = card.channel.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).width(48.dp).height(24.dp)
                )
            }
            if (card.live) {
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) { LiveBadge() }
            }
            val title = if (showName) match?.name ?: card.program.title else card.program.title
            val line = if (card.live) {
                "${card.channel.name} · ${timeRange(card.program.startMs, card.program.endMs)}"
            } else {
                "${card.channel.name} · ${whenText(card.program.startMs)}"
            }
            Column(modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) {
                Text(title, color = Color.White, style = AppleTvType.Caption1, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(line, color = Dim, style = AppleTvType.Caption2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (card.live) {
                    Spacer(Modifier.height(4.dp))
                    val now = System.currentTimeMillis()
                    val fraction = ((now - card.program.startMs).toFloat() / (card.program.endMs - card.program.startMs).coerceAtLeast(1))
                        .coerceIn(0f, 1f)
                    Box(modifier = Modifier.fillMaxWidth().height(4.dp).background(Color(0x55FFFFFF), RoundedCornerShape(2.dp))) {
                        Box(modifier = Modifier.fillMaxWidth(fraction).fillMaxHeight().background(LiveRed, RoundedCornerShape(2.dp)))
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveBadge() {
    Row(
        modifier = Modifier.background(LiveRed, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.news_live), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** "8:00 PM – 9:00 PM". */
private fun timeRange(startMs: Long, endMs: Long): String {
    val format = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
    return "${format.format(Date(startMs))} – ${format.format(Date(endMs))}"
}

/** "Nov 13, 9:00 PM". */
private fun whenText(startMs: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(startMs))

private const val FOCUS_TRIES = 20
private const val FOCUS_RETRY_MS = 50L
