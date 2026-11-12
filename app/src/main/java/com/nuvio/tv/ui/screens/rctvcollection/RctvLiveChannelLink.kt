package com.nuvio.tv.ui.screens.rctvcollection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.nuvio.tv.core.rctvcontrol.RctvChannelMatcher
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.ui.components.AppleTvColors
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.livetv.LiveTvViewModel
import kotlinx.coroutines.delay

private const val SETTLE_MS = 2_500L
private const val GIVE_UP_MS = 25_000L
private const val CHECK_EVERY_MS = 250L

/**
 * RCTV Control: a Home banner's Live TV channel. Waits for the guide (a moment the first time),
 * finds the channel by the name the owner wrote ([RctvChannelMatcher]) and plays it; when the guide
 * hasn't got it, [onNotFound] (the Guide opens).
 */
@Composable
fun RctvLiveChannelLink(
    wanted: String,
    liveTvViewModel: LiveTvViewModel,
    onPlay: (LiveTvChannel) -> Unit,
    onNotFound: () -> Unit
) {
    val play by rememberUpdatedState(onPlay)
    val notFound by rememberUpdatedState(onNotFound)
    LaunchedEffect(wanted) {
        val startedAt = System.currentTimeMillis()
        while (true) {
            val state = liveTvViewModel.uiState.value
            // The sample guide (no sources set) isn't the person's.
            val channels = if (state.isPreviewGuide) emptyList() else state.channels
            val match = RctvChannelMatcher.find(wanted, channels) { it.name }
            if (match != null) {
                play(match)
                return@LaunchedEffect
            }
            val waited = System.currentTimeMillis() - startedAt
            val settled = !state.isLoading && waited > SETTLE_MS
            if ((settled && (channels.isNotEmpty() || state.error != null || state.isPreviewGuide)) || waited > GIVE_UP_MS) {
                notFound()
                return@LaunchedEffect
            }
            delay(CHECK_EVERY_MS)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppleTvColors.Background),
        contentAlignment = Alignment.Center
    ) {
        LoadingIndicator()
    }
}
