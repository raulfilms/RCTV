package com.nuvio.tv.data.remote.espn

import com.nuvio.tv.domain.model.SportsAthlete
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import com.nuvio.tv.domain.model.SportsGameStat
import com.nuvio.tv.domain.model.SportsLeague
import com.nuvio.tv.domain.model.SportsStandingEntry
import com.nuvio.tv.domain.model.SportsStandingStat
import com.nuvio.tv.domain.model.SportsStandingsGroup
import com.nuvio.tv.domain.model.SportsTeam
import com.nuvio.tv.domain.model.SportsTeamGame
import com.nuvio.tv.domain.model.SportsTeamProfile
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Baseball ESPN doesn't keep up: LIDOM (the Dominican league), the Caribbean Series and the Minor
 * Leagues (Triple-A to Single-A), from MLB's own Stats API (statsapi.mlb.com, what MLB.com and
 * MiLB.com read): games with their scores, inning and TV, teams with their logos, standings,
 * rosters and each game's line score.
 */
internal class MlbStatsSource(private val okHttpClient: OkHttpClient) {

    /** One of the leagues: MLB's sport (level) and, for the winter ones, its league. */
    data class StatsLeague(
        val leaguePath: String,
        val name: String,
        val sportId: Int,
        /** The league within the sport (LIDOM in the winter leagues); null: the whole level. */
        val leagueId: Int?,
        val logo: String,
        val country: String?
    )

    private val teamsCache = ConcurrentHashMap<String, Pair<Long, List<JSONObject>>>()
    private val seasonCache = ConcurrentHashMap<String, Pair<Long, SeasonDates?>>()

    private data class SeasonDates(val start: LocalDate, val end: LocalDate)

    fun league(stats: StatsLeague): SportsLeague = SportsLeague(
        id = "$SPORT_PATH:${stats.leaguePath}",
        name = stats.name,
        sportName = SPORT_LABEL,
        badgeUrl = stats.logo,
        country = stats.country
    )

    /**
     * The games in [datesRange] (ESPN's "yyyyMMdd[-yyyyMMdd]", UTC days); none, from three days ago
     * to ten days on. A long range (a page looking for the next games between seasons) is cut to its
     * season's first days ahead, or last days back.
     */
    fun fetchFeed(feed: EspnSportsClient.Feed, datesRange: String?): EspnSportsClient.FeedResult {
        val stats = leagueOf(feed.leaguePath) ?: throw EspnSportsClient.EspnException("No league")
        val today = LocalDate.now(ZoneOffset.UTC)
        var (from, to) = rangeOf(datesRange) ?: (today.minusDays(3) to today.plusDays(10))
        if (ChronoUnit.DAYS.between(from, to) > LONG_RANGE_DAYS) {
            val clamped = clampToSeason(stats, from, to, today) ?: return emptyResult(feed, stats)
            from = clamped.first
            to = clamped.second
        }
        val games = schedule(stats, "startDate=$from&endDate=$to")
        val teams = teams(stats)
        val events = games.mapNotNull { eventOf(it, stats, teams) }
        return EspnSportsClient.FeedResult(feed = feed, league = league(stats), events = events, teams = teams.map { teamOf(it, stats) })
    }

    fun teams(feed: EspnSportsClient.Feed): List<SportsTeam> {
        val stats = leagueOf(feed.leaguePath) ?: return emptyList()
        return teams(stats).map { teamOf(it, stats) }.sortedBy { it.name }
    }

    fun teamProfile(feed: EspnSportsClient.Feed, teamId: String): SportsTeamProfile {
        val stats = leagueOf(feed.leaguePath) ?: throw EspnSportsClient.EspnException("No league")
        val raw = teams(stats).firstOrNull { TEAM_ID_PREFIX + it.optInt("id") == teamId }
            ?: throw EspnSportsClient.EspnException("No team")
        val standing = runCatching { standingOf(stats, teamId) }.getOrNull()
        return SportsTeamProfile(
            id = teamId,
            leagueId = "$SPORT_PATH:${stats.leaguePath}",
            location = raw.str("locationName"),
            nickname = raw.str("teamName"),
            displayName = raw.str("name").orEmpty(),
            abbreviation = raw.str("abbreviation"),
            color = null,
            alternateColor = null,
            logoUrl = logoOf(raw.optInt("id")),
            recordSummary = standing?.first,
            standingSummary = standing?.second,
            playoffSeed = null,
            points = null,
            venueName = raw.optJSONObject("venue")?.str("name"),
            venueCity = raw.str("locationName"),
            venueImageUrl = null
        )
    }

