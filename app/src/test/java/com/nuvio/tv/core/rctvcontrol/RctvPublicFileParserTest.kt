package com.nuvio.tv.core.rctvcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RctvPublicFileParserTest {

    @Test
    fun `streaming services in the website's order, hidden ones out`() {
        val layout = RctvPublicFileParser.streamingLayout("""{"order":["disney","netflix","prime"],"hidden":["prime"]}""")!!
        val tiles = listOf("netflix", "prime", "hbomax", "disney")
        // Website's order first, then the rest as they were.
        assertEquals(listOf("disney", "netflix", "hbomax"), RctvPublicFileParser.ordered(tiles, layout) { it })
        assertEquals(tiles, RctvPublicFileParser.ordered(tiles, null) { it })
        assertNull(RctvPublicFileParser.streamingLayout("not json"))
    }

    @Test
    fun `announcements with their dates`() {
        val list = RctvPublicFileParser.announcements(
            """[{"id":"a","title":"Maintenance","body":"Please try later","starts_at":null,"ends_at":"2026-11-12T00:00:00+00:00"},
               {"id":"","title":"No id"},{"id":"b","title":"  "}]"""
        )!!
        assertEquals(1, list.size)
        val a = list[0]
        assertEquals("Maintenance", a.title)
        assertTrue(a.isShownAt(RctvControlParser.epochMsOf("2026-11-11T23:00:00Z")!!))
        assertFalse(a.isShownAt(RctvControlParser.epochMsOf("2026-11-12T01:00:00Z")!!))
    }
}
