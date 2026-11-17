package com.nuvio.tv.data.livetv

import com.nuvio.tv.data.discover.DiscoverCategory
import com.nuvio.tv.data.discover.DiscoverGenre
import com.nuvio.tv.data.discover.DiscoverMediaFilter
import com.nuvio.tv.data.discover.TmdbDiscoverRepository
import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import javax.inject.Inject

/**
 * RCTV (owner): the Live TV player's "More to Watch" tab, picked from what's on and what was watched:
 * - titles like what's on now (TMDB's picks for its movie or series), first and most;
 * - popular titles of its genre (TMDB's genres for it, else the guide's category: Action, Comedy...);
 * - titles like the ones watched last on this profile.
 * What's on itself is left out; TMDB's trending titles only when nothing else came.
 */
class LiveMoreToWatch @Inject constructor(
    private val tmdb: TmdbDiscoverRepository,
    private val watchProgress: WatchProgressRepository
) {

    suspend fun picks(match: LiveProgramMatch?, program: EpgProgram?): List<MetaPreview> = coroutineScope {
        val language = tmdb.language()
        val likeThis = async {
            safe { match?.let { tmdb.recommendationsFor(it.itemId, !it.isMovie, language) }.orEmpty() }
        }
        val ofGenre = async { safe { genrePicks(match, program, language) } }
        val likeWatched = async { safe { watchedPicks(language, exclude = match?.itemId) } }
        val shown = mix(likeThis.await(), ofGenre.await(), likeWatched.await()).withArt(match?.itemId)
        shown.ifEmpty { safe { trending(language) }.withArt(match?.itemId) }
    }

    private fun List<MetaPreview>.withArt(leaveOut: String?): List<MetaPreview> =
        filter { it.id != leaveOut && (!it.background.isNullOrBlank() || !it.landscapePoster.isNullOrBlank()) }
            .distinctBy { it.id }
            .take(MAX_PICKS)

    private suspend fun genrePicks(match: LiveProgramMatch?, program: EpgProgram?, language: String): List<MetaPreview> {
        val wanted = (match?.genres.orEmpty() + program?.categories.orEmpty() + listOfNotNull(program?.category))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (wanted.isEmpty()) return emptyList()
        // The guide's categories are in English; TMDB's names for the match can be in the app's language.
        val genres = tmdb.genres(DiscoverMediaFilter.ALL, "en") +
            if (language != "en") safe { tmdb.genres(DiscoverMediaFilter.ALL, language) } else emptyList()
        val genre = pickGenre(wanted, genres) ?: return emptyList()
        val showsFirst = match?.isMovie == false || (match == null && !looksLikeMovie(wanted))
        return coroutineScope {
            val movies = async {
                genre.movieId?.let { id ->
                    safe { tmdb.page(TmdbCollectionMediaType.MOVIE, DiscoverCategory.POPULAR, id, null, 1, language).items }
                }.orEmpty()
            }
            val shows = async {
                genre.tvId?.let { id ->
                    safe { tmdb.page(TmdbCollectionMediaType.TV, DiscoverCategory.POPULAR, id, null, 1, language).items }
                }.orEmpty()
            }
            if (showsFirst) interleave(shows.await(), movies.await()) else interleave(movies.await(), shows.await())
        }
    }

    private suspend fun watchedPicks(language: String, exclude: String?): List<MetaPreview> {
        val all = withTimeoutOrNull(HISTORY_WAIT_MS) { watchProgress.allProgress.first() }.orEmpty()
        val recent = all
            .filter { it.contentType.equals("movie", true) || it.contentType.equals("series", true) }
            .sortedByDescending { it.lastWatched }
            .distinctBy { it.contentId }
            .filter { it.contentId != exclude }
            .take(WATCHED_TITLES)
        if (recent.isEmpty()) return emptyList()
        return coroutineScope {
            val lists = recent.map { progress ->
                async {
                    safe {
                        tmdb.recommendationsFor(progress.contentId, progress.contentType.equals("series", true), language)
                    }
                }
            }.awaitAll()
            interleave(*lists.toTypedArray())
        }
    }

    private suspend fun trending(language: String): List<MetaPreview> = coroutineScope {
        val movies = async { safe { tmdb.page(TmdbCollectionMediaType.MOVIE, DiscoverCategory.TRENDING, null, null, 1, language).items } }
        val shows = async { safe { tmdb.page(TmdbCollectionMediaType.TV, DiscoverCategory.TRENDING, null, null, 1, language).items } }
        interleave(movies.await(), shows.await())
    }

    private suspend fun <T> safe(block: suspend () -> List<T>): List<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }

    companion object {
        const val MAX_PICKS = 24
        private const val WATCHED_TITLES = 2
        private const val HISTORY_WAIT_MS = 3_000L

        /** The guide's words for what TMDB calls otherwise. */
        private val SYNONYMS = mapOf(
            "children" to "kids",
            "children's" to "kids",
            "sitcom" to "comedy",
            "animated" to "animation",
            "sci-fi" to "science fiction",
            "scifi" to "science fiction",
            "suspense" to "thriller",
            "talk show" to "talk",
            "news magazine" to "news",
            "docudrama" to "documentary",
            "reality show" to "reality",
            "soap opera" to "soap"
        )

        /**
         * The first of [wanted] (TMDB's genre names for what's on, then the guide's categories) that
         * TMDB has as a genre: by its whole name ("Science Fiction"), else by one of its words
         * ("Crime drama" is Crime).
         */
        internal fun pickGenre(wanted: List<String>, genres: List<DiscoverGenre>): DiscoverGenre? {
            for (raw in wanted) {
                val name = raw.lowercase(Locale.ROOT).trim().let { SYNONYMS[it] ?: it }
                genres.firstOrNull { g -> name in g.names || g.label.lowercase(Locale.ROOT) == name }?.let { return it }
                val words = name.split(' ', '-', '/', ',').map { SYNONYMS[it] ?: it }.filter { it.length > 3 }
                if (words.isEmpty()) continue
                genres.firstOrNull { g ->
                    g.names.any { n -> n.split(" & ", " and ").any { part -> part.trim() in words } }
                }?.let { return it }
            }
            return null
        }

        internal fun looksLikeMovie(categories: List<String>): Boolean =
            categories.any { val c = it.lowercase(Locale.ROOT); "movie" in c || "film" in c }

        /** What's on's picks first and most (two of them), then one of its genre, one like what was watched. */
        internal fun <T> mix(likeThis: List<T>, ofGenre: List<T>, likeWatched: List<T>): List<T> {
            val a = likeThis.iterator()
            val b = ofGenre.iterator()
            val c = likeWatched.iterator()
            return buildList {
                while (a.hasNext() || b.hasNext() || c.hasNext()) {
                    repeat(2) { if (a.hasNext()) add(a.next()) }
                    if (b.hasNext()) add(b.next())
                    if (c.hasNext()) add(c.next())
                }
            }
        }

        internal fun <T> interleave(vararg lists: List<T>): List<T> = buildList {
            val iterators = lists.map { it.iterator() }
            while (iterators.any { it.hasNext() }) {
                iterators.forEach { if (it.hasNext()) add(it.next()) }
            }
        }
    }
}
