package com.nuvio.tv.ui.screens.livetv

import com.nuvio.tv.data.livetv.LiveProgramLibrary
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.SportsLibraryStore
import com.nuvio.tv.data.remote.espn.EspnSportsClient
import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import com.nuvio.tv.ui.screens.sports.SPORTS_EVENT_LIBRARY_TYPE
import com.nuvio.tv.ui.screens.sports.SportsGameChannels
import com.nuvio.tv.ui.screens.sports.toSavedLibraryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** How saving a guide program to the Library's Events went. */
enum class LiveEventResult { ADDED, ALREADY_IN_LIBRARY, FAILED }

/**
 * Puts what TMDB doesn't have from the Guide in the Library's Events: a game as the game itself
 * (found on ESPN by its teams and time, so it shows both teams' logos, its time, and opens its
 * page), and anything else (a special, an award show, a game ESPN doesn't cover) as the program:
 * its name, picture, channel and time, opening its channel.
 */
@Singleton
class LiveEventLibrary @Inject constructor(
    private val espnClient: EspnSportsClient,
    private val libraryPreferences: LibraryPreferences,
    private val sportsLibraryStore: SportsLibraryStore
) {
    /** The leagues' games around a day (by the day searched), for a few minutes. */
    private val fixturesCache = ConcurrentHashMap<Long, Pair<Long, List<SportsEvent>>>()

    /**
     * The game [program] is, on ESPN; null when it doesn't look like a game or ESPN doesn't have it.
     * Throws when ESPN can't be reached.
     */
    suspend fun findGame(program: EpgProgram): SportsEvent? {
        if (!looksLikeGame(program)) return null
        val events = fixturesAround(program.startMs)
        return events
            .map { event -> event to SportsGameChannels.scoreFor(program, event) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    private suspend fun fixturesAround(timeMs: Long): List<SportsEvent> {
        val day = timeMs / DAY_MS
        val now = System.currentTimeMillis()
        fixturesCache[day]?.let { (savedAt, events) -> if (now - savedAt < CACHE_MS) return events }
        val events = withContext(Dispatchers.IO) {
            espnClient.fetchFixturesAround(timeMs).flatMap { it.events }
        }
        if (events.isEmpty()) error("No games from ESPN")
        fixturesCache[day] = now to events
        return events
    }

    /** The game saved to the Library (its details kept for the Library's card). */
    suspend fun addGame(event: SportsEvent): LiveEventResult = save(event.toSavedLibraryItem(), event)

    /** The guide program itself saved to the Library's Events. */
    suspend fun addProgram(channel: LiveTvChannel, program: EpgProgram): LiveEventResult {
        val event = programEvent(channel, program)
        val item = SavedLibraryItem(
            id = event.id,
            type = SPORTS_EVENT_LIBRARY_TYPE,
            name = event.name,
            poster = null,
            posterShape = PosterShape.LANDSCAPE,
            background = event.homeTeamBadgeUrl,
            description = channel.name,
            releaseInfo = null,
            imdbRating = null,
            genres = listOfNotNull(program.category?.takeIf { it.isNotBlank() }),
            addonBaseUrl = null
        )
        return save(item, event)
    }

    private suspend fun save(item: SavedLibraryItem, event: SportsEvent): LiveEventResult = try {
        if (libraryPreferences.isInLibrary(item.id, SPORTS_EVENT_LIBRARY_TYPE).first()) {
            LiveEventResult.ALREADY_IN_LIBRARY
        } else {
            sportsLibraryStore.rememberEvent(event)
            libraryPreferences.addItem(item = item)
            LiveEventResult.ADDED
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LiveEventResult.FAILED
    }

    fun isGameInLibrary(event: SportsEvent): Flow<Boolean> =
        libraryPreferences.isInLibrary(event.id, SPORTS_EVENT_LIBRARY_TYPE)

    fun isProgramInLibrary(channel: LiveTvChannel, program: EpgProgram): Flow<Boolean> =
        libraryPreferences.isInLibrary(programEventId(channel, program), SPORTS_EVENT_LIBRARY_TYPE)

    /** The program as an event: its name, picture (else the channel's logo), channel and start. */
    private fun programEvent(channel: LiveTvChannel, program: EpgProgram): SportsEvent {
        val title = LiveProgramLibrary.cleanTitle(program.title).ifBlank { program.title }
        return SportsEvent(
            id = programEventId(channel, program),
            name = title,
            sportName = null,
            leagueId = null,
            leagueName = channel.name,
            homeTeamId = null,
            homeTeamName = title,
            homeTeamBadgeUrl = program.imageUrl?.takeIf { it.isNotBlank() } ?: channel.logoUrl,
            awayTeamId = null,
            awayTeamName = "",
            awayTeamBadgeUrl = null,
            homeScore = null,
            awayScore = null,
            startTimeMs = program.startMs,
            venue = null,
            status = SportsEventStatus.UPCOMING,
            statusDetail = program.subtitle?.takeIf { it.isNotBlank() }
        )
    }

    companion object {
        /** A guide program saved as an event: "guide:<channel id>:<start>". */
        private const val PROGRAM_ID_PREFIX = "guide:"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val CACHE_MS = 10L * 60 * 1000

        private val SPORT_WORDS = listOf(
            "sport", "sports", "deporte", "deportes", "football", "futbol", "soccer", "basketball",
            "baloncesto", "basquetbol", "baseball", "beisbol", "hockey", "nfl", "nba", "wnba", "mlb",
            "nhl", "ncaa", "mls", "college football", "college basketball", "college baseball", "liga",
            "laliga", "premier league", "champions league", "europa league", "serie a", "bundesliga",
            "ligue 1", "copa", "cup", "partido"
        )

        /** "Bulls vs. Knicks", "Real Madrid v Barcelona", "Bears @ Packers", "América contra Chivas". */
        private val MATCHUP = Regex("""\b(vs|v|versus|contra)\b""")

        private fun programEventId(channel: LiveTvChannel, program: EpgProgram): String =
            "$PROGRAM_ID_PREFIX${channel.id}:${program.startMs}"

        /** A guide program saved as an event (not a game from ESPN). */
        fun isProgramEventId(id: String): Boolean = id.startsWith(PROGRAM_ID_PREFIX)

        /** The channel of a guide program saved as an event. */
        fun channelIdOf(id: String): String? =
            id.takeIf { isProgramEventId(it) }?.removePrefix(PROGRAM_ID_PREFIX)?.substringBeforeLast(':')?.takeIf { it.isNotBlank() }

        /** Its title, subtitle or categories say it's a game (or sports). */
        fun looksLikeGame(program: EpgProgram): Boolean {
            val heading = SportsGameChannels.normalize(listOfNotNull(program.title, program.subtitle).joinToString(" "))
            val categories = SportsGameChannels.normalize((program.categories + listOfNotNull(program.category)).joinToString(" "))
            fun has(text: String, word: String) = " $text ".contains(" $word ")
            if (MATCHUP.containsMatchIn(heading) || " @ " in program.title) return true
            return SPORT_WORDS.any { has(categories, it) || has(heading, it) }
        }
    }
}
