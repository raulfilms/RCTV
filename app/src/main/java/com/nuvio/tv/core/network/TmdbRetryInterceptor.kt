package com.nuvio.tv.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Extra tries after a failed TMDB request (so up to four in all). */
private const val MAX_RETRIES = 3

/** Longest wait before trying again, whatever TMDB's Retry-After says. */
private const val MAX_WAIT_MS = 4_000L

/**
 * Tries a TMDB request again when TMDB says "too many requests" (429), is briefly unavailable (5xx), or
 * the connection drops. Opening a streaming hub fires dozens of TMDB lookups at once (every row, the hero,
 * each card's title art), and a few of them used to come back 429, which left a row, a hero or a card
 * missing. Only reads (GET) are retried; a missing title (404) is not.
 */
class TmdbRetryInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "GET") return chain.proceed(request)

        var attempt = 0
        while (true) {
            val response: Response? = try {
                chain.proceed(request)
            } catch (e: IOException) {
                if (attempt >= MAX_RETRIES || chain.call().isCanceled()) throw e
                null
            }
            if (response != null) {
                val retryable = response.code == 429 || response.code in 500..599
                if (!retryable || attempt >= MAX_RETRIES) return response
                val retryAfterMs = response.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
                response.close()
                sleep(retryAfterMs ?: backoff(attempt))
            } else {
                sleep(backoff(attempt))
            }
            attempt++
        }
    }

    private fun backoff(attempt: Int): Long = 600L * (attempt + 1) + (0L..300L).random()

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms.coerceIn(200L, MAX_WAIT_MS))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting to retry", e)
        }
    }
}
