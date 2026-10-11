package com.nuvio.tv.data.channelprofile

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.screens.channelprofile.ChannelBrand
import com.nuvio.tv.ui.screens.channelprofile.ChannelBrands
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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ChannelProfileRepo"
private const val ROW_ITEM_CAP = 30
private const val HERO_ITEM_COUNT = 5
/** EPG titles looked up to find a channel's TMDB network when it isn't a known one. */
private const val NETWORK_LOOKUP_TITLES = 6

/** A movie seen in a channel's guide: its title and, when the guide gives one, its year. */
data class EpgMovieTitle(val title: String, val year: Int?)

/** The brand's TMDB ids: networks (its shows) and production companies (its movies). */
data class ChannelBrandIds(val networks: List<Int>, val companies: List<Int>)

/** What TMDB says about one title: who aired it and who made it. */
private data class TitleInfo(
    val tmdbId: Int,
    val isTv: Boolean,
    val networkIds: List<Int>,
    val networkNames: List<String>,
    val companyIds: List<Int>,
    val companyNames: List<String>
)

/**
 * Content for a channel's profile page, from TMDB: the brand's TV shows (by TMDB network),
 * its movies (by production company and from the movies in its guide), and whether a title
 * someone is watching belongs to it (for Continue Watching). Cached for the app's life.
 */
