package com.nuvio.tv.core.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class LogSafeUrlTest {

    @Test
    fun `only the scheme and host of an address are logged`() {
        assertEquals(
            "https://torrentio.example/…",
            "https://torrentio.example/realdebrid=SECRETKEY/stream/movie/tt1.json".forLog()
        )
        assertEquals(
            "http://iptv.example/…",
            "http://iptv.example:8080/player_api.php?username=me&password=secret".forLog()
        )
        assertEquals("<url>", "not an address".forLog())
        assertEquals("<url>", (null as String?).forLog())
    }

    @Test
    fun `a parsed address keeps only its scheme and host too`() {
        // What the crash service's network breadcrumbs carry (RCTV-AUD-011, 021).
        assertEquals(
            "https://addon.example/…",
            "https://addon.example/eyJkZWJyaWQiOiJTRUNSRVQifQ==/manifest.json?token=abc".toHttpUrl().forLog()
        )
    }
}
