package com.nuvio.tv.data.remote.espn

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * RCTV (PERF-01): the same league's scoreboard for the same days is read once for the pages that ask
 * for it within a minute; the results are the same as reading it again, and a failure isn't kept.
 */
class EspnScoreboardSharingTest {

    private val feeds = listOf(
        EspnSportsClient.Feed("football", "nfl", "Football", ""),
        EspnSportsClient.Feed("basketball", "nba", "Basketball", "")
    )

    private fun client(scoreboards: AtomicInteger, answer: (String) -> Int = { 200 }): EspnSportsClient {
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                if (path.endsWith("/scoreboard")) scoreboards.incrementAndGet()
                val code = answer(path)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                    .message(if (code == 200) "OK" else "Error")
                    .body(SCOREBOARD.toResponseBody("application/json".toMediaType())).build()
            }
            .build()
        return EspnSportsClient(http, mockk<Context>(relaxed = true))
    }

    @Test
    fun `a second page within a minute reuses the scoreboards and gets the same games`() {
        val reads = AtomicInteger()
        val espn = client(reads)
        val first = runBlocking { withTimeout(10_000) { espn.fetchAllFixtures(rangeDays = 6, feeds = feeds) } }
        val afterFirst = reads.get()
        val second = runBlocking { withTimeout(10_000) { espn.fetchAllFixtures(rangeDays = 6, feeds = feeds) } }

        assertEquals(2, afterFirst)
        assertEquals(afterFirst, reads.get())
        assertEquals(first.map { it.feed.leaguePath to it.events.map { e -> e.id } }, second.map { it.feed.leaguePath to it.events.map { e -> e.id } })
    }

    @Test
    fun `different days are separate reads, and clearing for memory reads again`() {
        val reads = AtomicInteger()
        val espn = client(reads)
        runBlocking { espn.fetchAllFixtures(rangeDays = 6, feeds = feeds) }
        runBlocking { espn.fetchAllFixtures(rangeDays = 7, feeds = feeds) }
        assertEquals(4, reads.get())
        EspnSportsClient.clearSharedRosters()
        runBlocking { espn.fetchAllFixtures(rangeDays = 6, feeds = feeds) }
        assertEquals(6, reads.get())
    }

    @Test
    fun `a league that failed is asked again next time`() {
        val reads = AtomicInteger()
        var nbaDown = true
        val espn = client(reads) { path -> if (nbaDown && "/basketball/nba/" in path) 503 else 200 }
        val first = runBlocking { espn.fetchAllFixtures(rangeDays = 6, feeds = feeds) }
        nbaDown = false
        val second = runBlocking { espn.fetchAllFixtures(rangeDays = 6, feeds = feeds) }

        assertEquals(listOf("nfl"), first.map { it.feed.leaguePath })
        assertEquals(setOf("nfl", "nba"), second.map { it.feed.leaguePath }.toSet())
    }

    private companion object {
        const val SCOREBOARD = """{"events":[{"id":"401","name":"A at B","date":"2026-10-28T00:00Z",
            "competitions":[{"competitors":[{"homeAway":"home","team":{"id":"1","displayName":"B"}},
            {"homeAway":"away","team":{"id":"2","displayName":"A"}}],"status":{"type":{"state":"pre"}}}]}]}"""
    }
}
