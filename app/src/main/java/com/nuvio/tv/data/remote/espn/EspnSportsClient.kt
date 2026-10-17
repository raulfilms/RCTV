package com.nuvio.tv.data.remote.espn

import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import com.nuvio.tv.domain.model.SportsGameStat
import com.nuvio.tv.domain.model.SportsLeague
import com.nuvio.tv.domain.model.SportsNewsArticle
import com.nuvio.tv.domain.model.SportsStandingEntry
import com.nuvio.tv.domain.model.SportsStandingStat
import com.nuvio.tv.domain.model.SportsStandingsGroup
import com.nuvio.tv.domain.model.SportsTeam
import com.nuvio.tv.domain.model.SportsAthlete
import com.nuvio.tv.domain.model.SportsTeamGame
import com.nuvio.tv.domain.model.SportsTeamHistory
import com.nuvio.tv.domain.model.SportsTeamProfile
import com.nuvio.tv.domain.model.SportsTeamStatCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Client for ESPN's free, public, unofficial scoreboard API
 * (`https://site.api.espn.com/apis/site/v2/sports/{sport}/{league}/scoreboard`). No API key or
 * backend of any kind is involved - the app talks to ESPN directly, at the user's request, so
 * scores stay live at all times rather than depending on a paid provider or a proxy we'd have to
 * keep running.
 *
 * Unlike TheSportsDB, ESPN's scoreboard response already embeds the league's display name/logo
 * and every competing team's name/logo right alongside the events, so a single request per feed is
 * enough to build events, teams, and league metadata together - no extra "all leagues"/"all teams"
 * calls needed.
 */
