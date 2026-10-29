package com.nuvio.tv.core.smartscraper

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import java.text.Normalizer
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RCTV: the player's list of sources (its cloud button) and each source's "The Batman (2022)" line. */
class AutoStreamPanelTest {

    private val batman = SmartSourceRequest(title = "The Batman", year = 2022, isSeries = false, runtimeMinutes = 176)

    private fun batmanStreams() = listOf(
        stream("too-big", "The.Batman.2022.2160p.WEB-DL.DDP5.1.HDR.HEVC", 9.1),
        stream("remux-60", "The.Batman.2022.2160p.BluRay.REMUX.TrueHD.7.1.Atmos.HEVC", 60.0),
        stream("1989", "Batman.1989.2160p.BluRay.DTS-HD.MA.5.1.HEVC", 6.0),
        stream("1080-uncached", "The.Batman.2022.1080p.WEB-DL.DDP5.1.x264", 4.0, name = "[RD download] Torrentio\n1080p"),
        stream("1080", "The.Batman.2022.1080p.WEB-DL.DDP5.1.x264", 5.2),
        stream("stereo", "The.Batman.2022.2160p.WEB-DL.AAC2.0.HEVC", 6.9),
        stream("atmos", "The.Batman.2022.2160p.WEB-DL.DDP5.1.Atmos.HDR.HEVC", 7.4),
        stream("720", "The.Batman.2022.720p.WEB-DL.DDP5.1.x264", 2.0),
        stream("1080-small", "The.Batman.2022.1080p.WEB-DL.DDP5.1.x264", 3.9)
    )

    @Test
    fun `nothing over 8 GB, never another title, best quality first`() {
        val shown = panel(batmanStreams())
        assertEquals(listOf("atmos", "stereo", "1080", "1080-small", "1080-uncached", "720"), shown)
    }

    @Test
    fun `the connection decides how big - 8 GB when it keeps up, else 6, 4, 2`() {
        // 176 min: 7.4 GB ≈ 6.0 Mbps (× 1.5 = 9.0), 5.2 GB ≈ 4.2 (6.3), 3.9 GB ≈ 3.2 (4.7), 2 GB ≈ 1.6 (2.4).
        val fast = SmartRankingContext(connection = ConnectionEstimate(ConnectionProfile.FAST, availableMbps = 100.0))
        assertEquals(listOf("atmos", "stereo", "1080", "1080-small", "1080-uncached", "720"), panel(batmanStreams(), fast))
        val eight = SmartRankingContext(connection = ConnectionEstimate(ConnectionProfile.SLOW, availableMbps = 8.0))
        assertEquals(listOf("1080", "1080-small", "1080-uncached", "720"), panel(batmanStreams(), eight))
        val five = SmartRankingContext(connection = ConnectionEstimate(ConnectionProfile.SLOW, availableMbps = 5.0))
        assertEquals(listOf("1080-small", "1080-uncached", "720"), panel(batmanStreams(), five))
        val three = SmartRankingContext(connection = ConnectionEstimate(ConnectionProfile.SLOW, availableMbps = 3.0))
        assertEquals(listOf("720"), panel(batmanStreams(), three))
    }

    @Test
    fun `a connection that keeps up with none still gets every source within 8 GB`() {
        val crawling = SmartRankingContext(connection = ConnectionEstimate(ConnectionProfile.SLOW, availableMbps = 0.5))
        assertEquals(listOf("atmos", "stereo", "1080", "1080-small", "1080-uncached", "720"), panel(batmanStreams(), crawling))
    }

    @Test
    fun `a size not given comes after every given one, never an unsized remux`() {
        val shown = panel(
            listOf(
                stream("4k-unsized", "The.Batman.2022.2160p.WEB-DL.DDP5.1.HEVC", null),
                stream("remux-unsized", "The.Batman.2022.1080p.BluRay.REMUX.DTS-HD.MA.5.1", null),
                stream("720", "The.Batman.2022.720p.WEB-DL.DDP5.1.x264", 2.0),
                stream("1080", "The.Batman.2022.1080p.WEB-DL.DDP5.1.x264", 5.2)
            )
        )
        assertEquals(listOf("1080", "720", "4k-unsized"), shown)
    }

