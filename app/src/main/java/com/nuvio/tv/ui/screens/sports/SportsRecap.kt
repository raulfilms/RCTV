package com.nuvio.tv.ui.screens.sports

import com.nuvio.tv.data.trailer.YouTubeSearchResult
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import java.time.Instant
import java.time.ZoneId

/** How long after its start a game is surely over (when its page doesn't say so). */
private const val GAME_OVER_AFTER_MS = 4L * 60 * 60 * 1000

/** The game is over: marked final, or started long enough ago. */
internal fun SportsEvent.isOverAt(now: Long): Boolean = when (status) {
    SportsEventStatus.FINISHED -> true
    SportsEventStatus.LIVE -> false
    SportsEventStatus.UPCOMING -> startTimeMs?.let { start -> now > start + GAME_OVER_AFTER_MS } == true
}

/**
 * Finds a game's highlights on YouTube: what to search ("Tampa Bay Buccaneers vs Dallas Cowboys
 * highlights 2026"), and which of the videos found is them — both teams in the title, "Highlights"
 * (or recap), published after the game, a highlights' length, an official channel first (NFL, NBA,
 * NBC Sports...). Not reactions, previews, video-game simulations, live streams or old games.
 */
internal object SportsRecapPicker {

    /** Videos about a game that aren't its highlights. */
    private val NOT_HIGHLIGHTS = listOf(
        "reaction", "reacts", "reacting", "live stream", "livestream", "watch party", "watchalong",
        "preview", "prediction", "predictions", "picks", "odds", "betting", "madden", "2k", "fifa",
        "ea fc", "eafc", "simulation", "simulated", "sim", "press conference", "postgame", "pregame",
        "podcast", "rumor", "rumors", "fantasy", "trailer", "breakdown", "film study", "mic d up"
    )

    /** "NBA 2K26", "FC 26", "Madden 27": video games. */
    private val VIDEO_GAME = Regex("""\b(2k\d{0,2}|fc \d{2}|madden \d{2})\b""")

    private val HIGHLIGHT_WORDS = listOf(
        "highlights", "highlight", "recap", "resumen", "resumenes", "goles", "all goals", "extended highlights",
        "mejores jugadas", "lo mejor"
    )

    /** A title in Spanish: "Resumen", "Goles", "Mejores jugadas", "Jornada". */
    private val SPANISH_WORDS = listOf("resumen", "resumenes", "goles", "gol", "mejores jugadas", "lo mejor", "jornada", "partido", "y goles")

    /** Channels in Spanish (soccer highlights in Spanish first). */
    private val SPANISH_CHANNELS = listOf(
        "espn deportes", "espn fans", "espn mx", "espn fc latam", "tudn", "telemundo deportes", "univision deportes",
        "fox deportes", "vix", "laliga", "liga mx", "movistar", "dazn espana", "tyc sports", "caracol",
        "sky sports mexico", "mls en espanol", "concacaf", "deportes", "futbol"
    )

    /** Channels that post the leagues' own highlights. */
    private val OFFICIAL_CHANNELS = listOf(
        "nfl", "nba", "mlb", "nhl", "wnba", "nbc sports", "peacock", "cbs sports", "cbs sports golazo",
        "espn", "espn fc", "fox sports", "fox soccer", "premier league", "laliga", "bundesliga",
        "serie a", "ligue 1", "mls", "major league soccer", "liga mx", "tudn", "telemundo deportes",
        "univision deportes", "ufc", "formula 1", "f1", "bein sports", "tnt sports", "dazn",
        "house of highlights", "uefa", "concacaf", "ncaa", "big ten", "sec network",
        "nascar", "nascar on fox", "nascar on nbc", "wwe", "aew", "all elite wrestling", "top rank", "top rank boxing",
        "matchroom", "matchroom boxing", "premier boxing champions", "golden boy", "queensberry", "the ring", "dazn boxing"
    )

    /** Words too common to stand for a team on their own ("City", "United"). */
    private val GENERIC_WORDS = setOf(
        "city", "united", "club", "town", "county", "athletic", "athletico", "atletico", "rovers",
        "wanderers", "albion", "state", "real", "sporting", "fc", "sc", "cf", "de", "the", "new", "san", "los", "las"
    )

    /** Soccer's highlights are in Spanish; every other sport's in English. */
    fun isSpanish(event: SportsEvent): Boolean =
        event.leagueId?.startsWith("soccer") == true || event.sportName.equals("soccer", ignoreCase = true)