@Singleton
class EspnSportsClient @Inject constructor(
    @Named("addonPermissive") private val okHttpClient: OkHttpClient
) {
    class EspnException(message: String) : Exception(message)

    /** One curated ESPN scoreboard feed: `{sportPath}/{leaguePath}`, e.g. "soccer/eng.1". */
    data class Feed(val sportPath: String, val leaguePath: String, val sportLabel: String, val emoji: String)

    /** One filter chip on the Teams browse screen, mapping a single curated league to its feed. */
    data class TeamsFilterChip(val id: String, val label: String, val emoji: String, val feed: Feed)

    /** Result of fetching a single feed: its league metadata plus the events/teams found in the requested date window. */
    data class FeedResult(
        val feed: Feed,
        val league: SportsLeague,
        val events: List<SportsEvent>,
        val teams: List<SportsTeam>
    )

    companion object {
        /** The sports/leagues surfaced throughout the Sports tab. Curated by hand - ESPN has many more, but this covers the major ones the app cares about. */
        val FEEDS = listOf(
            Feed("soccer", "eng.1", "Soccer", "⚽"),
            Feed("soccer", "esp.1", "Soccer", "⚽"),
            Feed("soccer", "ita.1", "Soccer", "⚽"),
            Feed("soccer", "ger.1", "Soccer", "⚽"),
            Feed("soccer", "fra.1", "Soccer", "⚽"),
            Feed("soccer", "uefa.champions", "Soccer", "⚽"),
            Feed("soccer", "usa.1", "Soccer", "⚽"),
            Feed("soccer", "mex.1", "Soccer", "⚽"),
            Feed("basketball", "nba", "Basketball", "🏀"),
            Feed("football", "nfl", "Football", "🏈"),
            Feed("baseball", "mlb", "Baseball", "⚾"),
            Feed("hockey", "nhl", "Hockey", "🏒"),
            Feed("mma", "ufc", "MMA", "🥊"),
            Feed("racing", "f1", "Motorsport", "🏎️"),
            Feed("tennis", "atp", "Tennis", "🎾")
        )

        /** Feeds pulled first / most often - decides which league badges load eagerly and which sports back "Live Now". */
        val HEADLINE_LEAGUE_PATHS = setOf("eng.1", "nba", "nfl", "mlb", "nhl", "uefa.champions", "ufc")

        /** Display label per feed, used before any network call has resolved the league's real name (e.g. for filter chips). */
        private val FEED_LABELS = mapOf(
            "eng.1" to "Premier League",
            "esp.1" to "La Liga",
            "ita.1" to "Serie A",
            "ger.1" to "Bundesliga",
            "fra.1" to "Ligue 1",
            "uefa.champions" to "Champions League",
            "usa.1" to "MLS",
            "mex.1" to "Liga MX",
            "nba" to "NBA",
            "nfl" to "NFL",
            "mlb" to "MLB",
            "nhl" to "NHL",
            "ufc" to "UFC",
            "f1" to "Formula 1",
            "atp" to "Tennis"
        )

        /** One filter chip per curated feed, for the Teams browse screen. */
        val TEAMS_FILTERS: List<TeamsFilterChip> = FEEDS.map { feed ->
            TeamsFilterChip(
                id = "${feed.sportPath}:${feed.leaguePath}",
                label = FEED_LABELS[feed.leaguePath] ?: feed.leaguePath,
                emoji = feed.emoji,
                feed = feed
            )
        }

        /** One marquee team the Sports home page's "Teams" row should feature, resolved by name against [feed]'s full roster. */
        private data class HomeTeamPick(val feed: Feed, val teamNameContains: String)

        /**
         * Fixed picks for the Sports home page's "Teams" row - chosen marquee teams rather than
         * "whoever happens to be playing this week" (which is what the scoreboard-derived team list
         * would otherwise surface, and would churn week to week).
         */
        private val HOME_TEAM_ROW_PICKS = listOf(
            HomeTeamPick(FEEDS.first { it.sportPath == "soccer" && it.leaguePath == "esp.1" }, "Real Madrid"),
            HomeTeamPick(FEEDS.first { it.sportPath == "soccer" && it.leaguePath == "esp.1" }, "Barcelona"),
            HomeTeamPick(FEEDS.first { it.sportPath == "basketball" && it.leaguePath == "nba" }, "Lakers"),
            HomeTeamPick(FEEDS.first { it.sportPath == "football" && it.leaguePath == "nfl" }, "Cowboys"),
            HomeTeamPick(FEEDS.first { it.sportPath == "baseball" && it.leaguePath == "mlb" }, "Yankees")
        )

        /**
         * Resolves the curated [Feed] behind a `"sportPath:leaguePath"` id, e.g. [SportsLeague.id].
         * Tolerates a still-percent-encoded id (`"football%3Anfl"`) reaching here, since navigation
         * libraries have historically been inconsistent about auto-decoding a colon in a route's path
         * segment - a straightforward `":"` match is tried first, decoding is only a fallback.
         */
        fun feedForLeagueId(leagueId: String): Feed? {
            FEEDS.firstOrNull { "${it.sportPath}:${it.leaguePath}" == leagueId }?.let { return it }
            val decoded = runCatching { java.net.URLDecoder.decode(leagueId, "UTF-8") }.getOrNull()
            if (decoded != null && decoded != leagueId) {
                FEEDS.firstOrNull { "${it.sportPath}:${it.leaguePath}" == decoded }?.let { return it }
            }
            return null
        }

        /** A [SportsEvent.id]'s parts, e.g. `"espn:football:nfl:401671808"` -> ("football", "nfl", "401671808"). */
        data class ParsedEventId(val sportPath: String, val leaguePath: String, val rawEventId: String)

        /** Parses a [SportsEvent.id] built by [fetchFeed] back into its feed + raw ESPN event id. Tolerates a still-percent-encoded id, same as [feedForLeagueId]. */
        fun parseEventId(eventId: String): ParsedEventId? {
            fun parse(id: String): ParsedEventId? {
                if (!id.startsWith("espn:")) return null
                val parts = id.removePrefix("espn:").split(":")
                if (parts.size != 3) return null
                return ParsedEventId(sportPath = parts[0], leaguePath = parts[1], rawEventId = parts[2])
            }
            parse(eventId)?.let { return it }
            val decoded = runCatching { java.net.URLDecoder.decode(eventId, "UTF-8") }.getOrNull()
            if (decoded != null && decoded != eventId) parse(decoded)?.let { return it }
            return null
        }

        private const val BASE_URL = "https://site.api.espn.com/apis/site/v2/sports"

        /** The European competitions a club of the big five leagues can play. */
        private val SOCCER_UEFA_COMPETITIONS = listOf("uefa.champions", "uefa.europa", "uefa.europa.conf", "uefa.super_cup")
        private val SOCCER_UEFA_LEAGUES = setOf("eng.1", "esp.1", "ita.1", "ger.1", "fra.1")

        /** Each league's cups and super cup (and CONCACAF's for MLS and Liga MX). */
        private val SOCCER_DOMESTIC_CUPS = mapOf(
            "eng.1" to listOf("eng.fa", "eng.league_cup", "eng.charity"),
            "esp.1" to listOf("esp.copa_del_rey", "esp.super_cup"),
            "ita.1" to listOf("ita.coppa_italia", "ita.super_cup"),
            "ger.1" to listOf("ger.dfb_pokal", "ger.super_cup"),
            "fra.1" to listOf("fra.coupe_de_france", "fra.super_cup"),
            "usa.1" to listOf("usa.open", "concacaf.champions", "concacaf.leagues.cup"),
            "mex.1" to listOf("concacaf.champions", "concacaf.leagues.cup")
        )

        /** Standings live under a different, older ESPN API shape than scoreboard/teams/news. */
        private const val STANDINGS_BASE_URL = "https://site.api.espn.com/apis/v2/sports"

        private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        private val isoFormatWithSeconds = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw EspnException("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun parseStartTimeMs(dateText: String?): Long? {
        if (dateText.isNullOrBlank()) return null
        return runCatching { isoFormat.parse(dateText)?.time }.getOrNull()
    }

    /** News articles' `published`/`lastModified` fields include seconds, unlike scoreboard event dates. */
    private fun parseNewsDate(dateText: String?): Long? {
        if (dateText.isNullOrBlank()) return null
        return runCatching { isoFormatWithSeconds.parse(dateText)?.time }.getOrNull()
            ?: parseStartTimeMs(dateText)
    }

    /** Builds an ESPN `yyyyMMdd-yyyyMMdd` dates query spanning [startOffsetDays]..[endOffsetDays] from today (UTC), inclusive. */
    fun dateRange(startOffsetDays: Int, endOffsetDays: Int): String {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.add(Calendar.DAY_OF_YEAR, startOffsetDays)
        val start = dayFormat.format(calendar.time)
        calendar.add(Calendar.DAY_OF_YEAR, endOffsetDays - startOffsetDays)
        val end = dayFormat.format(calendar.time)
        return "$start-$end"
    }

    /**
     * A team's names as ESPN gives them ("Chicago Bulls", "Bulls", "CHI" aside), to find its games
     * in the TV guide: the full name, the short one, the nickname, the city and the nickname.
     */
    private fun teamAliasesOf(teamJson: JSONObject?): List<String> {
        if (teamJson == null) return emptyList()
        val location = teamJson.str("location")
        val nickname = teamJson.str("name")
        return listOfNotNull(
            teamJson.str("displayName"),
            teamJson.str("shortDisplayName"),
            nickname,
            if (location != null && nickname != null && !nickname.startsWith(location)) "$location $nickname" else null
        ).distinct()
    }

    private fun statusFrom(type: JSONObject?): Pair<SportsEventStatus, String?> {
        val state = type?.optString("state")?.lowercase(Locale.US).orEmpty()
        val detail = type?.optString("shortDetail")?.takeIf { it.isNotBlank() }
            ?: type?.optString("detail")?.takeIf { it.isNotBlank() }
        val status = when (state) {
            "in" -> SportsEventStatus.LIVE
            "post" -> SportsEventStatus.FINISHED
            else -> SportsEventStatus.UPCOMING
        }
        return status to detail
    }

    /**
     * Fetches one feed's scoreboard. [datesRange] is ESPN's own `yyyyMMdd` or `yyyyMMdd-yyyyMMdd`
     * query format; omitted, ESPN defaults to "today" (or "this week/round" for some sports).
     */
    fun fetchFeed(feed: Feed, datesRange: String? = null): FeedResult {
        val base = "$BASE_URL/${feed.sportPath}/${feed.leaguePath}/scoreboard"
        val url = if (datesRange != null) "$base?dates=$datesRange" else base
        val body = get(url)
        val json = JSONObject(body)

        val leagueId = "${feed.sportPath}:${feed.leaguePath}"
        val leagueJson = json.optJSONArray("leagues")?.optJSONObject(0)
        val leagueName = leagueJson?.optString("name")?.takeIf { it.isNotBlank() } ?: feed.leaguePath
        val leagueBadge = leagueJson?.optJSONArray("logos")?.optJSONObject(0)?.optString("href")?.takeIf { it.isNotBlank() }
        val league = SportsLeague(id = leagueId, name = leagueName, sportName = feed.sportLabel, badgeUrl = leagueBadge, country = null)

        val eventsArray = json.optJSONArray("events") ?: JSONArray()
        val events = ArrayList<SportsEvent>(eventsArray.length())
        val teams = LinkedHashMap<String, SportsTeam>()

        for (i in 0 until eventsArray.length()) {
            val raw = eventsArray.optJSONObject(i) ?: continue
            val competition = raw.optJSONArray("competitions")?.optJSONObject(0) ?: continue
            val competitors = competition.optJSONArray("competitors") ?: JSONArray()

            var home: JSONObject? = null
            var away: JSONObject? = null
            for (j in 0 until competitors.length()) {
                val competitor = competitors.optJSONObject(j) ?: continue
                when (competitor.optString("homeAway")) {
                    "home" -> home = competitor
                    "away" -> away = competitor
                }
            }

            fun teamJsonOf(competitor: JSONObject?) = competitor?.optJSONObject("team")
            val homeTeamJson = teamJsonOf(home)
            val awayTeamJson = teamJsonOf(away)

            fun displayName(teamJson: JSONObject?): String =
                teamJson?.optString("shortDisplayName")?.takeIf { it.isNotBlank() }
                    ?: teamJson?.optString("displayName")?.takeIf { it.isNotBlank() }
                    ?: teamJson?.optString("name").orEmpty()

            fun badgeOf(teamJson: JSONObject?): String? = teamJson?.optString("logo")?.takeIf { it.isNotBlank() }
            fun idOf(teamJson: JSONObject?): String? = teamJson?.optString("id")?.takeIf { it.isNotBlank() }
            fun scoreOf(competitor: JSONObject?): Int? = competitor?.optString("score")?.toIntOrNull()
            fun abbreviationOf(teamJson: JSONObject?): String? = teamJson?.optString("abbreviation")?.takeIf { it.isNotBlank() }
            fun colorOf(teamJson: JSONObject?): String? =
                teamJson?.optString("color")?.removePrefix("#")?.takeIf { it.length == 6 }

            val homeTeamId = idOf(homeTeamJson)
            val awayTeamId = idOf(awayTeamJson)
            val homeTeamName = displayName(homeTeamJson)
            val awayTeamName = displayName(awayTeamJson)
            val homeBadge = badgeOf(homeTeamJson)
            val awayBadge = badgeOf(awayTeamJson)

            if (homeTeamId != null && homeTeamName.isNotBlank()) {
                teams[homeTeamId] = SportsTeam(
                    id = homeTeamId, name = homeTeamName, shortName = homeTeamName,
                    sportName = feed.sportLabel, leagueId = leagueId, leagueName = leagueName, badgeUrl = homeBadge,
                    color = colorOf(homeTeamJson)
                )
            }
            if (awayTeamId != null && awayTeamName.isNotBlank()) {
                teams[awayTeamId] = SportsTeam(
                    id = awayTeamId, name = awayTeamName, shortName = awayTeamName,
                    sportName = feed.sportLabel, leagueId = leagueId, leagueName = leagueName, badgeUrl = awayBadge,
                    color = colorOf(awayTeamJson)
                )
            }

            val (status, statusDetail) = statusFrom(raw.optJSONObject("status")?.optJSONObject("type"))

            events += SportsEvent(
                id = "espn:${feed.sportPath}:${feed.leaguePath}:${raw.optString("id")}",
                name = raw.optString("shortName")?.takeIf { it.isNotBlank() }
                    ?: raw.optString("name")?.takeIf { it.isNotBlank() }
                    ?: "$homeTeamName vs $awayTeamName",
                sportName = feed.sportLabel,
                leagueId = leagueId,
                leagueName = leagueName,
                homeTeamId = homeTeamId,
                homeTeamName = homeTeamName,
                homeTeamBadgeUrl = homeBadge,
                awayTeamId = awayTeamId,
                awayTeamName = awayTeamName,
                awayTeamBadgeUrl = awayBadge,
                homeScore = scoreOf(home),
                awayScore = scoreOf(away),
                startTimeMs = parseStartTimeMs(raw.optString("date")),
                venue = competition.optJSONObject("venue")?.optString("fullName")?.takeIf { it.isNotBlank() },
                status = status,
                statusDetail = statusDetail,
                homeTeamAbbreviation = abbreviationOf(homeTeamJson),
                awayTeamAbbreviation = abbreviationOf(awayTeamJson),
                homeTeamColor = colorOf(homeTeamJson),
                awayTeamColor = colorOf(awayTeamJson),
                homeTeamAliases = teamAliasesOf(homeTeamJson),
                awayTeamAliases = teamAliasesOf(awayTeamJson)
            )
        }

        return FeedResult(feed = feed, league = league, events = events, teams = teams.values.toList())
    }

    /**
     * Fetches every curated feed. [rangeDays] widens the scoreboard window beyond "today" so
     * upcoming (not just live/today's) fixtures show up too. Each feed is fetched independently, so
     * one league being down/unreachable never blocks the rest.
     */
    fun fetchAllFixtures(rangeDays: Int = 10, feeds: List<Feed> = FEEDS): List<FeedResult> {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val start = dayFormat.format(calendar.time)
        calendar.add(Calendar.DAY_OF_YEAR, rangeDays)
        val end = dayFormat.format(calendar.time)
        val range = "$start-$end"

        return feeds.mapNotNull { feed ->
            runCatching { fetchFeed(feed, range) }
                .recoverCatching { fetchFeed(feed) }
                .getOrNull()
        }
    }

    /**
     * Fetches a league's full standings from ESPN's `apis/v2` standings endpoint (a different, older
     * API shape than scoreboard/teams/news). Response nesting varies by sport - a single flat table
     * for most soccer leagues, one group per conference for others - but is always at most two levels
     * deep: a `children` array whose entries each carry a `standings.entries` list. Parsing generically
     * against that shape (rather than special-casing every curated sport) keeps this working across
     * all of them, at the cost of not knowing in advance how many groups a given league will return.
     */
    fun fetchStandings(feed: Feed): List<SportsStandingsGroup> {
        val url = "$STANDINGS_BASE_URL/${feed.sportPath}/${feed.leaguePath}/standings"
        val json = JSONObject(get(url))
        val leagueId = "${feed.sportPath}:${feed.leaguePath}"

        fun parseGroup(groupJson: JSONObject, fallbackName: String): SportsStandingsGroup? {
            val entriesJson = groupJson.optJSONObject("standings")?.optJSONArray("entries") ?: return null
            val entries = ArrayList<SportsStandingEntry>(entriesJson.length())
            for (i in 0 until entriesJson.length()) {
                val entryJson = entriesJson.optJSONObject(i) ?: continue
                val teamJson = entryJson.optJSONObject("team") ?: continue
                val teamId = teamJson.optString("id")?.takeIf { it.isNotBlank() } ?: continue
                val teamName = teamJson.optString("shortDisplayName")?.takeIf { it.isNotBlank() }
                    ?: teamJson.optString("displayName")?.takeIf { it.isNotBlank() }
                    ?: teamJson.optString("name").orEmpty()
                val team = SportsTeam(
                    id = teamId,
                    name = teamName,
                    shortName = teamJson.optString("abbreviation")?.takeIf { it.isNotBlank() },
                    sportName = feed.sportLabel,
                    leagueId = leagueId,
                    leagueName = fallbackName,
                    badgeUrl = teamJson.optJSONArray("logos")?.optJSONObject(0)?.optString("href")?.takeIf { it.isNotBlank() }
                )

                val statsJson = entryJson.optJSONArray("stats") ?: JSONArray()
                val stats = ArrayList<SportsStandingStat>(statsJson.length())
                for (j in 0 until statsJson.length()) {
                    val statJson = statsJson.optJSONObject(j) ?: continue
                    val value = statJson.optString("displayValue")?.takeIf { it.isNotBlank() } ?: continue
                    val label = statJson.optString("shortDisplayName")?.takeIf { it.isNotBlank() }
                        ?: statJson.optString("abbreviation")?.takeIf { it.isNotBlank() }
                        ?: statJson.optString("name")?.takeIf { it.isNotBlank() }
                        ?: continue
                    stats += SportsStandingStat(label = label, value = value)
                }

                entries += SportsStandingEntry(rank = i + 1, team = team, stats = stats.take(5))
            }
            if (entries.isEmpty()) return null
            val groupName = groupJson.optString("name")?.takeIf { it.isNotBlank() }
                ?: groupJson.optString("abbreviation")?.takeIf { it.isNotBlank() }
                ?: fallbackName
            return SportsStandingsGroup(name = groupName, entries = entries)
        }

        val groups = ArrayList<SportsStandingsGroup>()
        val childrenJson = json.optJSONArray("children") ?: JSONArray()
        for (i in 0 until childrenJson.length()) {
            val childJson = childrenJson.optJSONObject(i) ?: continue
            parseGroup(childJson, feed.sportLabel)?.let { groups += it }
        }
        // A handful of leagues expose "standings" directly at the top level instead of via "children".
        if (groups.isEmpty()) {
            parseGroup(json, feed.sportLabel)?.let { groups += it }
        }
        return groups
    }

    /** Fetches recent ESPN news headlines for a league. */
    fun fetchNews(feed: Feed, limit: Int = 20): List<SportsNewsArticle> {
        val url = "$BASE_URL/${feed.sportPath}/${feed.leaguePath}/news?limit=$limit"
        val json = JSONObject(get(url))
        val articlesJson = json.optJSONArray("articles") ?: JSONArray()
        val articles = ArrayList<SportsNewsArticle>(articlesJson.length())

        for (i in 0 until articlesJson.length()) {
            val articleJson = articlesJson.optJSONObject(i) ?: continue
            val headline = articleJson.optString("headline")?.takeIf { it.isNotBlank() } ?: continue
            val imageUrl = articleJson.optJSONArray("images")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
            val link = articleJson.optJSONObject("links")?.optJSONObject("web")?.optString("href")?.takeIf { it.isNotBlank() }

            articles += SportsNewsArticle(
                id = articleJson.optString("id")?.takeIf { it.isNotBlank() } ?: "news:${feed.leaguePath}:$i",
                headline = headline,
                description = articleJson.optString("description")?.takeIf { it.isNotBlank() },
                imageUrl = imageUrl,
                publishedMs = parseNewsDate(articleJson.optString("published")),
                link = link
            )
        }
        return articles
    }

    /**
     * Fetches a league's full team roster (unlike [fetchFeed]'s [FeedResult.teams], not limited to
     * whichever teams happen to be playing within the requested date window).
     */
    fun fetchTeams(feed: Feed): List<SportsTeam> {
        val url = "$BASE_URL/${feed.sportPath}/${feed.leaguePath}/teams?limit=200"
        val json = JSONObject(get(url))
        val leagueId = "${feed.sportPath}:${feed.leaguePath}"

        val teamsJson = json.optJSONArray("sports")
            ?.optJSONObject(0)
            ?.optJSONArray("leagues")
            ?.optJSONObject(0)
            ?.optJSONArray("teams") ?: JSONArray()

        val teams = ArrayList<SportsTeam>(teamsJson.length())
        for (i in 0 until teamsJson.length()) {
            val teamJson = teamsJson.optJSONObject(i)?.optJSONObject("team") ?: continue
            val id = teamJson.optString("id")?.takeIf { it.isNotBlank() } ?: continue
            val name = teamJson.optString("displayName")?.takeIf { it.isNotBlank() }
                ?: teamJson.optString("name").orEmpty()
            val badge = teamJson.optJSONArray("logos")?.optJSONObject(0)?.optString("href")?.takeIf { it.isNotBlank() }

            teams += SportsTeam(
                id = id,
                name = name,
                shortName = teamJson.optString("abbreviation")?.takeIf { it.isNotBlank() },
                sportName = feed.sportLabel,
                leagueId = leagueId,
                leagueName = feed.sportLabel,
                badgeUrl = badge,
                color = teamJson.optString("color")?.removePrefix("#")?.takeIf { it.length == 6 }
            )
        }
        return teams
    }

    /**
     * Resolves the Sports home page's fixed "Teams" row picks (see [HOME_TEAM_ROW_PICKS]) against
     * each pick's league roster, in the same order the picks are declared. A pick whose team can't be
     * found (a league roster fetch failed, or ESPN renamed the team) is simply skipped rather than
     * breaking the whole row.
     */
    fun fetchHomeTeamRowPicks(): List<SportsTeam> {
        val rosterCache = mutableMapOf<String, List<SportsTeam>>()
        return HOME_TEAM_ROW_PICKS.mapNotNull { pick ->
            val cacheKey = "${pick.feed.sportPath}:${pick.feed.leaguePath}"
            val roster = rosterCache.getOrPut(cacheKey) {
                runCatching { fetchTeams(pick.feed) }.getOrDefault(emptyList())
            }
            roster.firstOrNull { it.name.contains(pick.teamNameContains, ignoreCase = true) }
        }
    }

    /**
     * Fetches a single game's team-vs-team stat comparison from ESPN's game summary endpoint
     * (`.../summary?event={id}`). Individual (non team-based) sports like tennis/MMA/racing have no
     * `boxscore.teams` in their summary at all, so this simply returns an empty list for those rather
     * than failing - the game detail screen treats an empty list as "stats aren't available".
     */
    fun fetchGameStats(sportPath: String, leaguePath: String, eventId: String): List<SportsGameStat> =
        fetchGameSummary(sportPath, leaguePath, eventId).stats

    /** A game's page data: the team-vs-team stats, and the teams' names (to find its highlights). */
    data class GameSummary(
        val stats: List<SportsGameStat>,
        val homeTeamAliases: List<String>,
        val awayTeamAliases: List<String>
    )

    fun fetchGameSummary(sportPath: String, leaguePath: String, eventId: String): GameSummary {
        val url = "$BASE_URL/$sportPath/$leaguePath/summary?event=$eventId"
        val json = JSONObject(get(url))
        var homeAliases = emptyList<String>()
        var awayAliases = emptyList<String>()
        val competitors = json.optJSONObject("header")?.optJSONArray("competitions")?.optJSONObject(0)?.optJSONArray("competitors")
        if (competitors != null) {
            for (i in 0 until competitors.length()) {
                val competitor = competitors.optJSONObject(i) ?: continue
                when (competitor.optString("homeAway")) {
                    "home" -> homeAliases = teamAliasesOf(competitor.optJSONObject("team"))
                    "away" -> awayAliases = teamAliasesOf(competitor.optJSONObject("team"))
                }
            }
        }
        return GameSummary(stats = gameStatsOf(json), homeTeamAliases = homeAliases, awayTeamAliases = awayAliases)
    }

    private fun gameStatsOf(json: JSONObject): List<SportsGameStat> {
        val teamsJson = json.optJSONObject("boxscore")?.optJSONArray("teams") ?: JSONArray()
        if (teamsJson.length() < 2) return emptyList()

        fun statsOf(teamJson: JSONObject?): LinkedHashMap<String, Pair<String, String>> {
            val statsArray = teamJson?.optJSONArray("statistics") ?: JSONArray()
            val map = LinkedHashMap<String, Pair<String, String>>()
            for (i in 0 until statsArray.length()) {
                val statJson = statsArray.optJSONObject(i) ?: continue
                val name = statJson.optString("name")?.takeIf { it.isNotBlank() } ?: continue
                val value = statJson.optString("displayValue")?.takeIf { it.isNotBlank() } ?: continue
                val label = statJson.optString("label")?.takeIf { it.isNotBlank() } ?: name
                map[name] = label to value
            }
            return map
        }

        var homeStats = LinkedHashMap<String, Pair<String, String>>()
        var awayStats = LinkedHashMap<String, Pair<String, String>>()
        for (i in 0 until teamsJson.length()) {
            val teamJson = teamsJson.optJSONObject(i) ?: continue
            when (teamJson.optString("homeAway")) {
                "home" -> homeStats = statsOf(teamJson)
                "away" -> awayStats = statsOf(teamJson)
            }
        }
        val orderedKeys = homeStats.keys.ifEmpty { awayStats.keys }

        return orderedKeys.mapNotNull { key ->
            val home = homeStats[key]
            val away = awayStats[key]
            val label = home?.first ?: away?.first ?: return@mapNotNull null
            SportsGameStat(label = label, homeValue = home?.second ?: "-", awayValue = away?.second ?: "-")
        }
    }

    // ------------------------------------------------------------------ Team page

    /** A JSON value that ESPN gives either as text or as `{ "value": 24.0, "displayValue": "24" }`. */
    private fun JSONObject.textOrDisplayValue(key: String): String? {
        return when (val value = opt(key)) {
            is JSONObject -> value.optString("displayValue").takeIf { it.isNotBlank() }
                ?: value.optDouble("value").takeIf { !it.isNaN() }?.let { number ->
                    if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
                }
            is String -> value.takeIf { it.isNotBlank() }
            is Number -> value.toString()
            else -> null
        }
    }

    private fun JSONObject.str(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }

    /** The logo of a team as ESPN lists it: `logo`, else the first of `logos`. */
    private fun logoOf(teamJson: JSONObject?): String? =
        teamJson?.str("logo") ?: teamJson?.optJSONArray("logos")?.optJSONObject(0)?.str("href")

    /** Where ESPN keeps a player's photo when the data has none ("nfl", "nba", "mlb", "nhl"). */
    private fun headshotFallback(feed: Feed, athleteId: String): String? = when (feed.leaguePath) {
        "nfl", "nba", "mlb", "nhl" -> "https://a.espncdn.com/i/headshots/${feed.leaguePath}/players/full/$athleteId.png"
        else -> null
    }

    /** A player from ESPN's athlete JSON (roster, leaders or box score). */
    private fun parseAthlete(json: JSONObject?, feed: Feed, group: String? = null, useFallbackPhoto: Boolean = false): SportsAthlete? {
        json ?: return null
        val id = json.str("id") ?: return null
        val name = json.str("displayName") ?: json.str("fullName") ?: json.str("shortName") ?: return null
        val headshot = json.optJSONObject("headshot")?.str("href")
            ?: (json.opt("headshot") as? String)?.takeIf { it.startsWith("http") }
            ?: if (useFallbackPhoto) headshotFallback(feed, id) else null
        val position = json.optJSONObject("position")?.let { it.str("abbreviation") ?: it.str("displayName") }
        return SportsAthlete(
            id = id,
            name = name,
            shortName = json.str("shortName"),
            jersey = json.str("jersey"),
            position = position,
            headshotUrl = headshot,
            age = json.optInt("age", 0).takeIf { it > 0 },
            height = json.str("displayHeight"),
            weight = json.str("displayWeight"),
            experienceYears = json.optJSONObject("experience")?.optInt("years", -1)?.takeIf { it >= 0 },
            college = json.optJSONObject("college")?.let { it.str("name") ?: it.str("shortName") },
            group = group
        )
    }

    /** The athletes in an ESPN `leaders` list: `[{ leaders: [{ athlete }] }]`, in order, once each. */
    private fun athletesOfLeaders(leadersJson: JSONArray?, feed: Feed): List<SportsAthlete> {
        leadersJson ?: return emptyList()
        val athletes = LinkedHashMap<String, SportsAthlete>()
        for (i in 0 until leadersJson.length()) {
            val category = leadersJson.optJSONObject(i) ?: continue
            val entries = category.optJSONArray("leaders") ?: continue
            for (j in 0 until entries.length()) {
                val athlete = parseAthlete(entries.optJSONObject(j)?.optJSONObject("athlete"), feed) ?: continue
                athletes.putIfAbsent(athlete.id, athlete)
            }
        }
        return athletes.values.toList()
    }

    /** A team's page header: names, colors, logo, record, standing and stadium. */
    fun fetchTeamProfile(feed: Feed, teamId: String): SportsTeamProfile {
        val json = JSONObject(get("$BASE_URL/${feed.sportPath}/${feed.leaguePath}/teams/$teamId"))
        val team = json.optJSONObject("team") ?: throw EspnException("No team")
        val items = team.optJSONObject("record")?.optJSONArray("items") ?: JSONArray()
        var total: JSONObject? = null
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optString("type") == "total" || total == null) total = item
            if (item.optString("type") == "total") break
        }
        fun totalStat(name: String): Double? {
            val stats = total?.optJSONArray("stats") ?: return null
            for (i in 0 until stats.length()) {
                val stat = stats.optJSONObject(i) ?: continue
                if (stat.optString("name") == name) return stat.optDouble("value").takeIf { !it.isNaN() }
            }
            return null
        }
        val venue = team.optJSONObject("franchise")?.optJSONObject("venue") ?: team.optJSONObject("venue")
        val address = venue?.optJSONObject("address")
        val city = listOfNotNull(address?.str("city"), address?.str("state") ?: address?.str("country"))
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
        val displayName = team.str("displayName") ?: team.str("name") ?: throw EspnException("No team name")
        return SportsTeamProfile(
            id = team.str("id") ?: teamId,
            leagueId = "${feed.sportPath}:${feed.leaguePath}",
            location = team.str("location"),
            nickname = team.str("name") ?: team.str("nickname"),
            displayName = displayName,
            abbreviation = team.str("abbreviation"),
            color = team.str("color")?.removePrefix("#")?.takeIf { it.length == 6 },
            alternateColor = team.str("alternateColor")?.removePrefix("#")?.takeIf { it.length == 6 },
            logoUrl = logoOf(team),
            recordSummary = total?.str("summary"),
            standingSummary = team.str("standingSummary"),
            playoffSeed = totalStat("playoffSeed")?.toInt()?.takeIf { it > 0 },
            points = totalStat("points")?.toInt()?.takeIf { feed.sportPath == "soccer" },
            venueName = venue?.str("fullName"),
            venueCity = city,
            venueImageUrl = venue?.optJSONArray("images")?.optJSONObject(0)?.str("href")
        )
    }

    /** One game of a team schedule's `events`. */
    private fun parseScheduleGame(raw: JSONObject, sourceFeed: Feed, sourceLeagueName: String): SportsTeamGame? {
        // The game's own competition (soccer: the league, the Champions League, a cup...).
        val leagueJson = raw.optJSONObject("league")
        val competitionSlug = leagueJson?.str("slug")?.takeIf { it != "all" }
        val feed = if (competitionSlug != null && competitionSlug != sourceFeed.leaguePath) {
            sourceFeed.copy(leaguePath = competitionSlug)
        } else {
            sourceFeed
        }
        val competitionName = leagueJson?.let { it.str("shortName") ?: it.str("abbreviation") ?: it.str("name") }
        val leagueName = if (competitionSlug != null && competitionSlug != sourceFeed.leaguePath) {
            competitionName ?: sourceLeagueName
        } else {
            sourceLeagueName
        }
        val competitionLogo = leagueJson?.str("alternateId")?.takeIf { feed.sportPath == "soccer" }
            ?.let { "https://a.espncdn.com/i/leaguelogos/soccer/500-dark/$it.png" }
        val competition = raw.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val competitors = competition.optJSONArray("competitors") ?: return null
        var home: JSONObject? = null
        var away: JSONObject? = null
        for (j in 0 until competitors.length()) {
            val competitor = competitors.optJSONObject(j) ?: continue
            when (competitor.optString("homeAway")) {
                "home" -> home = competitor
                "away" -> away = competitor
            }
        }
        if (home == null || away == null) {
            // A few feeds leave homeAway out: the first is home.
            home = home ?: competitors.optJSONObject(0)
            away = away ?: competitors.optJSONObject(1)
        }
        val homeTeam = home?.optJSONObject("team")
        val awayTeam = away?.optJSONObject("team")
        fun nameOf(teamJson: JSONObject?): String =
            teamJson?.str("displayName") ?: teamJson?.str("shortDisplayName") ?: teamJson?.str("name").orEmpty()
        val homeName = nameOf(homeTeam)
        val awayName = nameOf(awayTeam)
        if (homeName.isBlank() && awayName.isBlank()) return null

        val statusType = competition.optJSONObject("status")?.optJSONObject("type")
            ?: raw.optJSONObject("status")?.optJSONObject("type")
        val (status, statusDetail) = statusFrom(statusType)
        val leagueId = "${feed.sportPath}:${feed.leaguePath}"
        val rawId = raw.str("id") ?: competition.str("id") ?: return null

        val broadcasts = competition.optJSONArray("broadcasts")
        var broadcast: String? = null
        if (broadcasts != null) {
            val names = ArrayList<String>()
            for (i in 0 until broadcasts.length()) {
                val entry = broadcasts.optJSONObject(i) ?: continue
                val name = entry.optJSONObject("media")?.str("shortName")
                    ?: entry.optJSONArray("names")?.optString(0)?.takeIf { it.isNotBlank() }
                if (name != null && name !in names) names += name
            }
            broadcast = names.take(2).joinToString(" • ").takeIf { it.isNotBlank() }
        }

        val leaders = LinkedHashMap<String, List<SportsAthlete>>()
        listOfNotNull(home, away).forEach { competitor ->
            val teamId = competitor.optJSONObject("team")?.str("id") ?: return@forEach
            val athletes = athletesOfLeaders(competitor.optJSONArray("leaders"), feed)
            if (athletes.isNotEmpty()) leaders[teamId] = athletes
        }

        val event = SportsEvent(
            id = "espn:${feed.sportPath}:${feed.leaguePath}:$rawId",
            name = raw.str("shortName") ?: raw.str("name") ?: "$awayName @ $homeName",
            sportName = feed.sportLabel,
            leagueId = leagueId,
            leagueName = leagueName,
            homeTeamId = homeTeam?.str("id"),
            homeTeamName = homeName,
            homeTeamBadgeUrl = logoOf(homeTeam),
            awayTeamId = awayTeam?.str("id"),
            awayTeamName = awayName,
            awayTeamBadgeUrl = logoOf(awayTeam),
            homeScore = home?.textOrDisplayValue("score")?.toIntOrNull()?.takeIf { status != SportsEventStatus.UPCOMING },
            awayScore = away?.textOrDisplayValue("score")?.toIntOrNull()?.takeIf { status != SportsEventStatus.UPCOMING },
            startTimeMs = parseNewsDate(raw.str("date") ?: competition.str("date")),
            venue = competition.optJSONObject("venue")?.str("fullName"),
            status = status,
            statusDetail = statusDetail,
            homeTeamAbbreviation = homeTeam?.str("abbreviation"),
            awayTeamAbbreviation = awayTeam?.str("abbreviation"),
            homeTeamColor = homeTeam?.str("color")?.removePrefix("#")?.takeIf { it.length == 6 },
            awayTeamColor = awayTeam?.str("color")?.removePrefix("#")?.takeIf { it.length == 6 },
            homeTeamAliases = teamAliasesOf(homeTeam),
            awayTeamAliases = teamAliasesOf(awayTeam)
        )
        val week = raw.optJSONObject("week")
        val seasonType = raw.optJSONObject("seasonType")
        return SportsTeamGame(
            event = event,
            weekLabel = week?.str("text"),
            weekNumber = week?.optInt("number", 0)?.takeIf { it > 0 },
            seasonTypeLabel = seasonType?.str("name"),
            seasonType = seasonType?.optInt("type", 0)?.takeIf { it > 0 },
            broadcast = broadcast,
            leaders = leaders,
            competitionName = competitionName,
            competitionLogoUrl = competitionLogo
        )
    }

    /**
     * A team's whole season, in order: the regular season and the playoffs (soccer: the results
     * played and the fixtures to come).
     */
    suspend fun fetchTeamSchedule(feed: Feed, teamId: String, leagueName: String): List<SportsTeamGame> {
        if (feed.sportPath == "soccer") return fetchSoccerSchedule(feed, teamId, leagueName)
        val base = "$BASE_URL/${feed.sportPath}/${feed.leaguePath}/teams/$teamId/schedule"
        val urls = if (feed.sportPath == "soccer") {
            listOf(base, "$base?fixture=true")
        } else {
            listOf("$base?seasontype=2", "$base?seasontype=3")
        }
        val games = LinkedHashMap<String, SportsTeamGame>()
        var anyLoaded = false
        urls.forEach { url ->
            val json = runCatching { JSONObject(get(url)) }.getOrNull() ?: return@forEach
            anyLoaded = true
            val events = json.optJSONArray("events") ?: return@forEach
            for (i in 0 until events.length()) {
                val game = parseScheduleGame(events.optJSONObject(i) ?: continue, feed, leagueName) ?: continue
                games.putIfAbsent(game.event.id, game)
            }
        }
        // Nothing in the regular season yet (or a feed without season types): ESPN's own default.
        if (games.isEmpty() && feed.sportPath != "soccer") {
            val json = runCatching { JSONObject(get(base)) }.getOrNull()
            if (json != null) {
                anyLoaded = true
                val events = json.optJSONArray("events") ?: JSONArray()
                for (i in 0 until events.length()) {
                    val game = parseScheduleGame(events.optJSONObject(i) ?: continue, feed, leagueName) ?: continue
                    games.putIfAbsent(game.event.id, game)
                }
            }
        }
        if (!anyLoaded) throw EspnException("Schedule unavailable")
        return games.values.sortedBy { it.event.startTimeMs ?: Long.MAX_VALUE }
    }

    /**
     * A soccer club's season in every competition it plays: its league, the Champions League (or
     * Europa / Conference League), its country's cups and super cup — each game with its
     * competition's name and logo. The competitions are loaded together; one that the club isn't
     * in (or ESPN doesn't have) just adds nothing.
     */
    private suspend fun fetchSoccerSchedule(feed: Feed, teamId: String, leagueName: String): List<SportsTeamGame> =
        coroutineScope {
            val anyLoaded = java.util.concurrent.atomic.AtomicBoolean(false)
            suspend fun load(slug: String): List<SportsTeamGame> {
                val base = "$BASE_URL/soccer/$slug/teams/$teamId/schedule"
                val source = feed.copy(leaguePath = slug)
                val sourceName = if (slug == feed.leaguePath) leagueName else slug
                return listOf(base, "$base?fixture=true").map { url ->
                    async(Dispatchers.IO) {
                        val json = runCatching { JSONObject(get(url)) }.getOrNull() ?: return@async emptyList()
                        anyLoaded.set(true)
                        val events = json.optJSONArray("events") ?: return@async emptyList()
                        (0 until events.length()).mapNotNull { i ->
                            events.optJSONObject(i)?.let { parseScheduleGame(it, source, sourceName) }
                        }
                    }
                }.awaitAll().flatten()
            }

            val home = feed.leaguePath.takeIf { it in SOCCER_DOMESTIC_CUPS }
            val first = buildList {
                add(feed.leaguePath)
                // ESPN's "all": the club's own league, whichever page it was opened from.
                add("all")
                if (home != null) addAll(SOCCER_DOMESTIC_CUPS.getValue(home))
                if (home == null || home in SOCCER_UEFA_LEAGUES) addAll(SOCCER_UEFA_COMPETITIONS)
            }.distinct()
            val firstGames = first.map { slug -> async { load(slug) } }.awaitAll().flatten()

            // Opened from the Champions League: the club's league's cups too, once it's known.
            val domestic = home ?: firstGames.asSequence()
                .mapNotNull { EspnSportsClient.parseEventId(it.event.id)?.leaguePath }
                .firstOrNull { it in SOCCER_DOMESTIC_CUPS }
            val second = domestic?.let { SOCCER_DOMESTIC_CUPS.getValue(it) }.orEmpty().filter { it !in first }
            val secondGames = second.map { slug -> async { load(slug) } }.awaitAll().flatten()

            if (!anyLoaded.get()) throw EspnException("Schedule unavailable")
            (firstGames + secondGames)
                .distinctBy { EspnSportsClient.parseEventId(it.event.id)?.rawEventId ?: it.event.id }
                .sortedBy { it.event.startTimeMs ?: Long.MAX_VALUE }
        }

    /** A team's players, with their photos; NFL rosters come in groups (Offense, Defense...). */
    fun fetchTeamRoster(feed: Feed, teamId: String): List<SportsAthlete> {
        val json = JSONObject(get("$BASE_URL/${feed.sportPath}/${feed.leaguePath}/teams/$teamId/roster"))
        val entries = json.optJSONArray("athletes") ?: return emptyList()
        val athletes = ArrayList<SportsAthlete>()
        fun groupLabel(raw: String?): String? = raw?.takeIf { it.isNotBlank() }?.let { value ->
            when (value) {
                "specialTeam" -> "Special Teams"
                else -> value.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }
            }
        }
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val items = entry.optJSONArray("items")
            if (items != null) {
                val group = groupLabel(entry.str("position") ?: entry.str("name"))
                for (j in 0 until items.length()) {
                    parseAthlete(items.optJSONObject(j), feed, group, useFallbackPhoto = true)?.let { athletes += it }
                }
            } else {
                parseAthlete(entry, feed, useFallbackPhoto = true)?.let { athletes += it }
            }
        }
        return athletes
    }

    /** A team's season stats, by group ("Passing", "Rushing"...). */
    fun fetchTeamStats(feed: Feed, teamId: String): List<SportsTeamStatCategory> {
        val json = JSONObject(get("$BASE_URL/${feed.sportPath}/${feed.leaguePath}/teams/$teamId/statistics"))
        val categories = json.optJSONObject("results")?.optJSONObject("stats")?.optJSONArray("categories")
            ?: json.optJSONObject("statistics")?.optJSONArray("splits")
            ?: return emptyList()
        val result = ArrayList<SportsTeamStatCategory>()
        for (i in 0 until categories.length()) {
            val category = categories.optJSONObject(i) ?: continue
            val name = category.str("displayName") ?: category.str("name") ?: continue
            val statsJson = category.optJSONArray("stats") ?: continue
            val stats = ArrayList<SportsStandingStat>()
            for (j in 0 until statsJson.length()) {
                val stat = statsJson.optJSONObject(j) ?: continue
                val value = stat.str("displayValue") ?: continue
                val label = stat.str("displayName") ?: stat.str("shortDisplayName") ?: stat.str("name") ?: continue
                stats += SportsStandingStat(label = label, value = value)
            }
            if (stats.isNotEmpty()) result += SportsTeamStatCategory(name = name, stats = stats)
        }
        return result
    }

    /** A team's ESPN news. */
    fun fetchTeamNews(feed: Feed, teamId: String, limit: Int = 24): List<SportsNewsArticle> {
        val url = "$BASE_URL/${feed.sportPath}/${feed.leaguePath}/news?team=$teamId&limit=$limit"
        val json = JSONObject(get(url))
        val articlesJson = json.optJSONArray("articles") ?: JSONArray()
        val articles = ArrayList<SportsNewsArticle>(articlesJson.length())
        for (i in 0 until articlesJson.length()) {
            val articleJson = articlesJson.optJSONObject(i) ?: continue
            val headline = articleJson.str("headline") ?: continue
            articles += SportsNewsArticle(
                id = articleJson.str("id") ?: "news:${feed.leaguePath}:$teamId:$i",
                headline = headline,
                description = articleJson.str("description"),
                imageUrl = articleJson.optJSONArray("images")?.optJSONObject(0)?.str("url"),
                publishedMs = parseNewsDate(articleJson.str("published")),
                link = articleJson.optJSONObject("links")?.optJSONObject("web")?.str("href")
            )
        }
        return articles
    }

    /**
     * The players of a game, by team id (the game's leaders: who led each team in it, or before it
     * the season's leaders), from ESPN's game summary; the box score's players when it has no leaders.
     */
    fun fetchGamePlayers(feed: Feed, rawEventId: String): Map<String, List<SportsAthlete>> {
        val json = JSONObject(get("$BASE_URL/${feed.sportPath}/${feed.leaguePath}/summary?event=$rawEventId"))
        val result = LinkedHashMap<String, List<SportsAthlete>>()
        val leaders = json.optJSONArray("leaders")
        if (leaders != null) {
            for (i in 0 until leaders.length()) {
                val entry = leaders.optJSONObject(i) ?: continue
                val teamId = entry.optJSONObject("team")?.str("id") ?: continue
                val athletes = athletesOfLeaders(entry.optJSONArray("leaders"), feed).filter { it.headshotUrl != null }
                if (athletes.isNotEmpty()) result[teamId] = athletes
            }
        }
        if (result.isEmpty()) {
            val players = json.optJSONObject("boxscore")?.optJSONArray("players") ?: JSONArray()
            for (i in 0 until players.length()) {
                val entry = players.optJSONObject(i) ?: continue
                val teamId = entry.optJSONObject("team")?.str("id") ?: continue
                val athletes = LinkedHashMap<String, SportsAthlete>()
                val statistics = entry.optJSONArray("statistics") ?: continue
                for (j in 0 until statistics.length()) {
                    val first = statistics.optJSONObject(j)?.optJSONArray("athletes")?.optJSONObject(0) ?: continue
                    val athlete = parseAthlete(first.optJSONObject("athlete"), feed) ?: continue
                    if (athlete.headshotUrl != null) athletes.putIfAbsent(athlete.id, athlete)
                }
                if (athletes.isNotEmpty()) result[teamId] = athletes.values.toList()
            }
        }
        return result
    }

    /**
     * A team's history from Wikipedia: the summary of the first of [titles] that is a page about a
     * team (not a disambiguation page), in [language] ("es"), else in English.
     */
    fun fetchTeamHistory(titles: List<String>, language: String, keywords: List<String>): SportsTeamHistory? {
        val languages = listOf(language, "en").distinct()
        for (lang in languages) {
            for (title in titles) {
                val encoded = java.net.URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "_")
                val url = "https://$lang.wikipedia.org/api/rest_v1/page/summary/$encoded?redirect=true"
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "RCTV/1.0 (Android TV)")
                    .build()
                val body = runCatching {
                    okHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) response.body?.string() else null
                    }
                }.getOrNull() ?: continue
                val json = runCatching { JSONObject(body) }.getOrNull() ?: continue
                if (json.optString("type") == "disambiguation") continue
                val extract = json.str("extract") ?: continue
                val about = (json.optString("description") + " " + extract).lowercase(Locale.ROOT)
                if (keywords.none { it in about }) continue
                return SportsTeamHistory(
                    title = json.str("title") ?: title,
                    summary = extract,
                    imageUrl = json.optJSONObject("originalimage")?.str("source")
                        ?: json.optJSONObject("thumbnail")?.str("source"),
                    pageUrl = json.optJSONObject("content_urls")?.optJSONObject("desktop")?.str("page")
                )
            }
        }
        return null
    }
}
