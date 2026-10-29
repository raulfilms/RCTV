package com.nuvio.tv.core.diagnostics

import android.os.Debug
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    private const val MB = 1024L * 1024L
    private const val SCREENS_SHOWN = 4

    private val policy = NativeGrowthPolicy()
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

    private fun check() {
        runCatching {
            val before = nativeMb()
            if (!policy.shouldCollect(before, SystemClock.elapsedRealtime())) return
            val runtime = Runtime.getRuntime()
            runtime.gc()
            // What's freed with the garbage (native memory) is freed by its finalizers and cleaners.
            runtime.runFinalization()
            val after = nativeMb()
            policy.collected(after, SystemClock.elapsedRealtime())
            note(SessionTrail.screenName(), before, after)
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
}