    /** The team's season: its games, the ones played with their scores. */
    fun teamSchedule(feed: EspnSportsClient.Feed, teamId: String): List<SportsTeamGame> {
        val stats = leagueOf(feed.leaguePath) ?: return emptyList()
        val id = teamId.removePrefix(TEAM_ID_PREFIX).toIntOrNull() ?: return emptyList()
        val season = currentSeason(stats)
        val teams = teams(stats)
        return schedule(stats, "teamId=$id&season=$season", byLeague = false)
            .mapNotNull { game -> eventOf(game, stats, teams)?.let { it to game } }
            .sortedBy { it.first.startTimeMs ?: Long.MAX_VALUE }
            .map { (event, game) ->
                val type = game.str("gameType")
                SportsTeamGame(
                    event = event,
                    weekLabel = null,
                    weekNumber = null,
                    seasonTypeLabel = when (type) {
                        "S", "E" -> "Preseason"
                        "R" -> "Regular Season"
                        null -> null
                        else -> "Postseason"
                    },
                    seasonType = when (type) {
                        "S", "E" -> 1
                        "R" -> 2
                        null -> null
                        else -> 3
                    },
                    broadcast = broadcastsOf(game).firstOrNull()
                )
            }
    }

    /** The league's table(s): won, lost, percentage, games back, streak. */
    fun standings(feed: EspnSportsClient.Feed): List<SportsStandingsGroup> {
        val stats = leagueOf(feed.leaguePath) ?: return emptyList()
        val records = standingsRecords(stats)
        val teams = teams(stats).associateBy { it.optInt("id") }
        return records.mapNotNull { record ->
            val rows = record.optJSONArray("teamRecords") ?: return@mapNotNull null
            val entries = (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val teamJson = row.optJSONObject("team") ?: return@mapNotNull null
                val id = teamJson.optInt("id")
                val full = teams[id] ?: teamJson
                SportsStandingEntry(
                    rank = index + 1,
                    team = teamOf(full, stats),
                    stats = listOfNotNull(
                        SportsStandingStat("W", row.optInt("wins").toString()),
                        SportsStandingStat("L", row.optInt("losses").toString()),
                        row.str("winningPercentage")?.let { SportsStandingStat("PCT", it) },
                        row.str("gamesBack")?.let { SportsStandingStat("GB", it) },
                        row.optJSONObject("streak")?.str("streakCode")?.let { SportsStandingStat("STRK", it) }
                    )
                )
            }
            if (entries.isEmpty()) return@mapNotNull null
            val name = record.optJSONObject("division")?.str("name")
                ?: record.optJSONObject("league")?.str("name")
                ?: stats.name
            SportsStandingsGroup(name = name, entries = entries)
        }
    }

    /** A team's players (pitchers, catchers, infielders, outfielders), with MLB's photos. */
    fun roster(feed: EspnSportsClient.Feed, teamId: String): List<SportsAthlete> {
        val stats = leagueOf(feed.leaguePath) ?: return emptyList()
        val id = teamId.removePrefix(TEAM_ID_PREFIX).toIntOrNull() ?: return emptyList()
        val json = JSONObject(get("$API/teams/$id/roster?season=${currentSeason(stats)}"))
        val roster = json.optJSONArray("roster") ?: return emptyList()
        return (0 until roster.length()).mapNotNull { index ->
            val entry = roster.optJSONObject(index) ?: return@mapNotNull null
            val person = entry.optJSONObject("person") ?: return@mapNotNull null
            val personId = person.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
            val position = entry.optJSONObject("position")
            SportsAthlete(
                id = "mlb-person-$personId",
                name = person.str("fullName") ?: return@mapNotNull null,
                jersey = entry.str("jerseyNumber"),
                position = position?.str("abbreviation"),
                headshotUrl = headshotOf(personId),
                group = when (position?.str("type")) {
                    "Pitcher" -> "Pitchers"
                    "Catcher" -> "Catchers"
                    "Infielder" -> "Infielders"
                    "Outfielder" -> "Outfielders"
                    else -> position?.str("type")
                }
            )
        }
    }

