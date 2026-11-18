package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
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
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Replay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * RCTV (owner): a channel the IPTV provider records (catch-up) plays like a DVR, as YouTube TV's:
 * - opened while a program is on: "Watch From Beginning" or "Watch Live";
 * - the timeline goes back and forward through what was recorded (faster while held), plays from
 *   there a moment after letting go, and shows the time there and how far behind live;
 * - pausing live and playing again goes on from where it was paused;
 * - behind live, "Go Live" goes back to live in one press (and reaching the end of the recording
 *   goes back to live by itself).
 */

/** Within this much of now counts as live. */
internal const val DVR_LIVE_EDGE_MS = 20_000L

/** A program on for longer than this offers "Watch From Beginning" when the channel opens. */
internal const val DVR_OFFER_FROM_BEGINNING_MS = 3 * 60_000L

/** A moved timeline plays from there this long after the last press. */
internal const val DVR_SCRUB_COMMIT_MS = 900L

/** Live paused longer than this goes on from where it was paused (shorter: it just plays on). */
internal const val DVR_RESUME_FROM_PAUSE_MS = 15_000L

/** How long "Watch From Beginning / Watch Live" stays when nothing is pressed (live goes on). */
private const val DVR_PROMPT_TIMEOUT_MS = 12_000L

/** RCTV (owner, the DVR): what the live controls need of a recorded channel. */
internal class LiveDvrUi(
    /** The real time playing (now when live). */
    val positionMs: Long,
    /** Where the timeline was moved to, not playing there yet. */
    val previewMs: Long?,
    val nowMs: Long,
    val onScrub: (deltaMs: Long) -> Unit,
    val onCommit: () -> Unit,
    val onGoLive: () -> Unit
) {
    fun isBehind(atMs: Long): Boolean = atMs < nowMs - DVR_LIVE_EDGE_MS
}

/** "−12:30" (how far behind live), or "−1:05:20". */
internal fun dvrBehindLabel(atMs: Long, nowMs: Long): String {
    val seconds = ((nowMs - atMs) / 1000).coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) "−%d:%02d:%02d".format(hours, minutes, secs) else "−%d:%02d".format(minutes, secs)
}

/** "9:14 PM · −12:30". */
internal fun dvrPositionLabel(atMs: Long, nowMs: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(atMs)) + " · " + dvrBehindLabel(atMs, nowMs)

/** Behind live: how far, where LIVE shows when live. */
@Composable
internal fun DvrTimeBadge(atMs: Long, nowMs: Long) {
    Text(
        text = dvrBehindLabel(atMs, nowMs),
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Color.White.copy(alpha = 0.28f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

/** "● Go Live": white when focused, as the player's buttons. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun DvrGoLiveButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(34.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.18f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp).height(34.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE5202E))
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = stringResource(R.string.player_live_go_live),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

/**
 * A recorded channel opened while a program is on: "Watch From Beginning" or "Watch Live", in the
 * middle of the screen, with the program under them. Live plays behind it; it goes away on its own.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun LiveDvrStartPrompt(
    programTitle: String,
    startedMinutesAgo: Int,
    onFromBeginning: () -> Unit,
    onLive: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focus = remember { FocusRequester() }
    val currentOnLive by rememberUpdatedState(onLive)
    LaunchedEffect(Unit) {
        repeat(6) {
            withFrameNanos { }
            if (runCatching { focus.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    LaunchedEffect(Unit) {
        delay(DVR_PROMPT_TIMEOUT_MS)
        currentOnLive()
    }
    val shadow = TextStyle(shadow = Shadow(color = Color.Black.copy(alpha = 0.8f), blurRadius = 8f))
    // Up or Down: live goes on.
    val dismissKeys = Modifier.onPreviewKeyEvent { event ->
        if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
        when (event.nativeKeyEvent.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                currentOnLive()
                true
            }
            else -> false
        }
    }
    Column(
        modifier = modifier.widthIn(max = 640.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = programTitle,
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = shadow
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.player_live_dvr_started, startedMinutesAgo),
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            style = shadow
        )
        Spacer(modifier = Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DvrPromptButton(
                icon = Icons.Default.Replay,
                label = stringResource(R.string.livetv_watch_from_beginning_btn),
                onClick = onFromBeginning,
                modifier = Modifier.focusRequester(focus).then(dismissKeys)
            )
            DvrPromptButton(
                icon = Icons.Default.LiveTv,
                label = stringResource(R.string.livetv_watch_live_btn),
                onClick = onLive,
                modifier = dismissKeys
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DvrPromptButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Black.copy(alpha = 0.6f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/**
 * RCTV (owner, the DVR): a recorded show chosen outside the player (Home's Catch Up): the channel
 * opens and plays it from its start. Taken once, by that channel, within a minute.
 */
object LiveDvrHandoff {
    private class Pending(val channelId: String, val startMs: Long, val atMs: Long)

    @Volatile private var pending: Pending? = null

    fun set(channelId: String, startMs: Long) {
        pending = Pending(channelId, startMs, android.os.SystemClock.elapsedRealtime())
    }

    fun take(channelId: String): Long? {
        val chosen = pending ?: return null
        if (chosen.channelId != channelId) return null
        pending = null
        return chosen.startMs.takeIf { android.os.SystemClock.elapsedRealtime() - chosen.atMs < HANDOFF_MAX_AGE_MS }
    }

    private const val HANDOFF_MAX_AGE_MS = 60_000L
}
