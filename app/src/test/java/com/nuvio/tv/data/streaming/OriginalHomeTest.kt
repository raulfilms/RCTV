package com.nuvio.tv.data.streaming

import com.nuvio.tv.R
import com.nuvio.tv.data.remote.api.TmdbWatchProviderEntry
import com.nuvio.tv.domain.model.MetaCompany
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OriginalHomeTest {

    private val prime = TmdbWatchProviderEntry(providerId = 9, providerName = "Amazon Prime Video", logoPath = "/prime.jpg")
    private val hulu = TmdbWatchProviderEntry(providerId = 15, providerName = "Hulu", logoPath = "/hulu.jpg")
    private val netflix = TmdbWatchProviderEntry(providerId = 8, providerName = "Netflix", logoPath = "/netflix.jpg")

    @Test
    fun `an FX show is Hulu's, though it's on Prime Video first`() {
        val home = OriginalHome.of(true, listOf(MetaCompany("FX", "https://x/fx.png")), emptyList(), listOf(prime, hulu))
        assertEquals("Hulu", home?.name)
        assertEquals(R.drawable.disney_brand_logo_hulu, home?.logoRes)
        assertEquals("https://image.tmdb.org/t/p/w92/hulu.jpg", home?.iconUrl)
    }

    @Test
    fun `a network with no service of its own shows its own logo, not where it streams`() {
        val home = OriginalHome.of(true, listOf(MetaCompany("AMC", "https://x/amc.png")), emptyList(), listOf(netflix))
        assertEquals("AMC", home?.name)
        assertNull(home?.logoRes)
        assertEquals("https://x/amc.png", home?.logoUrl)
    }

    @Test
    fun `a movie is its studio's service, else the service that's its only home`() {
        val dune = OriginalHome.of(
            false, emptyList(),
            listOf(MetaCompany("Legendary Pictures", "https://x/l.png"), MetaCompany("Warner Bros. Pictures", "https://x/wb.png")),
            listOf(prime)
        )
        assertEquals(R.drawable.streaming_logo_hbomax, dune?.logoRes)
        val pixar = OriginalHome.of(false, emptyList(), listOf(MetaCompany("Pixar", null)), emptyList())
        assertEquals("Disney+", pixar?.name)
        val original = OriginalHome.of(false, emptyList(), listOf(MetaCompany("Seven Bucks Productions", null)), listOf(netflix))
        assertEquals(R.drawable.streaming_logo_netflix, original?.logoRes)
        assertEquals(R.drawable.streaming_logo_a24, OriginalHome.of(false, emptyList(), listOf(MetaCompany("A24", null)), emptyList())?.logoRes)
        assertNull(OriginalHome.of(false, emptyList(), emptyList(), emptyList()))
    }
}
