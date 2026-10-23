package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.livetv.LiveVodState
import com.nuvio.tv.data.livetv.LiveVodStatus
import kotlinx.coroutines.delay

/*
 * RCTV: a Live TV channel can't go back (it isn't recorded). When what's on is a movie or an
 * episode TMDB has and the sources have too, Left offers "Watch From Beginning" in the middle of
 * the screen: it plays it from its sources, from the start, like any title. What's on is looked up
 * (TMDB, then the sources) while the channel plays, so it's ready when Left is pressed.
 */

/** How long "Watch From Beginning" stays on screen when nothing is pressed. */
private const val PROMPT_TIMEOUT_MS = 8_000L

/** Keeps what's on now (on the channel playing) looked up on TMDB and in the sources. */
@Composable
internal fun LiveVodWatcher(liveViewModel: LiveTvPlayerViewModel, channelName: String, channelLogo: String?) {
    val guide by liveViewModel.guide.collectAsState()
    val nowMs by liveViewModel.nowMs.collectAsState()
    val program = remember(guide, channelName, channelLogo, nowMs) {
        liveViewModel.channelFor(channelName, channelLogo, guide)?.let { guide.programAt(it, nowMs) }
    }
    LaunchedEffect(program) { liveViewModel.prepareVod(program) }
}

/** "Watch From Beginning", in the middle of the screen, with what it is under it. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun LiveWatchFromBeginningPrompt(
    state: LiveVodState,
    onWatch: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focus = remember { FocusRequester() }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(Unit) {
        repeat(6) {
            withFrameNanos { }
            if (runCatching { focus.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    // It goes away on its own.
    LaunchedEffect(Unit) {
        delay(PROMPT_TIMEOUT_MS)
        currentOnDismiss()
    }
    // A source is still being looked for: it shows, and can be chosen as soon as one is found.
    val checking = state.status == LiveVodStatus.CHECKING
    val target = state.target
    val whatItIs = if (target.isMovie) {
        listOfNotNull(target.title, target.year).joinToString(" · ")
    } else {
        stringResource(R.string.livetv_library_episode_title, target.title, target.season ?: 0, target.episode ?: 0) +
            (target.episodeName?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
    }
    val shadow = TextStyle(shadow = Shadow(color = Color.Black.copy(alpha = 0.8f), blurRadius = 8f))

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            onClick = { if (!checking) onWatch() },
            modifier = Modifier
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                    when (event.nativeKeyEvent.keyCode) {
                        // Anywhere else: back to the channel.
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            currentOnDismiss()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> true
                        else -> false
                    }
                },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Black.copy(alpha = 0.6f),
                contentColor = Color.White,
                focusedContainerColor = Color.White,
                focusedContentColor = Color.Black
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Replay,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(if (checking) R.string.livetv_vod_looking else R.string.livetv_watch_from_beginning_btn),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = whatItIs,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = shadow
        )
    }
}
