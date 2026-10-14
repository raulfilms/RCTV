package com.nuvio.tv.ui.screens.tmdb

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.poster.withCustomPosterUrls
import com.nuvio.tv.core.tmdb.TmdbEntityBrowseData
import com.nuvio.tv.core.tmdb.TmdbEntityKind
import com.nuvio.tv.core.tmdb.TmdbEntityRailType
import com.nuvio.tv.core.tmdb.TmdbEntityMediaType
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.data.discover.DiscoverGenre
import com.nuvio.tv.data.discover.DiscoverMediaFilter
import com.nuvio.tv.data.discover.TmdbDeepPager
import com.nuvio.tv.data.discover.TmdbDiscoverRepository
import com.nuvio.tv.data.discover.TmdbSeeAllFilter
import com.nuvio.tv.data.discover.matches
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.domain.model.MetaPreview
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.net.URLDecoder
import javax.inject.Inject

/**
 * A rail's See All page: every title the studio, network or service has (with no limit), and its
 * Genre / Year filter, applied by TMDB to all of them.
 */
data class EntitySeeAllFilterState(
    val railKey: String? = null,
    val filter: TmdbSeeAllFilter = TmdbSeeAllFilter(),
    val items: List<MetaPreview> = emptyList(),
    /** Next TMDB page of the filtered list, or null once everything is in. */
    val nextPage: Int? = 1,
    val isLoading: Boolean = false,
    val genres: List<DiscoverGenre> = emptyList(),
    /** Newest first. */
    val years: List<Int> = emptyList(),
    /** TMDB's totals by genre label (for the picked Year), filled in once the menu opens. */
    val genreCounts: Map<String, Int> = emptyMap()
)

