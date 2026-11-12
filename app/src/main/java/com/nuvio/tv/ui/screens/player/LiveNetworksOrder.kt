package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.LiveTvChannel

/**
 * RCTV: the Networks row's order: the channel playing, the recent channels, the Guide's Custom
 * (favorite) channels, then every other channel in the Guide's order, each once.
 */
internal fun networksOrder(
    channels: List<LiveTvChannel>,
    currentId: String?,
    recentIds: List<String>,
    favoriteIds: Set<String>
): List<LiveTvChannel> {
    if (channels.isEmpty()) return emptyList()
    val byId = HashMap<String, LiveTvChannel>(channels.size * 2)
    channels.forEach { byId.putIfAbsent(it.id, it) }
    val seen = HashSet<String>()
    val ordered = ArrayList<LiveTvChannel>(byId.size)
    fun add(channel: LiveTvChannel?) {
        if (channel != null && seen.add(channel.id)) ordered += channel
    }
    add(currentId?.let(byId::get))
    recentIds.forEach { add(byId[it]) }
    if (favoriteIds.isNotEmpty()) channels.forEach { if (it.id in favoriteIds) add(it) }
    channels.forEach(::add)
    return ordered
}

/**
 * RCTV (long-press OK): the channel watched before [currentId]: the newest recent channel that
 * isn't the one playing and that the lineup still has.
 */
internal fun previousLiveChannel(
    channels: List<LiveTvChannel>,
    currentId: String?,
    recentIds: List<String>
): LiveTvChannel? {
    if (channels.isEmpty()) return null
    return recentIds.asSequence()
        .filter { it != currentId }
        .mapNotNull { id -> channels.firstOrNull { it.id == id } }
        .firstOrNull()
}
