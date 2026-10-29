package com.nuvio.tv.data.channelprofile

import com.nuvio.tv.core.util.FastText
import android.content.Context
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.discover.TmdbDeepPager
import com.nuvio.tv.data.discover.TmdbSeeAllFilter
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.ui.screens.channelprofile.ChannelBrand
import com.nuvio.tv.ui.screens.channelprofile.ChannelBrands
import dagger.hilt.android.qualifiers.ApplicationContext
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
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ChannelProfileRepo"
private const val ROW_ITEM_CAP = 30
/** Titles a channel's TV Shows / Movies row shows before its "See All" card. */
const val CHANNEL_ROW_VISIBLE = 10
/** TMDB's discover stops at page 500, so a "See All" can go that far (every title TMDB lists). */
private const val TMDB_MAX_PAGE = 500
private const val HERO_ITEM_COUNT = 5
/** The See All Year menu never goes back further than this. */
private const val OLDEST_YEAR = 1900
/**
 * Shows from a channel family's guide looked up to find more of its TMDB networks (a sister channel
 * like Investigation Discovery or Nick Jr.), or the network itself when it isn't a known one.
 */
private const val NETWORK_LOOKUP_TITLES = 12

/** A movie seen in a channel's guide: its title and, when the guide gives one, its year. */
data class EpgMovieTitle(val title: String, val year: Int?)

/** The brand's TMDB ids: networks (its shows) and production companies (its movies). */
data class ChannelBrandIds(val networks: List<Int>, val companies: List<Int>)

