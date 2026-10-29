package com.nuvio.tv.core.smartscraper

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RCTV: a movie's Extended and Director's Cut versions: found in its files' names, offered in its
 * Extras, played only when chosen there (the regular Play keeps the regular version first).
 */
class AutoStreamEditionTest {

    private val movie = SmartSourceRequest(title = "Kingdom of Heaven", year = 2005, isSeries = false, runtimeMinutes = 144)

    @Test
    fun `the version is read from the file's name`() {
        fun edition(name: String) = ReleaseParser.parseName(name).edition
        assertEquals(ReleaseEdition.DIRECTORS_CUT, edition("Kingdom.of.Heaven.2005.Directors.Cut.1080p.BluRay.DTS.x264"))
        assertEquals(ReleaseEdition.DIRECTORS_CUT, edition("Kingdom of Heaven (2005) Director's Cut 2160p UHD"))
        assertEquals(ReleaseEdition.DIRECTORS_CUT, edition("Kingdom.of.Heaven.2005.Extended.Directors.Cut.1080p"))
        assertEquals(ReleaseEdition.EXTENDED, edition("The.Lord.of.the.Rings.2001.EXTENDED.2160p.UHD.BluRay.x265"))
        assertEquals(ReleaseEdition.EXTENDED, edition("Avatar.2009.Extended.Collectors.Edition.1080p"))
        assertEquals(ReleaseEdition.EXTENDED, edition("Pelicula.2005.Version.Extendida.1080p.Latino"))
        assertEquals(ReleaseEdition.NONE, edition("Kingdom.of.Heaven.2005.1080p.BluRay.DTS.x264"))
        assertEquals(ReleaseEdition.NONE, edition("Extendedly.Yours.2005.1080p"))
        assertEquals(ReleaseEdition.EXTENDED, ReleaseEdition.ofKey("extended"))
        assertEquals(ReleaseEdition.DIRECTORS_CUT, ReleaseEdition.ofKey("directors_cut"))
        assertNull(ReleaseEdition.ofKey(""))
        assertNull(ReleaseEdition.ofKey("none"))
        assertNull(ReleaseEdition.ofKey(null))
    }

    private fun streams() = listOf(
        stream("dc-4k", "Kingdom.of.Heaven.2005.Directors.Cut.2160p.UHD.BluRay.DDP5.1.HEVC", 7.5),
        stream("dc-1080", "Kingdom.of.Heaven.2005.Directors.Cut.1080p.BluRay.DDP5.1.x264", 5.0),
        stream("regular-1080", "Kingdom.of.Heaven.2005.1080p.BluRay.DDP5.1.x264", 4.0),
        stream("regular-720", "Kingdom.of.Heaven.2005.720p.BluRay.DDP5.1.x264", 2.0)
    )

    @Test
    fun `a title that is the word itself is the regular version`() {
        val family = SmartSourceRequest(title = "Extended Family", year = 2020, isSeries = false)
        val release = ReleaseParser.parseName("Extended.Family.2020.1080p.WEB-DL.DDP5.1.x264")
        assertEquals(ReleaseEdition.EXTENDED, release.edition)
        assertEquals(ReleaseEdition.NONE, release.editionFor(family))
        assertEquals(ReleaseEdition.EXTENDED, release.editionFor(movie))
    }

    @Test
    fun `the regular Play keeps the regular version first, a Director's Cut only after it`() {
        val result = AutoStreamEngine.rank(streams().map(SourceProviderAdapters::normalize), movie)
        assertEquals(listOf("regular-1080", "regular-720", "dc-4k", "dc-1080"), result.candidates.map { it.stream.infoHash })
    }

    @Test
    fun `chosen in the Extras, only that version plays`() {
        val result = AutoStreamEngine.rank(
            streams().map(SourceProviderAdapters::normalize),
            movie.copy(edition = ReleaseEdition.DIRECTORS_CUT)
        )
        assertEquals(listOf("dc-4k", "dc-1080"), result.candidates.map { it.stream.infoHash })
        assertTrue(result.rejected.all { it.second == AutoStreamReject.WRONG_CONTENT })
        val extended = AutoStreamEngine.rank(streams().map(SourceProviderAdapters::normalize), movie.copy(edition = ReleaseEdition.EXTENDED))
        assertTrue(extended.candidates.isEmpty())
    }

    @Test
    fun `the Extras offer each version Auto Stream can play, with its best file`() {
        val editions = AutoStreamEngine.editions(streams().map(SourceProviderAdapters::normalize), movie)
        assertEquals(listOf(ReleaseEdition.DIRECTORS_CUT), editions.map { it.edition })
        assertEquals(VideoResolution.UHD_2160, editions.single().best.resolution)
        // Only a version over 8 GB: not offered.
        val tooBig = AutoStreamEngine.editions(
            listOf(stream("ext-9", "Kingdom.of.Heaven.2005.Extended.2160p.BluRay.DDP5.1.HEVC", 9.0)).map(SourceProviderAdapters::normalize),
            movie
        )
        assertTrue(tooBig.isEmpty())
        // A series: none.
        val series = SmartSourceRequest(title = "Show", year = 2005, isSeries = true, season = 1, episode = 1)
        assertTrue(AutoStreamEngine.editions(streams().map(SourceProviderAdapters::normalize), series).isEmpty())
    }

    @Test
    fun `the cloud's list keeps the version playing, or the regular one first`() {
        val normalized = streams().map(SourceProviderAdapters::normalize)
        assertEquals(
            listOf("regular-1080", "regular-720", "dc-4k", "dc-1080"),
            AutoStreamEngine.panelSources(normalized, movie).map { it.stream.infoHash }
        )
        assertEquals(
            listOf("dc-4k", "dc-1080"),
            AutoStreamEngine.panelSources(normalized, movie.copy(edition = ReleaseEdition.DIRECTORS_CUT)).map { it.stream.infoHash }
        )
    }

    private fun stream(hash: String, release: String, sizeGb: Double) = Stream(
        name = "[RD+] AIOStreams 4K",
        title = release,
        description = null,
        url = "https://example.com/$hash",
        ytId = null,
        infoHash = hash,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = null,
            videoSize = (sizeGb * ParsedRelease.BYTES_PER_GB).toLong()
        ),
        addonName = "AIOStreams Extended",
        addonLogo = null
    )
}
