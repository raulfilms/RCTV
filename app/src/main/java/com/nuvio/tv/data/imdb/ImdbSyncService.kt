package com.nuvio.tv.data.imdb

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.WatchProgress
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
import kotlinx.coroutines.withContext
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
    /** Series rated on IMDb (the Library's "IMDb TV Rated"). */
    val ratedSeriesCount: Int = 0,
    val isSyncing: Boolean = false,
    /** Signed in to IMDb on this profile: what's watched in the app is marked watched on IMDb. */
    val isSignedIn: Boolean = false,
    /** The IMDb sign-in stopped working (signed out on IMDb, or it expired): sign in again. */
    val signInExpired: Boolean = false
) {
    val isConnected: Boolean get() = userId != null
}

/**
 * The user's IMDb watchlist and ratings, kept on the device for each profile and asked again
 * every half hour or so. Each profile links its own IMDb account (or none): another profile never
 * sees this one's watchlist, ratings or sign-in.
 * - every movie they rated on IMDb shows as watched (by its IMDb id and its TMDB id, as titles
 *   come from either); rated series aren't watched, they're the Library's "IMDb TV Rated";
 * - the watchlist is the Library's "IMDb List", apart from the app's own library;
 * - signed in to IMDb (in the app, on the profile), a movie watched to the end in the app is
 *   marked watched on IMDb. Nothing else is ever written to IMDb.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class ImdbSyncService @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val client: ImdbGraphqlClient,
    private val tmdbApi: TmdbApi,
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val mappingMutex = Mutex()
    private val markMutex = Mutex()
    /** When each movie was last tried on IMDb ("profile|tt…"): a movie playing to its end saves often. */
    private val markAttempts = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val syncing = MutableStateFlow(false)

    /** IMDb id → TMDB id of rated movies (shared by every profile; a title's ids don't change). */
    private val tmdbIdPrefs = appContext.getSharedPreferences("imdb_tmdb_ids", Context.MODE_PRIVATE)
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
        val ratedCount: Int = 0,
        val ratedSeries: List<ImdbTitle> = emptyList(),
        /** The IMDb sign-in's cookies (only this profile's). */
        val cookies: String? = null,
        val signInExpired: Boolean = false,
        /** Movies the app marked watched on IMDb already. */
        val markedWatched: Set<String> = emptySet()
    )

    private val stored: StateFlow<Stored> = profileManager.activeProfileId
        // Switching profiles starts from nothing, so the last profile's lists never show for this one.
        .flatMapLatest { profileId -> store(profileId).data.map { readStored(it) }.onStart { emit(Stored()) } }
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
        ratedSeriesCount = data.ratedSeries.size,
        isSyncing = isSyncing,
        isSignedIn = data.userId != null && ImdbGraphqlClient.isSignedIn(data.cookies),
        signInExpired = data.signInExpired
    )

    /** The IMDb watchlist, in its own order (empty with no account linked). */
    val watchlist: StateFlow<List<ImdbTitle>> = stored.map { it.watchlist }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The series rated on IMDb, newest rating first (empty with no account linked). */
    val ratedSeries: StateFlow<List<ImdbTitle>> = stored.map { it.ratedSeries }
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

    init {
        // Each profile has its own IMDb account (or none): switching profiles brings its own lists up to date.
        scope.launch {
            profileManager.activeProfileId.collect { syncIfStale() }
        }
    }

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
                prefs.remove(ratedSeriesKey)
                prefs.remove(nicknameKey)
                prefs.remove(markedKey)
                // A sign-in belongs to the account it was made for.
                prefs.remove(cookiesKey)
                prefs.remove(signInExpiredKey)
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

    /**
     * Signed in to IMDb (in the app's IMDb page): keeps the sign-in for this profile only, and
     * links its account ([profileKey]: the "ur…" or "p.…" id its profile page showed; null: the
     * account already linked).
     */
    suspend fun connectSignedIn(cookies: String, profileKey: String?): Result<ImdbAccount> {
        val profileId = profileManager.activeProfileId.value
        val linkedId = readStored(store(profileId).data.first()).userId
        val userId = if (profileKey != null) {
            val profile = try {
                client.profileFor(profileKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Result.failure(e)
            }
            if (profile.userId != linkedId) {
                connect(profile.userId).onFailure { return Result.failure(it) }
            }
            profile.userId
        } else {
            linkedId ?: return Result.failure(ImdbException(ImdbException.Reason.NOT_FOUND, "No IMDb account found for the sign-in"))
        }
        store(profileId).edit { prefs ->
            if (prefs[userIdKey] != userId) return@edit
            prefs[cookiesKey] = cookies
            prefs.remove(signInExpiredKey)
        }
        val synced = sync(force = true)
        return synced.map { accountOf(readStored(store(profileId).data.first()), isSyncing = false) }
    }

    /** Forgets the IMDb sign-in (the account stays linked: its lists still come in). */
    suspend fun signOut() {
        store(profileManager.activeProfileId.value).edit { prefs ->
            prefs.remove(cookiesKey)
            prefs.remove(signInExpiredKey)
        }
    }

    /**
     * A movie was watched to the end in the app (or marked watched there): on [profileId]'s IMDb
     * account, if it's signed in, it's marked watched too (unless IMDb has it rated, or the app
     * marked it already). Episodes aren't (IMDb marks titles, not episodes).
     */
    fun onWatchedInApp(profileId: Int, progress: WatchProgress) {
        if (progress.season != null || progress.episode != null) return
        if (!progress.contentType.equals("movie", ignoreCase = true)) return
        scope.launch {
            markMutex.withLock {
                val data = readStored(store(profileId).data.first())
                val cookies = data.cookies
                if (data.userId == null || data.signInExpired || !ImdbGraphqlClient.isSignedIn(cookies)) return@withLock
                val imdbId = imdbIdOf(progress) ?: return@withLock
                if (imdbId in data.ratedMovieIds || imdbId in data.markedWatched) return@withLock
                val attemptKey = "$profileId|$imdbId"
                val now = System.currentTimeMillis()
                if (now - (markAttempts[attemptKey] ?: 0L) < MARK_RETRY_MS) return@withLock
                markAttempts[attemptKey] = now
                val marked = try {
                    client.markWatched(imdbId, cookies.orEmpty())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ImdbException) {
                    if (e.reason == ImdbException.Reason.SIGNED_OUT || e.reason == ImdbException.Reason.PRIVATE) {
                        store(profileId).edit { it[signInExpiredKey] = System.currentTimeMillis() }
                        toast(appContext.getString(R.string.imdb_error_signed_out))
                    }
                    Log.w(TAG, "Couldn't mark $imdbId watched on IMDb: ${e.reason}")
                    return@withLock
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't mark $imdbId watched on IMDb: ${e.message}")
                    return@withLock
                }
                if (!marked) return@withLock
                store(profileId).edit { prefs ->
                    prefs[markedKey] = JSONArray((readIds(prefs[markedKey]) + imdbId).toList()).toString()
                }
                toast(appContext.getString(R.string.imdb_marked_watched_toast, progress.name.ifBlank { imdbId }))
            }
        }
    }

    /** The movie's IMDb id: its own id, or the IMDb id of its TMDB id. */
    private suspend fun imdbIdOf(progress: WatchProgress): String? {
        listOf(progress.contentId, progress.videoId).forEach { id ->
            IMDB_ID.find(id)?.takeIf { it.range.first == 0 }?.let { return it.value }
        }
        val tmdbId = progress.contentId.takeIf { it.startsWith("tmdb:") }
            ?.removePrefix("tmdb:")?.substringBefore(':')?.toIntOrNull()
            ?: return null
        tmdbIds.value.entries.firstOrNull { it.value == tmdbId }?.let { return it.key }
        return try {
            tmdbApi.getMovieExternalIds(tmdbId, BuildConfig.TMDB_API_KEY).body()?.imdbId?.takeIf { IMDB_ID.matches(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun toast(text: String) = withContext(Dispatchers.Main) {
        Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
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
            // Signed in, the watchlist comes in even when it's private on IMDb.
            val signedInCookies = current.cookies?.takeIf { !current.signInExpired && ImdbGraphqlClient.isSignedIn(it) }
            var signInFailed = false
            var watchlist = attempt { client.watchlist(userId, signedInCookies) }
            if (signedInCookies != null && (watchlist.exceptionOrNull() as? ImdbException)?.reason == ImdbException.Reason.SIGNED_OUT) {
                signInFailed = true
                watchlist = attempt { client.watchlist(userId) }
            }
            val ratedSeries = ratings.getOrNull()?.filter { it.isSeries && !it.isEpisode }?.sortedByDescending { it.addedAtMs }
            val ratedMovies = ratings.getOrNull()?.filter(::countsAsWatchedMovie)?.map { it.id }?.toSet()
            val error = listOf(watchlist.exceptionOrNull(), ratings.exceptionOrNull())
                .firstNotNullOfOrNull { (it as? ImdbException)?.reason ?: it?.let { ImdbException.Reason.FAILED } }
            // Saved to the profile it was read for (even if another profile is on by now), and only
            // while that profile still has this account.
            store(profileId).edit { prefs ->
                if (prefs[userIdKey] != userId) return@edit
                ratings.getOrNull()?.let { rated ->
                    prefs[ratedKey] = JSONArray(ratedMovies.orEmpty().toList()).toString()
                    prefs[ratedCountKey] = rated.size.toLong()
                    prefs[ratedSeriesKey] = JSONArray().apply { ratedSeries.orEmpty().forEach { put(titleJson(it)) } }.toString()
                }
                if (signInFailed) prefs[signInExpiredKey] = now
                watchlist.getOrNull()?.let { (titles, owner) ->
                    prefs[watchlistKey] = JSONArray().apply { titles.forEach { put(titleJson(it)) } }.toString()
                    if (prefs[nicknameKey].isNullOrBlank() && owner != null) prefs[nicknameKey] = owner
                }
                prefs[lastSyncKey] = now
                if (error != null) prefs[errorKey] = error.name else prefs.remove(errorKey)
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
            ratedCount = (prefs[ratedCountKey] ?: 0L).toInt(),
            ratedSeries = readTitles(prefs[ratedSeriesKey]),
            cookies = prefs[cookiesKey]?.takeIf { it.isNotBlank() },
            signInExpired = (prefs[signInExpiredKey] ?: 0L) > 0L,
            markedWatched = readIds(prefs[markedKey])
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
        /** A movie IMDb didn't take is tried again after this long. */
        const val MARK_RETRY_MS = 2L * 60 * 1000
        val NOT_MOVIES = setOf("videoGame", "podcastSeries", "podcastEpisode", "tvSeries", "tvMiniSeries", "tvEpisode")

        val inputKey = stringPreferencesKey("imdb_input")
        val userIdKey = stringPreferencesKey("imdb_user_id")
        val nicknameKey = stringPreferencesKey("imdb_nickname")
        val lastSyncKey = longPreferencesKey("imdb_last_sync_ms")
        val errorKey = stringPreferencesKey("imdb_last_error")
        val watchlistKey = stringPreferencesKey("imdb_watchlist_json")
        val ratedKey = stringPreferencesKey("imdb_rated_movies_json")
        val ratedCountKey = longPreferencesKey("imdb_rated_count")
        val ratedSeriesKey = stringPreferencesKey("imdb_rated_series_json")
        val cookiesKey = stringPreferencesKey("imdb_sign_in_cookies")
        val signInExpiredKey = longPreferencesKey("imdb_sign_in_expired_ms")
        val markedKey = stringPreferencesKey("imdb_marked_watched_json")
        val IMDB_ID = Regex("tt\\d{5,10}")
    }
}
