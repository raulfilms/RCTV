package com.nuvio.tv.core.rctvcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RctvAccessTest {
    private val now = 1_800_000_000_000L
    private val token = "a".repeat(43)

    @Test
    fun `Light gets Full only while its trial runs`() {
        val trial = RctvAccount(basePlan = RctvPlan.LIGHT, trialEndsAtMs = now + 60_000)
        assertEquals(RctvPlan.FULL, trial.planAt(now))
        assertEquals(RctvPlan.LIGHT, trial.planAt(now + 60_000))
        assertEquals(RctvPlan.LIGHT, RctvAccount(basePlan = RctvPlan.LIGHT).planAt(now))
        assertEquals(RctvPlan.FULL, RctvAccount(basePlan = RctvPlan.LIGHT, master = true).planAt(now))
        assertEquals(RctvPlan.FULL, RctvAccount(basePlan = RctvPlan.FULL).planAt(now))
    }

    @Test
    fun `codes are capitals without look-alike characters`() {
        assertEquals("ABCD2346", RctvCodes.normalize("abcd-2346"))
        // O, I, S, 0, 1 and 5 aren't in any code.
        assertEquals("ABCD", RctvCodes.normalize("abcdOIS015"))
        assertEquals("ABCDEFGH", RctvCodes.normalize("abcdefghjk"))
        assertTrue(RctvCodes.isValid("ABCD2346"))
        assertEquals(false, RctvCodes.isValid("ABCD234"))
    }

    @Test
    fun `activation answers`() {
        val ok = RctvControlParser.activation(200, """{"device_token":"$token","plan":"light","device_id":"x"}""")
        assertEquals(RctvActivation.Activated(token, RctvAccount(basePlan = RctvPlan.LIGHT)), ok)
        assertEquals(RctvActivation.WrongCode, RctvControlParser.activation(400, """{"error":{"code":"ACTIVATION_DENIED"}}"""))
        assertEquals(RctvActivation.TooManyTries, RctvControlParser.activation(429, """{"error":{"code":"RATE_LIMITED"}}"""))
        assertEquals(RctvActivation.NoConnection, RctvControlParser.activation(503, """{"error":{"code":"SERVER_ERROR"}}"""))
        // A token of the wrong shape is never kept.
        assertEquals(RctvActivation.NoConnection, RctvControlParser.activation(200, """{"device_token":"short","plan":"full"}"""))
        assertEquals(RctvActivation.NoConnection, RctvControlParser.activation(200, "not json"))
    }

    @Test
    fun `device config with a trial, Master and how often to ask`() {
        val config = RctvControlParser.deviceConfig(
            """{"plan":"full","base_plan":"light","master":false,"trial_ends_at":"2027-01-15T08:00:00.123456+00:00",
               "limits":{"iptv":2,"vod":3},"services":{"x":"secret"},"iptv":null,"heartbeat_seconds":300}"""
        )!!
        assertEquals(RctvPlan.LIGHT, config.account.basePlan)
        assertEquals(1_800_000_000_123L, config.account.trialEndsAtMs)
        assertEquals(300, config.checkEverySeconds)
        val master = RctvControlParser.deviceConfig("""{"plan":"full","base_plan":"light","master":true,"trial_ends_at":null}""")!!
        assertTrue(master.account.master)
        assertNull(master.account.trialEndsAtMs)
        assertEquals(RctvControlParser.DEFAULT_CHECK_SECONDS, master.checkEverySeconds)
    }

    @Test
    fun `the website before v2 only sends the plan`() {
        val old = RctvControlParser.deviceConfig("""{"plan":"light","services":{},"iptv":null}""")!!
        assertEquals(RctvAccount(basePlan = RctvPlan.LIGHT), old.account)
        assertNull(RctvControlParser.deviceConfig("""{"services":{}}"""))
        assertNull(RctvControlParser.deviceConfig(null))
    }

    @Test
    fun `asking too often or too rarely is kept in range`() {
        assertEquals(60, RctvControlParser.deviceConfig("""{"plan":"full","heartbeat_seconds":5}""")!!.checkEverySeconds)
        assertEquals(3600, RctvControlParser.deviceConfig("""{"plan":"full","heartbeat_seconds":99999}""")!!.checkEverySeconds)
    }

    @Test
    fun `services, profiles and public files from device config`() {
        val config = RctvControlParser.deviceConfig(
            """{"plan":"full","services":{"realdebrid":"RDKEY123456","torbox":"x","latinodebrid":"https://ld.example/manifest.json","addon_latam":"http://plain.example/manifest.json"},
               "iptv":{"server":"http://iptv.example:8080/","username":"u","password":"p","epg_url":null},
               "profiles":[{"slot":1,"name":"Main","is_kids":true},{"slot":2,"name":"Kid","color":"#FF0000","avatar":"default","is_kids":true},{"slot":9,"name":"Bad"}],
               "public_urls":{"row_layout":"https://x.supabase.co/storage/v1/object/public/rctv-public/row_layout.json","announcements":"http://no"}}"""
        )!!
        assertEquals("RDKEY123456", config.services.realDebrid)
        // Too short to be a key.
        assertNull(config.services.torbox)
        // Addons only over HTTPS.
        assertEquals(mapOf("latinodebrid" to "https://ld.example/manifest.json"), config.services.addons)
        assertEquals(RctvIptv("http://iptv.example:8080", "u", "p", null), config.services.iptv)
        // The main profile is never Kids; slots are 1 to 8.
        assertEquals(
            listOf(RctvRemoteProfile(1, "Main"), RctvRemoteProfile(2, "Kid", color = "#FF0000", isKids = true)),
            config.profiles
        )
        assertTrue(config.publicUrls.rowLayout!!.startsWith("https://"))
        assertNull(config.publicUrls.announcements)
        // Secrets never show in logs.
        assertEquals(false, config.services.toString().contains("RDKEY"))
        assertEquals("RctvIptv(hidden)", config.services.iptv.toString())
    }

    @Test
    fun `each website addon's server comes with it, by its link`() {
        val services = RctvControlParser.deviceConfig(
            """{"plan":"light","services":{
               "addon_list":["https://aio.example/a/manifest.json","http://plain.example/manifest.json",
               "https://torrentio.example/torbox=SECRETKEY","https://other.example/x"],
               "addon_roles":["aio_dubbed","torrentio_rd","torrentio_tb",""]}}"""
        )!!.services
        // In the same order as the links (the dropped http link takes its role with it); "" says nothing.
        assertEquals(
            mapOf("https://aio.example/a/manifest.json" to "aio_dubbed", "https://torrentio.example/torbox=SECRETKEY" to "torrentio_tb"),
            services.addonRoles
        )
        assertEquals(false, services.toString().contains("SECRETKEY"))
    }

    @Test
    fun `the account's addon list keeps the website's order`() {
        val services = RctvControlParser.deviceConfig(
            """{"plan":"light","services":{"addon_latam":"https://latam.example/manifest.json",
               "addon_list":["https://torrentio.example/rd=SECRETKEY/manifest.json","http://plain.example/manifest.json",
               "https://aio.example/stremio/abc/manifest.json","https://torrentio.example/rd=SECRETKEY/manifest.json","https://bad example/x"]}}"""
        )!!.services
        // HTTPS only, once each, in order.
        assertEquals(
            listOf("https://torrentio.example/rd=SECRETKEY/manifest.json", "https://aio.example/stremio/abc/manifest.json"),
            services.addonList
        )
        // The account's list first, then LatinoDebrid and Addon LATAM.
        assertEquals(services.addonList + "https://latam.example/manifest.json", services.managedAddonUrls)
        assertEquals(false, services.toString().contains("SECRETKEY"))
        // On the TV: the website's addons first (its order), the TV's own after them as they were.
        assertEquals(
            listOf("https://b.example/k=1", "https://a.example", "https://own1.example", "https://own2.example"),
            RctvAddonOrder.managedFirst(
                listOf("https://own1.example", "https://a.example", "https://own2.example", "https://b.example/k=1"),
                listOf("https://b.example/k=1/manifest.json", "https://a.example/manifest.json", "https://notyet.example")
            )
        )
    }

    @Test
    fun `the guide address set on the website reaches the TV`() {
        fun iptvWith(epg: String) = RctvControlParser.deviceConfig(
            """{"plan":"full","iptv":{"server":"http://iptv.example:8080","username":"u","password":"p","epg_url":$epg}}"""
        )!!.services.iptv!!
        assertEquals("https://guide.example/epg.xml.gz", iptvWith("\"https://guide.example/epg.xml.gz\"").epgUrl)
        // Many IPTV guides are only on HTTP.
        assertEquals("http://iptv.example:8080/xmltv.php", iptvWith("\"http://iptv.example:8080/xmltv.php\"").epgUrl)
        assertNull(iptvWith("\"ftp://guide.example/epg.xml\"").epgUrl)
        assertNull(iptvWith("null").epgUrl)
    }

    @Test
    fun `an older website without profiles leaves the TV's alone`() {
        assertNull(RctvControlParser.deviceConfig("""{"plan":"full"}""")!!.profiles)
    }
}
