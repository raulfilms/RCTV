package com.nuvio.tv.core.rctvcontrol

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.LibrarySyncService
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.core.sync.WatchStateMutationStore
import com.nuvio.tv.core.sync.WatchedItemsSyncService
import com.nuvio.tv.core.sync.library.LibrarySyncLocalStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.repository.LibraryRepositoryImpl
import com.nuvio.tv.data.repository.WatchProgressRepositoryImpl
import com.nuvio.tv.domain.model.LibrarySourceMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RCTV Control: the library, watched titles and progress of the profile watching, kept in its RCTV
 * account (the website), so every TV of the account has the same ones. RCTV replaces Nuvio's cloud:
 * the same sync as Nuvio's ([LibrarySyncService], [WatchedItemsSyncService], [WatchProgressSyncService]),
 * whose calls go to the website while the TV is activated ([com.nuvio.tv.core.sync.SyncRpc]).
 *
 * The first time a profile syncs with its account, what this TV already had goes up too (nothing is
 * lost moving from Nuvio) and everything is read again. Then: the profile's changes go up within a
 * minute, and the other TVs' changes come down every few minutes, when the app comes to the front and
 * when the profile changes.
 */
@Singleton
class RctvWatchSync @Inject constructor(
    @ApplicationContext context: Context,
    private val access: RctvAccessManager,
    private val profileManager: ProfileManager,
    private val librarySyncService: LibrarySyncService,
    private val libraryLocalStore: LibrarySyncLocalStore,
    private val watchedItemsSyncService: WatchedItemsSyncService,
    private val watchProgressSyncService: WatchProgressSyncService,
    private val mutationStore: WatchStateMutationStore,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val libraryRepository: LibraryRepositoryImpl,
    private val watchProgressRepository: WatchProgressRepositoryImpl
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Profiles read in full since the app started (then changes alone). */
    private val pulledThisRun: MutableSet<Int> = Collections.synchronizedSet(HashSet())

    /** Keeps the profile watching in sync while the app is open (run it from the screen's lifecycle). */
    suspend fun run() {
        combine(
            access.state.map { !it.needsActivation }.distinctUntilChanged(),
            profileManager.activeProfileId
        ) { activated, profileId -> activated to profileId }
            .collectLatest { (activated, profileId) ->
                if (!activated) {
                    // Not (or no longer) on an account: the next one starts over.
                    prefs.edit().clear().apply()
                    pulledThisRun.clear()
                    return@collectLatest
                }
                if (!access.watchSyncActive) return@collectLatest
                syncNow(profileId, full = profileId !in pulledThisRun)
                var ticks = 0
                while (true) {
                    delay(PUSH_EVERY_MS)
                    ticks++
                    if (ticks % PULL_EVERY_TICKS == 0) syncNow(profileId, full = false) else pushNow(profileId)
                }
            }
    }

    private suspend fun syncNow(profileId: Int, full: Boolean) {
        try {
            val firstOnAccount = !prefs.getBoolean(initKey(profileId), false)
            if (firstOnAccount) {
                // What this TV already had goes up with the account's.
                libraryLocalStore.queueAllItemsForPush(profileId)
                mutationStore.queueWatchedUpserts(watchedItemsPreferences.getAllItems(profileId), profileId)
                mutationStore.queueProgressUpserts(watchProgressPreferences.getAllRawEntries(profileId), profileId)
            }
            val readAll = full || firstOnAccount
            var ok = true

            if (libraryRepository.sourceMode.first() == LibrarySourceMode.LOCAL) {
                libraryRepository.isSyncingFromRemote = true
                try {
                    ok = librarySyncService.syncFromRemote(profileId, forceSnapshot = firstOnAccount).isSuccess && ok
                    libraryRepository.hasCompletedInitialPull = true
                } finally {
                    libraryRepository.isSyncingFromRemote = false
                }
            }

            if (watchProgressSyncService.shouldUseSupabaseWatchProgressSync(profileId)) {
                val watched = if (readAll) {
                    watchedItemsSyncService.syncSnapshotFromRemote(profileId)
                } else {
                    watchedItemsSyncService.syncDeltaFromRemote(profileId)
                }
                watchProgressRepository.hasCompletedInitialWatchedItemsPull = true
                ok = watched.isSuccess && ok
                if (watched.getOrNull()?.preservedLocalItems == true || firstOnAccount) {
                    ok = watchedItemsSyncService.pushToRemote(profileId).isSuccess && ok
                }

                watchProgressRepository.isSyncingFromRemote = true
                try {
                    val progress = if (readAll) {
                        watchProgressSyncService.syncSnapshotFromRemote(profileId)
                    } else {
                        watchProgressSyncService.syncDeltaFromRemote(profileId)
                    }
                    watchProgressRepository.hasCompletedInitialPull = true
                    ok = progress.isSuccess && ok
                    if (progress.getOrNull()?.preservedLocalItems == true || firstOnAccount) {
                        ok = watchProgressSyncService.pushToRemote(profileId).isSuccess && ok
                    }
                } finally {
                    watchProgressRepository.isSyncingFromRemote = false
                }
            } else {
                // Trakt or another tracker keeps this profile's progress.
                watchProgressRepository.hasCompletedInitialPull = true
                watchProgressRepository.hasCompletedInitialWatchedItemsPull = true
            }

            if (ok) {
                pulledThisRun.add(profileId)
                if (firstOnAccount) prefs.edit().putBoolean(initKey(profileId), true).apply()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Tried again on the next round; nothing local is lost (changes wait to go up).
            Log.w(TAG, "Watch sync failed: ${e.javaClass.simpleName}")
        }
    }

    /** The profile's own changes, up (when it has any). */
    private suspend fun pushNow(profileId: Int) {
        try {
            if (libraryRepository.sourceMode.first() == LibrarySourceMode.LOCAL) {
                librarySyncService.pushToRemote(profileId)
            }
            if (!watchProgressSyncService.shouldUseSupabaseWatchProgressSync(profileId)) return
            if (mutationStore.pendingWatchedUpserts(profileId).isNotEmpty() ||
                mutationStore.pendingWatchedDeletes(profileId).isNotEmpty()
            ) {
                watchedItemsSyncService.pushToRemote(profileId)
            }
            if (mutationStore.pendingProgressUpserts(profileId).isNotEmpty() ||
                mutationStore.pendingProgressDeletes(profileId).isNotEmpty()
            ) {
                watchProgressSyncService.pushToRemote(profileId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Watch push failed: ${e.javaClass.simpleName}")
        }
    }

    private fun initKey(profileId: Int) = "synced_p$profileId"

    private companion object {
        const val TAG = "RctvWatchSync"
        const val PREFS = "rctv_watch_sync"
        const val PUSH_EVERY_MS = 60_000L
        /** Every 5 minutes, the other TVs' changes. */
        const val PULL_EVERY_TICKS = 5
    }
}
