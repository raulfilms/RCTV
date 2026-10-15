package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class MetaPreview(
    val id: String,
    val type: ContentType,
    val rawType: String = type.toApiString(),
    val name: String,
    val poster: String?,
    val posterShape: PosterShape,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val imdbRating: Float?,
    val genres: List<String>,
    val runtime: String? = null,
    val status: String? = null,
    val ageRating: String? = null,
    val language: String? = null,
    val released: String? = null,
    val country: String? = null,
    val imdbId: String? = null,
    val slug: String? = null,
    val landscapePoster: String? = null,
    val rawPosterUrl: String? = null,
    val director: List<String> = emptyList(),
    val writer: List<String> = emptyList(),
    val links: List<MetaLink> = emptyList(),
    val behaviorHints: MetaBehaviorHints? = null,
    val trailers: List<MetaTrailer> = emptyList(),
    val trailerYtIds: List<String> = emptyList(),
    val seasonCount: Int? = null,
    val voteCount: Int? = null,
    val sourceAddonBaseUrl: String? = null,
    /** True when [landscapePoster] already has the title's name on it (a TMDB "title card" backdrop). */
    val landscapeHasTitle: Boolean = false,
    /** A label on the card ("NEW", "PREMIERE", "COMING SOON"), e.g. on Home's Premieres row. */
    val badge: String? = null,
    /** The [badge] is for a title still to come (styled apart). */
    val badgeUpcoming: Boolean = false
) {
    val apiType: String
        get() = type.toApiString(rawType)

    val backdropUrl: String?
        get() = background ?: landscapePoster ?: poster
}
