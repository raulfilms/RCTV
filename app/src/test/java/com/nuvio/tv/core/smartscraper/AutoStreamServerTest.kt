package com.nuvio.tv.core.smartscraper

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RCTV: the servers, told apart inside the app (never shown): AIO Stream (Main, IMAX, Extended,
 * Dubbed), Torrentio (TorBox, Real-Debrid, Premiumize), LatinoDebrid, Addon LATAM, a plugin
 * (All-in-One-Nuvio); and their order: the resolution, then the sound, then the server.
 */
class AutoStreamServerTest {

    private val movie = SmartSourceRequest(title = "Movie", year = 2020, isSeries = false, runtimeMinutes = 120)

    @Test
    fun `each server is told apart`() {
        fun identity(addon: String, name: String, plugin: String? = null) =
            SourceProviderAdapters.normalize(stream("x", RELEASE_4K, 5.0, name = name, addon = addon, plugin = plugin)).provider
        assertEquals(ProviderFamily.AIOSTREAMS to ProviderVariant.MAIN, identity("AIOStreams", "[TB+] AIOStreams 4K").let { it.family to it.variant })
        assertEquals(ProviderVariant.IMAX, identity("AIOStreams IMAX", "[RD+] AIOStreams 4K").variant)
        assertEquals(ProviderVariant.EXTENDED, identity("AIOStreams Extended", "[RD+] AIOStreams 4K").variant)
        assertEquals(ProviderVariant.DUBBED, identity("AIOStreams Dubbed", "[RD+] AIOStreams 4K").variant)
        // An AIO stream that names Torrentio (its source) is still AIO Stream's.
        assertEquals(ProviderFamily.AIOSTREAMS, identity("AIOStreams", "[RD+] Torrentio 4K").family)
        assertEquals(ProviderFamily.TORRENTIO to DebridService.TORBOX, identity("Torrentio TB", "[TB+] Torrentio\n4k").let { it.family to it.debrid })
        assertEquals(DebridService.REAL_DEBRID, identity("Torrentio RD", "[RD+] Torrentio\n4k").debrid)
        assertEquals(DebridService.PREMIUMIZE, identity("Torrentio PM", "[PM+] Torrentio\n4k").debrid)
        assertEquals(ProviderFamily.LATINODEBRID, identity("LatinoDebrid", "LatinoDebrid\n1080p").family)
        assertEquals(ProviderFamily.LATAM, identity("Addon LATAM", "LATAM\n1080p").family)
        assertEquals(ProviderFamily.PLUGIN, identity("MoviesDrive", "MoviesDrive - 4K", plugin = "All-in-One-Nuvio").family)
        assertEquals("All-in-One-Nuvio", identity("MoviesDrive", "MoviesDrive - 4K", plugin = "All-in-One-Nuvio").key)
        assertEquals(ProviderFamily.OTHER, identity("Something", "Something 4K").family)
        // LatinoDebrid and Addon LATAM are Spanish.
        val latam = SourceProviderAdapters.normalize(stream("x", RELEASE_4K, 5.0, name = "LATAM", addon = "Addon LATAM"))
        assertEquals(setOf(AudioLanguage.SPANISH), latam.providerLanguages)
    }

    @Test
    fun `the same sound - AIO Stream, then Torrentio by TorBox, Real-Debrid, Premiumize, then a plugin, then the rest`() {
        val result = rank(
            stream("other", RELEASE_4K, 5.0, name = "[RD+] Something", addon = "Something"),
            stream("plugin", RELEASE_4K, 5.0, name = "All-in-One 4K", addon = "MoviesDrive", plugin = "All-in-One-Nuvio", url = "https://example.com/plugin.mkv", hash = null),
            stream("pm", RELEASE_4K, 5.0, name = "[PM+] Torrentio\n4k", addon = "Torrentio PM"),
            stream("rd", RELEASE_4K, 5.0, name = "[RD+] Torrentio\n4k", addon = "Torrentio RD"),
            stream("tb", RELEASE_4K, 5.0, name = "[TB+] Torrentio\n4k", addon = "Torrentio TB"),
            stream("aio", RELEASE_4K, 5.0, name = "[RD+] AIOStreams 4K", addon = "AIOStreams")
        )
        assertEquals(listOf("aio", "tb", "rd", "pm", "plugin", "other"), result.candidates.map { it.stream.addonLogo })
    }

    @Test
    fun `the resolution first, then 5_1 before 2_0, and only then the server`() {
        val result = rank(
            stream("aio-1080", "Movie.2020.1080p.WEB-DL.DDP5.1.x264", 4.0, name = "[RD+] AIOStreams 1080p", addon = "AIOStreams"),
            stream("aio-4k-2.0", "Movie.2020.2160p.WEB-DL.DDP2.0.HEVC", 5.0, name = "[RD+] AIOStreams 4K", addon = "AIOStreams"),
            stream("tio-4k-5.1", RELEASE_4K, 5.0, name = "[RD+] Torrentio\n4k", addon = "Torrentio RD"),
            stream("aio-4k-5.1", RELEASE_4K, 4.8, name = "[RD+] AIOStreams 4K", addon = "AIOStreams")
        )
        assertEquals(listOf("aio-4k-5.1", "tio-4k-5.1", "aio-4k-2.0", "aio-1080"), result.candidates.map { it.stream.addonLogo })
    }

