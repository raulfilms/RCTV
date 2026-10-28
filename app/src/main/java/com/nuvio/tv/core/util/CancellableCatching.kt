package com.nuvio.tv.core.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] that lets coroutine cancellation through (RCTV-AUD-016): a [CancellationException]
 * is thrown on instead of becoming a failure, so a cancelled caller stops instead of carrying on
 * with an empty or "error" result. Anything else is caught, as [runCatching] does.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
