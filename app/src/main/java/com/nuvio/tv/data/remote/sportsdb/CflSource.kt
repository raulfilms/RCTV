package com.nuvio.tv.data.remote.sportsdb

import com.nuvio.tv.data.remote.espn.EspnSportsClient
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import com.nuvio.tv.domain.model.SportsLeague
import com.nuvio.tv.domain.model.SportsTeam
import com.nuvio.tv.domain.model.SportsTeamGame
import com.nuvio.tv.domain.model.SportsTeamProfile
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The CFL (Canadian Football League). ESPN's data for it stopped in 2022, so its games come from
 * TheSportsDB: its weeks are TheSportsDB's "rounds" (the next game says which week it is), its
 * teams are the ones in them. The weeks found are kept (also on disk); a team's page fills in the
 * rest of its season as the free key's requests allow.
 */
internal class CflSource(
    private val okHttpClient: OkHttpClient,
    private val cacheFile: File?
) {

    /** One game, as TheSportsDB has it. */
    data class Game(
        val id: String,
        val round: Int,
        val startMs: Long?,
        val homeId: String,
        val homeName: String,
        val homeBadge: String?,
        val awayId: String,
        val awayName: String,
        val awayBadge: String?,
        val homeScore: Int?,
        val awayScore: Int?,
        val status: String?,
        val venue: String?
    )

    /** A week's games, and when they were looked at. */
    private data class Week(val savedAt: Long, val games: List<Game>)

    private val lock = Any()
    @Volatile private var season: String? = null
    @Volatile private var nextRound: Int = 0
    @Volatile private var nextCheckedAt: Long = 0
    @Volatile private var weeks: Map<Int, Week>? = null

    val league: SportsLeague = SportsLeague(
        id = LEAGUE_ID,
        name = LEAGUE_NAME,
        sportName = SPORT_LABEL,
        badgeUrl = LOGO,
        country = "Canada"
    )

    /** The games in [datesRange] (ESPN's "yyyyMMdd[-yyyyMMdd]", UTC days); none given, from three days ago on. */
    fun fetchFeed(feed: EspnSportsClient.Feed, datesRange: String?): EspnSportsClient.FeedResult {
        refreshAroundNow()
        val now = System.currentTimeMillis()
        val (from, to) = rangeOf(datesRange)
        val games = allGames().filter { inRange(it.startMs, from, to, now) }.sortedBy { it.startMs ?: Long.MAX_VALUE }
        return EspnSportsClient.FeedResult(feed = feed, league = league, events = games.map { eventOf(it, now) }, teams = teams())
    }

    /** The teams in the weeks found (all nine, once a few weeks are in). */
    fun teams(): List<SportsTeam> {
        val seen = LinkedHashMap<String, SportsTeam>()
        allGames().sortedByDescending { it.startMs ?: 0L }.forEach { game ->
            listOf(Triple(game.homeId, game.homeName, game.homeBadge), Triple(game.awayId, game.awayName, game.awayBadge))
                .forEach { (id, name, badge) ->
                    val teamId = TEAM_ID_PREFIX + id
                    if (teamId !in seen) {
                        seen[teamId] = SportsTeam(
                            id = teamId,
                            name = name,
                            shortName = TEAMS[name]?.nickname ?: name,
                            sportName = SPORT_LABEL,
                            leagueId = LEAGUE_ID,
                            leagueName = LEAGUE_NAME,
                            badgeUrl = badge,
                            color = TEAMS[name]?.color
                        )
                    }
                }
        }
        return seen.values.sortedBy { it.name }
    }

    fun teamProfile(teamId: String): SportsTeamProfile {
        val team = teams().firstOrNull { it.id == teamId } ?: throw EspnSportsClient.EspnException("No team")
        val known = TEAMS[team.name]
        val venue = allGames().firstOrNull { TEAM_ID_PREFIX + it.homeId == teamId && !it.venue.isNullOrBlank() }?.venue
        return SportsTeamProfile(
            id = team.id,
            leagueId = LEAGUE_ID,
            location = known?.city,
            nickname = known?.nickname,
            displayName = team.name,
            abbreviation = known?.abbreviation,
            color = team.color,
            alternateColor = null,
            logoUrl = team.badgeUrl,
            recordSummary = recordOf(teamId),
            standingSummary = known?.division,
            playoffSeed = null,
            points = null,
            venueName = venue,
            venueCity = known?.city,
            venueImageUrl = null
        )
    }

    /** The team's season: the weeks found, and the rest as the requests allow. */
    fun teamSchedule(teamId: String): List<SportsTeamGame> {
        refreshAroundNow()
        fillSeason()
        val now = System.currentTimeMillis()
        return allGames()
            .filter { TEAM_ID_PREFIX + it.homeId == teamId || TEAM_ID_PREFIX + it.awayId == teamId }
            .sortedBy { it.startMs ?: Long.MAX_VALUE }
            .map { game ->
                SportsTeamGame(
                    event = eventOf(game, now),
                    weekLabel = if (game.round in 1..REGULAR_SEASON_WEEKS) "Week ${game.round}" else "Playoffs",
                    weekNumber = game.round,
                    seasonTypeLabel = if (game.round in 1..REGULAR_SEASON_WEEKS) "Regular Season" else "Postseason",
                    seasonType = if (game.round in 1..REGULAR_SEASON_WEEKS) 2 else 3,
                    broadcast = null
                )
            }
    }

    /** "12-5": the games it won and lost of the ones found. */
    private fun recordOf(teamId: String): String? {
        var wins = 0
        var losses = 0
        var ties = 0
        allGames().forEach { game ->
            val home = TEAM_ID_PREFIX + game.homeId == teamId
            val away = TEAM_ID_PREFIX + game.awayId == teamId
            if (!home && !away) return@forEach
            if (!isFinal(game.status)) return@forEach
            val mine = (if (home) game.homeScore else game.awayScore) ?: return@forEach
            val theirs = (if (home) game.awayScore else game.homeScore) ?: return@forEach
            when {
                mine > theirs -> wins++
                mine < theirs -> losses++
                else -> ties++
            }
        }
        if (wins + losses + ties == 0) return null
        return if (ties > 0) "$wins-$losses-$ties" else "$wins-$losses"
    }

    // ------------------------------------------------------------------ the weeks

    private fun allGames(): List<Game> = loaded().values.flatMap { it.games }.distinctBy { it.id }

    private fun loaded(): Map<Int, Week> {
        weeks?.let { return it }
        synchronized(lock) {
            weeks?.let { return it }
            val fromDisk = runCatching { readCache() }.getOrDefault(emptyMap())
            weeks = fromDisk
            return fromDisk
        }
    }

    /** The week on now and the ones around it, looked at again every few minutes. */
    private fun refreshAroundNow() {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (now - nextCheckedAt > NEXT_CHECK_MS || season == null) {
                val body = SportsDbRequests.get(okHttpClient, "eventsnextleague.php?id=$SPORTSDB_LEAGUE")
                if (body != null) {
                    val next = runCatching { JSONObject(body).optJSONArray("events")?.optJSONObject(0) }.getOrNull()
                    next?.optString("intRound")?.toIntOrNull()?.let { nextRound = it }
                    next?.str("strSeason")?.let { season = it }
                    nextCheckedAt = now
                } else if (season == null) {
                    // Not reachable yet: what's on disk.
                    season = savedSeason
                    nextRound = savedNextRound
                }
            }
            val round = nextRound.takeIf { it > 0 } ?: return
            for (r in (round - 2).coerceAtLeast(1)..(round + 1)) {
                val week = loaded()[r]
                // A week with a game on (or about to be) looked at often; the others now and then.
                val busy = week?.games?.any { game ->
                    val start = game.startMs ?: return@any false
                    now > start - BUSY_BEFORE_MS && now < start + BUSY_AFTER_MS
                } == true
                val maxAge = if (busy) BUSY_REFRESH_MS else WEEK_REFRESH_MS
                if (week != null && now - week.savedAt < maxAge) continue
                fetchWeek(r) ?: break
            }
        }
    }

    /** The season's other weeks (for a team's page), as many as this minute's requests allow. */
    private fun fillSeason() {
        synchronized(lock) {
            val round = nextRound.takeIf { it > 0 } ?: return
            val last = maxOf(round + 1, REGULAR_SEASON_WEEKS + PLAYOFF_WEEKS)
            for (r in 1..last) {
                if (loaded()[r] != null) continue
                if (SportsDbRequests.left() <= 4) return
                fetchWeek(r) ?: return
            }
        }
    }

    /** One week's games, kept; null when it couldn't be looked at (no requests left, offline). */
    private fun fetchWeek(round: Int): Week? {
        val currentSeason = season ?: return null
        val body = SportsDbRequests.get(okHttpClient, "eventsround.php?id=$SPORTSDB_LEAGUE&r=$round&s=$currentSeason") ?: return null
        val events = runCatching { JSONObject(body).optJSONArray("events") }.getOrNull()
        val games = (0 until (events?.length() ?: 0)).mapNotNull { index -> events?.optJSONObject(index)?.let(::gameOf) }
        val week = Week(System.currentTimeMillis(), games)
        weeks = loaded() + (round to week)
        runCatching { writeCache() }
        return week
    }

    private fun gameOf(raw: JSONObject): Game? {
        val id = raw.str("idEvent") ?: return null
        val homeId = raw.str("idHomeTeam") ?: return null
        val awayId = raw.str("idAwayTeam") ?: return null
        return Game(
            id = id,
            round = raw.optString("intRound").toIntOrNull() ?: 0,
            startMs = parseUtc(raw.str("strTimestamp")) ?: parseUtc(raw.str("dateEvent")?.let { "${it}T${raw.str("strTime") ?: "00:00:00"}" }),
            homeId = homeId,
            homeName = raw.str("strHomeTeam").orEmpty(),
            homeBadge = raw.str("strHomeTeamBadge"),
            awayId = awayId,
            awayName = raw.str("strAwayTeam").orEmpty(),
            awayBadge = raw.str("strAwayTeamBadge"),
            homeScore = raw.str("intHomeScore")?.toIntOrNull(),
            awayScore = raw.str("intAwayScore")?.toIntOrNull(),
            status = raw.str("strStatus"),
            venue = raw.str("strVenue")
        )
    }

    // ------------------------------------------------------------------ the game as an event

    private fun eventOf(game: Game, now: Long): SportsEvent {
        val start = game.startMs
        val status = when {
            isFinal(game.status) -> SportsEventStatus.FINISHED
            game.status != null && game.status.uppercase(Locale.US) !in NOT_STARTED -> SportsEventStatus.LIVE
            start != null && now > start + GAME_MS -> SportsEventStatus.FINISHED
            else -> SportsEventStatus.UPCOMING
        }
        val home = TEAMS[game.homeName]
        val away = TEAMS[game.awayName]
        return SportsEvent(
            id = "espn:$SPORT_PATH:$LEAGUE_PATH:${game.id}",
            name = "${game.awayName} at ${game.homeName}",
            sportName = SPORT_LABEL,
            leagueId = LEAGUE_ID,
            leagueName = LEAGUE_NAME,
            homeTeamId = TEAM_ID_PREFIX + game.homeId,
            homeTeamName = home?.nickname?.let { "${home.city} $it" } ?: game.homeName,
            homeTeamBadgeUrl = game.homeBadge,
            awayTeamId = TEAM_ID_PREFIX + game.awayId,
            awayTeamName = away?.nickname?.let { "${away.city} $it" } ?: game.awayName,
            awayTeamBadgeUrl = game.awayBadge,
            homeScore = game.homeScore.takeIf { status != SportsEventStatus.UPCOMING },
            awayScore = game.awayScore.takeIf { status != SportsEventStatus.UPCOMING },
            startTimeMs = start,
            venue = game.venue,
            status = status,
            statusDetail = when (status) {
                SportsEventStatus.FINISHED -> "Final"
                SportsEventStatus.LIVE -> game.status?.takeUnless { isFinal(it) }
                SportsEventStatus.UPCOMING -> null
            },
            homeTeamAbbreviation = home?.abbreviation,
            awayTeamAbbreviation = away?.abbreviation,
            homeTeamColor = home?.color,
            awayTeamColor = away?.color,
            homeTeamAliases = listOfNotNull(game.homeName, home?.nickname, home?.alias).distinct(),
            awayTeamAliases = listOfNotNull(game.awayName, away?.nickname, away?.alias).distinct()
        )
    }

    // ------------------------------------------------------------------ the disk

    @Volatile private var savedSeason: String? = null
    @Volatile private var savedNextRound: Int = 0

    private fun readCache(): Map<Int, Week> {
        val file = cacheFile?.takeIf { it.exists() } ?: return emptyMap()
        val json = JSONObject(file.readText())
        if (json.optInt("version") != CACHE_VERSION) return emptyMap()
        savedSeason = json.str("season")
        savedNextRound = json.optInt("nextRound")
        val result = HashMap<Int, Week>()
        val weeksJson = json.optJSONObject("weeks") ?: return emptyMap()
        weeksJson.keys().forEach { key ->
            val round = key.toIntOrNull() ?: return@forEach
            val entry = weeksJson.optJSONObject(key) ?: return@forEach
            val games = entry.optJSONArray("games") ?: JSONArray()
            result[round] = Week(
                savedAt = entry.optLong("savedAt"),
                games = (0 until games.length()).mapNotNull { index -> games.optJSONObject(index)?.let(::gameFromJson) }
            )
        }
        return result
    }

    private fun writeCache() {
        val file = cacheFile ?: return
        val weeksJson = JSONObject()
        loaded().forEach { (round, week) ->
            weeksJson.put(
                round.toString(),
                JSONObject()
                    .put("savedAt", week.savedAt)
                    .put("games", JSONArray().apply { week.games.forEach { put(gameToJson(it)) } })
            )
        }
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject()
                .put("version", CACHE_VERSION)
                .putOpt("season", season)
                .put("nextRound", nextRound)
                .put("weeks", weeksJson)
                .toString()
        )
    }

    private fun gameToJson(game: Game): JSONObject = JSONObject()
        .put("id", game.id)
        .put("round", game.round)
        .putOpt("startMs", game.startMs)
        .put("homeId", game.homeId)
        .put("homeName", game.homeName)
        .putOpt("homeBadge", game.homeBadge)
        .put("awayId", game.awayId)
        .put("awayName", game.awayName)
        .putOpt("awayBadge", game.awayBadge)
        .putOpt("homeScore", game.homeScore)
        .putOpt("awayScore", game.awayScore)
        .putOpt("status", game.status)
        .putOpt("venue", game.venue)

    private fun gameFromJson(json: JSONObject): Game? = Game(
        id = json.str("id") ?: return null,
        round = json.optInt("round"),
        startMs = if (json.has("startMs")) json.optLong("startMs") else null,
        homeId = json.str("homeId") ?: return null,
        homeName = json.str("homeName").orEmpty(),
        homeBadge = json.str("homeBadge"),
        awayId = json.str("awayId") ?: return null,
        awayName = json.str("awayName").orEmpty(),
        awayBadge = json.str("awayBadge"),
        homeScore = if (json.has("homeScore")) json.optInt("homeScore") else null,
        awayScore = if (json.has("awayScore")) json.optInt("awayScore") else null,
        status = json.str("status"),
        venue = json.str("venue")
    )

    private fun JSONObject.str(key: String): String? = optString(key).trim().takeIf { it.isNotBlank() && it != "null" }

    companion object {
        const val SPORT_PATH = "football"
        const val LEAGUE_PATH = "cfl"
        const val LEAGUE_ID = "$SPORT_PATH:$LEAGUE_PATH"
        const val LEAGUE_NAME = "CFL"
        const val SPORT_LABEL = "Football"
        /** TheSportsDB's CFL badge. */
        const val LOGO = "https://r2.thesportsdb.com/images/media/league/badge/ffypv51488739128.png"
        /** The CFL's teams by TheSportsDB's ids, kept apart from ESPN's. */
        const val TEAM_ID_PREFIX = "tsdb-"

        private const val SPORTSDB_LEAGUE = 4405
        private const val CACHE_VERSION = 1
        private const val REGULAR_SEASON_WEEKS = 21
        private const val PLAYOFF_WEEKS = 3
        private const val NEXT_CHECK_MS = 30L * 60 * 1000
        private const val WEEK_REFRESH_MS = 6L * 60 * 60 * 1000
        private const val BUSY_REFRESH_MS = 10L * 60 * 1000
        private const val BUSY_BEFORE_MS = 60L * 60 * 1000
        private const val BUSY_AFTER_MS = 5L * 60 * 60 * 1000
        private const val GAME_MS = 4L * 60 * 60 * 1000
        private const val THREE_DAYS_MS = 3L * 24 * 60 * 60 * 1000
        private val DAY_KEY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)
        private val NOT_STARTED = setOf("NS", "NOT STARTED", "TBD", "POSTPONED", "PST", "CANC", "CANCELLED")
        private val FINAL_STATUSES = setOf("FT", "AOT", "AET", "MATCH FINISHED", "FINISHED", "FINAL", "AP")

        /** What TheSportsDB doesn't say about the nine teams: city, nickname, short name, color, division. */
        private data class Club(
            val city: String,
            val nickname: String,
            val abbreviation: String,
            val color: String,
            val division: String,
            val alias: String? = null
        )

        private val TEAMS = mapOf(
            "BC Lions" to Club("BC", "Lions", "BC", "F15A22", "West Division", "British Columbia Lions"),
            "Calgary Stampeders" to Club("Calgary", "Stampeders", "CGY", "C8102E", "West Division", "Stamps"),
            "Edmonton Elks" to Club("Edmonton", "Elks", "EDM", "00573F", "West Division"),
            "Saskatchewan Roughriders" to Club("Saskatchewan", "Roughriders", "SSK", "006341", "West Division", "Riders"),
            "Winnipeg Blue Bombers" to Club("Winnipeg", "Blue Bombers", "WPG", "002F6C", "West Division", "Bombers"),
            "Hamilton Tiger-Cats" to Club("Hamilton", "Tiger-Cats", "HAM", "FFB81C", "East Division", "Ticats"),
            "Montreal Alouettes" to Club("Montreal", "Alouettes", "MTL", "0E2240", "East Division", "Als"),
            "Ottawa Redblacks" to Club("Ottawa", "Redblacks", "OTT", "C8102E", "East Division", "Ottawa RedBlacks"),
            "Toronto Argonauts" to Club("Toronto", "Argonauts", "TOR", "5F8FB4", "East Division", "Argos")
        )

        private fun isFinal(status: String?): Boolean = status?.uppercase(Locale.US) in FINAL_STATUSES

        private fun parseUtc(text: String?): Long? {
            if (text.isNullOrBlank()) return null
            return runCatching { LocalDateTime.parse(text.take(19)).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
        }

        private fun rangeOf(datesRange: String?): Pair<String?, String?> {
            if (datesRange.isNullOrBlank()) return null to null
            val parts = datesRange.split('-')
            return parts.first() to parts.last()
        }

        private fun inRange(startMs: Long?, from: String?, to: String?, now: Long): Boolean {
            val start = startMs ?: return false
            if (from == null || to == null) return start >= now - THREE_DAYS_MS
            val day = DAY_KEY.format(Instant.ofEpochMilli(start).atOffset(ZoneOffset.UTC))
            return day in from..to
        }
    }
}
