package com.nuvio.tv.ui.screens.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.discover.DiscoverCategory
import com.nuvio.tv.data.discover.DiscoverGenre
import com.nuvio.tv.data.discover.DiscoverMediaFilter
import com.nuvio.tv.data.discover.DiscoverPage
import com.nuvio.tv.data.discover.TmdbDeepPager
import com.nuvio.tv.data.discover.TmdbDiscoverRepository
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TmdbDiscoverUiState(
    val mediaFilter: DiscoverMediaFilter = DiscoverMediaFilter.ALL,
    val category: DiscoverCategory = DiscoverCategory.POPULAR,
    val genres: List<DiscoverGenre> = emptyList(),
    val selectedGenre: DiscoverGenre? = null,
    val year: Int? = null,
    val items: List<MetaPreview> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val loadFailed: Boolean = false
) {
    /** Changes whenever the menus change, so the grid goes back to the top. */
    val filterKey: String get() = "$mediaFilter|$category|${selectedGenre?.label}|$year"
}

/**
 * The Discover page: TMDB's whole catalog (movies, shows or both) by Category (Popular, Trending,
 * New, All), Genre and Year. More titles load as the grid scrolls, with no limit: past TMDB's
 * 10,000-title cap the list goes on a stretch of time at a time (see [TmdbDeepPager]), so every
 * movie and show TMDB has can be reached.
 */
