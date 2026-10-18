package com.nuvio.tv.ui.screens.sports

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.nuvio.tv.R
import com.nuvio.tv.data.local.LiveTvDataStore
import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import com.nuvio.tv.ui.screens.livetv.LiveGuideSnapshot
import com.nuvio.tv.ui.screens.livetv.LiveTvGuideLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

// ---------------------------------------------------------------------------------------------
// Live by the clock: a game whose start time has come is live, without waiting for the scores.
// ---------------------------------------------------------------------------------------------

/** How long after its start a game not yet marked over still counts as live. */
private const val LIVE_WINDOW_MS = 4L * 60 * 60 * 1000

/** How often the pages look at the clock (an upcoming game goes live on time). */
internal const val SPORTS_CLOCK_TICK_MS = 30_000L

/** Live now: marked live, or upcoming and its start time has come (within a game's length). */
internal fun SportsEvent.isLiveAt(now: Long): Boolean = when (status) {
    SportsEventStatus.LIVE -> true
    SportsEventStatus.FINISHED -> false
    SportsEventStatus.UPCOMING -> {
        val start = startTimeMs
        start != null && now >= start && now < start + LIVE_WINDOW_MS
    }
}

/** The game as the clock has it: an upcoming game whose time has come shows as live. */
internal fun SportsEvent.withClock(now: Long): SportsEvent =
    if (status == SportsEventStatus.UPCOMING && isLiveAt(now)) copy(status = SportsEventStatus.LIVE) else this

// ---------------------------------------------------------------------------------------------
// The game in the TV guide: the channel showing it now.
// ---------------------------------------------------------------------------------------------

/** A game on a channel of the guide: the channel, its program, and how sure the match is. */
internal data class GameAiring(val channel: LiveTvChannel, val program: EpgProgram, val score: Int)

/**
 * Finds a game in the Live TV guide (the IPTV channels and their EPG): the programs on now (or
 * starting in a few minutes) whose title has both teams ("NBA Basketball: Chicago Bulls at Minnesota
 * Timberwolves", "Bulls vs. Timberwolves", "Real Madrid - Barcelona"), or one team when the rest
 * says it's that game (a live, game-length program starting with it). Not the shows about it
 * (pregame, highlights, replays).
 */
internal object SportsGameChannels {

    /** A program starting this soon can be the game (the guide's slot may start on the hour). */
    private const val STARTING_SOON_MS = 15L * 60 * 1000
    /** A program starting this close to the game's start is the game's slot. */
    private const val SAME_START_MS = 40L * 60 * 1000
    /** Shorter than this, a program with one team only isn't the game. */
    private const val GAME_LENGTH_MS = 80L * 60 * 1000
    private const val MIN_SCORE = 60

    /** Shows about a game, not the game. */
    private val NOT_THE_GAME = listOf(
        "pregame", "pre game", "postgame", "post game", "previa", "postpartido", "post partido",
        "highlights", "resumen", "resumenes", "recap", "replay", "repeticion", "diferido", "encore",
        "rewind", "classic", "classics", "preview", "review", "condensed", "best of", "press conference",
        "rueda de prensa", "conferencia de prensa", "analysis", "analisis", "magazine", "draft"
    )

    /** Words that say what sport a program is ("NBA Basketball", "Fútbol"). */
    private fun sportWords(event: SportsEvent): List<String> {
        val sport = normalize(event.sportName.orEmpty())
        val league = normalize(event.leagueName.orEmpty())
        val words = ArrayList<String>()
        if (league.isNotBlank()) words += league
        event.leagueId?.substringAfter(':')?.substringBefore('.')?.let { words += normalize(it) }
        words += when {
            "basket" in sport -> listOf("basketball", "baloncesto", "basquetbol", "basket")
            "baseball" in sport -> listOf("baseball", "beisbol", "pelota")
            "hockey" in sport -> listOf("hockey")
            "soccer" in sport || "futbol" in sport -> listOf("soccer", "futbol", "football", "liga", "league", "cup", "copa")
            "football" in sport -> listOf("football", "futbol americano")
            else -> listOf(sport)
        }
        return words.filter { it.length >= 3 }.distinct()
    }

    /** "Man Utd" and "Manchester United" are one name. */
    internal fun canonical(text: String): String = (" $text ")
        .replace(" man utd ", " manchester united ")
        .replace(" man united ", " manchester united ")
        .replace(" man city ", " manchester city ")
        .replace(" utd ", " united ")
        .replace(" psg ", " paris saint germain ")
        .replace(" paris sg ", " paris saint germain ")
        .replace(" st ", " saint ")
        .trim()

