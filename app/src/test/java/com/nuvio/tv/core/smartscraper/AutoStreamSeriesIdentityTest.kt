package com.nuvio.tv.core.smartscraper

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RCTV (owner's rules, 5-nov-2026): a series is the series, as a movie is the movie: never a movie
 * of its name, a spin-off, the show it comes from, an older show of its name, another season or
 * episode; then the episode's own file, a picture worth watching for its length, the sound, the size.
 */
class AutoStreamSeriesIdentityTest {

    private val below = SmartSourceRequest(title = "Below", year = 2026, isSeries = true, season = 1, episode = 2, runtimeMinutes = 50)

    @Test
    fun `the series Below - never the movie Below, Below Deck, another season or episode`() {
        val result = rank(
            below,
            stream("movie", "Below.2002.1080p.BluRay.DD5.1.x264", 8.0),
            stream("deck", "Below.Deck.S01E02.1080p.WEB-DL.DDP5.1.x264", 1.5),
            stream("s02", "Below.S02E02.1080p.WEB-DL.DDP5.1.x264", 1.5),
            stream("e03", "Below.S01E03.1080p.WEB-DL.DDP5.1.x264", 1.5),
            stream("ep", "Below.S01E02.1080p.WEB-DL.DDP5.1.x264", 1.6),
            stream("ep-2026", "Below.2026.S01E02.1080p.WEB-DL.DDP5.1.H.265", 1.0)
        )
        assertEquals(listOf("ep-2026", "ep"), hashes(result))
        listOf("movie", "deck", "s02", "e03").forEach { assertEquals(it, AutoStreamReject.WRONG_CONTENT, reason(result, it)) }
        // The movie and the other season and episode aren't even in the list; Below Deck is, to choose by hand.
        val list = SmartScoreEngine.rank(streams(
            stream("movie", "Below.2002.1080p.BluRay.DD5.1.x264", 8.0),
            stream("deck", "Below.Deck.S01E02.1080p.WEB-DL.DDP5.1.x264", 1.5)
        ), below)
        assertTrue(list.rejected.any { it.stream.infoHash == "movie" && it.rejection == RejectReason.WRONG_TYPE })
        assertTrue(list.all.any { it.stream.infoHash == "deck" && it.rejection == RejectReason.WRONG_TITLE })
    }

    @Test
    fun `an older show of its name is another show, a newer one isn't its first season`() {
        val shogun = SmartSourceRequest(title = "Shogun", year = 2024, isSeries = true, season = 1, episode = 1, runtimeMinutes = 60)
        val result = rank(
            shogun,
            stream("1980", "Shogun.1980.S01E01.1080p.BluRay.DD5.1.x264", 3.0),
            stream("2024", "Shogun.2024.S01E01.1080p.WEB-DL.DDP5.1.x264", 3.0)
        )
        assertEquals(listOf("2024"), hashes(result))
        assertEquals(AutoStreamReject.WRONG_CONTENT, reason(result, "1980"))
        val old = SmartSourceRequest(title = "Shogun", year = 1980, isSeries = true, season = 1, episode = 1, runtimeMinutes = 60)
        val oldResult = rank(
            old,
            stream("1980", "Shogun.1980.S01E01.1080p.BluRay.DD5.1.x264", 3.0),
            stream("2024", "Shogun.2024.S01E01.1080p.WEB-DL.DDP5.1.x264", 3.0)
        )
        assertEquals(listOf("1980"), hashes(oldResult))
        // A later season's own year is fine.
        val later = SmartSourceRequest(title = "Doctor Who", year = 2005, isSeries = true, season = 4, episode = 1, runtimeMinutes = 45)
        assertEquals(
            listOf("s04"),
            hashes(rank(later, stream("s04", "Doctor.Who.2008.S04E01.1080p.WEB-DL.DD5.1.x264", 2.0)))
        )
    }

    @Test
    fun `a spin-off or its parent show isn't it, its brand, country or year words are`() {
        assertTrue(TitleMatcher.anotherShow("Below Deck", SmartSourceRequest(title = "Below", year = null, isSeries = true)))
        assertTrue(TitleMatcher.anotherShow("NCIS", SmartSourceRequest(title = "NCIS: Los Angeles", year = null, isSeries = true)))
        assertTrue(TitleMatcher.anotherShow("NCIS Los Angeles", SmartSourceRequest(title = "NCIS", year = null, isSeries = true)))
        assertTrue(TitleMatcher.anotherShow("Daredevil Born Again", SmartSourceRequest(title = "Daredevil", year = null, isSeries = true)))
        assertFalse(TitleMatcher.anotherShow("Marvels Daredevil", SmartSourceRequest(title = "Daredevil", year = null, isSeries = true)))
        assertFalse(TitleMatcher.anotherShow("Andor", SmartSourceRequest(title = "Star Wars: Andor", year = null, isSeries = true)))
        assertFalse(TitleMatcher.anotherShow("The Office US", SmartSourceRequest(title = "The Office", year = null, isSeries = true)))
        val office = SmartSourceRequest(title = "The Office", year = 2005, isSeries = true, season = 2, episode = 3, runtimeMinutes = 45)
        assertEquals(listOf("us"), hashes(rank(office, stream("us", "The.Office.US.S02E03.1080p.WEB-DL.DD5.1.x264", 1.4))))
    }

    @Test
    fun `an episode's own file first, one far too small for its length after`() {
        val show = SmartSourceRequest(title = "Show", year = null, isSeries = true, season = 1, episode = 2, runtimeMinutes = 45)
        val result = rank(
            show,
            stream("tiny", "Show.S01E02.1080p.WEB-DL.DDP5.1.x264", 0.3),
            stream("pack", "Show.S01.1080p.WEB-DL.DDP5.1.x264", null, fileIdx = 2),
            stream("ep", "Show.S01E02.1080p.WEB-DL.DDP5.1.x264", 1.5)
        )
        assertEquals(listOf("ep", "tiny", "pack"), hashes(result))
    }

    @Test
    fun `a movie request never plays an episode or a season`() {
        val movie = SmartSourceRequest(title = "Below", year = 2002, isSeries = false, runtimeMinutes = 105)
        val result = rank(
            movie,
            stream("ep", "Below.S01E02.1080p.WEB-DL.DDP5.1.x264", 1.5),
            stream("season", "Below.S01.1080p.WEB-DL.DDP5.1.x264", 9.0),
            stream("movie", "Below.2002.1080p.BluRay.DD5.1.x264", 6.0)
        )
        assertEquals(listOf("movie"), hashes(result))
        assertEquals(AutoStreamReject.WRONG_CONTENT, reason(result, "ep"))
        assertEquals(AutoStreamReject.WRONG_CONTENT, reason(result, "season"))
    }

    @Test
    fun `a telenovela's Spanish server link that says only its episode number plays when it's the one asked for`() {
        val novela = SmartSourceRequest(
            title = "El Señor de los Cielos", year = 2013, isSeries = true, season = 9, episode = 45,
            runtimeMinutes = 45, originalLanguage = AudioLanguage.SPANISH
        )
        val result = rank(
            novela,
            latam("e45", "El Señor de los Cielos - Episodio 45 1080p"),
            latam("e46", "El Señor de los Cielos - Episodio 46 1080p")
        )
        assertEquals(listOf("https://example.com/e45"), result.candidates.map { it.stream.url })
        assertTrue(result.candidates.single().scored.source.isSpanishServer)
    }

    private fun latam(id: String, title: String) = Stream(
        name = "LATAM\n1080p",
        title = title,
        description = null,
        url = "https://example.com/$id",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null, bingeGroup = null, countryWhitelist = null, proxyHeaders = null,
            filename = title.replace(' ', '.') + ".mp4"
        ),
        addonName = "Addon LATAM",
        addonLogo = null
    )

    private fun streams(vararg streams: Stream) = streams.map(SourceProviderAdapters::normalize)

    private fun rank(request: SmartSourceRequest, vararg streams: Stream) =
        AutoStreamEngine.rank(streams(*streams), request, canVerifySize = true)

    private fun hashes(result: AutoStreamResult) = result.candidates.map { it.stream.infoHash }

    private fun reason(result: AutoStreamResult, hash: String) =
        result.rejected.first { it.first.stream.infoHash == hash }.second

    private fun stream(hash: String, release: String, sizeGb: Double?, fileIdx: Int? = null) = Stream(
        name = "[RD+] Torrentio\n4k",
        title = release,
        description = null,
        url = "https://example.com/$hash",
        ytId = null,
        infoHash = hash,
        fileIdx = fileIdx,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = null,
            videoSize = sizeGb?.let { (it * GB).toLong() }
        ),
        addonName = "Torrentio RD",
        addonLogo = null
    )

    private companion object {
        const val GB = 1_073_741_824L
    }
}