    /** A game's line score: runs by inning, then runs, hits and errors. */
    fun lineScore(gamePk: String): List<SportsGameStat> {
        val json = JSONObject(get("$API/game/$gamePk/linescore"))
        val rows = ArrayList<SportsGameStat>()
        val innings = json.optJSONArray("innings") ?: JSONArray()
        for (i in 0 until innings.length()) {
            val inning = innings.optJSONObject(i) ?: continue
            val number = inning.optInt("num", i + 1)
            val home = inning.optJSONObject("home")?.let { if (it.has("runs")) it.optInt("runs").toString() else null } ?: "–"
            val away = inning.optJSONObject("away")?.let { if (it.has("runs")) it.optInt("runs").toString() else null } ?: "–"
            rows += SportsGameStat(label = inningLabel(number), homeValue = home, awayValue = away)
        }
        val teams = json.optJSONObject("teams")
        val home = teams?.optJSONObject("home")
        val away = teams?.optJSONObject("away")
        if (home != null && away != null) {
            rows += SportsGameStat("Runs", home.optInt("runs").toString(), away.optInt("runs").toString())
            rows += SportsGameStat("Hits", home.optInt("hits").toString(), away.optInt("hits").toString())
            rows += SportsGameStat("Errors", home.optInt("errors").toString(), away.optInt("errors").toString())
        }
        return rows
    }

    // ------------------------------------------------------------------ MLB's API

    private fun schedule(stats: StatsLeague, query: String, byLeague: Boolean = true): List<JSONObject> {
        val leagueFilter = if (byLeague && stats.leagueId != null) "&leagueId=${stats.leagueId}" else ""
        val json = JSONObject(get("$API/schedule?sportId=${stats.sportId}$leagueFilter&$query&hydrate=team,linescore,broadcasts(all)"))
        val dates = json.optJSONArray("dates") ?: return emptyList()
        val games = ArrayList<JSONObject>()
        for (i in 0 until dates.length()) {
            val day = dates.optJSONObject(i)?.optJSONArray("games") ?: continue
            for (j in 0 until day.length()) day.optJSONObject(j)?.let { games += it }
        }
        return games
    }

    private fun teams(stats: StatsLeague): List<JSONObject> {
        val key = stats.leaguePath
        val now = System.currentTimeMillis()
        teamsCache[key]?.let { (savedAt, teams) -> if (now - savedAt < TEAMS_CACHE_MS && teams.isNotEmpty()) return teams }
        val leagueFilter = stats.leagueId?.let { "&leagueIds=$it" }.orEmpty()
        val teams = runCatching {
            val json = JSONObject(get("$API/teams?sportId=${stats.sportId}$leagueFilter&season=${currentSeason(stats)}"))
            val array = json.optJSONArray("teams") ?: JSONArray()
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                .filter { it.optBoolean("active", true) }
        }.getOrElse { teamsCache[key]?.second.orEmpty() }
        teamsCache[key] = now to teams
        return teams
    }

    private fun standingsRecords(stats: StatsLeague): List<JSONObject> {
        val leagueIds = stats.leagueId?.toString()
            ?: leaguesOf(stats).joinToString(",").ifBlank { return emptyList() }
        val json = JSONObject(get("$API/standings?leagueId=$leagueIds&season=${currentSeason(stats)}&hydrate=division,league"))
        val records = json.optJSONArray("records") ?: return emptyList()
        return (0 until records.length()).mapNotNull { records.optJSONObject(it) }
    }

