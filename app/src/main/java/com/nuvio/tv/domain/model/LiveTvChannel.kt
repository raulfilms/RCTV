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
    val sourceId: String? = null,
    /** ISO 3166 two-letter country code ("US"), from the playlist or guessed from the channel's name or group. */
    val country: String? = null,
    /** Channel number from the playlist or guide ("7.1" is a broadcast sub-channel). */
    val number: String? = null,
    /** RCTV (owner's channel list): its Guide category (a GuideBrowse name), or null to sort it from its programs. */
    val guideCategory: String? = null,
    /** RCTV: another version of the Guide's channel with this id (the player turns to it when that one fails). */
    val versionOf: String? = null,
    /**
     * RCTV (owner): how many days back the IPTV provider keeps this channel's programs to watch
     * again (catch-up); 0 when it keeps none.
     */
    val archiveDays: Int = 0
)

/**
 * RCTV (owner): a program the IPTV provider recorded on a channel (catch-up), to watch again.
 * [serverStart] is its start as the provider's server writes it ("2026-11-17:21-00").
 */
data class LiveCatchUpProgram(
    val title: String,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
    val serverStart: String,
    val durationMinutes: Int
) {
    /** Still on (it can be started over from the beginning). */
    fun isAiringAt(timeMs: Long): Boolean = timeMs in startMs until endMs
}

/**
 * A channel in the Guide's "Custom" list as Home's Top Channels shows it: its name and logo are
 * kept with the list, so Home doesn't have to load the IPTV sources.
 */
data class CustomLiveChannel(
    val id: String,
    val name: String,
    val logoUrl: String? = null,
    /** Its place in the Guide's channel list, so Home shows them in the Guide's order. */
    val position: Int = Int.MAX_VALUE
)
