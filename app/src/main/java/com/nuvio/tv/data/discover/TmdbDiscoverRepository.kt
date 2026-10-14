package com.nuvio.tv.data.discover

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Discover page's "Category" menu. [ALL] is every title TMDB has, with nothing left out (no
 * minimum votes, no date limit): the best-known titles of all time first, then everything else.
 */
enum class DiscoverCategory { POPULAR, TRENDING, NEW, ALL }

/** The Discover page's "Type" menu. */
enum class DiscoverMediaFilter { ALL, MOVIES, SHOWS }

/**
 * One choice in the Discover page's "Genre" menu. Movies and TV shows number some genres differently
 * on TMDB (Action is 28 for movies, "Action & Adventure" 10759 for shows), so a genre carries both ids;
 * null means that kind of title doesn't have it (there's no Horror for TV shows on TMDB).
 */
data class DiscoverGenre(
    val label: String,
    val movieId: Int?,
    val tvId: Int?,
    /**
     * TMDB's names for it, lowercase ("action", and "action & adventure" for shows): how titles that
     * didn't come from a TMDB query (hand-picked, franchise movies) are matched to it.
     */
    val names: Set<String> = emptySet()
)

/** One page of results for one kind of title. [lastPage] is how far TMDB lets the page number go. */
data class DiscoverPage(
    val items: List<MetaPreview>,
    val lastPage: Int
)

/**
 * The Discover page's titles, straight from TMDB's whole catalog: every movie and show TMDB knows,
 * by popularity, what's trending this week, or newest first, with Genre and Year filters.
 */
