package com.nuvio.tv.core.network

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.tls.OkHostnameVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * RCTV-AUD-021: the addon/IPTV/subtitle client checks certificates and hostnames like any other,
 * and a certificate failure is told apart from other failures.
 */
class AddonHttpClientTest {

    private val cacheRoot: File = Files.createTempDirectory("rctv-addon-cache").toFile()

    private val loopbackOnly = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
    }

    private fun addonClient(): OkHttpClient = AddonHttpClient.create(OkHttpClient(), cacheRoot)

    @Test
    fun `the addon client keeps certificate and hostname checks`() {
        val client = addonClient()
        assertSame(OkHostnameVerifier, client.hostnameVerifier)
        assertEquals(File(cacheRoot, "addon_http_cache_v2"), client.cache?.directory)
    }

    @Test
    fun `an untrusted certificate fails, with no request reaching the server, and is told as such`() {
        TlsServer().use { server ->
            val client = addonClient().newBuilder().retryOnConnectionFailure(false).build()
            val failure = failureOf { client.newCall(Request.Builder().url("https://127.0.0.1:${server.port}/manifest.json").build()).execute().close() }
            assertTrue("got $failure", failure is SSLHandshakeException)
            assertTrue(failure!!.isTlsCertificateFailure())
            assertEquals(0, server.requests.get())
        }
    }

    @Test
    fun `a trusted certificate for another name fails hostname verification`() {
        TlsServer().use { server ->
            val client = addonClient().newBuilder()
                .sslSocketFactory(server.clientSocketFactory, server.trustManager)
                .dns(loopbackOnly)
                .retryOnConnectionFailure(false)
                .build()
            val failure = failureOf { client.newCall(Request.Builder().url("https://localhost:${server.port}/manifest.json").build()).execute().close() }
            assertTrue("got $failure", failure is SSLPeerUnverifiedException)
            assertTrue(failure!!.isTlsCertificateFailure())
            assertEquals(0, server.requests.get())
        }
    }

    @Test
    fun `a valid certificate still connects`() {
        TlsServer().use { server ->
            val client = addonClient().newBuilder()
                .sslSocketFactory(server.clientSocketFactory, server.trustManager)
                .build()
            client.newCall(Request.Builder().url("https://127.0.0.1:${server.port}/manifest.json").build()).execute().use {
                assertEquals(200, it.code)
            }
            assertEquals(1, server.requests.get())
        }
    }

    @Test
    fun `network and login failures are not called certificate failures`() {
        assertFalse(SocketTimeoutException("timeout").isTlsCertificateFailure())
        assertFalse(IOException("HTTP 401").isTlsCertificateFailure())
        assertFalse(SSLHandshakeException("protocol_version").isTlsCertificateFailure())
        assertTrue(IOException(SSLHandshakeException("x").apply { initCause(CertificateExpiredException("expired")) }).isTlsCertificateFailure())
        assertTrue(CertificateException("unknown authority").isTlsCertificateFailure())
    }

    @Test
    fun `the old cache, filled over unchecked connections, is removed`() {
        val legacy = File(cacheRoot, "addon_http_cache").apply { mkdirs(); File(this, "journal").writeText("x") }
        AddonHttpClient.dropLegacyCache(cacheRoot)
        val deadline = System.currentTimeMillis() + 5_000
        while (legacy.exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertFalse(legacy.exists())
    }

    private fun failureOf(block: () -> Unit): Throwable? = try {
        block()
        fail("expected a failure")
        null
    } catch (e: Throwable) {
        e
    }

    /** An HTTPS server on 127.0.0.1 with the test certificate (valid for 127.0.0.1 only). */
    private class TlsServer : AutoCloseable {
        val trustManager: X509TrustManager
        val clientSocketFactory: javax.net.ssl.SSLSocketFactory
        private val socket: ServerSocket
        val port: Int
        val requests = AtomicInteger()
        private val acceptor: Thread

        init {
            val password = "changeit".toCharArray()
            val keyStore = KeyStore.getInstance("PKCS12").apply {
                requireNotNull(AddonHttpClientTest::class.java.getResourceAsStream("/subtitle-redirect-test.p12")).use { load(it, password) }
            }
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, password) }.keyManagers
            trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(keyStore) }.trustManagers.filterIsInstance<X509TrustManager>().first()
            clientSocketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory
            val serverContext = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
            socket = serverContext.serverSocketFactory.createServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            port = socket.localPort
            acceptor = thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    runCatching {
                        client.use { connection ->
                            val reader = connection.getInputStream().bufferedReader()
                            reader.readLine() ?: return@use
                            generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.count()
                            requests.incrementAndGet()
                            val body = "{}"
                            connection.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                                flush()
                            }
                        }
                    }
                }
            }
        }

        override fun close() {
            socket.close()
            acceptor.join(1_000)
        }
    }
}
