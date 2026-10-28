package com.nuvio.tv.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTrailTest {

    @Test
    fun `memory by kind is shown in MB, in a fixed order`() {
        val stats = mapOf(
            "summary.java-heap" to "40960",
            "summary.native-heap" to "1331200",
            "summary.graphics" to "81920",
            "summary.code" to "61440",
            "summary.private-other" to "40960",
            "summary.total-pss" to "1556480",
            "summary.stack" to "1024"
        )
        assertEquals(
            "total 1520 · native 1300 · graphics 80 · java 40 · code 60 · other 40 MB",
            SessionTrail.memoryKindsLine(stats)
        )
    }

    @Test
    fun `missing or unreadable kinds are left out`() {
        assertEquals("native 2 MB", SessionTrail.memoryKindsLine(mapOf("summary.native-heap" to " 2048 ", "summary.graphics" to "n/a")))
        assertEquals("", SessionTrail.memoryKindsLine(emptyMap()))
    }

    @Test
    fun `the thread count is read from the process status`() {
        assertEquals(87, SessionTrail.threadsFrom("Name:\tcom.nuvio\nState:\tS\nThreads:\t87\nVmRSS:\t1 kB\n"))
        assertEquals(null, SessionTrail.threadsFrom("Name:\tx\n"))
    }
}
