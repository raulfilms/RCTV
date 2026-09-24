package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.LiveTvConnection
import com.nuvio.tv.domain.model.LiveTvSource
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
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the per-profile IPTV sources (M3U playlists, Xtream Codes accounts, Stalker/MAG
 * portals) for Live TV.
 *
 * Supports multiple sources at once (added in the ARVIO merge, phase 1 step 3 - mirrors ARVIO's
 * own multi-playlist/multi-portal model); a caller that wants everything the user configured
 * reads [sources] and merges the enabled ones. [connection]/[setM3uConnection]/
 * [setXtreamConnection]/[setStalkerConnection]/[clearConnection] remain as a single-source
 * convenience API for existing callers (pre phase-1-step-3): they operate on one implicit
 * "Default" source and are equivalent to a one-element [sources] list. New code that wants
 * several sources side by side should use [addSource]/[updateSource]/[removeSource]/
 * [setSourceEnabled] instead.
 */
@Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LiveTvDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "live_tv"
        private const val DEFAULT_SOURCE_ID = "default"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val sourcesJsonKey = stringPreferencesKey("live_tv_sources_json")

    // Legacy single-connection keys, from before this multi-source format existed. Only ever
    // read (to migrate a value saved by an older build) - new writes always go through
    // [sourcesJsonKey], and [clearConnection] wipes these too so a migrated-then-cleared profile
    // doesn't resurrect them.
    private val legacyMethodKey = stringPreferencesKey("live_tv_connection_method")
    private val legacyM3uUrlKey = stringPreferencesKey("live_tv_m3u_playlist_url")
    private val legacyM3uEpgKey = stringPreferencesKey("live_tv_m3u_epg_url")
    private val legacyXtreamServerKey = stringPreferencesKey("live_tv_xtream_server_url")
    private val legacyXtreamUserKey = stringPreferencesKey("live_tv_xtream_username")
    private val legacyXtreamPassKey = stringPreferencesKey("live_tv_xtream_password")
    private val legacyStalkerPortalKey = stringPreferencesKey("live_tv_stalker_portal_url")
    private val legacyStalkerMacKey = stringPreferencesKey("live_tv_stalker_mac_address")

    /** Every configured source (enabled or not), newest multi-source format first, falling back to a migrated legacy single connection. */
    val sources: StateFlow<List<LiveTvSource>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs -> sourcesFromPrefs(prefs) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The first enabled source's connection, or null - kept for callers built before multi-source support. */
    val connection: StateFlow<LiveTvConnection?> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs -> sourcesFromPrefs(prefs).firstOrNull { it.enabled }?.connection }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private fun sourcesFromPrefs(prefs: Preferences): List<LiveTvSource> =
        parseSources(prefs[sourcesJsonKey]) ?: legacySource(prefs)?.let { listOf(it) } ?: emptyList()

    suspend fun setM3uConnection(playlistUrl: String, epgUrl: String?) {
        replaceWithSingleSource(
            LiveTvConnection.M3u(
                playlistUrl = playlistUrl.trim(),
                epgUrl = epgUrl?.trim()?.takeIf { it.isNotBlank() }
            )
        )
    }

    suspend fun setXtreamConnection(serverUrl: String, username: String, password: String) {
        replaceWithSingleSource(
            LiveTvConnection.Xtream(serverUrl = serverUrl.trim(), username = username.trim(), password = password.trim())
        )
    }

    suspend fun setStalkerConnection(portalUrl: String, macAddress: String) {
        replaceWithSingleSource(
            LiveTvConnection.Stalker(portalUrl = portalUrl.trim(), macAddress = macAddress.trim().uppercase())
        )
    }

    private suspend fun replaceWithSingleSource(connection: LiveTvConnection) {
        val source = LiveTvSource(id = DEFAULT_SOURCE_ID, name = "Default", connection = connection, enabled = true)
        store().edit { prefs -> prefs[sourcesJsonKey] = encodeSources(listOf(source)) }
    }

    suspend fun clearConnection() {
        store().edit { prefs ->
            prefs.remove(sourcesJsonKey)
            prefs.remove(legacyMethodKey)
            prefs.remove(legacyM3uUrlKey)
            prefs.remove(legacyM3uEpgKey)
            prefs.remove(legacyXtreamServerKey)
            prefs.remove(legacyXtreamUserKey)
            prefs.remove(legacyXtreamPassKey)
            prefs.remove(legacyStalkerPortalKey)
            prefs.remove(legacyStalkerMacKey)
        }
    }

    // ── Multi-source API (phase 2 UI builds on these) ──

    /** Adds a new source, or replaces the existing one with the same [LiveTvSource.id]. */
    suspend fun addSource(source: LiveTvSource) {
        store().edit { prefs ->
            val current = sourcesFromPrefs(prefs)
            prefs[sourcesJsonKey] = encodeSources(current.filterNot { it.id == source.id } + source)
        }
    }

    /** Alias for [addSource]: both are an upsert keyed by [LiveTvSource.id]. */
    suspend fun updateSource(source: LiveTvSource) = addSource(source)

    suspend fun removeSource(id: String) {
        store().edit { prefs ->
            val current = sourcesFromPrefs(prefs)
            prefs[sourcesJsonKey] = encodeSources(current.filterNot { it.id == id })
        }
    }

    suspend fun setSourceEnabled(id: String, enabled: Boolean) {
        store().edit { prefs ->
            val current = sourcesFromPrefs(prefs)
            prefs[sourcesJsonKey] = encodeSources(current.map { if (it.id == id) it.copy(enabled = enabled) else it })
        }
    }

    /** A fresh, unused source id for [addSource] to key a newly created source on. */
    fun newSourceId(): String = UUID.randomUUID().toString()

    private fun encodeSources(sources: List<LiveTvSource>): String {
        val array = JSONArray()
        sources.forEach { array.put(encodeSource(it)) }
        return array.toString()
    }

    private fun encodeSource(source: LiveTvSource): JSONObject {
        val obj = JSONObject()
        obj.put("id", source.id)
        obj.put("name", source.name)
        obj.put("enabled", source.enabled)
        when (val connection = source.connection) {
            is LiveTvConnection.M3u -> {
                obj.put("type", "m3u")
                obj.put("playlistUrl", connection.playlistUrl)
                connection.epgUrl?.let { obj.put("epgUrl", it) }
            }
            is LiveTvConnection.Xtream -> {
                obj.put("type", "xtream")
                obj.put("serverUrl", connection.serverUrl)
                obj.put("username", connection.username)
                obj.put("password", connection.password)
            }
            is LiveTvConnection.Stalker -> {
                obj.put("type", "stalker")
                obj.put("portalUrl", connection.portalUrl)
                obj.put("macAddress", connection.macAddress)
            }
        }
        return obj
    }

    private fun parseSources(json: String?): List<LiveTvSource>? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val connection = connectionFromJson(obj) ?: continue
                    add(
                        LiveTvSource(
                            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                            name = obj.optString("name").ifBlank { "Source" },
                            connection = connection,
                            enabled = obj.optBoolean("enabled", true)
                        )
                    )
                }
            }
        }.getOrNull()
    }

    private fun connectionFromJson(obj: JSONObject): LiveTvConnection? = when (obj.optString("type")) {
        "m3u" -> obj.optString("playlistUrl").takeIf { it.isNotBlank() }?.let { url ->
            LiveTvConnection.M3u(playlistUrl = url, epgUrl = obj.optString("epgUrl").takeIf { it.isNotBlank() })
        }
        "xtream" -> {
            val server = obj.optString("serverUrl")
            val user = obj.optString("username")
            val pass = obj.optString("password")
            if (server.isNotBlank() && user.isNotBlank() && pass.isNotBlank()) {
                LiveTvConnection.Xtream(serverUrl = server, username = user, password = pass)
            } else null
        }
        "stalker" -> {
            val portal = obj.optString("portalUrl")
            val mac = obj.optString("macAddress")
            if (portal.isNotBlank() && mac.isNotBlank()) {
                LiveTvConnection.Stalker(portalUrl = portal, macAddress = mac)
            } else null
        }
        else -> null
    }

    private fun legacySource(prefs: Preferences): LiveTvSource? {
        val connection = when (prefs[legacyMethodKey]) {
            "xtream" -> {
                val server = prefs[legacyXtreamServerKey]
                val user = prefs[legacyXtreamUserKey]
                val pass = prefs[legacyXtreamPassKey]
                if (!server.isNullOrBlank() && !user.isNullOrBlank() && !pass.isNullOrBlank()) {
                    LiveTvConnection.Xtream(serverUrl = server, username = user, password = pass)
                } else null
            }
            "m3u" -> {
                val url = prefs[legacyM3uUrlKey]
                if (!url.isNullOrBlank()) {
                    LiveTvConnection.M3u(playlistUrl = url, epgUrl = prefs[legacyM3uEpgKey]?.takeIf { it.isNotBlank() })
                } else null
            }
            "stalker" -> {
                val portal = prefs[legacyStalkerPortalKey]
                val mac = prefs[legacyStalkerMacKey]
                if (!portal.isNullOrBlank() && !mac.isNullOrBlank()) {
                    LiveTvConnection.Stalker(portalUrl = portal, macAddress = mac)
                } else null
            }
            else -> null
        } ?: return null
        return LiveTvSource(id = DEFAULT_SOURCE_ID, name = "Default", connection = connection, enabled = true)
    }
}
