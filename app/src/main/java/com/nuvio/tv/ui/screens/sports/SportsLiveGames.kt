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
import com.nuvio.tv.core.util.FastText
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
        // On now by the guide or YouTube (Banana Ball, whose schedule has no live scores); a game
        // whose time isn't set yet (TBA), or a golf tournament (days long: ESPN says when it's on),
        // doesn't go live by the clock.
        id in BananaBallLiveIds.ids ||
            (!startTimeTba() && !isGolf() && start != null && now >= start && now < start + LIVE_WINDOW_MS)
    }
}

/** Its start time isn't set yet ("TBA", Banana Ball's schedule): only its day is known. */
internal fun SportsEvent.startTimeTba(): Boolean =
    status == SportsEventStatus.UPCOMING && statusDetail.equals(com.nuvio.tv.data.remote.espn.BananaBallSource.TIME_TBA, ignoreCase = true)

/**
 * Its time isn't set (TBA) but it's its day: it may be on now, so a channel showing it is looked
 * for before its page opens.
 */
internal fun SportsEvent.mayBeOnNow(now: Long): Boolean {
    val start = startTimeMs ?: return false
    return startTimeTba() && abs(now - start) < TBA_DAY_MS
}

/** A TBA game is put in its day's evening: this far either side is still its day. */
private const val TBA_DAY_MS = 14L * 60 * 60 * 1000

/** "Sat, Feb 6 • TBA": the day of a game whose time isn't set yet. */
internal fun tbaStartLabel(startMs: Long): String =
    java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault()).format(java.util.Date(startMs)) + " • TBA"

