package com.nuvio.tv.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenVisitsTest {

    @Test
    fun `each screen's visits show native memory at the first and latest opening`() {
        val visits = ScreenVisits()
        listOf(300L, 380L, 420L).forEachIndexed { i, base ->
            visits.record("sports", base)
            visits.record("sports_teams", base + 80 + i * 300L)
        }
        assertEquals("sports_teams ×3 380→1100, sports ×3 300→420", visits.line())
    }

    @Test
    fun `a single visit and the screens kept`() {
        val visits = ScreenVisits(kept = 2)
        visits.record("home", 200)
        visits.record("sports", 250)
        visits.record("search", 260)
        assertEquals("search ×1 260, sports ×1 250", visits.line())
        assertEquals("", ScreenVisits().line())
    }
}
