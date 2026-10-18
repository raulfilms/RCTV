package com.nuvio.tv.data.remote.sportsdb

import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * TheSportsDB's API with its free key: 30 requests a minute for the whole app. Every source that
 * reads it (PPV Events, the CFL) asks here, so together they stay under it.
 */
internal object SportsDbRequests {

    const val API = "https://www.thesportsdb.com/api/v1/json/123"

    /** Leaves a few of the key's 30 a minute for the rest of the app (players' photos). */
    private const val MAX_PER_MINUTE = 24
    private const val MINUTE_MS = 60_000L

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

    private fun take(): Boolean = synchronized(times) {
        val now = System.currentTimeMillis()
        drop(now)
        if (times.size >= MAX_PER_MINUTE) return@synchronized false
        times.addLast(now)
        true
    }

    private fun fillMinute() = synchronized(times) {
        val now = System.currentTimeMillis()
        while (times.size < MAX_PER_MINUTE) times.addLast(now)
    }

    private fun drop(now: Long) {
        while (times.isNotEmpty() && now - times.first() > MINUTE_MS) times.removeFirst()
    }
}
