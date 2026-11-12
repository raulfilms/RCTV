package com.nuvio.tv.data.remote.espn

import com.nuvio.tv.domain.model.GameCenter
import com.nuvio.tv.domain.model.GameCenterBoxRow
import com.nuvio.tv.domain.model.GameCenterBoxScore
import com.nuvio.tv.domain.model.GameCenterBoxSection
import com.nuvio.tv.domain.model.GameCenterLeader
import com.nuvio.tv.domain.model.GameCenterPlay
import com.nuvio.tv.domain.model.GameCenterTeam
import com.nuvio.tv.domain.model.GameCenterTeamStat
import com.nuvio.tv.domain.model.SportsEventStatus
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * RCTV (game center): reads ESPN's game summary (`.../summary?event={id}`) into a [GameCenter]:
 * the header (teams, score, period, clock, score by period), the teams' stats (`boxscore.teams`),
 * the players' tables (`boxscore.players`, for the leaders and each team's box score) and the key
 * plays (`scoringPlays`, soccer's `keyEvents`, or the scoring plays among `plays`). Whatever a sport
 * doesn't give is left empty; nothing here throws on missing parts.
 */
internal object EspnGameCenterParser {

    fun parse(json: JSONObject, eventId: String, sportPath: String, leaguePath: String): GameCenter? {
        val competition = json.optJSONObject("header")?.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val competitors = competition.optJSONArray("competitors") ?: return null
        var homeJson: JSONObject? = null
        var awayJson: JSONObject? = null
        for (i in 0 until competitors.length()) {
            val competitor = competitors.optJSONObject(i) ?: continue
            when (competitor.optString("homeAway")) {
                "home" -> homeJson = competitor
                "away" -> awayJson = competitor
            }
        }
        if (homeJson == null || awayJson == null) return null
        val home = teamOf(homeJson)
        val away = teamOf(awayJson)
        val soccer = sportPath == "soccer"
        val (left, right) = if (soccer) home to away else away to home
        val leftIsHome = soccer

        val statusJson = competition.optJSONObject("status") ?: json.optJSONObject("header")?.optJSONObject("status")
        val type = statusJson?.optJSONObject("type")
        val state = type?.optString("state")?.lowercase(Locale.US).orEmpty()
        val status = when (state) {
            "in" -> SportsEventStatus.LIVE
            "post" -> SportsEventStatus.FINISHED
            else -> SportsEventStatus.UPCOMING
        }
        val detail = type?.str("shortDetail") ?: type?.str("detail")
        val period = statusJson?.optInt("period", 0)?.takeIf { it > 0 }
        val clock = statusJson?.str("displayClock")
        val inPlay = status == SportsEventStatus.LIVE && type?.optString("name") == "STATUS_IN_PROGRESS"
        val periodPrefix = periodPrefixOf(sportPath, leaguePath)
        val periodLabel = if (inPlay && period != null && periodPrefix != null) "$periodPrefix$period" else null

        val played = maxOf(left.periodScores.size, right.periodScores.size)
        val periodLabels = periodLabelsOf(sportPath, leaguePath, played)

        val teamStats = teamStatsOf(json.optJSONObject("boxscore")?.optJSONArray("teams"), sportPath, home.id, away.id, leftIsHome)
        val tables = playerTablesOf(json.optJSONObject("boxscore")?.optJSONArray("players"), sportPath, leaguePath)
        val leftTables = tables[left.id].orEmpty()
        val rightTables = tables[right.id].orEmpty()
        val boxScores = listOfNotNull(
            leftTables.takeIf { it.isNotEmpty() }?.let { GameCenterBoxScore(left, it.map(TeamTable::section)) },
            rightTables.takeIf { it.isNotEmpty() }?.let { GameCenterBoxScore(right, it.map(TeamTable::section)) }
        )
        val leaders = leadersOf(sportPath, left, leftTables, right, rightTables)
        val keyPlays = keyPlaysOf(json, sportPath, leaguePath, home, away, leftIsHome)

        return GameCenter(
            eventId = eventId,
            sportPath = sportPath,
            leaguePath = leaguePath,
            status = status,
            statusDetail = detail,
            periodLabel = periodLabel,
            clock = clock?.takeIf { periodLabel != null },
            left = left,
            right = right,
            periodLabels = periodLabels,
            teamStats = teamStats,
            leaders = leaders,
            boxScores = boxScores,
            keyPlays = keyPlays
        )
    }

    // ------------------------------------------------------------------ Teams and periods

    private fun teamOf(competitor: JSONObject): GameCenterTeam {
        val team = competitor.optJSONObject("team")
        val name = team?.str("shortDisplayName") ?: team?.str("name") ?: team?.str("displayName") ?: "?"
        val abbreviation = team?.str("abbreviation") ?: name.take(3).uppercase(Locale.US)
        val logo = team?.str("logo") ?: team?.optJSONArray("logos")?.optJSONObject(0)?.str("href")
        val records = competitor.optJSONArray("record") ?: competitor.optJSONArray("records")
        var record: String? = null
        if (records != null) {
            for (i in 0 until records.length()) {
                val entry = records.optJSONObject(i) ?: continue
                val summary = entry.str("summary") ?: entry.str("displayValue") ?: continue
                if (record == null || entry.optString("type") == "total") record = summary
                if (entry.optString("type") == "total") break
            }
        }
        val lines = competitor.optJSONArray("linescores")
        val periodScores = if (lines == null) emptyList() else (0 until lines.length()).mapNotNull { i ->
            val line = lines.optJSONObject(i) ?: return@mapNotNull null
            line.str("displayValue") ?: line.optDouble("value").takeIf { !it.isNaN() }?.let(::number)
        }
        return GameCenterTeam(
            id = team?.str("id"),
            name = name,
            abbreviation = abbreviation,
            logoUrl = logo,
            color = team?.str("color")?.removePrefix("#")?.takeIf { it.length == 6 },
            record = record,
            score = competitor.str("score"),
            periodScores = periodScores
        )
    }

    /** "Q" (football, the NBA), "H" (college basketball's halves), "P" (hockey); none for the rest. */
    internal fun periodPrefixOf(sportPath: String, leaguePath: String): String? = when (sportPath) {
        "football" -> "Q"
        "basketball" -> if (leaguePath.contains("college")) "H" else "Q"
        "hockey" -> "P"
        else -> null
    }

    /** The period columns: the sport's regular ones, plus any extra already [played] (overtime, extra innings). */
    internal fun periodLabelsOf(sportPath: String, leaguePath: String, played: Int): List<String> {
        val regular = when (sportPath) {
            "football" -> 4
            "basketball" -> if (leaguePath.contains("college") && !leaguePath.contains("women")) 2 else 4
            "hockey" -> 3
            "baseball" -> 9
            "soccer" -> 2
            else -> played
        }
        val count = maxOf(regular, played)
        return (1..count).map { n ->
            when {
                sportPath == "baseball" -> n.toString()
                n <= regular -> ordinal(n)
                n == regular + 1 -> "OT"
                else -> "${n - regular}OT"
            }
        }
    }

    private fun ordinal(n: Int): String = when {
        n % 100 in 11..13 -> "${n}TH"
        n % 10 == 1 -> "${n}ST"
        n % 10 == 2 -> "${n}ND"
        n % 10 == 3 -> "${n}RD"
        else -> "${n}TH"
    }

    // ------------------------------------------------------------------ Team stats

    private data class StatPick(val names: List<String>, val label: String, val percentFromMadeAttempts: Boolean = false)

    private val FOOTBALL_STATS = listOf(
        StatPick(listOf("possessionTime"), "Time of possession"),
        StatPick(listOf("turnovers"), "Turnovers lost"),
        StatPick(listOf("thirdDownEff"), "Third down percentage", percentFromMadeAttempts = true),
        StatPick(listOf("netPassingYards"), "Passing yards"),
        StatPick(listOf("rushingYards"), "Rushing yards"),
        StatPick(listOf("totalYards"), "Total yards")
    )
    private val BASKETBALL_STATS = listOf(
        StatPick(listOf("fieldGoalPct"), "Field goal %"),
        StatPick(listOf("threePointFieldGoalPct"), "Three point %"),
        StatPick(listOf("freeThrowPct"), "Free throw %"),
        StatPick(listOf("totalRebounds"), "Rebounds"),
        StatPick(listOf("assists"), "Assists"),
        StatPick(listOf("turnovers", "totalTurnovers"), "Turnovers")
    )
    private val HOCKEY_STATS = listOf(
        StatPick(listOf("shotsTotal", "shots"), "Shots"),
        StatPick(listOf("powerPlayGoals"), "Power play goals"),
        StatPick(listOf("faceoffPercent", "faceOffWinPercentage"), "Faceoff %"),
        StatPick(listOf("hits"), "Hits"),
        StatPick(listOf("blockedShots"), "Blocked shots"),
        StatPick(listOf("penaltyMinutes"), "Penalty minutes")
    )
    private val SOCCER_STATS = listOf(
        StatPick(listOf("possessionPct"), "Possession %"),
        StatPick(listOf("totalShots"), "Shots"),
        StatPick(listOf("shotsOnTarget"), "Shots on target"),
        StatPick(listOf("wonCorners"), "Corners"),
        StatPick(listOf("foulsCommitted"), "Fouls"),
        StatPick(listOf("saves"), "Saves")
    )
    private val BASEBALL_STATS = listOf(
        StatPick(listOf("hits"), "Hits"),
        StatPick(listOf("homeRuns"), "Home runs"),
        StatPick(listOf("RBIs", "RBI", "runsBattedIn"), "RBI"),
        StatPick(listOf("walks", "baseOnBalls"), "Walks"),
        StatPick(listOf("strikeouts"), "Strikeouts"),
        StatPick(listOf("errors"), "Errors")
    )
    private const val STATS_SHOWN = 6

    private fun teamStatsOf(
        teams: JSONArray?,
        sportPath: String,
        homeId: String?,
        awayId: String?,
        leftIsHome: Boolean
    ): List<GameCenterTeamStat> {
        if (teams == null || teams.length() < 2) return emptyList()
        var homeStats: Map<String, Pair<String, String>> = emptyMap()
        var awayStats: Map<String, Pair<String, String>> = emptyMap()
        for (i in 0 until teams.length()) {
            val entry = teams.optJSONObject(i) ?: continue
            val id = entry.optJSONObject("team")?.str("id")
            val side = entry.optString("homeAway").ifBlank {
                when (id) {
                    homeId -> "home"
                    awayId -> "away"
                    else -> ""
                }
            }
            when (side) {
                "home" -> homeStats = statsOf(entry.optJSONArray("statistics"))
                "away" -> awayStats = statsOf(entry.optJSONArray("statistics"))
            }
        }
        val leftStats = if (leftIsHome) homeStats else awayStats
        val rightStats = if (leftIsHome) awayStats else homeStats
        if (leftStats.isEmpty() && rightStats.isEmpty()) return emptyList()

        val picks = when (sportPath) {
            "football" -> FOOTBALL_STATS
            "basketball" -> BASKETBALL_STATS
            "hockey" -> HOCKEY_STATS
            "soccer" -> SOCCER_STATS
            "baseball" -> BASEBALL_STATS
            else -> emptyList()
        }
        val chosen = picks.mapNotNull { pick ->
            val key = pick.names.firstOrNull { leftStats.containsKey(it) || rightStats.containsKey(it) } ?: return@mapNotNull null
            val leftValue = leftStats[key]?.second ?: "-"
            val rightValue = rightStats[key]?.second ?: "-"
            if (pick.percentFromMadeAttempts) {
                val l = percentOfMadeAttempts(leftValue)
                val r = percentOfMadeAttempts(rightValue)
                stat(pick.label, l ?: leftValue, r ?: rightValue)
            } else {
                stat(pick.label, leftValue, rightValue)
            }
        }
        if (chosen.size >= 3) return chosen.take(STATS_SHOWN)
        // A sport (or names) this doesn't know: ESPN's own first ones, with its labels.
        val keys = (leftStats.keys + rightStats.keys).distinct()
        return keys.take(STATS_SHOWN).map { key ->
            val label = leftStats[key]?.first ?: rightStats[key]?.first ?: key
            stat(label, leftStats[key]?.second ?: "-", rightStats[key]?.second ?: "-")
        }
    }

    /** A team's stats by name: (label, value). A group of stats (baseball's batting) is opened up. */
    private fun statsOf(array: JSONArray?): Map<String, Pair<String, String>> {
        val stats = LinkedHashMap<String, Pair<String, String>>()
        if (array == null) return stats
        fun add(entry: JSONObject) {
            val name = entry.str("name") ?: return
            val value = entry.str("displayValue") ?: return
            stats.putIfAbsent(name, (entry.str("label") ?: entry.str("displayName") ?: name) to value)
        }
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val nested = entry.optJSONArray("stats")
            if (nested != null) {
                for (j in 0 until nested.length()) nested.optJSONObject(j)?.let(::add)
            } else {
                add(entry)
            }
        }
        return stats
    }

    private fun stat(label: String, left: String, right: String): GameCenterTeamStat {
        val l = magnitude(left)
        val r = magnitude(right)
        val share = if (l != null && r != null && l + r > 0.0) (l / (l + r)).toFloat() else 0.5f
        return GameCenterTeamStat(label, left, right, share.coerceIn(0f, 1f))
    }

    /** "3-8" → "37.5" (third downs made out of tried). */
    internal fun percentOfMadeAttempts(value: String): String? {
        val parts = value.split('-', '/')
        if (parts.size != 2) return null
        val made = parts[0].trim().toDoubleOrNull() ?: return null
        val tried = parts[1].trim().toDoubleOrNull() ?: return null
        if (tried <= 0.0) return "0"
        val percent = made * 100.0 / tried
        return number(Math.round(percent * 10.0) / 10.0)
    }

    /** How much a stat's text is, for the bar: "18:37" (minutes), "3-8" (made), "45%", "143". */
    internal fun magnitude(value: String): Double? {
        val text = value.trim().removeSuffix("%")
        if (text.isEmpty() || text == "-") return null
        if (':' in text) {
            val parts = text.split(':')
            val minutes = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
            val seconds = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            return minutes * 60.0 + seconds
        }
        if ('-' in text.drop(1)) return text.split('-').firstOrNull()?.trim()?.toDoubleOrNull()
        return text.toDoubleOrNull()
    }

    // ------------------------------------------------------------------ Players' tables

    /** A column shown: its header, ESPN's label it comes from, and which part of "15/22" it is. */
    private data class Column(val header: String, val source: String, val part: Int? = null)

    private data class SectionSpec(
        val groups: List<String>,
        val title: String,
        val columns: List<Column>,
        val rankBy: String?,
        val rows: Int,
        /** The leader card's category ("Passing"); null: no leader from this section. */
        val leaderCategory: String?
    )

    private data class TeamTable(val section: GameCenterBoxSection, val spec: SectionSpec, val ranks: List<Double>, val athletes: List<JSONObject>)

    private val FOOTBALL_SECTIONS = listOf(
        SectionSpec(listOf("passing"), "Passing", listOf(Column("Cmp", "C/ATT", 0), Column("Yds", "YDS"), Column("TD", "TD"), Column("Int", "INT")), "YDS", 2, "Passing"),
        SectionSpec(listOf("rushing"), "Rushing", listOf(Column("Car", "CAR"), Column("Yds", "YDS"), Column("TD", "TD"), Column("Avg", "AVG")), "YDS", 3, "Rushing"),
        SectionSpec(listOf("receiving"), "Receiving", listOf(Column("Rec", "REC"), Column("Yds", "YDS"), Column("TD", "TD"), Column("Avg", "AVG")), "YDS", 3, "Receiving")
    )
    private val BASKETBALL_SECTIONS = listOf(
        SectionSpec(listOf("*"), "Players", listOf(Column("Pts", "PTS"), Column("Reb", "REB"), Column("Ast", "AST"), Column("Min", "MIN")), "PTS", 5, "Points")
    )
    private val BASEBALL_SECTIONS = listOf(
        SectionSpec(listOf("batting"), "Batting", listOf(Column("H-AB", "H-AB"), Column("R", "R"), Column("HR", "HR"), Column("RBI", "RBI")), "H", 4, "Batting"),
        SectionSpec(listOf("pitching"), "Pitching", listOf(Column("IP", "IP"), Column("H", "H"), Column("ER", "ER"), Column("K", "K")), "K", 2, "Pitching")
    )
    private val HOCKEY_SECTIONS = listOf(
        SectionSpec(listOf("forwards", "defenses", "defense", "skaters"), "Skaters", listOf(Column("G", "G"), Column("A", "A"), Column("SOG", "SOG"), Column("+/-", "+/-")), "G", 4, "Skaters"),
        SectionSpec(listOf("goalies", "goalkeeping"), "Goalies", listOf(Column("SA", "SA"), Column("SV", "SV"), Column("SV%", "SV%"), Column("TOI", "TOI")), null, 1, null)
    )

    /** Each team's tables (by its ESPN id), in the sport's order. */
    private fun playerTablesOf(players: JSONArray?, sportPath: String, leaguePath: String): Map<String?, List<TeamTable>> {
        if (players == null) return emptyMap()
        val specs = when (sportPath) {
            "football" -> FOOTBALL_SECTIONS
            "basketball" -> BASKETBALL_SECTIONS
            "baseball" -> BASEBALL_SECTIONS
            "hockey" -> HOCKEY_SECTIONS
            else -> null
        }
        val result = LinkedHashMap<String?, List<TeamTable>>()
        for (i in 0 until players.length()) {
            val teamEntry = players.optJSONObject(i) ?: continue
            val teamId = teamEntry.optJSONObject("team")?.str("id")
            val groups = teamEntry.optJSONArray("statistics") ?: continue
            val tables = ArrayList<TeamTable>()
            if (specs != null) {
                for (spec in specs) {
                    // Hockey's skaters come in two groups (forwards, defense): read as one.
                    val matching = (0 until groups.length()).mapNotNull { groups.optJSONObject(it) }.filter { group ->
                        spec.groups.contains("*") || group.optString("name").lowercase(Locale.US) in spec.groups
                    }.let { if (spec.groups.contains("*")) it.take(1) else it }
                    tableOf(matching, spec)?.let(tables::add)
                }
            } else {
                // A sport this doesn't know: its first groups, their first columns, as ESPN orders them.
                for (j in 0 until minOf(groups.length(), 3)) {
                    val group = groups.optJSONObject(j) ?: continue
                    val labels = stringsOf(group.optJSONArray("labels"))
                    if (labels.isEmpty()) continue
                    val spec = SectionSpec(
                        groups = listOf(group.optString("name")),
                        title = group.str("text") ?: group.str("name")?.replaceFirstChar { it.titlecase(Locale.US) } ?: "Players",
                        columns = labels.take(4).map { Column(it, it) },
                        rankBy = null,
                        rows = 4,
                        leaderCategory = null
                    )
                    tableOf(listOf(group), spec)?.let(tables::add)
                }
            }
            result[teamId] = tables
        }
        return result
    }

    private fun tableOf(groups: List<JSONObject>, spec: SectionSpec): TeamTable? {
        if (groups.isEmpty()) return null
        data class Line(val athlete: JSONObject, val values: List<String>, val rank: Double)
        val lines = ArrayList<Line>()
        for (group in groups) {
            val labels = stringsOf(group.optJSONArray("labels")).map { it.uppercase(Locale.US) }
            if (labels.isEmpty()) continue
            val indexes = spec.columns.map { labels.indexOf(it.source.uppercase(Locale.US)) }
            if (indexes.all { it < 0 }) continue
            val rankIndex = spec.rankBy?.let { labels.indexOf(it.uppercase(Locale.US)) } ?: -1
            val athletes = group.optJSONArray("athletes") ?: continue
            for (a in 0 until athletes.length()) {
                val entry = athletes.optJSONObject(a) ?: continue
                val athlete = entry.optJSONObject("athlete") ?: continue
                val stats = stringsOf(entry.optJSONArray("stats"))
                if (stats.isEmpty()) continue // didn't play
                val values = spec.columns.mapIndexed { c, column ->
                    val raw = indexes[c].takeIf { it >= 0 }?.let { stats.getOrNull(it) } ?: "-"
                    if (column.part != null) raw.split('/', '-').getOrNull(column.part)?.trim() ?: raw else raw
                }
                val rank = if (rankIndex >= 0) {
                    stats.getOrNull(rankIndex)?.let { magnitude(it) } ?: Double.NEGATIVE_INFINITY
                } else {
                    -lines.size.toDouble() // ESPN's own order
                }
                lines += Line(athlete, values, rank)
            }
        }
        if (lines.isEmpty()) return null
        val sorted = if (spec.rankBy != null) lines.sortedByDescending { it.rank } else lines
        val shown = sorted.take(spec.rows)
        val shownColumns = spec.columns.indices.filter { c -> shown.any { it.values[c] != "-" } }
        val section = GameCenterBoxSection(
            title = spec.title,
            columns = shownColumns.map { spec.columns[it].header },
            rows = shown.map { line ->
                GameCenterBoxRow(
                    jersey = line.athlete.str("jersey"),
                    name = line.athlete.str("shortName") ?: line.athlete.str("displayName") ?: "?",
                    values = shownColumns.map { line.values[it] }
                )
            }
        )
        return TeamTable(section, spec, shown.map { it.rank }, shown.map { it.athlete })
    }

    /**
     * The game's leaders: for each section with a category (Passing, Rushing, Receiving; Points),
     * the best player of the two teams. Basketball adds the best rebounder and passer.
     */
    private fun leadersOf(
        sportPath: String,
        left: GameCenterTeam,
        leftTables: List<TeamTable>,
        right: GameCenterTeam,
        rightTables: List<TeamTable>
    ): List<GameCenterLeader> {
        val leaders = ArrayList<GameCenterLeader>()
        val sides = listOf(left to leftTables, right to rightTables)
        val categories = (leftTables + rightTables).mapNotNull { it.spec.leaderCategory }.distinct()
        for (category in categories) {
            var best: Triple<GameCenterTeam, TeamTable, Int>? = null
            var bestRank = Double.NEGATIVE_INFINITY
            for ((team, tables) in sides) {
                val table = tables.firstOrNull { it.spec.leaderCategory == category } ?: continue
                val rank = table.ranks.firstOrNull() ?: continue
                if (best == null || rank > bestRank) {
                    best = Triple(team, table, 0)
                    bestRank = rank
                }
            }
            val (team, table, row) = best ?: continue
            leaders += leaderOf(team, table, row, category)
        }
        if (sportPath == "basketball") {
            for ((label, title) in listOf("REB" to "Rebounds", "AST" to "Assists")) {
                var bestTeam: GameCenterTeam? = null
                var bestTable: TeamTable? = null
                var bestRow = -1
                var bestValue = Double.NEGATIVE_INFINITY
                for ((team, tables) in sides) {
                    val table = tables.firstOrNull() ?: continue
                    val column = table.section.columns.indexOf(label.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) })
                    if (column < 0) continue
                    table.section.rows.forEachIndexed { r, boxRow ->
                        val value = boxRow.values.getOrNull(column)?.let { magnitude(it) } ?: return@forEachIndexed
                        if (value > bestValue) {
                            bestValue = value
                            bestTeam = team
                            bestTable = table
                            bestRow = r
                        }
                    }
                }
                val team = bestTeam ?: continue
                val table = bestTable ?: continue
                leaders += leaderOf(team, table, bestRow, title)
            }
        }
        return leaders.distinctBy { it.name to it.category }
    }

    private fun leaderOf(team: GameCenterTeam, table: TeamTable, row: Int, category: String): GameCenterLeader {
        val athlete = table.athletes[row]
        val boxRow = table.section.rows[row]
        return GameCenterLeader(
            name = athlete.str("displayName") ?: boxRow.name,
            headshotUrl = athlete.optJSONObject("headshot")?.str("href"),
            teamName = team.name,
            teamColor = team.color,
            category = category,
            columns = table.section.columns.zip(boxRow.values)
        )
    }

    // ------------------------------------------------------------------ Key plays

    private const val KEY_PLAYS_SHOWN = 20

    private fun keyPlaysOf(
        json: JSONObject,
        sportPath: String,
        leaguePath: String,
        home: GameCenterTeam,
        away: GameCenterTeam,
        leftIsHome: Boolean
    ): List<GameCenterPlay> {
        val prefix = periodPrefixOf(sportPath, leaguePath)
        val teamsById = listOf(home, away).associateBy { it.id }
        fun scoreText(play: JSONObject): String? {
            val homeScore = play.opt("homeScore")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: return null
            val awayScore = play.opt("awayScore")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: return null
            return if (leftIsHome) {
                "${home.abbreviation} $homeScore - ${away.abbreviation} $awayScore"
            } else {
                "${away.abbreviation} $awayScore - ${home.abbreviation} $homeScore"
            }
        }
        fun timeOf(play: JSONObject): String? {
            val period = play.optJSONObject("period")
            val number = period?.optInt("number", 0)?.takeIf { it > 0 }
            val clock = play.optJSONObject("clock")?.str("displayValue")
            return when {
                sportPath == "soccer" -> clock
                prefix != null && number != null -> listOfNotNull("$prefix$number", clock).joinToString(" ")
                else -> period?.str("displayValue") ?: clock
            }
        }
        fun playOf(play: JSONObject): GameCenterPlay? {
            val text = play.str("text") ?: play.str("shortText") ?: return null
            val teamJson = play.optJSONObject("team")
            val team = teamJson?.str("id")?.let { teamsById[it] }
            return GameCenterPlay(
                title = play.optJSONObject("type")?.str("text"),
                text = text,
                time = timeOf(play),
                teamLogoUrl = team?.logoUrl ?: teamJson?.str("logo"),
                teamAbbreviation = team?.abbreviation ?: teamJson?.str("abbreviation"),
                score = scoreText(play)
            )
        }

        json.optJSONArray("scoringPlays")?.takeIf { it.length() > 0 }?.let { array ->
            return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::playOf) }
                .asReversed().take(KEY_PLAYS_SHOWN)
        }
        json.optJSONArray("keyEvents")?.takeIf { it.length() > 0 }?.let { array ->
            return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                .filter { event ->
                    val kind = event.optJSONObject("type")?.optString("text").orEmpty().lowercase(Locale.US)
                    event.optBoolean("scoringPlay") || "goal" in kind || "card" in kind || "penalty" in kind
                }
                .mapNotNull(::playOf)
                .asReversed().take(KEY_PLAYS_SHOWN)
        }
        json.optJSONArray("plays")?.takeIf { it.length() > 0 }?.let { array ->
            return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                .filter { it.optBoolean("scoringPlay") }
                .mapNotNull(::playOf)
                .asReversed().take(KEY_PLAYS_SHOWN)
        }
        return emptyList()
    }

    // ------------------------------------------------------------------ Helpers

    private fun stringsOf(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map { array.optString(it).orEmpty() }

    private fun JSONObject.str(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun number(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}
