package com.nuvio.tv.data.remote.rctvcore

import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether the app asks RCTV Core (Oracle) for Sports, decided three ways, all of which must agree:
 *
 * 1. In the build: [enabledInBuild] (on since 4-nov-2026, approved by the owner).
 * 2. On the server: RCTV Core's /api/v1/client-config says Sports is on. Read at most every
 *    [checkEveryMs]; it can turn RCTV Core off for every TV without a new build.
 * 3. On this TV: after [failuresToRest] failures in a row, RCTV Core is left alone for [restMs]
 *    and the app works exactly as before (rctv-datos / rctv-ligas, then ESPN).
 *
 * Plain Kotlin with the clock passed in, so it's tested on the JVM.
 */
internal class RctvCoreSwitch(
    private val enabledInBuild: Boolean,
    private val checkEveryMs: Long = 5 * 60_000L,
    private val failuresToRest: Int = 3,
    private val restMs: Long = 10 * 60_000L
) {
    @Volatile private var serverSaysOn: Boolean? = null
    @Volatile private var checkedAtMs = Long.MIN_VALUE / 2
    @Volatile private var restUntilMs = Long.MIN_VALUE / 2
    private val failures = AtomicInteger(0)

    /** Ask RCTV Core now (it's on everywhere and isn't resting). */
    fun usable(nowMs: Long): Boolean = enabledInBuild && serverSaysOn == true && nowMs >= restUntilMs

    /** The server's switch should be read (again) before asking. */
    fun shouldCheck(nowMs: Long): Boolean = enabledInBuild && nowMs >= restUntilMs && nowMs - checkedAtMs >= checkEveryMs

    /** What /api/v1/client-config said ([enabled]), or null when it couldn't be read. */
    fun checked(enabled: Boolean?, nowMs: Long) {
        checkedAtMs = nowMs
        if (enabled == null) failed(nowMs) else serverSaysOn = enabled
    }

    fun succeeded() {
        failures.set(0)
    }

    fun failed(nowMs: Long) {
        if (failures.incrementAndGet() >= failuresToRest) {
            failures.set(0)
            restUntilMs = nowMs + restMs
        }
    }
}
