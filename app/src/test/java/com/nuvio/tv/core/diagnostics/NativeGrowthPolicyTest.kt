package com.nuvio.tv.core.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeGrowthPolicyTest {

    @Test
    fun collectsOnFastGrowthOnly() {
        val policy = NativeGrowthPolicy()
        assertFalse(policy.shouldCollect(24, 0))      // first look: the baseline
        assertFalse(policy.shouldCollect(100, 1_000)) // +76: not yet
        assertTrue(policy.shouldCollect(130, 2_000))  // +106: due (the Sports page's pile-up)
        policy.collected(40, 2_100)
        assertFalse(policy.shouldCollect(150, 3_000)) // +110, but 0.9 s after the last one
        assertTrue(policy.shouldCollect(150, 5_200))
    }

    @Test
    fun memoryInUseDoesNotRepeatCollections() {
        val policy = NativeGrowthPolicy()
        policy.shouldCollect(300, 0)
        assertTrue(policy.shouldCollect(400, 4_000))
        policy.collected(390, 4_100)                  // nothing freed: it's in use
        assertFalse(policy.shouldCollect(400, 10_000))
        assertFalse(policy.shouldCollect(420, 20_000))
        assertTrue(policy.shouldCollect(440, 30_000)) // +50 when high: due
    }

    @Test
    fun baselineFollowsMemoryDown() {
        val policy = NativeGrowthPolicy()
        policy.shouldCollect(200, 0)
        policy.shouldCollect(60, 1_000)               // the collector ran on its own
        assertFalse(policy.shouldCollect(140, 2_000))
        assertTrue(policy.shouldCollect(160, 3_000))
    }
}
