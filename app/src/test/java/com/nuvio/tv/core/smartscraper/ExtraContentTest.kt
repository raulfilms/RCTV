package com.nuvio.tv.core.smartscraper

import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** RCTV (owner's rule): a movie is the movie; a featurette, a making-of, a trailer never plays for it. */
class ExtraContentTest {

    private fun source(title: String) = SourceProviderAdapters.normalize(
        Stream(
            name = "Torrentio\n1080p", title = title, description = null, url = "https://example.test/f.mkv",
            ytId = null, infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null,
            addonName = "Torrentio", addonLogo = null
        )
    )

    private val interstellar = SmartSourceRequest(title = "Interstellar", year = 2014, isSeries = false)

    @Test
    fun `a featurette inside the movie's own torrent is an extra`() {
        assertNotNull(ExtraContent.wordIn(source("Interstellar.2014.2160p.UHD.BluRay\nFeaturettes/The.Science.of.Interstellar.mkv"), interstellar))
        assertEquals("trailer", ExtraContent.wordIn(source("Interstellar.2014.Official.Trailer.1080p"), interstellar))
        assertEquals("making of", ExtraContent.wordIn(source("Interstellar.2014.Making.Of.720p"), interstellar))
    }

    @Test
    fun `the movie itself is not`() {
        assertNull(ExtraContent.wordIn(source("Interstellar.2014.1080p.BluRay.x264.DTS"), interstellar))
    }

    @Test
    fun `a word that is in the title doesn't count`() {
        val vampire = SmartSourceRequest(title = "Interview with the Vampire", year = 1994, isSeries = false)
        assertNull(ExtraContent.wordIn(source("Interview.with.the.Vampire.1994.1080p.BluRay"), vampire))
    }

    @Test
    fun `Auto Stream never takes an extra for the movie`() {
        val scored = SmartScoreEngine.judge(
            source("Interstellar.2014.1080p.BluRay\nExtras/Behind.The.Scenes.mkv\n💾 2.1 GB"),
            interstellar,
            SmartRankingContext.DEFAULT
        )
        assertEquals(RejectReason.WRONG_TYPE, scored.rejection)
    }
}
