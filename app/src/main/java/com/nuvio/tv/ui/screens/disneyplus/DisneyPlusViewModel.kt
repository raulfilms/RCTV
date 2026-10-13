package com.nuvio.tv.ui.screens.disneyplus

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.disneyplus.DisneyPlusCatalog
import com.nuvio.tv.data.disneyplus.DisneyPlusHub
import com.nuvio.tv.data.disneyplus.DisneyPlusRepository
import com.nuvio.tv.data.disneyplus.DisneyRowKind
import com.nuvio.tv.data.disneyplus.DisneyRowSpec
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

private const val SEE_ALL_MAX_PAGES = 15

/** A wide collection card (e.g. "Disney Jr. — Collection"). [backdrops] fill in once loaded. */
data class DisneyCollectionTile(
    val hub: DisneyPlusHub,
    val backdrops: List<String> = emptyList()
)

/** One row slot on the page, in definition order. [row] is null while loading or when the row came back empty. */
data class DisneyPlusRowState(
    val spec: DisneyRowSpec,
    val title: String,
    val row: CatalogRow? = null,
    val isLoading: Boolean = true,
    val collectionTiles: List<DisneyCollectionTile> = emptyList()
)

/**
 * A row's See All page: everything the row has, then further TMDB pages as the person scrolls.
 * [nextPage] is null once there's nothing more to load.
 */
data class HubSeeAllState(
    val rowId: String,
    val title: String,
    val items: List<MetaPreview>,
    val isLoadingMore: Boolean = false,
    val nextPage: Int? = 1
)

data class DisneyPlusUiState(
    val hub: DisneyPlusHub = DisneyPlusHub.MAIN,
    val seeAll: HubSeeAllState? = null,
    val heroItems: List<MetaPreview> = emptyList(),
    val isHeroLoading: Boolean = true,
    val continueWatching: List<ContinueWatchingItem> = emptyList(),
    val rows: List<DisneyPlusRowState> = emptyList()
) {
    val isInitialLoading: Boolean
        get() = isHeroLoading && rows.none { it.row != null }

    val hasAnyContent: Boolean
        get() = heroItems.isNotEmpty() || continueWatching.isNotEmpty() ||
            rows.any { it.row != null || it.collectionTiles.isNotEmpty() }

    val isFinishedLoading: Boolean
        get() = !isHeroLoading && rows.none { it.isLoading }
}

/**
 * Backs the main Disney+, Paramount+, discovery+ and HBO Max pages and each of their brand pages (Disney, Pixar,
 * Marvel..., CBS, BET..., HGTV, Food Network...) — which page it is comes from the "hub" navigation argument.
 *
 * Load order: the hero's source row first (so the banner shows up fast), then every other row in
 * parallel (a few at a time), each appearing as soon as it's ready.
 */
