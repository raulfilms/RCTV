package com.nuvio.tv.ui.screens.news

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
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.data.livetv.LiveProgramMatch
import com.nuvio.tv.domain.model.LiveTvChannel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Background = Color(0xFF070B18)
private val CardColor = Color(0xFF151C2E)
private val CardBorder = Color(0x33FFFFFF)
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
        modifier = Modifier.fillMaxSize().background(Background),
        contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp)
    ) {
        item(key = "header") {
            Column {
                Text(
                    text = stringResource(R.string.news_title),
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 48.dp, bottom = 14.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                    fontSize = 18.sp,
                    modifier = Modifier.padding(start = 48.dp, top = 24.dp)
                )
            }
        }

        if (state.picked.isNotEmpty()) {
            item(key = "picked") {
                NewsRow(stringResource(R.string.news_picked), state.picked) { card ->
                    ShowCard(
                        card = card,
                        match = state.titles[NewsViewModel.titleKey(card.program.title)],
                        onClick = { open(card, preferShow = true) },
                        onLongClick = { onOpenChannelProfile(card.channel) }
                    )
                }
            }
        }

        if (state.onNow.isNotEmpty()) {
            item(key = "on_now") {
                NewsRow(stringResource(R.string.news_on_now), state.onNow) { card ->
                    ProgramCard(
                        card = card,
                        match = state.titles[NewsViewModel.titleKey(card.program.title)],
                        onClick = { open(card, preferShow = false) },
                        onLongClick = { onOpenChannelProfile(card.channel) }
                    )
                }
            }
        }

        state.channelRows.forEach { row ->
            item(key = "channel_${row.channel.id}") {
                NewsRow(row.channel.name, row.cards) { card ->
                    ProgramCard(
                        card = card,
                        match = state.titles[NewsViewModel.titleKey(card.program.title)],
                        onClick = { open(card, preferShow = false) },
                        onLongClick = { onOpenChannelProfile(card.channel) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NewsRow(title: String, cards: List<NewsCard>, card: @Composable (NewsCard) -> Unit) {
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
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            items(cards, key = { it.key }) { card(it) }
        }
    }
}

@Composable
private fun NewsChipButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) ChipSelected else ChipColor,
            focusedContainerColor = Color.White,
            contentColor = Color.White,
            focusedContentColor = Color(0xFF0A1022)
        ),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        modifier = modifier
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp)
        )
    }
}

/** A show: its picture (TMDB's when the guide has none) and its name under it. */
@Composable
private fun ShowCard(card: NewsCard, match: LiveProgramMatch?, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(modifier = Modifier.width(CardWidth)) {
        NewsFocusCard(onClick = onClick, onLongClick = onLongClick) {
            Art(card, match)
            if (card.live) {
                Box(modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)) { LiveBadge() }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = match?.name ?: card.program.title,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A program on a channel: LIVE and how far it is, or when it starts; the time and channel under it. */
@Composable
private fun ProgramCard(card: NewsCard, match: LiveProgramMatch?, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(modifier = Modifier.width(CardWidth)) {
        NewsFocusCard(onClick = onClick, onLongClick = onLongClick) {
            Art(card, match)
            if (card.live) {
                Box(modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 14.dp)) { LiveBadge() }
                val now = System.currentTimeMillis()
                val fraction = ((now - card.program.startMs).toFloat() / (card.program.endMs - card.program.startMs).coerceAtLeast(1))
                    .coerceIn(0f, 1f)
                Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x55FFFFFF))) {
                    Box(modifier = Modifier.fillMaxWidth(fraction).fillMaxHeight().background(LiveRed))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "${whenText(card.program.startMs)} • ${card.channel.name}",
            color = Dim,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = card.program.title,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The program's picture, else TMDB's, else the channel's logo on RCTV's dark. */
@Composable
private fun BoxScope.Art(card: NewsCard, match: LiveProgramMatch?) {
    val picture = card.program.imageUrl ?: match?.backdrop
    if (picture != null) {
        AsyncImage(model = picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
        Box(
            modifier = Modifier.fillMaxSize()
                .background(Brush.linearGradient(listOf(Color(0xFF14254D), Color(0xFF0A1022), Color(0xFF3A0E1E))))
        )
        AsyncImage(
            model = card.channel.logoUrl,
            contentDescription = card.channel.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.5f).height(64.dp)
        )
    }
}

@Composable
private fun NewsFocusCard(onClick: () -> Unit, onLongClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
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
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        modifier = Modifier.width(CardWidth).height(CardHeight)
    ) {
        Box(modifier = Modifier.fillMaxSize().clip(shape), content = content)
    }
}

@Composable
private fun LiveBadge() {
    Row(
        modifier = Modifier.background(LiveRed, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.news_live), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** "Nov 13, 9:00 PM". */
private fun whenText(startMs: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(startMs))

private const val FOCUS_TRIES = 20
private const val FOCUS_RETRY_MS = 50L
private val CardWidth = 320.dp
private val CardHeight = 180.dp
