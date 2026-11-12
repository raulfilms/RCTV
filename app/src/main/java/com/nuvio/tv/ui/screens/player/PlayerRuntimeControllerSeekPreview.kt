package com.nuvio.tv.ui.screens.player

import android.os.Build
import androidx.media3.common.util.UnstableApi

/** RCTV (seek preview): how long the arrows rest before a moved timeline plays (as YouTube TV). */
internal const val PREVIEW_SEEK_COMMIT_PAUSE_MS = 1_000L

/**
 * RCTV (seek preview): the timeline was moved to [positionMs]: its picture is asked for (movies and
 * episodes only; the source must be one whose file the TV can read, see [SeekPreviewSources]).
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.requestSeekPreviewPicture(positionMs: Long) {
    val url = currentStreamUrl
    if (url.isBlank()) return
    val format = runCatching { _exoPlayer?.videoFormat }.getOrNull()
    val eligible = SeekPreviewSources.canPreview(
        url = url,
        mimeType = currentStreamMimeType,
        sdkInt = Build.VERSION.SDK_INT,
        videoMimeType = format?.sampleMimeType ?: currentVideoTrackMimeType,
        videoCodecs = format?.codecs ?: currentVideoTrackCodecs
    )
    seekPreviewFrames.show(url, currentHeaders, positionMs, eligible)
}
