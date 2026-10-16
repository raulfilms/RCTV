package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import com.nuvio.tv.domain.model.SportsLeague
import com.nuvio.tv.domain.model.SportsTeam
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** A sports team in the Library, and when it was added. */
data class SavedSportsTeam(val team: SportsTeam, val addedAt: Long)

/** A sports league in the Library, and when it was added. */
data class SavedSportsLeague(val league: SportsLeague, val addedAt: Long)

/**
 * The sports teams and leagues the person added to the Library, kept whole so the Library shows
 * and opens them without loading ESPN. Also the details of the games they bookmarked (the bookmark
 * itself is a "sports_event" item in [LibraryPreferences]), so a game opens from the Library with
 * its teams, badges and score.
 */
@Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SportsLibraryStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "sports_library"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    private val teamsKey = stringPreferencesKey("library_teams_json")
    private val leaguesKey = stringPreferencesKey("library_leagues_json")
    private val eventsKey = stringPreferencesKey("library_event_details_json")

    val teams: StateFlow<List<SavedSportsTeam>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs -> readTeams(prefs) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val leagues: StateFlow<List<SavedSportsLeague>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs -> readLeagues(prefs) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The bookmarked games' details, by event id. */
    val eventDetails: StateFlow<Map<String, SportsEvent>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs -> readEvents(prefs).associateBy { it.id } }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Adds the team to the Library, or takes it out if it's there. */
    suspend fun toggleTeam(team: SportsTeam) {
        store().edit { prefs ->
            val current = readTeams(prefs)
            val updated = if (current.any { it.team.id == team.id }) {
                current.filterNot { it.team.id == team.id }
            } else {
                current + SavedSportsTeam(team, System.currentTimeMillis())
            }
            prefs[teamsKey] = JSONArray().apply {
                updated.forEach { put(teamJson(it.team).put("addedAt", it.addedAt)) }
            }.toString()
        }
    }

    suspend fun removeTeam(teamId: String) {
        store().edit { prefs ->
            val updated = readTeams(prefs).filterNot { it.team.id == teamId }
            prefs[teamsKey] = JSONArray().apply {
                updated.forEach { put(teamJson(it.team).put("addedAt", it.addedAt)) }
            }.toString()
        }
    }

    /** Adds the league to the Library, or takes it out if it's there. */
    suspend fun toggleLeague(league: SportsLeague) {
        store().edit { prefs ->
            val current = readLeagues(prefs)
            val updated = if (current.any { it.league.id == league.id }) {
                current.filterNot { it.league.id == league.id }
            } else {
                current + SavedSportsLeague(league, System.currentTimeMillis())
            }
            prefs[leaguesKey] = JSONArray().apply {
                updated.forEach { put(leagueJson(it.league).put("addedAt", it.addedAt)) }
            }.toString()
        }
    }

    suspend fun removeLeague(leagueId: String) {
        store().edit { prefs ->
            val updated = readLeagues(prefs).filterNot { it.league.id == leagueId }
            prefs[leaguesKey] = JSONArray().apply {
                updated.forEach { put(leagueJson(it.league).put("addedAt", it.addedAt)) }
            }.toString()
        }
    }

    /** Keeps a bookmarked game's details (teams, badges, score, time) for the Library. */
    suspend fun rememberEvent(event: SportsEvent) {
        store().edit { prefs ->
            val updated = readEvents(prefs).filterNot { it.id == event.id } + event
            prefs[eventsKey] = JSONArray().apply { updated.forEach { put(eventJson(it)) } }.toString()
        }
    }

    suspend fun forgetEvent(eventId: String) {
        store().edit { prefs ->
            val updated = readEvents(prefs).filterNot { it.id == eventId }
            prefs[eventsKey] = JSONArray().apply { updated.forEach { put(eventJson(it)) } }.toString()
        }
    }

    // ── JSON ──

    private fun JSONObject.str(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

    private fun JSONObject.intOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.longOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private fun objects(json: String?): List<JSONObject> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        }.getOrDefault(emptyList())
    }

    private fun readTeams(prefs: Preferences): List<SavedSportsTeam> =
        objects(prefs[teamsKey]).mapNotNull { obj ->
            val id = obj.str("id") ?: return@mapNotNull null
            SavedSportsTeam(
                team = SportsTeam(
                    id = id,
                    name = obj.str("name") ?: id,
                    shortName = obj.str("shortName"),
                    sportName = obj.str("sportName"),
                    leagueId = obj.str("leagueId"),
                    leagueName = obj.str("leagueName"),
                    badgeUrl = obj.str("badgeUrl")
                ),
                addedAt = obj.longOrNull("addedAt") ?: 0L
            )
        }

    private fun teamJson(team: SportsTeam): JSONObject = JSONObject()
        .put("id", team.id)
        .put("name", team.name)
        .putOpt("shortName", team.shortName)
        .putOpt("sportName", team.sportName)
        .putOpt("leagueId", team.leagueId)
        .putOpt("leagueName", team.leagueName)
        .putOpt("badgeUrl", team.badgeUrl)

    private fun readLeagues(prefs: Preferences): List<SavedSportsLeague> =
        objects(prefs[leaguesKey]).mapNotNull { obj ->
            val id = obj.str("id") ?: return@mapNotNull null
            SavedSportsLeague(
                league = SportsLeague(
                    id = id,
                    name = obj.str("name") ?: id,
                    sportName = obj.str("sportName").orEmpty(),
                    badgeUrl = obj.str("badgeUrl"),
                    country = obj.str("country")
                ),
                addedAt = obj.longOrNull("addedAt") ?: 0L
            )
        }

    private fun leagueJson(league: SportsLeague): JSONObject = JSONObject()
        .put("id", league.id)
        .put("name", league.name)
        .put("sportName", league.sportName)
        .putOpt("badgeUrl", league.badgeUrl)
        .putOpt("country", league.country)

    private fun readEvents(prefs: Preferences): List<SportsEvent> =
        objects(prefs[eventsKey]).mapNotNull { obj ->
            val id = obj.str("id") ?: return@mapNotNull null
            SportsEvent(
                id = id,
                name = obj.str("name").orEmpty(),
                sportName = obj.str("sportName"),
                leagueId = obj.str("leagueId"),
                leagueName = obj.str("leagueName"),
                homeTeamId = obj.str("homeTeamId"),
                homeTeamName = obj.str("homeTeamName").orEmpty(),
                homeTeamBadgeUrl = obj.str("homeTeamBadgeUrl"),
                awayTeamId = obj.str("awayTeamId"),
                awayTeamName = obj.str("awayTeamName").orEmpty(),
                awayTeamBadgeUrl = obj.str("awayTeamBadgeUrl"),
                homeScore = obj.intOrNull("homeScore"),
                awayScore = obj.intOrNull("awayScore"),
                startTimeMs = obj.longOrNull("startTimeMs"),
                venue = obj.str("venue"),
                status = runCatching { SportsEventStatus.valueOf(obj.str("status").orEmpty()) }
                    .getOrDefault(SportsEventStatus.UPCOMING),
                statusDetail = obj.str("statusDetail")
            )
        }

    private fun eventJson(event: SportsEvent): JSONObject = JSONObject()
        .put("id", event.id)
        .put("name", event.name)
        .putOpt("sportName", event.sportName)
        .putOpt("leagueId", event.leagueId)
        .putOpt("leagueName", event.leagueName)
        .putOpt("homeTeamId", event.homeTeamId)
        .put("homeTeamName", event.homeTeamName)
        .putOpt("homeTeamBadgeUrl", event.homeTeamBadgeUrl)
        .putOpt("awayTeamId", event.awayTeamId)
        .put("awayTeamName", event.awayTeamName)
        .putOpt("awayTeamBadgeUrl", event.awayTeamBadgeUrl)
        .putOpt("homeScore", event.homeScore)
        .putOpt("awayScore", event.awayScore)
        .putOpt("startTimeMs", event.startTimeMs)
        .putOpt("venue", event.venue)
        .put("status", event.status.name)
        .putOpt("statusDetail", event.statusDetail)
}
