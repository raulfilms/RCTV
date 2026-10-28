package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.network.forLog
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * RCTV (RCTV-AUD-009): libmpv sends its `http-header-fields` with every request FFmpeg makes for a
 * file, to whatever host a redirect or an HLS playlist points at, and they can't be scoped per host.
 * When a stream's headers carry anything beyond the ones any host may get (a cookie, a token, an
 * Authorization), mpv plays it through this proxy instead: a server on 127.0.0.1 only, behind a
 * random token, that makes each of mpv's requests with the app's own validating client, so the
 * stream's credentials follow [PlaybackRedirectCredentials]' rules (its host only; a redirect off
 * it keeps only User-Agent, Referer, Origin and Accept-Language; a segment on another host gets its
 * plain headers without credentials). mpv itself only gets [headersForMpv].
 *
 * One upstream request per request mpv makes, nothing ahead of it: a single-use link (a Stalker
 * portal's) or an addon's resolve address is asked for exactly as mpv asks for it. HLS playlists are
 * rewritten so every address in them goes through the proxy too; a DASH manifest isn't (its relative
 * addresses come back through the proxy; an absolute one is asked for by mpv directly, with
 * [headersForMpv] only). Certificates are checked by the client as for any other request; there is
 * no fallback.
 */
internal class MpvCredentialProxy private constructor(
    private val streamUrl: HttpUrl,
    private val streamHeaders: Map<String, String>,
    private val client: OkHttpClient
) : Closeable {

    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val server = ServerSocket(0, 16, InetAddress.getByName(LOOPBACK))
    private val workers: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "rctv-mpv-proxy").apply { isDaemon = true }
    }
    private val openCalls = Collections.synchronizedSet(mutableSetOf<okhttp3.Call>())
    private val openSockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    @Volatile private var closed = false

    /** The stream's address after its redirects, for relative addresses mpv resolves itself (DASH). */
    @Volatile private var streamFinalUrl: HttpUrl = streamUrl

    /** What mpv opens instead of the stream. */
    val localUrl: String = "http://$LOOPBACK:${server.localPort}/$token/s"

    init {
        Thread({ acceptLoop() }, "rctv-mpv-proxy-accept").apply { isDaemon = true }.start()
    }

    /** Whether this proxy is for [url] with [headers] (the same stream again: a reattach, a reconnect). */
    fun serves(url: String, headers: Map<String, String>): Boolean =
        !closed && url.toHttpUrlOrNull() == streamUrl && headers == streamHeaders

    override fun close() {
        if (closed) return
        closed = true
        runCatching { server.close() }
        synchronized(openCalls) { openCalls.forEach { it.cancel() } }
        synchronized(openSockets) { openSockets.forEach { runCatching { it.close() } } }
        workers.shutdownNow()
    }

    private fun acceptLoop() {
        while (!closed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            openSockets += socket
            runCatching { workers.execute { serve(socket) } }.onFailure {
                openSockets -= socket
                runCatching { socket.close() }
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.use { connection ->
                val input = BufferedInputStream(connection.getInputStream())
                val output = connection.getOutputStream()
                val requestLine = readLine(input) ?: return
                val incoming = readHeaders(input)
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0).orEmpty()
                val target = targetOf(parts.getOrNull(1).orEmpty())
                when {
                    target == null -> writeStatus(output, 404, "Not Found")
                    method != "GET" && method != "HEAD" -> writeStatus(output, 405, "Method Not Allowed")
                    else -> forward(method, target, incoming, output)
                }
            }
        } catch (_: IOException) {
            // mpv closed the connection (a seek, the player stopping).
        } finally {
            openSockets -= socket
        }
    }

    /** The upstream address for a request path, or null (a wrong token, an unknown path). */
    private fun targetOf(path: String): Target? {
        val prefix = "/$token/"
        if (!path.startsWith("/") || path.length < prefix.length) return null
        val given = path.substring(1, 1 + token.length)
        if (!MessageDigest.isEqual(given.toByteArray(), token.toByteArray()) || path[prefix.length - 1] != '/') return null
        val rest = path.substring(prefix.length)
        return when {
            rest == "s" -> Target(streamUrl, isStream = true)
            rest.startsWith("r/") -> decode(rest.removePrefix("r/"))?.let { Target(it, isStream = false) }
            rest.isNotEmpty() -> streamFinalUrl.resolve(rest)?.takeIf { it.isHttp() }?.let { Target(it, isStream = false) }
            else -> null
        }
    }

    private class Target(val url: HttpUrl, val isStream: Boolean)

    private fun forward(method: String, target: Target, incoming: Map<String, String>, output: OutputStream) {
        val request = Request.Builder().url(target.url).apply {
            // The stream's own headers; the client's interceptors decide which of them each host gets.
            streamHeaders.forEach { (name, value) -> header(name, value) }
            FORWARDED_FROM_MPV.forEach { name -> incoming[name]?.let { header(name, it) } }
            if (streamHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                header("User-Agent", incoming["user-agent"] ?: PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            }
            if (method == "HEAD") head() else get()
        }.build()
        val call = client.newCall(request)
        openCalls += call
        try {
            val response = try {
                call.execute()
            } catch (e: IOException) {
                // A certificate that doesn't check out ends here too: no retry without checks.
                Log.w(TAG, "upstream failed for ${target.url.forLog()}: ${e.javaClass.simpleName}")
                writeStatus(output, 502, "Bad Gateway")
                return
            }
            response.use {
                if (target.isStream) streamFinalUrl = response.request.url
                if (method == "GET" && response.isSuccessful && isHlsPlaylist(response)) {
                    writePlaylist(response, output)
                } else {
                    writeResponse(response, output, withBody = method == "GET")
                }
            }
        } finally {
            openCalls -= call
        }
    }

    private fun writePlaylist(response: Response, output: OutputStream) {
        val body = response.body ?: return writeStatus(output, 502, "Bad Gateway")
        val source = body.source()
        if (source.request(MAX_PLAYLIST_BYTES + 1)) return writeStatus(output, 502, "Bad Gateway")
        val text = source.buffer.readUtf8()
        val rewritten = rewriteHlsPlaylist(text, response.request.url) { proxyUrlFor(it) }.toByteArray(Charsets.UTF_8)
        val head = StringBuilder("HTTP/1.1 ${response.code} ${response.message.ifBlank { "OK" }}\r\n")
        response.header("Content-Type")?.let { head.append("Content-Type: ").append(it).append("\r\n") }
        head.append("Content-Length: ").append(rewritten.size).append("\r\nConnection: close\r\n\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        output.write(rewritten)
        output.flush()
    }

    private fun writeResponse(response: Response, output: OutputStream, withBody: Boolean) {
        val head = StringBuilder("HTTP/1.1 ${response.code} ${response.message.ifBlank { "OK" }}\r\n")
        PASSED_TO_MPV.forEach { name -> response.headers(name).forEach { head.append(name).append(": ").append(it).append("\r\n") } }
        response.headers.names().filter { it.startsWith("icy-", ignoreCase = true) }
            .forEach { name -> head.append(name).append(": ").append(response.header(name)).append("\r\n") }
        head.append("Connection: close\r\n\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (withBody) response.body?.byteStream()?.use { it.copyTo(output, COPY_BUFFER_BYTES) }
        output.flush()
    }

    private fun proxyUrlFor(url: HttpUrl): String =
        "http://$LOOPBACK:${server.localPort}/$token/r/" + url.toString().encodeUtf8().base64Url()

    private fun decode(encoded: String): HttpUrl? =
        encoded.substringBefore('?').decodeBase64()?.utf8()?.toHttpUrlOrNull()?.takeIf { it.isHttp() }

    private fun HttpUrl.isHttp(): Boolean = scheme == "http" || scheme == "https"

    private fun writeStatus(output: OutputStream, code: Int, message: String) {
        output.write("HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            if (headers.size >= MAX_HEADERS) continue
            val name = line.substringBefore(':', "").trim().lowercase()
            if (name.isNotEmpty()) headers[name] = line.substringAfter(':').trim()
        }
        return headers
    }

    private fun readLine(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (bytes.size() == 0) null else bytes.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.write(b)
            if (bytes.size() > MAX_LINE_BYTES) throw IOException("line too long")
        }
        return bytes.toString(Charsets.ISO_8859_1.name())
    }

    companion object {
        private const val TAG = "MpvCredentialProxy"
        private const val LOOPBACK = "127.0.0.1"
        private const val MAX_PLAYLIST_BYTES = 8L * 1024 * 1024
        private const val MAX_LINE_BYTES = 16 * 1024
        private const val MAX_HEADERS = 64
        private const val COPY_BUFFER_BYTES = 64 * 1024

        /** What mpv's request may pass on (seeking, live metadata); never its own credentials. */
        private val FORWARDED_FROM_MPV = listOf("range", "if-range", "accept", "icy-metadata")

        /** What mpv needs of an answer; redirects are followed here, so never a Location. */
        private val PASSED_TO_MPV = listOf(
            "Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag"
        )

        /**
         * Whether [url] with [headers] goes through the proxy: a network stream whose headers carry a
         * credential (by name: Authorization, Cookie, …token…, …key…; or by value: Bearer, Basic). A
         * stream with only plain headers plays straight from mpv as it always did (nothing to leak,
         * nothing changed), and so does one on this device (a local torrent server).
         */
        fun isNeeded(url: String, headers: Map<String, String>): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            if (parsed.host == LOOPBACK || parsed.host == "localhost" || parsed.host == "::1") return false
            return headers.any { (name, value) -> PlaybackRedirectCredentials.isCredential(name, value) }
        }

        /** The headers mpv gets itself: only those any host may receive (User-Agent, Referer, Origin, Accept-Language). */
        fun headersForMpv(headers: Map<String, String>): Map<String, String> =
            headers.filterKeys { PlaybackRedirectCredentials.isCrossHostHeader(it) }

        /**
         * A proxy for [url] and [headers] (null when the address isn't HTTP(S) or no local port can be
         * opened). [client] is the stream's client: certificate checks and [PlaybackRedirectCredentials]'
         * rules for its headers.
         */
        fun start(
            url: String,
            headers: Map<String, String>,
            client: OkHttpClient? = null
        ): MpvCredentialProxy? {
            val parsed = url.toHttpUrlOrNull() ?: return null
            return runCatching {
                MpvCredentialProxy(parsed, headers, client ?: upstreamClientFor(parsed, headers))
            }.onFailure { Log.w(TAG, "proxy couldn't start: ${it.javaClass.simpleName}") }.getOrNull()
        }

        /**
         * The stream's client for the proxy: [PlayerPlaybackNetworking.createHttpClient] (certificate
         * checks, the credential rules), with mpv's own patience. mpv waits up to 60 s for a stalled
         * read on a movie (`network-timeout`, 20 s on a live channel, which then reconnects); the
         * playback client gives up after 15 s, which would end a movie that mpv would have kept
         * waiting for. So the proxy never gives up before mpv would.
         */
        internal fun upstreamClientFor(url: HttpUrl, headers: Map<String, String>): OkHttpClient =
            PlayerPlaybackNetworking.createHttpClient(headers, url).newBuilder()
                .connectTimeout(MPV_NETWORK_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(MPV_NETWORK_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
                .build()

        /** mpv's `network-timeout` for a movie (its default), the longest it waits. */
        private const val MPV_NETWORK_TIMEOUT_S = 60L

        /** An HLS playlist: by its type, or a `.m3u8` address answered with `#EXTM3U`. */
        internal fun isHlsPlaylist(response: Response): Boolean {
            val type = response.header("Content-Type").orEmpty().lowercase()
            if ("mpegurl" in type) return true
            if (!response.request.url.encodedPath.endsWith(".m3u8", ignoreCase = true)) return false
            return runCatching { response.peekBody(7).string() == "#EXTM3U" }.getOrDefault(false)
        }

        private val URI_ATTRIBUTE = Regex("URI=\"([^\"]*)\"")

        /**
         * [text] (an HLS playlist fetched from [playlistUrl]) with every address in it, on its own line
         * or in a `URI="…"` attribute (keys, maps, renditions, parts), resolved against [playlistUrl]
         * and replaced by [proxied]'s. Addresses that aren't HTTP(S) (an `skd:` key) are kept as they are.
         */
        internal fun rewriteHlsPlaylist(text: String, playlistUrl: HttpUrl, proxied: (HttpUrl) -> String): String {
            fun proxiedOrKept(reference: String): String =
                playlistUrl.resolve(reference)?.takeIf { it.scheme == "http" || it.scheme == "https" }
                    ?.let(proxied) ?: reference
            val lines = text.split('\n')
            return lines.joinToString("\n") { raw ->
                val line = raw.removeSuffix("\r")
                val ending = if (raw.endsWith("\r")) "\r" else ""
                val trimmed = line.trim()
                val rewritten = when {
                    trimmed.isEmpty() -> line
                    trimmed.startsWith("#") -> URI_ATTRIBUTE.replace(line) { match ->
                        "URI=\"${proxiedOrKept(match.groupValues[1])}\""
                    }
                    else -> proxiedOrKept(trimmed)
                }
                rewritten + ending
            }
        }
    }
}
