package com.nuvio.tv.data.remote.rctvcore

import com.google.gson.stream.JsonReader
import com.nuvio.tv.domain.model.SportsEventStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class RctvCoreSportsJsonTest {

    private fun sample(): String =
        javaClass.classLoader!!.getResourceAsStream("rctv-core-scoreboards.json")!!.bufferedReader().readText()

    @Test
    fun `RCTV Core's answer becomes the app's own models`() {
        val answer = RctvCoreSportsJson.readScoreboards(JsonReader(StringReader(sample())))
        assertEquals(listOf("basketball:nba", "soccer:eng.1"), answer.results.keys.toList())
        assertEquals(setOf("hockey:nhl"), answer.failed)

        val nba = answer.results.getValue("basketball:nba")
        assertEquals("National Basketball Association", nba.league.name)
        assertEquals("Basketball", nba.league.sportName)
        assertEquals("https://a.espncdn.com/nba.png", nba.league.badgeUrl)
        assertEquals(4, nba.events.size)

        val game = nba.events[0]
        assertEquals("espn:basketball:nba:401", game.id)
        assertEquals("BOS @ LAL", game.name)
        assertEquals("Lakers", game.homeTeamName)
        assertEquals("13", game.homeTeamId)
        assertEquals(110, game.homeScore)
        assertEquals(99, game.awayScore)
        assertEquals(1793665800000L, game.startTimeMs) // 2026-11-03T00:30Z
        assertEquals(SportsEventStatus.FINISHED, game.status)
        assertEquals("Final", game.statusDetail)
        assertEquals("552583", game.homeTeamColor)
        assertEquals(listOf("Los Angeles Lakers", "Lakers"), game.homeTeamAliases)
        assertNull(game.leaderName)

        assertEquals(SportsEventStatus.LIVE, nba.events[1].status)
        // Left out on the server: the models' defaults.
        val noTime = nba.events[3]
        assertNull(noTime.startTimeMs)
        assertNull(noTime.homeScore)

        assertEquals(listOf("13", "2", "20", "9"), nba.teams.map { it.id })
        assertEquals("Lakers", nba.teams[0].name)
        assertEquals("basketball:nba", nba.teams[0].leagueId)
    }

    @Test
    fun `broken or unexpected parts are skipped, not fatal`() {
        val text = """{"results":[{"leagueId":"x:y","league":{"id":"x:y","name":"Y","sportName":"S"},
            "events":[{"id":"1","name":"A","status":"SOMETHING"},{"id":"2","name":"B","status":"LIVE","homeScore":"x","new":{"a":1}}],
            "teams":[{"name":"no id"}]},{"leagueId":"bad"}],"failed":"not a list"}"""
        val answer = RctvCoreSportsJson.readScoreboards(JsonReader(StringReader(text)))
        val feed = answer.results.getValue("x:y")
        assertEquals(listOf("2"), feed.events.map { it.id })
        assertNull(feed.events[0].homeScore)
        assertTrue(feed.teams.isEmpty())
        assertTrue(answer.failed.isEmpty())
        assertEquals(1, answer.results.size)
    }

    @Test
    fun `the server's switch`() {
        assertEquals(true, RctvCoreSportsJson.readSportsEnabled(JsonReader(StringReader("""{"sports":{"enabled":true,"scoreboards":true},"checkAgainS":300}"""))))
        assertEquals(false, RctvCoreSportsJson.readSportsEnabled(JsonReader(StringReader("""{"sports":{"enabled":false}}"""))))
        assertNull(RctvCoreSportsJson.readSportsEnabled(JsonReader(StringReader("""{"other":1}"""))))
    }
}
