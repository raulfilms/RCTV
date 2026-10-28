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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * What the Sports pages got from the ESPN client before the remediation, pinned so the changes
 * (cancellation, shared rosters, the validating client) can't change it: every league's result in
 * priority order, each reported as it arrives, a failing league not holding the others back, and a
 * roster's teams as the pages show them. Uses only API the client had before (runs on both).
 */
class EspnRegressionTest {

    private val feeds = listOf(
        EspnSportsClient.Feed("football", "nfl", "Football", ""),
        EspnSportsClient.Feed("basketball", "nba", "Basketball", ""),
        EspnSportsClient.Feed("soccer", "esp.1", "Soccer", ""),
        EspnSportsClient.Feed("hockey", "nhl", "Hockey", "")
    )

    private fun client(answer: (path: String, query: String?) -> Pair<Int, String>): EspnSportsClient {
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val (code, body) = answer(request.url.encodedPath, request.url.encodedQuery)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "Error")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        return EspnSportsClient(http, mockk<Context>(relaxed = true))
    }

    @Test
    fun `every league answers, in priority order, each reported once as it arrives`() {
        val espn = client { _, _ -> 200 to "{}" }
        val reported = Collections.synchronizedList(mutableListOf<String>())

        val results = runBlocking {
            withTimeout(10_000) {
                espn.fetchAllFixtures(
                    rangeDays = 1,
                    feeds = feeds,
                    firstLeagueIds = setOf("soccer:esp.1"),
                    onResult = { reported += it.feed.leaguePath }
                )
            }
        }

        // The person's league first, then the headline ones (NFL, NBA, NHL), as before.
        assertEquals(listOf("esp.1", "nfl", "nba", "nhl"), results.map { it.feed.leaguePath })
        assertEquals(feeds.map { it.leaguePath }.toSet(), reported.toSet())
        assertEquals(feeds.size, reported.size)
    }

    @Test
    fun `a league that can't load doesn't hold the others back`() {
        val espn = client { path, _ -> if ("/basketball/nba/" in path) 503 to "" else 200 to "{}" }

        val results = runBlocking { withTimeout(10_000) { espn.fetchAllFixtures(rangeDays = 1, feeds = feeds) } }

        assertEquals(listOf("nfl", "esp.1", "nhl").toSet(), results.map { it.feed.leaguePath }.toSet())
        assertTrue(results.none { it.feed.leaguePath == "nba" })
    }

    @Test
    fun `a roster's teams are the ones the pages show, field by field`() {
        val espn = client { path, _ ->
            if (path.endsWith("/basketball/nba/teams")) 200 to NBA_TEAMS else 200 to "{}"
        }

        val teams = espn.fetchTeams(feeds[1])

        assertEquals(2, teams.size)
        val lakers = teams.first()
        assertEquals("13", lakers.id)
        assertEquals("Los Angeles Lakers", lakers.name)
        assertEquals("LAL", lakers.shortName)
        assertEquals("basketball:nba", lakers.leagueId)
        assertEquals("Basketball", lakers.sportName)
        assertEquals("https://a.espncdn.com/i/teamlogos/nba/500/lal.png", lakers.badgeUrl)
        assertEquals("552583", lakers.color)
        // No logo field: the first of "logos"; a colour that isn't six digits isn't kept.
        assertEquals("https://a.espncdn.com/i/teamlogos/nba/500/bos.png", teams[1].badgeUrl)
        assertEquals(null, teams[1].color)
    }

    private companion object {
        val NBA_TEAMS = """{"sports":[{"leagues":[{"teams":[
            {"team":{"id":"13","displayName":"Los Angeles Lakers","abbreviation":"LAL","color":"#552583","logo":"https://a.espncdn.com/i/teamlogos/nba/500/lal.png"}},
            {"team":{"id":"2","displayName":"Boston Celtics","abbreviation":"BOS","color":"0a7","logos":[{"href":"https://a.espncdn.com/i/teamlogos/nba/500/bos.png"}]}}
        ]}]}]}"""
    }
}
