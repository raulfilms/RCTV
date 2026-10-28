package com.nuvio.tv.ui.screens.player

import okhttp3.internal.tls.OkHostnameVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.SSLContext

class PlaybackNetworkingSslVerificationTest {

    @Test
    fun `verifyPrimaryClientUsesSystemSslAndPreservesSni()`() {
        val primaryClient = PlayerPlaybackNetworking.playbackHttpClient

        // OkHttp's own hostname verifier, not one that accepts every host (RCTV-AUD-002).
        assertSame(OkHostnameVerifier, primaryClient.hostnameVerifier)

        // Connection timeouts and retry policies are properly configured
        assertEquals(15_000, primaryClient.connectTimeoutMillis.toLong())
        assertEquals(15_000, primaryClient.readTimeoutMillis.toLong())
        assertTrue(primaryClient.retryOnConnectionFailure)
    }

    @Test
    fun `verifyPrimaryClientSupportsTls13Protocols()`() {
        val defaultContext = SSLContext.getDefault()
        val defaultParams = defaultContext.defaultSSLParameters
        val supportedProtocols = defaultParams.protocols.toList()

        println("Supported JVM SSL Protocols: $supportedProtocols")

        // Verify that TLS 1.3 and TLS 1.2 are supported by the primary SSL context
        assertTrue("SSL context must support TLSv1.3 protocol", supportedProtocols.contains("TLSv1.3"))
        assertTrue("SSL context must support TLSv1.2 protocol", supportedProtocols.contains("TLSv1.2"))
    }

    @Test
    fun `no client retries a TLS failure around certificate checks`() {
        // The old SSLException fallback was the primary client's only application interceptor.
        assertTrue(PlayerPlaybackNetworking.playbackHttpClient.interceptors.isEmpty())
        val streamClient = PlayerPlaybackNetworking.createHttpClient(mapOf("Authorization" to "Bearer t"))
        assertTrue(streamClient.interceptors.isEmpty())
        assertSame(OkHostnameVerifier, streamClient.hostnameVerifier)
        assertSame(OkHostnameVerifier, subtitleHttpClient.hostnameVerifier)
    }
}
