package com.nuvio.tv.domain.model

/** A single Live TV channel, whether sourced from an M3U playlist or an Xtream Codes account. */
data class LiveTvChannel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val groupTitle: String? = null,
    /** Xtream Codes' internal channel identifier, used to correlate with EPG (Phase 2). */
    val epgChannelId: String? = null,
    /** Which configured [LiveTvSource] this channel came from, when merged from multiple sources (phase 2). Null for a channel not yet attributed to a source. */
    val sourceId: String? = null
)
