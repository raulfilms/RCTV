package com.nuvio.tv.core.player
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
class StreamAutoPlayLastResortTest {
    private fun stream(name: String) = Stream(name = name, title = null, description = null, url = "https://x/$name.mkv", ytId = null, infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null, addonName = "A", addonLogo = null)
    private fun pick(rules: List<Stream>, lastResort: List<Stream>?, all: List<Stream>) = StreamAutoPlaySelector.selectAutoPlayStream(
        streams = all, mode = StreamAutoPlayMode.AUTO_STREAM, regexPattern = "", source = StreamAutoPlaySource.ALL_SOURCES,
        installedAddonNames = setOf("A"), selectedAddons = emptySet(), selectedPlugins = emptySet(),
        smartRanking = rules, lastResortRanking = lastResort)
    @Test fun rulesFirstThenLastResort() {
        val a = stream("a"); val b = stream("b")
        assertEquals(a, pick(listOf(a), listOf(b, a), listOf(a, b)))
        assertEquals(b, pick(emptyList(), listOf(b, a), listOf(a, b)))
        assertNull(pick(emptyList(), null, listOf(a, b)))
    }
}
