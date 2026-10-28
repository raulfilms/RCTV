package com.nuvio.tv.data.remote.espn

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * RCTV (RCTV-AUD-007): results that several parts of the app ask for (a league's teams: the Teams
 * page, Search and Sports' Teams row) read once and shared.
 *
 * - Owner: whoever holds this (EspnSportsClient keeps one for the whole app).
 * - Expiry: a result is kept [keepMs] after it was read; then it's read again.
 * - Size: at most [maxKept] results, the oldest dropped first.
 * - What's kept: only results [isWorthKeeping] accepts (an empty or failed read isn't kept).
 * - Invalidation: by expiry, or [clear] (when the system asks for memory).
 * - Asked for at the same time: one read; the others wait for it and get the same result or error
 *   (or, with [waitersReadAgainOnFailure], read it themselves when that shared read failed: a read
 *   cancelled for the page that started it never fails another page's).
 */
internal class SharedResults<T>(
    private val keepMs: Long,
    private val maxKept: Int,
    private val isWorthKeeping: (T) -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
    private val waitersReadAgainOnFailure: Boolean = false
) {
    private class Kept<T>(val atMs: Long, val value: T)

    /** Oldest first. */
    private val kept = LinkedHashMap<String, Kept<T>>()
    private val reading = HashMap<String, CompletableFuture<T>>()

    /** [key]'s result: kept, being read by another caller (waited for), or read now with [read]. */
    fun get(key: String, read: () -> T): T {
        val future: CompletableFuture<T>
        val readHere: Boolean
        synchronized(this) {
            kept[key]?.let { entry ->
                if (now() - entry.atMs < keepMs) return entry.value
                kept.remove(key)
            }
            val running = reading[key]
            readHere = running == null
            future = running ?: CompletableFuture<T>().also { reading[key] = it }
        }
        if (!readHere) {
            return try {
                future.get()
            } catch (e: ExecutionException) {
                if (waitersReadAgainOnFailure) read() else throw e.cause ?: e
            }
        }
        val value = try {
            read()
        } catch (e: Throwable) {
            synchronized(this) { reading.remove(key) }
            future.completeExceptionally(e)
            throw e
        }
        synchronized(this) {
            reading.remove(key)
            if (isWorthKeeping(value)) {
                kept.remove(key)
                kept[key] = Kept(now(), value)
                while (kept.size > maxKept) kept.remove(kept.keys.first())
            }
        }
        future.complete(value)
        return value
    }

    /** Drops every kept result (a read under way still finishes for whoever waits on it). */
    @Synchronized
    fun clear() {
        kept.clear()
    }

    @Synchronized
    internal fun keptCount(): Int = kept.size
}
