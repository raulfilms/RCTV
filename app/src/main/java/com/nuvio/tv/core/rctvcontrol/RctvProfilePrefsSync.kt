package com.nuvio.tv.core.rctvcontrol

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RCTV (owner): everything a profile keeps as its favorites, kept with its RCTV account too, so a
 * TV activated with the same account has them at once (nothing to pick or link again): its sports
 * and leagues ("My Sports"), its teams ("My Teams"), its bookmarked games, its Favorite Networks
 * (the Guide's Custom list), its IMDb link (the public profile link only: never an IMDb sign-in)
 * and that it went through "Customize your experience". Movies, series, watched titles and
 * progress already follow the account ([RctvWatchSync]).
 *
 * Only these values, by name: nothing else of the profile's settings (the IPTV's login, for one,
 * never leaves the TV). The first time a profile syncs on a TV, the account's favorites and this
 * TV's are put together; after that the latest change wins: a change here goes up within seconds,
 * and the other TVs' come down every few minutes, when the app comes to the front and when the
 * profile changes.
 */
@Singleton
class RctvProfilePrefsSync @Inject constructor(
    @ApplicationContext context: Context,
    private val access: RctvAccessManager,
    private val profileManager: ProfileManager,
    private val factory: ProfileDataStoreFactory,
    private val customizeStore: RctvCustomizeStore
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _checked = MutableStateFlow<Set<Int>>(emptySet())

    /**
     * The profiles whose account favorites were read since the app opened (or couldn't be, in
     * time): "Customize your experience" waits for this, so it doesn't show for a profile that
     * already went through it on another TV.
     */
    val checked: StateFlow<Set<Int>> = _checked.asStateFlow()

    /** True while a profile's favorites follow its account (an activated TV). */
    val active: Boolean get() = access.watchSyncActive

    /** Keeps the profile watching in step with its account while the app is open (from the screen's lifecycle). */
    suspend fun run() {
        combine(
            access.state.map { !it.needsActivation }.distinctUntilChanged(),
            profileManager.activeProfileId
        ) { activated, profileId -> activated to profileId }
            .collectLatest { (activated, profileId) ->
                if (!activated) {
                    // Not (or no longer) on an account: the next one starts over.
                    prefs.edit().clear().apply()
                    _checked.value = emptySet()
                    return@collectLatest
                }
                if (!access.watchSyncActive) {
                    _checked.update { it + profileId }
                    return@collectLatest
                }
                try {
                    withTimeoutOrNull(FIRST_READ_MS) { sync(profileId) }
                } finally {
                    _checked.update { it + profileId }
                }
                var ticks = 0
                while (true) {
                    delay(CHECK_EVERY_MS)
                    ticks++
                    if (ticks % PULL_EVERY_TICKS == 0) sync(profileId) else pushIfChanged(profileId)
                }
            }
    }

    // ------------------------------------------------------------------ Sync

    private suspend fun sync(profileId: Int) = mutex.withLock {
        try {
            val local = export(profileId)
            val last = prefs.getString(lastKey(profileId), null)
            val lastVersion = prefs.getLong(versionKey(profileId), -1L)
            val (remote, version) = access.profilePrefs(profileId) ?: return@withLock
            when {
                remote == null || remote.length() == 0 -> {
                    // The account has none yet: this TV's go up.
                    if (hasAny(local)) push(profileId, local) else remember(profileId, local, version)
                }
                last == null -> {
                    // The first time on this TV: the account's, with what only this TV had.
                    write(profileId, merge(remote, local))
                    val now = export(profileId)
                    if (canonical(now) != canonical(remote)) push(profileId, now) else remember(profileId, now, version)
                }
                canonical(local) != last -> {
                    // Changed here since the last time: this TV's go up (the latest change wins).
                    push(profileId, local)
                }
                version != lastVersion -> {
                    // Changed on another TV: the account's come down.
                    write(profileId, remote)
                    remember(profileId, export(profileId), version)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Tried again on the next round; nothing on the TV is lost.
            Log.w(TAG, "Favorites sync failed: ${e.javaClass.simpleName}")
        }
    }

    /** The profile's own changes, up (when it has any). */
    private suspend fun pushIfChanged(profileId: Int) = mutex.withLock {
        try {
            val last = prefs.getString(lastKey(profileId), null) ?: return@withLock
            val local = export(profileId)
            if (canonical(local) != last) push(profileId, local)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Favorites push failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun push(profileId: Int, doc: JSONObject) {
        val version = access.saveProfilePrefs(profileId, doc) ?: return
        remember(profileId, doc, version)
    }

    private fun remember(profileId: Int, doc: JSONObject, version: Long) {
        prefs.edit()
            .putString(lastKey(profileId), canonical(doc))
            .putLong(versionKey(profileId), version)
            .apply()
    }

    // ------------------------------------------------------------------ The profile's favorites

    /** The profile's favorites as the account keeps them. */
    private suspend fun export(profileId: Int): JSONObject {
        val doc = JSONObject().put("schema", 1)
        for (feature in FEATURES) {
            val stored = factory.get(profileId, feature.store).data.first()
            val section = JSONObject()
            for (field in feature.fields) {
                when (field.kind) {
                    Kind.STRING_SET -> stored[stringSetPreferencesKey(field.key)]
                        ?.filter { it.isNotEmpty() }
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { section.put(field.key, JSONArray(it.sorted())) }
                    else -> stored[stringPreferencesKey(field.key)]
                        ?.takeIf { it.isNotBlank() }
                        ?.let { section.put(field.key, it) }
                }
            }
            doc.put(feature.section, section)
        }
        doc.put(CUSTOMIZE_DONE, customizeStore.isDone(profileId))
        return doc
    }

    /** Puts [doc]'s favorites on the profile (what it doesn't have is taken off). */
    private suspend fun write(profileId: Int, doc: JSONObject) {
        for (feature in FEATURES) {
            val section = doc.optJSONObject(feature.section) ?: JSONObject()
            factory.get(profileId, feature.store).edit { stored ->
                if (feature.store == IMDB_STORE) {
                    // Another IMDb account: what this TV had read from the old one goes.
                    val before = stored[stringPreferencesKey(IMDB_USER_ID)]
                    val after = section.optString(IMDB_USER_ID).takeIf { it.isNotBlank() }
                    if (before != after) IMDB_READ_KEYS.forEach { stored.remove(stringPreferencesKey(it)) }
                }
                for (field in feature.fields) write(stored, field, section)
            }
        }
        if (doc.optBoolean(CUSTOMIZE_DONE)) customizeStore.markDone(profileId)
    }

    private fun write(stored: MutablePreferences, field: Field, section: JSONObject) {
        when (field.kind) {
            Kind.STRING_SET -> {
                val key = stringSetPreferencesKey(field.key)
                val values = section.optJSONArray(field.key)?.let { array ->
                    (0 until array.length()).map { array.optString(it) }.filter { it.isNotEmpty() }.toSet()
                }
                if (values.isNullOrEmpty()) stored.remove(key) else stored[key] = values
            }
            else -> {
                val key = stringPreferencesKey(field.key)
                val value = if (section.isNull(field.key)) null else section.optString(field.key).takeIf { it.isNotBlank() }
                if (value == null) stored.remove(key) else stored[key] = value
            }
        }
    }

    private fun hasAny(doc: JSONObject): Boolean =
        doc.optBoolean(CUSTOMIZE_DONE) || FEATURES.any { feature -> (doc.optJSONObject(feature.section)?.length() ?: 0) > 0 }

    /**
     * The account's favorites with this TV's added: lists and sets put together (each item once),
     * the IMDb link the account's when it has one.
     */
    private fun merge(remote: JSONObject, local: JSONObject): JSONObject {
        val out = JSONObject().put("schema", 1)
        for (feature in FEATURES) {
            val r = remote.optJSONObject(feature.section) ?: JSONObject()
            val l = local.optJSONObject(feature.section) ?: JSONObject()
            val section = JSONObject()
            if (feature.store == IMDB_STORE) {
                val from = if (r.optString(IMDB_USER_ID).isNotBlank()) r else l
                feature.fields.forEach { field -> from.optString(field.key).takeIf { it.isNotBlank() }?.let { section.put(field.key, it) } }
            } else {
                for (field in feature.fields) {
                    val merged: Any? = when (field.kind) {
                        Kind.STRING_SET -> union(r.optJSONArray(field.key), l.optJSONArray(field.key))
                            ?.let { values -> JSONArray(values.sorted()) }
                        Kind.DELIMITED_SET -> union(r.optString(field.key), l.optString(field.key))
                        Kind.JSON_LIST -> unionList(r.optString(field.key), l.optString(field.key))
                        Kind.TEXT -> r.optString(field.key).ifBlank { l.optString(field.key) }.takeIf { it.isNotBlank() }
                    }
                    if (merged != null) section.put(field.key, merged)
                }
            }
            out.put(feature.section, section)
        }
        out.put(CUSTOMIZE_DONE, remote.optBoolean(CUSTOMIZE_DONE) || local.optBoolean(CUSTOMIZE_DONE))
        return out
    }

    private fun union(a: JSONArray?, b: JSONArray?): Set<String>? {
        val out = LinkedHashSet<String>()
        listOfNotNull(a, b).forEach { array -> (0 until array.length()).forEach { i -> array.optString(i).takeIf { it.isNotEmpty() }?.let(out::add) } }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun union(a: String, b: String): String? {
        val out = LinkedHashSet<String>()
        listOf(a, b).forEach { text -> text.split(DELIMITER).filter { it.isNotBlank() }.forEach(out::add) }
        return out.takeIf { it.isNotEmpty() }?.joinToString(DELIMITER)
    }

    /** Two JSON lists of saved items, each item once (by its league and id). */
    private fun unionList(a: String, b: String): String? {
        val out = JSONArray()
        val seen = HashSet<String>()
        listOf(a, b).forEach { text ->
            val array = runCatching { JSONArray(text) }.getOrNull() ?: return@forEach
            for (i in 0 until array.length()) {
                val item = array.opt(i) ?: continue
                val identity = (item as? JSONObject)?.let { obj ->
                    obj.optString("id").takeIf { it.isNotBlank() }?.let { id -> obj.optString("leagueId") + "|" + id }
                } ?: canonical(item)
                if (seen.add(identity)) out.put(item)
            }
        }
        return if (out.length() == 0) null else out.toString()
    }

    /** One way to write the same favorites (keys in order), to tell whether they changed. */
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted()
            .joinToString(",", "{", "}") { key -> JSONObject.quote(key) + ":" + canonical(value.opt(key)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }

    private fun lastKey(profileId: Int) = "last_p$profileId"
    private fun versionKey(profileId: Int) = "version_p$profileId"

    private enum class Kind { TEXT, JSON_LIST, DELIMITED_SET, STRING_SET }

    private data class Field(val key: String, val kind: Kind)

    /** A profile store ([ProfileDataStoreFactory]) and the values of it that follow the account. */
    private data class Feature(val store: String, val section: String, val fields: List<Field>)

    private companion object {
        const val TAG = "RctvProfilePrefs"
        const val PREFS = "rctv_profile_prefs_sync"
        const val CUSTOMIZE_DONE = "customize_done"
        const val IMDB_STORE = "imdb_sync"
        const val IMDB_USER_ID = "imdb_user_id"

        /** The sports' sets' separator ([com.nuvio.tv.data.local.SportsPreferencesDataStore]). */
        const val DELIMITER = "\u0001"

        /** The first read waits at most this long (the opening goes on without it). */
        const val FIRST_READ_MS = 10_000L
        const val CHECK_EVERY_MS = 15_000L

        /** Every 3 minutes, the other TVs' changes. */
        const val PULL_EVERY_TICKS = 12

        /**
         * The values that follow the account, by their stores' own names (SportsLibraryStore,
         * SportsPreferencesDataStore, LiveTvDataStore, ImdbSyncService). Nothing else: the IPTV's
         * login in the same Live TV store, or an IMDb sign-in, never goes.
         */
        val FEATURES = listOf(
            Feature(
                "sports_library", "sports_library",
                listOf(
                    Field("library_teams_json", Kind.JSON_LIST),
                    Field("library_leagues_json", Kind.JSON_LIST),
                    Field("library_event_details_json", Kind.JSON_LIST)
                )
            ),
            Feature(
                "sports", "sports",
                listOf(
                    Field("sports_favorite_sports", Kind.DELIMITED_SET),
                    Field("sports_favorite_team_ids", Kind.DELIMITED_SET)
                )
            ),
            Feature(
                "live_tv", "live_tv",
                listOf(
                    Field("live_tv_custom_channel_ids", Kind.STRING_SET),
                    Field("live_tv_custom_channels_info_json", Kind.JSON_LIST)
                )
            ),
            Feature(
                IMDB_STORE, "imdb",
                listOf(
                    Field("imdb_input", Kind.TEXT),
                    Field(IMDB_USER_ID, Kind.TEXT),
                    Field("imdb_nickname", Kind.TEXT)
                )
            )
        )

        /** What the TV read from an IMDb account (and its sign-in): gone when the account linked changes. */
        val IMDB_READ_KEYS = listOf(
            "imdb_last_sync_ms", "imdb_last_error", "imdb_watchlist_json", "imdb_rated_movies_json",
            "imdb_rated_count", "imdb_rated_series_json", "imdb_sign_in_cookies", "imdb_sign_in_expired_ms",
            "imdb_marked_watched_json"
        )
    }
}
