package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.flow.update

/**
 * RCTV: the player's screen with no video, to see and design it (controls, title, timeline, the
 * "Live" look for TV channels). The picture stays black; nothing loads, nothing is saved to
 * Continue Watching and nothing is sent to Trakt.
 *
 * Opened from the Guide's sample channels (what's on now) and from a movie's "No streams found" page.
 */
object PlayerDesignPreview {
    /** The "stream" address that opens the player in preview mode. */
    const val URL = "rctv-preview://player"

    fun isPreview(url: String?): Boolean = url == URL

    /** A movie or show in the preview sits 25 minutes into a 2-hour timeline, so the bar shows. */
    internal const val POSITION_MS = 25L * 60_000L
    internal const val BUFFERED_MS = 32L * 60_000L
    internal const val DURATION_MS = 120L * 60_000L
}

/** Shows the player's screen as if a title were playing, without loading any video. */
internal fun PlayerRuntimeController.showDesignPreview() {
    val live = contentType.equals("live", ignoreCase = true) ||
        LivePlaybackUiPolicy.isLiveContentType(contentType)
    if (live) {
        livePlaybackLatched = true
        updatePlaybackTimeline(currentPosition = 0L, duration = 0L, bufferedPosition = 0L, isLive = true)
    } else {
        updatePlaybackTimeline(
            currentPosition = PlayerDesignPreview.POSITION_MS,
            duration = PlayerDesignPreview.DURATION_MS,
            bufferedPosition = PlayerDesignPreview.BUFFERED_MS,
            isLive = false
        )
    }
    _uiState.update {
        it.copy(
            error = null,
            showLoadingOverlay = false,
            isBuffering = false,
            isPlaying = true,
            showControls = true
        )
    }
}
