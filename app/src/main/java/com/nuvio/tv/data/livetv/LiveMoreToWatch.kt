package com.nuvio.tv.data.livetv

import com.nuvio.tv.data.discover.DiscoverCategory
import com.nuvio.tv.data.discover.DiscoverGenre
import com.nuvio.tv.data.discover.DiscoverMediaFilter
import com.nuvio.tv.data.discover.TmdbDiscoverRepository
import com.nuvio.tv.data.remote.espn.EspnSportsClient
import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.model.LiveTvChannel
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
 *
 * RCTV (owner): on a sports channel or a game, first the channels with games and sports events on
 * now (the same league's first: another NFL game, NFL RedZone; then the same sport's; then the other
 * live sports), then movies and series of that sport (tennis movies, tennis series), then the rest.
 */
class LiveMoreToWatch @Inject constructor(
    private val tmdb: TmdbDiscoverRepository,
    private val watchProgress: WatchProgressRepository
) {

    suspend fun picks(
        match: LiveProgramMatch?,
        program: EpgProgram?,
        sportFeeds: List<EspnSportsClient.Feed> = emptyList()
    ): List<MetaPreview> = coroutineScope {
        val language = tmdb.language()
        val ofSport = async { safe { sportTitles(sportFeeds, language) } }
        val likeThis = async {
            safe { match?.let { tmdb.recommendationsFor(it.itemId, !it.isMovie, language) }.orEmpty() }
        }
        val ofGenre = async { safe { genrePicks(match, program, language) } }
        val likeWatched = async { safe { watchedPicks(language, exclude = match?.itemId) } }
        val shown = (ofSport.await() + mix(likeThis.await(), ofGenre.await(), likeWatched.await())).withArt(match?.itemId)
        shown.ifEmpty { safe { trending(language) }.withArt(match?.itemId) }
    }

    /** Movies and series of the sport on now, the most popular, one of each in turn. */
    private suspend fun sportTitles(feeds: List<EspnSportsClient.Feed>, language: String): List<MetaPreview> {
        val keyword = sportKeywordOf(feeds) ?: return emptyList()
        return coroutineScope {
            val movies = async { safe { tmdb.withKeyword(keyword, TmdbCollectionMediaType.MOVIE, language) } }
            val shows = async { safe { tmdb.withKeyword(keyword, TmdbCollectionMediaType.TV, language) } }
            interleave(movies.await(), shows.await()).take(SPORT_TITLES)
        }
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
        /** How many channels with sports on now lead the row, and how many of the sport's titles follow. */
        const val MAX_SPORT_CHANNELS = 12
        private const val SPORT_TITLES = 12

        /**
         * What's on is sports (a game, an event, a sports channel's program): its leagues, as the
         * Sports page knows them (tennis: ATP and WTA; an NFL game: the NFL); empty when it isn't.
         * Only the sport said ("Football") gives that sport's main leagues.
         */
        fun sportFeedsOf(channel: LiveTvChannel?, program: EpgProgram?): List<EspnSportsClient.Feed> {
            if (!isSports(channel, program)) return emptyList()
            val feeds = EspnSportsClient.feedsForProgram(textOf(channel, program))
            // Nothing named (only "Sports"): no league in particular.
            return if (feeds.map { it.leaguePath }.toSet() == UNNAMED_LEAGUES) emptyList() else feeds
        }

        /** Sports with no league named still count as sports (the channels show; no sport's titles). */
        fun isSports(channel: LiveTvChannel?, program: EpgProgram?): Boolean =
            (program != null && com.nuvio.tv.ui.screens.livetv.LiveEventLibrary.looksLikeGame(program)) ||
                channel?.guideCategory?.contains("sport", ignoreCase = true) == true ||
                // A sport's own channel by its name (Tennis Channel, Golf Channel, NFL RedZone).
                (channel != null && EspnSportsClient.feedsForProgram(
                    com.nuvio.tv.ui.screens.sports.SportsGameChannels.normalize(channel.name)
                ).map { it.leaguePath }.toSet() != UNNAMED_LEAGUES)

        private val UNNAMED_LEAGUES: Set<String> by lazy {
            EspnSportsClient.feedsForProgram("").map { it.leaguePath }.toSet()
        }

        private fun textOf(channel: LiveTvChannel?, program: EpgProgram?): String =
            com.nuvio.tv.ui.screens.sports.SportsGameChannels.normalize(
                (listOfNotNull(program?.title, program?.subtitle, program?.category, channel?.name) + program?.categories.orEmpty())
                    .joinToString(" ")
            )

        /**
         * The Guide's channels with sports on now ([current] left out): the same league's first (its
         * games, and its own channels: NFL RedZone and NFL Network for the NFL, Tennis Channel for
         * tennis), then the same sport's, then the other live games and events, then other sports
         * programs; live ones before the others, each in the Guide's order. At most [MAX_SPORT_CHANNELS].
         */
        fun sportsChannels(
            channels: List<LiveTvChannel>,
            programAt: (LiveTvChannel) -> EpgProgram?,
            current: LiveTvChannel?,
            feeds: List<EspnSportsClient.Feed>
        ): List<LiveTvChannel> {
            val leagues = feeds.map { it.leaguePath }.toSet()
            val sports = feeds.map { it.sportPath }.toSet()
            val ownChannels = leagues.flatMap { LEAGUE_CHANNEL_WORDS[it].orEmpty() }.toSet()
            val ranked = ArrayList<Pair<Int, LiveTvChannel>>()
            for (channel in channels) {
                if (channel.id == current?.id || channel.name.equals(current?.name, ignoreCase = true)) continue
                val program = programAt(channel)
                val name = " ${com.nuvio.tv.ui.screens.sports.SportsGameChannels.normalize(channel.name)} "
                val leagueChannel = ownChannels.any { name.contains(" $it ") }
                if (program == null && !leagueChannel) continue
                if (!leagueChannel && !isSports(channel, program)) continue
                val theirs = if (program != null) EspnSportsClient.feedsForProgram(textOf(channel, program)) else emptyList()
                val theirLeagues = theirs.map { it.leaguePath }.toSet()
                val named = theirLeagues != UNNAMED_LEAGUES
                val event = program != null && (program.isLive || com.nuvio.tv.ui.screens.livetv.LiveEventLibrary.looksLikeGame(program))
                val rank = when {
                    leagueChannel || (named && theirLeagues.any { it in leagues }) -> 0
                    named && theirs.any { it.sportPath in sports } -> 2
                    event -> 4
                    else -> 6
                } + if (program?.isLive == true || leagueChannel) 0 else 1
                ranked += rank to channel
            }
            return ranked.sortedBy { it.first }.map { it.second }.distinctBy { it.id }.take(MAX_SPORT_CHANNELS)
        }

        /** A league's own channels, by the words of their names. */
        private val LEAGUE_CHANNEL_WORDS: Map<String, List<String>> = mapOf(
            "nfl" to listOf("redzone", "red zone", "nfl network"),
            "college-football" to listOf("sec network", "acc network", "big ten network", "espnu"),
            "nba" to listOf("nba tv"),
            "wnba" to listOf("nba tv"),
            "mens-college-basketball" to listOf("sec network", "acc network", "big ten network", "espnu"),
            "mlb" to listOf("mlb network", "mlb tv"),
            "nhl" to listOf("nhl network"),
            "atp" to listOf("tennis channel", "tennis"),
            "wta" to listOf("tennis channel", "tennis"),
            "pga" to listOf("golf channel", "golf"),
            "lpga" to listOf("golf channel", "golf"),
            "f1" to listOf("f1 tv"),
            "ufc" to listOf("ufc"),
            "usa.1" to listOf("mls"),
            "esp.1" to listOf("laliga"),
            "eng.1" to listOf("premier league")
        )

        /** TMDB's keyword for the sport's movies and series. */
        internal fun sportKeywordOf(feeds: List<EspnSportsClient.Feed>): String? {
            val leagues = feeds.map { it.leaguePath }.toSet()
            return when (feeds.firstOrNull()?.sportPath) {
                "tennis" -> "tennis"
                "football" -> "american football"
                "basketball" -> "basketball"
                "baseball" -> "baseball"
                "hockey" -> "ice hockey"
                "soccer" -> "soccer"
                "golf" -> "golf"
                "mma" -> "mixed martial arts"
                "racing" -> if ("f1" in leagues) "formula 1" else "car race"
                else -> when {
                    leagues.any { "nascar" in it } -> "nascar"
                    leagues.any { "box" in it || "ppv" in it } -> "boxing"
                    else -> null
                }
            }
        }
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
