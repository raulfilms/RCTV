package com.nuvio.tv.data.remote.espn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SharedResultsTest {

    private var clockMs = 1_000L
    private fun shared(maxKept: Int = 10) = SharedResults<List<String>>(
        keepMs = 60_000,
        maxKept = maxKept,
        isWorthKeeping = { it.isNotEmpty() },
        now = { clockMs }
    )

    @Test
    fun `a kept result is reused until it expires`() {
        val results = shared()
        val reads = AtomicInteger()
        val first = results.get("nfl") { reads.incrementAndGet(); listOf("Bears") }
        clockMs += 59_000
        assertSame(first, results.get("nfl") { reads.incrementAndGet(); listOf("other") })
        assertEquals(1, reads.get())
        clockMs += 2_000
        assertEquals(listOf("again"), results.get("nfl") { reads.incrementAndGet(); listOf("again") })
        assertEquals(2, reads.get())
    }

    @Test
    fun `callers asking at the same time share one read`() {
        val results = shared()
        val reads = AtomicInteger()
        val readStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val first = pool.submit<List<String>> {
                results.get("nba") {
                    reads.incrementAndGet()
                    readStarted.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    listOf("Lakers")
                }
            }
            assertTrue(readStarted.await(5, TimeUnit.SECONDS))
            val others = (1..3).map { pool.submit<List<String>> { results.get("nba") { reads.incrementAndGet(); listOf("x") } } }
            Thread.sleep(100)
            release.countDown()
            assertEquals(listOf("Lakers"), first.get(5, TimeUnit.SECONDS))
            others.forEach { assertEquals(listOf("Lakers"), it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, reads.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `empty and failed reads are not kept, and a failure reaches the caller`() {
        val results = shared()
        assertEquals(emptyList<String>(), results.get("nhl") { emptyList() })
        assertEquals(0, results.keptCount())
        val failure = runCatching { results.get("nhl") { throw IllegalStateException("HTTP 503") } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(listOf("Rangers"), results.get("nhl") { listOf("Rangers") })
        assertEquals(1, results.keptCount())
    }

    @Test
    fun `at most maxKept results are kept, the oldest dropped first, and clear drops them all`() {
        val results = shared(maxKept = 2)
        val reads = AtomicInteger()
        listOf("a", "b", "c").forEach { key -> results.get(key) { reads.incrementAndGet(); listOf(key) } }
        assertEquals(2, results.keptCount())
        results.get("c") { reads.incrementAndGet(); listOf("c2") }
        results.get("a") { reads.incrementAndGet(); listOf("a2") }
        assertEquals(4, reads.get()) // "c" was kept, "a" had been dropped
        results.clear()
        assertEquals(0, results.keptCount())
    }
}
