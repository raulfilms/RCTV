package com.nuvio.tv.data.disneyplus

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tmdb.TmdbTitleArtService
import com.nuvio.tv.data.discover.TmdbDeepPager
import com.nuvio.tv.data.discover.TmdbSeeAllFilter
import com.nuvio.tv.data.discover.matches
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDetailsResponse
import com.nuvio.tv.data.remote.api.TmdbDiscoverResponse
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.data.remote.api.TmdbWatchProviderEntry
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.TitleArtResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DisneyPlusRepository"
private const val ROW_ITEM_CAP = 30
private const val HERO_ITEM_COUNT = 6
/** The See All Year menu never goes back further than this. */
private const val OLDEST_YEAR = 1900


/** What we learned about one watched title from TMDB: which services own it (Disney, Paramount, Discovery, HBO Max), and its genres. */
data class DisneyTitleInfo(
    val tmdbId: Int,
    val mediaType: TmdbCollectionMediaType,
    val owners: Set<HubService>,
    val genreIds: List<Int>
)

/**
 * Builds the rows of the app's own streaming sections (Disney+, Paramount+, discovery+, HBO Max) from TMDB. Everything is cached in memory for the life of the app
 * process (curated title lookups, keyword ids, per-title ownership checks), so moving between the
 * main page and the brand pages, or coming back to them, doesn't re-run the same lookups.
 */
