package com.nuvio.tv.core.runtime

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RCTV (PERF-02/03): background checks wait while the app is hidden and run as soon as it's back. */
class AppForegroundTest {

    @After
    fun reset() = AppForeground.set(true)

    @Test
    fun `on screen it doesn't wait`() = runBlocking {
        AppForeground.set(true)
        withTimeout(1_000) { AppForeground.awaitForeground() }
    }

    @Test
    fun `in the background it waits until the app is back`() = runBlocking {
        AppForeground.set(false)
        val waiting = async { AppForeground.awaitForeground(); true }
        delay(100)
        assertFalse(waiting.isCompleted)
        AppForeground.set(true)
        assertTrue(withTimeout(1_000) { waiting.await() })
    }
}