/** The game as the clock has it: an upcoming game whose time has come shows as live. */
internal fun SportsEvent.withClock(now: Long): SportsEvent = when {
    status != SportsEventStatus.UPCOMING || !isLiveAt(now) -> this
    // On now: "TBA" doesn't say where it's at.
    startTimeTba() -> copy(status = SportsEventStatus.LIVE, statusDetail = null)
    else -> copy(status = SportsEventStatus.LIVE)
}

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

    // -------------------------------------------------------------- by the network showing the game

    /** ESPN's short names for networks, as IPTV channels name them. */
    private val NETWORK_ALIASES = mapOf(
        "nflnet" to "nflnetwork", "nfln" to "nflnetwork", "mlbn" to "mlbnetwork", "nhln" to "nhlnetwork",
        "cbssn" to "cbssportsnetwork", "cbssports" to "cbssportsnetwork", "secn" to "secnetwork",
        "secnplus" to "secnetwork", "btn" to "bigtennetwork", "accn" to "accnetwork", "usanet" to "usanetwork",
        "usa" to "usanetwork", "nbatv" to "nbatv", "golf" to "golfchannel", "tennis" to "tennischannel",
        "fs1" to "foxsports1", "fs2" to "foxsports2", "foxsports1" to "foxsports1", "foxsports2" to "foxsports2",
        "tudn" to "tudn", "unimas" to "unimas", "univision" to "univision", "telemundo" to "telemundo",
        "beinsports" to "beinsports", "espndeportes" to "espndeportes", "trutv" to "trutv",
        "pac12" to "pac12network", "longhornnetwork" to "longhornnetwork", "lhn" to "longhornnetwork"
    )

    /** Streaming-only services: no IPTV channel to look for. */
    private val STREAMING_ONLY = setOf(
        "espn+", "espnplus", "peacock", "primevideo", "amazonprimevideo", "appletv", "appletv+", "netflix",
        "max", "paramount+", "paramountplus", "youtube", "youtubetv", "nbaleaguepass", "nhlpowerplay",
        "mlbtv", "nflplus", "dazn", "fubo", "fubotv", "espnapp", "espnunlimited", "foxone"
    )

    /** Over-the-air networks: their IPTV channels are local stations ("FOX 5 New York", "ABC 7 Chicago"). */
    private val BROADCAST_NETWORKS = setOf("abc", "cbs", "nbc", "fox", "cw")

    /** Words after an over-the-air network's name that make it another channel ("FOX News", "CBS Sports"). */
    private val NOT_THE_NETWORK = setOf(
        "news", "sports", "sport", "business", "life", "kids", "family", "soul", "deportes", "comedy",
        "weather", "now", "classics", "movies", "cinema", "reality", "nation", "radio"
    )

    /** "US: ESPN 2 FHD" → "espn2"; "US | NFL Network HD" → "nflnetwork". */
    private fun channelKey(name: String): String {
        val words = normalize(com.nuvio.tv.ui.screens.livetv.LiveTvViewModel.stripCountryTag(name))
            .split(' ')
            .filter { it.isNotBlank() && it !in CHANNEL_NOISE }
        return words.joinToString("")
    }

    private val CHANNEL_NOISE = setOf(
        "hd", "fhd", "uhd", "sd", "4k", "hevc", "h265", "1080p", "720p", "1080", "720", "east", "west",
        "us", "backup", "raw", "60fps", "50fps", "vip"
    )

    private fun networkKey(network: String): String {
        val raw = network.lowercase().replace("+", "plus")
        if (raw.replace(" ", "") in STREAMING_ONLY || network.lowercase().replace(" ", "") in STREAMING_ONLY) return ""
        val key = normalize(network).split(' ').filter { it.isNotBlank() && it !in CHANNEL_NOISE }.joinToString("")
        return NETWORK_ALIASES[key] ?: key
    }

    /**
     * RCTV: the channel of a network showing the game ([networks], ESPN's names), when the
     * provider's guide doesn't name the game: a US channel first, then any. Over-the-air networks
     * match their local stations ("FOX 5 New York" for FOX), never their other channels.
     */
    fun byNetwork(channels: List<LiveTvChannel>, networks: List<String>): LiveTvChannel? {
        for (network in networks) {
            val wanted = networkKey(network)
            if (wanted.length < 2) continue
            val matches = channels.filter { channel ->
                val key = channelKey(channel.name)
                val aliased = NETWORK_ALIASES[key] ?: key
                when {
                    aliased == wanted || key == wanted -> true
                    wanted in BROADCAST_NETWORKS -> {
                        val words = normalize(com.nuvio.tv.ui.screens.livetv.LiveTvViewModel.stripCountryTag(channel.name)).split(' ')
                        words.firstOrNull() == wanted && words.drop(1).none { it in NOT_THE_NETWORK }
                    }
                    else -> false
                }
            }
            if (matches.isEmpty()) continue
            // RCTV (owner): in English, the US's first (never another language's).
            val english = matches.mapNotNull { m -> languageRank(m, emptyList(), emptyList())?.let { m to it } }
            english.minByOrNull { it.second }?.first?.let { return it }
        }
        return null
    }

    /** A program starting this soon can be the game (the guide's slot may start on the hour). */
    private const val STARTING_SOON_MS = 15L * 60 * 1000
    /** A program starting this close to the game's start is the game's slot. */
    private const val SAME_START_MS = 40L * 60 * 1000
    /** Shorter than this, a program with one team only isn't the game. */
    private const val GAME_LENGTH_MS = 80L * 60 * 1000
    private const val MIN_SCORE = 60
    /** A Banana Ball game with no time yet ("TBA", put in the evening): a program that day can be it. */
    private const val BANANA_BALL_DAY_MS = 14L * 60 * 60 * 1000

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
            "motor" in sport || "racing" in sport -> listOf("racing", "motorsport", "motorsports", "auto racing", "carreras")
            "boxing" in sport -> listOf("boxing", "boxeo", "ppv", "pay per view")
            "wrestling" in sport -> listOf("wrestling", "lucha libre", "ppv", "pay per view")
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

    /**
     * "Chicago Bulls" → "chicago bulls": no accents, punctuation or case.
     *
     * RCTV (MEM-07): without regular expressions. This runs for every program on the guide, for
     * every game looked for: it compiled three new ones each time (nine per program), and on
     * Android their native memory piled up past 1 GB in seconds on a Mi Box 4 (the system closed
     * the app on Sports). The same texts come again and again (one program, many games): the
     * latest ones are kept.
     */
    internal fun normalize(text: String): String {
        if (text.length > NORMALIZED_KEPT_MAX_LENGTH) return FastText.asciiWords(text)
        synchronized(normalized) { normalized[text] }?.let { return it }
        val result = FastText.asciiWords(text)
        synchronized(normalized) { normalized[text] = result }
        return result
    }

    /** The latest texts made comparable (a program's title, its first 400 characters of description). */
    private val normalized = object : LinkedHashMap<String, String>(NORMALIZED_KEPT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > NORMALIZED_KEPT
    }
    private const val NORMALIZED_KEPT = 4_096
    private const val NORMALIZED_KEPT_MAX_LENGTH = 600

    private val CLUB_SUFFIXES = setOf("fc", "cf", "afc", "sc")

    /** A team's names to look for: "chicago bulls", "bulls"; "arsenal" (not "arsenal fc"). */
    private fun teamNames(name: String, aliases: List<String>): List<String> =
        (listOf(name) + aliases)
            .map { canonical(normalize(it)) }
            .map { FastText.withoutWords(it, CLUB_SUFFIXES) }
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
        // Banana Ball's times are often "TBA" (or just its schedule's), as are some PPV shows': any
        // program of the game on now is it.
        val gameStart = event.startTimeMs?.takeUnless { event.isBananaBall() || event.isGolf() || event.startTimeTba() }
        val found = ArrayList<GameAiring>()
        val seen = HashSet<String>()
        channels.forEach { channel ->
            val channelPrograms = epgByChannel[channel.epgChannelId ?: channel.id]
            // A channel named after the game, often with no guide: a PPV show ("PPV 1: Fundora vs
            // Hadribeaj"); RCTV, any game a provider's event channels carry today ("NBA 1: Boston
            // Celtics vs Charlotte Hornets 7:00 PM ET", "EPL 01: Everton v Coventry City", "(63) Yale
            // vs. Brown (2026-11-06 18:00:00 ET)").
            ppvChannelAiring(channel, channelPrograms, home, away, event, now)?.let { airing ->
                if (seen.add("${channel.id}|ppv")) found += airing
            }
            // RCTV (owner): a team's own channel ("NFL | Washington Commanders") during its game.
            teamChannelAiring(channel, channelPrograms, home, away, event, now)?.let { airing ->
                if (seen.add("${channel.id}|team")) found += airing
            }
            val programs = channelPrograms ?: return@forEach
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
        // RCTV (owner): games in English: the US's channels first (and the provider's channels
        // named after the game), then the UK's, then other English-speaking countries'; never a
        // channel in another language (a Brazilian SporTV for Thursday Night Football).
        return found
            .mapNotNull { airing -> languageRank(airing.channel, home, away)?.let { airing to it } }
            .sortedWith(compareBy<Pair<GameAiring, Int>> { it.second }.thenByDescending { it.first.score })
            .map { it.first }
    }

    /**
     * Where [channel] stands for a game in English: 0 the US's (or one named after the game, which
     * the provider's English event channels are), 1 the UK's or a country it doesn't say, 2 another
     * English-speaking country's; null for another language's, never shown for a game.
     */
    internal fun languageRank(channel: LiveTvChannel, home: List<String>, away: List<String>): Int? {
        val country = countryOf(channel)
        if (country != null) return ENGLISH_COUNTRIES[country]
        val name = canonical(normalize(channel.name))
        val group = canonical(normalize(channel.groupTitle.orEmpty()))
        if (NOT_ENGLISH.any { has(name, it) || has(group, it) }) return null
        return if (teamIn(name, home) > 0 && teamIn(name, away) > 0) 0 else 1
    }

    private fun countryOf(channel: LiveTvChannel): String? {
        channel.country?.let { com.nuvio.tv.ui.screens.livetv.LiveTvCountry.normalize(it) }?.let { return it }
        for (text in listOfNotNull(channel.name, channel.groupTitle)) {
            val code = com.nuvio.tv.core.util.GuideText.countryPrefix(text)?.first ?: continue
            com.nuvio.tv.ui.screens.livetv.LiveTvCountry.normalize(code)?.let { return it }
        }
        return null
    }

    /** RCTV (owner): the US first, then the UK, then other English-speaking countries. */
    private val ENGLISH_COUNTRIES = mapOf("US" to 0, "GB" to 1, "CA" to 2, "IE" to 2, "AU" to 2, "NZ" to 2)

    /** Channels in another language that don't say their country. */
    private val NOT_ENGLISH = listOf(
        "sportv", "deportes", "espanol", "en espanol", "tudn", "univision", "telemundo", "movistar",
        "globo", "premiere", "sky sport", "dazn", "eleven", "viaplay", "ziggo", "rmc", "canal", "latino",
        "brasil", "brazil", "portugal", "mexico", "argentina", "france", "italia", "deutschland"
    )

    /** The leagues whose teams have their own channels with some providers. */
    private val TEAM_CHANNEL_LEAGUES = setOf("nfl", "nba", "mlb", "nhl", "wnba", "mls")

    /**
     * RCTV (owner): a team's own channel during its game: named after one of the two teams and its
     * league ("NFL | Washington Commanders", in an "NBA Teams" group), from a little before the game
     * to its end. Just below a channel named after the game; the home team's first.
     */
    private fun teamChannelAiring(
        channel: LiveTvChannel,
        programs: List<EpgProgram>?,
        home: List<String>,
        away: List<String>,
        event: SportsEvent,
        now: Long
    ): GameAiring? {
        val league = event.leagueId?.substringAfter(':')?.substringBefore('.')?.lowercase()
            ?.takeIf { it in TEAM_CHANNEL_LEAGUES } ?: return null
        val name = canonical(normalize(channel.name))
        val group = canonical(normalize(channel.groupTitle.orEmpty()))
        if (!has(name, league) && !has(group, league)) return null
        val homeIn = teamIn(name, home) > 0
        val awayIn = teamIn(name, away) > 0
        if (homeIn == awayIn) return null // neither team, or both (the game's own channel, above)
        if (NOT_THE_GAME.any { has(name, it) || has(group, it) }) return null
        val start = event.startTimeMs ?: return null
        if (now < start - STARTING_SOON_MS || now > start + LIVE_WINDOW_MS) return null
        val program = programs?.firstOrNull { it.isAiringAt(now) } ?: EpgProgram(
            channelId = channel.epgChannelId ?: channel.id,
            title = event.name,
            startMs = now,
            endMs = now + LIVE_WINDOW_MS,
            isLive = true
        )
        return GameAiring(channel, program, TEAM_CHANNEL_SCORE + (if (homeIn) 1 else 0) + channelBonus(channel))
    }

    /** A team's own channel: below one named after the game. */
    private const val TEAM_CHANNEL_SCORE = 100

    /**
     * How surely a guide program (now or later) is [event] itself, 0 when it isn't: the game's teams
     * in it, at the game's time (not another game between the same teams, nor a show about it).
     */
    fun scoreFor(program: EpgProgram, event: SportsEvent): Int {
        val home = teamNames(event.homeTeamName, event.homeTeamAliases)
        val away = teamNames(event.awayTeamName, event.awayTeamAliases)
        if (home.isEmpty() || away.isEmpty()) return 0
        val gameStart = event.startTimeMs ?: return 0
        if (event.isGolf()) {
            // A tournament: any of its days.
            val lastDay = (event.endTimeMs ?: (gameStart + 3 * 24 * 60 * 60 * 1000L)) + 24 * 60 * 60 * 1000L
            if (program.endMs <= gameStart || program.startMs >= lastDay) return 0
            val score = scoreOf(program, home, away, sportWords(event), null)
            return if (score >= MIN_SCORE) score else 0
        }
        if (event.isBananaBall() || event.startTimeTba()) {
            // Its schedule's time is often "TBA": the game's day is what counts.
            if (abs(program.startMs - gameStart) > BANANA_BALL_DAY_MS) return 0
            val score = scoreOf(program, home, away, sportWords(event), null)
            return if (score >= MIN_SCORE) score else 0
        }
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

    /**
     * A channel whose name is the PPV show (both fighters, or the show and its promotion), on the
     * show's day: it's showing it (its program now, else the show itself).
     */
    private fun ppvChannelAiring(
        channel: LiveTvChannel,
        programs: List<EpgProgram>?,
        home: List<String>,
        away: List<String>,
        event: SportsEvent,
        now: Long
    ): GameAiring? {
        val name = canonical(normalize(channel.name))
        if (teamIn(name, home) == 0 || teamIn(name, away) == 0) return null
        // A replay or a show about it ("NBA | Replay"), not the game.
        val group = normalize(channel.groupTitle.orEmpty())
        if (NOT_THE_GAME.any { has(name, it) || has(group, it) }) return null
        val start = event.startTimeMs ?: return null
        if (abs(now - start) > BANANA_BALL_DAY_MS) return null
        val program = programs?.firstOrNull { it.isAiringAt(now) } ?: EpgProgram(
            channelId = channel.epgChannelId ?: channel.id,
            title = event.name,
            startMs = now,
            endMs = now + LIVE_WINDOW_MS,
            isLive = true
        )
        return GameAiring(channel, program, PPV_CHANNEL_SCORE + channelBonus(channel))
    }

    /** A channel named after the show: as sure as a program with both teams in its title. */
    private const val PPV_CHANNEL_SCORE = 110

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
    private val liveTvDataStore: LiveTvDataStore,
    private val bananaBallLive: BananaBallLive,
    private val espnClient: com.nuvio.tv.data.remote.espn.EspnSportsClient,
    private val playerSession: com.nuvio.tv.data.livetv.LiveTvPlayerSession
) : ViewModel() {

    /** A Banana Ball game no channel is showing: its live stream on YouTube, ready to play; else null. */
    suspend fun youTubeStreamFor(event: SportsEvent): String? = bananaBallLive.youTubeStreamFor(event)

    /** The channel showing [event] now, ready to play; null when no channel is showing it. */
    suspend fun liveChannelFor(event: SportsEvent): LiveTvChannel? {
        val sources = runCatching { liveTvDataStore.sources.first() }.getOrDefault(emptyList()).filter { it.enabled }
        if (sources.isEmpty()) return null
        guideLoader.ensure(sources)
        val now = System.currentTimeMillis()
        // On the guide as it is; else once its programs have loaded (a little while at most).
        var airing = guideLoader.guide.value?.let { guide -> com.nuvio.tv.core.diagnostics.NativeMemoryGuard.during("game-channels") { bestAiring(guide, event, now) } }
        if (airing == null) {
            val loaded = withTimeoutOrNull(GUIDE_WAIT_MS) {
                guideLoader.guide.first { guide -> guide != null && !guide.isLoading && !guide.isEpgLoading }
            } ?: guideLoader.guide.value
            airing = loaded?.let { guide -> com.nuvio.tv.core.diagnostics.NativeMemoryGuard.during("game-channels") { bestAiring(guide, event, System.currentTimeMillis()) } }
        }
        // RCTV (owner's rule): the provider's guide doesn't name the game: the channel of the network
        // showing it (ESPN, TNT, FOX...), as ESPN says.
        val channel = airing?.channel ?: networkChannelFor(event) ?: return null
        // RCTV (owner): the player's Networks row has the Guide's channels even when Live TV wasn't
        // opened first (it was empty from a game).
        guideLoader.guide.value?.takeIf { !it.isPreview && it.channels.isNotEmpty() }?.let { guide ->
            if (playerSession.guide.value.channels.isEmpty()) {
                playerSession.publish(
                    com.nuvio.tv.data.livetv.LiveTvPlayerSession.Guide(
                        channels = guide.channels,
                        epgByChannel = guide.epgByChannel,
                        customChannelIds = liveTvDataStore.customChannelIds.value
                    )
                )
            }
        }
        return channel.copy(streamUrl = guideLoader.resolvePlaybackUrl(channel, sources))
    }

    private suspend fun networkChannelFor(event: SportsEvent): LiveTvChannel? {
        val guide = guideLoader.guide.value ?: return null
        if (guide.isPreview || guide.channels.isEmpty()) return null
        val networks = withContext(Dispatchers.IO) {
            try {
                espnClient.fetchBroadcasts(event.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                emptyList()
            }
        }
        if (networks.isEmpty()) return null
        return withContext(Dispatchers.Default) { SportsGameChannels.byNetwork(guide.allChannels, networks) }
    }

    private suspend fun bestAiring(guide: LiveGuideSnapshot, event: SportsEvent, now: Long): GameAiring? {
        if (guide.isPreview || guide.channels.isEmpty()) return null
        return withContext(Dispatchers.Default) {
            SportsGameChannels.find(guide.allChannels, guide.epgByChannel, event, now).firstOrNull()
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
    /** A stream to play as live (a Banana Ball game streaming on YouTube): its URL and the game. */
    onPlayStream: ((url: String, event: SportsEvent) -> Unit)? = null,
    viewModel: SportsWatchViewModel = hiltViewModel()
): (SportsEvent) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val openPage by rememberUpdatedState(onOpenGamePage)
    val play by rememberUpdatedState(onPlayChannel)
    val playStream by rememberUpdatedState(onPlayStream)
    val busy = remember { booleanArrayOf(false) }
    return remember(viewModel, scope, context) {
        { event: SportsEvent ->
            val now = System.currentTimeMillis()
            // Its time not set but its day: played if a channel has it on, else its page.
            val maybeOn = !event.isLiveAt(now) && event.mayBeOnNow(now)
            if (!event.isLiveAt(now) && !maybeOn) {
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
                    // Not on a channel: Banana Ball streams its games on YouTube.
                    val streamPlayer = playStream
                    val youTubeStream = if (channel == null && streamPlayer != null && event.isBananaBall() && !maybeOn) {
                        Toast.makeText(context, R.string.sports_finding_game_youtube, Toast.LENGTH_SHORT).show()
                        busy[0] = true
                        try {
                            viewModel.youTubeStreamFor(event)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            null
                        } finally {
                            busy[0] = false
                        }
                    } else {
                        null
                    }
                    if (!isActive) return@launch
                    if (channel != null) {
                        // RCTV: the player loads with the two teams, not the channel's logo.
                        com.nuvio.tv.ui.screens.player.LiveGameMatchup.set(
                            com.nuvio.tv.ui.screens.player.GameMatchupArt.of(event)
                        )
                        play(channel)
                    } else if (youTubeStream != null && streamPlayer != null) {
                        com.nuvio.tv.ui.screens.player.LiveGameMatchup.set(
                            com.nuvio.tv.ui.screens.player.GameMatchupArt.of(event)
                        )
                        streamPlayer(youTubeStream, event)
                    } else {
                        // Only a game surely on now says it isn't on the channels.
                        if (!maybeOn) Toast.makeText(context, R.string.sports_game_not_on_channels, Toast.LENGTH_LONG).show()
                        openPage(event)
                    }
                }
            }
        }
    }
}
