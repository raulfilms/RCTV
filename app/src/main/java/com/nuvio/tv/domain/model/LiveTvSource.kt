package com.nuvio.tv.domain.model

/**
 * One configured IPTV source: an M3U playlist, an Xtream Codes account, or a Stalker/MAG portal.
 *
 * Added by the multi-source redesign (ARVIO merge, phase 1 step 3) so a profile can combine
 * several playlists/accounts at once instead of a single active connection - mirroring ARVIO's
 * own `IptvPlaylistEntry`/`StalkerPortalEntry` model. [LiveTvDataStore.connection] still exposes
 * a single-connection view (the first enabled source) for callers that haven't moved to
 * [LiveTvDataStore.sources] yet; the richer multi-source management UI itself is phase 2 work.
 */
data class LiveTvSource(
    val id: String,
    val name: String,
    val connection: LiveTvConnection,
    val enabled: Boolean = true
)
