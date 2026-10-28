package com.nuvio.tv.ui.screens.player

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.util.Collections
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * RCTV-AUD-002 and RCTV-AUD-009: the playback clients keep certificate and hostname checks with no
 * fallback, and a stream's credentials only go to its own host when a request is redirected.
 */
class PlaybackTlsAndRedirectTest {

    private val streamHeaders = mapOf(
        "Authorization" to "Bearer token",
        "Cookie" to "session=1",
        "X-Api-Key" to "secret",
        "X-Debrid-Id" to "own",
        "Referer" to "https://addon.example/",
        "User-Agent" to "Player"
    )

    // localhost and 127.0.0.1 are different hosts to OkHttp; both reach the test server over IPv4.
    private val loopbackOnly = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
    }

    @Test
    fun `an untrusted certificate fails and is not retried`() {
        RecordingServer(testTls()).use { server ->
            val url = "https://127.0.0.1:${server.port}/v.mkv"
            listOf(
                PlayerPlaybackNetworking.playbackHttpClient,
                PlayerPlaybackNetworking.createHttpClient(streamHeaders)
            ).forEach { client ->
                val noRetry = client.newBuilder().retryOnConnectionFailure(false).build()
                assertFails<SSLException> { noRetry.newCall(request(url)).execute().close() }
            }
            // No HTTP request reached the server: the handshake failed and nothing went around it.
            assertTrue(server.requestsTo("/v.mkv").isEmpty())
        }
    }

    @Test
    fun `a trusted certificate for another host fails hostname verification`() {
        val tls = testTls()
        RecordingServer(tls).use { server ->
            // The certificate covers 127.0.0.1 only.
            val client = PlayerPlaybackNetworking.createHttpClient(streamHeaders).newBuilder()
                .sslSocketFactory(tls.socketFactory, tls.trustManager)
                .dns(loopbackOnly)
                .retryOnConnectionFailure(false)
                .build()
            assertFails<SSLPeerUnverifiedException> {
                client.newCall(request("https://localhost:${server.port}/v.mkv")).execute().close()
            }
            assertTrue(server.requestsTo("/v.mkv").isEmpty())
        }
    }

    @Test
    fun `an authorized request with a valid certificate still plays`() {
        val tls = testTls()
        RecordingServer(tls).use { server ->
            val client = PlayerPlaybackNetworking.createHttpClient(streamHeaders).newBuilder()
                .sslSocketFactory(tls.socketFactory, tls.trustManager)
                .build()
            client.newCall(request("https://127.0.0.1:${server.port}/v.mkv")).execute().use {
                assertEquals(200, it.code)
            }
            val sent = server.requestsTo("/v.mkv").single()
            assertEquals("Bearer token", sent["authorization"])
            assertEquals("session=1", sent["cookie"])
        }
    }

    @Test
    fun `a redirect to another host gets none of the stream's credentials`() {
        RecordingServer().use { server ->
            server.redirects["/start"] = "http://localhost:${server.port}/landed"
            val client = PlayerPlaybackNetworking.createHttpClient(streamHeaders).newBuilder()
                .dns(loopbackOnly)
                .build()

            client.newCall(request("http://127.0.0.1:${server.port}/start")).execute().use {
                assertEquals(200, it.code)
            }

            server.assertEvery("/start") {
                assertEquals("Bearer token", it["authorization"])
                assertEquals("secret", it["x-api-key"])
            }
            server.assertEvery("/landed") {
                assertNull(it["authorization"])
                assertNull(it["cookie"])
                assertNull(it["x-api-key"])
                assertNull(it["x-debrid-id"])
                assertEquals("https://addon.example/", it["referer"])
                assertEquals("Player", it["user-agent"])
            }
        }
    }

    @Test
    fun `any playback client drops credential headers on a redirect to another host`() {
        RecordingServer().use { server ->
            server.redirects["/start"] = "http://localhost:${server.port}/landed"
            val client = PlayerPlaybackNetworking.playbackHttpClient.newBuilder().dns(loopbackOnly).build()

            client.newCall(request("http://127.0.0.1:${server.port}/start")).execute().use {
                assertEquals(200, it.code)
            }

            server.assertEvery("/landed") {
                assertNull(it["authorization"])
                assertNull(it["cookie"])
                assertNull(it["x-api-key"])
                assertEquals("https://addon.example/", it["referer"])
            }
        }
    }

    @Test
    fun `a redirect on the same host keeps the stream's credentials`() {
        RecordingServer().use { server ->
            RecordingServer().use { otherPort ->
                server.redirects["/start"] = "http://127.0.0.1:${server.port}/middle"
                // Same host, another port: OkHttp drops Authorization here; it is sent again.
                server.redirects["/middle"] = "http://127.0.0.1:${otherPort.port}/landed"
                val client = PlayerPlaybackNetworking.createHttpClient(streamHeaders)

                client.newCall(request("http://127.0.0.1:${server.port}/start")).execute().use {
                    assertEquals(200, it.code)
                }

                listOf(server to "/start", server to "/middle", otherPort to "/landed").forEach { (target, path) ->
                    target.assertEvery(path) {
                        assertEquals(path, "Bearer token", it["authorization"])
                        assertEquals(path, "session=1", it["cookie"])
                        assertEquals(path, "secret", it["x-api-key"])
                        assertEquals(path, "own", it["x-debrid-id"])
                    }
                }
            }
        }
    }

    @Test
    fun `an https to http redirect on the same host gets no credentials`() {
        val tls = testTls()
        RecordingServer(tls).use { httpsServer ->
            RecordingServer().use { httpServer ->
                httpsServer.redirects["/start"] = "http://127.0.0.1:${httpServer.port}/landed"
                val client = PlayerPlaybackNetworking.createHttpClient(streamHeaders).newBuilder()
                    .sslSocketFactory(tls.socketFactory, tls.trustManager)
                    .build()

                client.newCall(request("https://127.0.0.1:${httpsServer.port}/start")).execute().use {
                    assertEquals(200, it.code)
                }

                httpsServer.assertEvery("/start") { assertEquals("Bearer token", it["authorization"]) }
                httpServer.assertEvery("/landed") {
                    assertNull(it["authorization"])
                    assertNull(it["cookie"])
                    assertNull(it["x-api-key"])
                }
            }
        }
    }

    @Test
    fun `chunk requests sent straight to the redirect's host get none of the stream's credentials`() {
        RecordingServer().use { server ->
            // As the parallel-connections path does: the stream is on 127.0.0.1, the chunks are asked
            // for at the address the first request was redirected to (another host), without a redirect.
            val streamUrl = "http://127.0.0.1:${server.port}/v.mkv".toHttpUrl()
            val scope = requireNotNull(
                PlaybackRedirectCredentials.streamHeaderScope(
                    streamHeaders,
                    streamUrl,
                    PlaybackRedirectCredentials.OtherHostRequests.CROSS_HOST_HEADERS_ONLY
                )
            )
            val client = PlayerPlaybackNetworking.playbackHttpClient.newBuilder()
                .addNetworkInterceptor(scope)
                .dns(loopbackOnly)
                .build()

            client.newCall(request("http://localhost:${server.port}/chunk")).execute().use { assertEquals(200, it.code) }
            client.newCall(request("http://127.0.0.1:${server.port}/own")).execute().use { assertEquals(200, it.code) }

            server.assertEvery("/chunk") {
                assertNull(it["authorization"])
                assertNull(it["cookie"])
                assertNull(it["x-api-key"])
                assertNull(it["x-debrid-id"])
                assertEquals("https://addon.example/", it["referer"])
            }
            server.assertEvery("/own") {
                assertEquals("Bearer token", it["authorization"])
                assertEquals("own", it["x-debrid-id"])
            }
        }
    }

    @Test
    fun `segments and sidecar files on another host get the stream's plain headers but no credentials`() {
        val headers = streamHeaders + mapOf("X-Requested-With" to "player", "X-Custom" to "Bearer hidden")
        RecordingServer().use { server ->
            // The stream (a playlist) on 127.0.0.1; its segments on "localhost" (another host, as a CDN).
            val client = PlayerPlaybackNetworking.createHttpClient(headers, "http://127.0.0.1:${server.port}/master.m3u8".toHttpUrl())
                .newBuilder()
                .dns(loopbackOnly)
                .build()
            fun get(url: String) = client.newCall(
                Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
            ).execute().use { assertEquals(200, it.code) }

            get("http://127.0.0.1:${server.port}/master.m3u8")
            get("http://localhost:${server.port}/segment1.ts")

            server.assertEvery("/master.m3u8") {
                assertEquals("Bearer token", it["authorization"])
                assertEquals("session=1", it["cookie"])
                assertEquals("Bearer hidden", it["x-custom"])
            }
            server.assertEvery("/segment1.ts") {
                assertNull(it["authorization"])
                assertNull(it["cookie"])
                assertNull(it["x-api-key"])
                assertNull(it["x-custom"])
                assertEquals("player", it["x-requested-with"])
                assertEquals("own", it["x-debrid-id"])
                assertEquals("https://addon.example/", it["referer"])
                assertEquals("Player", it["user-agent"])
            }
        }
    }

    @Test
    fun `a segment host redirecting elsewhere gets only the cross-host headers there`() {
        val headers = streamHeaders + mapOf("X-Requested-With" to "player")
        RecordingServer().use { server ->
            server.redirects["/segment1.ts"] = "http://cdn.test:${server.port}/edge1.ts"
            val client = PlayerPlaybackNetworking.createHttpClient(headers, "http://127.0.0.1:${server.port}/master.m3u8".toHttpUrl())
                .newBuilder()
                .dns(loopbackOnly)
                .build()
            client.newCall(
                Request.Builder().url("http://localhost:${server.port}/segment1.ts")
                    .apply { headers.forEach { (name, value) -> header(name, value) } }.build()
            ).execute().use { assertEquals(200, it.code) }

            server.assertEvery("/edge1.ts") {
                assertNull(it["authorization"])
                assertNull(it["x-requested-with"])
                assertNull(it["x-debrid-id"])
                assertEquals("https://addon.example/", it["referer"])
            }
        }
    }

    @Test
    fun `a sibling subdomain of the stream's site keeps the credentials, another site doesn't`() {
        // Every name resolves to the local server; its Host header says which one was asked.
        val anyName = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
        }
        RecordingServer().use { server ->
            server.redirects["/start"] = "http://cdn.provider.com:${server.port}/landed"
            val streamUrl = "http://api.provider.com:${server.port}/start"
            val client = PlayerPlaybackNetworking.createHttpClient(streamHeaders, streamUrl.toHttpUrl()).newBuilder()
                .dns(anyName)
                .build()

            // A redirect to the provider's own CDN (as before the remediation): every header.
            client.newCall(request(streamUrl)).execute().use { assertEquals(200, it.code) }
            server.assertEvery("/landed") {
                assertEquals("cdn.provider.com:${server.port}", it["host"])
                assertEquals("Bearer token", it["authorization"])
                assertEquals("session=1", it["cookie"])
                assertEquals("own", it["x-debrid-id"])
            }

            // A segment of its own on another organisation's host: no credentials.
            client.newCall(request("http://cdn.other-company.net:${server.port}/seg.ts")).execute().use { assertEquals(200, it.code) }
            server.assertEvery("/seg.ts") {
                assertNull(it["authorization"])
                assertNull(it["cookie"])
                assertNull(it["x-api-key"])
            }
        }
        // Shared hosting suffixes and addresses are not one site.
        assertTrue(PlaybackRedirectCredentials.isSameSite("https://cdn.provider.com/a".toHttpUrl(), "https://api.provider.com/b".toHttpUrl()))
        assertTrue(!PlaybackRedirectCredentials.isSameSite("https://b.github.io/a".toHttpUrl(), "https://a.github.io/b".toHttpUrl()))
        assertTrue(!PlaybackRedirectCredentials.isSameSite("http://api.provider.com/a".toHttpUrl(), "https://api.provider.com/b".toHttpUrl()))
        assertTrue(!PlaybackRedirectCredentials.isSameSite("http://10.0.0.2/a".toHttpUrl(), "http://10.0.0.1/b".toHttpUrl()))
    }

    @Test
    fun `credential header names are recognised`() {
        listOf("Authorization", "Proxy-Authorization", "Cookie", "X-Api-Key", "X-Auth-Token", "x-session-id")
            .forEach { assertTrue(it, PlaybackRedirectCredentials.isCredentialName(it)) }
        listOf("Referer", "User-Agent", "Range", "Accept-Encoding", "Origin", "Icy-MetaData")
            .forEach { assertTrue(it, !PlaybackRedirectCredentials.isCredentialName(it)) }
        assertTrue(PlaybackRedirectCredentials.isCredential("X-Custom", "Bearer abc"))
        assertTrue(PlaybackRedirectCredentials.isCredential("X-Custom", "basic dXNlcjpwYXNz"))
        assertTrue(!PlaybackRedirectCredentials.isCredential("X-Requested-With", "player"))
    }

    /** The stream's headers on the request, as Media3's OkHttpDataSource sets them. */
    private fun request(url: String): Request =
        Request.Builder().url(url).apply { streamHeaders.forEach { (name, value) -> header(name, value) } }.build()

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            fail("expected ${T::class.java.simpleName}, got $e")
        }
        fail("expected ${T::class.java.simpleName}, nothing was thrown")
    }

    private class TestTls(val serverContext: SSLContext, val trustManager: X509TrustManager) {
        val socketFactory = SSLContext.getInstance("TLS")
            .apply { init(null, arrayOf(trustManager), null) }
            .socketFactory
    }

    /** Self-signed cert for 127.0.0.1 (the subtitle tests' own), valid until 2126. */
    private fun testTls(): TestTls {
        val password = "changeit".toCharArray()
        val resource = requireNotNull(
            PlaybackTlsAndRedirectTest::class.java.getResourceAsStream("/subtitle-redirect-test.p12")
        ) { "subtitle-redirect-test.p12 missing from test resources" }
        val keyStore = KeyStore.getInstance("PKCS12").apply { resource.use { load(it, password) } }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, password) }.keyManagers
        val trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore) }.trustManagers.filterIsInstance<X509TrustManager>().first()
        val serverContext = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
        return TestTls(serverContext, trustManager)
    }

    private class RecordedRequest(val path: String, val headers: Map<String, String>)

    /** Records each request's headers; 302 for [redirects], a small body otherwise. */
    private class RecordingServer(tls: TestTls? = null) : AutoCloseable {
        private val socket: ServerSocket = tls?.serverContext?.serverSocketFactory
            ?.createServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            ?: ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port: Int = socket.localPort
        val redirects: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
        private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

        private val acceptor = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching {
                    client.use { connection ->
                        val reader = connection.getInputStream().bufferedReader()
                        val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                        val headers = generateSequence { reader.readLine() }
                            .takeWhile { it.isNotEmpty() }
                            .associate { line ->
                                line.substringBefore(':').trim().lowercase() to line.substringAfter(':').trim()
                            }
                        requests.add(RecordedRequest(path, headers))
                        val location = redirects[path]
                        val response = if (location != null) {
                            "HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        } else {
                            "HTTP/1.1 200 OK\r\nContent-Type: video/x-matroska\r\n" +
                                "Content-Length: 4\r\nConnection: close\r\n\r\ndata"
                        }
                        connection.getOutputStream().apply { write(response.toByteArray()); flush() }
                    }
                }
            }
        }

        fun requestsTo(path: String): List<Map<String, String>> =
            synchronized(requests) { requests.filter { it.path == path }.map { it.headers } }

        /** Runs [check] on every request to [path]; at least one is required. */
        fun assertEvery(path: String, check: (Map<String, String>) -> Unit) {
            val matching = requestsTo(path)
            assertTrue("no request to $path", matching.isNotEmpty())
            matching.forEach(check)
        }

        override fun close() {
            socket.close()
            acceptor.join(1_000)
        }
    }
}
