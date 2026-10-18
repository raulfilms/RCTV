package com.nuvio.tv.data.remote.espn

import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import com.nuvio.tv.domain.model.SportsLeague
import com.nuvio.tv.domain.model.SportsTeam
import com.nuvio.tv.domain.model.SportsTeamGame
import com.nuvio.tv.domain.model.SportsTeamProfile
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Banana Ball (the Banana Ball Championship League: the Savannah Bananas and the league's other
 * teams). ESPN doesn't cover it, so its six teams are known here and its schedule comes from the
 * league's own site (bananaball.com/schedule): the season's games, where they're played, and on
 * what. The site has no scores or standings to read.
 */
internal class BananaBallSource(private val okHttpClient: OkHttpClient) {

    @Volatile private var cached: Pair<Long, List<BananaBallSchedule.Game>>? = null

    val league: SportsLeague = SportsLeague(
        id = LEAGUE_ID,
        name = LEAGUE_NAME,
        sportName = SPORT_LABEL,
        badgeUrl = LEAGUE_LOGO,
        country = "USA"
    )

    fun teams(): List<SportsTeam> = BananaBallSchedule.TEAMS.map { it.toSportsTeam() }

    fun team(teamId: String): BananaBallSchedule.Team? = BananaBallSchedule.TEAMS.firstOrNull { it.id == teamId }

    /**
     * The games in [datesRange] (ESPN's "yyyyMMdd" or "yyyyMMdd-yyyyMMdd", UTC days, like the other
     * leagues); none given, every game from three days ago on.
     */
    fun fetchFeed(feed: EspnSportsClient.Feed, datesRange: String?): EspnSportsClient.FeedResult {
        val now = System.currentTimeMillis()
        val events = schedule().mapIndexed { index, game -> eventOf(game, index, now) }
        val (from, to) = BananaBallSchedule.rangeOf(datesRange)
        val inRange = events.filter { event ->
            val start = event.startTimeMs ?: return@filter false
            if (from == null || to == null) {
                start >= now - THREE_DAYS_MS
            } else {
                val day = DAY_KEY.format(java.time.Instant.ofEpochMilli(start).atOffset(ZoneOffset.UTC))
                day in from..to
            }
        }
        return EspnSportsClient.FeedResult(feed = feed, league = league, events = inRange, teams = teams())
    }

    fun teamProfile(teamId: String): SportsTeamProfile {
        val team = team(teamId) ?: throw EspnSportsClient.EspnException("No team")
        return SportsTeamProfile(
            id = team.id,
            leagueId = LEAGUE_ID,
            location = team.location,
            nickname = team.nickname,
            displayName = team.name,
            abbreviation = team.abbreviation,
            color = team.color,
            alternateColor = null,
            logoUrl = team.logoUrl,
            recordSummary = null,
            standingSummary = null,
            playoffSeed = null,
            points = null,
            venueName = null,
            venueCity = null,
            venueImageUrl = null
        )
    }

    /** The team's games this season, in order. */
    fun teamSchedule(teamId: String): List<SportsTeamGame> {
        val now = System.currentTimeMillis()
        return schedule().mapIndexedNotNull { index, game ->
            if (game.homeTeamId != teamId && game.awayTeamId != teamId) return@mapIndexedNotNull null
            SportsTeamGame(
                event = eventOf(game, index, now),
                weekLabel = null,
                weekNumber = null,
                seasonTypeLabel = null,
                seasonType = null,
                broadcast = game.broadcast
            )
        }
    }

    private fun schedule(): List<BananaBallSchedule.Game> {
        val now = System.currentTimeMillis()
        cached?.let { (savedAt, games) -> if (now - savedAt < CACHE_MS) return games }
        val request = Request.Builder()
            .url(SCHEDULE_URL)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        val html = okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw EspnSportsClient.EspnException("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        val games = BananaBallSchedule.parseSchedule(html, LocalDate.now(BananaBallSchedule.EASTERN))
        if (games.isEmpty()) throw EspnSportsClient.EspnException("No Banana Ball games found")
        cached = now to games
        return games
    }

    private fun eventOf(game: BananaBallSchedule.Game, index: Int, now: Long): SportsEvent {
        val home = game.homeTeamId?.let(::team)
        val away = game.awayTeamId?.let(::team)
        val start = ZonedDateTime.of(game.date, game.time ?: BananaBallSchedule.DEFAULT_START, game.zone).toInstant().toEpochMilli()
        val over = now > start + GAME_LENGTH_MS
        val homeName = if (game.multiTeam) LEAGUE_NAME else home?.name.orEmpty()
        val awayName = if (game.multiTeam) MULTI_TEAM_NAME else away?.name.orEmpty()
        val day = game.date.format(DateTimeFormatter.BASIC_ISO_DATE)
        return SportsEvent(
            id = "espn:baseball:$LEAGUE_PATH:" + if (game.multiTeam) "$day-event$index" else "$day-${game.awayTeamId}-${game.homeTeamId}",
            name = if (game.multiTeam) MULTI_TEAM_NAME else "$awayName at $homeName",
            sportName = SPORT_LABEL,
            leagueId = LEAGUE_ID,
            leagueName = LEAGUE_NAME,
            homeTeamId = home?.id,
            homeTeamName = homeName,
            homeTeamBadgeUrl = home?.logoUrl ?: LEAGUE_LOGO,
            awayTeamId = away?.id,
            awayTeamName = awayName,
            awayTeamBadgeUrl = away?.logoUrl ?: LEAGUE_LOGO,
            homeScore = null,
            awayScore = null,
            startTimeMs = start,
            venue = game.venue,
            status = if (over) SportsEventStatus.FINISHED else SportsEventStatus.UPCOMING,
            // The site hasn't said when it starts yet: shown as TBA, not as the hour it's put at.
            statusDetail = if (game.time == null && !over) TIME_TBA else null,
            homeTeamAbbreviation = home?.abbreviation,
            awayTeamAbbreviation = away?.abbreviation,
            homeTeamColor = home?.color,
            awayTeamColor = away?.color,
            homeTeamAliases = home?.let { listOf(it.name, it.nickname) + it.siteNames }.orEmpty(),
            awayTeamAliases = away?.let { listOf(it.name, it.nickname) + it.siteNames }.orEmpty()
        )
    }

    private fun BananaBallSchedule.Team.toSportsTeam() = SportsTeam(
        id = id,
        name = name,
        shortName = name,
        sportName = SPORT_LABEL,
        leagueId = LEAGUE_ID,
        leagueName = LEAGUE_NAME,
        badgeUrl = logoUrl,
        color = color
    )

    companion object {
        const val LEAGUE_PATH = "banana-ball"
        const val LEAGUE_ID = "baseball:$LEAGUE_PATH"
        const val LEAGUE_NAME = "Banana Ball"
        const val SPORT_LABEL = "Baseball"
        /** What a game whose start isn't set yet says instead of a time. */
        const val TIME_TBA = "TBA"
        private const val MULTI_TEAM_NAME = "Multi-team Event"
        private const val SCHEDULE_URL = "https://bananaball.com/schedule/"
        private const val LEAGUE_LOGO = "https://bananaball.com/wp-content/uploads/2024/09/bbcl.png?w=256"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
        private const val CACHE_MS = 30L * 60 * 1000
        private const val THREE_DAYS_MS = 3L * 24 * 60 * 60 * 1000
        /** A Banana Ball game has a two-hour limit; with the show around it, about three. */
        private const val GAME_LENGTH_MS = 4L * 60 * 60 * 1000
        private val DAY_KEY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)

    }
}
