package com.nuvio.tv.core.util

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CancellableCatchingTest {

    @Test
    fun `a result and a failure are kept as runCatching keeps them`() {
        assertEquals(3, runCatchingCancellable { 1 + 2 }.getOrNull())
        val failure = runCatchingCancellable<Int> { throw IllegalStateException("down") }
        assertTrue(failure.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `cancellation is thrown on, not turned into a failure`() {
        try {
            runCatchingCancellable<Int> { throw CancellationException("left the page") }
            fail("cancellation was swallowed")
        } catch (e: CancellationException) {
            assertEquals("left the page", e.message)
        }
    }
}
