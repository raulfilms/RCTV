package com.nuvio.tv.ui.screens.sports

import com.nuvio.tv.data.trailer.YouTubeSearchResult
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

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
 * highlights October 12 2026"), and which of the videos found is them — both teams in the title,
 * "Highlights" (or recap), published after the game, a highlights' length, an official channel first
 * (NFL, NBA, NBC Sports...). Not reactions, previews, video-game simulations, live streams or old games.
 *
 * RCTV: the same two teams often play again days later (a baseball series, a hockey home-and-home,
 * division rivals twice a season), so the game itself is checked too: a date in the title must be
 * this game's, a score this game's final, a week ("Week 5") this game's; and it must have come out
 * within a few days of this game, the one that came out nearest to it first.
 */
internal object SportsRecapPicker {

    /** Highlights come out within hours of a game; a video from days later is another game's. */
    private const val MAX_DAYS_AFTER_GAME = 4.0

    /** About how long after a game starts its highlights come out. */
    private const val HOURS_TO_HIGHLIGHTS = 4.0

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

    /** Words that make a school's name another school's ("Michigan" in "Michigan State"). */
    private val NAME_AFTER = setOf("state", "st", "tech", "a", "southern", "christian", "poly", "international")
    private val NAME_BEFORE = setOf("north", "south", "east", "west", "northern", "southern", "eastern", "western", "central", "new")

