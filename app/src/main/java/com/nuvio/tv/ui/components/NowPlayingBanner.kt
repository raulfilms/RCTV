package com.nuvio.tv.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.core.playback.PlaybackSession

/** RCTV (owner, as YouTube TV): the Now Playing bar's height; the app's pages start under it. */
val NowPlayingBarHeight = 56.dp

/**
 * RCTV (owner, as YouTube TV): the Now Playing bar across the top of the app's pages while a
 * channel, movie or episode goes on playing behind the app (the player was left with Back): a black
 * bar with what plays behind it (darkened, and only inside the bar: the page below never shows it),
 * the channel's logo at the left and what's on in the middle. Up reaches
 * the name (it turns white, as YouTube TV's); OK, or Play on the remote, opens the full player again
 * on the same playback (nothing restarts); OK held closes it (the bar goes away). Down goes back
 * to the page. It stays on every page, the page whole under it (owner: it never slides away). It
 * counts as this TV playing for RCTV Control, as the player did.
 */
@Composable
fun NowPlayingBannerHost(
    navController: NavController,
    onPlayerScreen: Boolean,
    /** RCTV (owner): focus back on the page, where it was before Up to the bar (true when it went). */
    onBackToPage: () -> Boolean = { false },
    /** False where the page itself shows the playback (the Guide, behind it): the bar doesn't take it. */
    showVideo: Boolean = true,
    modifier: Modifier = Modifier
) {
    val nowPlaying by PlaybackSession.nowPlaying.collectAsState()
    val current = nowPlaying ?: return
    val context = LocalContext.current
    RctvPlaybackGuard(
        kind = current.guardKind,
        title = current.guardTitle,
        onStop = { messageRes ->
            PlaybackSession.stopMinimized()
            Toast.makeText(context, messageRes, Toast.LENGTH_LONG).show()
        }
    )
    if (onPlayerScreen) return
    val focusManager = LocalFocusManager.current
    val player = remember(current, showVideo) { if (showVideo) PlaybackSession.minimizedPlayer() else null }
    run {
        NowPlayingBar(
            nowPlaying = current,
            player = player,
            onClick = { PlaybackSession.resume(navController) },
            // RCTV (owner): OK held on the name closes what plays (where it was is kept for
            // Continue Watching) and the bar goes away; focus goes back to the page first.
            onLongClick = {
                onBackToPage()
                PlaybackSession.stopMinimized()
            },
            // RCTV (owner): never a dead end: Down goes back to the page (where it was), as do Left
            // and Right when nothing is beside the name.
            nameModifier = Modifier.onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> {
                        if (!onBackToPage()) focusManager.moveFocus(FocusDirection.Down)
                        true
                    }
                    Key.DirectionLeft, Key.DirectionRight -> {
                        val direction = if (event.key == Key.DirectionLeft) FocusDirection.Left else FocusDirection.Right
                        if (!focusManager.moveFocus(direction)) onBackToPage()
                        true
                    }
                    else -> false
                }
            },
            modifier = modifier
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NowPlayingBar(
    nowPlaying: PlaybackSession.NowPlaying,
    player: androidx.media3.common.Player?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    nameModifier: Modifier,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(NowPlayingBarHeight)
            .clipToBounds()
            .background(Color.Black)
    ) {
        // What plays, inside the bar only (darkened so the bar reads, as YouTube TV's); the page
        // below never shows it.
        if (player != null) {
            com.nuvio.tv.ui.screens.livetv.LiveTvSessionVideo(
                player = player,
                modifier = Modifier.fillMaxSize().clipToBounds(),
                inBar = true
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.72f))
                        )
                    )
            )
        }
        // The channel's logo at the left (a movie or an episode has none there).
        val logo = nowPlaying.art?.takeIf { nowPlaying.isLive && it.isNotBlank() }
        if (logo != null) {
            AsyncImage(
                model = logo,
                contentDescription = nowPlaying.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 48.dp)
                    .height(24.dp)
                    .widthIn(max = 96.dp)
            )
        }
        // What's on, in the middle: white when Up reaches it.
        val name = if (nowPlaying.isLive) {
            nowPlaying.subtitle?.takeIf { it.isNotBlank() } ?: nowPlaying.title
        } else {
            listOfNotNull(nowPlaying.title, nowPlaying.subtitle?.takeIf { it.isNotBlank() }).joinToString(" · ")
        }
        val shape = RoundedCornerShape(50)
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = nameModifier
                .align(Alignment.Center)
                .height(38.dp)
                .widthIn(max = 620.dp),
            shape = ClickableSurfaceDefaults.shape(shape = shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = Color.White,
                focusedContainerColor = Color.White,
                focusedContentColor = Color.Black
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
        ) {
            Box(modifier = Modifier.height(38.dp).padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
