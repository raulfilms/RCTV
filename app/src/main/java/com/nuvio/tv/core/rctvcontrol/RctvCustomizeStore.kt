package com.nuvio.tv.core.rctvcontrol

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RCTV (owner's design), "Customize your experience": the profiles of this TV that already went
 * through it (finished or skipped), so it shows only the first time a profile opens on the TV.
 * A profile created or deleted here starts over ([forget]).
 */
@Singleton
class RctvCustomizeStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val _done = MutableStateFlow<Set<Int>?>(null)

    /** The profiles that went through it; null until read from the TV (a moment after the app opens). */
    val done: StateFlow<Set<Int>?> = _done.asStateFlow()

    init {
        scope.launch {
            val saved = synchronized(lock) { read() }
            _done.update { (it ?: emptySet()) + saved }
        }
    }

    /** The profile went through it (finished or skipped): not shown again on this TV. */
    fun markDone(profileId: Int) {
        _done.update { (it ?: emptySet()) + profileId }
        scope.launch { synchronized(lock) { write(read() + profileId) } }
    }

    /** A new profile in this place (or the old one deleted): it shows again the first time it opens. */
    fun forget(profileId: Int) {
        _done.update { it?.minus(profileId) }
        scope.launch { synchronized(lock) { write(read() - profileId) } }
    }

    private fun read(): Set<Int> =
        prefs.getStringSet(KEY_DONE, null).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()

    private fun write(ids: Set<Int>) {
        prefs.edit().putStringSet(KEY_DONE, ids.mapTo(HashSet()) { it.toString() }).apply()
    }

    private companion object {
        const val PREFS_NAME = "rctv_customize"
        const val KEY_DONE = "done_profiles"
    }
}
