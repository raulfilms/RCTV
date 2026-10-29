package com.nuvio.tv.core.smartscraper

import com.nuvio.tv.domain.model.Stream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RCTV: an addon set up as a server from the phone is read as that server, whatever its name. */
class AddonRolesTest {

    @After
    fun reset() = AddonRoles.update(emptyMap())

    private fun stream(addon: String, name: String = "[RD+] 4K", plugin: String? = null) = Stream(
        name = name, title = "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", description = null, url = "https://example.com/x",
        ytId = null, infoHash = "x", fileIdx = null, externalUrl = null, behaviorHints = null,
        addonName = addon, addonLogo = null, pluginRepository = plugin
    )

    @Test
    fun `the role decides, not the name`() {
        AddonRoles.update(
            mapOf(
                "AIOStreams" to AddonRole.AIO_MAIN,
                "AIOStreams (2)" to AddonRole.AIO_IMAX,
                "My addon" to AddonRole.TORRENTIO_TB,
                "Cosas" to AddonRole.LATAM
            )
        )
        val imax = SourceProviderAdapters.normalize(stream("AIOStreams (2)")).provider
        assertEquals(ProviderFamily.AIOSTREAMS to ProviderVariant.IMAX, imax.family to imax.variant)
        val torbox = SourceProviderAdapters.normalize(stream("My addon", name = "[RD+] My addon")).provider
        assertEquals(ProviderFamily.TORRENTIO to DebridService.TORBOX, torbox.family to torbox.debrid)
        val latam = SourceProviderAdapters.normalize(stream("Cosas"))
        assertEquals(ProviderFamily.LATAM, latam.provider.family)
        assertEquals(setOf(AudioLanguage.SPANISH), latam.providerLanguages)
        // No role: read by its name, as before; a plugin's stream is never an addon's role.
        assertEquals(ProviderFamily.TORRENTIO, SourceProviderAdapters.normalize(stream("Torrentio RD", name = "[RD+] Torrentio\n4k")).provider.family)
        assertEquals(ProviderFamily.PLUGIN, SourceProviderAdapters.normalize(stream("AIOStreams", plugin = "All-in-One-Nuvio")).provider.family)
        assertEquals(ProviderVariant.IMAX, SourceProviderAdapters.identify(stream("AIOStreams (2)")).variant)
    }

    @Test
    fun `links are kept the app's way`() {
        assertEquals("https://a.example.com/abc", AddonRoles.normalizeUrl(" https://a.example.com/abc/manifest.json "))
        assertEquals("https://a.example.com/abc", AddonRoles.normalizeUrl("stremio://a.example.com/abc/manifest.json"))
        assertNull(AddonRoles.normalizeUrl("ftp://a.example.com"))
        assertNull(AddonRoles.normalizeUrl("just words"))
        assertTrue(AddonRoles.sameAddon("https://A.example.com/abc/", "https://a.example.com/abc/manifest.json"))
        assertEquals(AddonRole.TORRENTIO_PM, AddonRole.ofKey("torrentio_pm"))
        assertNull(AddonRole.ofKey("nope"))
    }
}
