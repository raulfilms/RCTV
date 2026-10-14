package com.nuvio.tv.data.discover

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import java.util.Locale

/**
 * A See All page's Type / Genre / Year filter. TMDB applies it to its queries, so it covers a row's
 * whole catalog (every Drama movie on Disney+, not just the ones already on screen).
 */
data class TmdbSeeAllFilter(
    val mediaType: TmdbCollectionMediaType? = null,
    val genre: DiscoverGenre? = null,
    val year: Int? = null
) {
    val isEmpty: Boolean get() = mediaType == null && genre == null && year == null

    /** The genre's TMDB id for movies or for shows (null: that kind of title doesn't have it). */
    fun genreId(mediaType: TmdbCollectionMediaType): Int? = when (mediaType) {
        TmdbCollectionMediaType.MOVIE -> genre?.movieId
        TmdbCollectionMediaType.TV -> genre?.tvId
    }
}

/**
 * True when a title that didn't come from a filtered TMDB query (a hand-picked title, a franchise
 * movie) passes the filter: same kind of title, one of the genre's names, released that year.
 */
fun MetaPreview.matches(filter: TmdbSeeAllFilter): Boolean {
    val isShow = type == ContentType.SERIES
    filter.mediaType?.let { wanted ->
        if (isShow != (wanted == TmdbCollectionMediaType.TV)) return false
    }
    filter.genre?.let { genre ->
        if (filter.genreId(if (isShow) TmdbCollectionMediaType.TV else TmdbCollectionMediaType.MOVIE) == null) return false
        if (genres.none { it.trim().lowercase(Locale.ROOT) in genre.names }) return false
    }
    filter.year?.let { year ->
        val released = (released ?: releaseInfo)?.take(4)
        if (released != year.toString()) return false
    }
    return true
}
