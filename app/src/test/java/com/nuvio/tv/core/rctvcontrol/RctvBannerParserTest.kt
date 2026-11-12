package com.nuvio.tv.core.rctvcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RctvBannerParserTest {

    private val collectionId = "0ddbb153-23bc-4080-934a-127a6e5e8a35"

    private fun banner(id: String, action: String, extra: String = "") =
        """{"id":"$id","title":"NAVIDAD","subtitle":"Las mejores","button":"","note":"","image_url":"https://x.supabase.co/storage/v1/object/public/rctv-media/banners/a.jpg","after":"home_new_releases","kids":false,"starts_at":null,"ends_at":null,"action":$action$extra}"""

    private val collectionsJson = """[
        {"id":"$collectionId","name":"Navidad","description":"","mode":"manual","items":[
            {"type":"movie","tmdb_id":771,"title":"Home Alone","year":1990,"poster_path":"/onTSipZ8R3bliBdKfPtsDuHTdlL.jpg","backdrop_path":null},
            {"type":"series","tmdb_id":0,"title":"Bad id","year":null,"poster_path":null,"backdrop_path":null}
        ],"theme":{"keywords":[],"media":"all","sort":"popular"}},
        {"id":"c2","name":"Halloween","description":"","mode":"theme","items":[],"theme":{"keywords":[{"id":3335,"name":"halloween"}],"media":"movie","sort":"newest"}}
    ]"""

    @Test
    fun `every kind of banner is read`() {
        val json = "[" + listOf(
            banner("b1", """{"type":"collection","collection_id":"$collectionId"}"""),
            banner("b2", """{"type":"game","event_id":"espn:basketball:nba:401704123","league_id":"basketball:nba","league_name":"NBA","sport_name":"Basketball","name":"Celtics at Knicks","start":"2026-12-25T17:00:00.000Z","home":{"id":"18","name":"New York Knicks","logo":"https://a.espncdn.com/ny.png"},"away":{"id":"2","name":"Boston Celtics","logo":null}}"""),
            banner("b3", """{"type":"team","league_id":"football:nfl","team_id":"17","name":"New England Patriots","logo":null,"color":"002244"}"""),
            banner("b4", """{"type":"league","league_id":"mma:ufc","name":"UFC","sport_name":"MMA","logo":null}"""),
            banner("b5", """{"type":"channel","channel":"ESPN"}"""),
            banner("bad1", """{"type":"game","event_id":"401","league_id":"basketball:nba","name":"x"}"""),
            banner("bad2", """{"type":"rocket"}""")
        ).joinToString(",") + "]"
        val banners = RctvBannerParser.banners(json)!!
        assertEquals(listOf("b1", "b2", "b3", "b4", "b5"), banners.map { it.id })
        val game = banners[1].action as RctvBannerAction.Game
        assertEquals("18", game.home?.id)
        assertEquals(null, game.away?.logoUrl)
        assertEquals(1_798_218_000_000L, game.startMs)
        assertEquals("002244", (banners[2].action as RctvBannerAction.Team).color)
        assertEquals("ESPN", (banners[4].action as RctvBannerAction.Channel).name)
        assertNull(RctvBannerParser.banners("not json"))
    }

    @Test
    fun `collections keep good titles and read themes`() {
        val collections = RctvBannerParser.collections(collectionsJson)!!
        assertEquals(2, collections.size)
        assertEquals(listOf(771), collections[0].items.map { it.tmdbId })
        assertNull(collections[0].theme)
        val theme = collections[1].theme!!
        assertEquals(listOf(3335), theme.keywordIds)
        assertEquals(RctvThemeMedia.MOVIES, theme.media)
        assertEquals(RctvThemeSort.NEWEST, theme.sort)
    }

    @Test
    fun `who sees which banner`() {
        val now = 1_800_000_000_000L
        val banners = RctvBannerParser.banners(
            "[" + listOf(
                banner("collection", """{"type":"collection","collection_id":"$collectionId"}"""),
                banner("kidsCollection", """{"type":"collection","collection_id":"$collectionId"}""").replace("\"kids\":false", "\"kids\":true"),
                banner("missingCollection", """{"type":"collection","collection_id":"gone"}"""),
                banner("channel", """{"type":"channel","channel":"ESPN"}"""),
                banner("ended", """{"type":"channel","channel":"ESPN"}""").replace("\"ends_at\":null", "\"ends_at\":\"2020-01-01T00:00:00Z\""),
                banner("later", """{"type":"channel","channel":"ESPN"}""").replace("\"starts_at\":null", "\"starts_at\":\"2099-01-01T00:00:00Z\"")
            ).joinToString(",") + "]"
        )!!
        val collections = RctvBannerParser.collections(collectionsJson)!!
        // Full: as published, the ended and future ones out, the missing collection out.
        assertEquals(
            listOf("collection", "kidsCollection", "channel"),
            RctvBannerParser.visible(banners, collections, now, full = true, kids = false).map { it.id }
        )
        // Light: no Live TV.
        assertEquals(
            listOf("collection", "kidsCollection"),
            RctvBannerParser.visible(banners, collections, now, full = false, kids = false).map { it.id }
        )
        // Kids: only collections allowed on Kids.
        assertEquals(
            listOf("kidsCollection"),
            RctvBannerParser.visible(banners, collections, now, full = true, kids = true).map { it.id }
        )
    }

    @Test
    fun `banners go after their Home row, or the last one before it that shows`() {
        fun at(after: String) = RctvBannerParser.banners("[" + banner(after, """{"type":"channel","channel":"ESPN"}""").replace("home_new_releases", after) + "]")!!.single()
        val banners = listOf(at("continue_watching"), at("streaming_services"), at("home_trending"), at("home_channels"), at("home_awards"), at("home_unknown"))
        val order = listOf("home_trending", "home_new_releases", "home_channels", "home_awards")
        // Light: no Top Channels row; Awards didn't load.
        val slots = RctvBannerParser.slots(banners, order, shownRows = setOf("home_trending", "home_new_releases"))
        assertEquals(listOf("continue_watching"), slots["continue_watching"]?.map { it.id })
        assertEquals(listOf("streaming_services", "home_unknown"), slots["streaming_services"]?.map { it.id })
        assertEquals(listOf("home_trending"), slots["home_trending"]?.map { it.id })
        assertEquals(listOf("home_channels", "home_awards"), slots["home_new_releases"]?.map { it.id })
        // Nothing shows yet: all of them under Streaming Services (Continue Watching keeps its own).
        assertEquals(5, RctvBannerParser.slots(banners, order, emptySet())["streaming_services"]?.size)
        // A bad row name: under Streaming Services.
        assertEquals("streaming_services", at("Home-Trending!").afterRow)
    }

    @Test
    fun `channel names match the guide's`() {
        val guide = listOf("ESPN2 HD", "US: ESPN HD", "ESPN Deportes", "Fox Sports 1", "ABC - WABC New York", "Telemundo")
        assertEquals("US: ESPN HD", RctvChannelMatcher.find("ESPN", guide) { it })
        assertEquals("ESPN2 HD", RctvChannelMatcher.find("espn2", guide) { it })
        assertEquals("ESPN Deportes", RctvChannelMatcher.find("ESPN Deportes", guide) { it })
        assertEquals("Fox Sports 1", RctvChannelMatcher.find("fox sports", guide) { it })
        assertEquals("Telemundo", RctvChannelMatcher.find("telemundo", guide) { it })
        assertEquals("ABC - WABC New York", RctvChannelMatcher.find("ABC", guide) { it })
        assertNull(RctvChannelMatcher.find("Univision", guide) { it })
        assertNull(RctvChannelMatcher.find("   ", guide) { it })
        assertTrue(RctvChannelMatcher.normalize("|US| ESPN FHD") == "espn")
    }
}
