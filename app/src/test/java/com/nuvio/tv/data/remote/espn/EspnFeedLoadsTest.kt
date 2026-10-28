package com.nuvio.tv.data.remote.espn

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * RCTV-AUD-006 and RCTV-AUD-007, against a fake ESPN (an interceptor answering every request):
 * leaving a page stops its league loads, and a league's teams are read once for every page.
 */
class EspnFeedLoadsTest {

    private val feeds = listOf(
        EspnSportsClient.Feed("football", "nfl", "Football", ""),
        EspnSportsClient.Feed("basketball", "nba", "Basketball", ""),
        EspnSportsClient.Feed("hockey", "nhl", "Hockey", ""),
        EspnSportsClient.Feed("basketball", "wnba", "Basketball", ""),
        EspnSportsClient.Feed("soccer", "eng.1", "Soccer", ""),
        EspnSportsClient.Feed("soccer", "esp.1", "Soccer", "")
    )

    private val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** An ESPN client whose every request is answered by [answer] (after [beforeAnswer]). */
    private fun client(beforeAnswer: () -> Unit = {}, answer: (path: String) -> String = { "{}" }): EspnSportsClient {
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requested += request.url.encodedPath
                beforeAnswer()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(answer(request.url.encodedPath).toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        return EspnSportsClient(http, mockk<Context>(relaxed = true))
    }

    /** The leagues asked for ("football/nfl"), each once. */
    private fun leaguesRequested(): Set<String> = synchronized(requested) {
        requested.map { it.substringAfter("/sports/").substringBeforeLast("/") }.toSet()
    }

    @Test
    fun `leaving the page starts no league that had not started`() {
        val inFlight = CountDownLatch(3)
        val release = CountDownLatch(1)
        val espn = client(beforeAnswer = {
            inFlight.countDown()
            release.await(5, TimeUnit.SECONDS)
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val job = scope.launch { espn.fetchAllFixtures(rangeDays = 1, feeds = feeds) }
            // Three leagues load at once in the whole app; the other three wait.
            assertTrue("three leagues should be loading", inFlight.await(5, TimeUnit.SECONDS))
            job.cancel()
            release.countDown()
            runBlocking { withTimeout(5_000) { job.join() } }
            Thread.sleep(300)
            assertEquals("leagues asked for: ${leaguesRequested()}", 3, leaguesRequested().size)
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test
    fun `without leaving, every league is asked for`() {
        val espn = client()
        runBlocking { withTimeout(10_000) { espn.fetchAllFixtures(rangeDays = 1, feeds = feeds) } }
        assertEquals(feeds.map { "${it.sportPath}/${it.leaguePath}" }.toSet(), leaguesRequested())
    }

    @Test
    fun `a league's teams are read once for every page that asks`() {
        EspnSportsClient.clearSharedRosters()
        val espn = client(answer = { TEAMS_ANSWER })
        val nfl = feeds.first()

        val teamsPage = espn.fetchTeams(nfl)
        val search = espn.fetchTeams(nfl)

        assertEquals(listOf("Atlanta Falcons"), teamsPage.map { it.name })
        assertEquals(teamsPage, search)
        assertEquals(1, synchronized(requested) { requested.count { it.endsWith("/football/nfl/teams") } })

        // Dropped when the system asks for memory: read again.
        EspnSportsClient.clearSharedRosters()
        espn.fetchTeams(nfl)
        assertEquals(2, synchronized(requested) { requested.count { it.endsWith("/football/nfl/teams") } })
    }

    @Test
    fun `a league whose dated request fails is asked once more, without dates`() {
        val nfl = feeds.first()
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requested += request.url.toString()
                val failing = request.url.queryParameter("dates") != null
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (failing) 503 else 200)
                    .message(if (failing) "Unavailable" else "OK")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val espn = EspnSportsClient(http, mockk<Context>(relaxed = true))

        runBlocking { withTimeout(10_000) { espn.fetchAllFixtures(rangeDays = 1, feeds = listOf(nfl)) } }

        val asked = synchronized(requested) { requested.toList() }
        assertEquals("requests: $asked", 2, asked.size)
        assertTrue(asked[0].contains("dates="))
        assertTrue(!asked[1].contains("dates="))
    }

    @Test
    fun `leaving the page stops a request already waiting on ESPN`() {
        // A server that accepts the connection and never answers: the request waits on it.
        val server = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val accepted = CountDownLatch(1)
        val held = Collections.synchronizedList(mutableListOf<java.net.Socket>())
        val acceptor = Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                held += socket
                accepted.countDown()
            }
        }.apply { isDaemon = true; start() }
        val http = OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                requested += request.url.encodedPath
                // ESPN's address, sent to the silent local server instead.
                chain.proceed(
                    request.newBuilder()
                        .url(request.url.newBuilder().scheme("http").host("127.0.0.1").port(server.localPort).build())
                        .build()
                )
            }
            .build()
        val espn = EspnSportsClient(http, mockk<Context>(relaxed = true))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val job = scope.launch { espn.fetchAllFixtures(rangeDays = 1, feeds = listOf(feeds.first())) }
            assertTrue("the request should reach the server", accepted.await(5, TimeUnit.SECONDS))
            val cancelledAt = System.nanoTime()
            job.cancel()
            // Without the request being cancelled, this would wait for the 30 s read timeout.
            runBlocking { withTimeout(5_000) { job.join() } }
            val tookMs = (System.nanoTime() - cancelledAt) / 1_000_000
            assertTrue("took $tookMs ms to stop", tookMs < 5_000)
            Thread.sleep(200)
            // Not asked again without dates after the cancellation.
            assertEquals(1, synchronized(requested) { requested.size })
        } finally {
            scope.cancel()
            server.close()
            held.forEach { runCatching { it.close() } }
            acceptor.join(1_000)
        }
    }

    @Test
    fun `the ESPN client is given the validating HTTP client`() {
        // RCTV-AUD-021: not the `addonPermissive` client, which accepts any certificate and host.
        val qualifiers = EspnSportsClient::class.java.constructors
            .flatMap { constructor -> constructor.parameterAnnotations.flatMap { it.toList() } }
            .filterIsInstance<javax.inject.Named>()
            .map { it.value }
        assertTrue("ESPN client qualifiers: $qualifiers", "addonPermissive" !in qualifiers)
    }

    private companion object {
        const val TEAMS_ANSWER = """{"sports":[{"leagues":[{"teams":[{"team":{"id":"1","displayName":"Atlanta Falcons",""" +
            """"abbreviation":"ATL","color":"a71930","logos":[{"href":"https://a.espncdn.com/i/teamlogos/nfl/500/atl.png"}]}}]}]}]}"""
    }
}
