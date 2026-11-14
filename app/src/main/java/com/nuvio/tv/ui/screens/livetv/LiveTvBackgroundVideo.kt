package com.nuvio.tv.ui.screens.livetv

import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
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
 */
@OptIn(UnstableApi::class)
@Composable
internal fun LiveTvBackgroundVideo(
    channel: LiveTvChannel,
    resolveUrl: suspend (LiveTvChannel) -> String,
    onFailed: () -> Unit,
    modifier: Modifier = Modifier
) {
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
        modifier = modifier.fillMaxSize()
    )
}

private const val MIN_BUFFER_MS = 4_000
private const val MAX_BUFFER_MS = 12_000
private const val START_BUFFER_MS = 1_000
private const val REBUFFER_MS = 2_000
/** After the full player let go of the stream. */
private const val START_DELAY_MS = 600L
private const val RETRY_DELAY_MS = 2_000L
