package com.nuvio.tv.ui.screens.livetv

import com.nuvio.tv.domain.model.LiveTvChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvCuratedGuideTest {

    private val list = """
        {"version":1,"lists":{
          "TV_SHOWS":[["USA: TNT","TNT"],["Tubi: Always Funny","Always Funny"]],
          "CHANNELS_24_7":[["24/7: 13 Reasons Why","24/7 · 13 Reasons Why"],["EN 24/7 | 13 Reasons Whyⓧ","24/7 · 13 Reasons Why"]],
          "SPORTS":[["USA: NFL Redzone FHD","NFL RedZone"]],
          "PUERTO_RICO":[["PRI: WAPA","WAPA"]]},
         "internalGroups":["USA | ESPN+","USA | NFL Game Pass"],
         "countries":{"FR | France":"FR","AR | Arabic":"ARABIC"}}
    """.trimIndent()

    private var next = 0
    private fun channel(name: String, group: String) =
        LiveTvChannel(id = "s:${next++}", name = name, streamUrl = "http://example.invalid/$next", groupTitle = group)

    private fun playlist(): List<LiveTvChannel> {
        // Enough of the list's names to be its provider's (at least 30 found).
        val filler = (1..30).map { channel("USA: TNT", "USA | Entertainment") }.drop(29)
        return filler + listOf(
            channel("USA: TNT FHD backup", "USA | Entertainment"),
            channel("USA: TNT West", "USA | Entertainment"),
            channel("Tubi: Always Funny", "USA | Tubi"),
            channel("24/7: 13 Reasons Why", "24/7 Drama"),
            channel("EN 24/7 | 13 Reasons Whyⓧ", "24/7 Drama"),
            channel("USA: NFL Redzone FHD", "USA | NFL Game Pass"),
            channel("USA: NFL Redzone", "USA | Sports"),
            channel("(12) Lakers vs Celtics", "USA | ESPN+"),
            channel("PRI: WAPA", "PRI | Puerto Rico"),
            channel("FR: TF1", "FR | France"),
            channel("AR: MBC 1", "AR | Arabic"),
            channel("USA: Some Channel Not Listed", "USA | Entertainment"),
            channel("Netflix: Something", "Netflix Series")
        )
    }

    @Test
    fun `the owner's list decides the Guide`() {
        LiveTvCuratedGuide.loadForTest(list)
        val all = playlist().let { it + List(29) { i -> channel("PRI: WAPA", "PRI | Puerto Rico").copy(id = "dup:$i") } }
        val applied = LiveTvCuratedGuide.apply(all)!!
        val shown = applied.visible.associateBy { it.name }

        // His names and categories; US and Puerto Rico.
        assertEquals("TV_SHOWS", shown.getValue("TNT").guideCategory)
        assertEquals("US", shown.getValue("TNT").country)
        assertEquals("PUERTO_RICO", shown.getValue("WAPA").guideCategory)
        assertEquals("PR", shown.getValue("WAPA").country)
        assertEquals("SPORTS", shown.getValue("NFL RedZone").guideCategory)
        // Another country: International, under its country.
        assertEquals("INTERNATIONAL", shown.getValue("FR: TF1").guideCategory)
        assertEquals("FR", shown.getValue("FR: TF1").country)
        assertEquals("ARABIC", shown.getValue("AR: MBC 1").country)
        // Not in his list, a streaming service's copy: not in the Guide at all.
        assertTrue("USA: Some Channel Not Listed" !in shown)
        assertTrue(applied.hidden.none { it.name == "USA: Some Channel Not Listed" || it.name == "Netflix: Something" })

        // One TNT, its own feed first; its backup and West feed are its versions (West last).
        assertEquals(1, applied.visible.count { it.name == "TNT" })
        val tnt = shown.getValue("TNT")
        val tntVersions = applied.hidden.filter { it.versionOf == tnt.id }
        assertTrue(tntVersions.any { it.groupTitle == "USA | Entertainment" })
        // A 24/7 show from two of the provider's lists: one channel.
        assertEquals(1, applied.visible.count { it.guideCategory == "CHANNELS_24_7" })
        assertEquals("24/7 · 13 Reasons Why", applied.visible.single { it.guideCategory == "CHANNELS_24_7" }.name)

        // Leagues and events: out of the Guide, kept for Sports (not as versions).
        val event = applied.hidden.single { it.groupTitle == "USA | ESPN+" }
        assertNull(event.versionOf)
        // The listed RedZone shows even though its group is a game pass; its other feed is a version.
        // (A league or event group's channel is always Sports' own, never a version.)
        assertEquals(1, applied.hidden.count { it.name == "NFL RedZone" && it.versionOf == shown.getValue("NFL RedZone").id })
    }

    @Test
    fun `the player turns to the next version and back around`() {
        LiveTvCuratedGuide.loadForTest(list)
        val all = playlist() + List(29) { i -> channel("PRI: WAPA", "PRI | Puerto Rico").copy(id = "dup:$i") }
        val applied = LiveTvCuratedGuide.apply(all)!!
        LiveTvChannelVersions.update(applied.visible, applied.hidden)
        val tnt = applied.visible.single { it.name == "TNT" }
        val versions = LiveTvChannelVersions.of(tnt.streamUrl)
        assertTrue(versions.size >= 3)
        assertEquals(tnt.streamUrl, versions.first())
        var url = tnt.streamUrl
        repeat(versions.size) { url = LiveTvChannelVersions.next(url)!! }
        assertEquals(tnt.streamUrl, url)
        assertEquals(tnt.streamUrl, LiveTvChannelVersions.channelKey(versions.last()))
        // A channel with a single feed has nothing to turn to.
        assertNull(LiveTvChannelVersions.next(applied.visible.single { it.name == "Always Funny" }.streamUrl))
    }

    @Test
    fun `another provider's playlist is sorted the usual way`() {
        LiveTvCuratedGuide.loadForTest(list)
        assertNull(LiveTvCuratedGuide.apply(listOf(channel("FR: TF1", "FR | France"), channel("USA: TNT", "USA | Entertainment"))))
    }

    /** RCTV: the owner's guide of 14-nov-2026 (his categories, each row's territory, local stations inside). */
    private val ownersGuide = """
        {"version":2,"lists":{
          "ENTERTAINMENT":[["NY | New York ABC 7 WABC","ABC","US"],["USA: TNT","TNT","US"],["PRI: WAPA","WAPA PR","PR"]],
          "SPORTS":[["USA: NFL Redzone FHD","NFL RedZone","US"]]},
         "internal":[["AK | ANCHORAGE ABC13 KYUR","US"],["PRI: CANAL 34","PR"]],
         "internalGroups":["USA | ESPN+"],
         "countries":{"FR | France":"FR"}}
    """.trimIndent()

    @Test
    fun `the owner's new guide has his categories, no 24-7, and its local stations inside`() {
        LiveTvCuratedGuide.loadForTest(ownersGuide)
        val filler = (1..30).map { channel("USA: TNT", "USA | Entertainment") }
        val applied = LiveTvCuratedGuide.apply(
            filler + listOf(
                channel("NY | New York ABC 7 WABC", "USA | Locals"),
                channel("PRI: WAPA", "PRI | Puerto Rico"),
                channel("USA: NFL Redzone FHD", "USA | NFL Game Pass"),
                channel("24/7: 13 Reasons Why", "24/7 Drama"),
                channel("(12) Lakers vs Celtics", "USA | ESPN+"),
                channel("AK | ANCHORAGE ABC13 KYUR", "USA | Locals"),
                channel("PRI: CANAL 34", "PRI | Puerto Rico"),
                channel("FR: TF1", "FR | France")
            )
        )!!
        assertEquals(listOf("ABC", "TNT", "WAPA PR", "NFL RedZone", "FR: TF1"), applied.visible.map { it.name })
        val shown = applied.visible.associateBy { it.name }
        assertEquals("ENTERTAINMENT", shown.getValue("ABC").guideCategory)
        assertEquals("ENTERTAINMENT", shown.getValue("WAPA PR").guideCategory)
        assertEquals("PR", shown.getValue("WAPA PR").country)
        assertEquals("US", shown.getValue("TNT").country)
        assertEquals("SPORTS", shown.getValue("NFL RedZone").guideCategory)
        assertEquals("INTERNATIONAL", shown.getValue("FR: TF1").guideCategory)
        // 24/7 is gone: neither in the Guide nor inside.
        assertTrue((applied.visible + applied.hidden).none { "24/7" in it.name })
        // The local stations are inside, as stations of their country (Sports and the networks' pages).
        val anchorage = applied.hidden.single { it.name == "AK | ANCHORAGE ABC13 KYUR" }
        assertEquals("LOCAL", anchorage.guideCategory)
        assertEquals("US", anchorage.country)
        assertNull(anchorage.versionOf)
        assertEquals("PR", applied.hidden.single { it.name == "PRI: CANAL 34" }.country)
        // Games and events stay inside for Sports.
        assertTrue(applied.hidden.any { it.name == "(12) Lakers vs Celtics" && it.versionOf == null })
    }

    @Test
    fun `versions of a channel share one key`() {
        assertEquals("tnt", LiveTvCuratedGuide.versionKey("USA: TNT FHD backup"))
        assertEquals("tnt", LiveTvCuratedGuide.versionKey("USA: TNT West"))
        assertEquals("fox news", LiveTvCuratedGuide.versionKey("USA: FOX News Channel"))
        assertEquals("13 reasons why", LiveTvCuratedGuide.versionKey("EN 24/7 | 13 Reasons Whyⓧ"))
        assertEquals("13 reasons why", LiveTvCuratedGuide.versionKey("24/7 · 13 Reasons Why"))
        assertEquals("amc plus", LiveTvCuratedGuide.versionKey("US AMC Plus").removePrefix("us "))
    }
}
