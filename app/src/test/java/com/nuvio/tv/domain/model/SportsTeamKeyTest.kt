package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsTeamKeyTest {
    private val knicks = SportsTeam(id = "18", name = "Knicks", leagueId = "basketball:nba")
    private val sanLorenzo = SportsTeam(id = "18", name = "San Lorenzo", leagueId = "soccer:arg.1")
    private val patriots = SportsTeam(id = "17", name = "Patriots", leagueId = "football:nfl")
    private val rosario = SportsTeam(id = "17", name = "Rosario Central", leagueId = "soccer:arg.1")
    private val miami = SportsTeam(id = "20232", name = "Inter Miami CF", leagueId = "soccer:usa.1")

    @Test
    fun `the same number in another league is another team`() {
        val myTeams = setOf(SportsTeamKey.of(knicks), SportsTeamKey.of(patriots), SportsTeamKey.of(miami))
        assertTrue(myTeams.hasTeam(knicks))
        assertTrue(myTeams.hasTeam(patriots))
        assertFalse(myTeams.hasTeam(sanLorenzo))
        assertFalse(myTeams.hasTeam(rosario))
    }

    @Test
    fun `a soccer team is the same in every competition`() {
        val myTeams = setOf(SportsTeamKey.of(miami))
        assertTrue(myTeams.hasTeam("soccer:concacaf.champions", "20232"))
        assertTrue(myTeams.hasTeam("soccer:usa.1", "20232"))
    }

    @Test
    fun `My Teams saved with the number alone take the Sports page's first team`() {
        val legacy = setOf("18", "17", "20232")
        // The person's leagues come first on the Sports page.
        val resolved = SportsTeamKey.legacyResolution(legacy, listOf(knicks, patriots, miami, sanLorenzo, rosario))
        assertEquals(
            mapOf("18" to "basketball:nba/18", "17" to "football:nfl/17", "20232" to "soccer/20232"),
            resolved
        )
        assertTrue(SportsTeamKey.legacyResolution(setOf("basketball:nba/18"), listOf(knicks)).isEmpty())
    }

    @Test
    fun `numbers alone still match until they're named`() {
        assertTrue(setOf("18").hasTeam(knicks))
        assertEquals("18", SportsTeamKey.teamIdOf("basketball:nba/18"))
        assertTrue(SportsTeamKey.isLegacy("18"))
        assertFalse(SportsTeamKey.isLegacy("soccer/18"))
    }
}
