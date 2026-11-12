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
}