    @Test
    fun `an episode's list has only that episode`() {
        val request = SmartSourceRequest(title = "The Last of Us", year = 2023, isSeries = true, season = 1, episode = 3)
        val shown = AutoStreamEngine.panelSources(
            listOf(
                stream("e3", "The.Last.of.Us.S01E03.1080p.WEB-DL.DDP5.1.H.264", 2.5),
                stream("e4", "The.Last.of.Us.S01E04.1080p.WEB-DL.DDP5.1.H.264", 2.5),
                stream("s2e3", "The.Last.of.Us.S02E03.2160p.WEB-DL.DDP5.1.HEVC", 5.0),
                stream("e3-4k", "The.Last.of.Us.S01E03.2160p.WEB-DL.DDP5.1.HEVC", 7.0)
            ).map(SourceProviderAdapters::normalize),
            request
        ).map { it.stream.infoHash }
        assertEquals(listOf("e3-4k", "e3"), shown)
    }

    @Test
    fun `the list keeps the very streams it was given`() {
        val streams = batmanStreams()
        val shown = AutoStreamEngine.panelSources(streams.map(SourceProviderAdapters::normalize), batman).map { it.stream }
        assertTrue(shown.isNotEmpty() && shown.all { kept -> streams.any { it === kept } })
        assertEquals(emptyList<ScoredSource>(), AutoStreamEngine.panelSources(emptyList(), batman))
    }

    // ==================================================================== The title line

    @Test
    fun `the title line - the title's own name and the file's year`() {
        fun line(release: String, name: String? = "The Batman", year: Int? = 2022) =
            SourceTitleLine.of(ReleaseParser.parseName(release), name, year, null, null)
        assertEquals("The Batman (2022)", line("The.Batman.2022.2160p.WEB-DL.DDP5.1.HEVC"))
        assertEquals("The Batman (2022)", line("the batman 2022 1080p"))
        // No year in the file: the title's.
        assertEquals("The Batman (2022)", line("The.Batman.2160p.WEB-DL.DDP5.1.HEVC"))
        // Another name in the file: the file's, as a title.
        assertEquals("El Planeta De Los Simios (2024)", line("El.Planeta.de.los.Simios.2024.1080p.WEB-DL", "Kingdom of the Planet of the Apes", 2024))
        assertEquals("Rocky II (1979)", line("Rocky.II.1979.1080p.BluRay", null, null))
        // Only the file's own name is cleaned (a title that is a service's word stays).
        assertEquals("Comet (2014)", SourceTitleLine.of(ReleaseParser.parseName("Comet.2014.1080p.WEB-DL"), "Comet", 2014, null, null) { "" })
        assertEquals("Orion (2014)", SourceTitleLine.of(ReleaseParser.parseName("Something.Else.2014.1080p"), "Orion", 2014, null, null) { "" })
        // No name anywhere: no line.
        assertNull(SourceTitleLine.of(ParsedRelease(), null, null, null, null))
        assertEquals("The Batman", SourceTitleLine.of(ParsedRelease(), "The Batman", null, null, null))
    }

    @Test
    fun `the title line of an episode - the show and its season and episode`() {
        val release = ReleaseParser.parseName("The.Last.of.Us.S01E03.1080p.WEB-DL.DDP5.1.H.264")
        assertEquals("The Last of Us · S1 E3", SourceTitleLine.of(release, "The Last of Us", 2023, 1, 3))
    }

    @Test
    fun `the title matcher's normalize is the expression it replaced`() {
        fun regex(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
                .lowercase(Locale.ROOT)
                .replace("&", " and ")
                .replace(Regex("['’`´]"), "")
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        listOf(
            "", " ", "The Batman", "Squidward's  Tiki-Land!", "Amélie & Co", "Mission: Impossible – Part One",
            "Mission.Impossible.Part.1", "L’Été", "Rock´n´Roll", "`quoted`", "Pokémon: Mewtwo's Revenge",
            "東京ヴェルディ", "Спартак", "İstanbul", "Straße", "ﬁnal", "a&b&&c", "Ⅻ ² ½", "tab\tnew\nline",
            "é combining", "'́", "WALL·E", "Se7en", "M*A*S*H"
        ).forEach { assertEquals("\"$it\"", regex(it), TitleMatcher.normalize(it)) }
    }

    // ==================================================================== Helpers

    private fun panel(streams: List<Stream>, context: SmartRankingContext = SmartRankingContext.DEFAULT): List<String?> =
        AutoStreamEngine.panelSources(streams.map(SourceProviderAdapters::normalize), batman, context).map { it.stream.infoHash }

    private fun stream(hash: String, release: String, sizeGb: Double?, name: String = "[RD+] Torrentio\n4k") = Stream(
        name = name,
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
            videoSize = sizeGb?.let { (it * ParsedRelease.BYTES_PER_GB).toLong() }
        ),
        addonName = "Torrentio RD",
        addonLogo = null
    )
}
