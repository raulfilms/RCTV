package com.nuvio.tv.ui.screens.disneyplus

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.discover.DiscoverGenre
import com.nuvio.tv.data.discover.DiscoverMediaFilter
import com.nuvio.tv.data.discover.TmdbDiscoverRepository
import com.nuvio.tv.data.discover.TmdbSeeAllFilter
import com.nuvio.tv.data.discover.matches
import com.nuvio.tv.data.disneyplus.DisneyPlusCatalog
import com.nuvio.tv.data.disneyplus.DisneyPlusHub
import com.nuvio.tv.data.disneyplus.DisneyPlusRepository
import com.nuvio.tv.data.disneyplus.DisneyRowKind
import com.nuvio.tv.data.disneyplus.DisneyRowSource
import com.nuvio.tv.data.disneyplus.DisneyRowSpec
import com.nuvio.tv.data.disneyplus.HubCardArt
import com.nuvio.tv.data.disneyplus.withPremiereBadges
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
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
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Most rounds of pages for one See All load, when the pages only repeat titles already shown. */
private const val SEE_ALL_ROUNDS = 60

/** How long a page waits for its banner's title logos before opening without them (they follow). */
private const val HERO_LOGO_WAIT_MS = 1_200L

/** Release rows label as "NEW" what came out this many days ago or less. */
private const val NEW_BADGE_DAYS = 14L

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
    val collectionTiles: List<DisneyCollectionTile> = emptyList(),
    /** A row of cards: each card's picture (collection, filmmaker, channel logo) by the list it opens. */
    val cardArt: Map<String, String> = emptyMap()
)

/** Rows that load titles from TMDB (the others are cards, or lists opened from a card). */
private val DisneyRowSpec.loadsTitles: Boolean
    get() = kind == DisneyRowKind.CATALOG || kind == DisneyRowKind.RECOMMENDED

/**
 * A row's See All page: everything the row has, then further TMDB pages as the person scrolls.
 * [nextPage] is null once there's nothing more to load.
 */
data class HubSeeAllState(
    val rowId: String,
    val title: String,
    val items: List<MetaPreview>,
    val isLoadingMore: Boolean = false,
    val nextPage: Int? = 1,
    /** Type / Genre / Year, applied by TMDB to the row's whole catalog. */
    val filter: TmdbSeeAllFilter = TmdbSeeAllFilter(),
    /** Movies, shows or both: the Type menu only shows when there are both. */
    val mediaTypes: List<TmdbCollectionMediaType> = emptyList(),
    val genres: List<DiscoverGenre> = emptyList(),
    /** Newest first. */
    val years: List<Int> = emptyList(),
    /** TMDB's totals by genre label (for the picked Type and Year), filled in once the menu opens. */
    val genreCounts: Map<String, Int> = emptyMap(),
    /** TMDB's totals by kind of title (for the picked Genre and Year). */
    val typeCounts: Map<TmdbCollectionMediaType, Int> = emptyMap()
)

