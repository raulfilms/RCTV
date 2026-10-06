package com.nuvio.tv.data.disneyplus

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDetailsResponse
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
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

/** What we learned about one watched title from TMDB: whether Disney owns it, and its genres. */
data class DisneyTitleInfo(
    val tmdbId: Int,
    val mediaType: TmdbCollectionMediaType,
    val isDisneyOwned: Boolean,
    val genreIds: List<Int>
)

/**
 * Builds the Disney+ hub rows from TMDB. Everything is cached in memory for the life of the app
 * process (curated title lookups, keyword ids, per-title ownership checks), so moving between the
 * main page and the brand pages, or coming back to them, doesn't re-run the same lookups.
 */
@Singleton
class DisneyPlusRepository @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore
) {
    private val apiKey get() = BuildConfig.TMDB_API_KEY

    private val curatedCache = ConcurrentHashMap<String, Optional<MetaPreview>>()
    private val keywordCache = ConcurrentHashMap<String, Optional<Int>>()
    private val titleInfoCache = ConcurrentHashMap<String, Optional<DisneyTitleInfo>>()
    private val logoCache = ConcurrentHashMap<String, Optional<String>>()
    private val rowCache = ConcurrentHashMap<String, List<MetaPreview>>()

    /** Tiny nullable holder so "looked it up, found nothing" can be cached (ConcurrentHashMap rejects nulls). */
    private data class Optional<T>(val value: T?)

    private val lookupLimiter = Semaphore(6)

    private suspend fun language(): String =
        runCatching { tmdbSettingsDataStore.settings.first().language }.getOrNull()?.ifBlank { null } ?: "en"

    // ------------------------------------------------------------------ rows

    /**
     * Resolves one catalog row: runs every source in parallel, then interleaves their results
     * (first of each, then second of each...) so a mixed movie/series row doesn't show all movies first.
     */
    suspend fun resolveRow(hub: DisneyPlusHub, spec: DisneyRowSpec, title: String, forceRefresh: Boolean = false): CatalogRow? =
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
                interleave(perSource).also { if (it.isNotEmpty()) rowCache[cacheKey] = it }
            }
            if (items.isEmpty()) null else buildRow("disney_${hub.key}_${spec.id}", title, items)
        }

    /** Disney+ titles in the genres the person watches most. Falls back to Disney+'s best-rated titles. */
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

        val sources = buildList {
            if (movieGenres.isNotEmpty() || tvGenres.isNotEmpty()) {
                if (movieGenres.isNotEmpty()) {
                    add(DisneyRowSource.Discover(TmdbCollectionMediaType.MOVIE, providers = DisneyPlusIds.PROVIDER_DISNEY_PLUS, genres = movieGenres.joinToString("|"), voteCountGte = 150))
                }
                if (tvGenres.isNotEmpty()) {
                    add(DisneyRowSource.Discover(TmdbCollectionMediaType.TV, providers = DisneyPlusIds.PROVIDER_DISNEY_PLUS, genres = tvGenres.joinToString("|"), voteCountGte = 50))
                }
            } else {
                add(DisneyRowSource.Discover(TmdbCollectionMediaType.MOVIE, sortBy = "vote_average.desc", providers = DisneyPlusIds.PROVIDER_DISNEY_PLUS, voteCountGte = 3000))
                add(DisneyRowSource.Discover(TmdbCollectionMediaType.TV, sortBy = "vote_average.desc", providers = DisneyPlusIds.PROVIDER_DISNEY_PLUS, voteCountGte = 800))
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

    private fun topGenres(infos: List<DisneyTitleInfo>): List<Int> =
        infos.flatMap { it.genreIds }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(2)
            .map { it.key }

    /** Hero slides: the first few titles with a backdrop, each given a title logo when TMDB has one. */
    suspend fun heroItems(row: CatalogRow?): List<MetaPreview> = withContext(Dispatchers.IO) {
        val picks = row?.items.orEmpty().filter { !it.background.isNullOrBlank() }.take(HERO_ITEM_COUNT)
        coroutineScope {
            picks.map { item ->
                async { item.copy(logo = item.logo ?: logoFor(item)) }
            }.awaitAll()
        }
    }

    private suspend fun logoFor(item: MetaPreview): String? {
        val tmdbId = item.id.removePrefix("tmdb:").toIntOrNull() ?: return null
        val isTv = item.type == ContentType.SERIES
        val key = "${if (isTv) "tv" else "movie"}:$tmdbId"
        logoCache[key]?.let { return it.value }
        val result = runCatching {
            val response = if (isTv) tmdbApi.getTvImages(tmdbId, apiKey) else tmdbApi.getMovieImages(tmdbId, apiKey)
            if (!response.isSuccessful) error("HTTP ${response.code()}")
            val logos = response.body()?.logos.orEmpty().filter { !it.filePath.isNullOrBlank() }
            val best = logos.firstOrNull { it.iso6391 == "en" } ?: logos.firstOrNull()
            best?.filePath?.let { "https://image.tmdb.org/t/p/w500$it" }
        }.onFailure { if (it is CancellationException) throw it }
        // Only remember real answers; a network failure gets retried next time.
        if (result.isSuccess) logoCache[key] = Optional(result.getOrNull())
        return result.getOrNull()
    }

    // ------------------------------------------------------------------ ownership (continue watching)

    /** True when TMDB credits the title to a Disney-owned studio or network. Unknown titles count as not Disney. */
    suspend fun isDisneyOwned(contentId: String, contentType: String): Boolean =
        titleInfo(contentId, contentType)?.isDisneyOwned == true

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
                        isDisneyOwned = it.isDisneyOwned(),
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

    private fun TmdbDetailsResponse.isDisneyOwned(): Boolean {
        val companies = productionCompanies.orEmpty()
        if (companies.any { company -> company.id?.let { id -> id in DisneyPlusIds.DISNEY_COMPANY_IDS } == true }) return true
        val networkList = networks.orEmpty()
        if (networkList.any { network -> network.id?.let { id -> id in DisneyPlusIds.DISNEY_NETWORK_IDS } == true }) return true
        if (companies.any { company ->
                val name = company.name?.lowercase(Locale.US).orEmpty()
                name.isNotBlank() && DisneyPlusIds.DISNEY_COMPANY_NAME_FRAGMENTS.any { name.contains(it) }
            }
        ) return true
        return networkList.any { network ->
            network.name?.trim()?.lowercase(Locale.US)?.let { name -> name in DisneyPlusIds.DISNEY_NETWORK_NAMES } == true
        }
    }

    // ------------------------------------------------------------------ sources

    private suspend fun discover(source: DisneyRowSource.Discover, language: String): List<MetaPreview> {
        val resolvedKeywordId = source.keywordQuery?.let { query ->
            keywordId(query) ?: return emptyList()
        }
        val today = LocalDate.now().toString()
        val releaseLte = source.releaseDateLte?.let { if (it == DisneyPlusCatalog.TODAY) today else it }
        val releaseGte = source.releaseDateGte?.let { if (it == DisneyPlusCatalog.TODAY) today else it }
        val usesProviders = !source.providers.isNullOrBlank()
        val response = when (source.mediaType) {
            TmdbCollectionMediaType.MOVIE -> tmdbApi.discoverMovies(
                apiKey = apiKey,
                language = language,
                page = 1,
                sortBy = source.sortBy,
                withCompanies = source.companies,
                releaseDateLte = releaseLte,
                voteCountGte = source.voteCountGte,
                withGenres = source.genres,
                releaseDateGte = releaseGte,
                withKeywords = resolvedKeywordId?.toString(),
                watchRegion = if (usesProviders) "US" else null,
                withWatchProviders = source.providers,
                withWatchMonetizationTypes = if (usesProviders) "flatrate" else null,
                withoutGenres = source.withoutGenres,
                withRuntimeGte = source.runtimeGte,
                withRuntimeLte = source.runtimeLte
            ).body()
            TmdbCollectionMediaType.TV -> tmdbApi.discoverTv(
                apiKey = apiKey,
                language = language,
                page = 1,
                sortBy = source.sortBy.replace("primary_release_date", "first_air_date"),
                withCompanies = source.companies,
                withNetworks = source.networks,
                firstAirDateLte = releaseLte,
                voteCountGte = source.voteCountGte,
                withGenres = source.genres,
                firstAirDateGte = releaseGte,
                withKeywords = resolvedKeywordId?.toString(),
                watchRegion = if (usesProviders) "US" else null,
                withWatchProviders = source.providers,
                withWatchMonetizationTypes = if (usesProviders) "flatrate" else null,
                withoutGenres = source.withoutGenres
            ).body()
        }
        return response?.results.orEmpty().mapNotNull { it.toPreview(source.mediaType) }
    }

    private suspend fun keywordId(query: String): Int? {
        val key = query.lowercase(Locale.US)
        keywordCache[key]?.let { return it.value }
        val id = runCatching {
            val results = tmdbApi.searchKeywords(apiKey, query).body()?.results.orEmpty()
            (results.firstOrNull { it.name.equals(query, ignoreCase = true) } ?: results.firstOrNull())?.id
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        if (id != null) keywordCache[key] = Optional(id)
        return id
    }

    private suspend fun curated(source: DisneyRowSource.Curated, language: String): List<MetaPreview> = coroutineScope {
        source.titles.map { title -> async { resolveCurated(title, language) } }.awaitAll().filterNotNull()
    }

    private suspend fun resolveCurated(title: CuratedTitle, language: String): MetaPreview? {
        val key = "${title.mediaType.value}:${title.title.lowercase(Locale.US)}:${title.year}:$language"
        curatedCache[key]?.let { return it.value }
        val preview = lookupLimiter.withPermit {
            runCatching {
                val results = when (title.mediaType) {
                    TmdbCollectionMediaType.MOVIE ->
                        tmdbApi.searchMovies(apiKey, title.title, language, primaryReleaseYear = title.year).body()
                    TmdbCollectionMediaType.TV ->
                        tmdbApi.searchTv(apiKey, title.title, language, firstAirDateYear = title.year).body()
                }?.results.orEmpty()
                val wanted = normalizeTitle(title.title)
                val best = results.firstOrNull { result ->
                    normalizeTitle(result.originalTitle ?: result.originalName ?: "") == wanted ||
                        normalizeTitle(result.title ?: result.name ?: "") == wanted
                } ?: results.firstOrNull()
                best?.toPreview(title.mediaType)
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        }
        // Only cache successful lookups, so a transient network error doesn't hide the title for the whole session.
        if (preview != null) curatedCache[key] = Optional(preview)
        return preview
    }

    private fun normalizeTitle(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "")

    // ------------------------------------------------------------------ helpers

    private fun interleave(lists: List<List<MetaPreview>>): List<MetaPreview> {
        val seen = HashSet<String>()
        val out = ArrayList<MetaPreview>()
        val maxSize = lists.maxOfOrNull { it.size } ?: 0
        for (index in 0 until maxSize) {
            for (list in lists) {
                val item = list.getOrNull(index) ?: continue
                if (seen.add("${item.apiType}:${item.id}")) out += item
                if (out.size >= ROW_ITEM_CAP) return out
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

    private fun TmdbDiscoverResult.toPreview(mediaType: TmdbCollectionMediaType): MetaPreview? {
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
            logo = null,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = date?.take(4)?.takeIf { it.isNotBlank() },
            released = date?.takeIf { it.isNotBlank() },
            imdbRating = voteAverage?.toFloat()?.takeIf { it > 0f },
            voteCount = voteCount,
            genres = emptyList()
        )
    }

    private fun imageUrl(path: String?, size: String): String? =
        path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }
}