@HiltViewModel
class DisneyPlusViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DisneyPlusRepository,
    private val watchProgressRepository: WatchProgressRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val hub: DisneyPlusHub = DisneyPlusHub.fromKey(savedStateHandle.get<String>("hub"))
    private val hubSpec = DisneyPlusCatalog.hub(hub)

    /**
     * Title art for cards that find it as they appear: the See All grid (every hub) and any row title
     * whose art wasn't found up front.
     */
    val titleArt: com.nuvio.tv.domain.repository.TitleArtResolver get() = repository.titleArtResolver

    private val _uiState = MutableStateFlow(
        DisneyPlusUiState(
            hub = hub,
            rows = hubSpec.rows
                .filter { it.kind != DisneyRowKind.CONTINUE_WATCHING }
                .map { spec ->
                    val isCollections = spec.kind == DisneyRowKind.COLLECTIONS
                    DisneyPlusRowState(
                        spec = spec,
                        title = context.getString(spec.titleRes),
                        // Collection tiles show right away with their names; artwork fills in afterwards.
                        isLoading = !isCollections,
                        collectionTiles = if (isCollections) spec.collections.map { DisneyCollectionTile(it) } else emptyList()
                    )
                }
        )
    )
    val uiState: StateFlow<DisneyPlusUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    /** Rows loading at the same time (TMDB copes fine; busy answers are retried). */
    private val rowLimiter = Semaphore(6)

    init {
        load(forceRefresh = false)
        if (hubSpec.rows.any { it.kind == DisneyRowKind.CONTINUE_WATCHING }) {
            observeContinueWatching()
        }
    }

    fun retry() = load(forceRefresh = true)

    // ------------------------------------------------------------------ See All

    private var seeAllJob: Job? = null

    /** Opens a row's See All page with what the row already has, then loads more right away. */
    fun openSeeAll(rowId: String) {
        val rowState = _uiState.value.rows.firstOrNull { it.spec.id == rowId } ?: return
        val items = rowState.row?.items.orEmpty()
        val hasMoreSources = rowState.spec.sources.any { it is com.nuvio.tv.data.disneyplus.DisneyRowSource.Discover }
        _uiState.update {
            it.copy(
                seeAll = HubSeeAllState(
                    rowId = rowId,
                    title = rowState.title,
                    items = items,
                    nextPage = if (hasMoreSources) 1 else null
                )
            )
        }
        loadMoreSeeAll()
    }

    fun closeSeeAll() {
        seeAllJob?.cancel()
        _uiState.update { it.copy(seeAll = null) }
    }

    /**
     * Next TMDB page(s) of the open See All row. A page that only repeats what's already shown (the row
     * itself came from page 1) is skipped; loading stops when TMDB has nothing more (or after 15 pages).
     */
    fun loadMoreSeeAll() {
        val current = _uiState.value.seeAll ?: return
        val firstPage = current.nextPage ?: return
        if (current.isLoadingMore) return
        val spec = _uiState.value.rows.firstOrNull { it.spec.id == current.rowId }?.spec ?: return
        _uiState.update { state -> state.copy(seeAll = state.seeAll?.copy(isLoadingMore = true)) }
        seeAllJob = viewModelScope.launch {
            val known = current.items.mapTo(HashSet()) { "${it.apiType}:${it.id}" }
            var page = firstPage
            var fresh: List<MetaPreview> = emptyList()
            var nextPage: Int? = null
            while (page <= SEE_ALL_MAX_PAGES) {
                val pageItems = try {
                    repository.resolveRowPage(spec, page)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                if (pageItems.isEmpty()) break
                fresh = pageItems.filter { known.add("${it.apiType}:${it.id}") }
                if (page < SEE_ALL_MAX_PAGES) nextPage = page + 1
                if (fresh.isNotEmpty()) break
                page++
                nextPage = null
            }
            _uiState.update { state ->
                val open = state.seeAll?.takeIf { it.rowId == current.rowId } ?: return@update state
                state.copy(
                    seeAll = open.copy(
                        items = open.items + fresh,
                        isLoadingMore = false,
                        nextPage = if (fresh.isEmpty()) null else nextPage
                    )
                )
            }
        }
    }

    private fun load(forceRefresh: Boolean) {
        loadJob?.cancel()
        _uiState.update { state ->
            state.copy(
                isHeroLoading = true,
                rows = state.rows.map {
                    it.copy(
                        isLoading = it.spec.kind != DisneyRowKind.COLLECTIONS,
                        row = if (forceRefresh) null else it.row
                    )
                }
            )
        }
        loadJob = viewModelScope.launch {
            launch { loadCollectionArt() }
            // The banner and every row load at the same time: nothing waits for the banner's logos.
            launch {
                val heroSpec = hubSpec.rows.firstOrNull { it.id == hubSpec.heroRowId }
                val heroRow = heroSpec?.let { spec -> safeResolve(spec, forceRefresh) }
                heroSpec?.let { setRow(it.id, heroRow) }
                val heroItems = try {
                    repository.heroItems(heroRow)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    repository.heroPicks(heroRow?.items.orEmpty())
                }
                _uiState.update { it.copy(heroItems = heroItems, isHeroLoading = false) }
            }

            coroutineScope {
                hubSpec.rows
                    .filter { it.kind == DisneyRowKind.CATALOG && it.id != hubSpec.heroRowId }
                    .map { spec ->
                        async {
                            rowLimiter.withPermit {
                                setRow(spec.id, safeResolve(spec, forceRefresh))
                            }
                        }
                    }
                    .plus(
                        hubSpec.rows.filter { it.kind == DisneyRowKind.RECOMMENDED }.map { spec ->
                            async { loadRecommended(spec) }
                        }
                    )
                    .awaitAll()
            }
        }
    }

    private suspend fun loadCollectionArt() {
        val hubs = hubSpec.rows.filter { it.kind == DisneyRowKind.COLLECTIONS }.flatMap { it.collections }.distinct()
        if (hubs.isEmpty()) return
        coroutineScope {
            hubs.map { collectionHub ->
                async {
                    val art = try {
                        repository.collectionArt(collectionHub)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                    if (art.isNotEmpty()) {
                        _uiState.update { state ->
                            state.copy(rows = state.rows.map { rowState ->
                                if (rowState.collectionTiles.none { it.hub == collectionHub }) rowState
                                else rowState.copy(collectionTiles = rowState.collectionTiles.map { tile ->
                                    if (tile.hub == collectionHub) tile.copy(backdrops = art) else tile
                                })
                            })
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /** A failed row just stays hidden; it never leaves the page stuck in a loading state. */
    private suspend fun safeResolve(spec: DisneyRowSpec, forceRefresh: Boolean): CatalogRow? = try {
        repository.resolveRow(hub, spec, context.getString(spec.titleRes), forceRefresh)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun loadRecommended(spec: DisneyRowSpec) {
        val history = try {
            watchProgressRepository.allProgress.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val heroIds = _uiState.value.heroItems.map { it.id }.toSet()
        val row = try {
            repository.resolveRecommendedRow(hub, context.getString(spec.titleRes), history, heroIds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        setRow(spec.id, row)
    }

    private fun setRow(rowId: String, row: CatalogRow?) {
        _uiState.update { state ->
            state.copy(rows = state.rows.map { if (it.spec.id == rowId) it.copy(row = row, isLoading = false) else it })
        }
    }

    /**
     * Continue Watching here only keeps titles TMDB credits to a studio or network the page's service owns
     * (Disney+: Disney, Pixar, Marvel, Lucasfilm, 20th Century, National Geographic, Hulu, FX, ABC...;
     * Paramount+: Paramount, CBS, Showtime, Nickelodeon, MTV, Comedy Central, BET, Smithsonian...;
     * discovery+: Discovery, HGTV, Food Network, TLC, ID, OWN, Animal Planet, Magnolia...;
     * HBO Max: HBO, Warner Bros., DC, Cartoon Network, Adult Swim...).
     * Everything else stays only in the app's main Home continue watching.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeContinueWatching() {
        viewModelScope.launch {
            watchProgressRepository.continueWatching
                .debounce(300)
                .collectLatest { progressList ->
                    val latestPerTitle = progressList
                        .filter { it.isInProgress() }
                        .sortedByDescending { it.lastWatched }
                        .distinctBy { it.contentId }
                        .take(40)
                    val ownedOnly = coroutineScope {
                        latestPerTitle.map { progress ->
                            async { progress.takeIf { repository.isOwnedBy(hub.service, it.contentId, it.contentType) } }
                        }.awaitAll()
                    }.filterNotNull()
                    _uiState.update { state ->
                        state.copy(continueWatching = ownedOnly.map { ContinueWatchingItem.InProgress(progress = it) })
                    }
                }
        }
    }

    fun removeContinueWatching(item: ContinueWatchingItem) {
        val contentId = when (item) {
            is ContinueWatchingItem.InProgress -> item.progress.contentId
            is ContinueWatchingItem.NextUp -> item.info.contentId
        }
        _uiState.update { state ->
            state.copy(continueWatching = state.continueWatching.filterNot { existing ->
                when (existing) {
                    is ContinueWatchingItem.InProgress -> existing.progress.contentId == contentId
                    is ContinueWatchingItem.NextUp -> existing.info.contentId == contentId
                }
            })
        }
        viewModelScope.launch {
            runCatching { watchProgressRepository.removeProgress(contentId = contentId, season = null, episode = null) }
        }
    }
}