@Singleton
class ChannelProfileRepository @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore
) {
    private val apiKey get() = BuildConfig.TMDB_API_KEY

    private data class Optional<T>(val value: T?)

    private val idsCache = ConcurrentHashMap<String, ChannelBrandIds>()
    private val idsLocks = ConcurrentHashMap<String, Mutex>()
    private val networkNameCache = ConcurrentHashMap<Int, Optional<String>>()
    private val companyCache = ConcurrentHashMap<String, Optional<Int>>()
    private val searchCache = ConcurrentHashMap<String, Optional<MetaPreview>>()
    private val titleInfoCache = ConcurrentHashMap<String, Optional<TitleInfo>>()
    private val logoCache = ConcurrentHashMap<String, Optional<String>>()
    private val rowCache = ConcurrentHashMap<String, List<MetaPreview>>()

    private val lookupLimiter = Semaphore(6)

    private suspend fun language(): String =
        runCatching { tmdbSettingsDataStore.settings.first().language }.getOrNull()?.ifBlank { null } ?: "en"

    // ------------------------------------------------------------------ brand ids

    /**
     * The brand's TMDB networks and companies. Known network ids are used only when TMDB's name
     * for them is the brand's; for any other channel the network is found through shows in its
     * guide (a show whose TMDB network has the channel's name).
     */
    suspend fun brandIds(brand: ChannelBrand, epgSeries: List<String>): ChannelBrandIds = withContext(Dispatchers.IO) {
        idsCache[brand.key]?.let { return@withContext it }
        idsLocks.getOrPut(brand.key) { Mutex() }.withLock {
            idsCache[brand.key]?.let { return@withLock it }
            val verified = coroutineScope {
                brand.tmdbNetworks.map { id -> async { id.takeIf { ChannelBrands.isBrandNetwork(brand, networkName(id)) } } }
                    .awaitAll()
                    .filterNotNull()
            }
            val networks = verified.ifEmpty { networksFromGuide(brand, epgSeries) }
            val companies = coroutineScope {
                brand.companyQueries.map { query -> async { companyId(query) } }.awaitAll().filterNotNull()
            }
            val ids = ChannelBrandIds(networks.distinct(), companies.distinct())
            // Keep answers that found something, or a guide search that found nothing; anything else
            // (a network hiccup, a guide that hadn't loaded yet) is tried again next time.
            if (ids.networks.isNotEmpty() || ids.companies.isNotEmpty() ||
                (brand.tmdbNetworks.isEmpty() && epgSeries.isNotEmpty())
            ) {
                idsCache[brand.key] = ids
            }
            ids
        }
    }

    private suspend fun networkName(id: Int): String? {
        networkNameCache[id]?.let { return it.value }
        val result = runCatching {
            val response = tmdbApi.getNetworkDetails(id, apiKey)
            if (!response.isSuccessful) error("HTTP ${response.code()}")
            response.body()?.name
        }.onFailure { if (it is CancellationException) throw it }
        if (result.isSuccess) networkNameCache[id] = Optional(result.getOrNull())
        return result.getOrNull()
    }

    private suspend fun networksFromGuide(brand: ChannelBrand, epgSeries: List<String>): List<Int> {
        val language = language()
        return coroutineScope {
            epgSeries.take(NETWORK_LOOKUP_TITLES).map { title ->
                async {
                    val show = searchExact(title, year = null, isTv = true, language = language) ?: return@async emptyList()
                    val info = titleInfo(show.id, "series") ?: return@async emptyList()
                    info.networkIds.zip(info.networkNames)
                        .filter { (_, name) -> ChannelBrands.isBrandNetwork(brand, name) }
                        .map { it.first }
                }
            }.awaitAll().flatten()
        }
    }

    /** A company name ("HBO Films") to its TMDB id, preferring an exact US match. */
    private suspend fun companyId(query: String): Int? {
        val key = query.lowercase(Locale.US)
        companyCache[key]?.let { return it.value }
        val result = runCatching {
            val results = tmdbApi.searchCompanies(apiKey, query).body()?.results.orEmpty()
            (results.firstOrNull { it.name.equals(query, ignoreCase = true) && it.originCountry.equals("US", ignoreCase = true) }
                ?: results.firstOrNull { it.name.equals(query, ignoreCase = true) })?.id
        }.onFailure { if (it is CancellationException) throw it }
        if (result.isSuccess) companyCache[key] = Optional(result.getOrNull())
        return result.getOrNull()
    }

    // ------------------------------------------------------------------ rows

    /** The brand's TV shows: its TMDB network's most popular, else the shows in its guide. */
    suspend fun tvShows(brand: ChannelBrand, ids: ChannelBrandIds, epgSeries: List<String>, title: String): CatalogRow? =
        withContext(Dispatchers.IO) {
            val cacheKey = "${brand.key}:tv:${ids.networks.joinToString(",")}:${epgSeries.size}"
            val items = rowCache[cacheKey] ?: run {
                val language = language()
                val fromNetwork = if (ids.networks.isNotEmpty()) {
                    discover(isTv = true, networks = ids.networks.joinToString("|"), companies = null, language = language)
                } else {
                    emptyList()
                }
                val list = fromNetwork.ifEmpty {
                    coroutineScope {
                        epgSeries.map { show -> async { searchExact(show, year = null, isTv = true, language = language) } }
                            .awaitAll()
                            .filterNotNull()
                    }
                }.distinctBy { it.id }.take(ROW_ITEM_CAP)
                list.also { if (it.isNotEmpty()) rowCache[cacheKey] = it }
            }
            if (items.isEmpty()) null else buildRow("channel_${brand.key}_tv", title, items)
        }

    /** The brand's movies: what its channels show in the guide, then its studio's films. */
    suspend fun movies(brand: ChannelBrand, ids: ChannelBrandIds, epgMovies: List<EpgMovieTitle>, title: String): CatalogRow? =
        withContext(Dispatchers.IO) {
            val cacheKey = "${brand.key}:movies:${ids.companies.joinToString(",")}:${epgMovies.size}"
            val items = rowCache[cacheKey] ?: run {
                val language = language()
                val (fromGuide, fromCompanies) = coroutineScope {
                    val guide = async {
                        epgMovies.map { movie -> async { searchExact(movie.title, movie.year, isTv = false, language = language) } }
                            .awaitAll()
                            .filterNotNull()
                    }
                    val studio = async {
                        if (ids.companies.isEmpty()) emptyList()
                        else discover(isTv = false, networks = null, companies = ids.companies.joinToString("|"), language = language)
                    }
                    guide.await() to studio.await()
                }
                interleave(listOf(fromGuide, fromCompanies)).also { if (it.isNotEmpty()) rowCache[cacheKey] = it }
            }
            if (items.isEmpty()) null else buildRow("channel_${brand.key}_movies", title, items)
        }

    private suspend fun discover(isTv: Boolean, networks: String?, companies: String?, language: String): List<MetaPreview> =
        runCatching {
            val response = if (isTv) {
                tmdbApi.discoverTv(
                    apiKey = apiKey,
                    language = language,
                    page = 1,
                    sortBy = "popularity.desc",
                    withNetworks = networks,
                    withCompanies = companies,
                    voteCountGte = 10
                ).body()
            } else {
                tmdbApi.discoverMovies(
                    apiKey = apiKey,
                    language = language,
                    page = 1,
                    sortBy = "popularity.desc",
                    withCompanies = companies,
                    voteCountGte = 10
                ).body()
            }
            response?.results.orEmpty().mapNotNull { it.toPreview(isTv) }
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "Discover failed", it)
        }.getOrDefault(emptyList())

    /** A guide title to its TMDB entry, only when the names match (so "NBA Basketball" finds nothing). */
    private suspend fun searchExact(title: String, year: Int?, isTv: Boolean, language: String): MetaPreview? {
        val key = "${if (isTv) "tv" else "movie"}:${normalizeTitle(title)}:$year:$language"
        searchCache[key]?.let { return it.value }
        val result = lookupLimiter.withPermit {
            runCatching {
                val results = if (isTv) {
                    tmdbApi.searchTv(apiKey, title, language, firstAirDateYear = year).body()
                } else {
                    tmdbApi.searchMovies(apiKey, title, language, primaryReleaseYear = year).body()
                }?.results.orEmpty()
                val wanted = normalizeTitle(title)
                results
                    .filter { result ->
                        normalizeTitle(result.title ?: result.name ?: "") == wanted ||
                            normalizeTitle(result.originalTitle ?: result.originalName ?: "") == wanted
                    }
                    .maxByOrNull { (it.voteCount ?: 0) }
                    ?.toPreview(isTv)
            }.onFailure { if (it is CancellationException) throw it }
        }
        if (result.isSuccess) searchCache[key] = Optional(result.getOrNull())
        return result.getOrNull()
    }

    // ------------------------------------------------------------------ continue watching

    /**
     * True when a title someone is watching is the brand's: TMDB lists one of its networks (a show)
     * or one of its companies (a movie), or the title is on the page's own rows.
     */
    suspend fun belongsToBrand(
        contentId: String,
        contentType: String,
        brand: ChannelBrand,
        ids: ChannelBrandIds,
        rowIds: Set<String>
    ): Boolean {
        val info = titleInfo(contentId, contentType) ?: return false
        if ("tmdb:${info.tmdbId}" in rowIds) return true
        if (info.isTv) {
            return info.networkIds.any { it in ids.networks } ||
                info.networkNames.any { ChannelBrands.isBrandNetwork(brand, it) }
        }
        if (info.companyIds.any { it in ids.companies }) return true
        return info.companyNames.any { name ->
            val lower = name.lowercase(Locale.US)
            brand.companyFragments.any { lower.contains(it) }
        }
    }

    private suspend fun titleInfo(contentId: String, contentType: String): TitleInfo? = withContext(Dispatchers.IO) {
        val isTv = contentType.equals("series", true) || contentType.equals("tv", true)
        val cacheKey = "${if (isTv) "tv" else "movie"}:$contentId"
        titleInfoCache[cacheKey]?.let { return@withContext it.value }
        val info = lookupLimiter.withPermit {
            runCatching {
                val tmdbId = tmdbService.ensureTmdbId(contentId, if (isTv) "tv" else "movie")?.toIntOrNull()
                    ?: return@runCatching null
                val details = if (isTv) tmdbApi.getTvDetails(tmdbId, apiKey).body() else tmdbApi.getMovieDetails(tmdbId, apiKey).body()
                details?.let {
                    val networks = it.networks.orEmpty().filter { network -> network.id != null }
                    val companies = it.productionCompanies.orEmpty().filter { company -> company.id != null }
                    TitleInfo(
                        tmdbId = tmdbId,
                        isTv = isTv,
                        networkIds = networks.mapNotNull { network -> network.id },
                        networkNames = networks.map { network -> network.name.orEmpty() },
                        companyIds = companies.mapNotNull { company -> company.id },
                        companyNames = companies.map { company -> company.name.orEmpty() }
                    )
                }
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        }
        // Only a real answer is cached; a failed lookup is retried next time.
        if (info != null) titleInfoCache[cacheKey] = Optional(info)
        info
    }

    // ------------------------------------------------------------------ hero

    /** Featured slides: the first titles with a backdrop, each with its title logo when TMDB has one. */
    suspend fun heroItems(items: List<MetaPreview>): List<MetaPreview> = withContext(Dispatchers.IO) {
        val picks = items.filter { !it.background.isNullOrBlank() }.take(HERO_ITEM_COUNT)
        coroutineScope { picks.map { item -> async { item.copy(logo = item.logo ?: logoFor(item)) } }.awaitAll() }
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
        if (result.isSuccess) logoCache[key] = Optional(result.getOrNull())
        return result.getOrNull()
    }

    // ------------------------------------------------------------------ helpers

    private fun normalizeTitle(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.US)
            .replace("&", "and")
            .replace(Regex("[^a-z0-9]+"), "")

    private fun interleave(lists: List<List<MetaPreview>>): List<MetaPreview> {
        val seen = HashSet<String>()
        val out = ArrayList<MetaPreview>()
        val maxSize = lists.maxOfOrNull { it.size } ?: 0
        for (index in 0 until maxSize) {
            for (list in lists) {
                val item = list.getOrNull(index) ?: continue
                if (seen.add(item.id)) out += item
                if (out.size >= ROW_ITEM_CAP) return out
            }
        }
        return out
    }

    private fun buildRow(catalogId: String, title: String, items: List<MetaPreview>): CatalogRow {
        val allSeries = items.all { it.type == ContentType.SERIES }
        return CatalogRow(
            addonId = "tmdb",
            addonName = "TMDB",
            addonBaseUrl = "",
            catalogId = catalogId,
            catalogName = title,
            type = if (allSeries) ContentType.SERIES else ContentType.MOVIE,
            rawType = if (allSeries) "series" else "movie",
            items = items,
            isLoading = false,
            hasMore = false,
            currentPage = 1,
            supportsSkip = false,
            skipStep = 20
        )
    }

    private fun TmdbDiscoverResult.toPreview(isTv: Boolean): MetaPreview? {
        val name = title?.takeIf { it.isNotBlank() }
            ?: this.name?.takeIf { it.isNotBlank() }
            ?: originalTitle?.takeIf { it.isNotBlank() }
            ?: originalName?.takeIf { it.isNotBlank() }
            ?: return null
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
