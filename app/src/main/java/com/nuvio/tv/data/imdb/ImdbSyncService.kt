package com.nuvio.tv.data.imdb

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.imdb.ImdbException
import com.nuvio.tv.data.remote.imdb.ImdbGraphqlClient
import com.nuvio.tv.data.remote.imdb.ImdbTitle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ImdbSync"

/** The IMDb account linked to the profile, and how its last sync went. */
data class ImdbAccount(
    /** What the user typed (their id or profile link). */
    val input: String = "",
    /** "ur12345678"; null when no account is linked. */
    val userId: String? = null,
    val nickname: String? = null,
    val lastSyncMs: Long = 0L,
    /** Why the last sync missed something (the watchlist is private...); null when it all came in. */
    val error: ImdbException.Reason? = null,
    val watchlistCount: Int = 0,
    val ratedCount: Int = 0,
    val isSyncing: Boolean = false
) {
    val isConnected: Boolean get() = userId != null
}

/**
 * The user's IMDb watchlist and ratings, kept on the device for each profile and asked again
 * every half hour or so:
 * - every movie they rated on IMDb shows as watched (by its IMDb id and its TMDB id, as titles
 *   come from either);
 * - the watchlist is the Library's "IMDb List", apart from the app's own library.
 * Nothing is ever written to IMDb.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class ImdbSyncService @Inject constructor(
    @ApplicationContext context: Context,
    private val client: ImdbGraphqlClient,
    private val tmdbApi: TmdbApi,
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val mappingMutex = Mutex()
    private val syncing = MutableStateFlow(false)

    /** IMDb id → TMDB id of rated movies (shared by every profile; a title's ids don't change). */
    private val tmdbIdPrefs = context.getSharedPreferences("imdb_tmdb_ids", Context.MODE_PRIVATE)
    private val tmdbIds = MutableStateFlow(readTmdbIds())

    private fun store(profileId: Int) = factory.get(profileId, FEATURE)

    private class Stored(
        val input: String = "",
        val userId: String? = null,
        val nickname: String? = null,
        val lastSyncMs: Long = 0L,
        val error: ImdbException.Reason? = null,
        val watchlist: List<ImdbTitle> = emptyList(),
        val ratedMovieIds: Set<String> = emptySet(),
        val ratedCount: Int = 0
    )

    private val stored: StateFlow<Stored> = profileManager.activeProfileId
        .flatMapLatest { profileId -> store(profileId).data.map { readStored(it) } }
        .stateIn(scope, SharingStarted.Eagerly, Stored())

    val account: StateFlow<ImdbAccount> = combine(stored, syncing) { data, isSyncing -> accountOf(data, isSyncing) }
        .stateIn(scope, SharingStarted.Eagerly, ImdbAccount())

    private fun accountOf(data: Stored, isSyncing: Boolean) = ImdbAccount(
        input = data.input,
        userId = data.userId,
        nickname = data.nickname,
        lastSyncMs = data.lastSyncMs,
        error = data.error,
        watchlistCount = data.watchlist.size,
        ratedCount = data.ratedCount,
        isSyncing = isSyncing
    )

    /** The IMDb watchlist, in its own order (empty with no account linked). */
    val watchlist: StateFlow<List<ImdbTitle>> = stored.map { it.watchlist }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The movies rated on IMDb: their IMDb ids ("tt…") and TMDB ids ("tmdb:…"). */
    val watchedMovieKeys: StateFlow<Set<String>> = combine(stored, tmdbIds) { data, mapped ->
        if (data.ratedMovieIds.isEmpty()) {
            emptySet()
        } else {
            buildSet {
                data.ratedMovieIds.forEach { imdbId ->
                    add(imdbId)
                    mapped[imdbId]?.let { add("tmdb:$it") }
                }
            }
        }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** [watchedMovieKeys], asking IMDb again first when the last sync is old. */
    fun observeWatchedMovieKeys(): Flow<Set<String>> = watchedMovieKeys.onStart { syncIfStale() }

    /** Asks IMDb again (in the background) when it was last asked a while ago. */
    fun syncIfStale() {
        scope.launch { runCatching { sync(force = false) } }
    }

    /**
     * Links the account the user typed ("ur12345678", "p.abc…", or their profile link) and reads
     * its watchlist and ratings. Fails with [ImdbException] when it isn't an IMDb account.
     */
    suspend fun connect(input: String): Result<ImdbAccount> {
        val profile = try {
            client.profileFor(input)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val profileId = profileManager.activeProfileId.value
        store(profileId).edit { prefs ->
            if (prefs[userIdKey] != profile.userId) {
                prefs.remove(watchlistKey)
                prefs.remove(ratedKey)
                prefs.remove(ratedCountKey)
                prefs.remove(nicknameKey)
            }
            prefs[inputKey] = input.trim()
            prefs[userIdKey] = profile.userId
            profile.nickname?.let { prefs[nicknameKey] = it }
            prefs.remove(lastSyncKey)
            prefs.remove(errorKey)
        }
        val synced = sync(force = true)
        return synced.map { accountOf(readStored(store(profileId).data.first()), isSyncing = false) }
    }

    /** Unlinks the account: its watchlist and ratings leave the app. */
    suspend fun disconnect() {
        store(profileManager.activeProfileId.value).edit { it.clear() }
    }

    /** Reads the watchlist and the ratings again (only when they're old, unless [force]). */
    suspend fun sync(force: Boolean): Result<Unit> = syncMutex.withLock {
        val profileId = profileManager.activeProfileId.value
        val current = readStored(store(profileId).data.first())
        val userId = current.userId ?: return Result.success(Unit)
        val now = System.currentTimeMillis()
        if (!force && current.lastSyncMs > 0 && now - current.lastSyncMs < STALE_MS) return Result.success(Unit)
        syncing.value = true
        try {
            val ratings = attempt { client.ratedTitles(userId) }
            val watchlist = attempt { client.watchlist(userId) }
            val ratedMovies = ratings.getOrNull()?.filter(::countsAsWatchedMovie)?.map { it.id }?.toSet()
            val error = listOf(watchlist.exceptionOrNull(), ratings.exceptionOrNull())
                .firstNotNullOfOrNull { (it as? ImdbException)?.reason ?: it?.let { ImdbException.Reason.FAILED } }
            if (profileManager.activeProfileId.value == profileId) {
                store(profileId).edit { prefs ->
                    if (prefs[userIdKey] != userId) return@edit
                    ratings.getOrNull()?.let { rated ->
                        prefs[ratedKey] = JSONArray(ratedMovies.orEmpty().toList()).toString()
                        prefs[ratedCountKey] = rated.size.toLong()
                    }
                    watchlist.getOrNull()?.let { (titles, owner) ->
                        prefs[watchlistKey] = JSONArray().apply { titles.forEach { put(titleJson(it)) } }.toString()
                        if (prefs[nicknameKey].isNullOrBlank() && owner != null) prefs[nicknameKey] = owner
                    }
                    prefs[lastSyncKey] = now
                    if (error != null) prefs[errorKey] = error.name else prefs.remove(errorKey)
                }
            }
            // Their TMDB ids meanwhile (a first sync can have hundreds to look up).
            if (ratedMovies != null) scope.launch { mappingMutex.withLock { mapToTmdb(ratedMovies) } }
            if (ratings.isFailure && watchlist.isFailure) {
                Result.failure(watchlist.exceptionOrNull() ?: ImdbException(ImdbException.Reason.FAILED, "IMDb sync failed"))
            } else {
                Result.success(Unit)
            }
        } finally {
            syncing.value = false
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "IMDb sync step failed: ${e.message}")
        Result.failure(e)
    }

    /** Rated movies (and TV movies, shorts, specials): not series, episodes or games. */
    private fun countsAsWatchedMovie(title: ImdbTitle): Boolean =
        !title.isSeries && !title.isEpisode && title.titleType !in NOT_MOVIES

    /** Finds the TMDB id of each rated movie not looked up yet (kept for good; "none" for two weeks). */
    private suspend fun mapToTmdb(imdbIds: Set<String>) {
        val now = System.currentTimeMillis()
        val pending = imdbIds.filter { imdbId ->
            val saved = tmdbIdPrefs.getString(imdbId, null) ?: return@filter true
            saved.startsWith(NONE_PREFIX) &&
                now - (saved.removePrefix(NONE_PREFIX).toLongOrNull() ?: 0L) > NONE_RETRY_MS
        }
        if (pending.isEmpty()) return
        val limiter = Semaphore(TMDB_LOOKUPS_AT_ONCE)
        pending.chunked(TMDB_BATCH).forEach { batch ->
            val found = coroutineScope {
                batch.map { imdbId ->
                    async { limiter.withPermit { imdbId to tmdbMovieIdOf(imdbId) } }
                }.awaitAll()
            }
            val editor = tmdbIdPrefs.edit()
            found.forEach { (imdbId, lookup) ->
                when (lookup) {
                    is TmdbLookup.Found -> editor.putString(imdbId, lookup.id.toString())
                    TmdbLookup.None -> editor.putString(imdbId, "$NONE_PREFIX$now")
                    // TMDB couldn't be asked: asked again next sync.
                    TmdbLookup.Failed -> Unit
                }
            }
            editor.apply()
            tmdbIds.update { current ->
                current + found.mapNotNull { (imdbId, lookup) -> (lookup as? TmdbLookup.Found)?.let { imdbId to it.id } }
            }
        }
    }

    private sealed interface TmdbLookup {
        data class Found(val id: Int) : TmdbLookup
        data object None : TmdbLookup
        data object Failed : TmdbLookup
    }

    /** The movie's TMDB id ([TmdbLookup.None]: TMDB has none; [TmdbLookup.Failed]: TMDB couldn't be asked). */
    private suspend fun tmdbMovieIdOf(imdbId: String): TmdbLookup = try {
        val response = tmdbApi.findByExternalId(externalId = imdbId, apiKey = BuildConfig.TMDB_API_KEY, externalSource = "imdb_id")
        val body = response.body()
        when {
            !response.isSuccessful || body == null -> TmdbLookup.Failed
            else -> body.movieResults?.firstOrNull()?.id?.let { TmdbLookup.Found(it) } ?: TmdbLookup.None
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TmdbLookup.Failed
    }

    private fun readTmdbIds(): Map<String, Int> =
        tmdbIdPrefs.all.mapNotNull { (imdbId, value) ->
            (value as? String)?.toIntOrNull()?.let { imdbId to it }
        }.toMap()

    private fun readStored(prefs: Preferences): Stored {
        val userId = prefs[userIdKey]?.takeIf { it.isNotBlank() } ?: return Stored(input = prefs[inputKey].orEmpty())
        return Stored(
            input = prefs[inputKey].orEmpty(),
            userId = userId,
            nickname = prefs[nicknameKey]?.takeIf { it.isNotBlank() },
            lastSyncMs = prefs[lastSyncKey] ?: 0L,
            error = prefs[errorKey]?.let { name -> ImdbException.Reason.entries.firstOrNull { it.name == name } },
            watchlist = readTitles(prefs[watchlistKey]),
            ratedMovieIds = readIds(prefs[ratedKey]),
            ratedCount = (prefs[ratedCountKey] ?: 0L).toInt()
        )
    }

    private fun readIds(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptySet()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf { id -> id.startsWith("tt") } }.toSet()
    }

    private fun readTitles(json: String?): List<ImdbTitle> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optString("id").takeIf { it.startsWith("tt") } ?: return@mapNotNull null
            val genres = obj.optJSONArray("genres")
            ImdbTitle(
                id = id,
                name = obj.optString("name").ifBlank { id },
                titleType = obj.optString("type"),
                isSeries = obj.optBoolean("series"),
                isEpisode = obj.optBoolean("episode"),
                year = obj.optInt("year").takeIf { it > 0 },
                posterUrl = obj.optString("poster").takeIf { it.startsWith("http") },
                genres = if (genres == null) emptyList() else (0 until genres.length()).mapNotNull { genres.optString(it).takeIf(String::isNotBlank) },
                imdbRating = obj.optDouble("rating").takeIf { !it.isNaN() && it > 0 }?.toFloat(),
                addedAtMs = obj.optLong("added")
            )
        }
    }

    private fun titleJson(title: ImdbTitle): JSONObject = JSONObject()
        .put("id", title.id)
        .put("name", title.name)
        .put("type", title.titleType)
        .put("series", title.isSeries)
        .put("episode", title.isEpisode)
        .putOpt("year", title.year)
        .putOpt("poster", title.posterUrl)
        .put("genres", JSONArray(title.genres))
        .putOpt("rating", title.imdbRating?.toDouble())
        .put("added", title.addedAtMs)

    private companion object {
        const val FEATURE = "imdb_sync"
        /** Asked again after this long. */
        const val STALE_MS = 30L * 60 * 1000
        const val NONE_PREFIX = "none|"
        const val NONE_RETRY_MS = 14L * 24 * 60 * 60 * 1000
        const val TMDB_LOOKUPS_AT_ONCE = 6
        const val TMDB_BATCH = 40
        val NOT_MOVIES = setOf("videoGame", "podcastSeries", "podcastEpisode", "tvSeries", "tvMiniSeries", "tvEpisode")

        val inputKey = stringPreferencesKey("imdb_input")
        val userIdKey = stringPreferencesKey("imdb_user_id")
        val nicknameKey = stringPreferencesKey("imdb_nickname")
        val lastSyncKey = longPreferencesKey("imdb_last_sync_ms")
        val errorKey = stringPreferencesKey("imdb_last_error")
        val watchlistKey = stringPreferencesKey("imdb_watchlist_json")
        val ratedKey = stringPreferencesKey("imdb_rated_movies_json")
        val ratedCountKey = longPreferencesKey("imdb_rated_count")
    }
}
