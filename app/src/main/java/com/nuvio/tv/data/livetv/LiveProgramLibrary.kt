package com.nuvio.tv.data.livetv

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.providerId
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.data.remote.api.TmdbEpisode
import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** An episode of a series on TMDB: the one a guide program is airing. */
data class LiveEpisode(
    val season: Int,
    val episode: Int,
    val name: String?,
    val overview: String?,
    val still: String?,
    val airDate: String?
)

/** A season of a series on TMDB. */
data class LiveSeason(val number: Int, val name: String?, val episodeCount: Int)

/** A guide program's movie or series on TMDB (with the episode airing, when it could be told). */
data class LiveProgramMatch(
    val isMovie: Boolean,
    val tmdbId: Int,
    val name: String,
    val year: Int?,
    val poster: String?,
    val backdrop: String?,
    val overview: String?,
    val rating: Float?,
    val genres: List<String>,
    val episode: LiveEpisode? = null
) {
    val itemId: String get() = "tmdb:$tmdbId"
    val itemType: String get() = if (isMovie) "movie" else "series"
}

/** What adding a program to the library came to. */
enum class LiveLibraryResult { ADDED, ALREADY_IN_LIBRARY, ADDED_SERIES_INSTEAD, NOT_FOUND, FAILED }

/**
 * Links what's on in the Live TV guide to TMDB (the movie, or the series and the episode airing) and
 * puts it in the library, so it opens like any other title.
 */
