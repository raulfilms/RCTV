package com.nuvio.tv.core.image

import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.request.ErrorResult
import coil3.request.ImageResult
import kotlinx.coroutines.delay
import java.io.IOException

/** Extra tries after a failed download (so up to three in all). */
private const val MAX_RETRIES = 2

/**
 * Tries an image again when it failed for a network reason: a timeout, a dropped connection, or the
 * image server being busy (429 / 5xx). A page opening starts a hundred or more downloads at once, and on
 * TV Wi-Fi a few of them used to time out, leaving that card, poster or hero blank for good. Images that
 * don't exist (404) aren't retried. Applies to every image the app loads.
 */
class ImageRetryInterceptor : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        var result = chain.proceed()
        var attempt = 0
        while (result is ErrorResult && attempt < MAX_RETRIES && isRetryable(result.throwable)) {
            attempt++
            // A short pause lets the burst of downloads clear before trying again.
            delay(500L * attempt)
            result = chain.withRequest(chain.request).proceed()
        }
        return result
    }

    private fun isRetryable(error: Throwable): Boolean = when (error) {
        is HttpException -> error.response.code == 429 || error.response.code >= 500
        is IOException -> true
        else -> error.cause is IOException
    }
}
