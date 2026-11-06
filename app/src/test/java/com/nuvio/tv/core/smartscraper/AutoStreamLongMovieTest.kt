package com.nuvio.tv.core.smartscraper

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RCTV (owner's rules, 5-nov-2026): the right movie first (its title and its year, never another of
 * the same name), then a picture worth watching for its length, then the sound, then the smallest
 * file; a long movie's big files are its own, never left out for a small one of another movie.
 */
class AutoStreamLongMovieTest {

    /** A 2 h 30 min movie of this year (the newest of its name). */
    private val odyssey = SmartSourceRequest(title = "The Odyssey", year = 2026, isSeries = false, runtimeMinutes = 150)

    private fun odysseyStreams() = listOf(
        stream("4k-14", "The.Odyssey.2026.2160p.WEB-DL.DDP5.1.HEVC", 14.0),
        stream("4k-10", "The.Odyssey.2026.2160p.WEB-DL.DDP5.1.HEVC", 10.0),
        stream("4k-8", "The.Odyssey.2026.2160p.WEB-DL.DDP5.1.HEVC", 8.0),
        stream("1080-6", "The.Odyssey.2026.1080p.WEB-DL.DDP5.1.x264", 6.0),
        stream("1080-3", "The.Odyssey.2026.1080p.WEB-DL.DDP5.1.x264", 3.0),
        stream("1080-2", "The.Odyssey.2026.1080p.WEB-DL.DDP5.1.x264", 2.0),
        // Another movie of the same name: an older one, and one whose year isn't said.
        stream("1997", "The.Odyssey.1997.1080p.BluRay.DD5.1.x264", 1.5),
        stream("no-year", "The.Odyssey.1080p.WEB-DL.DDP5.1.x264", 1.2)
    )

    @Test
    fun `a 2 h 30 min movie - the smallest file of a full picture, its big ones kept, never another of its name`() {
        val result = AutoStreamEngine.rank(odysseyStreams().map(SourceProviderAdapters::normalize), odyssey)
        // 8 GB per 2 hours: 10 GB for 2 h 30 min; the 14 GB is over it.
        assertEquals(AutoStreamReject.TOO_LARGE, reason(result, "4k-14"))
        // A 1080p of 2 or 3 GB for 2 h 30 min (H.264 under 3 Mbps) is far too small: only after.
        assertEquals(listOf("1080-6", "4k-8", "4k-10", "1080-2", "1080-3"), hashes(result))
        assertEquals(AutoStreamReject.WRONG_CONTENT, reason(result, "1997"))
        assertEquals(AutoStreamReject.WRONG_CONTENT, reason(result, "no-year"))
    }

    @Test
    fun `only big files of the right movie - they play, never a small one of another movie`() {
        val streams = listOf(
            stream("4k-10", "The.Odyssey.2026.2160p.WEB-DL.DDP5.1.HEVC", 10.0),
            stream("1080-9", "The.Odyssey.2026.1080p.BluRay.DTS.5.1.x264", 9.0),
            stream("1997", "The.Odyssey.1997.1080p.BluRay.DD5.1.x264", 1.5)
        )
        val result = AutoStreamEngine.rank(streams.map(SourceProviderAdapters::normalize), odyssey)
        assertEquals(listOf("1080-9", "4k-10"), hashes(result))
        assertEquals(AutoStreamReject.WRONG_CONTENT, reason(result, "1997"))
    }

    @Test
    fun `the Smart Scraper keeps a long movie's files up to its own limit too`() {
        val result = SmartScoreEngine.rank(odysseyStreams().map(SourceProviderAdapters::normalize), odyssey)
        val smart = result.smart.flatMap { listOf(it.best) + it.copies }.map { it.stream.infoHash }
        assertTrue("10 GB kept: $smart", "4k-10" in smart)
        assertTrue("14 GB left out: $smart", "4k-14" !in smart)
        assertTrue("never the 1997 one: $smart", "1997" !in smart && "no-year" !in smart)
        // The movie's own full-picture files before the ones far too small for its length.
        assertEquals("1080-6", result.smart.first().best.stream.infoHash)
        // A 2-hour movie keeps the limit of 8 GB.
        val config = SmartScraperConfig()
        assertEquals(config.maxFileSizeBytes, config.maxFileSizeBytesFor(120, isSeries = false))
        assertEquals(config.maxFileSizeBytes, config.maxFileSizeBytesFor(180, isSeries = true))
        assertEquals((10.0 * GB).toLong(), config.maxFileSizeBytesFor(150, isSeries = false))
    }

