package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.smartscraper.AutoStreamLinkCheck
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response

/**
 * Auto Stream v2.0 (§8 "Resolve + verify"): before a source plays, its link is followed through
 * its redirects and asked for its first two bytes (`Range: bytes=0-1`): it must answer 200/206 and
 * its whole size must be within the limit. Only the answer's headers are read; the connection is
 * closed at once (a server that ignores Range sends nothing more than what was already on its way).
 *
 * The request goes through the player's own client: the same certificate and hostname checks, the
 * stream's headers only on the stream's host, never to another host it redirects to (RCTV-AUD-009).
 * Nothing about the link is logged (it can carry a debrid key).
 */
internal object AutoStreamLinkVerifier {

    /** RCTV: links that checked out lately (each once: the player doesn't ask again what Auto Stream just did). */
    private const val RECENT_MS = 3L * 60 * 1000
    private const val RECENT_KEPT = 16
    private val recentOk = LinkedHashMap<String, Long>()

    /** [url] checked out in the last few minutes. */
    fun recentlyOk(url: String): Boolean = synchronized(recentOk) {
        val at = recentOk[url] ?: return false
        System.currentTimeMillis() - at < RECENT_MS
    }

    private fun rememberOk(url: String) = synchronized(recentOk) {
        recentOk.remove(url)
        recentOk[url] = System.currentTimeMillis()
        while (recentOk.size > RECENT_KEPT) recentOk.remove(recentOk.keys.first())
    }

    suspend fun verify(
        url: String?,
        headers: Map<String, String>,
        knownSizeBytes: Long?,
        maxBytes: Long,
        timeoutMs: Long
    ): AutoStreamLinkCheck.Result {
        val httpUrl = url?.toHttpUrlOrNull() ?: return if (url.isNullOrBlank()) {
            AutoStreamLinkCheck.unreachable()
        } else {
            // Not an http(s) link (a local file, a content URI): nothing to ask; the size said stands.
            AutoStreamLinkCheck.evaluate(200, null, knownSizeBytes?.toString(), knownSizeBytes, maxBytes)
        }
        val safeHeaders = PlayerMediaSourceFactory.sanitizeHeaders(headers)
        val client = PlayerPlaybackNetworking.createHttpClient(safeHeaders, httpUrl)
            .newBuilder()
            .callTimeout(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder()
            .url(httpUrl)
            .get()
            .apply {
                safeHeaders.forEach { (name, value) -> if (!name.equals("Range", ignoreCase = true)) header(name, value) }
                if (safeHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    header("User-Agent", PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
                }
            }
            .header("Range", "bytes=0-1")
            .build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(AutoStreamLinkCheck.unreachable())
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use {
                        AutoStreamLinkCheck.evaluate(
                            code = it.code,
                            contentRange = it.header("Content-Range"),
                            contentLength = it.header("Content-Length"),
                            knownSizeBytes = knownSizeBytes,
                            maxBytes = maxBytes,
                            contentType = it.header("Content-Type"),
                            // Where the redirects ended (an addon's error video?); read here only, never logged.
                            finalPath = it.request.url.encodedPath
                        )
                    }
                    if (!AutoStreamLinkCheck.isNotThere(result)) url?.let(::rememberOk)
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }
}
