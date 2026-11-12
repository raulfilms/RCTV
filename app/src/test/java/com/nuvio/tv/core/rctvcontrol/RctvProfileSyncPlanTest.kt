package com.nuvio.tv.core.rctvcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RctvProfileSyncPlanTest {
    private val main = RctvRemoteProfile(slot = 1, name = "Raúl")
    private val kid = RctvRemoteProfile(slot = 2, name = "Kid", isKids = true)
    private val guest = RctvRemoteProfile(slot = 3, name = "Guest")

    @Test
    fun `a new TV gets the account's profiles`() {
        val plan = RctvProfileSyncPlanner.afterPull(local = listOf(main.copy(name = "Profile 1")), remote = listOf(main, kid), known = emptyMap())
        assertEquals(listOf(main, kid), plan.upsertLocal)
        assertTrue(plan.upsertRemote.isEmpty())
        assertEquals(setOf(1, 2), plan.known.keys)
    }

    @Test
    fun `the first TV fills an empty account`() {
        val plan = RctvProfileSyncPlanner.afterPull(local = listOf(main, kid), remote = emptyList(), known = emptyMap())
        assertEquals(listOf(main, kid), plan.upsertRemote)
        assertTrue(plan.deleteLocal.isEmpty())
    }

    @Test
    fun `deleted on the website, deleted here (never the main profile)`() {
        val known = mapOf(1 to main, 2 to kid, 3 to guest)
        val plan = RctvProfileSyncPlanner.afterPull(local = listOf(main, kid, guest), remote = listOf(kid), known = known)
        assertEquals(listOf(3), plan.deleteLocal)
        // The main profile goes back up.
        assertEquals(listOf(main), plan.upsertRemote)
    }

    @Test
    fun `changed on the website wins, changed only here goes up`() {
        val known = mapOf(1 to main, 2 to kid)
        val renamedOnWeb = kid.copy(name = "Little one")
        val fromWeb = RctvProfileSyncPlanner.afterPull(local = listOf(main, kid), remote = listOf(main, renamedOnWeb), known = known)
        assertEquals(listOf(renamedOnWeb), fromWeb.upsertLocal)

        val kidsOff = kid.copy(isKids = false)
        val fromTv = RctvProfileSyncPlanner.afterPull(local = listOf(main, kidsOff), remote = listOf(main, kid), known = known)
        assertTrue(fromTv.upsertLocal.isEmpty())
        assertEquals(listOf(kidsOff), fromTv.upsertRemote)
    }

    @Test
    fun `a profile made here is sent, not deleted`() {
        val plan = RctvProfileSyncPlanner.afterPull(local = listOf(main, guest), remote = listOf(main), known = mapOf(1 to main))
        assertEquals(listOf(guest), plan.upsertRemote)
        assertTrue(plan.deleteLocal.isEmpty())
    }

    @Test
    fun `changes on this TV`() {
        val known = mapOf(1 to main, 2 to kid, 3 to guest)
        val plan = RctvProfileSyncPlanner.afterLocalChange(local = listOf(main, kid.copy(name = "K")), known = known)
        assertEquals(listOf(kid.copy(name = "K")), plan.upsertRemote)
        assertEquals(listOf(3), plan.removeRemote)
    }
}