    /** "8-4" and "1st in LIDOM": the team's record and place. */
    private fun standingOf(stats: StatsLeague, teamId: String): Pair<String, String?>? {
        for (record in standingsRecords(stats)) {
            val rows = record.optJSONArray("teamRecords") ?: continue
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                if (TEAM_ID_PREFIX + row.optJSONObject("team")?.optInt("id") != teamId) continue
                val group = record.optJSONObject("division")?.str("name") ?: stats.name
                return "${row.optInt("wins")}-${row.optInt("losses")}" to "${ordinal(i + 1)} in $group"
            }
        }
        return null
    }

    /** The level's leagues (Triple-A: the International League and the Pacific Coast League). */
    private fun leaguesOf(stats: StatsLeague): List<Int> {
        val json = runCatching { JSONObject(get("$API/league?sportId=${stats.sportId}")) }.getOrNull() ?: return emptyList()
        val leagues = json.optJSONArray("leagues") ?: return emptyList()
        return (0 until leagues.length()).mapNotNull { leagues.optJSONObject(it) }
            .filter { it.optBoolean("active", true) && it.optBoolean("hasStandings", true) }
            .map { it.optInt("id") }
            .filter { it > 0 }
    }

    /** The season the league's on, or its last (the winter leagues' 2026 season runs into 2027). */
    private fun currentSeason(stats: StatsLeague): Int {
        val today = LocalDate.now(ZoneOffset.UTC)
        // Winter baseball's season is named by the year it starts (October); its January games too.
        return if (stats.sportId == WINTER_SPORT && today.monthValue <= 3) today.year - 1 else today.year
    }

    /**
     * A long range cut to its season: going forward, the first days the next season plays; going
     * back, the last days the last one did. Null when there's no such season.
     */
    private fun clampToSeason(stats: StatsLeague, from: LocalDate, to: LocalDate, today: LocalDate): Pair<LocalDate, LocalDate>? {
        val forward = from >= today.minusDays(4)
        val year = currentSeason(stats)
        if (forward) {
            for (season in listOf(year, year + 1)) {
                val dates = seasonDates(stats, season) ?: continue
                if (dates.end < from) continue
                val start = later(dates.start, from)
                return start to earlier(start.plusDays(SEASON_DAYS), to)
            }
            return null
        }
        for (season in listOf(year, year - 1)) {
            val dates = seasonDates(stats, season) ?: continue
            if (dates.start > to) continue
            val end = earlier(dates.end, to)
            return later(end.minusDays(SEASON_DAYS), from) to end
        }
        return null
    }

    private fun seasonDates(stats: StatsLeague, season: Int): SeasonDates? {
        val key = "${stats.leaguePath}:$season"
        val now = System.currentTimeMillis()
        seasonCache[key]?.let { (savedAt, dates) -> if (now - savedAt < TEAMS_CACHE_MS) return dates }
        val dates = runCatching {
            val json = JSONObject(get("$API/league?sportId=${stats.sportId}&season=$season"))
            val leagues = json.optJSONArray("leagues") ?: JSONArray()
            val infos = (0 until leagues.length()).mapNotNull { leagues.optJSONObject(it) }
                .filter { stats.leagueId == null || it.optInt("id") == stats.leagueId }
                .mapNotNull { it.optJSONObject("seasonDateInfo") }
            val starts = infos.mapNotNull { info -> info.str("regularSeasonStartDate")?.let(::parseDay) }
            val ends = infos.mapNotNull { info ->
                (info.str("postSeasonEndDate") ?: info.str("regularSeasonEndDate") ?: info.str("seasonEndDate"))?.let(::parseDay)
            }
            if (starts.isEmpty() || ends.isEmpty()) null else SeasonDates(starts.minBy { it.toEpochDay() }, ends.maxBy { it.toEpochDay() })
        }.getOrNull()
        seasonCache[key] = now to dates
        return dates
    }

    // ------------------------------------------------------------------ games and teams

    private fun eventOf(game: JSONObject, stats: StatsLeague, teams: List<JSONObject>): SportsEvent? {
        val pk = game.optLong("gamePk").takeIf { it > 0 } ?: return null
        val teamsJson = game.optJSONObject("teams") ?: return null
        val homeSide = teamsJson.optJSONObject("home") ?: return null
        val awaySide = teamsJson.optJSONObject("away") ?: return null
        val home = fullTeam(homeSide.optJSONObject("team"), teams) ?: return null
        val away = fullTeam(awaySide.optJSONObject("team"), teams) ?: return null
        val status = game.optJSONObject("status")
        val state = status?.str("abstractGameState")
        val detailed = status?.str("detailedState")
        val linescore = game.optJSONObject("linescore")
        val eventStatus = when {
            state.equals("Final", ignoreCase = true) -> SportsEventStatus.FINISHED
            state.equals("Live", ignoreCase = true) && !detailed.equals("Warmup", ignoreCase = true) -> SportsEventStatus.LIVE
            else -> SportsEventStatus.UPCOMING
        }
        val detail = when (eventStatus) {
            SportsEventStatus.LIVE -> linescore?.let { score ->
                val half = score.str("inningHalf") ?: score.str("inningState")
                val inning = score.str("currentInningOrdinal")
                listOfNotNull(half, inning).joinToString(" ").ifBlank { null }
            } ?: detailed
            SportsEventStatus.FINISHED -> when {
                detailed == null || detailed.equals("Final", ignoreCase = true) || detailed.equals("Game Over", ignoreCase = true) -> {
                    val innings = linescore?.optInt("currentInning") ?: 0
                    if (innings > 9) "Final/$innings" else "Final"
                }
                else -> detailed
            }
            SportsEventStatus.UPCOMING -> when {
                status?.optBoolean("startTimeTBD") == true -> BananaBallSource.TIME_TBA
                detailed != null && !detailed.equals("Scheduled", ignoreCase = true) &&
                    !detailed.equals("Pre-Game", ignoreCase = true) && !detailed.equals("Warmup", ignoreCase = true) -> detailed
                else -> null
            }
        }
        val homeName = home.str("name").orEmpty()
        val awayName = away.str("name").orEmpty()
        val tv = broadcastsOf(game).take(2)
        return SportsEvent(
            id = "espn:$SPORT_PATH:${stats.leaguePath}:$pk",
            name = "$awayName at $homeName",
            sportName = SPORT_LABEL,
            leagueId = "$SPORT_PATH:${stats.leaguePath}",
            leagueName = stats.name,
            homeTeamId = TEAM_ID_PREFIX + home.optInt("id"),
            homeTeamName = homeName,
            homeTeamBadgeUrl = logoOf(home.optInt("id")),
            awayTeamId = TEAM_ID_PREFIX + away.optInt("id"),
            awayTeamName = awayName,
            awayTeamBadgeUrl = logoOf(away.optInt("id")),
            homeScore = homeSide.takeIf { it.has("score") }?.optInt("score")?.takeIf { eventStatus != SportsEventStatus.UPCOMING },
            awayScore = awaySide.takeIf { it.has("score") }?.optInt("score")?.takeIf { eventStatus != SportsEventStatus.UPCOMING },
            startTimeMs = parseInstant(game.str("gameDate")),
            venue = listOfNotNull(game.optJSONObject("venue")?.str("name"), tv.joinToString(", ").ifBlank { null }).joinToString(" • ").ifBlank { null },
            status = eventStatus,
            statusDetail = detail,
            homeTeamAbbreviation = home.str("abbreviation"),
            awayTeamAbbreviation = away.str("abbreviation"),
            homeTeamAliases = aliasesOf(home),
            awayTeamAliases = aliasesOf(away)
        )
    }

    /** The schedule's team, with the rest of what the league's teams say about it. */
    private fun fullTeam(team: JSONObject?, teams: List<JSONObject>): JSONObject? {
        team ?: return null
        val id = team.optInt("id").takeIf { it > 0 } ?: return null
        return if (team.has("teamName")) team else teams.firstOrNull { it.optInt("id") == id } ?: team
    }

    private fun teamOf(raw: JSONObject, stats: StatsLeague): SportsTeam {
        val id = raw.optInt("id")
        return SportsTeam(
            id = TEAM_ID_PREFIX + id,
            name = raw.str("name").orEmpty(),
            shortName = raw.str("shortName") ?: raw.str("teamName"),
            sportName = SPORT_LABEL,
            leagueId = "$SPORT_PATH:${stats.leaguePath}",
            leagueName = stats.name,
            badgeUrl = logoOf(id),
            color = null
        )
    }

    /** "Tigres del Licey": "Tigres", "Licey", "Licey Tigres". */
    private fun aliasesOf(team: JSONObject): List<String> {
        val nickname = team.str("teamName")
        val short = team.str("shortName")
        val franchise = team.str("franchiseName")
        val club = team.str("clubName")
        val location = team.str("locationName")
        return listOfNotNull(
            team.str("name"),
            nickname,
            short,
            franchise,
            club,
            if (franchise != null && nickname != null && franchise != nickname) "$franchise $nickname" else null,
            if (location != null && nickname != null && !nickname.startsWith(location)) "$location $nickname" else null
        ).filter { it.length >= 3 }.distinct()
    }

    /** The game's TV ("Tele Antillas", "MLB.TV"). */
    private fun broadcastsOf(game: JSONObject): List<String> {
        val array = game.optJSONArray("broadcasts") ?: return emptyList()
        val names = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            .filter { it.str("type").equals("TV", ignoreCase = true) || it.str("type") == null }
            .mapNotNull { it.str("name") }
            .distinct()
        // MLB.TV last: the channels first.
        return names.sortedBy { it.equals("MLB.TV", ignoreCase = true) }
    }

    private fun emptyResult(feed: EspnSportsClient.Feed, stats: StatsLeague) =
        EspnSportsClient.FeedResult(feed = feed, league = league(stats), events = emptyList(), teams = emptyList())

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw EspnSportsClient.EspnException("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun JSONObject.str(key: String): String? = optString(key).trim().takeIf { it.isNotBlank() && it != "null" }

    companion object {
        const val SPORT_PATH = "baseball"
        const val SPORT_LABEL = "Baseball"
        /** MLB's teams and players by their own ids, kept apart from ESPN's. */
        const val TEAM_ID_PREFIX = "mlb-"

        private const val API = "https://statsapi.mlb.com/api/v1"
        private const val WINTER_SPORT = 17
        private const val TEAMS_CACHE_MS = 6L * 60 * 60 * 1000
        private const val LONG_RANGE_DAYS = 21L
        private const val SEASON_DAYS = 10L
        private const val BASEBALL_ICON = "https://a.espncdn.com/combiner/i?img=/redesign/assets/img/icons/ESPN-icon-baseball.png"

        val LEAGUES = listOf(
            StatsLeague("lidom", "LIDOM", WINTER_SPORT, 131, favicon("lidom.com"), "Dominican Republic"),
            StatsLeague("caribbean-series", "Serie del Caribe", WINTER_SPORT, 162, BASEBALL_ICON, null),
            StatsLeague("milb-aaa", "Triple-A", 11, null, favicon("milb.com"), "USA"),
            StatsLeague("milb-aa", "Double-A", 12, null, favicon("milb.com"), "USA"),
            StatsLeague("milb-high-a", "High-A", 13, null, favicon("milb.com"), "USA"),
            StatsLeague("milb-a", "Single-A", 14, null, favicon("milb.com"), "USA")
        )

        val LEAGUE_PATHS: Set<String> = LEAGUES.map { it.leaguePath }.toSet()

        fun leagueOf(leaguePath: String): StatsLeague? = LEAGUES.firstOrNull { it.leaguePath == leaguePath }

        /** MLB's logo of a team (every level's, the winter leagues' too). */
        fun logoOf(teamId: Int): String = "https://www.mlbstatic.com/team-logos/$teamId.svg"

        /** MLB's photo of a player, its silhouette when it has none. */
        private fun headshotOf(personId: Int): String =
            "https://img.mlbstatic.com/mlb-photos/image/upload/d_people:generic:headshot:67:current.png/w_213,q_auto:best/v1/people/$personId/headshot/67/current"

        private fun favicon(domain: String): String = "https://www.google.com/s2/favicons?domain=$domain&sz=256"

        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)

        private fun rangeOf(datesRange: String?): Pair<LocalDate, LocalDate>? {
            if (datesRange.isNullOrBlank()) return null
            val parts = datesRange.split('-')
            val from = runCatching { LocalDate.parse(parts.first(), DAY) }.getOrNull() ?: return null
            val to = runCatching { LocalDate.parse(parts.last(), DAY) }.getOrNull() ?: return null
            return from to to
        }

        private fun parseDay(text: String): LocalDate? = runCatching { LocalDate.parse(text.take(10)) }.getOrNull()

        private fun parseInstant(text: String?): Long? {
            if (text.isNullOrBlank()) return null
            return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
        }

        private fun ordinal(number: Int): String = when {
            number % 100 in 11..13 -> "${number}th"
            number % 10 == 1 -> "${number}st"
            number % 10 == 2 -> "${number}nd"
            number % 10 == 3 -> "${number}rd"
            else -> "${number}th"
        }

        private fun inningLabel(number: Int): String = number.toString()

        private fun later(a: LocalDate, b: LocalDate): LocalDate = if (a.isAfter(b)) a else b
        private fun earlier(a: LocalDate, b: LocalDate): LocalDate = if (a.isBefore(b)) a else b
    }
}
