package com.nuvio.tv.data.remote.espn

import android.content.Context
import com.nuvio.tv.data.remote.sportsdb.CflSource
import com.nuvio.tv.data.remote.sportsdb.PpvEventsSource
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
import com.nuvio.tv.domain.model.SportsRaceResult
import com.nuvio.tv.domain.model.SportsShowDetails
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
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
    @Named("addonPermissive") private val okHttpClient: OkHttpClient,
    @ApplicationContext private val appContext: Context
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
            Feed("soccer", "uefa.europa", "Soccer", "⚽"),
            Feed("soccer", "usa.1", "Soccer", "⚽"),
            Feed("soccer", "mex.1", "Soccer", "⚽"),
            Feed("soccer", "conmebol.libertadores", "Soccer", "⚽"),
            Feed("soccer", "conmebol.sudamericana", "Soccer", "⚽"),
            Feed("soccer", "concacaf.champions", "Soccer", "⚽"),
            // National teams' and clubs' world tournaments (every few years).
            Feed("soccer", "fifa.world", "Soccer", "⚽"),
            Feed("soccer", "fifa.cwc", "Soccer", "⚽"),
            Feed("soccer", "uefa.nations", "Soccer", "⚽"),
            Feed("soccer", "conmebol.america", "Soccer", "⚽"),
            Feed("basketball", "nba", "Basketball", "🏀"),
            Feed("basketball", "wnba", "Basketball", "🏀"),
            Feed("football", "nfl", "Football", "🏈"),
            Feed("football", "ufl", "Football", "🏈"),
            // The CFL: ESPN's data for it stopped, see [CflSource].
            Feed(CflSource.SPORT_PATH, CflSource.LEAGUE_PATH, CflSource.SPORT_LABEL, "🏈"),
            Feed("baseball", "mlb", "Baseball", "⚾"),
            // Banana Ball (the Savannah Bananas' league): not on ESPN, see [BananaBallSource].
            Feed("baseball", BananaBallSource.LEAGUE_PATH, "Baseball", "🍌"),
            Feed("baseball", "world-baseball-classic", "Baseball", "⚾"),
            // LIDOM, the Caribbean Series and the Minor Leagues: from MLB's Stats API, see [MlbStatsSource].
            Feed(MlbStatsSource.SPORT_PATH, "lidom", MlbStatsSource.SPORT_LABEL, "🇩🇴"),
            Feed(MlbStatsSource.SPORT_PATH, "caribbean-series", MlbStatsSource.SPORT_LABEL, "⚾"),
            Feed(MlbStatsSource.SPORT_PATH, "milb-aaa", MlbStatsSource.SPORT_LABEL, "⚾"),
            Feed(MlbStatsSource.SPORT_PATH, "milb-aa", MlbStatsSource.SPORT_LABEL, "⚾"),
            Feed(MlbStatsSource.SPORT_PATH, "milb-high-a", MlbStatsSource.SPORT_LABEL, "⚾"),
            Feed(MlbStatsSource.SPORT_PATH, "milb-a", MlbStatsSource.SPORT_LABEL, "⚾"),
            Feed("hockey", "nhl", "Hockey", "🏒"),
            Feed("mma", "ufc", "MMA", "🥋"),
            // Boxing and the big wrestling shows: from TheSportsDB, see [PpvEventsSource]; boxing's
            // bodies and promoters are leagues of their fights.
            Feed(PpvEventsSource.SPORT_PATH, PpvEventsSource.LEAGUE_PATH, PpvEventsSource.SPORT_LABEL, "🥊")
        ) + PpvEventsSource.BOXING_LEAGUES.map { Feed(PpvEventsSource.SPORT_PATH, it.leaguePath, PpvEventsSource.SPORT_LABEL, "🥊") } + listOf(
            Feed("racing", "f1", "Motorsport", "🏎️"),
            // NASCAR's three national series: from NASCAR's own feeds, see [NascarSource].
            Feed(NascarSource.SPORT_PATH, "nascar-premier", NascarSource.SPORT_LABEL, "🏁"),
            Feed(NascarSource.SPORT_PATH, "nascar-secondary", NascarSource.SPORT_LABEL, "🏁"),
            Feed(NascarSource.SPORT_PATH, "nascar-truck", NascarSource.SPORT_LABEL, "🏁"),
            Feed("racing", "irl", "Motorsport", "🏎️"),
            // MotoGP: from its own results API, see [MotoGpSource].
            Feed(MotoGpSource.SPORT_PATH, MotoGpSource.LEAGUE_PATH, MotoGpSource.SPORT_LABEL, "🏍️"),
            Feed("tennis", "atp", "Tennis", "🎾"),
            Feed("tennis", "wta", "Tennis", "🎾")
        ) + VirtualLeagues.LEAGUES.filter { it.sportPath == VirtualLeagues.TENNIS }.map { Feed(it.sportPath, it.leaguePath, "Tennis", "🎾") } + listOf(
            // Golf: tournaments (a leaderboard of players), not games between teams.
            Feed("golf", "pga", "Golf", "⛳"),
            Feed("golf", "champions-tour", "Golf", "⛳"),
            Feed("golf", "lpga", "Golf", "⛳"),
            Feed("golf", "eur", "Golf", "⛳"),
            Feed("golf", "ntw", "Golf", "⛳"),
            Feed("golf", "liv", "Golf", "⛳")
        ) + VirtualLeagues.LEAGUES.filter { it.sportPath == "golf" }.map { Feed(it.sportPath, it.leaguePath, "Golf", "⛳") } + listOf(
            // The NCAA: college football (American football), men's basketball and baseball, every
            // division's teams.
            Feed("football", "college-football", "Football", "🏈"),
            Feed("basketball", "mens-college-basketball", "Basketball", "🏀"),
            Feed("baseball", "college-baseball", "Baseball", "⚾")
        )

        const val GOLF = "golf"

        /** Leagues loading at the same time (each holds its whole answer while it's read). */
        private const val MAX_FEEDS_AT_ONCE = 6

        /** A league's games in the rows: the next ones (a college weekend alone is over a hundred). */
        private const val ROWS_EVENT_LIMIT = 120

        /** The most an answer is read: bigger means something's wrong, and reading it would take all the memory. */
        internal const val MAX_RESPONSE_BYTES = 12L * 1024 * 1024

        /**
         * The leagues' own threads: as many as load at once (and a few for the waiting), apart
         * from the threads the rest of the app shares.
         */
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private val feedDispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(MAX_FEEDS_AT_ONCE + 4)

        /** Tennis' days in the rows (its tournaments list every match; more is on a tour's page). */
        private const val TENNIS_RANGE_DAYS = 2

        /** A league's page between seasons: this many of its next (or last) games. */
        private const val LEAGUE_HOME_EVENTS = 40

        /** A race is surely over this long after its start. */
        private const val RACE_OVER_MS = 6L * 60 * 60 * 1000

        /** The other names of ESPN's racing series, as TV guides write them. */
        private val RACING_ALIASES = mapOf(
            "f1" to listOf("Formula 1", "Formula One", "F1"),
            "irl" to listOf("IndyCar", "NTT IndyCar Series", "INDYCAR")
        )

        /** The NCAA's leagues: hundreds of teams each, by division and conference. */
        val COLLEGE_LEAGUE_PATHS = setOf("college-football", "mens-college-basketball", "college-baseball")

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
            "uefa.europa" to "Europa League",
            "usa.1" to "MLS",
            "mex.1" to "Liga MX",
            "conmebol.libertadores" to "Copa Libertadores",
            "conmebol.sudamericana" to "Copa Sudamericana",
            "concacaf.champions" to "CONCACAF Champions Cup",
            "fifa.world" to "FIFA World Cup",
            "fifa.cwc" to "FIFA Club World Cup",
            "uefa.nations" to "UEFA Nations League",
            "conmebol.america" to "Copa América",
            "nba" to "NBA",
            "wnba" to "WNBA",
            "nfl" to "NFL",
            "ufl" to "UFL",
            CflSource.LEAGUE_PATH to CflSource.LEAGUE_NAME,
            "mlb" to "MLB",
            BananaBallSource.LEAGUE_PATH to BananaBallSource.LEAGUE_NAME,
            "world-baseball-classic" to "World Baseball Classic",
            "lidom" to "LIDOM",
            "caribbean-series" to "Serie del Caribe",
            "milb-aaa" to "Triple-A",
            "milb-aa" to "Double-A",
            "milb-high-a" to "High-A",
            "milb-a" to "Single-A",
            "nhl" to "NHL",
            "ufc" to "UFC",
            "f1" to "Formula 1",
            "nascar-premier" to "NASCAR Cup Series",
            "nascar-secondary" to "NASCAR O'Reilly (Xfinity) Series",
            "nascar-truck" to "NASCAR Truck Series",
            "irl" to "IndyCar Series",
            MotoGpSource.LEAGUE_PATH to MotoGpSource.LEAGUE_NAME,
            PpvEventsSource.LEAGUE_PATH to PpvEventsSource.LEAGUE_NAME,
            "atp" to "ATP Tour",
            "wta" to "WTA Tour",
            "pga" to "PGA Tour",
            "champions-tour" to "PGA Tour Champions",
            "lpga" to "LPGA Tour",
            "eur" to "DP World Tour",
            "ntw" to "Korn Ferry Tour",
            "liv" to "LIV Golf",
            "college-football" to "NCAA Football",
            "mens-college-basketball" to "NCAA Basketball",
            "college-baseball" to "NCAA Baseball"
        ) + PpvEventsSource.BOXING_LEAGUES.associate { it.leaguePath to it.name } +
            VirtualLeagues.LEAGUES.associate { it.leaguePath to it.name }

        /** Golf, tennis, racing and PPV Events have players, drivers and fighters, not teams. */
        private fun hasTeams(feed: Feed): Boolean =
            feed.sportPath != GOLF && feed.sportPath != VirtualLeagues.TENNIS && feed.sportPath != "racing" &&
                feed.leaguePath !in PpvEventsSource.LEAGUE_PATHS && feed.leaguePath !in VirtualLeagues.LEAGUE_PATHS

        /** One filter chip per curated feed, for the Teams browse screen. */
        val TEAMS_FILTERS: List<TeamsFilterChip> = FEEDS.filter { hasTeams(it) }.map { feed ->
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

        /**
         * The leagues a TV program can be a game of, by what its title, categories and channel say
         * ("NBA", "Premier League", "béisbol", "NASCAR", "ESPN Deportes"...): a handful, not every
         * league. Nothing said about the sport: the big leagues. [text] is plain lowercase words
         * (accents and symbols taken out).
         */
        fun feedsForProgram(text: String): List<Feed> {
            val words = " ${text.trim()} "
            fun has(vararg terms: String) = terms.any { words.contains(" $it ") }
            val paths = LinkedHashSet<String>()
            // Leagues by name.
            if (has("nfl", "nfl network", "sunday night football", "monday night football", "thursday night football", "redzone")) paths += "nfl"
            if (has("college football", "ncaa football", "ncaaf", "cfb", "sec network", "big ten", "acc network")) paths += "college-football"
            if (has("ufl")) paths += "ufl"
            if (has("cfl", "canadian football")) paths += CflSource.LEAGUE_PATH
            if (has("nba", "nba tv")) paths += "nba"
            if (has("wnba")) paths += "wnba"
            if (has("college basketball", "ncaa basketball", "ncaab", "march madness")) paths += "mens-college-basketball"
            if (has("mlb", "mlb network", "baseball tonight")) paths += "mlb"
            if (has("lidom", "beisbol invernal", "pelota invernal", "liga dominicana")) paths += "lidom"
            if (has("serie del caribe", "caribbean series")) paths += "caribbean-series"
            if (has("milb", "minor league", "triple a", "double a")) paths += listOf("milb-aaa", "milb-aa")
            if (has("college baseball")) paths += "college-baseball"
            if (has("world baseball classic", "clasico mundial")) paths += "world-baseball-classic"
            if (has("banana ball", "savannah bananas")) paths += BananaBallSource.LEAGUE_PATH
            if (has("nhl", "nhl network", "hockey")) paths += "nhl"
            if (has("premier league", "epl")) paths += "eng.1"
            if (has("laliga", "la liga")) paths += "esp.1"
            if (has("serie a")) paths += "ita.1"
            if (has("bundesliga")) paths += "ger.1"
            if (has("ligue 1")) paths += "fra.1"
            if (has("champions league", "liga de campeones", "uefa champions")) paths += "uefa.champions"
            if (has("europa league")) paths += "uefa.europa"
            if (has("mls", "major league soccer")) paths += "usa.1"
            if (has("liga mx", "ligamx")) paths += "mex.1"
            if (has("libertadores")) paths += "conmebol.libertadores"
            if (has("sudamericana")) paths += "conmebol.sudamericana"
            if (has("concacaf")) paths += "concacaf.champions"
            if (has("club world cup", "mundial de clubes")) paths += "fifa.cwc"
            if (has("world cup", "copa mundial", "mundial")) paths += "fifa.world"
            if (has("nations league")) paths += "uefa.nations"
            if (has("copa america")) paths += "conmebol.america"
            if (has("ufc", "mma")) paths += "ufc"
            if (has("boxing", "boxeo", "ppv", "pay per view", "wwe", "aew", "wrestling", "lucha libre", "top rank", "dazn")) paths += PpvEventsSource.LEAGUE_PATH
            if (has("nascar")) paths += NascarSource.LEAGUE_PATHS
            if (has("indycar")) paths += "irl"
            if (has("formula 1", "formula one", "f1")) paths += "f1"
            if (has("motogp")) paths += MotoGpSource.LEAGUE_PATH
            if (has("tennis", "tenis", "atp", "wta", "tennis channel")) paths += listOf("atp", "wta")
            if (has("golf", "pga", "lpga", "liv golf", "golf channel")) paths += listOf("pga", "lpga", "liv", "eur")
            // Only the sport said: its main leagues.
            if (paths.isEmpty()) {
                if (has("soccer", "futbol", "beinsports", "bein", "tudn", "fox deportes")) {
                    paths += listOf("eng.1", "esp.1", "usa.1", "mex.1", "uefa.champions", "ita.1", "ger.1", "fra.1")
                }
                if (has("basketball", "baloncesto", "basquetbol")) paths += listOf("nba", "wnba", "mens-college-basketball")
                if (has("baseball", "beisbol")) paths += listOf("mlb", "lidom")
                if (has("football", "futbol americano")) paths += listOf("nfl", "college-football")
                if (has("racing", "motorsports", "auto racing", "carreras")) paths += listOf("nascar-premier", "f1", "irl", MotoGpSource.LEAGUE_PATH)
            }
            // Nothing said at all: the big leagues.
            if (paths.isEmpty()) paths += HEADLINE_LEAGUE_PATHS + listOf("college-football", "usa.1", "mex.1")
            return FEEDS.filter { it.leaguePath in paths }
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

        /** League-table numbers that say nothing on a team's page. */
        private val SKIPPED_TABLE_STATS = setOf("advanced", "rankChange", "deductions", "overall", "streak", "clincher", "playoffSeed")

        // Every league loads at once, on many threads, and a SimpleDateFormat shared between
        // threads reads wrong dates (or throws): each thread has its own.
        private fun utcFormat(pattern: String): ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }
        private val isoFormatLocal = utcFormat("yyyy-MM-dd'T'HH:mm'Z'")
        private val isoFormatWithSecondsLocal = utcFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        private val dayFormatLocal = utcFormat("yyyyMMdd")
        private val isoFormat: SimpleDateFormat get() = isoFormatLocal.get()!!
        private val isoFormatWithSeconds: SimpleDateFormat get() = isoFormatWithSecondsLocal.get()!!
        private val dayFormat: SimpleDateFormat get() = dayFormatLocal.get()!!
    }

    /** Banana Ball: not on ESPN; its teams and schedule come from its own site. */
    private val bananaBall by lazy { BananaBallSource(okHttpClient) }

    private fun Feed.isBananaBall(): Boolean = leaguePath == BananaBallSource.LEAGUE_PATH

    /** NASCAR: from NASCAR's own feeds. */
    private val nascar by lazy { NascarSource(okHttpClient) }

    private fun Feed.isNascar(): Boolean = leaguePath in NascarSource.LEAGUE_PATHS

    /** PPV Events (boxing, wrestling's big shows): from TheSportsDB, kept in the app's cache. */
    private val ppv by lazy { PpvEventsSource(okHttpClient, java.io.File(appContext.cacheDir, "ppv_events.json")) }

    private fun Feed.isPpv(): Boolean = sportPath == PpvEventsSource.SPORT_PATH && leaguePath in PpvEventsSource.LEAGUE_PATHS

    /** The CFL: from TheSportsDB, kept in the app's cache. */
    private val cfl by lazy { CflSource(okHttpClient, java.io.File(appContext.cacheDir, "cfl_games.json")) }

    private fun Feed.isCfl(): Boolean = sportPath == CflSource.SPORT_PATH && leaguePath == CflSource.LEAGUE_PATH

    /** LIDOM, the Caribbean Series and the Minor Leagues: from MLB's Stats API. */
    private val mlbStats by lazy { MlbStatsSource(okHttpClient) }

    private fun Feed.isMlbStats(): Boolean = sportPath == MlbStatsSource.SPORT_PATH && leaguePath in MlbStatsSource.LEAGUE_PATHS

    /** MotoGP: from its own results API. */
    private val motoGp by lazy { MotoGpSource(okHttpClient) }

    private fun Feed.isMotoGp(): Boolean = sportPath == MotoGpSource.SPORT_PATH && leaguePath == MotoGpSource.LEAGUE_PATH

    /** A Grand Slam or a golf major: a tournament of a tour's, shown as a league. */
    private fun Feed.virtual(): VirtualLeagues.VirtualLeague? = VirtualLeagues.of(sportPath, leaguePath)

    /**
     * Leagues whose events are another league's too (a Grand Slam's are the ATP's, a boxing body's
     * are PPV Events'): left out where every league's games are gathered, so none shows twice.
     */
    private fun Feed.isSubLeague(): Boolean =
        virtual() != null || (isPpv() && leaguePath != PpvEventsSource.LEAGUE_PATH)

    /** A race's places: the running order while it's on, else its results (NASCAR, MotoGP, F1, IndyCar). */
    fun fetchRaceResults(leaguePath: String, rawEventId: String, startTimeMs: Long?): List<SportsRaceResult> = when {
        leaguePath in NascarSource.LEAGUE_PATHS -> rawEventId.toLongOrNull()?.let { nascar.raceResults(leaguePath, it, startTimeMs) }.orEmpty()
        leaguePath == MotoGpSource.LEAGUE_PATH -> motoGp.raceResults(rawEventId)
        else -> espnRaceResults(leaguePath, rawEventId, startTimeMs)
    }

    /** A PPV show's poster, description and card. */
    fun fetchShowDetails(rawEventId: String): SportsShowDetails? = ppv.details(rawEventId)

    // ------------------------------------------------------------------ leagues of other leagues' events

    /** A Grand Slam's or a major's events: its tours' (in [datesRange]) that are it. */
    private fun virtualFeed(feed: Feed, virtual: VirtualLeagues.VirtualLeague, datesRange: String?): FeedResult {
        val parents = virtual.parents.mapNotNull { path -> FEEDS.firstOrNull { it.sportPath == virtual.sportPath && it.leaguePath == path } }
        val results = parents.mapNotNull { parent -> runCatching { fetchFeed(parent, datesRange) }.getOrNull() }
        if (results.isEmpty() && parents.isNotEmpty()) throw EspnException("No ${virtual.name}")
        val events = results.flatMap { it.events }
            .filter { virtual.matches(it) }
            .map { it.copy(leagueName = virtual.name) }
            .sortedBy { it.startTimeMs ?: Long.MAX_VALUE }
        return FeedResult(feed = feed, league = virtualLeagueOf(feed, virtual), events = events, teams = emptyList())
    }

    private fun virtualLeagueOf(feed: Feed, virtual: VirtualLeagues.VirtualLeague) = SportsLeague(
        id = "${feed.sportPath}:${feed.leaguePath}",
        name = virtual.name,
        sportName = feed.sportLabel,
        badgeUrl = virtual.logo,
        country = null
    )

    /** A league of another's events, as its tile shows it. */
    private fun subLeagueOf(feed: Feed): SportsLeague = feed.virtual()?.let { virtualLeagueOf(feed, it) } ?: ppv.league(feed.leaguePath)

    /** How many days an ESPN "yyyyMMdd-yyyyMMdd" range spans. */
    private fun rangeDays(datesRange: String): Long {
        val parts = datesRange.split('-')
        if (parts.size < 2) return 0
        val from = runCatching { dayFormat.parse(parts.first())?.time }.getOrNull() ?: return 0
        val to = runCatching { dayFormat.parse(parts.last())?.time }.getOrNull() ?: return 0
        return (to - from) / (24L * 60 * 60 * 1000)
    }

    /**
     * A league's page: its games from a few days ago to ten days on; between seasons (or editions of
     * a tournament), its next ones within a year, else its last ones.
     */
    fun fetchLeagueHome(feed: Feed): FeedResult {
        val near = fetchFeed(feed, dateRange(-3, 10))
        if (near.events.isNotEmpty()) return near
        // Banana Ball between seasons: its next season's games (its whole schedule).
        if (feed.isBananaBall()) return runCatching { fetchFeed(feed) }.getOrDefault(near)
        val virtual = feed.virtual()
        if (virtual?.window != null) {
            // A Grand Slam between editions: its last one's final days (before its draw, there's nothing ahead).
            val (_, end) = virtual.lastEdition(java.time.LocalDate.now(java.time.ZoneOffset.UTC)) ?: return near
            val range = "${end.minusDays(4).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)}-" +
                end.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
            return runCatching { fetchFeed(feed, range) }.getOrNull()?.takeIf { it.events.isNotEmpty() } ?: near
        }
        // Tennis' tours and the NCAA play too much in a year to look that far.
        if (feed.sportPath == VirtualLeagues.TENNIS || feed.leaguePath in COLLEGE_LEAGUE_PATHS) return near
        val ahead = runCatching { fetchFeed(feed, dateRange(11, 365)) }.getOrNull()
        if (ahead != null && ahead.events.isNotEmpty()) {
            return ahead.copy(events = ahead.events.sortedBy { it.startTimeMs ?: Long.MAX_VALUE }.take(LEAGUE_HOME_EVENTS))
        }
        // Golf's past year is every tournament's whole leaderboard: too much.
        if (feed.sportPath == GOLF) return near
        val before = runCatching { fetchFeed(feed, dateRange(-240, -4)) }.getOrNull()
        if (before != null && before.events.isNotEmpty()) {
            return before.copy(events = before.events.sortedBy { it.startTimeMs ?: 0L }.takeLast(LEAGUE_HOME_EVENTS))
        }
        // A tournament played every few years (Copa América, the Club World Cup): ESPN's own pick, its last games.
        if (!feed.isPpv() && !feed.isNascar() && !feed.isMotoGp() && !feed.isCfl() && !feed.isMlbStats()) {
            runCatching { fetchFeed(feed) }.getOrNull()?.takeIf { it.events.isNotEmpty() }?.let { return it }
        }
        return near
    }

    // ------------------------------------------------------------------ tennis

    /** A tennis tournament's singles matches, each one an event (player against player). */
    private fun tennisMatchesOf(feed: Feed, raw: JSONObject, leagueId: String, leagueName: String): List<SportsEvent> {
        val tournament = raw.str("name") ?: raw.str("shortName").orEmpty()
        val groupings = raw.optJSONArray("groupings") ?: return emptyList()
        val matches = ArrayList<SportsEvent>()
        for (g in 0 until groupings.length()) {
            val grouping = groupings.optJSONObject(g) ?: continue
            val slug = grouping.optJSONObject("grouping")?.str("slug").orEmpty()
            // The singles (not the doubles).
            if (!slug.contains("singles")) continue
            val competitions = grouping.optJSONArray("competitions") ?: continue
            for (c in 0 until competitions.length()) {
                val match = competitions.optJSONObject(c) ?: continue
                tennisMatchOf(feed, match, tournament, leagueId, leagueName)?.let { matches += it }
            }
        }
        return matches
    }

    private fun tennisMatchOf(feed: Feed, match: JSONObject, tournament: String, leagueId: String, leagueName: String): SportsEvent? {
        val id = match.str("id") ?: return null
        val competitorsJson = match.optJSONArray("competitors") ?: return null
        val players = (0 until competitorsJson.length()).mapNotNull { competitorsJson.optJSONObject(it) }
        if (players.size < 2) return null
        val first = players.firstOrNull { it.optString("homeAway") == "home" } ?: players.minByOrNull { it.optInt("order", 99) } ?: return null
        val second = players.firstOrNull { it !== first } ?: return null
        fun nameOf(player: JSONObject): String =
            player.optJSONObject("athlete")?.let { it.str("displayName") ?: it.str("fullName") } ?: "TBD"
        fun flagOf(player: JSONObject): String? = player.optJSONObject("athlete")?.optJSONObject("flag")?.str("href")
        fun setsOf(player: JSONObject): List<JSONObject> {
            val array = player.optJSONArray("linescores") ?: return emptyList()
            return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        }
        fun lastNameOf(name: String): String? = name.substringAfterLast(' ').takeIf { it.length >= 3 && it != name }
        val firstSets = setsOf(first)
        val secondSets = setsOf(second)
        val (status, detail) = statusFrom(match.optJSONObject("status")?.optJSONObject("type"))
        // "6-4 3-6 7-6(5)": the sets, the first player's games first.
        val setLine = firstSets.indices.mapNotNull { index ->
            val mine = firstSets[index].optDouble("value").takeIf { !it.isNaN() }?.toInt() ?: return@mapNotNull null
            val theirs = secondSets.getOrNull(index)?.optDouble("value")?.takeIf { !it.isNaN() }?.toInt() ?: return@mapNotNull null
            val tiebreak = (firstSets[index].optInt("tiebreak", -1).takeIf { it >= 0 } ?: secondSets[index].optInt("tiebreak", -1))
                .takeIf { it >= 0 }
            "$mine-$theirs" + (tiebreak?.let { "($it)" } ?: "")
        }.joinToString(" ")
        val firstName = nameOf(first)
        val secondName = nameOf(second)
        val round = match.optJSONObject("round")?.str("displayName") ?: match.optJSONObject("type")?.str("text")
        val court = (match.optJSONObject("venue")?.opt("court") as? String)?.takeIf { it.isNotBlank() }
        return SportsEvent(
            id = "espn:${feed.sportPath}:${feed.leaguePath}:$id",
            name = "$firstName vs $secondName",
            sportName = feed.sportLabel,
            leagueId = leagueId,
            leagueName = leagueName,
            homeTeamId = null,
            homeTeamName = firstName,
            homeTeamBadgeUrl = flagOf(first),
            awayTeamId = null,
            awayTeamName = secondName,
            awayTeamBadgeUrl = flagOf(second),
            homeScore = firstSets.count { it.optBoolean("winner") }.takeIf { status != SportsEventStatus.UPCOMING },
            awayScore = secondSets.count { it.optBoolean("winner") }.takeIf { status != SportsEventStatus.UPCOMING },
            startTimeMs = parseStartTimeMs(match.str("date") ?: match.str("startDate")),
            // The tournament first: a Grand Slam's league knows its matches by it.
            venue = listOfNotNull(tournament.takeIf { it.isNotBlank() }, round, court).joinToString(" • ").ifBlank { null },
            status = status,
            statusDetail = when (status) {
                SportsEventStatus.UPCOMING -> if (!match.optBoolean("timeValid", true)) BananaBallSource.TIME_TBA else null
                else -> listOfNotNull(detail, setLine.ifBlank { null }).joinToString(" • ").ifBlank { null }
            },
            homeTeamAliases = listOfNotNull(lastNameOf(firstName)),
            awayTeamAliases = listOfNotNull(lastNameOf(secondName))
        )
    }

    // ------------------------------------------------------------------ racing (ESPN's: Formula 1, IndyCar)

    /** A race weekend as one event: the race's start, its circuit, its winner once it's run. */
    private fun raceEventOf(feed: Feed, raw: JSONObject, leagueId: String, leagueName: String, leagueBadge: String?): SportsEvent? {
        val id = raw.str("id") ?: return null
        val name = raw.str("name") ?: raw.str("shortName") ?: return null
        val race = raceCompetitionOf(raw)
        val start = parseStartTimeMs(race?.str("date") ?: raw.str("date"))
        val statusType = race?.optJSONObject("status")?.optJSONObject("type") ?: raw.optJSONObject("status")?.optJSONObject("type")
        var (status, detail) = statusFrom(statusType)
        // Schedules ESPN doesn't update (IndyCar's): a race long past is over.
        if (status == SportsEventStatus.UPCOMING && start != null && System.currentTimeMillis() > start + RACE_OVER_MS) {
            status = SportsEventStatus.FINISHED
            detail = "Final"
        }
        val winner = race?.let { raceCompetitorsOf(it) }?.let { drivers ->
            drivers.firstOrNull { it.optBoolean("winner") }
                ?: drivers.minByOrNull { it.optInt("order", Int.MAX_VALUE) }?.takeIf { status == SportsEventStatus.FINISHED }
        }
        val circuit = raw.optJSONObject("circuit")
        val circuitName = circuit?.str("fullName") ?: race?.optJSONObject("venue")?.str("fullName")
        val place = circuit?.optJSONObject("address")?.let { address -> listOfNotNull(address.str("city"), address.str("country")).joinToString(", ") }
        val series = FEED_LABELS[feed.leaguePath] ?: leagueName
        return SportsEvent(
            id = "espn:${feed.sportPath}:${feed.leaguePath}:$id",
            name = name,
            sportName = feed.sportLabel,
            leagueId = leagueId,
            leagueName = leagueName,
            homeTeamId = null,
            // The race and the series stand for the two "teams": what the TV guide names.
            homeTeamName = name,
            homeTeamBadgeUrl = leagueBadge,
            awayTeamId = null,
            awayTeamName = series,
            awayTeamBadgeUrl = null,
            homeScore = null,
            awayScore = null,
            startTimeMs = start,
            venue = listOfNotNull(circuitName, place?.ifBlank { null }).joinToString(", ").ifBlank { null },
            status = status,
            statusDetail = if (status == SportsEventStatus.FINISHED) "Final" else detail.takeIf { status == SportsEventStatus.LIVE },
            homeTeamAliases = listOfNotNull(raw.str("shortName")?.takeIf { it != name }, circuitName).distinct(),
            awayTeamAliases = listOfNotNull(leagueName.takeIf { it != series }) + RACING_ALIASES[feed.leaguePath].orEmpty(),
            leaderName = winner?.optJSONObject("athlete")?.let { it.str("displayName") ?: it.str("fullName") }
        )
    }

    /** The weekend's race (not its practices or qualifying). */
    private fun raceCompetitionOf(raw: JSONObject): JSONObject? {
        val array = raw.optJSONArray("competitions") ?: return null
        val competitions = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        return competitions.lastOrNull { it.optJSONObject("type")?.str("abbreviation").equals("Race", ignoreCase = true) }
            ?: competitions.lastOrNull()
    }

    private fun raceCompetitorsOf(race: JSONObject): List<JSONObject> {
        val array = race.optJSONArray("competitors") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    /** An ESPN race's classification: the drivers in the order they finished (or are running). */
    private fun espnRaceResults(leaguePath: String, rawEventId: String, startTimeMs: Long?): List<SportsRaceResult> {
        val base = "$BASE_URL/racing/$leaguePath/scoreboard"
        val urls = buildList {
            if (startTimeMs != null) {
                val dayMs = 24L * 60 * 60 * 1000
                add("$base?dates=${dayFormat.format(java.util.Date(startTimeMs - 4 * dayMs))}-${dayFormat.format(java.util.Date(startTimeMs + dayMs))}")
            }
            add(base)
        }
        urls.forEach { url ->
            val events = runCatching { JSONObject(get(url)).optJSONArray("events") }.getOrNull() ?: return@forEach
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                if (event.optString("id") != rawEventId) continue
                val race = raceCompetitionOf(event) ?: return emptyList()
                return raceCompetitorsOf(race)
                    .sortedBy { it.optInt("order", Int.MAX_VALUE) }
                    .mapIndexedNotNull { index, competitor ->
                        val athlete = competitor.optJSONObject("athlete") ?: return@mapIndexedNotNull null
                        SportsRaceResult(
                            position = competitor.optInt("order", index + 1),
                            driver = athlete.str("displayName") ?: athlete.str("fullName") ?: return@mapIndexedNotNull null,
                            carNumber = competitor.optJSONObject("vehicle")?.str("number").orEmpty(),
                            carBadgeUrl = athlete.optJSONObject("flag")?.str("href"),
                            team = competitor.optJSONObject("vehicle")?.str("manufacturer") ?: competitor.optJSONObject("team")?.str("displayName"),
                            laps = null,
                            lapsLed = null,
                            status = null,
                            points = null
                        )
                    }
            }
        }
        return emptyList()
    }

    // ------------------------------------------------------------------ golf

    /** One player on a golf tournament's leaderboard. */
    data class GolfPlayer(
        val id: String,
        /** "1", "T3", "CUT". */
        val position: String,
        val name: String,
        val flagUrl: String?,
        val headshotUrl: String?,
        /** To par: "-12", "E", "+3". */
        val toPar: String,
        /** Holes played today ("12", "F"), when the round is on. */
        val thru: String?,
        /** Each round's strokes ("68"). */
        val rounds: List<String>
    )

    /**
     * A tournament as an event: its name (and the tour's, to find it in the TV guide), its days,
     * where it's at (the round, or Final) and its leader. Tournaments with no status from ESPN are
     * placed by their days: on during them, over after.
     */
    private fun golfEventOf(feed: Feed, raw: JSONObject, leagueId: String, leagueName: String, leagueBadge: String?): SportsEvent? {
        val id = raw.optString("id").takeIf { it.isNotBlank() } ?: return null
        val name = raw.optString("name").takeIf { it.isNotBlank() }
            ?: raw.optString("shortName").takeIf { it.isNotBlank() }
            ?: return null
        val competition = raw.optJSONArray("competitions")?.optJSONObject(0)
        val start = parseStartTimeMs(raw.optString("date"))
        val end = parseStartTimeMs(raw.optString("endDate"))
        val players = golfPlayersOf(competition)
        val statusType = raw.optJSONObject("status")?.optJSONObject("type")
            ?: competition?.optJSONObject("status")?.optJSONObject("type")
        val (status, detail) = if (statusType != null && statusType.optString("state").isNotBlank()) {
            statusFrom(statusType)
        } else {
            golfStatusByDays(start, end, players)
        }
        val leader = players.firstOrNull()?.takeIf { status != SportsEventStatus.UPCOMING }
        val venue = competition?.optJSONObject("venue")?.optString("fullName")?.takeIf { it.isNotBlank() }
            ?: raw.optJSONArray("courses")?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() }
        val tourNames = listOfNotNull(FEED_LABELS[feed.leaguePath], leagueName).distinct()
        return SportsEvent(
            id = "espn:${feed.sportPath}:${feed.leaguePath}:$id",
            name = name,
            sportName = feed.sportLabel,
            leagueId = leagueId,
            leagueName = leagueName,
            homeTeamId = null,
            // The tournament and the tour stand for the two "teams": what the TV guide names.
            homeTeamName = name,
            homeTeamBadgeUrl = leagueBadge,
            awayTeamId = null,
            awayTeamName = tourNames.first(),
            awayTeamBadgeUrl = null,
            homeScore = null,
            awayScore = null,
            startTimeMs = start,
            venue = venue,
            status = status,
            statusDetail = detail,
            homeTeamAliases = listOfNotNull(raw.optString("shortName").takeIf { it.isNotBlank() && it != name }),
            awayTeamAliases = tourNames.drop(1),
            leaderName = leader?.name,
            leaderScore = leader?.toPar,
            endTimeMs = end
        )
    }

    /** No status from ESPN: before its first day upcoming, after its last day over, on in between. */
    private fun golfStatusByDays(start: Long?, end: Long?, players: List<GolfPlayer>): Pair<SportsEventStatus, String?> {
        val now = System.currentTimeMillis()
        val dayMs = 24L * 60 * 60 * 1000
        if (start == null || now < start) return SportsEventStatus.UPCOMING to null
        val over = (end ?: (start + 3 * dayMs)) + dayMs
        if (now > over) return SportsEventStatus.FINISHED to "Final"
        val round = players.maxOfOrNull { it.rounds.size }?.takeIf { it > 0 }
        return SportsEventStatus.LIVE to round?.let { "Round $it" }
    }

    /** The leaderboard in an ESPN golf competition, in order (ties as "T3"). */
    private fun golfPlayersOf(competition: JSONObject?): List<GolfPlayer> {
        val competitors = competition?.optJSONArray("competitors") ?: return emptyList()
        data class Row(val order: Int, val player: GolfPlayer, val explicitPosition: Boolean)
        val rows = ArrayList<Row>(competitors.length())
        for (i in 0 until competitors.length()) {
            val competitor = competitors.optJSONObject(i) ?: continue
            val athlete = competitor.optJSONObject("athlete")
            val name = athlete?.optString("displayName")?.takeIf { it.isNotBlank() }
                ?: athlete?.optString("fullName")?.takeIf { it.isNotBlank() }
                ?: continue
            val score = competitor.opt("score")
            val toPar = when (score) {
                is JSONObject -> score.optString("displayValue")
                null, JSONObject.NULL -> ""
                else -> score.toString()
            }.ifBlank { "-" }
            val status = competitor.optJSONObject("status")
            val position = status?.optJSONObject("position")?.optString("displayName")?.takeIf { it.isNotBlank() }
            val thru = status?.let { s ->
                s.optString("displayThru").takeIf { it.isNotBlank() }
                    ?: s.opt("thru")?.toString()?.takeIf { it.isNotBlank() && it != "0" && it != "null" }
            }
            val linescores = competitor.optJSONArray("linescores")
            val rounds = if (linescores == null) emptyList() else (0 until linescores.length()).mapNotNull { index ->
                val round = linescores.optJSONObject(index) ?: return@mapNotNull null
                round.optDouble("value").takeIf { !it.isNaN() && it > 0 }?.toInt()?.toString()
            }
            rows += Row(
                order = competitor.optInt("order", i + 1),
                player = GolfPlayer(
                    id = competitor.optString("id"),
                    position = position.orEmpty(),
                    name = name,
                    flagUrl = athlete?.optJSONObject("flag")?.optString("href")?.takeIf { it.isNotBlank() },
                    headshotUrl = athlete?.optJSONObject("headshot")?.optString("href")?.takeIf { it.isNotBlank() },
                    toPar = toPar,
                    thru = thru,
                    rounds = rounds
                ),
                explicitPosition = position != null
            )
        }
        val sorted = rows.sortedBy { it.order }
        // ESPN gives the order; the position (ties) from the scores when it doesn't say.
        return sorted.mapIndexed { index, row ->
            if (row.explicitPosition) return@mapIndexed row.player
            val firstWithScore = sorted.indexOfFirst { it.player.toPar == row.player.toPar }
            val tied = sorted.count { it.player.toPar == row.player.toPar } > 1
            val place = (if (firstWithScore >= 0) firstWithScore else index) + 1
            row.player.copy(position = if (tied && row.player.toPar != "-") "T$place" else "$place")
        }
    }

    /**
     * A golf tournament's leaderboard: its event in the tour's scoreboard of its days
     * ([startTimeMs] places them; null: the current week's).
     */
    fun fetchGolfLeaderboard(leaguePath: String, rawEventId: String, startTimeMs: Long?): List<GolfPlayer> {
        val base = "$BASE_URL/$GOLF/$leaguePath/scoreboard"
        val urls = buildList {
            if (startTimeMs != null) {
                val format = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                val dayMs = 24L * 60 * 60 * 1000
                add("$base?dates=${format.format(java.util.Date(startTimeMs))}-${format.format(java.util.Date(startTimeMs + 4 * dayMs))}")
            }
            add(base)
        }
        urls.forEach { url ->
            val events = runCatching { JSONObject(get(url)).optJSONArray("events") }.getOrNull() ?: return@forEach
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                if (event.optString("id") != rawEventId) continue
                return golfPlayersOf(event.optJSONArray("competitions")?.optJSONObject(0))
            }
        }
        return emptyList()
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw EspnException("HTTP ${response.code}")
            return boundedBody(response)
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
    fun fetchFeed(feed: Feed, datesRange: String? = null): FeedResult =
        fetchFeedRaw(feed, datesRange).deduplicated()

    /**
     * A game or a team a source lists twice (a postponed game on its old and new days) is one:
     * the pages key their cards by id, and a repeated one would bring them down.
     */
    private fun FeedResult.deduplicated(): FeedResult = copy(
        events = events.distinctBy { it.id },
        teams = teams.distinctBy { it.id }
    )

    /** [limit]: at most this many of ESPN's games (the rows need the next ones, not hundreds). */
    private fun fetchFeedRaw(feed: Feed, datesRange: String?, limit: Int? = null): FeedResult {
        if (feed.isBananaBall()) return bananaBall.fetchFeed(feed, datesRange)
        if (feed.isNascar()) return nascar.fetchFeed(feed, datesRange)
        if (feed.isPpv()) return ppv.fetchFeed(feed, datesRange)
        if (feed.isCfl()) return cfl.fetchFeed(feed, datesRange)
        if (feed.isMlbStats()) return mlbStats.fetchFeed(feed, datesRange)
        if (feed.isMotoGp()) return motoGp.fetchFeed(feed, datesRange)
        feed.virtual()?.let { return virtualFeed(feed, it, datesRange) }
        val base = "$BASE_URL/${feed.sportPath}/${feed.leaguePath}/scoreboard"
        // A long range (a league's next or last games, months away): all of it, not ESPN's first page.
        val url = when {
            datesRange == null -> base
            rangeDays(datesRange) > 31 -> "$base?dates=$datesRange&limit=500"
            limit != null -> "$base?dates=$datesRange&limit=$limit"
            else -> "$base?dates=$datesRange"
        }
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
            if (feed.sportPath == GOLF) {
                golfEventOf(feed, raw, leagueId, leagueName, leagueBadge)?.let { events += it }
                continue
            }
            // Tennis: a tournament's matches (each one an event, player against player).
            if (feed.sportPath == VirtualLeagues.TENNIS) {
                events += tennisMatchesOf(feed, raw, leagueId, leagueName)
                continue
            }
            // A race (Formula 1, IndyCar): one event, its winner once it's run.
            if (feed.sportPath == "racing") {
                raceEventOf(feed, raw, leagueId, leagueName, leagueBadge)?.let { events += it }
                continue
            }
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
    fun fetchAllFixtures(
        rangeDays: Int = 10,
        feeds: List<Feed> = FEEDS,
        /** Leagues ("sport:league") loaded first: the person's own. */
        firstLeagueIds: Set<String> = emptySet(),
        /** Each league's games as soon as they're in (on the loading threads). */
        onResult: ((FeedResult) -> Unit)? = null
    ): List<FeedResult> {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val start = dayFormat.format(calendar.time)
        calendar.add(Calendar.DAY_OF_YEAR, rangeDays)
        val end = dayFormat.format(calendar.time)
        return fetchFixturesInRange("$start-$end", byPriority(feeds, firstLeagueIds), onResult)
    }

    /**
     * The person's leagues first, then the big ones, then the rest in their order; the heavy ones
     * (every match of a tennis tour, hundreds of college games) last.
     */
    private fun byPriority(feeds: List<Feed>, firstLeagueIds: Set<String>): List<Feed> =
        feeds.withIndex().sortedWith(compareBy({ (_, feed) ->
            when {
                "${feed.sportPath}:${feed.leaguePath}" in firstLeagueIds -> 0
                feed.leaguePath in HEADLINE_LEAGUE_PATHS -> 1
                feed.isHeavy() -> 3
                else -> 2
            }
        }, { it.index })).map { it.value }

    /** A league whose scoreboard is megabytes (every match of a tennis tour, a college season's weekend). */
    private fun Feed.isHeavy(): Boolean = sportPath == VirtualLeagues.TENNIS || leaguePath in COLLEGE_LEAGUE_PATHS

    /**
     * Every curated league's games the day before, the day of and the day after [timeMs]: to find
     * which game a TV guide program is.
     */
    fun fetchFixturesAround(timeMs: Long, feeds: List<Feed> = FEEDS): List<FeedResult> {
        val dayMs = 24L * 60 * 60 * 1000
        val format = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val start = format.format(java.util.Date(timeMs - dayMs))
        val end = format.format(java.util.Date(timeMs + dayMs))
        return fetchFixturesInRange("$start-$end", feeds)
    }

    private fun fetchFixturesInRange(
        range: String,
        feeds: List<Feed>,
        onResult: ((FeedResult) -> Unit)? = null
    ): List<FeedResult> {
        // Tennis lists every match of every tournament it's asked about (megabytes a tour): its
        // next couple of days are what the rows show.
        val tennisRange = shortRange(range, TENNIS_RANGE_DAYS)
        // A few leagues at once (one after another took many seconds), in the order given; never
        // all of them at once: every league's whole answer read at the same time took more memory
        // than a TV has, and the system closed the app. On threads of their own, too: seventy
        // leagues waiting on the network took every thread the rest of the app reads and saves
        // with (the Guide, the settings), and the app stalled. The heavy leagues one at a time.
        val permits = kotlinx.coroutines.sync.Semaphore(MAX_FEEDS_AT_ONCE)
        val heavyPermit = kotlinx.coroutines.sync.Semaphore(1)
        return kotlinx.coroutines.runBlocking(feedDispatcher) {
            feeds.map { feed ->
                async {
                    // A league of another's events (a Grand Slam, a boxing body): only its tile, its
                    // games are its tour's.
                    val result = if (feed.isSubLeague()) {
                        runCatching { FeedResult(feed, subLeagueOf(feed), emptyList(), emptyList()) }.getOrNull()
                    } else {
                        val feedRange = if (feed.sportPath == VirtualLeagues.TENNIS) tennisRange else range
                        val load: suspend () -> FeedResult? = {
                            permits.withPermit {
                                runCatching { fetchFeedRaw(feed, feedRange, limit = ROWS_EVENT_LIMIT).deduplicated() }
                                    .recoverCatching { fetchFeed(feed) }
                                    .getOrNull()
                            }
                        }
                        if (feed.isHeavy()) heavyPermit.withPermit { load() } else load()
                    }
                    if (result != null && onResult != null) runCatching { onResult(result) }
                    result
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** "yyyyMMdd-yyyyMMdd" cut to its first [days] days. */
    private fun shortRange(range: String, days: Int): String {
        val start = range.substringBefore('-')
        val startMs = runCatching { dayFormat.parse(start)?.time }.getOrNull() ?: return range
        val end = dayFormat.format(java.util.Date(startMs + days * 24L * 60 * 60 * 1000))
        return if (end < range.substringAfterLast('-')) "$start-$end" else range
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
        if (feed.isMlbStats()) return mlbStats.standings(feed)
        // Banana Ball's site has no standings to read; nor have NASCAR's or MotoGP's feeds read here,
        // PPV Events, the CFL's free data, tennis' tours or a single tournament.
        if (feed.isBananaBall() || feed.isNascar() || feed.isPpv() || feed.isCfl() || feed.isMotoGp() ||
            feed.virtual() != null || feed.sportPath == VirtualLeagues.TENNIS
        ) return emptyList()
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
        if (feed.isBananaBall() || feed.isPpv() || feed.isMlbStats() || feed.isMotoGp()) return emptyList()
        // A Grand Slam's or a major's news: its tour's.
        feed.virtual()?.let { virtual ->
            val parent = FEEDS.firstOrNull { it.sportPath == virtual.sportPath && it.leaguePath == virtual.parents.first() } ?: return emptyList()
            return fetchNews(parent, limit)
        }
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
        if (feed.isCfl()) return cfl.teams()
        if (feed.isMlbStats()) return mlbStats.teams(feed)
        if (!hasTeams(feed)) return emptyList()
        if (feed.isBananaBall()) return bananaBall.teams()
        // Every team (the NCAA's leagues have hundreds).
        val url = "$BASE_URL/${feed.sportPath}/${feed.leaguePath}/teams?limit=1000"
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

    /** Some of a league's teams under one name: a division or a conference ("NCAA Division II"); null for the rest. */
    data class TeamGroup(val label: String?, val teams: List<SportsTeam>)

    /**
     * A college league's teams, every one once: by division or conference where ESPN groups them
     * ("FBS (I-A) · SEC", "NCAA Division II", "NCAA Division III"), the rest (A to Z) first.
     */
    fun fetchTeamGroups(feed: Feed): List<TeamGroup> {
        if (feed.isCfl() || feed.isMlbStats()) return listOf(TeamGroup(null, fetchTeams(feed)))
        if (!hasTeams(feed)) return emptyList()
        if (feed.isBananaBall()) return listOf(TeamGroup(null, bananaBall.teams()))
        val all = runCatching { fetchTeams(feed) }.getOrDefault(emptyList())
        val colors = all.associate { it.id to it.color }
        val leagueId = "${feed.sportPath}:${feed.leaguePath}"
        val groups = ArrayList<TeamGroup>()
        val grouped = HashSet<String>()
        runCatching {
            val json = JSONObject(get("$BASE_URL/${feed.sportPath}/${feed.leaguePath}/groups"))
            fun walk(node: JSONObject, path: List<String>) {
                val here = node.str("name")?.let { path + it } ?: path
                val teamsJson = node.optJSONArray("teams")
                if (teamsJson != null && teamsJson.length() > 0) {
                    val teams = ArrayList<SportsTeam>()
                    for (i in 0 until teamsJson.length()) {
                        val teamJson = teamsJson.optJSONObject(i) ?: continue
                        val id = teamJson.str("id") ?: continue
                        if (!grouped.add(id)) continue
                        teams += SportsTeam(
                            id = id,
                            name = teamJson.str("displayName") ?: teamJson.str("name") ?: continue,
                            shortName = teamJson.str("abbreviation"),
                            sportName = feed.sportLabel,
                            leagueId = leagueId,
                            leagueName = feed.sportLabel,
                            badgeUrl = logoOf(teamJson),
                            color = teamJson.str("color")?.removePrefix("#")?.takeIf { it.length == 6 } ?: colors[id]
                        )
                    }
                    if (teams.isNotEmpty()) groups += TeamGroup(groupLabelOf(here), teams.sortedBy { it.name })
                }
                val children = node.optJSONArray("children") ?: return
                for (i in 0 until children.length()) walk(children.optJSONObject(i) ?: continue, here)
            }
            val roots = json.optJSONArray("groups") ?: JSONArray()
            for (i in 0 until roots.length()) walk(roots.optJSONObject(i) ?: continue, emptyList())
        }
        val rest = all.filter { it.id !in grouped }.sortedBy { it.name }
        return listOfNotNull(TeamGroup(null, rest).takeIf { rest.isNotEmpty() }) + groups
    }

    /** "NCAA Division I > FBS (I-A) > SEC" → "FBS (I-A) · SEC"; "Division II/III > NCAA Division II" → "NCAA Division II". */
    private fun groupLabelOf(path: List<String>): String? {
        val names = path.filterNot { '/' in it }
        return when {
            names.isEmpty() -> path.lastOrNull()
            names.size == 1 -> names[0]
            else -> "${names[names.size - 2]} · ${names.last()}"
        }
    }

    /**
     * Resolves the Sports home page's fixed "Teams" row picks (see [HOME_TEAM_ROW_PICKS]) against
     * each pick's league roster, in the same order the picks are declared. A pick whose team can't be
     * found (a league roster fetch failed, or ESPN renamed the team) is simply skipped rather than
     * breaking the whole row.
     */
    fun fetchHomeTeamRowPicks(): List<SportsTeam> {
        // Each league's teams once, all at once.
        val feeds = HOME_TEAM_ROW_PICKS.map { it.feed }.distinct()
        val rosters = kotlinx.coroutines.runBlocking(feedDispatcher) {
            feeds.map { feed -> async { feed to runCatching { fetchTeams(feed) }.getOrDefault(emptyList()) } }
                .awaitAll()
                .toMap()
        }
        return HOME_TEAM_ROW_PICKS.mapNotNull { pick ->
            rosters[pick.feed].orEmpty().firstOrNull { it.name.contains(pick.teamNameContains, ignoreCase = true) }
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
        val awayTeamAliases: List<String>,
        /** The game's week (NFL, college football), when the league plays in weeks. */
        val week: Int? = null,
        /** ESPN's season type: 1 preseason, 2 regular season, 3 postseason. */
        val seasonType: Int? = null
    )

    fun fetchGameSummary(sportPath: String, leaguePath: String, eventId: String): GameSummary {
        // A golf tournament has a leaderboard ([fetchGolfLeaderboard]), not team stats.
        if (sportPath == GOLF) return GameSummary(emptyList(), emptyList(), emptyList())
        if (leaguePath == BananaBallSource.LEAGUE_PATH) return GameSummary(emptyList(), emptyList(), emptyList())
        // LIDOM, the Caribbean Series, the Minor Leagues: the line score (runs by inning, R H E).
        if (sportPath == MlbStatsSource.SPORT_PATH && leaguePath in MlbStatsSource.LEAGUE_PATHS) {
            return GameSummary(runCatching { mlbStats.lineScore(eventId) }.getOrDefault(emptyList()), emptyList(), emptyList())
        }
        // A race has its results ([fetchRaceResults]); a PPV show its card ([fetchShowDetails]); the
        // CFL's free data and tennis have no box score to read.
        if (sportPath == "racing" || sportPath == PpvEventsSource.SPORT_PATH || sportPath == VirtualLeagues.TENNIS ||
            (sportPath == CflSource.SPORT_PATH && leaguePath == CflSource.LEAGUE_PATH)
        ) {
            return GameSummary(emptyList(), emptyList(), emptyList())
        }
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
        val header = json.optJSONObject("header")
        return GameSummary(
            stats = gameStatsOf(json),
            homeTeamAliases = homeAliases,
            awayTeamAliases = awayAliases,
            week = header?.optInt("week", 0)?.takeIf { it > 0 },
            seasonType = header?.optJSONObject("season")?.optInt("type", 0)?.takeIf { it > 0 }
        )
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
        "nfl", "nba", "wnba", "mlb", "nhl", "college-football", "mens-college-basketball" ->
            "https://a.espncdn.com/i/headshots/${feed.leaguePath}/players/full/$athleteId.png"
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
        if (feed.isBananaBall()) return bananaBall.teamProfile(teamId)
        if (feed.isCfl()) return cfl.teamProfile(teamId)
        if (feed.isMlbStats()) return mlbStats.teamProfile(feed, teamId)
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
        if (feed.isBananaBall()) return bananaBall.teamSchedule(teamId)
        if (feed.isCfl()) return cfl.teamSchedule(teamId)
        if (feed.isMlbStats()) return mlbStats.teamSchedule(feed, teamId)
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
        // Banana Ball's rosters are set by its draft: none on its site to read. Nor has the CFL's free data.
        if (feed.isBananaBall() || feed.isCfl()) return emptyList()
        if (feed.isMlbStats()) return mlbStats.roster(feed, teamId)
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

    /** A team's stats: which season they're for ("2025-26 Regular Season"), and the groups of stats. */
    data class TeamStats(val seasonLabel: String?, val categories: List<SportsTeamStatCategory>)

    /**
     * A team's season stats, by group ("Passing", "Rushing"...): the regular season on now (ESPN
     * gives the last one until it's asked for this one); before it starts, the last one, said so.
     * Soccer (ESPN has no team stats for it): the team's numbers in the league table (played, won,
     * drawn, lost, goals, points).
     */
    fun fetchTeamStats(feed: Feed, teamId: String): TeamStats {
        if (feed.isBananaBall() || feed.isCfl() || feed.isMlbStats()) return TeamStats(seasonLabel = null, categories = emptyList())
        val base = "$BASE_URL/${feed.sportPath}/${feed.leaguePath}/teams/$teamId/statistics"
        val json = JSONObject(get(base))
        var categories = statCategoriesOf(json)
        val current = json.optJSONObject("season")
        val requested = json.optJSONObject("requestedSeason")
        var label = seasonLabelOf(requested ?: current)
        // The regular season on now, when ESPN gave an older one.
        val currentYear = current?.optInt("year", 0) ?: 0
        val currentType = current?.optInt("type", 0) ?: 0
        val sameAsRequested = requested != null &&
            requested.optInt("year", 0) == currentYear && requested.optInt("type", 0) == currentType
        if (currentYear > 0 && currentType == 2 && !sameAsRequested) {
            val currentJson = runCatching { JSONObject(get("$base?season=$currentYear&seasontype=2")) }.getOrNull()
            val currentCategories = currentJson?.let { statCategoriesOf(it) }.orEmpty()
            if (hasGamesPlayed(currentCategories)) {
                categories = currentCategories
                label = seasonLabelOf(current)
            }
        }
        if (categories.isEmpty()) {
            standingStatsOf(feed, teamId)?.let { (tableName, stats) ->
                categories = listOf(stats)
                label = tableName
            }
        }
        return TeamStats(seasonLabel = label, categories = categories)
    }

    /** The stat groups of a team statistics response, every stat of each. */
    private fun statCategoriesOf(json: JSONObject): List<SportsTeamStatCategory> {
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

    /** Some games are in these stats (a season just started has none yet). */
    private fun hasGamesPlayed(categories: List<SportsTeamStatCategory>): Boolean {
        if (categories.isEmpty()) return false
        val played = categories.flatMap { it.stats }.firstOrNull { it.label.equals("Games Played", ignoreCase = true) }
            ?: return true
        return (played.value.replace(",", "").toDoubleOrNull() ?: 0.0) > 0.0
    }

    /** "2025-26 Regular Season" from ESPN's season JSON; "2026-27 English Premier League" for soccer. */
    private fun seasonLabelOf(season: JSONObject?): String? {
        season ?: return null
        val display = season.str("displayName")
        val name = season.str("name")
        return when {
            display != null && name != null && !display.contains(name, ignoreCase = true) -> "$display $name"
            display != null -> display
            else -> name
        }
    }

    /**
     * A team's numbers in its league table ("2026-27 English Premier League": Rank 19, Games Played
     * 5, Wins 0, Draws 2...), from ESPN's standings; null when the team isn't in them.
     */
    private fun standingStatsOf(feed: Feed, teamId: String): Pair<String, SportsTeamStatCategory>? {
        val json = runCatching { JSONObject(get("$STANDINGS_BASE_URL/${feed.sportPath}/${feed.leaguePath}/standings")) }.getOrNull()
            ?: return null
        fun find(group: JSONObject, groupName: String?): Pair<String, SportsTeamStatCategory>? {
            val name = group.str("name") ?: groupName
            val entries = group.optJSONObject("standings")?.optJSONArray("entries")
            if (entries != null) {
                for (i in 0 until entries.length()) {
                    val entry = entries.optJSONObject(i) ?: continue
                    if (entry.optJSONObject("team")?.str("id") != teamId) continue
                    val statsJson = entry.optJSONArray("stats") ?: return null
                    val stats = ArrayList<SportsStandingStat>()
                    var rank: SportsStandingStat? = null
                    for (j in 0 until statsJson.length()) {
                        val stat = statsJson.optJSONObject(j) ?: continue
                        val key = stat.str("name") ?: stat.str("type")
                        if (key in SKIPPED_TABLE_STATS) continue
                        val value = stat.str("displayValue") ?: stat.str("summary") ?: continue
                        val label = stat.str("displayName") ?: stat.str("shortDisplayName") ?: key ?: continue
                        val item = SportsStandingStat(label = label, value = value)
                        if (key == "rank") rank = item else stats += item
                    }
                    if (stats.isEmpty()) return null
                    val title = name ?: feed.sportLabel
                    return title to SportsTeamStatCategory(name = title, stats = listOfNotNull(rank) + stats)
                }
            }
            val children = group.optJSONArray("children") ?: return null
            for (i in 0 until children.length()) {
                find(children.optJSONObject(i) ?: continue, name)?.let { return it }
            }
            return null
        }
        return find(json, null)
    }

    /** A team's ESPN news. */
    fun fetchTeamNews(feed: Feed, teamId: String, limit: Int = 24): List<SportsNewsArticle> {
        if (feed.isBananaBall() || feed.isCfl() || feed.isMlbStats()) return emptyList()
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
        if (feed.isBananaBall() || feed.isCfl() || feed.isMlbStats() || !hasTeams(feed)) return emptyMap()
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
