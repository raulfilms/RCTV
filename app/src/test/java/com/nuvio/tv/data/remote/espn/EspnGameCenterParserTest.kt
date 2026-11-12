package com.nuvio.tv.data.remote.espn

import com.nuvio.tv.domain.model.SportsEventStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RCTV (game center): ESPN's game summary read into the panel's cards (an NFL game, as the owner's photos). */
class EspnGameCenterParserTest {

    private val nfl = """
    {
      "header": { "competitions": [ {
        "status": { "period": 2, "displayClock": "0:03",
          "type": { "name": "STATUS_IN_PROGRESS", "state": "in", "shortDetail": "0:03 - 2nd" } },
        "competitors": [
          { "homeAway": "home", "score": "7",
            "team": { "id": "18", "name": "Saints", "shortDisplayName": "Saints", "abbreviation": "NO", "color": "d3bc8d",
                      "logos": [ { "href": "https://a.espncdn.com/no.png" } ] },
            "linescores": [ { "displayValue": "0" }, { "displayValue": "7" } ],
            "record": [ { "type": "total", "summary": "1-3" } ] },
          { "homeAway": "away", "score": "6",
            "team": { "id": "16", "name": "Vikings", "shortDisplayName": "Vikings", "abbreviation": "MIN", "color": "4f2683",
                      "logos": [ { "href": "https://a.espncdn.com/min.png" } ] },
            "linescores": [ { "displayValue": "3" }, { "displayValue": "3" } ],
            "record": [ { "type": "total", "summary": "4-0" } ] }
        ] } ] },
      "boxscore": {
        "teams": [
          { "homeAway": "away", "team": { "id": "16" }, "statistics": [
            { "name": "possessionTime", "label": "Possession", "displayValue": "18:37" },
            { "name": "turnovers", "label": "Turnovers", "displayValue": "1" },
            { "name": "thirdDownEff", "label": "3rd down efficiency", "displayValue": "3-8" },
            { "name": "netPassingYards", "label": "Passing", "displayValue": "143" },
            { "name": "rushingYards", "label": "Rushing", "displayValue": "41" } ] },
          { "homeAway": "home", "team": { "id": "18" }, "statistics": [
            { "name": "possessionTime", "label": "Possession", "displayValue": "11:20" },
            { "name": "turnovers", "label": "Turnovers", "displayValue": "0" },
            { "name": "thirdDownEff", "label": "3rd down efficiency", "displayValue": "1-6" },
            { "name": "netPassingYards", "label": "Passing", "displayValue": "37" },
            { "name": "rushingYards", "label": "Rushing", "displayValue": "47" } ] }
        ],
        "players": [
          { "team": { "id": "16" }, "statistics": [
            { "name": "passing", "labels": ["C/ATT","YDS","AVG","TD","INT","SACKS","QBR","RTG"],
              "athletes": [ { "athlete": { "id": "1", "displayName": "Kyler Murray", "shortName": "K. Murray", "jersey": "1",
                                            "headshot": { "href": "https://a.espncdn.com/km.png" } },
                              "stats": ["15/22","143","6.5","0","0","1-6","50.1","88.2"] } ] },
            { "name": "rushing", "labels": ["CAR","YDS","AVG","TD","LONG"],
              "athletes": [ { "athlete": { "id": "33", "displayName": "Aaron Jones", "shortName": "A. Jones", "jersey": "33" },
                              "stats": ["10","36","3.6","0","9"] },
                            { "athlete": { "id": "1", "displayName": "Kyler Murray", "shortName": "K. Murray", "jersey": "1" },
                              "stats": ["1","5","5.0","0","5"] } ] },
            { "name": "receiving", "labels": ["REC","YDS","AVG","TD","LONG","TGTS"],
              "athletes": [ { "athlete": { "id": "18", "displayName": "Justin Jefferson", "shortName": "J. Jefferson", "jersey": "18" },
                              "stats": ["4","56","14.0","0","22","6"] } ] } ] },
          { "team": { "id": "18" }, "statistics": [
            { "name": "passing", "labels": ["C/ATT","YDS","AVG","TD","INT","SACKS","QBR","RTG"],
              "athletes": [ { "athlete": { "id": "6", "displayName": "Tyler Shough", "shortName": "T. Shough", "jersey": "6" },
                              "stats": ["8/14","37","2.6","0","0","2-10","20.0","60.1"] } ] },
            { "name": "rushing", "labels": ["CAR","YDS","AVG","TD","LONG"],
              "athletes": [ { "athlete": { "id": "5", "displayName": "Kendre Miller", "shortName": "K. Miller", "jersey": "5" },
                              "stats": ["8","27","3.4","1","8"] },
                            { "athlete": { "id": "41", "displayName": "Alvin Kamara", "shortName": "A. Kamara", "jersey": "41" },
                              "stats": ["2","10","5.0","0","6"] } ] } ] }
        ]
      },
      "scoringPlays": [
        { "type": { "text": "Field Goal" }, "text": "Will Reichard 45 Yd Field Goal", "awayScore": 3, "homeScore": 0,
          "period": { "number": 1 }, "clock": { "displayValue": "4:12" }, "team": { "id": "16" } },
        { "type": { "text": "Rushing Touchdown" }, "text": "Kendre Miller 2 Yd Rush (Blake Grupe Kick)", "awayScore": 3, "homeScore": 7,
          "period": { "number": 2 }, "clock": { "displayValue": "9:01" }, "team": { "id": "18" } }
      ]
    }
    """.trimIndent()

    private fun parse() = EspnGameCenterParser.parse(JSONObject(nfl), "espn:football:nfl:401", "football", "nfl")

    @Test
    fun `the header reads as YouTube TV's scoreboard`() {
        val center = parse()
        assertNotNull(center)
        center!!
        assertEquals(SportsEventStatus.LIVE, center.status)
        assertEquals("Q2", center.periodLabel)
        assertEquals("0:03", center.clock)
        assertEquals("MIN", center.left.abbreviation) // away on the left
        assertEquals("6", center.left.score)
        assertEquals("NO", center.right.abbreviation)
        assertEquals("4-0", center.left.record)
        assertEquals(listOf("1ST", "2ND", "3RD", "4TH"), center.periodLabels)
        assertEquals(listOf("3", "3"), center.left.periodScores)
    }

    @Test
    fun `team stats with the photo's labels and bars`() {
        val stats = parse()!!.teamStats
        assertEquals(listOf("Time of possession", "Turnovers lost", "Third down percentage", "Passing yards", "Rushing yards"), stats.map { it.label })
        val third = stats[2]
        assertEquals("37.5", third.leftValue)
        assertEquals("16.7", third.rightValue)
        assertEquals(1.0, stats[1].leftShare.toDouble(), 0.001)
        assertTrue(stats[0].leftShare > 0.6f)
    }

    @Test
    fun `leaders and box scores`() {
        val center = parse()!!
        val passing = center.leaders.first { it.category == "Passing" }
        assertEquals("Kyler Murray", passing.name)
        assertEquals("Vikings", passing.teamName)
        assertEquals(listOf("Cmp" to "15", "Yds" to "143", "TD" to "0", "Int" to "0"), passing.columns)
        assertEquals("Aaron Jones", center.leaders.first { it.category == "Rushing" }.name)
        val vikings = center.boxScores.first()
        assertEquals("Vikings", vikings.team.name)
        assertEquals(listOf("Passing", "Rushing", "Receiving"), vikings.sections.map { it.title })
        assertEquals(listOf("A. Jones", "K. Murray"), vikings.sections[1].rows.map { it.name })
        assertEquals("33", vikings.sections[1].rows[0].jersey)
    }

    @Test
    fun `key plays, newest first, with the score`() {
        val plays = parse()!!.keyPlays
        assertEquals(2, plays.size)
        assertEquals("Rushing Touchdown", plays[0].title)
        assertEquals("Q2 9:01", plays[0].time)
        assertEquals("MIN 3 - NO 7", plays[0].score)
        assertEquals("https://a.espncdn.com/no.png", plays[0].teamLogoUrl)
    }

    @Test
    fun `a summary without teams gives nothing`() {
        assertNull(EspnGameCenterParser.parse(JSONObject("{}"), "x", "football", "nfl"))
    }

    @Test
    fun `period columns for each sport`() {
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"), EspnGameCenterParser.periodLabelsOf("baseball", "mlb", 10))
        assertEquals(listOf("1ST", "2ND", "3RD", "OT"), EspnGameCenterParser.periodLabelsOf("hockey", "nhl", 4))
        assertEquals(listOf("1ST", "2ND"), EspnGameCenterParser.periodLabelsOf("basketball", "mens-college-basketball", 0))
    }
}
