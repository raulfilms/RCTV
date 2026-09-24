package com.nuvio.tv.domain.model

/**
 * A single VOD (movie) entry from a Stalker/Ministra portal's `vod` catalog.
 * [cmd] is the portal's own playback token; it must be exchanged for a real
 * stream URL via `StalkerClient.resolveVodStreamUrl` right before playback
 * starts (Stalker links are short-lived, unlike Xtream's static URLs).
 */
data class StalkerVodItem(
    val id: String,
    val name: String,
    val cmd: String,
    val posterUrl: String? = null,
    val year: String? = null,
    val categoryName: String? = null,
    val imdbId: String? = null,
    val tmdbId: String? = null
)

/**
 * A TV show entry from a Stalker portal's `series` catalog (level one of the
 * portal's two-level series model). Its seasons are fetched separately, on
 * demand, via `StalkerClient.fetchSeasons`.
 */
data class StalkerSeriesItem(
    val id: String,
    val name: String,
    val posterUrl: String? = null,
    val categoryName: String? = null,
    val imdbId: String? = null,
    val tmdbId: String? = null
)

/**
 * One season of a [StalkerSeriesItem] (level two). [episodeNumbers] lists the
 * episodes available in that season; there is no separate id per episode,
 * only a number that's passed alongside [cmd] to
 * `StalkerClient.resolveVodStreamUrl` to resolve a specific episode's stream.
 */
data class StalkerSeasonItem(
    val id: String,
    val name: String,
    val cmd: String,
    val episodeNumbers: List<Int>
)
