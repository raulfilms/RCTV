package com.nuvio.tv.ui.screens.sports

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.screens.detail.formatPlaybackTime
import kotlinx.coroutines.delay

/** Which of the middle controls was used last (it shows lit). */
private enum class RecapControl { NONE, BACK, PLAY, FORWARD }

private val RecapProgressRed = Color(0xFFFF0033)
private const val CONTROLS_HIDE_MS = 3500L

/**
 * A game's highlights, full screen. Its controls show at the start and with any key, then hide
 * (they stay while paused): the game on top (the teams, the NFL's week), back 10 · play/pause ·
 * forward 10 in the middle, and the line of how far it's gone at the bottom. OK plays or pauses,
 * left / right go back / forward (faster held), Back returns to the game's page.
 */
@Composable
internal fun SportsRecapPlayer(
    title: String,
    videoUrl: String?,
    audioUrl: String?,
    isLoading: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    var isPaused by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var lastControl by remember { mutableStateOf(RecapControl.NONE) }
    var interaction by remember { mutableIntStateOf(0) }
    var seekToken by remember { mutableIntStateOf(0) }
    var seekDeltaMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    val canControl = !videoUrl.isNullOrBlank() && !isLoading && errorMessage == null
    val rootRequester = remember { FocusRequester() }
    val retryRequester = remember { FocusRequester() }

    fun showControls(control: RecapControl) {
        controlsVisible = true
        lastControl = control
        interaction++
    }

    LaunchedEffect(videoUrl) {
        isPaused = false
        showControls(RecapControl.NONE)
    }
    // The controls hide a moment after the last key (not while paused).
    LaunchedEffect(controlsVisible, interaction, isPaused, canControl) {
        if (controlsVisible && canControl && !isPaused) {
            delay(CONTROLS_HIDE_MS)
            controlsVisible = false
            lastControl = RecapControl.NONE
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        LaunchedEffect(errorMessage) {
            repeat(10) {
                delay(16)
                val requester = if (errorMessage != null) retryRequester else rootRequester
                if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(rootRequester)
                .focusable()
                .onPreviewKeyEvent { keyEvent ->
                    val native = keyEvent.nativeKeyEvent
                    if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                    when (native.keyCode) {
                        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                            onDismiss()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            if (!canControl) return@onPreviewKeyEvent false
                            isPaused = !isPaused
                            showControls(RecapControl.PLAY)
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            if (!canControl) return@onPreviewKeyEvent false
                            isPaused = true
                            showControls(RecapControl.PLAY)
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY -> {
                            if (!canControl) return@onPreviewKeyEvent false
                            isPaused = false
                            showControls(RecapControl.PLAY)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            if (!canControl) return@onPreviewKeyEvent false
                            seekDeltaMs = -seekStepMs(native.repeatCount)
                            seekToken++
                            showControls(RecapControl.BACK)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            if (!canControl) return@onPreviewKeyEvent false
                            seekDeltaMs = seekStepMs(native.repeatCount)
                            seekToken++
                            showControls(RecapControl.FORWARD)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (!canControl) return@onPreviewKeyEvent false
                            showControls(RecapControl.NONE)
                            true
                        }
                        else -> false
                    }
                }
        ) {
            if (!videoUrl.isNullOrBlank() && errorMessage == null) {
                TrailerPlayer(
                    trailerUrl = videoUrl,
                    trailerAudioUrl = audioUrl,
                    isPlaying = true,
                    isPaused = isPaused,
                    seekRequestToken = seekToken,
                    seekDeltaMs = seekDeltaMs,
                    onProgressChanged = { position, duration ->
                        positionMs = position
                        durationMs = duration
                    },
                    onEnded = onDismiss,
                    modifier = Modifier.fillMaxSize()
                )
            }

            AnimatedVisibility(
                visible = controlsVisible || !canControl,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(300)),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Darker at the top (the title) and the bottom (the line).
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0f to Color.Black.copy(alpha = 0.7f),
                                        0.22f to Color.Transparent,
                                        0.72f to Color.Transparent,
                                        1f to Color.Black.copy(alpha = 0.75f)
                                    )
                                )
                            )
                    )
                    // The game: the teams (and the NFL's week).
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 56.dp, end = 56.dp, top = 36.dp)
                    )
                    if (canControl) {
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(40.dp)
                        ) {
                            RecapControlButton(Icons.Filled.Replay10, size = 64.dp, lit = lastControl == RecapControl.BACK)
                            RecapControlButton(
                                if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                size = 88.dp,
                                lit = lastControl == RecapControl.PLAY || isPaused
                            )
                            RecapControlButton(Icons.Filled.Forward10, size = 64.dp, lit = lastControl == RecapControl.FORWARD)
                        }
                        RecapProgressLine(
                            positionMs = positionMs,
                            durationMs = durationMs,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(start = 56.dp, end = 56.dp, bottom = 40.dp)
                        )
                    }
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            }

            if (!errorMessage.isNullOrBlank()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(text = errorMessage, color = Color.White, fontSize = 18.sp)
                    Button(onClick = onRetry, modifier = Modifier.focusRequester(retryRequester)) {
                        Text(text = stringResource(R.string.action_retry))
                    }
                }
            }
        }
    }
}

/** How far one press goes: 10 s, more while it's held. */
private fun seekStepMs(repeatCount: Int): Long = when {
    repeatCount >= 12 -> 30_000L
    repeatCount >= 4 -> 20_000L
    else -> 10_000L
}

/** One of the middle controls: a round button, white when it's the one just used. */
@Composable
private fun RecapControlButton(icon: ImageVector, size: Dp, lit: Boolean) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (lit) Color.White else Color.White.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (lit) Color.Black else Color.White,
            modifier = Modifier.size(size * 0.55f)
        )
    }
}

/** The line of how far the highlights have gone, with the time gone and the whole time. */
@Composable
private fun RecapProgressLine(positionMs: Long, durationMs: Long, modifier: Modifier = Modifier) {
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.3f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .clip(RoundedCornerShape(3.dp))
                    .background(RecapProgressRed)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(text = formatPlaybackTime(positionMs), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.weight(1f))
            Text(text = formatPlaybackTime(durationMs), color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp)
        }
    }
}
