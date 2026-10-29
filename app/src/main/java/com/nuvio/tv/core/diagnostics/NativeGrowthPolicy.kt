package com.nuvio.tv.core.diagnostics

/**
 * RCTV (MEM-08): when the app's native memory has grown enough since the last collection that one
 * is due now (see [NativeMemoryGuard]). Plain Kotlin, so it's tested on the JVM.
 *
 * The baseline is the native memory right after the last collection, or the lowest seen since:
 * memory that a collection couldn't free (images on screen, the player) never triggers another
 * one by itself; only new growth over it does.
 */
class NativeGrowthPolicy(
    /** Growth over the baseline that calls for a collection. */
    private val growthMb: Long = 96L,
    /** The same once native memory is this high, where the Mi Box 4 is near its limit... */
    private val highMb: Long = 400L,
    /** ...where less growth is enough. */
    private val growthWhenHighMb: Long = 48L,
    /** Never two collections closer than this. */
    private val minGapMs: Long = 3_000L
) {
    private var baselineMb = -1L
    private var lastCollectMs = Long.MIN_VALUE / 2

    /** [nativeMb] now, at [nowMs]: true when a collection should run. */
    fun shouldCollect(nativeMb: Long, nowMs: Long): Boolean {
        if (baselineMb < 0 || nativeMb < baselineMb) {
            baselineMb = nativeMb
            return false
        }
        if (nowMs - lastCollectMs < minGapMs) return false
        val limit = if (nativeMb >= highMb) growthWhenHighMb else growthMb
        return nativeMb - baselineMb >= limit
    }

    /** A collection ran and left [afterMb] at [nowMs]: the new baseline. */
    fun collected(afterMb: Long, nowMs: Long) {
        baselineMb = afterMb
        lastCollectMs = nowMs
    }
}
