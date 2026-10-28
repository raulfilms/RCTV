package com.nuvio.tv.core.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * RCTV (PERF-02, PERF-03): whether the app is on screen (MainActivity started). Background checks that
 * only keep what's on screen current (Trakt's progress, the membership) wait here while the app is
 * in the background, and run as soon as it comes back, so what the person sees is just as current.
 * Before the Activity starts the app counts as on screen (nothing waits that used to run).
 */
object AppForeground {
    private val state = MutableStateFlow(true)

    val isForeground: StateFlow<Boolean> = state.asStateFlow()

    fun set(foreground: Boolean) {
        state.value = foreground
    }

    /** Returns at once when the app is on screen; otherwise when it comes back. */
    suspend fun awaitForeground() {
        if (!state.value) state.first { it }
    }
}
