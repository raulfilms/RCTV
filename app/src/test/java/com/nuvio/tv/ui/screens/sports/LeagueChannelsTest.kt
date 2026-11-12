package com.nuvio.tv.ui.screens.sports

import com.nuvio.tv.domain.model.LiveTvChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RCTV (league page): a league's official channels and replay channels found in an IPTV lineup. */
class LeagueChannelsTest {

    private var next = 0
    private fun ch(name: String, group: String? = null, country: String? = null) =
        LiveTvChannel(id = "c${next++}", name = name, streamUrl = "", groupTitle = group, country = country)

    private val lineup = listOf(
        ch("De: RAN NFL NETWORK HD", "DE | Sports"),
        ch("CA: NFL Network", "CA | Sports"),
        ch("USA: NFL Network", "USA | Sports", "US"),
        ch("USA: NFL Redzone", "USA | Sports", "US"),
        ch("USA: NFL Channel", "USA | Sports", "US"),
        ch("USA: NBA TV", "USA | Sports", "US"),
        ch("WNBA TV", "USA | WNBA"),
        ch("USA: ESPN SEC Network", "USA | Sports", "US"),
        ch("USA: ACC Network", "USA | Sports", "US"),
        ch("Es: LALIGA TV HYPERMOTION", "ES | Deportes"),
        ch("NFL Replay 2 ᴴᴰ", "NFL | Replay"),
        ch("NFL Replay 10 ᴴᴰ", "NFL | Replay"),
        ch("NFL Replay 1 ᴴᴰ", "NFL | Replay"),
        ch("NFL Replay Highlights ᴴᴰ", "NFL | Replay"),
        ch("NFL Replay info ᴴᴰ", "NFL | Replay"),
        ch("NBA Replay 1 ᴴᴰ", "NBA | Replay")
    )

    @Test
    fun `NFL channels, the US ones, in the league's order`() {
        val found = LeagueChannels.official("football:nfl", lineup)
        assertEquals(listOf("NFL RedZone", "NFL Network", "NFL Channel"), found.map { it.title })
        assertEquals("USA: NFL Network", found[1].channel.name)
    }

    @Test
    fun `NBA TV isn't WNBA TV, and a league with none has none`() {
        assertEquals(listOf("USA: NBA TV"), LeagueChannels.official("basketball:nba", lineup).map { it.channel.name })
        assertTrue(LeagueChannels.official("basketball:wnba", lineup).isEmpty())
        assertFalse(LeagueChannels.hasOfficialChannels("soccer:mex.1"))
    }

    @Test
    fun `college conference networks and LaLiga 2 isn't LaLiga`() {
        assertEquals(listOf("SEC Network", "ACC Network"), LeagueChannels.official("football:college-football", lineup).map { it.title })
        assertTrue(LeagueChannels.official("soccer:esp.1", lineup).isEmpty())
    }

    @Test
    fun `replays in order, highlights last, no info channel`() {
        val replays = LeagueChannels.replays("football:nfl", lineup).map { it.title }
        assertEquals(listOf("NFL Replay 1", "NFL Replay 2", "NFL Replay 10", "NFL Replay Highlights"), replays)
        assertEquals(listOf("NBA Replay 1"), LeagueChannels.replays("basketball:nba", lineup).map { it.title })
        assertTrue(LeagueChannels.replays("soccer:eng.1", lineup).isEmpty())
    }

    @Test
    fun `names shown without country tag or quality marks`() {
        assertEquals("NFL Redzone", LeagueChannels.displayName("USA: NFL Redzone ᴴᴰ"))
        assertEquals("NFL Replay 3", LeagueChannels.displayName("NFL Replay 3 ᴴᴰ"))
    }
}

/** RCTV (league page): moving through a football league's weeks. */
class FootballWeeksTest {
    @Test
    fun `weeks go on into the playoffs and back`() {
        assertEquals(4 to 2, FootballWeeks.step("nfl", 3, 2, 1))
        assertEquals(1 to 3, FootballWeeks.step("nfl", 18, 2, 1))
        assertEquals(18 to 2, FootballWeeks.step("nfl", 1, 3, -1))
        assertEquals(5 to 3, FootballWeeks.step("nfl", 5, 3, 1))
        assertEquals(4 to 1, FootballWeeks.step("nfl", 1, 2, -1))
        assertEquals(1 to 1, FootballWeeks.step("nfl", 1, 1, -1))
        assertEquals(1 to 3, FootballWeeks.step("college-football", 15, 2, 1))
    }
}
