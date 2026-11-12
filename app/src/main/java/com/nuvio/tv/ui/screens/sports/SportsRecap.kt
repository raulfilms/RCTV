package com.nuvio.tv.ui.screens.sports

import com.nuvio.tv.data.remote.espn.BananaBallSource
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

/** RCTV (owner's list): the YouTube channel a league's recaps come from, and only from. */
internal data class RecapChannel(
    /** The channel's handle on YouTube ("@NBA"). */
    val handle: String,
    /** Its titles are in Spanish ("Resumen") rather than English ("Highlights"). */
    val spanish: Boolean,
    /** What to search for there besides the two teams. */
    val words: String,
    /** A title with this first (NBC Sports' "Extended Highlights" for the Premier League). */
    val preferred: String? = null,
    /** Any video of the game there will do (Banana Ball's), not only one called highlights. */
    val anyVideo: Boolean = false
)

internal object OfficialRecapChannels {

    private val BY_LEAGUE: Map<String, RecapChannel> = mapOf(
        "soccer:esp.1" to RecapChannel("@LaLiga", spanish = true, words = "resumen"),
        "soccer:eng.1" to RecapChannel("@NBCSports", spanish = false, words = "extended highlights", preferred = "extended"),
        "soccer:ger.1" to RecapChannel("@bundesliga", spanish = false, words = "highlights"),
        "soccer:ita.1" to RecapChannel("@seriea", spanish = false, words = "highlights"),
        "soccer:usa.1" to RecapChannel("@mls", spanish = false, words = "highlights"),
        "soccer:uefa.champions" to RecapChannel("@tudn_usa", spanish = true, words = "resumen"),
        "hockey:nhl" to RecapChannel("@NHL", spanish = false, words = "highlights"),
        "basketball:wnba" to RecapChannel("@WNBA", spanish = false, words = "highlights"),
        "baseball:mlb" to RecapChannel("@MLB", spanish = false, words = "highlights"),
        "football:nfl" to RecapChannel("@NFL", spanish = false, words = "highlights"),
        "basketball:nba" to RecapChannel("@NBA", spanish = false, words = "highlights"),
        BananaBallSource.LEAGUE_ID to RecapChannel("@officialbananaball", spanish = false, words = "highlights", anyVideo = true)
    )

    /** College football, basketball and baseball: the official highlights only (see [SportsRecapPicker]). */
    private val COLLEGE = setOf(
        "football:college-football", "basketball:mens-college-basketball",
        "basketball:womens-college-basketball", "baseball:college-baseball"
    )

    /** The league's own channel; null for a league that has none on the list (YouTube's search then). */
    fun of(event: SportsEvent): RecapChannel? = event.leagueId?.lowercase(Locale.ROOT)?.let { BY_LEAGUE[it] }

    fun isCollege(event: SportsEvent): Boolean = event.leagueId?.lowercase(Locale.ROOT) in COLLEGE
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

    /** RCTV: shorter than this is a clip or a Short, not a game's recap. */
    private const val MIN_RECAP_SECONDS = 60

    /** RCTV: "#shorts" in a title: a vertical Short. */
    private val SHORT_TAG = Regex("""#shorts?\b""", RegexOption.IGNORE_CASE)