    /** "Chicago Bulls" → "chicago bulls": no accents, punctuation or case. */
    internal fun normalize(text: String): String {
        val plain = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return plain.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** A team's names to look for: "chicago bulls", "bulls"; "arsenal" (not "arsenal fc"). */
    private fun teamNames(name: String, aliases: List<String>): List<String> =
        (listOf(name) + aliases)
            .map { canonical(normalize(it)) }
            .map { it.replace(Regex("\\b(fc|cf|afc|sc)\\b"), " ").replace(Regex("\\s+"), " ").trim() }
            .filter { it.length >= 3 }
            .distinct()

    private fun has(text: String, phrase: String): Boolean = " $text ".contains(" $phrase ")

    /** How a team shows in a text: 2 with a name of two words or more, 1 with a one-word name, 0 not at all. */
    private fun teamIn(text: String, names: List<String>): Int {
        var best = 0
        names.forEach { name ->
            if (has(text, name)) best = maxOf(best, if (' ' in name) 2 else 1)
        }
        return best
    }

    fun find(
        channels: List<LiveTvChannel>,
        epgByChannel: Map<String, List<EpgProgram>>,
        event: SportsEvent,
        now: Long
    ): List<GameAiring> {
        val home = teamNames(event.homeTeamName, event.homeTeamAliases)
        val away = teamNames(event.awayTeamName, event.awayTeamAliases)
        if (home.isEmpty() && away.isEmpty()) return emptyList()
        val sportWords = sportWords(event)
        val gameStart = event.startTimeMs
        val found = ArrayList<GameAiring>()
        val seen = HashSet<String>()
        channels.forEach { channel ->
            val programs = epgByChannel[channel.epgChannelId ?: channel.id] ?: return@forEach
            programs.forEach program@{ program ->
                // On now, or starting in a few minutes.
                if (program.endMs <= now || program.startMs > now + STARTING_SOON_MS) return@program
                // Around the game's own time (not an old game between the same teams).
                if (gameStart != null && (program.endMs <= gameStart || program.startMs >= gameStart + LIVE_WINDOW_MS)) return@program
                val score = scoreOf(program, home, away, sportWords, gameStart)
                if (score < MIN_SCORE) return@program
                if (!seen.add("${channel.id}|${program.startMs}")) return@program
                found += GameAiring(channel, program, score + channelBonus(channel))
            }
        }
        return found.sortedByDescending { it.score }
    }

    /**
     * How surely a guide program (now or later) is [event] itself, 0 when it isn't: the game's teams
     * in it, at the game's time (not another game between the same teams, nor a show about it).
     */
    fun scoreFor(program: EpgProgram, event: SportsEvent): Int {
        val home = teamNames(event.homeTeamName, event.homeTeamAliases)
        val away = teamNames(event.awayTeamName, event.awayTeamAliases)
        if (home.isEmpty() || away.isEmpty()) return 0
        val gameStart = event.startTimeMs ?: return 0
        if (program.endMs <= gameStart || program.startMs >= gameStart + LIVE_WINDOW_MS) return 0
        val score = scoreOf(program, home, away, sportWords(event), gameStart)
        return if (score >= MIN_SCORE) score else 0
    }

    private fun scoreOf(
        program: EpgProgram,
        home: List<String>,
        away: List<String>,
        sportWords: List<String>,
        gameStart: Long?
    ): Int {
        val heading = canonical(normalize(listOfNotNull(program.title, program.subtitle).joinToString(" ")))
        if (NOT_THE_GAME.any { has(heading, it) }) return 0
        val description = canonical(normalize(program.description.orEmpty().take(400)))
        val homeInHeading = teamIn(heading, home)
        val awayInHeading = teamIn(heading, away)
        val homeAnywhere = maxOf(homeInHeading, teamIn(description, home))
        val awayAnywhere = maxOf(awayInHeading, teamIn(description, away))

        val sportInHeading = sportWords.any { has(heading, it) }

        var score = when {
            homeInHeading > 0 && awayInHeading > 0 -> 100
            (homeInHeading > 0 && awayAnywhere > 0) || (awayInHeading > 0 && homeAnywhere > 0) -> 80
            // "NBA Basketball", the teams in its description: the game. "SportsCenter" with the
            // teams in its description: a show about it.
            homeAnywhere > 0 && awayAnywhere > 0 ->
                if (sportInHeading && NOT_THE_GAME.none { has(description, it) }) 60 else return 0
            homeInHeading > 0 || awayInHeading > 0 -> 15 + 15 * maxOf(homeInHeading, awayInHeading)
            else -> return 0
        }
        val bothTeams = homeAnywhere > 0 && awayAnywhere > 0
        // One team only: it must look like the game itself (game-length, live, at the game's time).
        if (!bothTeams && program.endMs - program.startMs < GAME_LENGTH_MS) return 0
        if (program.isLive) score += 20
        if (gameStart != null && abs(program.startMs - gameStart) <= SAME_START_MS) score += 15
        val everything = "$heading $description ${normalize(program.categories.joinToString(" "))}"
        if (sportWords.any { has(everything, it) }) score += 10
        return score
    }

    /** Between copies of a channel: the HD one first, the backups last. */
    private fun channelBonus(channel: LiveTvChannel): Int {
        val name = normalize(channel.name)
        return when {
            has(name, "backup") || has(name, "sd") -> -3
            has(name, "fhd") || has(name, "uhd") || has(name, "4k") -> 2
            has(name, "hd") -> 1
            else -> 0
        }
    }
}

/**
 * Plays a live game: finds the channel showing it in the Live TV guide (the guide loads first
 * when it isn't yet) and gives it ready to play, like the Guide's own channels.
 */
@HiltViewModel
class SportsWatchViewModel @Inject constructor(
    private val guideLoader: LiveTvGuideLoader,
    private val liveTvDataStore: LiveTvDataStore
) : ViewModel() {

    /** The channel showing [event] now, ready to play; null when no channel is showing it. */
    suspend fun liveChannelFor(event: SportsEvent): LiveTvChannel? {
        val sources = runCatching { liveTvDataStore.sources.first() }.getOrDefault(emptyList()).filter { it.enabled }
        if (sources.isEmpty()) return null
        guideLoader.ensure(sources)
        val now = System.currentTimeMillis()
        // On the guide as it is; else once its programs have loaded (a little while at most).
        var airing = guideLoader.guide.value?.let { guide -> bestAiring(guide, event, now) }
        if (airing == null) {
            val loaded = withTimeoutOrNull(GUIDE_WAIT_MS) {
                guideLoader.guide.first { guide -> guide != null && !guide.isLoading && !guide.isEpgLoading }
            } ?: guideLoader.guide.value
            airing = loaded?.let { guide -> bestAiring(guide, event, System.currentTimeMillis()) }
        }
        val channel = airing?.channel ?: return null
        return channel.copy(streamUrl = guideLoader.resolvePlaybackUrl(channel, sources))
    }

    private suspend fun bestAiring(guide: LiveGuideSnapshot, event: SportsEvent, now: Long): GameAiring? {
        if (guide.isPreview || guide.channels.isEmpty()) return null
        return withContext(Dispatchers.Default) {
            SportsGameChannels.find(guide.channels, guide.epgByChannel, event, now).firstOrNull()
        }
    }

    private companion object {
        const val GUIDE_WAIT_MS = 12_000L
    }
}

/**
 * What choosing a game does on the Sports pages: a live game plays right away on the channel
 * showing it (the Guide's player, like any channel); an upcoming or finished game opens its page.
 * A live game no channel is showing opens its page too, saying so.
 */
@Composable
fun rememberSportsGameOpener(
    onOpenGamePage: (SportsEvent) -> Unit,
    onPlayChannel: (LiveTvChannel) -> Unit,
    viewModel: SportsWatchViewModel = hiltViewModel()
): (SportsEvent) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val openPage by rememberUpdatedState(onOpenGamePage)
    val play by rememberUpdatedState(onPlayChannel)
    val busy = remember { booleanArrayOf(false) }
    return remember(viewModel, scope, context) {
        { event: SportsEvent ->
            if (!event.isLiveAt(System.currentTimeMillis())) {
                openPage(event)
            } else if (!busy[0]) {
                busy[0] = true
                scope.launch {
                    // Looking takes a moment when the guide is still loading: say so.
                    val notice = launch {
                        delay(700)
                        Toast.makeText(context, R.string.sports_finding_game_channel, Toast.LENGTH_SHORT).show()
                    }
                    val channel = try {
                        viewModel.liveChannelFor(event)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        null
                    } finally {
                        notice.cancel()
                        busy[0] = false
                    }
                    // Left the page meanwhile: nothing to open.
                    if (!isActive) return@launch
                    if (channel != null) {
                        play(channel)
                    } else {
                        Toast.makeText(context, R.string.sports_game_not_on_channels, Toast.LENGTH_LONG).show()
                        openPage(event)
                    }
                }
            }
        }
    }
}
