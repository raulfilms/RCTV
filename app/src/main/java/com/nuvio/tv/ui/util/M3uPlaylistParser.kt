package com.nuvio.tv.ui.util

import com.nuvio.tv.domain.model.LiveTvChannel

/**
 * Minimal EXTM3U / M3U8 IPTV playlist parser. Understands the widely-used
 * `#EXTINF:-1 tvg-logo="..." group-title="...",Channel Name` + stream-URL-on-next-line
 * shape used by IPTV playlists (Xtream, community M3U lists, etc). Unknown/unsupported
 * tags are ignored rather than causing a failure, so partially-malformed playlists still
 * yield whatever channels can be recognized.
 */
object M3uPlaylistParser {

    private val attributeRegex = Regex("""([a-zA-Z0-9_-]+)="([^"]*)"""")

    fun parse(playlistText: String): List<LiveTvChannel> {
        if (!playlistText.contains("#EXTM3U") && !playlistText.contains("#EXTINF")) {
            return emptyList()
        }

        val channels = mutableListOf<LiveTvChannel>()
        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null
        var pendingEpgId: String? = null
        var index = 0

        playlistText.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val commaIndex = line.lastIndexOf(',')
                    val attributesPart = if (commaIndex >= 0) line.substring(0, commaIndex) else line
                    pendingName = (if (commaIndex >= 0) line.substring(commaIndex + 1) else "").trim()
                        .ifBlank { null }
                    val attributes = attributeRegex.findAll(attributesPart).associate {
                        it.groupValues[1].lowercase() to it.groupValues[2]
                    }
                    pendingLogo = attributes["tvg-logo"]?.takeIf { it.isNotBlank() }
                    pendingGroup = attributes["group-title"]?.takeIf { it.isNotBlank() }
                    pendingEpgId = (attributes["tvg-id"] ?: attributes["tvg-chno"])?.takeIf { it.isNotBlank() }
                }
                line.startsWith("#") -> {
                    // Other directives (#EXTGRP, #EXTVLCOPT, #EXTM3U, ...) are not needed for playback.
                }
                else -> {
                    val streamUrl = line
                    if (streamUrl.startsWith("http://", ignoreCase = true) ||
                        streamUrl.startsWith("https://", ignoreCase = true)
                    ) {
                        index += 1
                        channels += LiveTvChannel(
                            id = "livetv_$index",
                            name = pendingName?.takeIf { it.isNotBlank() } ?: "Channel $index",
                            streamUrl = streamUrl,
                            logoUrl = pendingLogo,
                            groupTitle = pendingGroup,
                            epgChannelId = pendingEpgId
                        )
                    }
                    pendingName = null
                    pendingLogo = null
                    pendingGroup = null
                    pendingEpgId = null
                }
            }
        }

        return channels
    }
}