/** One page of a channel's "See All": its titles, and the next TMDB page (null when there's no more). */
data class ChannelTitlesPage(val items: List<MetaPreview>, val nextPage: Int?)

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
    @ApplicationContext private val context: Context,
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val titleArt: com.nuvio.tv.core.tmdb.TmdbTitleArtService
) {
    private val apiKey get() = BuildConfig.TMDB_API_KEY

    /** For cards that find their title art as they appear (rows and See All). */
    val titleArtResolver: com.nuvio.tv.domain.repository.TitleArtResolver get() = titleArt

    private data class Optional<T>(val value: T?)

    private val idsCache = ConcurrentHashMap<String, ChannelBrandIds>()
    private val idsLocks = ConcurrentHashMap<String, Mutex>()
    private val networkNameCache = ConcurrentHashMap<Int, Optional<String>>()
    private val companyCache = ConcurrentHashMap<String, Optional<Int>>()
    private val searchCache = ConcurrentHashMap<String, Optional<MetaPreview>>()
    private val titleInfoCache = ConcurrentHashMap<String, Optional<TitleInfo>>()
    /** A row's titles, and whether TMDB has more pages of them (for "See All"). */
    private data class RowContent(val items: List<MetaPreview>, val morePages: Boolean)

    private val rowCache = ConcurrentHashMap<String, RowContent>()
    /** TMDB's total for a See All query with a filter (the filter menu's counts). */
    private val totalCache = ConcurrentHashMap<String, Int>()
    /** The year of a network's (or studio's) oldest title on TMDB (0: none). */
    private val earliestYearCache = ConcurrentHashMap<String, Int>()
    private val countLimiter = Semaphore(4)

    private val lookupLimiter = Semaphore(6)

    /** TMDB genre names by id, per "tv"/"movie" and language (for the See All genre filter). */
    private val genreNames = ConcurrentHashMap<String, Map<Int, String>>()

    private suspend fun genreMap(isTv: Boolean, language: String): Map<Int, String> {
        val key = "${if (isTv) "tv" else "movie"}:$language"
        genreNames[key]?.let { return it }
        val map = runCatching {
            val response = if (isTv) tmdbApi.getTvGenres(apiKey, language) else tmdbApi.getMovieGenres(apiKey, language)
            response.body()?.genres.orEmpty().associate { it.id to it.name }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull().orEmpty()
        if (map.isNotEmpty()) genreNames[key] = map
        return map
    }

    private suspend fun language(): String =
        runCatching { tmdbSettingsDataStore.settings.first().language }.getOrNull()?.ifBlank { null } ?: "en"

    // ------------------------------------------------------------------ brand ids

    /**
     * The brand's TMDB networks and companies: those the channel list (assets/channel-tmdb.csv)
     * gives its channels, known network ids TMDB confirms by name, and the networks of shows in its
     * guide whose TMDB network has one of the family's names (so sister channels count too). Its
     * movies come from its companies; a brand without any uses the TMDB company with its own name.
     */
    suspend fun brandIds(
        brand: ChannelBrand,
        epgSeries: List<String>,
        channelGuideIds: List<String> = emptyList(),
        channelNames: List<String> = emptyList(),
        /**
         * False for a quick first answer (known and listed ids only, so the page shows right away); the
         * full answer also searches the guide for sister channels' networks. A brand with no known ids
         * always searches the guide.
         */
        withGuide: Boolean = true
    ): ChannelBrandIds = withContext(Dispatchers.IO) {
        idsCache[brand.key]?.let { return@withContext it }
        if (!withGuide) {
            val quick = knownIds(brand, channelGuideIds, channelNames)
            if (quick.networks.isNotEmpty()) return@withContext quick
        }
        idsLocks.getOrPut(brand.key) { Mutex() }.withLock {
            idsCache[brand.key]?.let { return@withLock it }
            val known = knownIds(brand, channelGuideIds, channelNames)
            val networks = (known.networks + networksFromGuide(brand, epgSeries)).distinct()
            val ids = ChannelBrandIds(networks, known.companies)
            // Keep the answer once the guide was part of it (it adds the sister channels' networks);
            // before the guide has loaded, or after a network hiccup, it's worked out again next time.
            if (epgSeries.isNotEmpty() && (ids.networks.isNotEmpty() || ids.companies.isNotEmpty() || brand.tmdbNetworks.isEmpty())) {
                idsCache[brand.key] = ids
            }
            ids
        }
    }

    /** The ids known without searching the guide: confirmed network ids, the channel list's ids, studios. */
    private suspend fun knownIds(brand: ChannelBrand, channelGuideIds: List<String>, channelNames: List<String>): ChannelBrandIds =
        coroutineScope {
            val verified = async {
                brand.tmdbNetworks.map { id -> async { id.takeIf { ChannelBrands.isBrandNetwork(brand, networkName(id)) } } }
                    .awaitAll()
                    .filterNotNull()
            }
            val companies = async {
                brand.companyQueries.ifEmpty { listOf(brand.name) }
                    .map { query -> async { companyId(query) } }.awaitAll().filterNotNull()
            }
            ChannelTmdbMap.ensureLoaded(context)
            val listed = ChannelTmdbMap.idsFor(channelGuideIds, channelNames)
            ChannelBrandIds(
                networks = (listed.networks + verified.await()).distinct(),
                companies = (listed.companies + companies.await()).distinct()
            )
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
            val content = rowCache[cacheKey] ?: run {
                val language = language()
                val fromNetwork = if (ids.networks.isNotEmpty()) {
                    discover(isTv = true, networks = ids.networks.joinToString("|"), companies = null, language = language, page = 1)
                } else {
                    DiscoverResult(emptyList(), 0)
                }
                val list = fromNetwork.items.ifEmpty {
                    coroutineScope {
                        epgSeries.map { show -> async { searchExact(show, year = null, isTv = true, language = language) } }
                            .awaitAll()
                            .filterNotNull()
                    }
                }.distinctBy { it.id }.take(ROW_ITEM_CAP)
                RowContent(list, morePages = fromNetwork.items.isNotEmpty() && fromNetwork.totalPages > 1)
                    .also { if (it.items.isNotEmpty()) rowCache[cacheKey] = it }
            }
            if (content.items.isEmpty()) null
            else buildRow("channel_${brand.key}_tv", title, content.items, hasMore = content.morePages)
        }

    /** The brand's movies: what its channels show in the guide, then its studio's films. */
    suspend fun movies(brand: ChannelBrand, ids: ChannelBrandIds, epgMovies: List<EpgMovieTitle>, title: String): CatalogRow? =
        withContext(Dispatchers.IO) {
            val cacheKey = "${brand.key}:movies:${ids.companies.joinToString(",")}:${epgMovies.size}"
            val content = rowCache[cacheKey] ?: run {
                val language = language()
                val (fromGuide, fromCompanies) = coroutineScope {
                    val guide = async {
                        epgMovies.map { movie -> async { searchExact(movie.title, movie.year, isTv = false, language = language) } }
                            .awaitAll()
                            .filterNotNull()
                    }
                    val studio = async {
                        if (ids.companies.isEmpty()) DiscoverResult(emptyList(), 0)
                        else discover(isTv = false, networks = null, companies = ids.companies.joinToString("|"), language = language, page = 1)
                    }
                    guide.await() to studio.await()
                }
                RowContent(
                    items = interleave(listOf(fromGuide, fromCompanies.items)),
                    morePages = fromCompanies.totalPages > 1
                ).also { if (it.items.isNotEmpty()) rowCache[cacheKey] = it }
            }
            if (content.items.isEmpty()) null
            else buildRow("channel_${brand.key}_movies", title, content.items, hasMore = content.morePages)
        }

    /**
     * A page of a channel's "See All" (TV shows or movies): TMDB page [page] of its network's (or
     * studio's) titles. Unfiltered, page 1 comes from the row itself, so this starts at page 2.
     * [filter] (Genre / Year) is applied by TMDB, so it finds every matching title it lists.
     */
    suspend fun seeAllPage(
        isTv: Boolean,
        ids: ChannelBrandIds,
        page: Int,
        loadedCount: Int,
        filter: TmdbSeeAllFilter = TmdbSeeAllFilter()
    ): ChannelTitlesPage =
        withContext(Dispatchers.IO) {
            val query = seeAllQuery(isTv, ids, filter)
            if (query == null || page > TMDB_MAX_PAGE) return@withContext ChannelTitlesPage(emptyList(), null)
            val result = discover(
                isTv = isTv,
                networks = query.networks,
                companies = query.companies,
                language = language(),
                page = page,
                failOnError = true,
                genres = query.genres,
                dateGte = query.dateGte,
                dateLte = query.dateLte
            )
            // Every page TMDB has (up to its own limit), so See All lists the whole network or studio.
            val next = if (page < minOf(result.totalPages, TMDB_MAX_PAGE)) page + 1 else null
            ChannelTitlesPage(result.items, next)
        }

    /**
     * Every show (or movie) of the channel's networks (or studios) for [filter], with no limit: past
     * TMDB's 10,000-title cap the list goes on a stretch of time at a time (see [TmdbDeepPager]).
     * Null when the channel has no TMDB network (or studio) for that kind of title.
     */
    fun seeAllPager(isTv: Boolean, ids: ChannelBrandIds, filter: TmdbSeeAllFilter): TmdbDeepPager<MetaPreview>? {
        val query = seeAllQuery(isTv, ids, filter) ?: return null
        return TmdbDeepPager(
            from = query.dateGte?.let { LocalDate.parse(it) },
            to = query.dateLte?.let { LocalDate.parse(it) },
            dateOrder = TmdbDeepPager.DateOrder.NONE,
            idOf = { it.id }
        ) { page, from, to ->
            val language = language()
            val body = withContext(Dispatchers.IO) {
                discoverResponse(
                    isTv, query.networks, query.companies, language, page, query.genres,
                    from?.toString(), to?.toString(), "popularity.desc"
                )
            }
            val genres = genreMap(isTv, language)
            TmdbDeepPager.DeepPage(
                items = body?.results.orEmpty().mapNotNull { it.toPreview(isTv, genres) },
                totalPages = body?.totalPages ?: 0
            )
        }
    }

    /** True when the channel's See All of shows (or movies) comes from TMDB, not just from its guide. */
    fun hasSeeAllCatalog(isTv: Boolean, ids: ChannelBrandIds): Boolean =
        if (isTv) ids.networks.isNotEmpty() else ids.companies.isNotEmpty()

    /** TMDB's total of the channel's shows (or movies) for [filter]; null when TMDB couldn't be reached. */
    suspend fun seeAllCount(isTv: Boolean, ids: ChannelBrandIds, filter: TmdbSeeAllFilter): Int? =
        withContext(Dispatchers.IO) {
            val query = seeAllQuery(isTv, ids, filter) ?: return@withContext 0
            val key = "${if (isTv) "tv" else "movie"}:$query"
            totalCache[key]?.let { return@withContext it }
            val total: Int? = countLimiter.withPermit {
                runCatching {
                    discoverResponse(isTv, query.networks, query.companies, language(), 1, query.genres, query.dateGte, query.dateLte, "popularity.desc")
                        ?.totalResults ?: 0
                }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            }
            if (total != null) totalCache[key] = total
            total
        }

    /** The years of the channel's shows (or movies) on TMDB, newest first: from its oldest to next year. */
    suspend fun seeAllYears(isTv: Boolean, ids: ChannelBrandIds): List<Int> = withContext(Dispatchers.IO) {
        val query = seeAllQuery(isTv, ids, TmdbSeeAllFilter()) ?: return@withContext emptyList()
        val key = "${if (isTv) "tv" else "movie"}:$query"
        val earliest: Int? = earliestYearCache[key] ?: countLimiter.withPermit {
            runCatching {
                val results = discoverResponse(
                    isTv, query.networks, query.companies, language(), 1,
                    genres = null,
                    // Titles with no date at all would otherwise come first.
                    dateGte = "1870-01-01",
                    dateLte = null,
                    sortBy = if (isTv) "first_air_date.asc" else "primary_release_date.asc"
                )?.results.orEmpty()
                results.mapNotNull { (if (isTv) it.firstAirDate else it.releaseDate)?.take(4)?.toIntOrNull() }.minOrNull() ?: 0
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        }?.also { earliestYearCache[key] = it }
        val last = LocalDate.now().year + 1
        val first = earliest?.takeIf { it > 0 }?.coerceIn(OLDEST_YEAR, last) ?: OLDEST_YEAR
        (last downTo first).toList()
    }

    /** The channel's network (or studio) query with the filter's genre and year in it; null: none or no match. */
    private data class SeeAllQuery(
        val networks: String?,
        val companies: String?,
        val genres: String?,
        val dateGte: String?,
        val dateLte: String?
    )

    private fun seeAllQuery(isTv: Boolean, ids: ChannelBrandIds, filter: TmdbSeeAllFilter): SeeAllQuery? {
        val list = if (isTv) ids.networks else ids.companies
        if (list.isEmpty()) return null
        val mediaType = if (isTv) TmdbCollectionMediaType.TV else TmdbCollectionMediaType.MOVIE
        if (filter.mediaType != null && filter.mediaType != mediaType) return null
        val genre = if (filter.genre == null) null else (filter.genreId(mediaType) ?: return null)
        return SeeAllQuery(
            networks = if (isTv) list.joinToString("|") else null,
            companies = if (isTv) null else list.joinToString("|"),
            genres = genre?.toString(),
            dateGte = filter.year?.let { "$it-01-01" },
            dateLte = filter.year?.let { "$it-12-31" }
        )
    }

    /**
     * The same titles with their title art for the horizontal cards: TMDB's backdrop with the title on
     * it when there is one, otherwise the title logo.
     */
    suspend fun withLogos(items: List<MetaPreview>): List<MetaPreview> = titleArt.withTitleArt(items)

    private data class DiscoverResult(val items: List<MetaPreview>, val totalPages: Int)

    /**
     * One page of a network's shows or a studio's movies, most popular first. No minimum vote count:
     * every title TMDB lists for the network or company is included (a 10-vote minimum used to cut
     * Animal Planet's 158 shows down to about 20).
     */
    private suspend fun discover(
        isTv: Boolean,
        networks: String?,
        companies: String?,
        language: String,
        page: Int,
        /** See All: a page TMDB couldn't give (busy, offline) is an error, so it's tried again, not the end. */
        failOnError: Boolean = false,
        /** See All filter: TMDB genre id(s) and first air / release date range. */
        genres: String? = null,
        dateGte: String? = null,
        dateLte: String? = null
    ): DiscoverResult =
        runCatching {
            val response = discoverResponse(isTv, networks, companies, language, page, genres, dateGte, dateLte, "popularity.desc")
            val genreNames = genreMap(isTv, language)
            DiscoverResult(
                items = response?.results.orEmpty().mapNotNull { it.toPreview(isTv, genreNames) },
                totalPages = response?.totalPages ?: 1
            )
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "Discover failed", it)
            if (failOnError) throw it
        }.getOrDefault(DiscoverResult(emptyList(), 0))

    private suspend fun discoverResponse(
        isTv: Boolean,
        networks: String?,
        companies: String?,
        language: String,
        page: Int,
        genres: String?,
        dateGte: String?,
        dateLte: String?,
        sortBy: String
    ): com.nuvio.tv.data.remote.api.TmdbDiscoverResponse? {
        val raw = if (isTv) {
            tmdbApi.discoverTv(
                apiKey = apiKey,
                language = language,
                page = page,
                sortBy = sortBy,
                withNetworks = networks,
                withCompanies = companies,
                withGenres = genres,
                firstAirDateGte = dateGte,
                firstAirDateLte = dateLte
            )
        } else {
            tmdbApi.discoverMovies(
                apiKey = apiKey,
                language = language,
                page = page,
                sortBy = sortBy,
                withCompanies = companies,
                withGenres = genres,
                releaseDateGte = dateGte,
                releaseDateLte = dateLte
            )
        }
        if (!raw.isSuccessful) throw java.io.IOException("TMDB discover HTTP ${raw.code()}")
        return raw.body()
    }

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
                val genres = genreMap(isTv, language)
                results
                    .filter { result ->
                        normalizeTitle(result.title ?: result.name ?: "") == wanted ||
                            normalizeTitle(result.originalTitle ?: result.originalName ?: "") == wanted
                    }
                    .maxByOrNull { (it.voteCount ?: 0) }
                    ?.toPreview(isTv, genres)
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
    suspend fun heroItems(items: List<MetaPreview>): List<MetaPreview> =
        titleArt.withLogos(items.filter { !it.background.isNullOrBlank() }.take(HERO_ITEM_COUNT))

    // ------------------------------------------------------------------ helpers

    /** RCTV (MEM-07): without regular expressions (see [FastText]). */
    private fun normalizeTitle(value: String): String = FastText.asciiCompact(value)

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

    private fun buildRow(catalogId: String, title: String, items: List<MetaPreview>, hasMore: Boolean): CatalogRow {
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
            // More titles on TMDB than the row holds: the row ends with "See All".
            hasMore = hasMore || items.size > CHANNEL_ROW_VISIBLE,
            currentPage = 1,
            supportsSkip = false,
            skipStep = 20
        )
    }

    private fun TmdbDiscoverResult.toPreview(isTv: Boolean, genreNames: Map<Int, String> = emptyMap()): MetaPreview? {
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
            // Horizontal cards on the channel page: a smaller backdrop is plenty.
            landscapePoster = imageUrl(backdropPath, "w780"),
            logo = null,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = date?.take(4)?.takeIf { it.isNotBlank() },
            released = date?.takeIf { it.isNotBlank() },
            imdbRating = voteAverage?.toFloat()?.takeIf { it > 0f },
            voteCount = voteCount,
            // Names for the See All genre filter ("Comedy", "Drama").
            genres = genreIds.orEmpty().mapNotNull { genreNames[it] }
        )
    }

    private fun imageUrl(path: String?, size: String): String? =
        path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }
}
