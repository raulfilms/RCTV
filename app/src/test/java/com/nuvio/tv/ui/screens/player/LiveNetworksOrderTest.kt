package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.livetv.RecentLiveChannels
import com.nuvio.tv.domain.model.LiveTvChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** RCTV (Networks): the row's order, the recent channels and the long-press OK channel. */
class LiveNetworksOrderTest {

    private fun ch(id: String) = LiveTvChannel(id = id, name = "Channel $id", streamUrl = "")

    private val lineup = listOf("a", "b", "c", "d", "e", "f", "g").map(::ch)

    @Test
    fun `playing first, then recent, then favorites, then the lineup, each once`() {
        val order = networksOrder(
            channels = lineup,
            currentId = "e",
            recentIds = listOf("e", "c", "zz"),
            favoriteIds = setOf("g", "c", "b")
        ).map { it.id }
        assertEquals(listOf("e", "c", "b", "g", "a", "d", "f"), order)
    }

    @Test
    fun `no recent channels or favorites keeps the lineup's order`() {
        assertEquals(lineup.map { it.id }, networksOrder(lineup, null, emptyList(), emptySet()).map { it.id })
    }

    @Test
    fun `a channel listed twice shows once`() {
        val order = networksOrder(lineup + ch("a"), "a", listOf("a"), setOf("a")).map { it.id }
        assertEquals(lineup.map { it.id }, order)
    }

    @Test
    fun `the last four channels, newest first, no repeats`() {
        var recent = emptyList<String>()
        listOf("a", "b", "c", "b", "d", "e").forEach { recent = RecentLiveChannels.after(recent, it) }
        assertEquals(listOf("e", "d", "b", "c"), recent)
        assertEquals(recent, RecentLiveChannels.after(recent, " "))
    }

    @Test
    fun `long-press OK toggles between the two last channels`() {
        var recent = RecentLiveChannels.after(RecentLiveChannels.after(emptyList(), "a"), "b")
        val back = previousLiveChannel(lineup, "b", recent)
        assertEquals("a", back?.id)
        recent = RecentLiveChannels.after(recent, back!!.id)
        assertEquals("b", previousLiveChannel(lineup, "a", recent)?.id)
    }

    @Test
    fun `no previous channel when only the one playing was watched or it left the lineup`() {
        assertNull(previousLiveChannel(lineup, "a", listOf("a")))
        assertNull(previousLiveChannel(lineup, "a", listOf("a", "zz")))
        assertEquals("c", previousLiveChannel(lineup, "a", listOf("a", "zz", "c"))?.id)
    }
}