data class DisneyPlusUiState(
    val hub: DisneyPlusHub = DisneyPlusHub.MAIN,
    val seeAll: HubSeeAllState? = null,
    val heroItems: List<MetaPreview> = emptyList(),
    val isHeroLoading: Boolean = true,
    val continueWatching: List<ContinueWatchingItem> = emptyList(),
    val rows: List<DisneyPlusRowState> = emptyList()
) {
    /**
     * The page shows once its top (the hero) is ready, so it always opens at the top with focus on
     * the hero, instead of showing the rows first and then scrolling back up when the hero arrives.
     * A page whose hero can't load still shows its rows.
     */
    val isInitialLoading: Boolean
        get() = isHeroLoading

    val hasAnyContent: Boolean
        get() = heroItems.isNotEmpty() || continueWatching.isNotEmpty() ||
            rows.any { it.row != null || it.collectionTiles.isNotEmpty() || it.spec.kind == DisneyRowKind.CARDS }

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
    private val discoverRepository: TmdbDiscoverRepository,
    private val watchProgressRepository: WatchProgressRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val hub: DisneyPlusHub = DisneyPlusHub.fromKey(savedStateHandle.get<String>("hub"))
    private val hubSpec = DisneyPlusCatalog.hub(hub)

    /** The row whose See All the page opened straight on (Home's rows); Back then closes the page. */
    private val directSeeAllRow: String? = savedStateHandle.get<String>("seeAll")?.takeIf { it.isNotBlank() }
    val isDirectSeeAll: Boolean get() = directSeeAllRow != null

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
                        title = spec.title ?: context.getString(spec.titleRes),
                        // Collection tiles and cards show right away with their names; artwork fills in
                        // afterwards. Lists opened from a card load when opened.
                        isLoading = spec.loadsTitles,
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
        val seeAllRow = directSeeAllRow
        if (seeAllRow != null) {
            // Just the row's See All page: nothing else of the page loads.
            _uiState.update { it.copy(isHeroLoading = false) }
            openSeeAll(seeAllRow)
        } else {
            load(forceRefresh = false)
        }
        if (seeAllRow == null && hubSpec.rows.any { it.kind == DisneyRowKind.CONTINUE_WATCHING }) {
            observeContinueWatching()
        }
    }

    fun retry() = load(forceRefresh = true)

    // ------------------------------------------------------------------ See All

    private var seeAllJob: Job? = null
    /** Genre and Year menus of the open See All page. */
    private var seeAllGenresJob: Job? = null
    private var seeAllYearsJob: Job? = null
    private var seeAllCountsJob: Job? = null
    /** The filter menu was opened on this See All page, so its counts are kept up to date. */
    private var seeAllCountsWanted = false
    private val countLimiter = Semaphore(6)

    /** Opens a row's See All page with what the row already has, then loads more right away. */
    fun openSeeAll(rowId: String) {
        val rowState = _uiState.value.rows.firstOrNull { it.spec.id == rowId } ?: return
        val items = rowState.row?.items.orEmpty()
        // Every row has more past what it shows: its TMDB queries, its complete catalog, or (for
        // hand-picked titles) the whole TMDB franchise collections those movies belong to.
        val hasMoreSources = rowState.spec.seeAll.isNotEmpty() || rowState.spec.sources.isNotEmpty()
        val mediaTypes = rowState.spec.mediaTypes(items)
        seeAllJob?.cancel()
        seeAllCountsJob?.cancel()
        seeAllCountsWanted = false
        _uiState.update {
            it.copy(
                seeAll = HubSeeAllState(
                    rowId = rowId,
                    title = rowState.title,
                    items = items,
                    nextPage = if (hasMoreSources) 1 else null,
                    mediaTypes = mediaTypes
                )
            )
        }
        seeAllSession = repository.seeAllSession(rowState.spec)
        loadSeeAllMenus(rowState.spec, mediaTypes)
        loadMoreSeeAll()
    }

    fun closeSeeAll() {
        seeAllSession = null
        seeAllJob?.cancel()
        seeAllGenresJob?.cancel()
        seeAllYearsJob?.cancel()
        seeAllCountsJob?.cancel()
        _uiState.update { it.copy(seeAll = null) }
    }

    /**
     * New Type / Genre / Year picks on the See All page ([type] "movie" or "series", [genre] a genre's
     * label, [year] "2024"; null is "All"). The list starts over with the row's own titles that match,
     * then TMDB's whole catalog for those picks.
     */
    fun setSeeAllFilter(type: String?, genre: String?, year: String?) {
        val current = _uiState.value.seeAll ?: return
        val filter = TmdbSeeAllFilter(
            mediaType = when (type) {
                "movie" -> TmdbCollectionMediaType.MOVIE
                "series", "tv" -> TmdbCollectionMediaType.TV
                else -> null
            },
            genre = genre?.let { label -> current.genres.firstOrNull { it.label == label } },
            year = year?.toIntOrNull()
        )
        if (filter == current.filter) return
        val rowState = _uiState.value.rows.firstOrNull { it.spec.id == current.rowId } ?: return
        val rowItems = rowState.row?.items.orEmpty()
        val hasMoreSources = rowState.spec.seeAll.isNotEmpty() || rowState.spec.sources.isNotEmpty()
        val old = current.filter
        seeAllJob?.cancel()
        _uiState.update { state ->
            val open = state.seeAll ?: return@update state
            state.copy(
                seeAll = open.copy(
                    filter = filter,
                    items = if (filter.isEmpty) rowItems else rowItems.filter { it.matches(filter) },
                    isLoadingMore = false,
                    nextPage = if (hasMoreSources) 1 else null,
                    // Counts by genre depend on the Type and Year; counts by Type on the Genre and Year.
                    genreCounts = if (old.mediaType != filter.mediaType || old.year != filter.year) emptyMap() else open.genreCounts,
                    typeCounts = if (old.genre != filter.genre || old.year != filter.year) emptyMap() else open.typeCounts
                )
            )
        }
        seeAllSession = repository.seeAllSession(rowState.spec, filter)
        loadMoreSeeAll()
        if (seeAllCountsWanted) loadSeeAllCounts()
    }

    /**
     * The filter menu opened: TMDB's totals for each genre and type, given the other picks, so the
     * menu shows how many titles the whole catalog has (not just what's loaded). Asked only once the
     * menu is opened (one TMDB query per genre), then kept up to date as the picks change.
     */
    fun loadSeeAllCounts() {
        seeAllCountsWanted = true
        val current = _uiState.value.seeAll ?: return
        val spec = _uiState.value.rows.firstOrNull { it.spec.id == current.rowId }?.spec ?: return
        val filter = current.filter
        seeAllCountsJob?.cancel()
        seeAllCountsJob = viewModelScope.launch {
            // The Genre menu may still be loading: wait for it.
            val genres = current.genres.ifEmpty {
                seeAllGenresJob?.join()
                _uiState.value.seeAll?.takeIf { it.rowId == current.rowId }?.genres.orEmpty()
            }
            fun stillOpen(open: HubSeeAllState?) = open != null && open.rowId == current.rowId && open.filter == filter
            coroutineScope {
                if (current.mediaTypes.size > 1) {
                    current.mediaTypes.forEach { mediaType ->
                        if (_uiState.value.seeAll?.typeCounts?.containsKey(mediaType) == true) return@forEach
                        launch {
                            val count = countLimiter.withPermit {
                                safeCount(spec, filter.copy(mediaType = mediaType))
                            } ?: return@launch
                            _uiState.update { state ->
                                val open = state.seeAll?.takeIf { stillOpen(it) } ?: return@update state
                                state.copy(seeAll = open.copy(typeCounts = open.typeCounts + (mediaType to count)))
                            }
                        }
                    }
                }
                genres.forEach { genre ->
                    if (_uiState.value.seeAll?.genreCounts?.containsKey(genre.label) == true) return@forEach
                    launch {
                        val count = countLimiter.withPermit {
                            safeCount(spec, filter.copy(genre = genre))
                        } ?: return@launch
                        _uiState.update { state ->
                            val open = state.seeAll?.takeIf { stillOpen(it) } ?: return@update state
                            state.copy(seeAll = open.copy(genreCounts = open.genreCounts + (genre.label to count)))
                        }
                    }
                }
            }
        }
    }

    private suspend fun safeCount(spec: DisneyRowSpec, filter: TmdbSeeAllFilter): Int? = try {
        repository.countForFilter(spec, filter)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** The See All page's Genre menu (TMDB's genres for the row's movies and/or shows) and Year menu. */
    private fun loadSeeAllMenus(spec: DisneyRowSpec, mediaTypes: List<TmdbCollectionMediaType>) {
        seeAllGenresJob?.cancel()
        seeAllYearsJob?.cancel()
        val rowId = spec.id
        seeAllGenresJob = viewModelScope.launch {
            val mediaFilter = when {
                mediaTypes.size > 1 || mediaTypes.isEmpty() -> DiscoverMediaFilter.ALL
                mediaTypes.first() == TmdbCollectionMediaType.MOVIE -> DiscoverMediaFilter.MOVIES
                else -> DiscoverMediaFilter.SHOWS
            }
            val genres = try {
                discoverRepository.genres(mediaFilter, discoverRepository.language())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            _uiState.update { state ->
                val open = state.seeAll?.takeIf { it.rowId == rowId } ?: return@update state
                state.copy(seeAll = open.copy(genres = genres))
            }
        }
        seeAllYearsJob = viewModelScope.launch {
            val years = try {
                repository.seeAllYears(spec)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            _uiState.update { state ->
                val open = state.seeAll?.takeIf { it.rowId == rowId } ?: return@update state
                state.copy(seeAll = open.copy(years = years))
            }
        }
    }

    /** Movies, shows or both: what the row's sources and titles are. */
    private fun DisneyRowSpec.mediaTypes(items: List<MetaPreview>): List<TmdbCollectionMediaType> {
        val found = HashSet<TmdbCollectionMediaType>()
        (sources + seeAll).forEach { source ->
            when (source) {
                is DisneyRowSource.Discover -> found += source.mediaType
                is DisneyRowSource.Curated -> source.titles.forEach { found += it.mediaType }
                is DisneyRowSource.TmdbCollection -> found += TmdbCollectionMediaType.MOVIE
                is DisneyRowSource.Trending -> source.mediaType?.let { found += it }
                    ?: found.addAll(listOf(TmdbCollectionMediaType.MOVIE, TmdbCollectionMediaType.TV))
            }
        }
        if (items.any { it.type == com.nuvio.tv.domain.model.ContentType.SERIES }) found += TmdbCollectionMediaType.TV
        if (items.any { it.type != com.nuvio.tv.domain.model.ContentType.SERIES }) found += TmdbCollectionMediaType.MOVIE
        return listOf(TmdbCollectionMediaType.MOVIE, TmdbCollectionMediaType.TV).filter { it in found }
    }

    /** The open See All page's list being loaded (the row, its picks), with no limit. */
    private var seeAllSession: DisneyPlusRepository.HubSeeAllSession? = null

    /**
     * More titles of the open See All row, for its Type / Genre / Year picks, with no limit: every
     * title TMDB has for the row. Titles already shown (the row's own) are skipped; loading stops only
     * when there's nothing more. When nothing could be loaded, the same titles are asked for again on
     * the next scroll instead of ending the list.
     */
    fun loadMoreSeeAll() {
        val current = _uiState.value.seeAll ?: return
        if (current.nextPage == null || current.isLoadingMore) return
        val session = seeAllSession ?: return
        val filter = current.filter
        _uiState.update { state -> state.copy(seeAll = state.seeAll?.copy(isLoadingMore = true)) }
        seeAllJob = viewModelScope.launch {
            val known = current.items.mapTo(HashSet()) { "${it.apiType}:${it.id}" }
            var fresh: List<MetaPreview> = emptyList()
            var failed = false
            var rounds = 0
            // A round can bring only titles already shown; keep going until something new comes.
            while (!session.isDone && rounds < SEE_ALL_ROUNDS) {
                rounds++
                val pageItems = try {
                    session.next()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = true
                    break
                }
                fresh = pageItems.filter { known.add("${it.apiType}:${it.id}") }
                if (fresh.isNotEmpty()) break
            }
            _uiState.update { state ->
                val open = state.seeAll?.takeIf { it.rowId == current.rowId && it.filter == filter } ?: return@update state
                state.copy(
                    seeAll = open.copy(
                        items = open.items + fresh,
                        isLoadingMore = false,
                        nextPage = if (session.isDone && !failed) null else 1
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
                        isLoading = it.spec.loadsTitles,
                        row = if (forceRefresh) null else it.row
                    )
                }
            )
        }
        loadJob = viewModelScope.launch {
            launch { loadCollectionArt() }
            launch { loadCardArt() }
            // The banner and every row load at the same time. The page opens once the banner is
            // there; it waits a moment for the titles' logos, never longer (they're added after).
            launch {
                val heroSpec = hubSpec.rows.firstOrNull { it.id == hubSpec.heroRowId }
                val heroRow = heroSpec?.let { spec -> safeResolve(spec, forceRefresh)?.let { badged(spec, it) } }
                heroSpec?.let { setRow(it.id, heroRow) }
                val picks = repository.heroPicks(heroRow?.items.orEmpty())
                val withLogos = safeLogos { withTimeoutOrNull(HERO_LOGO_WAIT_MS) { repository.withLogos(picks) } }
                _uiState.update { it.copy(heroItems = withLogos ?: picks, isHeroLoading = false) }
                if (withLogos == null && picks.isNotEmpty()) {
                    safeLogos { repository.withLogos(picks) }?.let { late ->
                        _uiState.update { state -> if (state.heroItems == picks) state.copy(heroItems = late) else state }
                    }
                }
            }

            coroutineScope {
                hubSpec.rows
                    .filter { it.kind == DisneyRowKind.CATALOG && it.id != hubSpec.heroRowId }
                    .map { spec ->
                        async {
                            rowLimiter.withPermit {
                                setRow(spec.id, safeResolve(spec, forceRefresh)?.let { badged(spec, it) })
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
        // Tiles with their own bundled picture don't need backdrops from TMDB (saves dozens of
        // lookups on pages with big collection rows, like HBO Max's).
        val hubs = hubSpec.rows.filter { it.kind == DisneyRowKind.COLLECTIONS }.flatMap { it.collections }.distinct()
            .filter { it.collectionArtRes() == null }
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

    /** Release rows: "NEW" on what came out these last two weeks, "COMING SOON" on what's to come. */
    private fun badged(spec: DisneyRowSpec, row: CatalogRow): CatalogRow =
        if (!spec.premiereBadges) row
        else row.withPremiereBadges(
            newLabel = context.getString(com.nuvio.tv.R.string.home_badge_new),
            premiereLabel = null,
            comingLabel = context.getString(com.nuvio.tv.R.string.home_badge_coming_soon),
            newDays = NEW_BADGE_DAYS
        )

    /** The pictures of the card rows: collections' backdrops, filmmakers' photos, channels' logos. */
    private suspend fun loadCardArt() {
        val cards = hubSpec.rows.filter { it.kind == DisneyRowKind.CARDS }.flatMap { spec -> spec.cards.map { spec.id to it } }
        if (cards.isEmpty()) return
        val limiter = Semaphore(4)
        coroutineScope {
            cards.forEach { (rowId, card) ->
                if (card.art == HubCardArt.Tile) return@forEach
                launch {
                    val image = limiter.withPermit {
                        try {
                            when (val art = card.art) {
                                is HubCardArt.Collection -> repository.franchiseArt(art.query)?.second
                                is HubCardArt.Person -> repository.personPhoto(art.personId)
                                is HubCardArt.Provider -> repository.providerLogo(art.providerId)
                                HubCardArt.Tile -> null
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    } ?: return@launch
                    _uiState.update { state ->
                        state.copy(rows = state.rows.map { rowState ->
                            if (rowState.spec.id != rowId) rowState
                            else rowState.copy(cardArt = rowState.cardArt + (card.rowId to image))
                        })
                    }
                }
            }
        }
    }

    private suspend fun safeLogos(block: suspend () -> List<MetaPreview>?): List<MetaPreview>? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
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
