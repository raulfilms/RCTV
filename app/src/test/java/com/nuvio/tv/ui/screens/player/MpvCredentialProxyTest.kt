package com.nuvio.tv.ui.screens.player

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.util.Collections
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * RCTV-AUD-009: libmpv plays credential-bearing streams through [MpvCredentialProxy]. "mpv" here is a
 * plain HTTP client asking the proxy, as FFmpeg would; the servers record what each host received.
 */
class MpvCredentialProxyTest {

    private val streamHeaders = mapOf(
        "Authorization" to "Bearer token",
        "Cookie" to "mac=00:1A:79:00:00:01",
        "X-Plex-Token" to "plex",
        "X-Emby-Authorization" to "emby",
        "apikey" to "key",
        "X-Session-Info" to "Bearer hidden",
        "X-Client" to "rctv",
        "Referer" to "https://addon.example/",
        "User-Agent" to "Player"
    )
    private val credentialNames = listOf("authorization", "cookie", "x-plex-token", "x-emby-authorization", "apikey", "x-session-info")

    private val loopbackOnly = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
    }

    /** What mpv is: no headers of the stream's, no redirects of its own to follow (the proxy does). */
    private val mpv = OkHttpClient.Builder().followRedirects(false).build()

    private fun upstreamClient(streamUrl: String, tls: TestTls? = null): OkHttpClient =
        PlayerPlaybackNetworking.createHttpClient(streamHeaders, streamUrl.toHttpUrl()).newBuilder()
            .dns(loopbackOnly)
            .retryOnConnectionFailure(false)
            .apply { tls?.let { sslSocketFactory(it.socketFactory, it.trustManager) } }
            .build()

    private fun proxyFor(streamUrl: String, tls: TestTls? = null): MpvCredentialProxy =
        requireNotNull(MpvCredentialProxy.start(streamUrl, streamHeaders, upstreamClient(streamUrl, tls)))

    private fun get(url: String, range: String? = null) =
        mpv.newCall(Request.Builder().url(url).apply { range?.let { header("Range", it) } }.build()).execute()

    @Test
    fun `mpv gets only the headers any host may receive, and the proxy is used only when needed`() {
        assertEquals(mapOf("Referer" to "https://addon.example/", "User-Agent" to "Player"), MpvCredentialProxy.headersForMpv(streamHeaders))
        assertTrue(MpvCredentialProxy.isNeeded("https://portal.example/play/1.ts", mapOf("Cookie" to "mac=1")))
        assertTrue(MpvCredentialProxy.isNeeded("https://portal.example/play/1.ts", mapOf("X-Odd-Name" to "Bearer abc")))
        assertTrue(MpvCredentialProxy.isNeeded("https://dav.example/v.mkv", mapOf("X-Plex-Token" to "t")))
        // Plain headers only: mpv plays it directly, exactly as before.
        assertFalse(MpvCredentialProxy.isNeeded("https://portal.example/play/1.ts", mapOf("X-Odd-Name" to "value")))
        assertFalse(MpvCredentialProxy.isNeeded("https://cdn.example/v.mkv", mapOf("User-Agent" to "Player", "Referer" to "r")))
        assertFalse(MpvCredentialProxy.isNeeded("http://127.0.0.1:8090/stream/v.mkv", streamHeaders))
        assertFalse(MpvCredentialProxy.isNeeded("/storage/movie.mkv", streamHeaders))
    }

    @Test
    fun `a stream on its own host gets every header, mpv's range, and one request per request`() {
        Server().use { server ->
            val streamUrl = "http://127.0.0.1:${server.port}/play/1.ts?token=single-use&password=pw"
            proxyFor(streamUrl).use { proxy ->
                // The address mpv is given carries nothing of the stream's.
                assertFalse(proxy.localUrl.contains("single-use") || proxy.localUrl.contains("pw") || proxy.localUrl.contains(server.port.toString()))

                get(proxy.localUrl, range = "bytes=10-").use { response ->
                    assertEquals(206, response.code)
                    assertEquals("bytes 10-13/14", response.header("Content-Range"))
                    assertEquals("data", response.body?.string())
                }

                val sent = server.requestsTo("/play/1.ts").single()
                assertEquals("/play/1.ts?token=single-use&password=pw", sent.target)
                assertEquals("bytes=10-", sent.headers["range"])
                credentialNames.forEach { assertNotNull(it, sent.headers[it]) }
            }
        }
    }

    @Test
    fun `a redirect on the same host keeps the credentials, to another host drops them`() {
        Server().use { server ->
            Server().use { otherPort ->
                server.redirects["/same"] = "http://127.0.0.1:${otherPort.port}/landed"
                server.redirects["/away"] = "http://localhost:${server.port}/landed"
                proxyFor("http://127.0.0.1:${server.port}/same").use { proxy ->
                    get(proxy.localUrl).use { response ->
                        assertEquals(200, response.code)
                        assertNull("mpv never sees the redirect", response.header("Location"))
                    }
                    otherPort.requestsTo("/landed").single().headers.let { landed ->
                        credentialNames.forEach { assertNotNull(it, landed[it]) }
                    }
                }
                proxyFor("http://127.0.0.1:${server.port}/away").use { proxy ->
                    get(proxy.localUrl).use { assertEquals(200, it.code) }
                    server.requestsTo("/landed").single().headers.let { landed ->
                        (credentialNames + "x-client").forEach { assertNull(it, landed[it]) }
                        assertEquals("https://addon.example/", landed["referer"])
                        assertEquals("Player", landed["user-agent"])
                    }
                }
            }
        }
    }

    @Test
    fun `an https to http redirect gets no credentials, and an untrusted certificate fails`() {
        val tls = testTls()
        Server(tls).use { httpsServer ->
            Server().use { httpServer ->
                httpsServer.redirects["/start"] = "http://127.0.0.1:${httpServer.port}/landed"
                proxyFor("https://127.0.0.1:${httpsServer.port}/start", tls).use { proxy ->
                    get(proxy.localUrl).use { assertEquals(200, it.code) }
                    assertEquals("Bearer token", httpsServer.requestsTo("/start").single().headers["authorization"])
                    httpServer.requestsTo("/landed").single().headers.let { landed ->
                        credentialNames.forEach { assertNull(it, landed[it]) }
                    }
                }
                // The system's trust only: the self-signed certificate is refused, nothing reaches the server.
                val strict = PlayerPlaybackNetworking.createHttpClient(streamHeaders, "https://127.0.0.1:${httpsServer.port}/start".toHttpUrl())
                    .newBuilder().retryOnConnectionFailure(false).build()
                requireNotNull(MpvCredentialProxy.start("https://127.0.0.1:${httpsServer.port}/again", streamHeaders, strict)).use { proxy ->
                    get(proxy.localUrl).use { assertEquals(502, it.code) }
                    assertTrue(httpsServer.requestsTo("/again").isEmpty())
                }
            }
        }
    }

    @Test
    fun `hls playlists are rewritten, and segments get credentials on the stream host only`() {
        Server().use { server ->
            val base = "http://127.0.0.1:${server.port}"
            val cdn = "http://localhost:${server.port}"
            server.bodies["/hls/master.m3u8"] = "application/vnd.apple.mpegurl" to
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv1/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2\n$cdn/cdn/index.m3u8\n"
            server.bodies["/hls/v1/index.m3u8"] = "application/x-mpegURL" to
                "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:4,\nseg1.ts\n#EXTINF:4,\n$cdn/cdn/seg2.ts\n"

            proxyFor("$base/hls/master.m3u8").use { proxy ->
                val master = get(proxy.localUrl).use { it.body!!.string() }
                assertFalse("no upstream address left in the playlist", master.contains(base) || master.contains(cdn) || master.contains("v1/"))
                val variants = master.lines().filter { it.startsWith("http://127.0.0.1:") }
                assertEquals(2, variants.size)

                val media = get(variants[0]).use { it.body!!.string() }
                val keyUrl = Regex("URI=\"([^\"]+)\"").find(media)!!.groupValues[1]
                val segments = media.lines().filter { it.startsWith("http://127.0.0.1:") }
                (listOf(keyUrl) + segments).forEach { url -> get(url).use { assertEquals(url, 200, it.code) } }

                listOf("/hls/master.m3u8", "/hls/v1/index.m3u8", "/hls/v1/key.bin", "/hls/v1/seg1.ts").forEach { path ->
                    val sent = server.requestsTo(path).single()
                    assertEquals("$path on the stream's host", "Bearer token", sent.headers["authorization"])
                    assertEquals("127.0.0.1:${server.port}", sent.headers["host"])
                }
                val onCdn = server.requestsTo("/cdn/seg2.ts").single()
                assertEquals("localhost:${server.port}", onCdn.headers["host"])
                listOf("authorization", "cookie", "x-plex-token", "x-emby-authorization", "apikey", "x-session-info")
                    .forEach { assertNull(it, onCdn.headers[it]) }
                assertEquals("rctv", onCdn.headers["x-client"])
                assertEquals("https://addon.example/", onCdn.headers["referer"])
            }
        }
    }

    @Test
    fun `a relative address mpv resolves itself (dash) comes back through the proxy`() {
        Server().use { server ->
            proxyFor("http://127.0.0.1:${server.port}/dash/manifest.mpd").use { proxy ->
                get(proxy.localUrl).use { assertEquals(200, it.code) }
                get(proxy.localUrl.removeSuffix("/s") + "/seg-1.m4s?n=1").use { assertEquals(200, it.code) }
                val sent = server.requestsTo("/dash/seg-1.m4s?n=1").single()
                assertEquals("Bearer token", sent.headers["authorization"])
            }
        }
    }

    @Test
    fun `a wrong token is refused before anything is asked upstream, and a closed proxy refuses all`() {
        Server().use { server ->
            val proxy = proxyFor("http://127.0.0.1:${server.port}/v.mkv")
            val wrong = proxy.localUrl.replace(Regex("/[0-9a-f]{32}/"), "/" + "0".repeat(32) + "/")
            get(wrong).use { assertEquals(404, it.code) }
            assertTrue(server.requests.isEmpty())
            proxy.close()
            assertTrue(runCatching { get(proxy.localUrl).close() }.isFailure)
        }
    }

    @Test
    fun `the proxy's own client waits as long as mpv would and keeps every check`() {
        val streamUrl = "https://portal.example/play/1.ts".toHttpUrl()
        val client = MpvCredentialProxy.upstreamClientFor(streamUrl, streamHeaders)
        // mpv's network-timeout for a movie: a stalled read isn't cut shorter than mpv cut it.
        assertEquals(60_000, client.readTimeoutMillis)
        assertEquals(60_000, client.connectTimeoutMillis)
        assertTrue(client.connectTimeoutMillis >= PlayerPlaybackNetworking.playbackHttpClient.connectTimeoutMillis)
        assertTrue(client.hostnameVerifier === okhttp3.internal.tls.OkHostnameVerifier)
        // The credential rules: the base guard and the stream's own scope.
        assertEquals(2, client.networkInterceptors.size)
        assertTrue(client.interceptors.isEmpty())
    }

    @Test
    fun `playlist rewriting keeps non-http keys and line endings`() {
        val playlist = "#EXTM3U\r\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key-id\"\r\n#EXT-X-MAP:URI=\"init.mp4\"\r\nseg.m4s\r\n"
        val rewritten = MpvCredentialProxy.rewriteHlsPlaylist(playlist, "https://h.example/a/p.m3u8".toHttpUrl()) { "P<$it>" }
        assertEquals(
            "#EXTM3U\r\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key-id\"\r\n#EXT-X-MAP:URI=\"P<https://h.example/a/init.mp4>\"\r\nP<https://h.example/a/seg.m4s>\r\n",
            rewritten
        )
    }

    private class TestTls(val serverContext: SSLContext, val trustManager: X509TrustManager) {
        val socketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory
    }

    /** Self-signed cert for 127.0.0.1 (the subtitle tests' own), valid until 2126. */
    private fun testTls(): TestTls {
        val password = "changeit".toCharArray()
        val resource = requireNotNull(MpvCredentialProxyTest::class.java.getResourceAsStream("/subtitle-redirect-test.p12"))
        val keyStore = KeyStore.getInstance("PKCS12").apply { resource.use { load(it, password) } }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, password) }.keyManagers
        val trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore) }.trustManagers.filterIsInstance<X509TrustManager>().first()
        return TestTls(SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }, trustManager)
    }

    private class Recorded(val target: String, val headers: Map<String, String>)

    /** Records requests; 302 for [redirects], a set body for [bodies], else "data" (206 for a Range). */
    private class Server(tls: TestTls? = null) : AutoCloseable {
        private val socket: ServerSocket = tls?.serverContext?.serverSocketFactory
            ?.createServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            ?: ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port: Int = socket.localPort
        val redirects: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
        val bodies: MutableMap<String, Pair<String, String>> = java.util.concurrent.ConcurrentHashMap()
        val requests: MutableList<Recorded> = Collections.synchronizedList(mutableListOf())

        private val acceptor = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching {
                    client.use { connection ->
                        val reader = connection.getInputStream().bufferedReader()
                        val target = reader.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                        val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }
                            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
                        requests.add(Recorded(target, headers))
                        val path = target.substringBefore('?')
                        val response = when {
                            redirects[path] != null ->
                                "HTTP/1.1 302 Found\r\nLocation: ${redirects[path]}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            bodies[path] != null -> {
                                val (type, body) = bodies.getValue(path)
                                "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                            }
                            headers["range"] != null ->
                                "HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp2t\r\nContent-Range: bytes 10-13/14\r\n" +
                                    "Content-Length: 4\r\nConnection: close\r\n\r\ndata"
                            else -> "HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nContent-Length: 4\r\nConnection: close\r\n\r\ndata"
                        }
                        connection.getOutputStream().apply { write(response.toByteArray()); flush() }
                    }
                }
            }
        }

        fun requestsTo(target: String): List<Recorded> = synchronized(requests) {
            requests.filter { it.target == target || (!target.contains('?') && it.target.substringBefore('?') == target) }
        }

        override fun close() {
            socket.close()
            acceptor.join(1_000)
        }
    }
}