    /**
     * What to search: both teams and "highlights" (and the year, for this season's game); soccer in
     * Spanish ("Real Madrid vs Barcelona resumen y goles").
     */
    fun queriesFor(event: SportsEvent): List<String> {
        val home = fullName(event.homeTeamName, event.homeTeamAliases)
        val away = fullName(event.awayTeamName, event.awayTeamAliases)
        val year = event.startTimeMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).year }
        if (isSpanish(event)) {
            return listOf("$home vs $away resumen y goles", "$away vs $home resumen goles ${year ?: ""}".trim()).distinct()
        }
        val suffix = listOfNotNull("highlights", year?.toString()).joinToString(" ")
        // A race or a wrestling show: the event and its series ("YellaWood 500 NASCAR Cup Series highlights").
        if (event.isSoloEvent() && !event.isFight()) {
            return listOf("${event.name} $away $suffix", "${event.name} highlights").distinct()
        }
        return listOf("$away vs $home $suffix", "$home vs $away highlights").distinct()
    }

    /** The team's longest name ("Dallas Cowboys" before "Cowboys"), for titles. */
    fun fullNameOf(name: String, aliases: List<String>): String = fullName(name, aliases)

    /** The team's longest name ("Dallas Cowboys" before "Cowboys"). */
    private fun fullName(name: String, aliases: List<String>): String =
        (aliases + name).filter { it.isNotBlank() }.maxByOrNull { it.length } ?: name

    /** A team's names to find in a title: "dallas cowboys", "cowboys"; "hull city" (not "city"). */
    private fun teamNames(name: String, aliases: List<String>): List<String> {
        val names = (listOf(name) + aliases)
            .map { SportsGameChannels.canonical(SportsGameChannels.normalize(it)) }
            .filter { it.length >= 3 }
        val nicknames = names.mapNotNull { full ->
            val last = full.substringAfterLast(' ')
            last.takeIf { ' ' in full && it.length >= 4 && it !in GENERIC_WORDS }
        }
        return (names + nicknames).distinct()
    }

    private fun has(text: String, phrase: String): Boolean = " $text ".contains(" $phrase ")

    /** The videos that are this game's highlights, the likeliest first. */
    fun pick(results: List<YouTubeSearchResult>, event: SportsEvent, now: Long): List<YouTubeSearchResult> {
        val home = teamNames(event.homeTeamName, event.homeTeamAliases)
        val away = teamNames(event.awayTeamName, event.awayTeamAliases)
        val daysSinceGame = event.startTimeMs?.let { start -> (now - start) / 86_400_000.0 }
        val spanish = isSpanish(event)
        return results
            .mapNotNull { result -> scoreOf(result, home, away, daysSinceGame, spanish)?.let { result to it } }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun scoreOf(
        result: YouTubeSearchResult,
        home: List<String>,
        away: List<String>,
        daysSinceGame: Double?,
        spanish: Boolean
    ): Int? {
        val title = SportsGameChannels.canonical(SportsGameChannels.normalize(result.title))
        val channel = SportsGameChannels.normalize(result.channel.orEmpty())
        // A live stream or a premiere to come: not highlights.
        val seconds = result.durationSeconds ?: return null
        if (NOT_HIGHLIGHTS.any { has(title, it) } || VIDEO_GAME.containsMatchIn(title)) return null
        val homeIn = home.any { has(title, it) }
        val awayIn = away.any { has(title, it) }
        if (!homeIn || !awayIn) return null
        val highlights = HIGHLIGHT_WORDS.any { has(title, it) }
        val official = OFFICIAL_CHANNELS.any { has(channel, it) } ||
            home.any { has(channel, it) } || away.any { has(channel, it) }
        if (!highlights && !official) return null
        // Published before the game: an older game between the same teams.
        val age = result.ageDays
        if (age != null && daysSinceGame != null && age > daysSinceGame + 1.5) return null

        var score = 50
        if (highlights) score += 40
        if (official) score += 25
        score += when {
            seconds < 45 -> -30
            seconds <= 25 * 60 -> 10
            seconds <= 40 * 60 -> -5
            else -> -40
        }
        if (age != null && daysSinceGame != null) score += 10
        // Soccer: in Spanish first (a Spanish title or channel); English ones only when there's none.
        val spanishTitle = SPANISH_WORDS.any { has(title, it) }
        if (spanish) {
            val spanishChannel = SPANISH_CHANNELS.any { has(channel, it) }
            score += when {
                spanishTitle && spanishChannel -> 60
                spanishTitle || spanishChannel -> 40
                else -> -40
            }
        } else if (spanishTitle) {
            // Every other sport: in English.
            score -= 40
        }
        return score
    }
}
