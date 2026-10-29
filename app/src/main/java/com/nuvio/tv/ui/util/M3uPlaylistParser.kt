package com.nuvio.tv.ui.util

import com.nuvio.tv.core.util.GuideText
import com.nuvio.tv.domain.model.LiveTvChannel
import java.io.Reader

/**
 * Minimal EXTM3U / M3U8 IPTV playlist parser. Understands the widely-used
 * `#EXTINF:-1 tvg-logo="..." group-title="...",Channel Name` + stream-URL-on-next-line
 * shape used by IPTV playlists (Xtream, community M3U lists, etc). Unknown/unsupported
 * tags are ignored rather than causing a failure, so partially-malformed playlists still
 * yield whatever channels can be recognized.
 */
object M3uPlaylistParser {


    fun parse(playlistText: String): List<LiveTvChannel> {
        if (!playlistText.contains("#EXTM3U") && !playlistText.contains("#EXTINF")) {
            return emptyList()
        }
        return parseLines(playlistText.lineSequence(), requireHeader = false)
    }

    /**
     * RCTV: the playlist read as it downloads, line by line, instead of as one text (a provider's
     * full playlist can be tens of megabytes). Lines are only taken once the playlist has shown it
     * is one (`#EXTM3U` or `#EXTINF`), so an error page is never read as channels.
     */
    fun parse(reader: Reader): List<LiveTvChannel> =
        reader.buffered(64 * 1024).useLines { lines -> parseLines(lines, requireHeader = true) }

    /**
     * True for a provider's movie or series episode listed in its playlist (an "m3u_plus" list
     * has its whole catalog of films and episodes after the channels). They aren't live channels:
     * no guide, nothing on now, and tens of thousands of them made the Guide, its filters and its
     * search slow and took a lot of memory.
     */
    internal fun isOnDemandFile(streamUrl: String): Boolean {
        val path = streamUrl.substringAfter("://").substringAfter('/', "")
        return GuideText.hasVodPathSegment("/$path") || GuideText.hasVodFileExtension(path)
    }

    private fun parseLines(lines: Sequence<String>, requireHeader: Boolean): List<LiveTvChannel> {
        val channels = mutableListOf<LiveTvChannel>()
        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null
        var pendingEpgId: String? = null
        var pendingCountry: String? = null
        var pendingNumber: String? = null
        var index = 0
        var isPlaylist = !requireHeader

        lines.forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    isPlaylist = true
                    val commaIndex = line.lastIndexOf(',')
                    val attributesPart = if (commaIndex >= 0) line.substring(0, commaIndex) else line
                    pendingName = (if (commaIndex >= 0) line.substring(commaIndex + 1) else "").trim()
                        .ifBlank { null }
                    // RCTV (MEM-07): read without a regular expression (GuideText), for every channel.
                    val attributes = GuideText.m3uAttributes(attributesPart)
                    pendingLogo = attributes["tvg-logo"]?.takeIf { it.isNotBlank() }
                    pendingGroup = attributes["group-title"]?.takeIf { it.isNotBlank() }
                    pendingEpgId = (attributes["tvg-id"] ?: attributes["tvg-chno"])?.takeIf { it.isNotBlank() }
                    pendingCountry = attributes["tvg-country"]?.takeIf { it.isNotBlank() }
                    pendingNumber = attributes["tvg-chno"]?.takeIf { it.isNotBlank() }
                }
                line.startsWith("#") -> {
                    if (line.startsWith("#EXTM3U", ignoreCase = true)) isPlaylist = true
                    // Other directives (#EXTGRP, #EXTVLCOPT, ...) are not needed for playback.
                }
                else -> {
                    val streamUrl = line
                    if (isPlaylist && (
                            streamUrl.startsWith("http://", ignoreCase = true) ||
                                streamUrl.startsWith("https://", ignoreCase = true)
                            )
                    ) {
                        // Counted either way, so each channel keeps the id it always had (the
                        // Guide's Custom list remembers channels by it).
                        index += 1
                        if (!isOnDemandFile(streamUrl)) {
                            channels += LiveTvChannel(
                                id = "livetv_$index",
                                name = pendingName?.takeIf { it.isNotBlank() } ?: "Channel $index",
                                streamUrl = streamUrl,
                                logoUrl = pendingLogo,
                                groupTitle = pendingGroup,
                                epgChannelId = pendingEpgId,
                                country = pendingCountry,
                                number = pendingNumber
                            )
                        }
                    }
                    pendingName = null
                    pendingLogo = null
                    pendingGroup = null
                    pendingEpgId = null
                    pendingCountry = null
                    pendingNumber = null
                }
            }
        }

        return channels
    }
}
