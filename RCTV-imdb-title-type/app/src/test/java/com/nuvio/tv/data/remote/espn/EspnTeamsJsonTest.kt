package com.nuvio.tv.data.remote.espn

import com.google.gson.stream.JsonReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class EspnTeamsJsonTest {

    private fun reader(json: String) = JsonReader(StringReader(json)).apply { isLenient = true }

    @Test
    fun `reads the first league's teams and only their few fields`() {
        val json = """{"sports":[{"leagues":[{"teams":[
            {"team":{"id":"22","abbreviation":"ARI","displayName":"Arizona Cardinals","name":"Cardinals","color":"a4113e",
              "logos":[{"href":"https://a/ari.png","rel":["full","default"]},{"href":"https://a/dark.png"}],
              "links":[{"href":"https://espn/ari","rel":["clubhouse"]}]}},
            {"team":{"id":1,"displayName":"","name":"Falcons","color":null}},
            {"team":{"displayName":"No id"}}
          ]},{"teams":[{"team":{"id":"9"}}]}]}]}"""
        val teams = EspnTeamsJson.readTeams(reader(json))
        assertEquals(listOf("22", "1"), teams.map { it.id })
        assertEquals("Arizona Cardinals", teams[0].displayName)
        assertEquals("https://a/ari.png", teams[0].logo)
        assertEquals("a4113e", teams[0].color)
        assertNull(teams[1].displayName)
        assertEquals("Falcons", teams[1].name)
        assertNull(teams[1].color)
    }

    @Test
    fun `reads the groups with their teams and the groups in them`() {
        val json = """{"groups":[
            {"name":"NCAA Division I","children":[{"name":"FBS (I-A)","children":[
              {"name":"SEC","teams":[{"id":"333","displayName":"Alabama","logo":"https://l/333.png"},{"id":"57","name":"Florida","logos":[{"href":"https://l/57.png"}]}]}]}]},
            {"name":"Division II/III","children":[{"name":"NCAA Division II","teams":[{"id":"2","displayName":"D2"}]}]}
          ]}"""
        val groups = EspnTeamsJson.readGroups(reader(json))
        assertEquals(2, groups.size)
        val sec = groups[0].children[0].children[0]
        assertEquals("SEC", sec.name)
        assertEquals(listOf("https://l/333.png", "https://l/57.png"), sec.teams.map { it.logo })
        assertEquals("2", groups[1].children[0].teams[0].id)
    }

    @Test
    fun `an answer that isn't an object has no teams`() {
        assertTrue(EspnTeamsJson.readTeams(reader("[1,2]")).isEmpty())
        assertTrue(EspnTeamsJson.readGroups(reader("\"no\"")).isEmpty())
    }
}
