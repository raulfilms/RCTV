package com.nuvio.tv.core.smartscraper

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartScraperTest {

    @Test
    fun `release names are read and normalized`() {
        val movie = ReleaseParser.parseName("Movie.Title.2022.2160p.WEB-DL.DDP5.1.Atmos.H.265-GRP")
        assertEquals("movie title", movie.title)
        assertEquals(2022, movie.year)
        assertEquals(VideoResolution.UHD_2160, movie.resolution)
        assertEquals(ReleaseSource.WEB_DL, movie.source)
        assertEquals(AudioCodec.DDP, movie.audioCodec)
        assertEquals(AudioChannels.CH_5_1, movie.channels)
        assertTrue(movie.atmos)
        assertEquals(VideoCodec.HEVC, movie.codec)

        val remux = ReleaseParser.parseName("The.Batman.2022.2160p.UHD.BluRay.x265.HDR.DV.TrueHD.Atmos.7.1-GRP.mkv")
        assertTrue(HdrFormat.DOLBY_VISION in remux.hdr)
        assertEquals(AudioCodec.TRUEHD, remux.audioCodec)
        assertEquals(AudioChannels.CH_7_1, remux.channels)

        assertEquals("blade runner 2049", ReleaseParser.parseName("Blade.Runner.2049.2017.1080p.BluRay").title)
        assertEquals(VideoResolution.UHD_2160, ReleaseParser.parseName("Movie.2020.3840x2160.HEVC").resolution)
        assertEquals(AudioCodec.DDP, ReleaseParser.parseName("Movie.2020.1080p.WEB-DL.EAC3.5.1").audioCodec)
        assertEquals(ReleaseSource.CAM, ReleaseParser.parseName("Movie.2023.HDCAM.x264").source)
    }

    @Test
    fun `a size is never read as audio channels`() {
        val parsed = ReleaseParser.parse(ReleaseParser.Input(names = listOf("Movie.2022.2160p.WEB-DL.H265"), text = "👤 12 💾 7.1 GB"))
        assertEquals(AudioChannels.UNKNOWN, parsed.channels)
        assertTrue((parsed.sizeBytes ?: 0) > 7_000_000_000L)
    }

    @Test
    fun `seasons, episodes and season packs`() {
        val episode = ReleaseParser.parseName("Breaking.Bad.S05E14.Ozymandias.1080p.WEB-DL")
        assertEquals(listOf(5), episode.seasons)
        assertEquals(listOf(14), episode.episodes)
        assertEquals(listOf(1, 2, 3), ReleaseParser.parseName("Show.S01E01-03.720p").episodes)
        val pack = ReleaseParser.parseName("Breaking.Bad.S01-S05.Complete.1080p")
        assertTrue(pack.isSeasonPack)
        assertEquals(listOf(1, 2, 3, 4, 5), pack.seasons)
        assertTrue(ReleaseParser.parseName("Star.Wars.Episode.4.A.New.Hope.1977.1080p").seasons.isEmpty())
    }

    @Test
    fun `wrong year, wrong type and over the size limit are kept out of the smart sources`() {
        val gb = 1_073_741_824L
        val request = SmartSourceRequest(title = "The Batman", year = 2022, isSeries = false, runtimeMinutes = 176)
        val streams = listOf(
            stream("[RD+] Torrentio\n4k", "The.Batman.2022.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265\n💾 7.4 GB", "aaa", (7.4 * gb).toLong()),
            stream("[TB+] Torrentio\n4k", "The.Batman.2022.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265\n💾 7.4 GB", "aaa", (7.4 * gb).toLong()),
            stream("[RD+] Torrentio\n4k", "The.Batman.2022.2160p.BluRay.REMUX.TrueHD.Atmos.7.1\n💾 62 GB", "bbb", 62 * gb),
            stream("[RD+] Torrentio\n1080p", "Batman.1989.1080p.BluRay.x264\n💾 9 GB", "ccc", null),
            stream("[RD+] Torrentio\n1080p", "The.Batman.S01E01.2004.1080p\n💾 1 GB", "ddd", null),
            stream("[RD+] Torrentio\n1080p", "The.Batman.2022.1080p.WEB-DL.DDP5.1.H.264\n💾 6.8 GB", "eee", (6.8 * gb).toLong())
        )
        val result = SmartScoreEngine.rank(streams.map(SourceProviderAdapters::normalize), request)

        assertTrue(result.rejected.any { it.rejection == RejectReason.WRONG_YEAR })
        assertTrue(result.rejected.any { it.rejection == RejectReason.WRONG_TYPE })
        assertTrue(result.smart.none { (it.best.release.sizeGb ?: 0.0) > 8.0 })
        assertTrue(result.all.any { it.rejection == RejectReason.TOO_LARGE })
        assertEquals(VideoResolution.UHD_2160, result.smart.first().best.release.resolution)
        assertEquals(2, result.smart.first().copyCount)
        assertTrue(SmartLabel.BEST_MATCH in result.smart.first().labels)
    }

    @Test
    fun `an episode of another season or number isn't the episode`() {
        val request = SmartSourceRequest(title = "House of the Dragon", year = 2022, isSeries = true, season = 2, episode = 4)
        val result = SmartScoreEngine.rank(
            listOf(
                stream("[RD+] Torrentio\n4k", "House.of.the.Dragon.S02E04.2160p.WEB-DL\n💾 5.2 GB", "e1", null),
                stream("[RD+] Torrentio\n1080p", "House.of.the.Dragon.S01E04.1080p.WEB-DL\n💾 2.2 GB", "e2", null),
                stream("[RD+] Torrentio\n1080p", "House.of.the.Dragon.S02E05.1080p.WEB-DL\n💾 2.2 GB", "e3", null)
            ).map(SourceProviderAdapters::normalize),
            request
        )
        assertEquals(listOf("e1"), result.all.map { it.stream.infoHash })
        assertFalse(result.rejected.isEmpty())
    }

    @Test
    fun `a slow connection ranks a lighter file first`() {
        val gb = 1_073_741_824L
        val request = SmartSourceRequest(title = "Movie", year = 2020, isSeries = false, runtimeMinutes = 120)
        val streams = listOf(
            stream("[RD+] Torrentio\n4k", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC\n💾 7.8 GB", "a", (7.8 * gb).toLong()),
            stream("[RD+] Torrentio\n720p", "Movie.2020.720p.WEB-DL.DDP5.1.x264\n💾 2.6 GB", "b", (2.6 * gb).toLong())
        ).map(SourceProviderAdapters::normalize)
        val slow = SmartScoreEngine.rank(streams, request, SmartRankingContext(connection = ConnectionEstimate(ConnectionProfile.SLOW, 8.0)))
        assertEquals("b", slow.smart.first().stream.infoHash)
        val fast = SmartScoreEngine.rank(streams, request, SmartRankingContext(connection = ConnectionEstimate(ConnectionProfile.FAST, 300.0)))
        assertEquals("a", fast.smart.first().stream.infoHash)
    }

    private fun stream(name: String, title: String, hash: String, size: Long?) = Stream(
        name = name,
        title = title,
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
            videoSize = size
        ),
        addonName = if (name.contains("TB")) "Torrentio TB" else "Torrentio RD",
        addonLogo = null
    )
}
