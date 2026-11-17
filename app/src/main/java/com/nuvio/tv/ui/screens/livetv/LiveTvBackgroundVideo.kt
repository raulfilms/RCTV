package com.nuvio.tv.ui.screens.livetv

import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * RCTV (owner): back from the player to the Guide, the channel goes on playing behind it (as on
 * YouTube TV). A small player of its own, with a short buffer (the TV's memory): it starts when the
 * Guide shows, stops when the Guide is left or a channel is chosen (the full player's turn), and
 * pauses while the app is in the background. Its address can carry the IPTV login: never logged.
 * [onFailed]: it couldn't play (the Guide goes on without it).
 * RCTV (owner): [still], the player's last picture, shows until the channel's first picture here,
 * so the picture never goes away on the way from the player to the Guide.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun LiveTvBackgroundVideo(
    channel: LiveTvChannel,
    resolveUrl: suspend (LiveTvChannel) -> String,
    onFailed: () -> Unit,
    modifier: Modifier = Modifier,
    still: ImageBitmap? = null
) {
    // The last picture stays until this player shows its first one, then fades out.
    var stillShown by remember { mutableStateOf(still != null) }
    val stillAlpha by animateFloatAsState(
        targetValue = if (stillShown) 1f else 0f,
        animationSpec = tween(STILL_FADE_MS),
        label = "liveStill"
    )
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val failed by rememberUpdatedState(onFailed)
    val player = remember {
        ExoPlayer.Builder(context.applicationContext)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
                    .build()
            )
            .build()
            .apply { playWhenReady = true }
    }
    DisposableEffect(player) {
        onDispose {
            runCatching {
                player.stop()
                player.release()
            }
        }
    }

    LaunchedEffect(channel.id) {
        val url = try {
            resolveUrl(channel)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (url.isNullOrBlank()) {
            failed()
            return@LaunchedEffect
        }
        val sources = DefaultMediaSourceFactory(
            PlayerPlaybackNetworking.createDataSourceFactory(context.applicationContext, emptyMap(), url.toHttpUrlOrNull())
        )
        // The player has just let the provider's connection go: one more try a moment later (many
        // IPTV providers take one connection at a time).
        var retried = false
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                stillShown = false
            }

            override fun onPlayerError(error: PlaybackException) {
                if (retried) {
                    failed()
                } else {
                    retried = true
                    scope.launch {
                        delay(RETRY_DELAY_MS)
                        player.prepare()
                    }
                }
            }
        }
        player.addListener(listener)
        player.setMediaSource(sources.createMediaSource(MediaItem.fromUri(url)))
        delay(START_DELAY_MS)
        player.prepare()
    }

    // The app in the background: no picture or sound behind it.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_START -> if (player.mediaItemCount > 0) {
                    player.seekToDefaultPosition()
                    player.play()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = modifier.fillMaxSize()) {
    AndroidView(
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(R.layout.live_background_player_view, null) as PlayerView).apply {
                this.player = player
                // The Guide keeps the remote: the picture never takes focus.
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                keepScreenOn = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        },
        onRelease = { view ->
            view.player = null
            view.keepScreenOn = false
        },
        modifier = Modifier.fillMaxSize()
    )
    if (still != null && stillAlpha > 0f) {
        Image(
            bitmap = still,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(stillAlpha)
        )
    }
    }
}

/**
 * RCTV (owner, as YouTube TV): the channel left playing with Back (the playback session, see
 * PlaybackSession) shown behind the Guide: the same player, no second connection. Its picture
 * goes back to the full player when the Now Playing banner is chosen.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun LiveTvSessionVideo(player: Player, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(R.layout.live_background_player_view, null) as PlayerView).apply {
                this.player = player
                // The Guide keeps the remote: the picture never takes focus.
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                keepScreenOn = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        },
        update = { view -> if (view.player !== player) view.player = player },
        onRelease = { view ->
            view.player = null
            view.keepScreenOn = false
        },
        modifier = modifier.fillMaxSize()
    )
}

private const val MIN_BUFFER_MS = 4_000
private const val MAX_BUFFER_MS = 12_000
private const val START_BUFFER_MS = 1_000
private const val REBUFFER_MS = 2_000
/** After the full player let go of the stream. */
private const val START_DELAY_MS = 600L
/** The last picture fading into the channel playing. */
private const val STILL_FADE_MS = 250
private const val RETRY_DELAY_MS = 2_000L