    @Test
    fun `the cloud's list keeps the same order`() {
        val shown = AutoStreamEngine.panelSources(
            listOf(
                stream("tio-4k-5.1", RELEASE_4K, 5.0, name = "[RD+] Torrentio\n4k", addon = "Torrentio RD"),
                stream("aio-4k-2.0", "Movie.2020.2160p.WEB-DL.DDP2.0.HEVC", 5.0, name = "[RD+] AIOStreams 4K", addon = "AIOStreams"),
                stream("aio-4k-5.1", RELEASE_4K, 4.8, name = "[RD+] AIOStreams 4K", addon = "AIOStreams")
            ).map(SourceProviderAdapters::normalize),
            movie
        ).map { it.stream.addonLogo }
        assertEquals(listOf("aio-4k-5.1", "tio-4k-5.1", "aio-4k-2.0"), shown)
    }

    @Test
    fun `a plugin's own link plays though no debrid service says it's ready, a debrid addon's doesn't`() {
        val result = rank(
            stream("plugin", RELEASE_4K, 5.0, name = "All-in-One 4K", addon = "MoviesDrive", plugin = "All-in-One-Nuvio", url = "https://example.com/p.mkv", hash = null),
            stream("unsaid", RELEASE_4K, 5.0, name = "Something 4K", addon = "Something", url = "https://example.com/s.mkv", hash = null)
        )
        assertEquals(listOf("plugin"), result.candidates.map { it.stream.addonLogo })
        assertEquals(AutoStreamReject.NOT_CACHED, result.rejected.single().second)
    }

    @Test
    fun `a telenovela in Spanish may play from Addon LATAM's link, an English title never from it`() {
        val novela = SmartSourceRequest(
            title = "La Reina del Sur", year = 2011, isSeries = true, season = 1, episode = 2,
            originalLanguage = AudioLanguage.SPANISH
        )
        val latam = stream("latam", "La.Reina.del.Sur.S01E02.720p.WEB-DL.AAC2.0", 0.9, name = "LATAM\n720p", addon = "Addon LATAM", url = "https://example.com/l.mp4", hash = null)
        val result = AutoStreamEngine.rank(listOf(latam).map(SourceProviderAdapters::normalize), novela)
        assertEquals(listOf("latam"), result.candidates.map { it.stream.addonLogo })
        val english = SmartSourceRequest(title = "La Reina del Sur", year = 2011, isSeries = true, season = 1, episode = 2)
        assertTrue(AutoStreamEngine.rank(listOf(latam).map(SourceProviderAdapters::normalize), english).candidates.isEmpty())
    }

    @Test
    fun `the order is configuration`() {
        val config = AutoStreamConfig()
        assertEquals(
            listOf(
                ProviderFamily.AIOSTREAMS, ProviderFamily.TORRENTIO, ProviderFamily.LATINODEBRID,
                ProviderFamily.LATAM, ProviderFamily.PLUGIN, ProviderFamily.OTHER
            ),
            config.serverOrder
        )
        assertEquals(listOf(DebridService.TORBOX, DebridService.REAL_DEBRID, DebridService.PREMIUMIZE), config.debridOrder)
        val aio = config.serverRank(ProviderIdentity("a", ProviderFamily.AIOSTREAMS, debrid = DebridService.PREMIUMIZE))
        val torbox = config.serverRank(ProviderIdentity("t", ProviderFamily.TORRENTIO, debrid = DebridService.TORBOX))
        assertTrue(aio < torbox)
    }

    // ==================================================================== Helpers

    private fun rank(vararg streams: Stream) = AutoStreamEngine.rank(streams.map(SourceProviderAdapters::normalize), movie)

    /** [id] is kept in the stream's logo field (never shown here), so each server's copy of a file is told apart. */
    private fun stream(
        id: String,
        release: String,
        sizeGb: Double?,
        name: String,
        addon: String,
        plugin: String? = null,
        url: String = "https://example.com/$id",
        hash: String? = "same-file"
    ) = Stream(
        name = name,
        title = release,
        description = null,
        url = url,
        ytId = null,
        infoHash = hash?.let { "$it-$id" },
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = null,
            videoSize = sizeGb?.let { (it * ParsedRelease.BYTES_PER_GB).toLong() }
        ),
        addonName = addon,
        addonLogo = id,
        pluginRepository = plugin
    )

    private companion object {
        const val RELEASE_4K = "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC"
    }
}
