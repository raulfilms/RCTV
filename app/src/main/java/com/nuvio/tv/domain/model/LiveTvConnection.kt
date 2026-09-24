package com.nuvio.tv.domain.model

/** How the user's Live TV channels/EPG are sourced. */
sealed class LiveTvConnection {
    data class M3u(
        val playlistUrl: String,
        val epgUrl: String? = null
    ) : LiveTvConnection()

    data class Xtream(
        val serverUrl: String,
        val username: String,
        val password: String
    ) : LiveTvConnection()

    data class Stalker(
        val portalUrl: String,
        val macAddress: String
    ) : LiveTvConnection()
}
