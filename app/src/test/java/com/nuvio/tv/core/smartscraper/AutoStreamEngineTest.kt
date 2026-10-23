package com.nuvio.tv.core.smartscraper

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoStreamEngineTest {

    private val movie = SmartSourceRequest(title = "Movie", year = 2020, isSeries = false, runtimeMinutes = 120)

    @Test
    fun `only under 6 GB with 5 1 or stereo, the highest resolution first`() {
        // The example of Auto Stream's rules: 7.5 GB, 6.2 GB and 7.1 are left out.
        val result = rank(
            stream("a", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 7.5),
            stream("b", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 6.2),
            stream("c", "Movie.2020.2160p.BluRay.TrueHD.7.1.HEVC", 5.8),
            stream("d", "Movie.2020.2160p.WEB-DL.AAC2.0.HEVC", 5.6),
            stream("e", "Movie.2020.1080p.WEB-DL.DDP5.1.x264", 5.4),
            stream("f", "Movie.2020.720p.WEB-DL.DDP5.1.x264", 3.2)
        )
        assertEquals(listOf("d", "e", "f"), result.candidates.map { it.stream.infoHash })
        assertEquals(AutoStreamReject.TOO_LARGE, result.rejected.first { it.first.stream.infoHash == "a" }.second)
        assertEquals(AutoStreamReject.TOO_LARGE, result.rejected.first { it.first.stream.infoHash == "b" }.second)
        assertEquals(AutoStreamReject.AUDIO_NOT_ALLOWED, result.rejected.first { it.first.stream.infoHash == "c" }.second)
    }

    @Test
    fun `6 GB itself is over the limit, just under it isn't`() {
        val result = rank(
            stream("six", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 6.0),
            stream("under", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 5.9)
        )
        assertEquals(listOf("under"), result.candidates.map { it.stream.infoHash })
    }

    @Test
    fun `5 1 beats stereo at the same resolution`() {
        val result = rank(
            stream("stereo", "Movie.2020.2160p.WEB-DL.AAC2.0.HEVC", 5.4),
            stream("surround", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 5.7)
        )
        assertEquals("surround", result.best?.stream?.infoHash)
    }

    @Test
    fun `a slow connection plays what it keeps up with`() {
        val slow = SmartRankingContext(connection = ConnectionEstimate(ConnectionProfile.SLOW, availableMbps = 8.0))
        val result = rank(
            stream("4k", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 5.6),
            stream("1080", "Movie.2020.1080p.WEB-DL.DDP5.1.x264", 5.4),
            stream("720", "Movie.2020.720p.WEB-DL.DDP5.1.x264", 3.2),
            context = slow
        )
        assertEquals("720", result.best?.stream?.infoHash)
        assertEquals("4k", rank(stream("4k", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 5.6), stream("720", "Movie.2020.720p.WEB-DL.DDP5.1.x264", 3.2)).best?.stream?.infoHash)
    }

    @Test
    fun `wrong year and wrong episode are never played`() {
        val result = rank(
            stream("old", "Movie.1987.1080p.WEB-DL.DDP5.1.x264", 4.0),
            stream("right", "Movie.2020.720p.WEB-DL.DDP5.1.x264", 2.0)
        )
        assertEquals(listOf("right"), result.candidates.map { it.stream.infoHash })
        val episode = AutoStreamEngine.rank(
            listOf(
                stream("s1", "Show.S01E04.1080p.WEB-DL.DDP5.1.x264", 1.8),
                stream("s2", "Show.S02E04.1080p.WEB-DL.DDP5.1.x264", 1.8)
            ).map(SourceProviderAdapters::normalize),
            SmartSourceRequest(title = "Show", year = null, isSeries = true, season = 2, episode = 4)
        )
        assertEquals(listOf("s2"), episode.candidates.map { it.stream.infoHash })
    }

    @Test
    fun `resolution comes strictly first, then audio said to be 5 1 or stereo`() {
        val result = rank(
            stream("unsaid", "Movie.2020.2160p.WEB-DL.HEVC", 5.5),
            stream("said", "Movie.2020.1080p.WEB-DL.DDP5.1.x264", 4.5),
            stream("hd", "Movie.2020.720p.WEB-DL.DDP5.1.x264", 2.0)
        )
        assertEquals(listOf("unsaid", "said", "hd"), result.candidates.map { it.stream.infoHash })
    }

    @Test
    fun `480p before 360p when nothing better exists`() {
        val result = rank(
            stream("360", "Movie.2020.360p.WEB-DL.AAC2.0.x264", 0.5),
            stream("480", "Movie.2020.480p.WEB-DL.AAC2.0.x264", 0.9)
        )
        assertEquals(listOf("480", "360"), result.candidates.map { it.stream.infoHash })
    }

    @Test
    fun `a 1080p TV without HDR gets 1080p, then a 4K without HDR, never 4K HDR first`() {
        val tv = SmartRankingContext(
            device = DeviceProfile(maxResolution = VideoResolution.FHD_1080, supportsHdr10 = false, isKnown = true)
        )
        val result = rank(
            stream("4khdr", "Movie.2020.2160p.WEB-DL.DDP5.1.HDR10.HEVC", 5.6),
            stream("4ksdr", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 5.4),
            stream("1080", "Movie.2020.1080p.WEB-DL.DDP5.1.x264", 4.0),
            stream("720", "Movie.2020.720p.WEB-DL.DDP5.1.x264", 2.0),
            context = tv
        )
        assertEquals(listOf("1080", "4ksdr", "720", "4khdr"), result.candidates.map { it.stream.infoHash })
    }

    @Test
    fun `a 4K HDR TV gets 4K HDR, Dolby Vision only when it shows it`() {
        val tv = SmartRankingContext(
            device = DeviceProfile(
                maxResolution = VideoResolution.UHD_2160,
                supportsHdr10 = true,
                supportsDolbyVision = false,
                isKnown = true
            )
        )
        val result = rank(
            stream("dvonly", "Movie.2020.2160p.WEB-DL.DDP5.1.DV.HEVC", 5.6),
            stream("hdr10", "Movie.2020.2160p.WEB-DL.DDP5.1.HDR10.HEVC", 5.5),
            stream("1080", "Movie.2020.1080p.WEB-DL.DDP5.1.x264", 4.0),
            context = tv
        )
        assertEquals("hdr10", result.best?.stream?.infoHash)
        assertEquals("dvonly", result.candidates.last().stream.infoHash)
        assertTrue(result.bestIsIdeal(tv))
        val only1080 = rank(stream("1080", "Movie.2020.1080p.WEB-DL.DDP5.1.x264", 4.0), context = tv)
        assertTrue(!only1080.bestIsIdeal(tv))
    }

    @Test
    fun `when nothing is within the limits the best of the rest still plays, never a list`() {
        val result = rank(
            stream("big", "Movie.2020.2160p.BluRay.TrueHD.7.1.HEVC", 12.0),
            stream("bigger", "Movie.2020.2160p.BluRay.REMUX.TrueHD.7.1.HEVC", 40.0)
        )
        assertTrue(result.relaxed)
        assertEquals(2, result.candidates.size)
    }

    @Test
    fun `the same file from several services is one choice, its cached copy first`() {
        val result = rank(
            stream("same", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 5.5, name = "[RD download] Torrentio\n4k", addon = "Torrentio RD"),
            stream("same", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 5.5, name = "[TB+] Torrentio\n4k", addon = "Torrentio TB"),
            stream("same", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 5.5, name = "[PM+] Torrentio\n4k", addon = "Torrentio PM")
        )
        assertEquals(1, result.candidates.size)
        assertEquals(1, result.candidates.single().copies.size)
        assertTrue(result.best!!.stream.name!!.contains("+]"))
        assertEquals(2, result.chain.size)
    }

    @Test
    fun `what will play is told by what it is`() {
        val release = ReleaseParser.parse(
            ReleaseParser.Input(names = listOf("Movie.2020.2160p.WEB-DL.DDP5.1.HDR10.HEVC"), sizeBytes = (5.7 * GB).toLong())
        )
        assertEquals("4K · 5.7 GB · 5.1 · HDR10", AutoStreamEngine.summary(release))
    }

    @Test
    fun `the language chosen comes first, LatinoDebrid's being Spanish`() {
        val english4k = stream("en", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 5.6)
        val latino = stream("es", "Movie.2020.1080p.WEB-DL.DD5.1.x264", 3.1, name = "LatinoDebrid\n1080p", addon = "LatinoDebrid")
        val spanish = AutoStreamEngine.rank(
            listOf(english4k, latino).map(SourceProviderAdapters::normalize),
            movie.copy(preferredLanguage = AudioLanguage.SPANISH, originalLanguage = AudioLanguage.ENGLISH)
        )
        assertEquals("es", spanish.best?.stream?.infoHash)
        val english = AutoStreamEngine.rank(
            listOf(english4k, latino).map(SourceProviderAdapters::normalize),
            movie.copy(preferredLanguage = AudioLanguage.ENGLISH, originalLanguage = AudioLanguage.ENGLISH)
        )
        assertEquals("en", english.best?.stream?.infoHash)
    }

    @Test
    fun `audio languages are read from words and flags, never from subtitles`() {
        assertEquals(
            setOf(AudioLanguage.SPANISH, AudioLanguage.ENGLISH),
            ReleaseParser.parseName("Movie.2020.1080p.WEB-DL.Dual.Latino-Ingles.DD5.1").audioLanguages
        )
        assertEquals(emptySet<AudioLanguage>(), ReleaseParser.parseName("Movie.2020.1080p.WEB-DL.Sub.Español").audioLanguages)
        assertEquals(setOf(AudioLanguage.SPANISH), ReleaseParser.audioLanguagesIn("💾 2 GB 🇲🇽"))
        assertEquals(setOf(AudioLanguage.SPANISH), ReleaseParser.parseName("Pelicula.2020.1080p.Castellano.AC3").audioLanguages)
    }

    private fun rank(vararg streams: Stream, context: SmartRankingContext = SmartRankingContext.DEFAULT) =
        AutoStreamEngine.rank(streams.map(SourceProviderAdapters::normalize), movie, context)

    private fun stream(
        hash: String,
        release: String,
        sizeGb: Double,
        name: String = "[RD+] Torrentio\n4k",
        addon: String = "Torrentio RD"
    ) = Stream(
        name = name,
        title = release,
        description = null,
        url = "https://example.com/$hash/$addon",
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
        addonName = addon,
        addonLogo = null
    )

    private companion object {
        const val GB = 1_073_741_824L
    }
}