@Singleton
class LiveProgramLibrary @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val libraryRepository: LibraryRepository,
    private val tmdbService: TmdbService,
    private val metaRepository: MetaRepository
) {
    private val apiKey get() = BuildConfig.TMDB_API_KEY
    private val limiter = Semaphore(4)

    private data class Optional<T>(val value: T?)
    private val matchCache = ConcurrentHashMap<String, Optional<LiveProgramMatch>>()
    private val seasonCache = ConcurrentHashMap<String, List<TmdbEpisode>>()

    private suspend fun language(): String =
        runCatching { tmdbSettingsDataStore.settings.first().language }.getOrNull()?.ifBlank { null } ?: "en"

    /**
     * The program's title on TMDB, matched by its exact name (so a game or a newscast finds nothing),
     * and for a series the episode airing: by its name in the guide, else by its air date. Null when
     * TMDB has no such title. Throws when TMDB can't be reached.
     */
    suspend fun match(program: EpgProgram): LiveProgramMatch? = withContext(Dispatchers.IO) {
        val language = language()
        val key = "${program.title}|${program.subtitle}|${program.startMs / DAY_MS}|$language"
        matchCache[key]?.let { return@withContext it.value }

        val rawTitle = cleanTitle(program.title)
        val year = TRAILING_YEAR.find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val title = rawTitle.replace(TRAILING_YEAR, "").trim()
        if (title.length < 2) return@withContext null

        val looksLikeMovie = program.categoryWords().any { "movie" in it || "film" in it }
        val found = if (looksLikeMovie) {
            search(title, year, isTv = false, language) ?: search(title, year, isTv = true, language)
        } else {
            search(title, year, isTv = true, language) ?: search(title, year, isTv = false, language)
        }
        val match = if (found != null && !found.isMovie) {
            found.copy(episode = runCatching { findEpisode(found.tmdbId, program, language) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull())
        } else {
            found
        }
        matchCache[key] = Optional(match)
        match
    }

    /**
     * The program's movie or series on TMDB, by its exact name, without looking for the episode:
     * what the guide shows its artwork from. Null when TMDB has no such title, when the title is a
     * generic one ("News", "Paid Programming"). Throws when TMDB can't be reached (nothing is
     * remembered then, so it's asked again next time).
     */
    suspend fun findTitle(program: EpgProgram): LiveProgramMatch? = withContext(Dispatchers.IO) {
        val rawTitle = cleanTitle(program.title)
        val year = TRAILING_YEAR.find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val title = rawTitle.replace(TRAILING_YEAR, "").trim()
        if (title.length < 2 || normalize(title) in GENERIC_TITLES) return@withContext null
        val looksLikeMovie = program.categoryWords().any { "movie" in it || "film" in it }
        val key = "${normalize(title)}|$year|$looksLikeMovie"
        titleCache[key]?.let { return@withContext it.value }
        // TMDB didn't answer for this title a moment ago: not asked again for every focus or open
        // (it's asked again in a minute; nothing is remembered as "not on TMDB").
        failedTitles[key]?.let { failedAt ->
            if (System.currentTimeMillis() - failedAt < FAILED_RETRY_MS) throw java.io.IOException("TMDB unavailable for now")
        }
        val language = language()
        val found = try {
            if (looksLikeMovie) {
                search(title, year, isTv = false, language) ?: search(title, year, isTv = true, language)
            } else {
                search(title, year, isTv = true, language) ?: search(title, year, isTv = false, language)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedTitles[key] = System.currentTimeMillis()
            throw e
        }
        failedTitles.remove(key)
        // Not on TMDB is remembered too: the same program isn't searched again.
        titleCache[key] = Optional(found)
        found
    }

    private val titleCache = ConcurrentHashMap<String, Optional<LiveProgramMatch>>()
    private val failedTitles = ConcurrentHashMap<String, Long>()
    private val seasonsCache = ConcurrentHashMap<String, List<LiveSeason>>()

    /**
     * A series' seasons on TMDB, in order (specials last). Throws when TMDB can't be reached.
     */
    suspend fun seasons(tvId: Int): List<LiveSeason> = withContext(Dispatchers.IO) {
        val language = language()
        val key = "$tvId:$language"
        seasonsCache[key]?.let { return@withContext it }
        val details = limiter.withPermit {
            val response = tmdbApi.getTvDetails(tvId, apiKey, language)
            if (!response.isSuccessful) throw java.io.IOException("TMDB details HTTP ${response.code()}")
            response.body()
        }
        val seasons = details?.seasons.orEmpty()
            .mapNotNull { season ->
                val number = season.seasonNumber ?: return@mapNotNull null
                LiveSeason(number = number, name = season.name?.takeIf { it.isNotBlank() }, episodeCount = season.episodeCount ?: 0)
            }
            .filter { it.episodeCount > 0 }
            .sortedBy { if (it.number == 0) Int.MAX_VALUE else it.number }
        if (seasons.isNotEmpty()) seasonsCache[key] = seasons
        seasons
    }

    /** A season's episodes on TMDB (empty when TMDB can't be reached). */
    suspend fun episodes(tvId: Int, season: Int): List<LiveEpisode> = withContext(Dispatchers.IO) {
        seasonEpisodes(tvId, season, language()).mapNotNull { it.toLiveEpisode(season) }
    }

    /**
     * Whether the movie or the series is in the library: under the id its page keeps it under (its
     * IMDb id), or under the "tmdb:" id the guide used to save it with.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun isInLibrary(match: LiveProgramMatch): Flow<Boolean> =
        flow { emit(libraryId(match)) }
            .flatMapLatest { id ->
                if (id == match.itemId) {
                    libraryRepository.isInLibrary(id, match.itemType)
                } else {
                    combine(
                        libraryRepository.isInLibrary(id, match.itemType),
                        libraryRepository.isInLibrary(match.itemId, match.itemType)
                    ) { canonical, legacy -> canonical || legacy }
                }
            }
            .distinctUntilChanged()

    /**
     * Adds the movie or the series to the library as the same title its page adds (its IMDb id and
     * the catalog's artwork), so it's one entry wherever it's added from. Nothing is taken out if
     * it's already there.
     */
    suspend fun addTitle(match: LiveProgramMatch): LiveLibraryResult {
        val id = libraryId(match)
        if (id != match.itemId) {
            // Saved by the guide before under its "tmdb:" id: it becomes the title's own entry.
            val legacyInLibrary = runCatching { libraryRepository.isInLibrary(match.itemId, match.itemType).first() }
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault(false)
            if (legacyInLibrary) {
                mergeGuideEntries()
                return LiveLibraryResult.ALREADY_IN_LIBRARY
            }
        }
        return add(catalogInput(id, match.itemType, fallback = match.toInput(id)))
    }

    /**
     * The id the rest of the app keeps a title under (its page, the catalogs, the library): its IMDb
     * id, else "tmdb:<id>" when TMDB doesn't know it (or can't be reached).
     */
    private suspend fun libraryId(match: LiveProgramMatch): String =
        imdbIdFor(match.tmdbId, match.itemType) ?: match.itemId

    /** The title's IMDb id (what the sources are asked by); null when TMDB doesn't know it. */
    suspend fun imdbId(match: LiveProgramMatch): String? = imdbIdFor(match.tmdbId, match.itemType)

    private val imdbIds = ConcurrentHashMap<String, String>()

    private suspend fun imdbIdFor(tmdbId: Int, type: String): String? {
        val key = "$type:$tmdbId"
        imdbIds[key]?.let { return it }
        val imdb = withTimeoutOrNull(IMDB_LOOKUP_TIMEOUT_MS) {
            try {
                tmdbService.tmdbToImdb(tmdbId, type)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }?.takeIf { it.startsWith("tt") }
        if (imdb != null) imdbIds[key] = imdb
        return imdb
    }

    /**
     * The library entry for [id] as its page makes it: the catalog's name and artwork (poster, logo,
     * backdrop), else [fallback] (TMDB's) when no catalog has it.
     */
    private suspend fun catalogInput(id: String, type: String, fallback: LibraryEntryInput): LibraryEntryInput {
        val meta = catalogMeta(id, type) ?: return fallback
        val year = Regex("(\\d{4})").find(meta.releaseInfo ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        return LibraryEntryInput(
            itemId = id,
            itemType = type,
            title = meta.name.ifBlank { fallback.title },
            year = year ?: fallback.year,
            imdbId = id.takeIf { it.startsWith("tt") },
            tmdbId = fallback.tmdbId,
            poster = meta.poster ?: fallback.poster,
            posterShape = meta.posterShape,
            background = meta.background ?: fallback.background,
            logo = meta.logo,
            description = meta.description ?: fallback.description,
            releaseInfo = meta.releaseInfo ?: fallback.releaseInfo,
            imdbRating = meta.imdbRating ?: fallback.imdbRating,
            genres = meta.genres.ifEmpty { fallback.genres }
        )
    }

    private suspend fun catalogMeta(id: String, type: String): Meta? {
        if (!id.startsWith("tt")) return null
        return withTimeoutOrNull(CATALOG_LOOKUP_TIMEOUT_MS) {
            try {
                when (val result = metaRepository.getMetaFromAllAddons(type = type, id = id).first { it !is NetworkResult.Loading }) {
                    is NetworkResult.Success -> result.data
                    else -> null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    private val mergeMutex = Mutex()

    /**
     * The movies and series the guide saved under their "tmdb:" id become the titles' own entries
     * (their IMDb id, as their page saves them): one entry per title. When the title's own entry is
     * there too, the guide's copy just goes. Only the app's own library (a Trakt, Simkl or MDBList
     * library keeps its titles by their ids already).
     */
    suspend fun mergeGuideEntries() {
        if (!mergeMutex.tryLock()) return
        try {
            if (libraryRepository.sourceMode.first().providerId != null) return
            val items = libraryRepository.libraryItems.first()
            val legacy = items.filter { entry ->
                LEGACY_TITLE_ID.matches(entry.id) &&
                    (entry.type.equals("movie", ignoreCase = true) || entry.type.equals("series", ignoreCase = true))
            }
            for (entry in legacy) {
                val type = entry.type.lowercase(Locale.ROOT)
                val tmdbId = entry.id.substringAfter(':').toIntOrNull() ?: continue
                val imdb = imdbIdFor(tmdbId, type) ?: continue
                // (Toggling adds what isn't there and takes out what is: each is checked right before.)
                if (!libraryRepository.isInLibrary(imdb, type).first()) {
                    libraryRepository.toggleDefault(catalogInput(imdb, type, fallback = entry.toInput(imdb, type, tmdbId)))
                }
                if (libraryRepository.isInLibrary(imdb, type).first() &&
                    libraryRepository.isInLibrary(entry.id, entry.type).first()
                ) {
                    libraryRepository.toggleDefault(LibraryEntryInput(itemId = entry.id, itemType = entry.type, title = entry.name))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Tried again next time.
        } finally {
            mergeMutex.unlock()
        }
    }

    private fun LibraryEntry.toInput(id: String, type: String, tmdbId: Int) = LibraryEntryInput(
        itemId = id,
        itemType = type,
        title = name,
        year = Regex("(\\d{4})").find(releaseInfo ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull(),
        imdbId = id,
        tmdbId = tmdbId,
        poster = poster,
        posterShape = posterShape,
        background = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres
    )

    /**
     * Adds the episode airing. A library synced with Trakt, Simkl or MDBList keeps whole series, so
     * the series goes in instead there.
     */
    suspend fun addEpisode(match: LiveProgramMatch): LiveLibraryResult {
        val episode = match.episode ?: return LiveLibraryResult.NOT_FOUND
        val syncedProvider = runCatching { libraryRepository.sourceMode.first().providerId }.getOrNull()
        if (syncedProvider != null) {
            val id = libraryId(match)
            return when (val result = add(catalogInput(id, match.itemType, fallback = match.toInput(id)))) {
                LiveLibraryResult.ADDED -> LiveLibraryResult.ADDED_SERIES_INSTEAD
                else -> result
            }
        }
        return add(match.toEpisodeInput(episode))
    }

    private suspend fun add(input: LibraryEntryInput): LiveLibraryResult = try {
        if (libraryRepository.isInLibrary(input.itemId, input.itemType).first()) {
            LiveLibraryResult.ALREADY_IN_LIBRARY
        } else {
            val result = libraryRepository.toggleDefault(input)
            if (result.requiresRemovalConfirmation) LiveLibraryResult.FAILED else LiveLibraryResult.ADDED
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LiveLibraryResult.FAILED
    }

    // ------------------------------------------------------------------ TMDB

    private suspend fun search(title: String, year: Int?, isTv: Boolean, language: String): LiveProgramMatch? {
        val results = limiter.withPermit {
            val response = if (isTv) {
                tmdbApi.searchTv(apiKey, title, language, firstAirDateYear = year)
            } else {
                tmdbApi.searchMovies(apiKey, title, language, primaryReleaseYear = year)
            }
            if (!response.isSuccessful) throw java.io.IOException("TMDB search HTTP ${response.code()}")
            response.body()?.results.orEmpty()
        }
        val wanted = normalize(title)
        val best = results
            .filter { result ->
                normalize(result.title ?: result.name ?: "") == wanted ||
                    normalize(result.originalTitle ?: result.originalName ?: "") == wanted
            }
            .maxByOrNull { it.voteCount ?: 0 }
            ?: return null
        return best.toMatch(isMovie = !isTv, genres = genreNames(isTv, best.genreIds.orEmpty(), language))
    }

    private suspend fun genreNames(isTv: Boolean, ids: List<Int>, language: String): List<String> {
        if (ids.isEmpty()) return emptyList()
        val key = "${if (isTv) "tv" else "movie"}:$language"
        val map = genreCache[key] ?: runCatching {
            val response = if (isTv) tmdbApi.getTvGenres(apiKey, language) else tmdbApi.getMovieGenres(apiKey, language)
            response.body()?.genres.orEmpty().associate { it.id to it.name }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull().orEmpty().also {
            if (it.isNotEmpty()) genreCache[key] = it
        }
        return ids.mapNotNull { map[it] }
    }

    private val genreCache = ConcurrentHashMap<String, Map<Int, String>>()

    /**
     * The episode a series' airing is: the one whose name is the guide's episode title (any season,
     * newest first), else one that first aired the day of the airing (a new episode).
     */
    private suspend fun findEpisode(tvId: Int, program: EpgProgram, language: String): LiveEpisode? {
        val seasons = limiter.withPermit {
            tmdbApi.getTvDetails(tvId, apiKey, language).body()?.numberOfSeasons
        } ?: return null
        if (seasons <= 0) return null
        val newestFirst = (seasons downTo 1).toList()
        val subtitle = program.subtitle?.let(::cleanTitle)?.takeIf { it.isNotBlank() }
        val airDay = Instant.ofEpochMilli(program.startMs).atZone(ZoneId.systemDefault()).toLocalDate()

        // By name: a few seasons at a time, newest first, stopping at the first match.
        if (subtitle != null) {
            val wanted = normalize(subtitle)
            for (batch in newestFirst.take(MAX_SEASONS).chunked(4)) {
                val loaded = coroutineScope {
                    batch.map { season -> async { season to seasonEpisodes(tvId, season, language) } }.awaitAll()
                }
                for ((season, episodes) in loaded) {
                    val exact = episodes.firstOrNull { normalize(it.name.orEmpty()) == wanted }
                        ?: episodes.firstOrNull { episode ->
                            val name = normalize(episode.name.orEmpty())
                            name.length >= 8 && wanted.length >= 8 && (name.startsWith(wanted) || wanted.startsWith(name))
                        }
                    exact?.let { return it.toLiveEpisode(season) }
                }
            }
        }
        // By date: a new episode first airs the day it's on (the last two seasons are enough).
        for (season in newestFirst.take(2)) {
            val episodes = seasonEpisodes(tvId, season, language)
            episodes.firstOrNull { episode ->
                val date = episode.airDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@firstOrNull false
                kotlin.math.abs(date.toEpochDay() - airDay.toEpochDay()) <= 1
            }?.let { return it.toLiveEpisode(season) }
        }
        return null
    }

    private suspend fun seasonEpisodes(tvId: Int, season: Int, language: String): List<TmdbEpisode> {
        val key = "$tvId:$season:$language"
        seasonCache[key]?.let { return it }
        val episodes = limiter.withPermit {
            runCatching { tmdbApi.getTvSeasonDetails(tvId, season, apiKey, language).body()?.episodes.orEmpty() }
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault(emptyList())
        }
        if (episodes.isNotEmpty()) seasonCache[key] = episodes
        return episodes
    }

    private fun TmdbEpisode.toLiveEpisode(season: Int): LiveEpisode? {
        val number = episodeNumber ?: return null
        return LiveEpisode(
            season = season,
            episode = number,
            name = name?.takeIf { it.isNotBlank() },
            overview = overview?.takeIf { it.isNotBlank() },
            still = image(stillPath, "w780"),
            airDate = airDate
        )
    }

    private fun TmdbDiscoverResult.toMatch(isMovie: Boolean, genres: List<String>): LiveProgramMatch? {
        val name = (title ?: this.name ?: originalTitle ?: originalName)?.takeIf { it.isNotBlank() } ?: return null
        val date = if (isMovie) releaseDate else firstAirDate
        return LiveProgramMatch(
            isMovie = isMovie,
            tmdbId = id,
            name = name,
            year = date?.take(4)?.toIntOrNull(),
            poster = image(posterPath, "w500"),
            backdrop = image(backdropPath, "w1280"),
            overview = overview?.takeIf { it.isNotBlank() },
            rating = voteAverage?.toFloat()?.takeIf { it > 0f },
            genres = genres
        )
    }

    private fun LiveProgramMatch.toInput(id: String = itemId) = LibraryEntryInput(
        itemId = id,
        itemType = itemType,
        title = name,
        year = year,
        imdbId = id.takeIf { it.startsWith("tt") },
        tmdbId = tmdbId,
        poster = poster,
        posterShape = PosterShape.POSTER,
        background = backdrop,
        description = overview,
        releaseInfo = year?.toString(),
        imdbRating = rating,
        genres = genres
    )

    /**
     * An episode in the library: its own entry ("tmdb:<series>:<season>:<episode>", type "episode"),
     * with the series' poster; it opens the series on that episode.
     */
    private fun LiveProgramMatch.toEpisodeInput(episode: LiveEpisode) = LibraryEntryInput(
        itemId = "tmdb:$tmdbId:${episode.season}:${episode.episode}",
        itemType = EPISODE_TYPE,
        title = buildString {
            append(name).append(" · S").append(episode.season).append(" E").append(episode.episode)
            episode.name?.let { append(" · ").append(it) }
        },
        year = episode.airDate?.take(4)?.toIntOrNull() ?: year,
        tmdbId = tmdbId,
        poster = poster,
        posterShape = PosterShape.POSTER,
        background = episode.still ?: backdrop,
        description = episode.overview ?: overview,
        releaseInfo = episode.airDate?.take(4) ?: year?.toString(),
        imdbRating = rating,
        genres = genres
    )

    private fun image(path: String?, size: String): String? =
        path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }

    private fun EpgProgram.categoryWords(): List<String> =
        (categories + listOfNotNull(category)).map { it.lowercase(Locale.ROOT) }

    companion object {
        /** The library type of an episode saved from the guide. */
        const val EPISODE_TYPE = "episode"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        /** After TMDB failed for a title, it isn't asked about it again for this long. */
        private const val FAILED_RETRY_MS = 60L * 1000
        private const val IMDB_LOOKUP_TIMEOUT_MS = 6_000L
        private const val CATALOG_LOOKUP_TIMEOUT_MS = 8_000L
        /** A movie or series the guide saved before it used the titles' own ids. */
        private val LEGACY_TITLE_ID = Regex("""^tmdb:\d+$""")
        /** Seasons looked through for an episode's name. */
        private const val MAX_SEASONS = 40
        private val TRAILING_YEAR = Regex("""\s*\((\d{4})\)\s*$""")

        /** Guide titles that aren't a show's name (TMDB could have something by that name). */
        private val GENERIC_TITLES = setOf(
            "news", "localnews", "thenews", "movie", "themovie", "film", "paidprogramming", "paidprogram",
            "infomercial", "programming", "tba", "tobeannounced", "signoff", "offair", "weather",
            "sports", "noinformation", "noprogram", "nodata", "specialprogramming"
        )
        private val BRACKET_TAGS = Regex("""\s*\[\s*(new|live)\s*]""", RegexOption.IGNORE_CASE)

        /** A guide title without its badges and leading symbols ("🏈 Show [NEW]" -> "Show"). */
        fun cleanTitle(raw: String): String {
            val withoutTags = raw.replace(BRACKET_TAGS, "").trim()
            var index = 0
            while (index < withoutTags.length) {
                val codePoint = withoutTags.codePointAt(index)
                if (Character.isLetterOrDigit(codePoint) || codePoint == '['.code || codePoint == '('.code ||
                    codePoint == '"'.code || codePoint == '¡'.code || codePoint == '¿'.code
                ) {
                    break
                }
                index += Character.charCount(codePoint)
            }
            return withoutTags.substring(index).trim().ifBlank { raw.trim() }
        }

        private fun normalize(value: String): String =
            Normalizer.normalize(value, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase(Locale.US)
                .replace("&", "and")
                .replace(Regex("[^a-z0-9]+"), "")

        /**
         * "tmdb:1399:2:5" (an episode saved from the guide) -> the series "tmdb:1399", season 2,
         * episode 5; null for any other id.
         */
        fun parseEpisodeId(itemId: String): Triple<String, Int, Int>? {
            val match = Regex("""^(tmdb:\d+):(\d+):(\d+)$""").find(itemId) ?: return null
            val (series, season, episode) = match.destructured
            return Triple(series, season.toInt(), episode.toInt())
        }
    }
}
