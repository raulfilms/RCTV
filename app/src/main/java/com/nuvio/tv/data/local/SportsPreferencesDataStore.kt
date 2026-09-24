package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

private const val DELIMITER = "\u0001"

/** Per-profile "My Sports" / "My Teams" favorites for the Sports screen. */
@Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SportsPreferencesDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "sports"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val favoriteSportsKey = stringPreferencesKey("sports_favorite_sports")
    private val favoriteTeamIdsKey = stringPreferencesKey("sports_favorite_team_ids")

    val favoriteSports: StateFlow<Set<String>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs -> prefs[favoriteSportsKey]?.toSet() ?: emptySet() }
    }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    val favoriteTeamIds: StateFlow<Set<String>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs -> prefs[favoriteTeamIdsKey]?.toSet() ?: emptySet() }
    }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    private fun String.toSet(): Set<String> = split(DELIMITER).filter { it.isNotBlank() }.toSet()
    private fun Set<String>.serialize(): String = joinToString(DELIMITER)

    suspend fun toggleFavoriteSport(sportName: String) {
        store().edit { prefs ->
            val current = prefs[favoriteSportsKey]?.toSet() ?: emptySet()
            val updated = if (sportName in current) current - sportName else current + sportName
            prefs[favoriteSportsKey] = updated.serialize()
        }
    }

    suspend fun toggleFavoriteTeam(teamId: String) {
        store().edit { prefs ->
            val current = prefs[favoriteTeamIdsKey]?.toSet() ?: emptySet()
            val updated = if (teamId in current) current - teamId else current + teamId
            prefs[favoriteTeamIdsKey] = updated.serialize()
        }
    }

    suspend fun setFavoriteSports(sports: Set<String>) {
        store().edit { prefs -> prefs[favoriteSportsKey] = sports.serialize() }
    }

    suspend fun setFavoriteTeams(teamIds: Set<String>) {
        store().edit { prefs -> prefs[favoriteTeamIdsKey] = teamIds.serialize() }
    }
}
