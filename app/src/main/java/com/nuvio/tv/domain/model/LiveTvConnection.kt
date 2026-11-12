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
        val password: String,
        /**
         * RCTV Control: the guide's address set on the website for the account (XMLTV, may carry the
         * account's login: never logged). Null: the provider's own guide.
         */
        val epgUrl: String? = null
    ) : LiveTvConnection() {
        // The login and the guide's address stay out of anything printed.
        override fun toString(): String = "Xtream(hidden)"
    }

    data class Stalker(
        val portalUrl: String,
        val macAddress: String
    ) : LiveTvConnection()
}