@HiltViewModel
class TmdbEntityBrowseViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val watchProgressRepository: com.nuvio.tv.domain.repository.WatchProgressRepository,
    private val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    private val layoutPreferenceDataStore: com.nuvio.tv.data.local.LayoutPreferenceDataStore,
    val posterOptions: com.nuvio.tv.ui.components.posteroptions.PosterOptionsController,
    streamingServicesRepository: com.nuvio.tv.data.streaming.StreamingServicesRepository,
    private val hubRepository: com.nuvio.tv.data.disneyplus.DisneyPlusRepository,
    private val discoverRepository: TmdbDiscoverRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** Title art for the See All page's horizontal cards, found as they appear. */
    val titleArt: com.nuvio.tv.domain.repository.TitleArtResolver get() = hubRepository.titleArtResolver

    private val inFlightRailLoads = mutableSetOf<String>()

    val entityKind: TmdbEntityKind = TmdbEntityKind.fromRouteValue(
        savedStateHandle.get<String>("entityKind").orEmpty()
    )
    val entityId: Int = savedStateHandle.get<Int>("entityId") ?: 0
    val entityName: String = savedStateHandle.get<String>("entityName").orEmpty().let { raw ->
        runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }
    val sourceType: String = savedStateHandle.get<String>("sourceType").orEmpty()

    /**
     * Set when the page was opened from one of Home's streaming tiles: the platform's bundled logo, shown
     * centered above a big hero banner (the page then looks like the other streaming hubs).
     */
    val platformLogoRes: Int? = streamingServicesRepository.logoRes(savedStateHandle.get<String>("hub"))

    private val _heroItems = MutableStateFlow<List<com.nuvio.tv.domain.model.MetaPreview>>(emptyList())

    /** Hub hero slides: popular titles on the platform with a backdrop (title logos fill in shortly after). */
    val heroItems: StateFlow<List<com.nuvio.tv.domain.model.MetaPreview>> = _heroItems.asStateFlow()

    private val _watchedMovieIds = MutableStateFlow<Set<String>>(emptySet())
    val watchedMovieIds: StateFlow<Set<String>> = _watchedMovieIds.asStateFlow()
    val watchedSeriesIds: StateFlow<Set<String>> = watchedSeriesStateHolder.fullyWatchedSeriesIds

    private val _uiState = MutableStateFlow<TmdbEntityBrowseUiState>(TmdbEntityBrowseUiState.Loading)
    val uiState: StateFlow<TmdbEntityBrowseUiState> = _uiState.asStateFlow()

    private val _posterCardCornerRadiusDp = MutableStateFlow(12)
    val posterCardCornerRadiusDp: StateFlow<Int> = _posterCardCornerRadiusDp.asStateFlow()

    init {
        posterOptions.bind(viewModelScope)
        load()
        viewModelScope.launch {
            watchProgressRepository.observeWatchedMovieIds()
                .collect { ids -> _watchedMovieIds.value = ids }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.posterCardCornerRadiusDp
                .collect { _posterCardCornerRadiusDp.value = it }
        }
    }

    private val _seeAllRailKey = MutableStateFlow<String?>(null)

    /** The rail whose See All page is open ("tv_popular"...), or null. */
    val seeAllRailKey: StateFlow<String?> = _seeAllRailKey.asStateFlow()

    private val _seeAllFilter = MutableStateFlow(EntitySeeAllFilterState())

    /** The open See All page's Genre / Year filter and, while one is on, TMDB's matches. */
    val seeAllFilter: StateFlow<EntitySeeAllFilterState> = _seeAllFilter.asStateFlow()

    private var seeAllFilterJob: Job? = null
    private var seeAllMenusJob: Job? = null
    private var seeAllCountsJob: Job? = null
    private var seeAllCountsWanted = false
    private val countLimiter = Semaphore(6)

    /** The open See All page's list being loaded (the rail, its picks), with no limit. */
    private var seeAllPager: TmdbDeepPager<com.nuvio.tv.domain.model.MetaPreview>? = null

    /** Opens a rail's See All page (every title, more loading while scrolling). */
    fun openSeeAll(mediaType: TmdbEntityMediaType, railType: TmdbEntityRailType) {
        val railKey = "${mediaType.value}_${railType.value}"
        _seeAllRailKey.value = railKey
        seeAllFilterJob?.cancel()
        seeAllCountsJob?.cancel()
        seeAllCountsWanted = false
        val rail = openRail()
        seeAllPager = railPager(mediaType, railType, TmdbSeeAllFilter())
        _seeAllFilter.value = EntitySeeAllFilterState(railKey = railKey, items = rail?.items.orEmpty())
        loadSeeAllMenus(mediaType, railType, railKey)
        loadMoreFiltered()
    }

    /**
     * Every title of a rail for [filter], with no limit: past TMDB's 10,000-title cap the list goes on
     * a stretch of time at a time (see [TmdbDeepPager]). Recently released stays newest first.
     */
    private fun railPager(
        mediaType: TmdbEntityMediaType,
        railType: TmdbEntityRailType,
        filter: TmdbSeeAllFilter
    ): TmdbDeepPager<com.nuvio.tv.domain.model.MetaPreview> {
        val genreId = filter.genreId(mediaType.toCollectionType())
        val today = LocalDate.now()
        // Recently released, and a network's shows, only go up to today.
        val railEnd = if (railType == TmdbEntityRailType.RECENT ||
            (entityKind == TmdbEntityKind.NETWORK && mediaType == TmdbEntityMediaType.TV)
        ) today else null
        val from = filter.year?.let { LocalDate.of(it, 1, 1) }
        val to = listOfNotNull(filter.year?.let { LocalDate.of(it, 12, 31) }, railEnd).minOrNull()
        return TmdbDeepPager(
            from = from,
            to = to,
            dateOrder = if (railType == TmdbEntityRailType.RECENT) TmdbDeepPager.DateOrder.NEWEST_FIRST
            else TmdbDeepPager.DateOrder.NONE,
            idOf = { it.id }
        ) { page, windowFrom, windowTo ->
            val language = tmdbSettingsDataStore.settings.first().language
            val result = tmdbMetadataService.fetchEntityRailPage(
                entityKind = entityKind,
                entityId = entityId,
                mediaType = mediaType,
                railType = railType,
                language = language,
                page = page,
                genreId = genreId,
                dateFrom = windowFrom?.toString(),
                dateTo = windowTo?.toString()
            )
            if (result.failed) throw java.io.IOException("TMDB rail page $page could not be loaded")
            TmdbDeepPager.DeepPage(result.items, result.totalPages)
        }
    }

    fun closeSeeAll() {
        seeAllPager = null
        _seeAllRailKey.value = null
        seeAllFilterJob?.cancel()
        seeAllMenusJob?.cancel()
        seeAllCountsJob?.cancel()
        _seeAllFilter.value = EntitySeeAllFilterState()
    }

    private fun openRail(): com.nuvio.tv.core.tmdb.TmdbEntityRail? {
        val key = _seeAllRailKey.value ?: return null
        val data = (_uiState.value as? TmdbEntityBrowseUiState.Success)?.data ?: return null
        return data.rails.firstOrNull { "${it.mediaType.value}_${it.railType.value}" == key }
    }

    private fun TmdbEntityMediaType.toCollectionType(): com.nuvio.tv.domain.model.TmdbCollectionMediaType =
        if (this == TmdbEntityMediaType.TV) com.nuvio.tv.domain.model.TmdbCollectionMediaType.TV
        else com.nuvio.tv.domain.model.TmdbCollectionMediaType.MOVIE

    /**
     * New Genre / Year picks on the See All page ([genre] a genre's label, [year] "2024"; null is
     * "All"). With a pick, TMDB lists every match the studio, network or service has.
     */
    fun setSeeAllFilter(@Suppress("UNUSED_PARAMETER") type: String?, genre: String?, year: String?) {
        val current = _seeAllFilter.value
        val rail = openRail() ?: return
        val filter = TmdbSeeAllFilter(
            genre = genre?.let { label -> current.genres.firstOrNull { it.label == label } },
            year = year?.toIntOrNull()
        )
        if (filter == current.filter) return
        seeAllFilterJob?.cancel()
        seeAllPager = railPager(rail.mediaType, rail.railType, filter)
        _seeAllFilter.value = current.copy(
            filter = filter,
            items = if (filter.isEmpty) rail.items else rail.items.filter { it.matches(filter) },
            nextPage = 1,
            isLoading = false,
            genreCounts = if (filter.year != current.filter.year) emptyMap() else current.genreCounts
        )
        loadMoreFiltered()
        if (seeAllCountsWanted) loadSeeAllCounts()
    }

    /**
     * More titles of the See All list (filtered or not), with no limit. Titles already shown are
     * skipped; when nothing could be loaded the same titles are asked for again on the next scroll.
     */
    fun loadMoreFiltered() {
        val current = _seeAllFilter.value
        if (current.nextPage == null || current.isLoading) return
        val pager = seeAllPager ?: return
        val filter = current.filter
        _seeAllFilter.update { it.copy(isLoading = true) }
        seeAllFilterJob = viewModelScope.launch {
            val known = current.items.mapTo(HashSet()) { it.id }
            var fresh: List<com.nuvio.tv.domain.model.MetaPreview> = emptyList()
            var failed = false
            var rounds = 0
            while (!pager.isDone && rounds < SEE_ALL_ROUNDS) {
                rounds++
                val pageItems = try {
                    pager.next()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = true
                    break
                }
                fresh = pageItems.filter { known.add(it.id) }
                if (fresh.isNotEmpty()) break
            }
            val pattern = runCatching { layoutPreferenceDataStore.customPosterUrlPattern.first() }.getOrNull()
            _seeAllFilter.update { state ->
                if (state.filter != filter || seeAllPager !== pager) return@update state
                val shown = state.items.mapTo(HashSet()) { it.id }
                val added = fresh.filter { shown.add(it.id) }
                state.copy(
                    items = state.items + (if (pattern != null) added.withCustomPosterUrls(pattern) else added),
                    isLoading = false,
                    nextPage = if (pager.isDone && !failed) null else 1
                )
            }
            // A few pages up front, so the grid fills the screen.
            val after = _seeAllFilter.value
            if (!failed && after.filter == filter && after.nextPage != null && after.items.size < SEE_ALL_FILTER_PRELOAD) {
                loadMoreFiltered()
            }
        }
    }

    /**
     * The filter menu opened: TMDB's total for each genre (with the picked Year), so the menu shows how
     * many titles the whole studio, network or service has.
     */
    fun loadSeeAllCounts() {
        seeAllCountsWanted = true
        val current = _seeAllFilter.value
        val rail = openRail() ?: return
        val filter = current.filter
        val railKey = current.railKey
        seeAllCountsJob?.cancel()
        seeAllCountsJob = viewModelScope.launch {
            val genres = current.genres.ifEmpty {
                seeAllMenusJob?.join()
                _seeAllFilter.value.takeIf { it.railKey == railKey }?.genres.orEmpty()
            }
            coroutineScope {
                genres.forEach { genre ->
                    if (_seeAllFilter.value.genreCounts.containsKey(genre.label)) return@forEach
                    val genreId = TmdbSeeAllFilter(genre = genre).genreId(rail.mediaType.toCollectionType()) ?: return@forEach
                    launch {
                        val count = countLimiter.withPermit {
                            tmdbMetadataService.fetchEntityRailTotal(
                                entityKind, entityId, rail.mediaType, rail.railType, genreId, filter.year
                            )
                        } ?: return@launch
                        _seeAllFilter.update { state ->
                            if (state.railKey != railKey || state.filter != filter) return@update state
                            state.copy(genreCounts = state.genreCounts + (genre.label to count))
                        }
                    }
                }
            }
        }
    }

    /** The See All page's Genre menu (TMDB's movie or show genres) and Year menu. */
    private fun loadSeeAllMenus(mediaType: TmdbEntityMediaType, railType: TmdbEntityRailType, railKey: String) {
        seeAllMenusJob?.cancel()
        seeAllMenusJob = viewModelScope.launch {
            val genres = try {
                discoverRepository.genres(
                    if (mediaType == TmdbEntityMediaType.TV) DiscoverMediaFilter.SHOWS else DiscoverMediaFilter.MOVIES,
                    discoverRepository.language()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            _seeAllFilter.update { if (it.railKey == railKey) it.copy(genres = genres) else it }
            val earliest = try {
                tmdbMetadataService.fetchEntityEarliestYear(entityKind, entityId, mediaType, railType)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val currentYear = LocalDate.now().year
            // Recently released, or a network's shows: nothing after this year.
            val last = if (railType == TmdbEntityRailType.RECENT || entityKind == TmdbEntityKind.NETWORK) currentYear else currentYear + 1
            val first = (earliest ?: SEE_ALL_OLDEST_YEAR).coerceIn(SEE_ALL_OLDEST_YEAR, last)
            _seeAllFilter.update { if (it.railKey == railKey) it.copy(years = (last downTo first).toList()) else it }
        }
    }

    fun retry() {
        _uiState.value = TmdbEntityBrowseUiState.Loading
        load()
    }

    fun loadMoreRail(mediaType: TmdbEntityMediaType, railType: TmdbEntityRailType) {
        val railKey = "${mediaType.value}_${railType.value}"
        val currentSuccess = _uiState.value as? TmdbEntityBrowseUiState.Success ?: return
        val targetRail = currentSuccess.data.rails.firstOrNull {
            it.mediaType == mediaType && it.railType == railType
        } ?: return
        if (!targetRail.hasMore || targetRail.isLoading || !inFlightRailLoads.add(railKey)) return

        _uiState.value = TmdbEntityBrowseUiState.Success(
            currentSuccess.data.withUpdatedRail(mediaType, railType) { it.copy(isLoading = true) }
        )

        viewModelScope.launch {
            try {
                val latestData = (_uiState.value as? TmdbEntityBrowseUiState.Success)?.data ?: return@launch
                val latestRail = latestData.rails.firstOrNull {
                    it.mediaType == mediaType && it.railType == railType
                } ?: return@launch
                val language = tmdbSettingsDataStore.settings.first().language
                val nextPage = latestRail.currentPage + 1
                val pageResult = tmdbMetadataService.fetchEntityRailPage(
                    entityKind = entityKind,
                    entityId = entityId,
                    mediaType = mediaType,
                    railType = railType,
                    language = language,
                    page = nextPage
                )
                val mergedItems = (latestRail.items + pageResult.items)
                    .distinctBy { it.id }

                val pattern = layoutPreferenceDataStore.customPosterUrlPattern.first()
                _uiState.value = TmdbEntityBrowseUiState.Success(
                    latestData.withUpdatedRail(mediaType, railType) {
                        it.copy(
                            items = mergedItems.withCustomPosterUrls(pattern),
                            // A failed page keeps its place, so scrolling tries it again.
                            currentPage = if (pageResult.failed) latestRail.currentPage else nextPage,
                            hasMore = pageResult.hasMore,
                            isLoading = false
                        )
                    }
                )
            } catch (_: Exception) {
                val fallback = (_uiState.value as? TmdbEntityBrowseUiState.Success)?.data ?: return@launch
                _uiState.value = TmdbEntityBrowseUiState.Success(
                    fallback.withUpdatedRail(mediaType, railType) { it.copy(isLoading = false) }
                )
            } finally {
                inFlightRailLoads.remove(railKey)
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val language = tmdbSettingsDataStore.settings.first().language
                val browseData = tmdbMetadataService.fetchEntityBrowse(
                    entityKind = entityKind,
                    entityId = entityId,
                    sourceType = sourceType,
                    fallbackName = entityName,
                    language = language
                )
                if (browseData != null && platformLogoRes != null) {
                    // Set before the page shows, so it opens with focus on the hero.
                    _heroItems.value = hubRepository.heroPicks(heroSourceItems(browseData))
                }
                _uiState.value = if (browseData != null) {
                    val pattern = layoutPreferenceDataStore.customPosterUrlPattern.first()
                    TmdbEntityBrowseUiState.Success(browseData.withCustomPosterUrls(pattern))
                } else {
                    TmdbEntityBrowseUiState.Error(
                        if (entityName.isNotBlank()) {
                            context.getString(R.string.tmdb_entity_error_load_named, entityName)
                        } else {
                            context.getString(R.string.tmdb_entity_error_load)
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.value = TmdbEntityBrowseUiState.Error(
                    e.message ?: context.getString(R.string.tmdb_entity_error_load)
                )
            }
            val picks = _heroItems.value
            if (picks.isNotEmpty()) {
                runCatching { hubRepository.withLogos(picks) }.getOrNull()?.let { _heroItems.value = it }
            }
        }
    }

    /** Hero candidates: the platform's leading rail (series for services, films for studios), then the rest. */
    private fun heroSourceItems(data: TmdbEntityBrowseData): List<com.nuvio.tv.domain.model.MetaPreview> {
        val leadType = if (sourceType.trim().equals("movie", ignoreCase = true)) TmdbEntityMediaType.MOVIE else TmdbEntityMediaType.TV
        val (lead, rest) = data.rails.partition { it.mediaType == leadType }
        return (lead + rest).flatMap { it.items }
    }

    private fun TmdbEntityBrowseData.withUpdatedRail(
        mediaType: TmdbEntityMediaType,
        railType: TmdbEntityRailType,
        transform: (com.nuvio.tv.core.tmdb.TmdbEntityRail) -> com.nuvio.tv.core.tmdb.TmdbEntityRail
    ): TmdbEntityBrowseData {
        return copy(
            rails = rails.map { rail ->
                if (rail.mediaType == mediaType && rail.railType == railType) {
                    transform(rail)
                } else {
                    rail
                }
            }
        )
    }
}

/** The See All Year menu never goes back further than this. */
private const val SEE_ALL_OLDEST_YEAR = 1900

/** Titles loaded before the person scrolls. */
private const val SEE_ALL_FILTER_PRELOAD = 40

/** Most pages for one See All load, when the pages only repeat titles already shown. */
private const val SEE_ALL_ROUNDS = 60
