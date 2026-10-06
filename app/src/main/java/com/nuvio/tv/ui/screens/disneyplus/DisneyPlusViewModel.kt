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

data class DisneyPlusUiState(
    val hub: DisneyPlusHub = DisneyPlusHub.MAIN,
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
 * Backs both the main Disney+ page and each brand page (Disney, Pixar, Marvel, Star Wars,
 * National Geographic, Hulu) — which page it is comes from the "hub" navigation argument.
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
    private val rowLimiter = Semaphore(4)

    init {
        load(forceRefresh = false)
        if (hubSpec.rows.any { it.kind == DisneyRowKind.CONTINUE_WATCHING }) {
            observeContinueWatching()
        }
    }

    fun retry() = load(forceRefresh = true)

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
            val heroSpec = hubSpec.rows.firstOrNull { it.id == hubSpec.heroRowId }
            val heroRow = heroSpec?.let { spec -> safeResolve(spec, forceRefresh) }
            heroSpec?.let { setRow(it.id, heroRow) }
            val heroItems = repository.heroItems(heroRow)
            _uiState.update { it.copy(heroItems = heroItems, isHeroLoading = false) }

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
     * Continue Watching here only keeps titles TMDB credits to a Disney-owned studio or network
     * (Disney, Pixar, Marvel, Lucasfilm, 20th Century, Searchlight, National Geographic, Hulu, FX, ABC...).
     * Everything else (e.g. a Paramount movie) stays only in the app's main Home continue watching.
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
                    val disneyOnly = coroutineScope {
                        latestPerTitle.map { progress ->
                            async { progress.takeIf { repository.isDisneyOwned(it.contentId, it.contentType) } }
                        }.awaitAll()
                    }.filterNotNull()
                    _uiState.update { state ->
                        state.copy(continueWatching = disneyOnly.map { ContinueWatchingItem.InProgress(progress = it) })
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