    @Test
    fun `another title needs the movie's exact year - a sequel or a sibling is never played on its own`() {
        val darkKnight = SmartSourceRequest(title = "The Dark Knight", year = 2008, isSeries = false, runtimeMinutes = 152)
        val dark = AutoStreamEngine.rank(
            listOf(
                stream("rises", "The.Dark.Knight.Rises.1080p.BluRay.DD5.1.x264", 3.5),
                stream("rises-2012", "The.Dark.Knight.Rises.2012.1080p.BluRay.DD5.1.x264", 3.5),
                stream("batman-tdk", "Batman.The.Dark.Knight.2008.1080p.BluRay.DD5.1.x264", 9.0),
                stream("tdk", "The.Dark.Knight.2008.1080p.BluRay.DD5.1.x264", 8.5)
            ).map(SourceProviderAdapters::normalize),
            darkKnight
        )
        assertEquals(listOf("tdk", "batman-tdk"), hashes(dark))
        assertEquals(AutoStreamReject.WRONG_CONTENT, reason(dark, "rises"))
        assertEquals(AutoStreamReject.WRONG_CONTENT, reason(dark, "rises-2012"))

        // Vol. 2 (2004) is a year off Vol. 1 (2003): not Vol. 1.
        val killBill = SmartSourceRequest(title = "Kill Bill: Vol. 1", year = 2003, isSeries = false, runtimeMinutes = 111)
        val bill = AutoStreamEngine.rank(
            listOf(
                stream("vol2", "Kill.Bill.Vol.2.2004.1080p.BluRay.DD5.1.x264", 4.0),
                stream("vol1", "Kill.Bill.Vol.1.2003.1080p.BluRay.DD5.1.x264", 4.0)
            ).map(SourceProviderAdapters::normalize),
            killBill
        )
        assertEquals(listOf("vol1"), hashes(bill))
        assertEquals(AutoStreamReject.WRONG_CONTENT, reason(bill, "vol2"))

        // The same movie under a longer name of its own year still plays ("Dune Part One" of 2021).
        val dune = SmartSourceRequest(title = "Dune", year = 2021, isSeries = false, runtimeMinutes = 155)
        val duneResult = AutoStreamEngine.rank(
            listOf(stream("part-one", "Dune.Part.One.2021.2160p.WEB-DL.DDP5.1.HEVC", 9.0)).map(SourceProviderAdapters::normalize),
            dune
        )
        assertEquals(listOf("part-one"), hashes(duneResult))
    }

    @Test
    fun `the movie's own year comes before a file whose year isn't said`() {
        val godfather = SmartSourceRequest(title = "The Godfather", year = 1972, isSeries = false, runtimeMinutes = 175)
        val result = AutoStreamEngine.rank(
            listOf(
                stream("no-year", "The.Godfather.1080p.BluRay.DD5.1.x264", 6.0),
                stream("1972", "The.Godfather.1972.1080p.BluRay.DD5.1.x264", 9.0)
            ).map(SourceProviderAdapters::normalize),
            godfather
        )
        assertEquals(listOf("1972", "no-year"), hashes(result))
        assertNull(result.rejected.firstOrNull { it.first.stream.infoHash == "1972" })
    }

    @Test
    fun `an episode keeps its own order - nothing here holds one back`() {
        val episode = SmartSourceRequest(title = "Show", year = null, isSeries = true, season = 1, episode = 2, runtimeMinutes = 45)
        val result = AutoStreamEngine.rank(
            listOf(
                stream("small", "Show.S01E02.1080p.WEB-DL.DDP5.1.x264", 0.3),
                stream("big", "Show.S01E02.1080p.WEB-DL.DDP5.1.x264", 1.5)
            ).map(SourceProviderAdapters::normalize),
            episode
        )
        assertEquals(listOf("small", "big"), hashes(result))
    }

    private fun hashes(result: AutoStreamResult) = result.candidates.map { it.stream.infoHash }

    private fun reason(result: AutoStreamResult, hash: String) =
        result.rejected.first { it.first.stream.infoHash == hash }.second

    private fun stream(hash: String, release: String, sizeGb: Double) = Stream(
        name = "[RD+] Torrentio\n4k",
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
            videoSize = (sizeGb * GB).toLong()
        ),
        addonName = "Torrentio RD",
        addonLogo = null
    )

    private companion object {
        const val GB = 1_073_741_824L
    }
}
