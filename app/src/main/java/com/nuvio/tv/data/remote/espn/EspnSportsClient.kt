package com.nuvio.tv.data.remote.espn

import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import com.nuvio.tv.domain.model.SportsLeague
import com.nuvio.tv.domain.model.SportsTeam
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

        private const val BASE_URL = "https://site.api.espn.com/apis/site/v2/sports"
        private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply {
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

            val homeTeamId = idOf(homeTeamJson)
            val awayTeamId = idOf(awayTeamJson)
            val homeTeamName = displayName(homeTeamJson)
            val awayTeamName = displayName(awayTeamJson)
            val homeBadge = badgeOf(homeTeamJson)
            val awayBadge = badgeOf(awayTeamJson)

            if (homeTeamId != null && homeTeamName.isNotBlank()) {
                teams[homeTeamId] = SportsTeam(
                    id = homeTeamId, name = homeTeamName, shortName = homeTeamName,
                    sportName = feed.sportLabel, leagueId = leagueId, leagueName = leagueName, badgeUrl = homeBadge
                )
            }
            if (awayTeamId != null && awayTeamName.isNotBlank()) {
                teams[awayTeamId] = SportsTeam(
                    id = awayTeamId, name = awayTeamName, shortName = awayTeamName,
                    sportName = feed.sportLabel, leagueId = leagueId, leagueName = leagueName, badgeUrl = awayBadge
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
                statusDetail = statusDetail
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
}