@HiltViewModel
class TmdbDiscoverViewModel @Inject constructor(
    private val repository: TmdbDiscoverRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    /**
     * Opened from one of Home's genre cards: the TMDB genre to pick once the Genre menu is known
     * (a series genre opens with Type set to TV Shows).
     */
    private val startShows = savedStateHandle.get<String>("type") == "shows"
    private var pendingGenreId: Int? = savedStateHandle.get<Int>("genreId")

    private val _uiState = MutableStateFlow(
        TmdbDiscoverUiState(mediaFilter = if (startShows) DiscoverMediaFilter.SHOWS else DiscoverMediaFilter.ALL)
    )
    val uiState: StateFlow<TmdbDiscoverUiState> = _uiState.asStateFlow()

    /**
     * One kind of title being paged through (movies or shows) for the current menus: [pager] for the
     * whole catalog, or plain pages for Trending (which TMDB keeps short).
     */
    private class Stream(
        val mediaType: TmdbCollectionMediaType,
        val genreId: Int?,
        val pager: TmdbDeepPager<MetaPreview>?
    ) {
        var nextPage = 1
        var lastPage = TmdbDiscoverRepository.TMDB_MAX_PAGE
        val done get() = pager?.isDone ?: (nextPage > lastPage)
    }

    private var streams: List<Stream> = emptyList()
    private val seen = HashSet<String>()
    private var loadJob: Job? = null
    private var genresJob: Job? = null

    init {
        loadGenres(_uiState.value.mediaFilter)
        // With a genre to pick, the list starts once it's picked (when the Genre menu is known).
        if (pendingGenreId == null) restart()
    }

    fun selectMediaFilter(filter: DiscoverMediaFilter) {
        if (filter == _uiState.value.mediaFilter) return
        _uiState.update { it.copy(mediaFilter = filter) }
        loadGenres(filter)
        restart()
    }

    fun selectCategory(category: DiscoverCategory) {
        if (category == _uiState.value.category) return
        _uiState.update { it.copy(category = category) }
        restart()
    }

    fun selectGenre(genre: DiscoverGenre?) {
        if (genre?.label == _uiState.value.selectedGenre?.label) return
        _uiState.update { it.copy(selectedGenre = genre) }
        restart()
    }

    fun selectYear(year: Int?) {
        if (year == _uiState.value.year) return
        _uiState.update { it.copy(year = year) }
        restart()
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasMore) return
        _uiState.update { it.copy(isLoadingMore = true, loadFailed = false) }
        loadJob = viewModelScope.launch { loadNextBatch() }
    }

    fun retry() {
        if (_uiState.value.items.isEmpty()) restart() else loadMore()
    }

    private fun loadGenres(filter: DiscoverMediaFilter) {
        genresJob?.cancel()
        genresJob = viewModelScope.launch {
            val genres = runCatching { repository.genres(filter, repository.language()) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault(emptyList())
            val wanted = pendingGenreId
            if (genres.isEmpty()) {
                // The genre can't be picked without the menu: show everything rather than nothing.
                if (wanted != null) {
                    pendingGenreId = null
                    restart()
                }
                return@launch
            }
            val previous = _uiState.value.selectedGenre
            // The genre card's genre; else the same genre on the new list (by name, e.g. "Action"),
            // or none if that Type doesn't have it.
            val kept = if (wanted != null) {
                pendingGenreId = null
                genres.firstOrNull { genre ->
                    if (filter == DiscoverMediaFilter.SHOWS) genre.tvId == wanted else genre.movieId == wanted
                }
            } else {
                previous?.let { old -> genres.firstOrNull { it.label == old.label } }
            }
            _uiState.update { it.copy(genres = genres, selectedGenre = kept) }
            if (kept != previous || wanted != null) restart()
        }
    }

    /** New menus: start over from the beginning. */
    private fun restart() {
        loadJob?.cancel()
        val state = _uiState.value
        val genre = state.selectedGenre
        val wantMovies = state.mediaFilter != DiscoverMediaFilter.SHOWS && (genre == null || genre.movieId != null)
        val wantShows = state.mediaFilter != DiscoverMediaFilter.MOVIES && (genre == null || genre.tvId != null)
        seen.clear()
        _uiState.update {
            it.copy(items = emptyList(), isLoading = true, isLoadingMore = false, hasMore = wantMovies || wantShows, loadFailed = false)
        }
        loadJob = viewModelScope.launch {
            val language = repository.language()
            streams = buildList {
                if (wantMovies) add(stream(TmdbCollectionMediaType.MOVIE, genre?.movieId, state, language))
                if (wantShows) add(stream(TmdbCollectionMediaType.TV, genre?.tvId, state, language))
            }
            loadNextBatch()
        }
    }

    private fun stream(mediaType: TmdbCollectionMediaType, genreId: Int?, state: TmdbDiscoverUiState, language: String) =
        Stream(mediaType, genreId, repository.deepPager(mediaType, state.category, genreId, state.year, language))

    /**
     * Fetches the next page of each kind of title together and mixes them (a movie, a show, a movie...).
     * Trending with a Genre or Year is filtered page by page, so it keeps going until a screenful is found.
     */
    private suspend fun loadNextBatch() {
        val state = _uiState.value
        val language = repository.language()
        val added = ArrayList<MetaPreview>()
        var anySucceeded = false
        var anyFailed = false
        var rounds = 0
        while (added.size < MIN_BATCH && rounds < MAX_ROUNDS_PER_BATCH) {
            val active = streams.filterNot { it.done }
            if (active.isEmpty()) break
            rounds++
            val pages: List<List<MetaPreview>?> = coroutineScope {
                active.map { stream ->
                    async {
                        try {
                            val pager = stream.pager
                            if (pager != null) {
                                pager.next()
                            } else {
                                val result: DiscoverPage = repository.page(
                                    mediaType = stream.mediaType,
                                    category = state.category,
                                    genreId = stream.genreId,
                                    year = state.year,
                                    page = stream.nextPage,
                                    language = language
                                )
                                stream.lastPage = result.lastPage
                                stream.nextPage += 1
                                result.items
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    }
                }.awaitAll()
            }
            if (pages.all { it == null }) {
                anyFailed = true
                break
            }
            anySucceeded = true
            val lists = pages.filterNotNull()
            val longest = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until longest) {
                for (list in lists) {
                    val item = list.getOrNull(i) ?: continue
                    if (seen.add("${item.apiType}:${item.id}")) added += item
                }
            }
        }
        val hasMore = streams.any { !it.done }
        _uiState.update {
            it.copy(
                items = it.items + added,
                isLoading = false,
                isLoadingMore = false,
                hasMore = hasMore,
                loadFailed = anyFailed && !anySucceeded
            )
        }
    }

    private companion object {
        /** Titles to gather before showing a batch (a few rows of the grid). */
        const val MIN_BATCH = 20
        /**
         * Most page fetches per batch, so a rare Trending filter can't run forever. Past the first
         * 10,000 titles a few pages can be all repeats (skipped), so there's room to get past them.
         */
        const val MAX_ROUNDS_PER_BATCH = 30
    }
}
