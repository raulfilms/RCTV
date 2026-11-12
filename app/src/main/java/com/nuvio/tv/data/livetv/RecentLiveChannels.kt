package com.nuvio.tv.data.livetv

/**
 * RCTV (Networks): the recent channels kept per profile for the Live TV player's Networks row
 * and the long-press OK shortcut: the last [MAX] channels watched, newest first, each once.
 */
object RecentLiveChannels {
    const val MAX = 4

    /** [current] once [channelId] was watched: it goes first, its older place is dropped. */
    fun after(current: List<String>, channelId: String): List<String> {
        if (channelId.isBlank()) return current.take(MAX)
        return (listOf(channelId) + current.filter { it != channelId && it.isNotBlank() }).distinct().take(MAX)
    }
}