    private val MONTHS_EN = listOf(
        "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"
    )
    private val MONTHS_ES = listOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre",
        "noviembre", "diciembre"
    )
    /** "October 12, 2026", "Oct. 12", "Sept 3rd". */
    private val DATE_EN = Regex(
        """\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b(?:,?\s+(\d{4}))?"""
    )
    /** "12 de octubre de 2026". */
    private val DATE_ES = Regex(
        """\b(\d{1,2})\s+de\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|sept?iembre|octubre|noviembre|diciembre)\b(?:\s+(?:de\s+)?(\d{4}))?"""
    )
    /** "10/12/26", "10/12/2026", "10/12" (or day first, "12/10/2026"). */
    private val DATE_NUMERIC = Regex("""(?<![\d/])(\d{1,2})/(\d{1,2})(?:/(\d{4}|\d{2}))?(?![\d/])""")
    /** A score: "27-24", "2 - 1", "(3–2)". */
    private val SCORE = Regex("""(?<![\d/:.\-–—])(\d{1,3})\s*[-–—]\s*(\d{1,3})(?![\d/:.%\-–—])""")
    private val WEEK = Regex("""\bweek\s+(\d{1,2})\b""")

    private val US_ZONES = listOf("America/New_York", "America/Chicago", "America/Los_Angeles", "America/Puerto_Rico")
        .map { ZoneId.of(it) }
    private val SOCCER_ZONES = listOf(
        "Europe/London", "Europe/Madrid", "America/Mexico_City", "America/New_York", "America/Los_Angeles", "America/Sao_Paulo"
    ).map { ZoneId.of(it) }

    /**
     * The time zones a game's date is told in: its league's (a 7 pm game in New York is already the
     * next day in Madrid, which mustn't make the next day's game look like this one).
     */
    private fun zonesOf(event: SportsEvent): List<ZoneId> {
        val league = event.leagueId.orEmpty().lowercase(Locale.ROOT)
        return when {
            isSpanish(event) -> SOCCER_ZONES
            "jpn" in league || "npb" in league -> listOf(ZoneId.of("Asia/Tokyo"))
            "kbo" in league || "kor" in league -> listOf(ZoneId.of("Asia/Seoul"))
            "aus" in league || "nrl" in league || "afl" in league -> listOf(ZoneId.of("Australia/Sydney"))
            else -> US_ZONES
        }
    }

    /** The game's date as its league tells it (a late game in Los Angeles is the next day in New York). */
    private fun gameDates(event: SportsEvent): Set<LocalDate> {
        val start = event.startTimeMs ?: return emptySet()
        val instant = Instant.ofEpochMilli(start)
        return zonesOf(event).map { instant.atZone(it).toLocalDate() }.toSet()
    }

    /** The dates written in a title: month, day and the year when it's given. */
    private data class TitleDate(val month: Int, val day: Int, val year: Int?)

    private fun fullYear(text: String?): Int? = text?.toIntOrNull()?.let { if (it < 100) 2000 + it else it }

    private fun datesIn(title: String): List<TitleDate> {
        val dates = ArrayList<TitleDate>()
        DATE_EN.findAll(title).forEach { match ->
            val month = MONTHS_EN.indexOf(match.groupValues[1].take(3)) + 1
            val day = match.groupValues[2].toIntOrNull() ?: return@forEach
            if (month in 1..12 && day in 1..31) dates += TitleDate(month, day, fullYear(match.groupValues[3].ifEmpty { null }))
        }
        DATE_ES.findAll(title).forEach { match ->
            val name = match.groupValues[2].replace("setiembre", "septiembre")
            val month = MONTHS_ES.indexOf(name) + 1
            val day = match.groupValues[1].toIntOrNull() ?: return@forEach
            if (month in 1..12 && day in 1..31) dates += TitleDate(month, day, fullYear(match.groupValues[3].ifEmpty { null }))
        }
        DATE_NUMERIC.findAll(title).forEach { match ->
            val first = match.groupValues[1].toInt()
            val second = match.groupValues[2].toInt()
            val year = fullYear(match.groupValues[3].ifEmpty { null })
            // Month first (US) or day first: both are kept, either may be the game's.
            if (first in 1..12 && second in 1..31) dates += TitleDate(first, second, year)
            if (second in 1..12 && first in 1..31 && first != second) dates += TitleDate(second, first, year)
        }
        return dates
    }

    private fun TitleDate.isOn(dates: Set<LocalDate>): Boolean =
        dates.any { it.monthValue == month && it.dayOfMonth == day && (year == null || it.year == year) }

    /** The scores written in a title ("Real Madrid 2-1 Barcelona"), not dates or years. */
    private fun scoresIn(title: String): List<Pair<Int, Int>> =
        SCORE.findAll(title).mapNotNull { match ->
            // "Week 5-..." or "Game 2-..." isn't a score.
            val before = title.substring(0, match.range.first).trimEnd()
            if (before.endsWith("week") || before.endsWith("game") || before.endsWith("semana")) return@mapNotNull null
            val a = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val b = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            a to b
        }.toList()

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
        // The game's date first ("October 12 2026"): the leagues' highlights say it in the title.
        val dated = event.startTimeMs?.let { start ->
            val date = Instant.ofEpochMilli(start).atZone(ZoneId.of("America/New_York")).toLocalDate()
            val month = date.month.getDisplayName(TextStyle.FULL, Locale.US)
            "$away vs $home highlights $month ${date.dayOfMonth} ${date.year}"
        }
        return listOfNotNull(dated, "$away vs $home $suffix", "$home vs $away highlights").distinct()
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

    /**
     * The team's name in the title as the team itself: "michigan" in "michigan vs ohio state", not in
     * "michigan state vs ohio state" (nor "carolina" in "north carolina").
     */
    private fun hasTeam(text: String, phrase: String): Boolean {
        val padded = " $text "
        var from = 0
        while (true) {
            val at = padded.indexOf(" $phrase ", from)
            if (at < 0) return false
            val before = padded.substring(0, at).trimEnd().substringAfterLast(' ')
            val after = padded.substring(at + phrase.length + 2).trimStart().substringBefore(' ')
            val otherSchool = (after in NAME_AFTER && !phrase.endsWith(" $after")) ||
                (before in NAME_BEFORE && !phrase.startsWith("$before "))
            if (!otherSchool) return true
            from = at + 1
        }
    }

    /**
     * The videos that are this game's highlights, the likeliest first. [week]: the game's week
     * (football), when known.
     */
    fun pick(
        results: List<YouTubeSearchResult>,
        event: SportsEvent,
        now: Long,
        week: Int? = null
    ): List<YouTubeSearchResult> {
        val home = teamNames(event.homeTeamName, event.homeTeamAliases)
        val away = teamNames(event.awayTeamName, event.awayTeamAliases)
        val daysSinceGame = event.startTimeMs?.let { start -> (now - start) / 86_400_000.0 }
        val spanish = isSpanish(event)
        val game = GameFacts(
            dates = gameDates(event),
            finalScore = if (event.homeScore != null && event.awayScore != null) event.homeScore to event.awayScore else null,
            week = week
        )
        return results
            .mapNotNull { result -> scoreOf(result, home, away, daysSinceGame, spanish, game)?.let { result to it } }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /** What a video's title is checked against: the game's date, final score and week. */
    private class GameFacts(val dates: Set<LocalDate>, val finalScore: Pair<Int, Int>?, val week: Int?)

    private fun scoreOf(
        result: YouTubeSearchResult,
        home: List<String>,
        away: List<String>,
        daysSinceGame: Double?,
        spanish: Boolean,
        game: GameFacts
    ): Int? {
        val title = SportsGameChannels.canonical(SportsGameChannels.normalize(result.title))
        val channel = SportsGameChannels.normalize(result.channel.orEmpty())
        // A live stream or a premiere to come: not highlights.
        val seconds = result.durationSeconds ?: return null
        if (NOT_HIGHLIGHTS.any { has(title, it) } || VIDEO_GAME.containsMatchIn(title)) return null
        val homeIn = home.any { hasTeam(title, it) }
        val awayIn = away.any { hasTeam(title, it) }
        if (!homeIn || !awayIn) return null
        val highlights = HIGHLIGHT_WORDS.any { has(title, it) }
        val official = OFFICIAL_CHANNELS.any { has(channel, it) } ||
            home.any { has(channel, it) } || away.any { has(channel, it) }
        if (!highlights && !official) return null
        // When it came out: between [age] and [age] + its unit ago ("2 weeks ago": 14 to 21 days).
        val age = result.ageDays
        val ageUnit = result.ageUnitDays ?: 1.0
        // Published before the game: an older game between the same teams.
        if (age != null && daysSinceGame != null && age > daysSinceGame + 0.5) return null
        // Published days after the game: a later game between the same teams.
        if (age != null && daysSinceGame != null && age + ageUnit < daysSinceGame - MAX_DAYS_AFTER_GAME) return null

        // The game itself, when the title says it: its date, its final score, its week.
        val rawTitle = result.title.lowercase(Locale.ROOT)
        var gameScore = 0
        if (game.dates.isNotEmpty()) {
            val dates = datesIn(rawTitle)
            if (dates.isNotEmpty()) {
                // Another day's game.
                if (dates.none { it.isOn(game.dates) }) return null
                gameScore += 60
            }
        }
        game.finalScore?.let { (homeScore, awayScore) ->
            val scores = scoresIn(rawTitle)
            if (scores.isNotEmpty()) {
                gameScore += if (scores.any { (a, b) -> (a == homeScore && b == awayScore) || (a == awayScore && b == homeScore) }) 60 else -45
            }
        }
        game.week?.let { week ->
            val weeks = WEEK.findAll(rawTitle).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
            if (weeks.isNotEmpty()) {
                if (week !in weeks) return null
                gameScore += 40
            }
        }

        var score = 50 + gameScore
        if (highlights) score += 40
        if (official) score += 25
        score += when {
            seconds < 45 -> -30
            seconds <= 25 * 60 -> 10
            seconds <= 40 * 60 -> -5
            else -> -40
        }
        // Out soon after the game: the nearest to it first (two games days apart).
        if (age != null && daysSinceGame != null) {
            val expected = (daysSinceGame - HOURS_TO_HIGHLIGHTS / 24.0).coerceAtLeast(0.0)
            val distance = when {
                expected < age -> age - expected
                expected > age + ageUnit -> expected - (age + ageUnit)
                else -> 0.0
            }
            score += if (distance == 0.0) 20 else -(distance * 8).toInt().coerceAtMost(30)
        }
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
