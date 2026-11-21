package com.nuvio.tv.ui.screens.player

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.playback.PlaybackSession
import com.nuvio.tv.data.livetv.LiveProgramLibrary

/**
 * RCTV (owner): whether Back can leave this playback going while the app is browsed: it plays (not
 * paused) with ExoPlayer, hasn't ended, has no error, and isn't the player's design preview.
 * The MPV engine keeps its picture in its own view, so it ends with the screen, as before.
 */
internal fun canMinimizePlayback(
    viewModel: PlayerViewModel,
    uiState: PlayerUiState,
    postPlayVisible: Boolean
): Boolean {
    val url = uiState.currentStreamUrl
    // RCTV (owner): only what's playing goes on behind the app (Now Playing). Paused, it's left as
    // it was: the player closes and the page shows as always (Continue Watching has it).
    val playing = viewModel.exoPlayer?.playWhenReady == true
    return viewModel.exoPlayer != null &&
        playing &&
        !viewModel.controller.isUsingMpvEngine() &&
        uiState.error == null &&
        !uiState.playbackEnded &&
        !postPlayVisible &&
        !url.isNullOrBlank() &&
        !PlayerDesignPreview.isPreview(url)
}

/** What the Now Playing banner shows for this playback. */
internal fun nowPlayingOf(uiState: PlayerUiState, liveProgramTitle: String?, context: Context): PlaybackSession.NowPlaying {
    val isLive = uiState.contentType.equals("live", ignoreCase = true)
    if (isLive) {
        return PlaybackSession.NowPlaying(
            isLive = true,
            title = uiState.title,
            subtitle = liveProgramTitle?.let(LiveProgramLibrary::cleanTitle)?.takeIf { it.isNotBlank() },
            art = uiState.logo?.takeIf { it.isNotBlank() },
            guardKind = "iptv",
            guardTitle = uiState.title
        )
    }
    val name = uiState.contentName?.takeIf { it.isNotBlank() } ?: uiState.title
    val season = uiState.currentSeason
    val episode = uiState.currentEpisode
    val episodeLine = if (season != null && episode != null) {
        val episodeName = uiState.currentEpisodeTitle?.trim()?.takeIf { it.isNotEmpty() }
        if (episodeName != null) {
            context.getString(R.string.cw_season_episode_title, season, episode, episodeName)
        } else {
            context.getString(R.string.cw_season_episode, season, episode)
        }
    } else {
        null
    }
    return PlaybackSession.NowPlaying(
        isLive = false,
        title = name,
        subtitle = episodeLine,
        // The poster from the player's arguments (see PlaybackSession.minimize).
        art = null,
        // RCTV (owner): from the IPTV's own catalog, it still takes one of the IPTV's connections.
        guardKind = if (SeekPreviewSources.isIptvAddress(uiState.currentStreamUrl)) "iptv" else "vod",
        guardTitle = name
    )
}
