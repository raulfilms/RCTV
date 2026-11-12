package com.nuvio.tv.ui.screens.player

/** RCTV (seek preview): which sources can give the timeline's pictures. */
internal object SeekPreviewSources {
    /** Pictures are read every [BUCKET_MS] of the movie (the nearest key frame to each). */
    const val BUCKET_MS = 5_000L

    fun bucketOf(positionMs: Long): Long =
        ((positionMs.coerceAtLeast(0L) + BUCKET_MS / 2) / BUCKET_MS) * BUCKET_MS

    /** The time at the preview's position, as the player's own clock (11:13, 1:02:45). */
    fun timeLabel(millis: Long): String {
        val totalSeconds = millis.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    private val streamingMimeTypes = setOf(
        "application/x-mpegurl",
        "application/vnd.apple.mpegurl",
        "application/dash+xml",
        "application/vnd.ms-sstr+xml"
    )
    private val streamingExtensions = listOf(".m3u8", ".mpd", ".ism", ".isml")
    private val iptvPaths = setOf("movie", "series", "live", "timeshift")

    /**
     * A movie or episode file the TV's own reader can open: http(s), not a live or segmented
     * stream (HLS, DASH), not the app's own torrent server on this TV (reading elsewhere in the
     * file would pull its download away from what plays), not an IPTV provider's file (they often
     * allow one connection: a second one could stop the movie), not Dolby Vision (wrong colors),
     * and Android 8.1 or newer.
     */
    fun canPreview(
        url: String,
        mimeType: String?,
        sdkInt: Int,
        videoMimeType: String? = null,
        videoCodecs: String? = null
    ): Boolean {
        if (sdkInt < 27) return false
        val lower = url.trim().lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        val afterScheme = lower.substringAfter("://")
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringAfterLast('@').let { hostPort ->
            if (hostPort.startsWith("[")) hostPort.substringBefore(']').removePrefix("[") else hostPort.substringBefore(':')
        }
        if (host == "localhost" || host.startsWith("127.") || host == "::1" || host == "0.0.0.0") return false
        val path = afterScheme.substringAfter('/', "").substringBefore('?').substringBefore('#')
        if (streamingExtensions.any { path.endsWith(it) }) return false
        if (mimeType?.lowercase() in streamingMimeTypes) return false
        val segments = path.split('/').filter { it.isNotBlank() }
        if (segments.size >= 4 && segments.first() in iptvPaths) return false
        val codecs = videoCodecs?.lowercase().orEmpty()
        if (videoMimeType?.lowercase() == "video/dolby-vision" ||
            codecs.startsWith("dvh") || codecs.startsWith("dva") || codecs.startsWith("dav1")
        ) return false
        return true
    }
}
