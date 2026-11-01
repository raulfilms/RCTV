package com.nuvio.tv.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RctvServersTest {

    @Test
    fun espnGoesToItsServerAndRestsAfterAFailure() {
        val scores = "https://site.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard?dates=20261101"
        val roster = "https://site.api.espn.com/apis/site/v2/sports/basketball/nba/teams/2/roster"
        val standings = "https://site.api.espn.com/apis/v2/sports/basketball/nba/standings"
        assertTrue(RctvServers.espn(scores, 0)!!.startsWith(RctvServers.DATA + "/espn?u=https%3A%2F%2Fsite.api.espn.com"))
        assertTrue(RctvServers.espn(roster, 0)!!.startsWith(RctvServers.LEAGUES))
        assertTrue(RctvServers.espn(standings, 0)!!.startsWith(RctvServers.LEAGUES))
        assertNull(RctvServers.espn("https://en.wikipedia.org/api/rest_v1/page/summary/X", 0))
        RctvServers.failed(RctvServers.LEAGUES + "/espn?u=x", 1_000)
        assertNull(RctvServers.espn(roster, 30_000))
        assertTrue(RctvServers.espn(scores, 30_000) != null)
        assertTrue(RctvServers.espn(roster, 61_001) != null)
    }

    @Test
    fun imagesShrunkWhereTheyMayBe() {
        assertEquals(
            "https://a.espncdn.com/combiner/i?img=%2Fi%2Fteamlogos%2Fnba%2F500%2Fbos.png&w=192&h=192",
            RctvServers.shrunkImage("https://a.espncdn.com/i/teamlogos/nba/500/bos.png", 180, 120, nowMs = 0)
        )
        assertEquals(
            RctvServers.IMAGES + "/?url=https%3A%2F%2Fr2.thesportsdb.com%2Fimages%2Fa.png&w=512&h=512",
            RctvServers.shrunkImage("https://r2.thesportsdb.com/images/a.png", 0, 0, nowMs = 0)
        )
        // A poster or any other site: as it is, unless it's a channel logo.
        assertNull(RctvServers.shrunkImage("https://image.tmdb.org/t/p/w500/x.jpg", 180, 270, nowMs = 0))
        val logo = RctvServers.channelLogo("https://logos.example.com/espn.png")!!
        assertTrue(logo.startsWith(RctvServers.SHRINK_MARK))
        assertTrue(RctvServers.shrunkImage(logo.removePrefix(RctvServers.SHRINK_MARK), 100, 60, forced = true, nowMs = 0)!!.endsWith("&w=128&h=128"))
        // Never sent: http, a login in the address, a password parameter; bigger than 512 stays as it is.
        assertEquals("http://iptv.example/logo.png", RctvServers.channelLogo("http://iptv.example/logo.png"))
        assertEquals("https://u:p@x.com/l.png", RctvServers.channelLogo("https://u:p@x.com/l.png"))
        assertEquals("https://x.com/l.png?username=a&password=b", RctvServers.channelLogo("https://x.com/l.png?username=a&password=b"))
        assertNull(RctvServers.shrunkImage("https://flagcdn.com/w320/us.png", 900, 600, nowMs = 0))
    }
}
