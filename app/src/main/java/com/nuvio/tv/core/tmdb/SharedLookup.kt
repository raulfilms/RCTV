package com.nuvio.tv.core.tmdb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * RCTV: waits for a lookup another caller already started (the same title asked for twice at
 * once). When that caller was cancelled (focus moved on, a card scrolled away), its lookup ends
 * with it; this caller then does the lookup itself ([retry]) instead of ending as if it had been
 * cancelled too, which left cards blank and a title's page without its details.
 */
internal suspend fun <T> awaitShared(existing: Deferred<T>, retry: suspend () -> T): T =
    try {
        existing.await()
    } catch (e: CancellationException) {
        // This caller itself was cancelled: that stands.
        currentCoroutineContext().ensureActive()
        retry()
    }
