package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.core.util.runCatchingCancellable
import android.content.Context
import android.util.Log
import com.nuvio.tv.data.remote.espn.EspnSportsClient
import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.SportsLeague
import com.nuvio.tv.domain.model.SportsTeam
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SportsSearchIndex"

/**
 * RCTV: the sports Search looks through: every league the app follows, every team of the leagues
 * with teams (the NCAA's too), and the games from yesterday to a week ahead. Loaded the first time
 * something is searched, in the background, and kept while the app runs: the teams for a few days
 * (also on the device, so after a restart they're there at once), the games for a few minutes.
 */
@Singleton
class SportsSearchIndex @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: EspnSportsClient
) {
    data class Snapshot(
        val leagues: List<SearchableLeague> = emptyList(),
        val teams: List<SearchTeamResult> = emptyList(),
        val games: List<SearchableGame> = emptyList()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val file by lazy { File(context.filesDir, FILE_NAME) }

    private val _state = MutableStateFlow(Snapshot(leagues = baseLeagues(emptyMap())))
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** The leagues' logos and full names as ESPN gives them, by league id. */
    private val leagueDetails = HashMap<String, SportsLeague>()

    private var teamsJob: Job? = null
    private var gamesJob: Job? = null
    @Volatile private var teamsLoadedAtMs = 0L
    @Volatile private var gamesLoadedAtMs = 0L
    private val fileLock = Any()

    /** Loads what's missing or old (in the background); nothing when it's all there. */
    fun ensure() {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (teamsJob?.isActive != true && now - teamsLoadedAtMs > TEAMS_MAX_AGE_MS) {
                teamsJob = scope.launch { loadTeams() }
            }
            if (gamesJob?.isActive != true && now - gamesLoadedAtMs > GAMES_MAX_AGE_MS) {
                gamesJob = scope.launch { loadGames() }
            }
        }
    }

    // ------------------------------------------------------------------ Leagues

    /** Every league the app follows, under the name it shows ("NBA"), with ESPN's logo once known. */
    private fun baseLeagues(details: Map<String, SportsLeague>): List<SearchableLeague> =
        EspnSportsClient.FEEDS.map { feed ->
            val id = "${feed.sportPath}:${feed.leaguePath}"
            val known = details[id]
            val label = EspnSportsClient.feedLabel(feed.leaguePath) ?: known?.name ?: feed.leaguePath
            SearchableLeague(
                league = SportsLeague(
                    id = id,
                    name = label,
                    sportName = feed.sportLabel,
                    badgeUrl = known?.badgeUrl,
                    country = known?.country
                ),
                names = (listOfNotNull(label, known?.name, feed.leaguePath.replace('.', ' ').replace('-', ' ')) +
                    LEAGUE_NICKNAMES[feed.leaguePath].orEmpty()).distinct()
            )
        }.distinctBy { it.league.id }

    private fun publishLeagues() {
        val leagues = synchronized(leagueDetails) { baseLeagues(HashMap(leagueDetails)) }
        _state.update { it.copy(leagues = leagues) }
    }

    // ------------------------------------------------------------------ Teams

    private suspend fun loadTeams() {
        val saved = runCatching { readSaved() }.getOrNull()
        if (saved != null) {
            synchronized(leagueDetails) { leagueDetails.putAll(saved.leagues.associateBy { it.id }) }
            publishLeagues()
            _state.update { it.copy(teams = saved.teams) }
            if (System.currentTimeMillis() - saved.savedAtMs < TEAMS_MAX_AGE_MS) {
                teamsLoadedAtMs = saved.savedAtMs
                return
            }
        }
        val gate = Semaphore(TEAMS_FEEDS_AT_ONCE)
        val loaded = try {
            coroutineScope {
                EspnSportsClient.TEAMS_FILTERS.map { chip ->
                    async {
                        gate.withPermit {
                            val feed = chip.feed
                            val leagueId = "${feed.sportPath}:${feed.leaguePath}"
                            runCatching { client.fetchTeams(feed) }
                                .onFailure { Log.w(TAG, "Teams of $leagueId: ${it.message}") }
                                .getOrDefault(emptyList())
                                .filter { it.name.isNotBlank() }
                                .map { team ->
                                    SearchTeamResult(
                                        team = team.copy(
                                            leagueId = team.leagueId?.takeIf { it.contains(':') } ?: leagueId
                                        ),
                                        leagueLabel = chip.label
                                    )
                                }
                        }
                    }
                }.awaitAll().flatten()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        if (loaded.isEmpty()) {
            // Couldn't load: tried again on the next search a few minutes from now.
            teamsLoadedAtMs = System.currentTimeMillis() - TEAMS_MAX_AGE_MS + RETRY_AFTER_MS
            return
        }
        teamsLoadedAtMs = System.currentTimeMillis()
        _state.update { it.copy(teams = loaded) }
        runCatching { save(loaded) }.onFailure { Log.w(TAG, "Couldn't keep the teams: ${it.message}") }
    }

    // ------------------------------------------------------------------ Games

    private suspend fun loadGames() {
        val games = LinkedHashMap<String, SearchableGame>()
        var lastPublishMs = 0L
        val results = runCatchingCancellable {
            client.fetchAllFixtures(rangeDays = GAMES_DAYS_AHEAD, daysBack = 1, onResult = { result ->
                synchronized(games) {
                    result.events.forEach { event -> if (!games.containsKey(event.id)) games[event.id] = SearchableGame(event) }
                }
                synchronized(leagueDetails) { leagueDetails[result.league.id] = result.league }
                // The leagues as they come in (a few at a time: not once per league).
                val now = System.currentTimeMillis()
                if (now - lastPublishMs > PUBLISH_EVERY_MS) {
                    lastPublishMs = now
                    val soFar = synchronized(games) { games.values.toList() }
                    _state.update { it.copy(games = soFar) }
                    publishLeagues()
                }
            })
        }.getOrElse {
            Log.w(TAG, "Games: ${it.message}")
            emptyList()
        }
        if (results.isEmpty() && games.isEmpty()) {
            gamesLoadedAtMs = System.currentTimeMillis() - GAMES_MAX_AGE_MS + RETRY_AFTER_MS
            return
        }
        gamesLoadedAtMs = System.currentTimeMillis()
        val all = synchronized(games) { games.values.toList() }
        _state.update { it.copy(games = all) }
        publishLeagues()
        // The leagues' logos, kept with the teams for next time.
        runCatching { _state.value.teams.takeIf { it.isNotEmpty() }?.let { save(it) } }
    }

    // ------------------------------------------------------------------ On the device

    private class Saved(val savedAtMs: Long, val teams: List<SearchTeamResult>, val leagues: List<SportsLeague>)

    private fun save(teams: List<SearchTeamResult>) = synchronized(fileLock) {
        val json = JSONObject()
        json.put("v", FILE_VERSION)
        json.put("savedAt", if (teamsLoadedAtMs > 0) teamsLoadedAtMs else System.currentTimeMillis())
        json.put("teams", JSONArray().apply {
            teams.forEach { entry ->
                val team = entry.team
                put(JSONObject().apply {
                    put("id", team.id)
                    put("name", team.name)
                    team.shortName?.let { put("short", it) }
                    team.sportName?.let { put("sport", it) }
                    team.leagueId?.let { put("league", it) }
                    team.leagueName?.let { put("leagueName", it) }
                    team.badgeUrl?.let { put("badge", it) }
                    team.color?.let { put("color", it) }
                    entry.leagueLabel?.let { put("label", it) }
                })
            }
        })
        json.put("leagues", JSONArray().apply {
            synchronized(leagueDetails) { leagueDetails.values.toList() }.forEach { league ->
                put(JSONObject().apply {
                    put("id", league.id)
                    put("name", league.name)
                    put("sport", league.sportName)
                    league.badgeUrl?.let { put("badge", it) }
                })
            }
        })
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun readSaved(): Saved? = synchronized(fileLock) { readSavedLocked() }

    private fun readSavedLocked(): Saved? {
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        if (json.optInt("v") != FILE_VERSION) return null
        fun JSONObject.str(key: String): String? = optString(key).takeIf { has(key) && it.isNotBlank() && it != "null" }
        val teamsJson = json.optJSONArray("teams") ?: return null
        val teams = (0 until teamsJson.length()).mapNotNull { i ->
            val item = teamsJson.optJSONObject(i) ?: return@mapNotNull null
            val id = item.str("id") ?: return@mapNotNull null
            val name = item.str("name") ?: return@mapNotNull null
            SearchTeamResult(
                team = SportsTeam(
                    id = id,
                    name = name,
                    shortName = item.str("short"),
                    sportName = item.str("sport"),
                    leagueId = item.str("league"),
                    leagueName = item.str("leagueName"),
                    badgeUrl = item.str("badge"),
                    color = item.str("color")
                ),
                leagueLabel = item.str("label")
            )
        }
        val leaguesJson = json.optJSONArray("leagues") ?: JSONArray()
        val leagues = (0 until leaguesJson.length()).mapNotNull { i ->
            val item = leaguesJson.optJSONObject(i) ?: return@mapNotNull null
            SportsLeague(
                id = item.str("id") ?: return@mapNotNull null,
                name = item.str("name") ?: return@mapNotNull null,
                sportName = item.str("sport").orEmpty(),
                badgeUrl = item.str("badge")
            )
        }
        if (teams.isEmpty()) return null
        return Saved(json.optLong("savedAt"), teams, leagues)
    }

    private companion object {
        /** Other names a league goes by, in English and Spanish ("EPL", "Grandes Ligas", "Mundial"). */
        val LEAGUE_NICKNAMES: Map<String, List<String>> = mapOf(
            "eng.1" to listOf("EPL", "English Premier League", "Liga Premier"),
            "esp.1" to listOf("LaLiga", "Liga Española", "Primera División"),
            "ita.1" to listOf("Calcio", "Liga Italiana"),
            "ger.1" to listOf("Liga Alemana"),
            "fra.1" to listOf("Ligue One", "Liga Francesa"),
            "uefa.champions" to listOf("UCL", "UEFA Champions League", "Liga de Campeones", "Champions"),
            "uefa.europa" to listOf("UEL", "UEFA Europa League"),
            "usa.1" to listOf("Major League Soccer"),
            "mex.1" to listOf("Liga BBVA MX", "Liga Mexicana"),
            "concacaf.champions" to listOf("Concachampions"),
            "fifa.world" to listOf("World Cup", "Mundial", "Copa del Mundo", "Copa Mundial"),
            "fifa.cwc" to listOf("Club World Cup", "Mundial de Clubes"),
            "nba" to listOf("National Basketball Association"),
            "wnba" to listOf("Women's National Basketball Association"),
            "nfl" to listOf("National Football League"),
            "ufl" to listOf("United Football League"),
            "mlb" to listOf("Major League Baseball", "Grandes Ligas"),
            "world-baseball-classic" to listOf("WBC", "Clásico Mundial de Béisbol"),
            "lidom" to listOf("Liga Dominicana", "Béisbol Dominicano", "Pelota Invernal"),
            "caribbean-series" to listOf("Caribbean Series"),
            "milb-aaa" to listOf("Minor League", "Ligas Menores", "AAA"),
            "milb-aa" to listOf("Minor League", "Ligas Menores", "AA"),
            "milb-high-a" to listOf("Minor League", "Ligas Menores"),
            "milb-a" to listOf("Minor League", "Ligas Menores"),
            "nhl" to listOf("National Hockey League"),
            "ufc" to listOf("Ultimate Fighting Championship"),
            "f1" to listOf("F1", "Formula One", "Fórmula 1", "Fórmula Uno"),
            "irl" to listOf("IndyCar", "Indy 500"),
            "nascar-premier" to listOf("NASCAR"),
            "nascar-secondary" to listOf("NASCAR", "Xfinity"),
            "nascar-truck" to listOf("NASCAR"),
            "college-football" to listOf("College Football", "CFB", "Fútbol Americano Universitario"),
            "mens-college-basketball" to listOf("College Basketball", "March Madness"),
            "college-baseball" to listOf("College Baseball", "College World Series")
        )

        const val FILE_NAME = "search_sports_index.json"
        const val FILE_VERSION = 1
        const val TEAMS_MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000
        const val GAMES_MAX_AGE_MS = 15L * 60 * 1000
        /** After a load that failed: tried again this long after. */
        const val RETRY_AFTER_MS = 2L * 60 * 1000
        const val GAMES_DAYS_AHEAD = 7
        const val TEAMS_FEEDS_AT_ONCE = 4
        const val PUBLISH_EVERY_MS = 3_000L
    }
}

/**
 * RCTV: the guide's titles ready for Search, for the guide loaded last (built on the first search
 * after it loads, on a background thread). Kept while the app runs.
 */
internal object GuideSearchCache {
    private class Cached(
        val channels: List<LiveTvChannel>,
        val epgByChannel: Map<String, List<EpgProgram>>,
        val index: GuideSearchIndex
    )

    @Volatile private var cached: Cached? = null

    fun indexFor(
        channels: List<LiveTvChannel>,
        epgByChannel: Map<String, List<EpgProgram>>,
        channelName: (LiveTvChannel) -> String
    ): GuideSearchIndex {
        cached?.let { if (it.channels === channels && it.epgByChannel === epgByChannel) return it.index }
        val index = GuideSearchIndex.build(channels, epgByChannel, channelName)
        cached = Cached(channels, epgByChannel, index)
        return index
    }
}
