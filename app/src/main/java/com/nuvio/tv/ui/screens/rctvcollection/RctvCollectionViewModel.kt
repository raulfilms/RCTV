package com.nuvio.tv.ui.screens.rctvcollection

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.kids.KidsContentGate
import com.nuvio.tv.core.rctvcontrol.RctvCollection
import com.nuvio.tv.core.rctvcontrol.RctvCollectionItem
import com.nuvio.tv.core.rctvcontrol.RctvCollectionTheme
import com.nuvio.tv.core.rctvcontrol.RctvPublicFiles
import com.nuvio.tv.core.rctvcontrol.RctvThemeMedia
import com.nuvio.tv.core.rctvcontrol.RctvThemeSort
import com.nuvio.tv.data.discover.TmdbDiscoverRepository
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverResponse
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class RctvCollectionUiState(
    val name: String = "",
    val items: List<MetaPreview> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    /** The collection isn't published (any more), or nothing could be loaded. */
    val unavailable: Boolean = false
)

/**
 * RCTV Control: a collection from the website (opened from a Home banner): the titles the owner
 * picked, in their order, or every movie and series with the collection's TMDB themes, a page at a
 * time. A Kids profile sees only titles for kids.
 */
@HiltViewModel
class RctvCollectionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val publicFiles: RctvPublicFiles,
    private val tmdbApi: TmdbApi,
    private val discoverRepository: TmdbDiscoverRepository,
    private val kidsGate: KidsContentGate
) : ViewModel() {

    private val collectionId: String = savedStateHandle.get<String>("collectionId")
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        .orEmpty()

    private val _uiState = MutableStateFlow(RctvCollectionUiState())
    val uiState: StateFlow<RctvCollectionUiState> = _uiState.asStateFlow()

    private var collection: RctvCollection? = null
    private var nextPage = 1
    private var hasMore = false
    private var loadJob: Job? = null

    init {
        loadJob = viewModelScope.launch {
            val found = publicFiles.collections.first().firstOrNull { it.id == collectionId }
            if (found == null) {
                _uiState.value = RctvCollectionUiState(isLoading = false, unavailable = true)
                return@launch
            }
            collection = found
            _uiState.value = RctvCollectionUiState(name = found.name, isLoading = true)
            val theme = found.theme
            if (theme == null) {
                val items = kidsGate.keep(found.items.map(::previewOf))
                _uiState.value = RctvCollectionUiState(name = found.name, items = items, isLoading = false)
            } else {
                loadThemePage(theme, first = true)
            }
        }
    }

    /** The next page of a collection by theme (the picked titles are all there already). */
    fun loadMore() {
        val theme = collection?.theme ?: return
        if (!hasMore || loadJob?.isActive == true) return
        loadJob = viewModelScope.launch { loadThemePage(theme, first = false) }
    }

    private suspend fun loadThemePage(theme: RctvCollectionTheme, first: Boolean) {
        if (!first) _uiState.update { it.copy(isLoadingMore = true) }
        try {
            val page = nextPage
            val language = discoverRepository.language()
            val (movies, series) = coroutineScope {
                val m = async { if (theme.media != RctvThemeMedia.SERIES) discoverMovies(theme, page, language) else null }
                val s = async { if (theme.media != RctvThemeMedia.MOVIES) discoverSeries(theme, page, language) else null }
                m.await() to s.await()
            }
            val found = merge(
                movies?.results.orEmpty().mapNotNull { it.toPreview(isSeries = false) },
                series?.results.orEmpty().mapNotNull { it.toPreview(isSeries = true) },
                theme.sort
            )
            val lastPage = maxOf(movies?.totalPages ?: 0, series?.totalPages ?: 0)
            nextPage = page + 1
            hasMore = page < minOf(lastPage, MAX_PAGES)
            val kept = kidsGate.keep(found)
            _uiState.update { state ->
                val known = state.items.mapTo(HashSet()) { it.id + it.rawType }
                state.copy(
                    items = state.items + kept.filter { known.add(it.id + it.rawType) },
                    isLoading = false,
                    isLoadingMore = false,
                    unavailable = first && kept.isEmpty() && !hasMore
                )
            }
            // A Kids profile can be left with very few titles on a page: read on so the page fills.
            if (hasMore && _uiState.value.items.size < MIN_SHOWN) loadThemePage(theme, first = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, isLoadingMore = false, unavailable = it.items.isEmpty()) }
        }
    }

    private suspend fun discoverMovies(theme: RctvCollectionTheme, page: Int, language: String): TmdbDiscoverResponse? =
        tmdbApi.discoverMovies(
            apiKey = BuildConfig.TMDB_API_KEY,
            language = language,
            page = page,
            sortBy = when (theme.sort) {
                RctvThemeSort.POPULAR -> "popularity.desc"
                RctvThemeSort.NEWEST -> "primary_release_date.desc"
                RctvThemeSort.RATING -> "vote_average.desc"
            },
            withKeywords = theme.keywordIds.joinToString("|"),
            releaseDateLte = if (theme.sort == RctvThemeSort.NEWEST) LocalDate.now().toString() else null,
            voteCountGte = if (theme.sort == RctvThemeSort.RATING) RATED_MIN_VOTES else null,
            includeVideo = false
        ).body()

    private suspend fun discoverSeries(theme: RctvCollectionTheme, page: Int, language: String): TmdbDiscoverResponse? =
        tmdbApi.discoverTv(
            apiKey = BuildConfig.TMDB_API_KEY,
            language = language,
            page = page,
            sortBy = when (theme.sort) {
                RctvThemeSort.POPULAR -> "popularity.desc"
                RctvThemeSort.NEWEST -> "first_air_date.desc"
                RctvThemeSort.RATING -> "vote_average.desc"
            },
            withKeywords = theme.keywordIds.joinToString("|"),
            firstAirDateLte = if (theme.sort == RctvThemeSort.NEWEST) LocalDate.now().toString() else null,
            voteCountGte = if (theme.sort == RctvThemeSort.RATING) RATED_MIN_VOTES else null
        ).body()

    private fun merge(movies: List<MetaPreview>, series: List<MetaPreview>, sort: RctvThemeSort): List<MetaPreview> {
        val all = movies + series
        return when (sort) {
            RctvThemeSort.POPULAR -> all.sortedByDescending { it.popularity ?: 0.0 }
            RctvThemeSort.NEWEST -> all.sortedByDescending { it.released.orEmpty() }
            RctvThemeSort.RATING -> all.sortedByDescending { it.imdbRating ?: 0f }
        }
    }

    private fun previewOf(item: RctvCollectionItem): MetaPreview = MetaPreview(
        id = "tmdb:${item.tmdbId}",
        type = if (item.isSeries) ContentType.SERIES else ContentType.MOVIE,
        rawType = if (item.isSeries) "series" else "movie",
        name = item.title,
        poster = imageUrl(item.posterPath, "w500") ?: imageUrl(item.backdropPath, "w780"),
        posterShape = PosterShape.POSTER,
        background = imageUrl(item.backdropPath, "w1280"),
        landscapePoster = imageUrl(item.backdropPath, "w780"),
        logo = null,
        description = null,
        releaseInfo = item.year?.toString(),
        imdbRating = null,
        genres = emptyList()
    )

    private fun TmdbDiscoverResult.toPreview(isSeries: Boolean): MetaPreview? {
        val name = (if (isSeries) this.name else title)?.takeIf { it.isNotBlank() }
            ?: originalTitle?.takeIf { it.isNotBlank() }
            ?: originalName?.takeIf { it.isNotBlank() }
            ?: return null
        val date = if (isSeries) firstAirDate else releaseDate
        return MetaPreview(
            id = "tmdb:$id",
            type = if (isSeries) ContentType.SERIES else ContentType.MOVIE,
            rawType = if (isSeries) "series" else "movie",
            name = name,
            poster = imageUrl(posterPath, "w500") ?: imageUrl(backdropPath, "w780"),
            posterShape = PosterShape.POSTER,
            background = imageUrl(backdropPath, "w1280"),
            landscapePoster = imageUrl(backdropPath, "w780"),
            logo = null,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = date?.take(4)?.takeIf { it.isNotBlank() },
            released = date?.takeIf { it.isNotBlank() },
            imdbRating = voteAverage?.toFloat()?.takeIf { it > 0f },
            voteCount = voteCount,
            genres = emptyList(),
            popularity = popularity
        )
    }

    private fun imageUrl(path: String?, size: String): String? =
        path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }

    private companion object {
        /** TMDB gives at most 500 pages; a theme past this many is plenty to scroll. */
        const val MAX_PAGES = 25
        const val MIN_SHOWN = 12
        const val RATED_MIN_VOTES = 150
    }
}
