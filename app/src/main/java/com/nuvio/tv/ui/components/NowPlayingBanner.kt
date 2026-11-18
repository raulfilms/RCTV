package com.nuvio.tv.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * RCTV (owner, as YouTube TV): Home moved down past its start: the Now Playing bar goes away (what
 * plays goes on being heard) and comes back at the top of Home. Set by Home only.
 */
object NowPlayingBarScroll {
    var pageScrolled by mutableStateOf(false)
}

/**
 * RCTV (owner, as YouTube TV): the Now Playing bar across the top of the app's pages while a
 * channel, movie or episode goes on playing behind the app (the player was left with Back): a black
 * bar, the channel's logo at the left and what's on in the middle; it's heard, not seen. Up reaches
 * the name (it turns white, as YouTube TV's); OK, or Play on the remote, opens the full player again
 * on the same playback (nothing restarts). Down goes back to the page. Moving down Home hides it
 * ([hidden]). It counts as this TV playing for RCTV Control, as the player did.
 */
@Composable
fun NowPlayingBannerHost(
    navController: NavController,
    onPlayerScreen: Boolean,
    /** RCTV (owner): focus back on the page, where it was before Up to the bar (true when it went). */
    onBackToPage: () -> Boolean = { false },
    /** The page moved down past its start: the bar slides away (the playback goes on). */
    hidden: Boolean = false,
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
    AnimatedVisibility(
        visible = !hidden,
        enter = slideInVertically(animationSpec = tween(BAR_SLIDE_MS)) { -it } + fadeIn(tween(BAR_SLIDE_MS)),
        exit = slideOutVertically(animationSpec = tween(BAR_SLIDE_MS)) { -it } + fadeOut(tween(BAR_SLIDE_MS)),
        modifier = modifier
    ) {
        NowPlayingBar(
            nowPlaying = current,
            onClick = { PlaybackSession.resume(navController) },
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
            }
        )
    }
}

/** How long the bar takes to slide away (and back), with the page moving under it. */
const val BAR_SLIDE_MS = 220

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NowPlayingBar(
    nowPlaying: PlaybackSession.NowPlaying,
    onClick: () -> Unit,
    nameModifier: Modifier,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(NowPlayingBarHeight)
            .background(Color.Black)
    ) {
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
