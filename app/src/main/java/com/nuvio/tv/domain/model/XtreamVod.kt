package com.nuvio.tv.domain.model

/**
 * A single VOD (movie) entry from an Xtream Codes account's `get_vod_streams` catalog.
 * Kept separate from the app's normal [Stream]/catalog models since it's sourced directly from
 * the user's own Xtream panel rather than an installed addon.
 */
data class XtreamVodItem(
    val id: String,
    val name: String,
    val streamUrl: String,
    val posterUrl: String? = null,
    val year: String? = null,
    val categoryName: String? = null,
    /** Present on some panels; lets a later pass match this item against the app's normal metadata (TMDB/IMDB-backed) pipeline instead of relying on the panel's own (often poor) artwork/title data. */
    val imdbId: String? = null,
    val tmdbId: String? = null
)

/** A TV series entry from an Xtream Codes account's `get_series` catalog. Episodes are fetched separately, on demand, via [XtreamClient.fetchSeriesEpisodes]. */
data class XtreamSeriesItem(
    val id: String,
    val name: String,
    val posterUrl: String? = null,
    val categoryName: String? = null,
    val imdbId: String? = null,
    val tmdbId: String? = null
)

/** A single episode of an [XtreamSeriesItem], resolved from `get_series_info`. */
data class XtreamSeriesEpisode(
    val id: String,
    val seriesId: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val streamUrl: String
)
