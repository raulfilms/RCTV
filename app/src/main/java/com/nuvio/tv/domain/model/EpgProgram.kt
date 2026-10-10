package com.nuvio.tv.domain.model

/** A single program entry from an XMLTV EPG feed, tied to a channel via [channelId]. */
data class EpgProgram(
    val channelId: String,
    val title: String,
    val description: String? = null,
    val category: String? = null,
    val startMs: Long,
    val endMs: Long,
    /** Episode name or other secondary title (XMLTV `sub-title`). */
    val subtitle: String? = null,
    /** Program artwork (XMLTV `icon`), when the feed has one. */
    val imageUrl: String? = null,
    /** Broadcast live: marked `<live/>` or "[LIVE]", or a new airing of a sports event. */
    val isLive: Boolean = false,
    /** A new episode or first airing: marked `<new/>` or "[NEW]". */
    val isNew: Boolean = false,
    /** Every XMLTV category of the program ([category] is the first one). */
    val categories: List<String> = emptyList()
) {
    fun isAiringAt(timeMs: Long): Boolean = timeMs in startMs until endMs

    /** 0f..1f how far through the program [timeMs] is; clamps outside the program's window. */
    fun progressAt(timeMs: Long): Float {
        val duration = (endMs - startMs).coerceAtLeast(1L)
        return ((timeMs - startMs).toFloat() / duration).coerceIn(0f, 1f)
    }
}
