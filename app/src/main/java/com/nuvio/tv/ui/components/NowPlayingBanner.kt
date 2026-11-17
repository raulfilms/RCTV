package com.nuvio.tv.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.playback.PlaybackSession

/**
 * RCTV (owner, as YouTube TV): the Now Playing banner at the top of every page while a channel,
 * movie or episode goes on playing behind the app (the player was left with Back). It shows the
 * channel's logo, its name and what's on (LIVE), or the movie's or series' poster, its title and
 * the episode; choosing it (Up to it, or Play on the remote) opens the full player again on the same
 * playback. It is part of the page (not a floating picture), and it counts as this TV playing for
 * RCTV Control, as the player did.
 */
@Composable
fun NowPlayingBannerHost(
    navController: NavController,
    onPlayerScreen: Boolean,
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
    NowPlayingBanner(
        nowPlaying = current,
        onClick = { PlaybackSession.resume(navController) },
        modifier = modifier
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NowPlayingBanner(
    nowPlaying: PlaybackSession.NowPlaying,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        modifier = modifier
            .padding(top = 12.dp)
            .height(44.dp)
            .widthIn(max = 520.dp),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xE6141619),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)), shape = shape)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f)
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            val art = nowPlaying.art
            if (!art.isNullOrBlank()) {
                if (nowPlaying.isLive) {
                    // The channel's logo, on a light chip so any logo reads.
                    Box(
                        modifier = Modifier
                            .height(30.dp)
                            .width(52.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(Color.White.copy(alpha = 0.92f))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = art,
                            contentDescription = nowPlaying.title,
                            contentScale = ContentScale.Fit
                        )
                    }
                } else {
                    AsyncImage(
                        model = art,
                        contentDescription = nowPlaying.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .height(34.dp)
                            .width(23.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
            }
            // What tells it's playing: LIVE for a channel, ▶ for a movie or an episode.
            if (nowPlaying.isLive) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFE50914))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.now_playing_live),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE50914)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.now_playing_playing),
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = nowPlaying.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                nowPlaying.subtitle?.let { line ->
                    Text(
                        text = line,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }
        }
    }
}
