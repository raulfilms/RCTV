package com.nuvio.tv.data.remote.sportsdb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * TheSportsDB's API with its free key: 30 requests a minute for the whole app. Every source that
 * reads it (PPV Events, the CFL, players' photos) asks here, so together they stay under it.
 */
internal object SportsDbRequests {

    const val API = "https://www.thesportsdb.com/api/v1/json/123"

    /** Leaves a few of the key's 30 a minute for the rest of the app (players' photos). */
    private const val MAX_PER_MINUTE = 24

    /** All of them together (players' photos may use what PPV and the CFL leave): just under the key's 30. */
    private const val MAX_TOTAL_PER_MINUTE = 28
    private const val MINUTE_MS = 60_000L

    /** What one request that waits for its turn ([getInTurn]) got. */
    sealed interface Reply {
        /** The answer. */
        data class Ok(val body: String) : Reply
        /** TheSportsDB said "too many requests". */
        data object Busy : Reply
        /** It couldn't be reached, or answered with an error. */
        data object Failed : Reply
    }

    /** When the last requests went out. */
    private val times = ArrayDeque<Long>()

    /** One request to the API ([path]: "eventsround.php?id=..."); null when this minute's are used up, or it fails. */
    fun get(okHttpClient: OkHttpClient, path: String): String? {
        if (!take()) return null
        val request = Request.Builder()
            .url("$API/$path")
            .header("Accept", "application/json")
            .build()
        return runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (response.code == 429) {
                    // Over the limit anyway: no more this minute.
                    fillMinute()
                    return@use null
                }
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        }.getOrNull()
    }

    /** Requests left this minute. */
    fun left(): Int = synchronized(times) {
        drop(System.currentTimeMillis())
        MAX_PER_MINUTE - times.size
    }

    /**
     * One request to the API ([path]) that waits for its turn when this minute's requests are used
     * up (players' photos: they come a little later rather than not at all). When TheSportsDB says
     * "too many requests" anyway, the rest of the minute is kept free and [Reply.Busy] comes back.
     */
    suspend fun getInTurn(okHttpClient: OkHttpClient, path: String): Reply {
        while (!take(MAX_TOTAL_PER_MINUTE)) delay(waitMs())
        val request = Request.Builder()
            .url("$API/$path")
            .header("Accept", "application/json")
            .build()
        return withContext(Dispatchers.IO) {
            runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    when {
                        response.code == 429 -> {
                            fillMinute()
                            Reply.Busy
                        }
                        !response.isSuccessful -> Reply.Failed
                        else -> response.body?.string()?.let { Reply.Ok(it) } ?: Reply.Failed
                    }
                }
            }.getOrDefault(Reply.Failed)
        }
    }

    private fun take(max: Int = MAX_PER_MINUTE): Boolean = synchronized(times) {
        val now = System.currentTimeMillis()
        drop(now)
        if (times.size >= max) return@synchronized false
        times.addLast(now)
        true
    }

    /** How long until the oldest request of this minute is a minute old (at least a little, at most a few seconds). */
    private fun waitMs(): Long = synchronized(times) {
        val oldest = times.firstOrNull() ?: return@synchronized 250L
        (oldest + MINUTE_MS + 50L - System.currentTimeMillis()).coerceIn(250L, 5_000L)
    }

    private fun fillMinute() = synchronized(times) {
        val now = System.currentTimeMillis()
        while (times.size < MAX_TOTAL_PER_MINUTE) times.addLast(now)
    }

    private fun drop(now: Long) {
        while (times.isNotEmpty() && now - times.first() > MINUTE_MS) times.removeFirst()
    }
}