@Singleton
class TmdbDiscoverRepository @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore
) {
    private val apiKey get() = BuildConfig.TMDB_API_KEY
    private val genreCache = ConcurrentHashMap<String, List<Pair<Int, String>>>()

    suspend fun language(): String =
        runCatching { tmdbSettingsDataStore.settings.first().language }.getOrNull()?.ifBlank { null } ?: "en"

    /** The Genre menu for a Type: movie genres, show genres, or both merged ("Action" covers both). */
    suspend fun genres(filter: DiscoverMediaFilter, language: String): List<DiscoverGenre> = withContext(Dispatchers.IO) {
        val (movie, tv) = coroutineScope {
            val m = async { genreList(TmdbCollectionMediaType.MOVIE, language) }
            val t = async { genreList(TmdbCollectionMediaType.TV, language) }
            m.await() to t.await()
        }
        when (filter) {
            DiscoverMediaFilter.MOVIES -> movie.map { (id, name) -> DiscoverGenre(name, id, null, setOf(name.genreKey())) }
            DiscoverMediaFilter.SHOWS -> tv.map { (id, name) -> DiscoverGenre(name, null, id, setOf(name.genreKey())) }
            DiscoverMediaFilter.ALL -> {
                val tvNames: Map<Int, String> = tv.toMap()
                val usedTv = HashSet<Int>()
                val merged = movie.map { (id, name) ->
                    val tvMatch = when {
                        id in tvNames -> id
                        else -> MOVIE_TO_TV_GENRE[id]?.takeIf { it in tvNames }
                    }
                    if (tvMatch != null) usedTv += tvMatch
                    DiscoverGenre(name, id, tvMatch, setOfNotNull(name.genreKey(), tvMatch?.let { tvNames[it]?.genreKey() }))
                }
                // Show-only genres (Kids, News, Reality, Soap, Talk) that no movie genre stands for.
                val showOnly = tv.filter { (id, _) -> id !in usedTv && id !in MOVIE_TO_TV_GENRE.values }
                    .map { (id, name) -> DiscoverGenre(name, null, id, setOf(name.genreKey())) }
                (merged + showOnly).sortedBy { it.label.lowercase() }
            }
        }
    }

    /**
     * Every [mediaType] title for the menus, with no limit (Popular, New, All): TMDB's whole catalog,
     * past its 10,000-title cap (see [TmdbDeepPager]). Null for Trending, which TMDB keeps short.
     */
    fun deepPager(
        mediaType: TmdbCollectionMediaType,
        category: DiscoverCategory,
        genreId: Int?,
        year: Int?,
        language: String
    ): TmdbDeepPager<MetaPreview>? {
        if (category == DiscoverCategory.TRENDING) return null
        val today = LocalDate.now()
        val from = year?.let { LocalDate.of(it, 1, 1) }
        val yearEnd = year?.let { LocalDate.of(it, 12, 31) }
        // New: released up to today, newest first.
        val to = if (category == DiscoverCategory.NEW) listOfNotNull(yearEnd, today).minOrNull() else yearEnd
        return TmdbDeepPager(
            from = from,
            to = to,
            dateOrder = if (category == DiscoverCategory.NEW) TmdbDeepPager.DateOrder.NEWEST_FIRST else TmdbDeepPager.DateOrder.NONE,
            idOf = { it.id }
        ) { page, windowFrom, windowTo ->
            val result = discoverWindow(mediaType, category, genreId, page, language, windowFrom, windowTo)
            TmdbDeepPager.DeepPage(result.items, result.lastPage)
        }
    }

    /** One page of the menus' query for [mediaType] within the given release dates (null: any). */
    private suspend fun discoverWindow(
        mediaType: TmdbCollectionMediaType,
        category: DiscoverCategory,
        genreId: Int?,
        page: Int,
        language: String,
        from: LocalDate?,
        to: LocalDate?
    ): DiscoverPage = withContext(Dispatchers.IO) {
        val isTv = mediaType == TmdbCollectionMediaType.TV
        val isNew = category == DiscoverCategory.NEW
        val isAll = category == DiscoverCategory.ALL
        val response = if (isTv) {
            tmdbApi.discoverTv(
                apiKey = apiKey,
                language = language,
                page = page,
                sortBy = when {
                    isNew -> "first_air_date.desc"
                    isAll -> "vote_count.desc"
                    else -> "popularity.desc"
                },
                withGenres = genreId?.toString(),
                firstAirDateGte = from?.toString(),
                firstAirDateLte = to?.toString(),
                // Newest first: skip shows no one has rated yet (thousands of unknown uploads).
                voteCountGte = if (isNew) NEW_MIN_VOTES_TV else null
            )
        } else {
            tmdbApi.discoverMovies(
                apiKey = apiKey,
                language = language,
                page = page,
                sortBy = when {
                    isNew -> "primary_release_date.desc"
                    isAll -> "vote_count.desc"
                    else -> "popularity.desc"
                },
                withGenres = genreId?.toString(),
                releaseDateGte = from?.toString(),
                releaseDateLte = to?.toString(),
                voteCountGte = if (isNew) NEW_MIN_VOTES_MOVIE else null
            )
        }
        if (!response.isSuccessful) throw IOException("TMDB discover HTTP ${response.code()}")
        val body = response.body()
        val names = genreList(mediaType, language).toMap()
        DiscoverPage(
            items = body?.results.orEmpty().mapNotNull { it.toPreview(mediaType, names) },
            lastPage = body?.totalPages ?: page
        )
    }

    /**
     * One page of [mediaType] titles. Trending can't be filtered by TMDB, so its genre and year are
     * applied to each page here (a filtered page can come back short or empty; the caller goes on).
     */
    suspend fun page(
        mediaType: TmdbCollectionMediaType,
        category: DiscoverCategory,
        genreId: Int?,
        year: Int?,
        page: Int,
        language: String
    ): DiscoverPage = withContext(Dispatchers.IO) {
        val isTv = mediaType == TmdbCollectionMediaType.TV
        val today = LocalDate.now().toString()
        val response = when (category) {
            DiscoverCategory.TRENDING -> tmdbApi.getTrending(
                mediaType = mediaType.value,
                timeWindow = "week",
                apiKey = apiKey,
                language = language,
                page = page
            )
            DiscoverCategory.POPULAR, DiscoverCategory.NEW, DiscoverCategory.ALL -> {
                val isNew = category == DiscoverCategory.NEW
                val isAll = category == DiscoverCategory.ALL
                if (isTv) {
                    tmdbApi.discoverTv(
                        apiKey = apiKey,
                        language = language,
                        page = page,
                        sortBy = when {
                            isNew -> "first_air_date.desc"
                            isAll -> "vote_count.desc"
                            else -> "popularity.desc"
                        },
                        withGenres = genreId?.toString(),
                        firstAirDateYear = year,
                        firstAirDateLte = if (isNew) today else null,
                        // Newest first: skip shows no one has rated yet (thousands of unknown uploads).
                        voteCountGte = if (isNew) NEW_MIN_VOTES_TV else null
                    )
                } else {
                    tmdbApi.discoverMovies(
                        apiKey = apiKey,
                        language = language,
                        page = page,
                        sortBy = when {
                            isNew -> "primary_release_date.desc"
                            isAll -> "vote_count.desc"
                            else -> "popularity.desc"
                        },
                        withGenres = genreId?.toString(),
                        year = year,
                        releaseDateLte = if (isNew) today else null,
                        voteCountGte = if (isNew) NEW_MIN_VOTES_MOVIE else null
                    )
                }
            }
        }
        if (!response.isSuccessful) throw IOException("TMDB discover HTTP ${response.code()}")
        val body = response.body()
        val names = genreList(mediaType, language).toMap()
        val results = body?.results.orEmpty().let { list ->
            if (category != DiscoverCategory.TRENDING) list
            else list.filter { result ->
                (genreId == null || result.genreIds.orEmpty().contains(genreId)) &&
                    (year == null || (if (isTv) result.firstAirDate else result.releaseDate)?.take(4) == year.toString())
            }
        }
        DiscoverPage(
            items = results.mapNotNull { it.toPreview(mediaType, names) },
            lastPage = (body?.totalPages ?: page).coerceAtMost(TMDB_MAX_PAGE)
        )
    }

    private suspend fun genreList(mediaType: TmdbCollectionMediaType, language: String): List<Pair<Int, String>> {
        val key = "${mediaType.value}:$language"
        genreCache[key]?.let { return it }
        val list: List<Pair<Int, String>> = runCatching {
            val response = when (mediaType) {
                TmdbCollectionMediaType.MOVIE -> tmdbApi.getMovieGenres(apiKey, language)
                TmdbCollectionMediaType.TV -> tmdbApi.getTvGenres(apiKey, language)
            }
            response.body()?.genres.orEmpty().map { it.id to it.name }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull().orEmpty()
        if (list.isNotEmpty()) genreCache[key] = list
        return list
    }

    private fun TmdbDiscoverResult.toPreview(mediaType: TmdbCollectionMediaType, genreNames: Map<Int, String>): MetaPreview? {
        val name = title?.takeIf { it.isNotBlank() }
            ?: this.name?.takeIf { it.isNotBlank() }
            ?: originalTitle?.takeIf { it.isNotBlank() }
            ?: originalName?.takeIf { it.isNotBlank() }
            ?: return null
        // Every title, even one with no picture yet (its card shows the name).
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
            landscapePoster = imageUrl(backdropPath, "w780"),
            logo = null,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = date?.take(4)?.takeIf { it.isNotBlank() },
            released = date?.takeIf { it.isNotBlank() },
            imdbRating = voteAverage?.toFloat()?.takeIf { it > 0f },
            voteCount = voteCount,
            genres = genreIds.orEmpty().mapNotNull { genreNames[it] }
        )
    }

    private fun imageUrl(path: String?, size: String): String? =
        path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }

    private fun String.genreKey(): String = trim().lowercase(Locale.ROOT)

    companion object {
        /** TMDB stops paging at 500 (20 titles a page). */
        const val TMDB_MAX_PAGE = 500
        private const val NEW_MIN_VOTES_MOVIE = 5
        private const val NEW_MIN_VOTES_TV = 3

        /** Movie genres whose TV counterpart has another id on TMDB. */
        private val MOVIE_TO_TV_GENRE = mapOf(
            28 to 10759,    // Action -> Action & Adventure
            12 to 10759,    // Adventure -> Action & Adventure
            878 to 10765,   // Science Fiction -> Sci-Fi & Fantasy
            14 to 10765,    // Fantasy -> Sci-Fi & Fantasy
            10752 to 10768  // War -> War & Politics
        )
    }
}
