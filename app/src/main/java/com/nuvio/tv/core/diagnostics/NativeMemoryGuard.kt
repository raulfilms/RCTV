package com.nuvio.tv.core.diagnostics

import android.os.Debug
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * RCTV (MEM-08, approved by the owner on 29-oct as an exception to the plan's "no System.gc()"
 * rule): keeps the app's native memory from piling up until the system closes it.
 *
 * On the Mi Box 4 (Android 9, 2 GB) Sports, Teams and Search left native garbage that only a
 * collection frees, while Java memory stayed so low (27–57 MB) that the collector hardly ran:
 * native memory reached 1.1–1.4 GB in seconds and the system closed the app. Each time a
 * collection finally ran (when the system asked for memory) it fell at once: 1261 → 350 MB and
 * 1281 → 463 MB in 2 s, by then too late. This checks native memory every second and runs a
 * collection as soon as it has grown fast ([NativeGrowthPolicy]), so it never gets near the limit.
 *
 * It doesn't stand for finding what leaves that garbage: each cleanup is noted with the screen it
 * happened on and how much it freed (see [summary], in the session notes), so the source can still
 * be found and removed.
 */
object NativeMemoryGuard {

    private const val CHECK_EVERY_MS = 1_000L
    internal const val GROWTH_MB = 40L
    internal const val HIGH_MB = 250L
    internal const val GROWTH_WHEN_HIGH_MB = 24L
    internal const val MIN_GAP_MS = 1_000L
    private const val MB = 1024L * 1024L
    private const val SCREENS_SHOWN = 4

    // RCTV (3-nov, Mi Box report at 23:49): on Search, native memory grew ~40 MB/s and the 7
    // cleanups freed 2,761 MB (it was garbage, not memory in use), yet it still reached 1.3 GB
    // and the process 2.9 GB of address space between them. Collections now start sooner and can
    // follow each other every 2 s while it keeps growing.
    private val policy = NativeGrowthPolicy(growthMb = GROWTH_MB, highMb = HIGH_MB, growthWhenHighMb = GROWTH_WHEN_HIGH_MB, minGapMs = MIN_GAP_MS)
    @Volatile private var started = false

    private var cleanups = 0
    private var freedMb = 0L
    private var last = ""
    /** How much each screen's cleanups freed (MB). */
    private val freedByScreen = LinkedHashMap<String, Long>()

    /** Starts checking (once, at the app's start). */
    fun start() {
        if (started) return
        started = true
        runCatching {
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "rctv-memory-guard").apply { isDaemon = true }
            }.scheduleWithFixedDelay(::check, CHECK_EVERY_MS, CHECK_EVERY_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** A collection made at the last check: (native before it, the screen then), measured at this one. */
    private var pending: Pair<Long, String>? = null

    private fun check() {
        runCatching {
            val now = nativeMb()
            // RCTV (Phase 2): what a collection freed is read a second later, once the cleaners
            // that free native memory after it have run (read right after it, it was under-counted).
            pending?.let { (before, screen) ->
                pending = null
                lastSampleMb = now
                policy.collected(now, SystemClock.elapsedRealtime())
                note(screen, before, now)
                return
            }
            attribute(now)
            if (!policy.shouldCollect(now, SystemClock.elapsedRealtime())) return
            Runtime.getRuntime().gc()
            pending = now to SessionTrail.screenName()
        }
    }

    @Synchronized
    private fun note(screen: String, before: Long, after: Long) {
        val freed = (before - after).coerceAtLeast(0L)
        cleanups++
        freedMb += freed
        freedByScreen[screen] = (freedByScreen[screen] ?: 0L) + freed
        last = "$screen $before → $after MB"
    }

    /** "5, freed 1630 MB (sports 980, sports_teams 650) · last: sports_teams 610 → 140 MB"; null before any. */
    @Synchronized
    fun summary(): String? {
        if (cleanups == 0) return null
        val screens = freedByScreen.entries.sortedByDescending { it.value }.take(SCREENS_SHOWN)
            .joinToString(", ") { "${it.key} ${it.value}" }
        return "$cleanups, freed $freedMb MB ($screens) · last: $last"
    }

    private fun nativeMb(): Long = Debug.getNativeHeapAllocatedSize() / MB

    // ---- What the app was doing while native memory grew (RCTV, MEM-09) ----
    //
    // Every second the growth since the last look is added to each kind of work going on then
    // (several at once each get it all): the work that keeps showing up while it grows is the one
    // that takes it. Only counts and sizes, never what's in it.

    private val running = ConcurrentHashMap<String, AtomicInteger>()
    private val lastSeenMs = ConcurrentHashMap<String, Long>()
    private val grownWhile = LinkedHashMap<String, Long>()
    private var lastSampleMb = -1L

    /** [block], noted as work of kind [label] while it runs. */
    inline fun <T> during(label: String, block: () -> T): T {
        begin(label)
        try {
            return block()
        } finally {
            end(label)
        }
    }

    fun begin(label: String) {
        running.getOrPut(label) { AtomicInteger() }.incrementAndGet()
    }

    fun end(label: String) {
        running[label]?.decrementAndGet()
        touch(label)
    }

    /** Work of kind [label] happened just now (quick, many times: decoding an image). */
    fun touch(label: String) {
        lastSeenMs[label] = SystemClock.elapsedRealtime()
    }

    private fun attribute(nowMb: Long) {
        val previous = lastSampleMb
        lastSampleMb = nowMb
        if (previous < 0 || nowMb <= previous) return
        val now = SystemClock.elapsedRealtime()
        val labels = (running.filterValues { it.get() > 0 }.keys +
            lastSeenMs.filterValues { now - it <= CHECK_EVERY_MS + 500 }.keys).ifEmpty { setOf("other") }
        synchronized(this) {
            labels.forEach { grownWhile[it] = (grownWhile[it] ?: 0L) + (nowMb - previous) }
        }
    }

    /** "guide-epg 820, espn 300, images 120 MB": what was going on while native memory grew; null before any. */
    @Synchronized
    fun growthSummary(): String? {
        if (grownWhile.isEmpty()) return null
        return grownWhile.entries.sortedByDescending { it.value }.take(6).joinToString(", ") { "${it.key} ${it.value}" } + " MB"
    }
}