    internal fun isShortTitle(title: String): Boolean = SHORT_TAG.containsMatchIn(title)

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
        "uefa", "concacaf", "ncaa", "big ten", "sec network",
        "nascar", "nascar on fox", "nascar on nbc", "wwe", "aew", "all elite wrestling", "top rank", "top rank boxing",
        "matchroom", "matchroom boxing", "premier boxing champions", "golden boy", "queensberry", "the ring", "dazn boxing"
    )

    /**
     * RCTV: the channels a college game's official highlights come from (the networks that show
     * the games, the conferences, the NCAA). Else only the schools' own channels.
     */
    private val COLLEGE_CHANNELS = listOf(
        "espn", "espnu", "espn college football", "espn college basketball", "espn college sports", "sportscenter",
        "cbs sports", "cbs sports college football", "cbs sports college basketball", "cbs sports hq",
        "fox sports", "fox college football", "fox college hoops", "big ten network", "big ten", "btn",
        "sec network", "sec", "acc network", "acc digital network", "accdn", "the acc", "big 12 conference",
        "big 12 network", "big 12", "pac 12 network", "pac 12 conference", "pac 12", "big east conference",
        "big east", "mountain west", "american athletic conference", "the american", "ncaa", "ncaa championships",
        "ncaa march madness", "march madness", "ncaa baseball", "d1baseball", "nbc sports", "peacock", "tnt sports",
        "the cw sports", "cw sports", "abc sports"
    ).map { SportsGameChannels.normalize(it) }.toSet()

    /**
     * RCTV (owner's rule: official recaps only): words in a channel's name that make it a fan's
     * re-upload channel ("NBA Highlights HD", "Real Madrid Fans"), not the league's, network's or team's own.
     */
    private val FAN_CHANNEL_WORDS = listOf(
        "highlights", "highlight", "fan", "fans", "hd", "4k", "clips", "edits", "daily", "mix",
        "compilation", "compilations", "replay", "replays", "goals", "goles", "videos", "zone", "hub"
    )

    /** RCTV: a channel named as an official one ("ESPN", "ESPN Deportes", "TUDN USA"), not one that only mentions it. */
    private fun isOfficialName(channel: String): Boolean =
        OFFICIAL_CHANNELS.any { channel == it || channel.startsWith("$it ") }

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
        // RCTV: a college game's official highlights.
        if (OfficialRecapChannels.isCollege(event)) {
            return listOfNotNull(dated, "$away vs $home official highlights", "$away vs $home $suffix").distinct()
        }
        return listOfNotNull(dated, "$away vs $home $suffix", "$home vs $away highlights").distinct()
    }

    /** RCTV: what to search in the league's own channel: the two teams, and its word for highlights. */
    fun channelQueriesFor(event: SportsEvent, channel: RecapChannel): List<String> {
        val home = fullName(event.homeTeamName, event.homeTeamAliases)
        val away = fullName(event.awayTeamName, event.awayTeamAliases)
        val teams = if (isSpanish(event)) "$home $away" else "$away $home"
        return listOf("$teams ${channel.words}", teams).distinct()
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
     * (football), when known. [channel]: the league's own channel: only its videos are taken.
     */
    fun pick(
        results: List<YouTubeSearchResult>,
        event: SportsEvent,
        now: Long,
        week: Int? = null,
        channel: RecapChannel? = null
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
        val college = channel == null && OfficialRecapChannels.isCollege(event)
        return results
            .filter { channel == null || it.channelHandle.equals(channel.handle, ignoreCase = true) }
            .mapNotNull { result -> scoreOf(result, home, away, daysSinceGame, spanish, game, channel, college)?.let { result to it } }
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
        game: GameFacts,
        recapChannel: RecapChannel? = null,
        college: Boolean = false
    ): Int? {
        val title = SportsGameChannels.canonical(SportsGameChannels.normalize(result.title))
        val channel = SportsGameChannels.normalize(result.channel.orEmpty())
        // A live stream or a premiere to come: not highlights.
        val seconds = result.durationSeconds ?: return null
        // RCTV (owner's rule): recaps are always 16:9, never a vertical Short (checked again before playing).
        if (result.isShort || isShortTitle(result.title) || seconds < MIN_RECAP_SECONDS) return null
        if (NOT_HIGHLIGHTS.any { has(title, it) } || VIDEO_GAME.containsMatchIn(title)) return null
        val homeIn = home.any { hasTeam(title, it) }
        val awayIn = away.any { hasTeam(title, it) }
        if (!homeIn || !awayIn) return null
        val highlights = HIGHLIGHT_WORDS.any { has(title, it) }
        val fanChannel = FAN_CHANNEL_WORDS.any { has(channel, it) }
        val teamChannel = !fanChannel && (home.any { has(channel, it) } || away.any { has(channel, it) })
        val official = when {
            // The league's own channel (only its videos get here).
            recapChannel != null -> true
            // A college game: the networks', conferences' and NCAA's channels, or the schools' own.
            college -> channel in COLLEGE_CHANNELS || teamChannel
            else -> !fanChannel && isOfficialName(channel) || teamChannel
        }
        // RCTV (owner's rule): every recap is official: the league's, a network's that shows it, or a team's own.
        if (!official) return null
        if (!highlights && recapChannel != null && !recapChannel.anyVideo) return null
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
        // The kind the league's channel is asked for first (the Premier League's extended highlights).
        recapChannel?.preferred?.let { if (has(title, it)) score += 30 }
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
