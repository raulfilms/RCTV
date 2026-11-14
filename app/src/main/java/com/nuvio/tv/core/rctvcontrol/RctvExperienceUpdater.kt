package com.nuvio.tv.core.rctvcontrol

import android.content.Context
import com.nuvio.tv.core.sync.StartupSyncService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RCTV (owner): "Please Update Your Experience". A day after the app last loaded everything (its
 * start's "Creating Your Experience", or the last update), Home asks to update. Update loads it all
 * again behind the same screen ("Updating Your Experience…", its bar filling): the IPTV channels and
 * guide, the website's settings, banners and messages, Home's TMDB rows and the catalogs, the games,
 * and the TMDB titles of what's on now. Later asks again in a few hours. The screen goes when it's
 * all in, or after [UPDATE_MAX_MS] (the rest keeps loading behind), or on Back.
 */
@Singleton
class RctvExperienceUpdater @Inject constructor(
    @ApplicationContext context: Context,
    private val access: RctvAccessManager,
    private val publicFiles: RctvPublicFiles,
    private val preparer: RctvExperiencePreparer,
    private val startupSync: StartupSyncService
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _progress = MutableStateFlow<Float?>(null)
    /** The update's bar (0 to 1) while its screen shows; null when it doesn't. */
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    private val _updates = MutableStateFlow(0)
    /** Updates started since the app opened: Home loads its TMDB rows again on each. */
    val updates: StateFlow<Int> = _updates.asStateFlow()

    @Volatile private var job: Job? = null

    init {
        // The first time: a day from now.
        if (prefs.getLong(KEY_UPDATED_AT, 0L) == 0L) noteUpdated()
    }

    /** Everything was just loaded (the app's start or an update): the next ask is a day from now. */
    fun noteUpdated() {
        prefs.edit().putLong(KEY_UPDATED_AT, System.currentTimeMillis()).remove(KEY_LATER_UNTIL).apply()
    }

    /** Whether Home asks to update now. */
    fun isDue(now: Long = System.currentTimeMillis()): Boolean {
        if (job?.isActive == true) return false
        val updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)
        val laterUntil = prefs.getLong(KEY_LATER_UNTIL, 0L)
        // A clock set back: asked again from now.
        if (updatedAt > now) {
            noteUpdated()
            return false
        }
        return now - updatedAt >= DUE_AFTER_MS && now >= laterUntil
    }

    /** Later: asked again in [LATER_MS]. */
    fun later() {
        prefs.edit().putLong(KEY_LATER_UNTIL, System.currentTimeMillis() + LATER_MS).apply()
    }

    /** Update: everything loads again, its screen showing until it's in. */
    fun update() {
        if (job?.isActive == true) return
        _progress.value = 0f
        job = scope.launch {
            try {
                // The website's settings (plan, addons, IPTV login) and its banners and messages.
                launch {
                    try {
                        access.check()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
                publicFiles.refresh()
                // Home's TMDB rows and Movies & TV's catalogs, asked again.
                _updates.update { it + 1 }
                startupSync.requestCatalogRefresh()
                // The IPTV channels and guide (read again now), the games, the titles on now.
                withTimeoutOrNull(UPDATE_MAX_MS) {
                    preparer.prepare(refresh = true) { loaded ->
                        _progress.update { shown -> shown?.let { maxOf(it, loaded.coerceAtMost(0.96f)) } }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A load that failed never keeps the screen up: what loaded stays.
            } finally {
                preparer.finish()
            }
            noteUpdated()
            if (_progress.value != null) {
                // The bar fills before it goes.
                _progress.value = 1f
                delay(FILL_MS)
            }
            _progress.value = null
        }
    }

    /** Back on the update's screen: it goes, and the update finishes behind it. */
    fun hide() {
        _progress.value = null
    }

    private companion object {
        const val PREFS = "rctv_experience_updates"
        const val KEY_UPDATED_AT = "updated_at"
        const val KEY_LATER_UNTIL = "later_until"
        /** A day after everything last loaded. */
        const val DUE_AFTER_MS = 24L * 60 * 60 * 1000
        const val LATER_MS = 4L * 60 * 60 * 1000
        /** The longest the update's screen shows (the guide's programs can take minutes on a Mi Box). */
        const val UPDATE_MAX_MS = 120_000L
        const val FILL_MS = 600L
    }
}