@Singleton
class DisneyPlusRepository @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val titleArt: TmdbTitleArtService
) {
    private val apiKey get() = BuildConfig.TMDB_API_KEY

    private val curatedCache = ConcurrentHashMap<String, Optional<MetaPreview>>()
    private val keywordCache = ConcurrentHashMap<String, Optional<Int>>()
    private val companyCache = ConcurrentHashMap<String, Optional<Int>>()
    private val networkCache = ConcurrentHashMap<String, Optional<Int>>()
    private val providerCache = ConcurrentHashMap<String, Int>()
    private val providerLookupMutex = Mutex()
    private val networkLookupMutex = Mutex()
    private val titleInfoCache = ConcurrentHashMap<String, Optional<DisneyTitleInfo>>()
    private val rowCache = ConcurrentHashMap<String, List<MetaPreview>>()
    private val genreNameCache = ConcurrentHashMap<String, Map<Int, String>>()
    /** A movie's TMDB franchise collection id (null: none), by TMDB movie id. */
    private val movieCollectionCache = ConcurrentHashMap<Int, Optional<Int>>()
    /** A franchise collection's movies, by collection id and language. */
    private val collectionPartsCache = ConcurrentHashMap<String, List<MetaPreview>>()
    /** A franchise collection name ("Toy Story") to its TMDB collection id. */
    private val collectionSearchCache = ConcurrentHashMap<String, Optional<Int>>()
    /** TMDB's total for one (filtered) discover query: the See All filter's counts. */
    private val totalCache = ConcurrentHashMap<String, Int>()
    /** The year of a discover query's oldest title (0: it has none), for the See All Year menu. */
    private val earliestYearCache = ConcurrentHashMap<String, Int>()

    /** Tiny nullable holder so "looked it up, found nothing" can be cached (ConcurrentHashMap rejects nulls). */
    private data class Optional<T>(val value: T?)

    private val lookupLimiter = Semaphore(6)
    /** The See All filter's counts (one TMDB query per genre), kept from crowding out the titles. */
    private val countLimiter = Semaphore(4)

    private suspend fun language(): String =
        runCatching { tmdbSettingsDataStore.settings.first().language }.getOrNull()?.ifBlank { null } ?: "en"

    // ------------------------------------------------------------------ rows

    /**
     * Resolves one catalog row: runs every source in parallel, then interleaves their results
     * (first of each, then second of each...) so a mixed movie/series row doesn't show all movies first.
     */
    suspend fun resolveRow(
        hub: DisneyPlusHub,
        spec: DisneyRowSpec,
        title: String,
        forceRefresh: Boolean = false
    ): CatalogRow? =
        withContext(Dispatchers.IO) {
            val cacheKey = "${hub.key}:${spec.id}"
            val cached = if (forceRefresh) null else rowCache[cacheKey]
            val items = cached ?: run {
                val language = language()
                val perSource = coroutineScope {
                    spec.sources.map { source ->
                        async {
                            try {
                                when (source) {
                                    is DisneyRowSource.Discover -> discover(source, language)
                                    is DisneyRowSource.Curated -> curated(source, language)
                                    is DisneyRowSource.TmdbCollection -> tmdbCollection(source.query, language)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "Row ${spec.id}: source failed", e)
                                emptyList()
                            }
                        }
                    }.awaitAll()
                }
                // A row made only of hand-picked titles (a saga, a timeline...) keeps all of them.
                val cap = if (spec.sources.all { it is DisneyRowSource.Curated }) Int.MAX_VALUE else ROW_ITEM_CAP
                interleave(perSource, cap).also { if (it.isNotEmpty()) rowCache[cacheKey] = it }
            }
            // The row shows as soon as its titles are known; each horizontal card finds its title art
            // as it appears on screen (LocalTitleArtResolver), so nothing waits on those lookups.
            if (items.isEmpty()) null else buildRow("disney_${hub.key}_${spec.id}", title, items)
        }

    /** The service's titles in the genres the person watches most. Falls back to its best-rated titles. */
    suspend fun resolveRecommendedRow(
        hub: DisneyPlusHub,
        title: String,
        history: List<WatchProgress>,
        excludeIds: Set<String>
    ): CatalogRow? = withContext(Dispatchers.IO) {
        val language = language()
        val recent = history
            .distinctBy { it.contentId }
            .sortedByDescending { it.lastWatched }
            .take(15)
        val infos = coroutineScope {
            recent.map { progress -> async { titleInfo(progress.contentId, progress.contentType) } }.awaitAll()
        }.filterNotNull()

        val movieGenres = topGenres(infos.filter { it.mediaType == TmdbCollectionMediaType.MOVIE })
        val tvGenres = topGenres(infos.filter { it.mediaType == TmdbCollectionMediaType.TV })
        val watchedTmdbIds = infos.map { "tmdb:${it.tmdbId}" }.toSet()
        val providers = hub.service.providers

        val sources = buildList {
            if (movieGenres.isNotEmpty() || tvGenres.isNotEmpty()) {
                if (movieGenres.isNotEmpty()) {
                    add(DisneyRowSource.Discover(TmdbCollectionMediaType.MOVIE, providers = providers, genres = movieGenres.joinToString("|"), voteCountGte = 150))
                }
                if (tvGenres.isNotEmpty()) {
                    add(DisneyRowSource.Discover(TmdbCollectionMediaType.TV, providers = providers, genres = tvGenres.joinToString("|"), voteCountGte = 50))
                }
            } else {
                add(DisneyRowSource.Discover(TmdbCollectionMediaType.MOVIE, sortBy = "vote_average.desc", providers = providers, voteCountGte = 3000))
                add(DisneyRowSource.Discover(TmdbCollectionMediaType.TV, sortBy = "vote_average.desc", providers = providers, voteCountGte = 800))
            }
        }
        val perSource = coroutineScope {
            sources.map { source ->
                async {
                    runCatching { discover(source, language) }
                        .onFailure { if (it is CancellationException) throw it }
                        .getOrDefault(emptyList())
                }
            }.awaitAll()
        }
        val items = interleave(perSource)
            .filterNot { it.id in watchedTmdbIds || it.id in excludeIds }
        if (items.isEmpty()) null else buildRow("disney_${hub.key}_recommended", title, items)
    }

    /**
     * A row's See All list, with no limit: [HubSeeAllSession.next] brings the next titles of every
     * TMDB query in the row and its complete catalog, interleaved like the row itself; the first
     * call also brings the row's hand-picked titles and whole TMDB franchise collections. Each query
     * goes past TMDB's 10,000-title cap (see [TmdbDeepPager]), so the list has every title there is.
     *
     * [filter] (the See All page's Type / Genre / Year) is applied by TMDB to every query, so it finds
     * every matching title in the catalog, not just among the ones already loaded.
     */
    fun seeAllSession(spec: DisneyRowSpec, filter: TmdbSeeAllFilter = TmdbSeeAllFilter()): HubSeeAllSession =
        HubSeeAllSession(spec, filter)

    inner class HubSeeAllSession internal constructor(
        private val spec: DisneyRowSpec,
        private val filter: TmdbSeeAllFilter
    ) {
        private var sessionLanguage: String? = null
        private var pagers: List<TmdbDeepPager<MetaPreview>>? = null
        private var localPending = true

        /** True once every title of the row's catalog has been listed. */
        val isDone: Boolean get() = !localPending && pagers?.all { it.isDone } == true

        /**
         * The next titles (one more page of each query). Throws when nothing could be loaded (no
         * connection), so the same titles are asked for again next time; nothing is skipped.
         */
        suspend fun next(): List<MetaPreview> = withContext(Dispatchers.IO) {
            val language = sessionLanguage ?: language().also { sessionLanguage = it }
            val allPagers = pagers ?: seeAllDiscoverSources(spec, filter).map { deepPager(it, language) }.also { pagers = it }
            val active = allPagers.filterNot { it.isDone }
            val localJobs = if (localPending) localJobs(spec, language) else emptyList()
            val jobCount = active.size + localJobs.size
            if (jobCount == 0) {
                localPending = false
                return@withContext emptyList()
            }
            var failures = 0
            var localFailures = 0
            val perSource = coroutineScope {
                val discovered = active.map { pager ->
                    async {
                        try {
                            pager.next()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Row ${spec.id}: page failed", e)
                            synchronized(this@DisneyPlusRepository) { failures++ }
                            emptyList()
                        }
                    }
                }
                val local = localJobs.map { job ->
                    async {
                        try {
                            // TMDB filtered the discover queries; these are filtered here.
                            job().filter { it.matches(filter) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Row ${spec.id}: franchise lookup failed", e)
                            synchronized(this@DisneyPlusRepository) { failures++; localFailures++ }
                            emptyList()
                        }
                    }
                }
                // The hand-picked and franchise titles first (they're the closest to the row), then the wider catalog.
                local.awaitAll() + discovered.awaitAll()
            }
            // Nothing could be loaded (no connection): say so, so See All asks again on the next
            // scroll instead of treating it as the end of the list.
            if (failures == jobCount) throw java.io.IOException("Row ${spec.id}: nothing could be loaded")
            if (localFailures == 0) localPending = false
            interleave(perSource, cap = Int.MAX_VALUE)
        }
    }

    /** One discover query of a row, paged with no limit, a stretch of time at a time past 10,000. */
    private fun deepPager(source: DisneyRowSource.Discover, language: String): TmdbDeepPager<MetaPreview> {
        val today = LocalDate.now()
        fun date(value: String?): LocalDate? = when (value) {
            null -> null
            DisneyPlusCatalog.TODAY -> today
            else -> runCatching { LocalDate.parse(value) }.getOrNull()
        }
        val order = when {
            source.sortBy.endsWith("date.desc") -> TmdbDeepPager.DateOrder.NEWEST_FIRST
            source.sortBy.endsWith("date.asc") -> TmdbDeepPager.DateOrder.OLDEST_FIRST
            else -> TmdbDeepPager.DateOrder.NONE
        }
        return TmdbDeepPager(
            from = date(source.releaseDateGte),
            to = date(source.releaseDateLte),
            dateOrder = order,
            idOf = { "${it.apiType}:${it.id}" }
        ) { page, from, to ->
            val windowed = source.copy(releaseDateGte = from?.toString(), releaseDateLte = to?.toString())
            // Null: a name in the query (keyword, company...) isn't on TMDB, so it has no titles.
            val body = discoverResponse(windowed, language, page)
            if (body == null) {
                TmdbDeepPager.DeepPage(emptyList(), 0)
            } else {
                val genres = genreMap(source.mediaType, language)
                TmdbDeepPager.DeepPage(
                    items = body.results.orEmpty().mapNotNull { it.toPreview(source.mediaType, genres) },
                    totalPages = body.totalPages ?: page
                )
            }
        }
    }

    /**
     * How many titles the row's whole catalog has for [filter], from TMDB's own totals (null when TMDB
     * couldn't be reached). Several queries in one row can share titles, so for those it's TMDB's sum.
     */
    suspend fun countForFilter(spec: DisneyRowSpec, filter: TmdbSeeAllFilter): Int? = withContext(Dispatchers.IO) {
        val language = language()
        val totals = coroutineScope {
            seeAllDiscoverSources(spec, filter).map { source -> async { discoverTotal(source, language) } }.awaitAll()
        }
        if (totals.any { it == null }) return@withContext null
        val local = localItems(spec, language).count { it.matches(filter) }
        maxOf(totals.sumOf { it ?: 0 }, local)
    }

    /**
     * The years the row's catalog spans, newest first, for the See All page's Year menu: from its
     * oldest title on TMDB to next year (or the row's own last year, e.g. a classics row).
     */
    suspend fun seeAllYears(spec: DisneyRowSpec): List<Int> = withContext(Dispatchers.IO) {
        val language = language()
        val currentYear = LocalDate.now().year
        val localYears = localItems(spec, language).mapNotNull { it.releaseYear() }
        val sources = seeAllDiscoverSources(spec, TmdbSeeAllFilter())
        if (sources.isEmpty()) return@withContext localYears.distinct().sortedDescending()
        val earliest = coroutineScope {
            sources.map { source -> async { earliestYear(source, language) } }.awaitAll()
        }.filterNotNull().filter { it > 0 }
        val first = (earliest + localYears).minOrNull()?.coerceAtLeast(OLDEST_YEAR) ?: OLDEST_YEAR
        val sourceLast = sources.maxOf { source ->
            source.releaseDateLte?.let { lte ->
                if (lte == DisneyPlusCatalog.TODAY) currentYear else lte.take(4).toIntOrNull()
            } ?: (currentYear + 1)
        }
        val last = maxOf(sourceLast.coerceAtMost(currentYear + 1), localYears.maxOrNull() ?: sourceLast)
        if (last < first) emptyList() else (last downTo first).toList()
    }

    /**
     * The row's TMDB discover queries for See All, with [filter] worked into each: its own queries plus
     * its complete catalog, without the row's minimum vote counts (those only pick what the 10-title
     * row shows first). Top-rated queries keep theirs, or a title with one 10/10 vote would lead the list.
     */
    private fun seeAllDiscoverSources(spec: DisneyRowSpec, filter: TmdbSeeAllFilter): List<DisneyRowSource.Discover> {
        val today = LocalDate.now().toString()
        return (spec.sources + spec.seeAll)
            .filterIsInstance<DisneyRowSource.Discover>()
            .map { source -> if (source.sortBy.startsWith("vote_average")) source else source.copy(voteCountGte = null) }
            .distinct()
            .flatMap { source -> if (filter.isEmpty) listOf(source) else source.filteredBy(filter, today) }
            .distinct()
    }

    /**
     * [filter] as TMDB parameters on this query: nothing when it can't match (wrong kind of title, a
     * genre this kind of title doesn't have or the query leaves out, a year outside its dates), else
     * the query narrowed to the genre and year. A query for several genres at once (Action or Adventure)
     * becomes one query per genre, each also in the wanted genre (TMDB can't mix "or" and "and").
     */
    private fun DisneyRowSource.Discover.filteredBy(
        filter: TmdbSeeAllFilter,
        today: String
    ): List<DisneyRowSource.Discover> {
        if (filter.mediaType != null && filter.mediaType != mediaType) return emptyList()
        var narrowed = this
        filter.year?.let { year ->
            val gte = releaseDateGte?.let { if (it == DisneyPlusCatalog.TODAY) today else it }
            val lte = releaseDateLte?.let { if (it == DisneyPlusCatalog.TODAY) today else it }
            val from = maxOf(gte ?: "0000-01-01", "$year-01-01")
            val to = minOf(lte ?: "9999-12-31", "$year-12-31")
            if (from > to) return emptyList()
            narrowed = narrowed.copy(releaseDateGte = from, releaseDateLte = to)
        }
        if (filter.genre == null) return listOf(narrowed)
        val id = filter.genreId(mediaType)?.toString() ?: return emptyList()
        if (withoutGenres.orEmpty().split(',', '|').any { it.trim() == id }) return emptyList()
        val current = genres?.trim()?.takeIf { it.isNotEmpty() } ?: return listOf(narrowed.copy(genres = id))
        if ('|' in current) {
            val anyOf = current.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            return if (id in anyOf) listOf(narrowed.copy(genres = id))
            else anyOf.map { narrowed.copy(genres = "$it,$id") }
        }
        val allOf = current.split(',').map { it.trim() }
        return listOf(if (id in allOf) narrowed else narrowed.copy(genres = "$current,$id"))
    }

    /**
     * The row's titles that don't come from a TMDB discover query, each list its own source: its
     * hand-picked titles (a row mixing them with a query only shows its first 30), the TMDB franchise
     * collections it names, and the whole collections its hand-picked movies belong to.
     */
    private fun localJobs(spec: DisneyRowSpec, language: String): List<suspend () -> List<MetaPreview>> = buildList {
        val all = spec.sources + spec.seeAll
        all.filterIsInstance<DisneyRowSource.Curated>().distinct()
            .forEach { source -> add { curated(source, language) } }
        all.filterIsInstance<DisneyRowSource.TmdbCollection>().distinct()
            .forEach { source -> add { tmdbCollection(source.query, language) } }
        val handPicked = spec.sources.filterIsInstance<DisneyRowSource.Curated>()
        if (handPicked.isNotEmpty()) add { franchisesOf(handPicked, language) }
    }

    /** Everything [localJobs] finds, once each (looked up once, then cached). */
    private suspend fun localItems(spec: DisneyRowSpec, language: String): List<MetaPreview> = coroutineScope {
        localJobs(spec, language).map { job ->
            async {
                runCatching { job() }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten().distinctBy { "${it.apiType}:${it.id}" }
    }

    /** TMDB's total for one discover query (null: couldn't be reached; not cached then). */
    private suspend fun discoverTotal(source: DisneyRowSource.Discover, language: String): Int? {
        val key = source.toString()
        totalCache[key]?.let { return it }
        val total: Int? = countLimiter.withPermit {
            runCatching { discoverResponse(source, language, page = 1)?.totalResults ?: 0 }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
        }
        if (total != null) totalCache[key] = total
        return total
    }

    /** The year of the oldest title a discover query has on TMDB (0: none; null: couldn't be reached). */
    private suspend fun earliestYear(source: DisneyRowSource.Discover, language: String): Int? {
        val key = source.toString()
        earliestYearCache[key]?.let { return it }
        val oldestFirst = source.copy(
            sortBy = "primary_release_date.asc",
            // Titles with no date at all would otherwise come first.
            releaseDateGte = source.releaseDateGte ?: "1870-01-01"
        )
        val year: Int? = countLimiter.withPermit {
            runCatching {
                val results = discoverResponse(oldestFirst, language, page = 1)?.results.orEmpty()
                results.mapNotNull { (it.releaseDate ?: it.firstAirDate)?.take(4)?.toIntOrNull() }.minOrNull() ?: 0
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        }
        if (year != null) earliestYearCache[key] = year
        return year
    }

    private fun MetaPreview.releaseYear(): Int? = (released ?: releaseInfo)?.take(4)?.toIntOrNull()

    /**
     * Every movie of the TMDB franchise collections the hand-picked movies belong to (Toy Story ->
     * the whole Toy Story Collection), in release order, collection by collection.
     */
    private suspend fun franchisesOf(sources: List<DisneyRowSource.Curated>, language: String): List<MetaPreview> =
        coroutineScope {
            val movies = sources.flatMap { it.titles }.filter { it.mediaType == TmdbCollectionMediaType.MOVIE }.distinct()
            val tmdbIds = movies.map { title -> async { resolveCurated(title, language) } }.awaitAll()
                .mapNotNull { it?.id?.removePrefix("tmdb:")?.toIntOrNull() }
                .distinct()
            val collectionIds = tmdbIds.map { id -> async { movieCollectionId(id) } }.awaitAll().filterNotNull().distinct()
            collectionIds.map { id -> async { collectionParts(id, language) } }.awaitAll().flatten()
        }

    /** A TMDB franchise collection found by name ("Toy Story" or "Toy Story Collection"): all its movies. */
    private suspend fun tmdbCollection(query: String, language: String): List<MetaPreview> {
        val id = collectionIdByName(query) ?: return emptyList()
        return collectionParts(id, language)
    }

    private suspend fun collectionIdByName(query: String): Int? {
        val key = query.lowercase(Locale.US)
        collectionSearchCache[key]?.let { return it.value }
        val result = lookupLimiter.withPermit {
            runCatching {
                val results = tmdbApi.searchCollections(apiKey, query).body()?.results.orEmpty()
                val wanted = normalizeTitle(query).removeSuffix("collection")
                // Exact name first ("Toy Story Collection"), then a collection whose name starts with it.
                (results.firstOrNull { normalizeTitle(it.name.orEmpty()).removeSuffix("collection") == wanted }
                    ?: results.firstOrNull { normalizeTitle(it.name.orEmpty()).startsWith(wanted) })?.id
            }.onFailure { if (it is CancellationException) throw it }
        }
        if (result.isSuccess) collectionSearchCache[key] = Optional(result.getOrNull())
        return result.getOrNull()
    }

    private suspend fun movieCollectionId(tmdbId: Int): Int? {
        movieCollectionCache[tmdbId]?.let { return it.value }
        val result = lookupLimiter.withPermit {
            runCatching {
                val response = tmdbApi.getMovieDetails(tmdbId, apiKey)
                if (!response.isSuccessful) throw java.io.IOException("TMDB movie HTTP ${response.code()}")
                response.body()?.belongsToCollection?.id
            }.onFailure { if (it is CancellationException) throw it }
        }
        if (result.isSuccess) movieCollectionCache[tmdbId] = Optional(result.getOrNull())
        return result.getOrNull()
    }

    private suspend fun collectionParts(collectionId: Int, language: String): List<MetaPreview> {
        val key = "$collectionId:$language"
        collectionPartsCache[key]?.let { return it }
        val parts = lookupLimiter.withPermit {
            val response = tmdbApi.getCollectionDetails(collectionId, apiKey, language)
            if (!response.isSuccessful) throw java.io.IOException("TMDB collection HTTP ${response.code()}")
            response.body()?.parts.orEmpty()
        }
        val genreNames = genreMap(TmdbCollectionMediaType.MOVIE, language)
        val previews = parts
            .sortedBy { it.releaseDate?.takeIf { date -> date.isNotBlank() } ?: "9999" }
            .mapNotNull { part ->
                TmdbDiscoverResult(
                    id = part.id,
                    title = part.title,
                    originalTitle = part.originalTitle,
                    posterPath = part.posterPath,
                    backdropPath = part.backdropPath,
                    overview = part.overview,
                    releaseDate = part.releaseDate,
                    voteAverage = part.voteAverage,
                    voteCount = part.voteCount,
                    genreIds = part.genreIds
                ).toPreview(TmdbCollectionMediaType.MOVIE, genreNames)
            }
        if (previews.isNotEmpty()) collectionPartsCache[key] = previews
        return previews
    }

    /** TMDB genre names by id, per media type and language (for the See All page's Genre filter). */
    private suspend fun genreMap(mediaType: TmdbCollectionMediaType, language: String): Map<Int, String> {
        val key = "${mediaType.value}:$language"
        genreNameCache[key]?.let { return it }
        val map = runCatching {
            val response = when (mediaType) {
                TmdbCollectionMediaType.MOVIE -> tmdbApi.getMovieGenres(apiKey, language)
                TmdbCollectionMediaType.TV -> tmdbApi.getTvGenres(apiKey, language)
            }
            response.body()?.genres.orEmpty().associate { it.id to it.name }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull().orEmpty()
        if (map.isNotEmpty()) genreNameCache[key] = map
        return map
    }

    private fun topGenres(infos: List<DisneyTitleInfo>): List<Int> =
        infos.flatMap { it.genreIds }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(2)
            .map { it.key }

    /** Hero slides: the first few titles with a backdrop, each given a title logo when TMDB has one. */
    suspend fun heroItems(row: CatalogRow?): List<MetaPreview> = withLogos(heroPicks(row?.items.orEmpty()))

    /** The first few titles that have a backdrop (hero slides, before their title logos are added). */
    fun heroPicks(items: List<MetaPreview>): List<MetaPreview> =
        items.filter { !it.background.isNullOrBlank() }.distinctBy { it.id }.take(HERO_ITEM_COUNT)

    /**
     * Gives the first [count] titles their title art for horizontal cards that show the name on the
     * artwork, like Disney+: TMDB's backdrop with the title already on it when there is one, otherwise
     * the title logo to lay over the plain backdrop. Titles with neither show their name as text.
     */
    suspend fun withTitleArt(items: List<MetaPreview>, count: Int = items.size): List<MetaPreview> =
        titleArt.withTitleArt(items, count)

    /** Gives each hero slide its title logo when TMDB has one. */
    suspend fun withLogos(picks: List<MetaPreview>): List<MetaPreview> = titleArt.withLogos(picks)

    /** For cards that find their title art as they appear (See All pages). */
    val titleArtResolver: TitleArtResolver get() = titleArt

    // ------------------------------------------------------------------ ownership (continue watching)

    /** True when TMDB credits the title to a studio or network the service owns. Unknown titles count as not owned. */
    suspend fun isOwnedBy(service: HubService, contentId: String, contentType: String): Boolean =
        titleInfo(contentId, contentType)?.owners?.contains(service) == true

    suspend fun titleInfo(contentId: String, contentType: String): DisneyTitleInfo? = withContext(Dispatchers.IO) {
        val isTv = contentType.equals("series", true) || contentType.equals("tv", true)
        val cacheKey = "${if (isTv) "tv" else "movie"}:$contentId"
        titleInfoCache[cacheKey]?.let { return@withContext it.value }
        val info = lookupLimiter.withPermit {
            runCatching {
                val tmdbId = tmdbService.ensureTmdbId(contentId, if (isTv) "tv" else "movie")?.toIntOrNull()
                    ?: return@runCatching null
                val details = if (isTv) tmdbApi.getTvDetails(tmdbId, apiKey).body() else tmdbApi.getMovieDetails(tmdbId, apiKey).body()
                details?.let {
                    DisneyTitleInfo(
                        tmdbId = tmdbId,
                        mediaType = if (isTv) TmdbCollectionMediaType.TV else TmdbCollectionMediaType.MOVIE,
                        owners = HubService.entries.filter { service -> it.isOwnedBy(service.ownership) }.toSet(),
                        genreIds = it.genres.orEmpty().map { genre -> genre.id }
                    )
                }
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        }
        // Only cache a confirmed answer. A failed or empty lookup (e.g. a timeout) is retried next time,
        // so one network hiccup can't hide a Disney title from Continue Watching for the whole session.
        if (info != null) titleInfoCache[cacheKey] = Optional(info)
        info
    }

    private fun TmdbDetailsResponse.isOwnedBy(ownership: HubOwnership): Boolean {
        val companies = productionCompanies.orEmpty()
        if (companies.any { company -> company.id?.let { id -> id in ownership.companyIds } == true }) return true
        val networkList = networks.orEmpty()
        if (networkList.any { network -> network.id?.let { id -> id in ownership.networkIds } == true }) return true
        if (companies.any { company ->
                val name = company.name?.lowercase(Locale.US).orEmpty()
                name.isNotBlank() && ownership.companyNameFragments.any { name.contains(it) }
            }
        ) return true
        return networkList.any { network ->
            network.name?.trim()?.lowercase(Locale.US)?.let { name -> name in ownership.networkNames } == true
        }
    }

    // ------------------------------------------------------------------ sources

    private suspend fun discover(source: DisneyRowSource.Discover, language: String, page: Int = 1): List<MetaPreview> {
        val body = discoverResponse(source, language, page) ?: return emptyList()
        val genres = genreMap(source.mediaType, language)
        return body.results.orEmpty().mapNotNull { it.toPreview(source.mediaType, genres) }
    }

    /**
     * One page of a discover query straight from TMDB. Null when a name in it (keyword, company,
     * network, provider) isn't on TMDB, so the query has no titles.
     */
    private suspend fun discoverResponse(
        source: DisneyRowSource.Discover,
        language: String,
        page: Int
    ): TmdbDiscoverResponse? {
        val resolvedKeywordId = source.keywordQuery?.let { query ->
            keywordId(query) ?: return null
        }
        val companies = source.companyQuery?.let { query ->
            val resolved = companyId(query) ?: return null
            listOfNotNull(source.companies, resolved.toString()).joinToString("|")
        } ?: source.companies
        val networks = source.networkLookup?.let { lookup ->
            val resolved = networkId(lookup, language) ?: return null
            listOfNotNull(source.networks, resolved.toString()).joinToString("|")
        } ?: source.networks
        val today = LocalDate.now().toString()
        val releaseLte = source.releaseDateLte?.let { if (it == DisneyPlusCatalog.TODAY) today else it }
        val releaseGte = source.releaseDateGte?.let { if (it == DisneyPlusCatalog.TODAY) today else it }
        val providers = source.providerQuery?.let { query ->
            val resolved = providerId(query) ?: return null
            listOfNotNull(source.providers, resolved.toString()).joinToString("|")
        } ?: source.providers
        val usesProviders = !providers.isNullOrBlank()
        val response = when (source.mediaType) {
            TmdbCollectionMediaType.MOVIE -> tmdbApi.discoverMovies(
                apiKey = apiKey,
                language = language,
                page = page,
                sortBy = source.sortBy,
                withCompanies = companies,
                releaseDateLte = releaseLte,
                voteCountGte = source.voteCountGte,
                withGenres = source.genres,
                releaseDateGte = releaseGte,
                withKeywords = resolvedKeywordId?.toString(),
                watchRegion = if (usesProviders) "US" else null,
                withWatchProviders = providers,
                withWatchMonetizationTypes = if (usesProviders) "flatrate" else null,
                withoutGenres = source.withoutGenres,
                withRuntimeGte = source.runtimeGte,
                withRuntimeLte = source.runtimeLte,
                withOriginalLanguage = source.originalLanguage,
                withoutCompanies = source.withoutCompanies
            )
            TmdbCollectionMediaType.TV -> tmdbApi.discoverTv(
                apiKey = apiKey,
                language = language,
                page = page,
                sortBy = source.sortBy.replace("primary_release_date", "first_air_date"),
                withCompanies = companies,
                withNetworks = networks,
                firstAirDateLte = releaseLte,
                voteCountGte = source.voteCountGte,
                withGenres = source.genres,
                firstAirDateGte = releaseGte,
                withKeywords = resolvedKeywordId?.toString(),
                watchRegion = if (usesProviders) "US" else null,
                withWatchProviders = providers,
                withWatchMonetizationTypes = if (usesProviders) "flatrate" else null,
                withoutGenres = source.withoutGenres,
                withOriginalLanguage = source.originalLanguage,
                withoutCompanies = source.withoutCompanies
            )
        }
        // An error answer (TMDB still busy after retries) is a failure, not "no more titles".
        if (!response.isSuccessful) throw java.io.IOException("TMDB discover HTTP ${response.code()}")
        return response.body() ?: TmdbDiscoverResponse()
    }

    private suspend fun keywordId(query: String): Int? {
        val key = query.lowercase(Locale.US)
        keywordCache[key]?.let { return it.value }
        val id = runCatching {
            val results = tmdbApi.searchKeywords(apiKey, query).body()?.results.orEmpty()
            // Only the exact keyword: never a near match or the first result ("batman" isn't a TMDB
            // keyword; its first result is "baştan"), which would fill a catalog with the wrong titles.
            results.firstOrNull { it.name?.trim().equals(query.trim(), ignoreCase = true) }?.id
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        if (id != null) keywordCache[key] = Optional(id)
        return id
    }

    /** Resolves a company name (e.g. "Disney Channel") to its TMDB id, preferring an exact name match. */
    private suspend fun companyId(query: String): Int? {
        val key = query.lowercase(Locale.US)
        companyCache[key]?.let { return it.value }
        val id = runCatching {
            val results = tmdbApi.searchCompanies(apiKey, query).body()?.results.orEmpty()
            (results.firstOrNull { it.name.equals(query, ignoreCase = true) && it.originCountry.equals("US", ignoreCase = true) }
                ?: results.firstOrNull { it.name.equals(query, ignoreCase = true) }
                ?: results.firstOrNull())?.id
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        if (id != null) companyCache[key] = Optional(id)
        return id
    }

    /**
     * Finds a network's TMDB id through one of its shows (e.g. Smithsonian Channel through "Aerial America"):
     * the first probe whose TMDB networks include one with a wanted name. Found ids are cached; a miss
     * (or a network error) is retried next time. One lookup at a time, so parallel rows share the answer.
     */
    private suspend fun networkId(lookup: NetworkLookup, language: String): Int? = networkLookupMutex.withLock {
        val key = lookup.names.first().lowercase(Locale.US)
        networkCache[key]?.let { return@withLock it.value }
        val wanted = lookup.names.map { networkKey(it) }.toSet()
        for (probe in lookup.probes) {
            val tmdbId = resolveCurated(probe, language)?.id?.removePrefix("tmdb:")?.toIntOrNull() ?: continue
            val details = runCatching { tmdbApi.getTvDetails(tmdbId, apiKey).body() }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull() ?: continue
            val match = details.networks.orEmpty()
                .firstOrNull { network -> networkKey(network.name.orEmpty()) in wanted }
                ?.id
            if (match != null) {
                networkCache[key] = Optional(match)
                return@withLock match
            }
        }
        null
    }

    /**
     * Finds a watch provider's TMDB id by name (e.g. "Crunchyroll") in TMDB's US provider list (TV, then
     * movies). Found ids are cached; a miss is retried next time.
     */
    private suspend fun providerId(name: String): Int? = providerLookupMutex.withLock {
        val key = name.lowercase(Locale.US)
        providerCache[key]?.let { return@withLock it }
        for (isTv in listOf(true, false)) {
            val list: List<TmdbWatchProviderEntry> = runCatching {
                val response = if (isTv) tmdbApi.getTvWatchProviders(apiKey, "US") else tmdbApi.getMovieWatchProviders(apiKey, "US")
                response.body()?.results.orEmpty()
            }.onFailure { if (it is CancellationException) throw it }.getOrNull() ?: continue
            val match = list.firstOrNull { it.providerName?.trim().equals(name, ignoreCase = true) }?.providerId
            if (match != null) {
                providerCache[key] = match
                return@withLock match
            }
        }
        null
    }

    /** Backdrop images for a collection tile, taken from the collection's art titles. */
    suspend fun collectionArt(hub: DisneyPlusHub): List<String> = withContext(Dispatchers.IO) {
        val language = language()
        coroutineScope {
            DisneyPlusCatalog.collectionArt(hub).map { title -> async { resolveCurated(title, language) } }.awaitAll()
        }.mapNotNull { it?.background }
    }

    private suspend fun curated(source: DisneyRowSource.Curated, language: String): List<MetaPreview> = coroutineScope {
        source.titles.map { title -> async { resolveCurated(title, language) } }.awaitAll().filterNotNull()
    }

    private suspend fun resolveCurated(title: CuratedTitle, language: String): MetaPreview? {
        val key = "${title.mediaType.value}:${title.title.lowercase(Locale.US)}:${title.year}:$language"
        curatedCache[key]?.let { return it.value }
        val preview = lookupLimiter.withPermit {
            runCatching { findCurated(title, language) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
        }
        // Only cache successful lookups, so a transient network error doesn't hide the title for the whole session.
        if (preview != null) curatedCache[key] = Optional(preview)
        else Log.w(TAG, "Hand-picked title not found on TMDB: ${title.title} (${title.year})")
        return preview
    }

    /**
     * A hand-picked title on TMDB, so none is lost: the exact name in its year first. TMDB sometimes
     * dates a title a year off (Snow White and the Seven Dwarfs is 1938 there, not 1937), so then the
     * exact name a year either side, then in any year (the closest one). Last, as before, the best
     * match TMDB gives for the name in that year.
     */
    private suspend fun findCurated(curated: CuratedTitle, language: String): MetaPreview? {
        val wanted = normalizeTitle(curated.title)
        val isTv = curated.mediaType == TmdbCollectionMediaType.TV
        suspend fun search(year: Int?): List<TmdbDiscoverResult> {
            val response = if (isTv) {
                tmdbApi.searchTv(apiKey, curated.title, language, firstAirDateYear = year)
            } else {
                tmdbApi.searchMovies(apiKey, curated.title, language, primaryReleaseYear = year)
            }
            if (!response.isSuccessful) throw java.io.IOException("TMDB search HTTP ${response.code()}")
            return response.body()?.results.orEmpty()
        }
        fun TmdbDiscoverResult.isExact(): Boolean =
            normalizeTitle(originalTitle ?: originalName ?: "") == wanted ||
                normalizeTitle(title ?: name ?: "") == wanted
        fun TmdbDiscoverResult.year(): Int? = (if (isTv) firstAirDate else releaseDate)?.take(4)?.toIntOrNull()
        val genres = genreMap(curated.mediaType, language)

        val inYear = search(curated.year)
        inYear.firstOrNull { it.isExact() }?.let { return it.toPreview(curated.mediaType, genres) }
        val year = curated.year
        if (year != null) {
            for (near in listOf(year + 1, year - 1)) {
                search(near).firstOrNull { it.isExact() }?.let { return it.toPreview(curated.mediaType, genres) }
            }
            search(null).filter { it.isExact() }
                .minByOrNull { result -> kotlin.math.abs((result.year() ?: 0) - year) }
                ?.let { return it.toPreview(curated.mediaType, genres) }
        }
        return inYear.firstOrNull()?.toPreview(curated.mediaType, genres)
    }

    /** Like [normalizeTitle] but keeps "+", so "discovery+" doesn't match "Discovery". */
    private fun networkKey(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9+]+"), "")

    private fun normalizeTitle(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.US)
            // "Beauty & the Beast" and "Beauty and the Beast" are the same title.
            .replace("&", "and")
            .replace(Regex("[^a-z0-9]+"), "")

    // ------------------------------------------------------------------ helpers

    private fun interleave(lists: List<List<MetaPreview>>, cap: Int = ROW_ITEM_CAP): List<MetaPreview> {
        val seen = HashSet<String>()
        val out = ArrayList<MetaPreview>()
        val maxSize = lists.maxOfOrNull { it.size } ?: 0
        for (index in 0 until maxSize) {
            for (list in lists) {
                val item = list.getOrNull(index) ?: continue
                if (seen.add("${item.apiType}:${item.id}")) out += item
                if (out.size >= cap) return out
            }
        }
        return out
    }

    private fun buildRow(catalogId: String, title: String, items: List<MetaPreview>): CatalogRow {
        val allSeries = items.all { it.type == ContentType.SERIES }
        val type = if (allSeries) ContentType.SERIES else ContentType.MOVIE
        return CatalogRow(
            addonId = "tmdb",
            addonName = "TMDB",
            addonBaseUrl = "",
            catalogId = catalogId,
            catalogName = title,
            type = type,
            rawType = if (allSeries) "series" else "movie",
            items = items,
            isLoading = false,
            hasMore = false,
            currentPage = 1,
            supportsSkip = false,
            skipStep = 20
        )
    }

    private fun TmdbDiscoverResult.toPreview(
        mediaType: TmdbCollectionMediaType,
        genreNames: Map<Int, String> = emptyMap()
    ): MetaPreview? {
        val name = title?.takeIf { it.isNotBlank() }
            ?: this.name?.takeIf { it.isNotBlank() }
            ?: originalTitle?.takeIf { it.isNotBlank() }
            ?: originalName?.takeIf { it.isNotBlank() }
            ?: return null
        val isTv = mediaType == TmdbCollectionMediaType.TV
        val date = if (isTv) firstAirDate else releaseDate
        return MetaPreview(
            id = "tmdb:$id",
            type = if (isTv) ContentType.SERIES else ContentType.MOVIE,
            rawType = if (isTv) "series" else "movie",
            name = name,
            poster = imageUrl(posterPath, "w500") ?: imageUrl(backdropPath, "w780"),
            posterShape = PosterShape.POSTER,
            background = imageUrl(backdropPath, "w1280"),
            // Horizontal cards: a 780px backdrop is plenty and downloads much faster than the 1280px one.
            landscapePoster = imageUrl(backdropPath, "w780"),
            logo = null,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = date?.take(4)?.takeIf { it.isNotBlank() },
            released = date?.takeIf { it.isNotBlank() },
            imdbRating = voteAverage?.toFloat()?.takeIf { it > 0f },
            voteCount = voteCount,
            // Names for the See All page's Genre filter.
            genres = genreIds.orEmpty().mapNotNull { genreNames[it] }
        )
    }

    private fun imageUrl(path: String?, size: String): String? =
        path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }
}
