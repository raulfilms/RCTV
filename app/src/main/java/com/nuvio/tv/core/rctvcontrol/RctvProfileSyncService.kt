package com.nuvio.tv.core.rctvcontrol

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.UserProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RCTV Control: the account's profiles are the same on every TV of the account. What the website
 * sends (every few minutes, [RctvAccessManager.remoteProfiles]) comes onto this TV; a profile made,
 * changed or deleted on this TV goes to the account right away ([RctvProfileSyncPlanner] decides).
 * A profile is in the same place everywhere: its slot is the TV's profile number (1 is the main one).
 */
@Singleton
class RctvProfileSyncService @Inject constructor(
    @ApplicationContext context: Context,
    private val access: RctvAccessManager,
    private val profileManager: ProfileManager
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    /** Keeps the profiles in sync while the app is open (run it from the screen's lifecycle). */
    suspend fun run() = coroutineScope {
        launch {
            // Disconnected from its account: what was agreed with it no longer counts.
            access.state.map { it.needsActivation }.distinctUntilChanged().collect { if (it) saveKnown(emptyMap()) }
        }
        launch { access.remoteProfiles.filterNotNull().collect { remote -> pull(remote) } }
        launch {
            // A change on this TV goes up once the profile screen settles.
            profileManager.profiles.drop(1).collectLatest {
                delay(PUSH_DELAY_MS)
                push()
            }
        }
    }

    private suspend fun pull(remote: List<RctvRemoteProfile>) = mutex.withLock {
        val known = readKnown()
        val localProfiles = profileManager.profiles.value
        val plan = RctvProfileSyncPlanner.afterPull(localProfiles.map(::toRemote), remote, known)
        val agreed = plan.known.toMutableMap()
        // What the account has, before applying it here (so the change it makes isn't sent back).
        saveKnown(agreed)
        plan.upsertLocal.forEach { r ->
            profileManager.applyAccountProfile(toLocal(r, localProfiles.firstOrNull { it.id == r.slot }))
        }
        plan.deleteLocal.forEach { slot ->
            if (profileManager.activeProfileId.value == slot) profileManager.setActiveProfile(RctvProfileSyncPlanner.MAIN_SLOT)
            profileManager.deleteProfile(slot)
        }
        plan.upsertRemote.forEach { p ->
            if (!access.upsertProfile(p)) {
                // Not sent: tried again next time.
                val before = known[p.slot]
                if (before != null) agreed[p.slot] = before else agreed.remove(p.slot)
            }
        }
        saveKnown(agreed)
    }

    private suspend fun push() = mutex.withLock {
        // Only with an account that keeps profiles (activated, and a website that sends them).
        if (access.state.value.needsActivation || access.remoteProfiles.value == null) return@withLock
        val known = readKnown()
        val plan = RctvProfileSyncPlanner.afterLocalChange(profileManager.profiles.value.map(::toRemote), known)
        if (plan.upsertRemote.isEmpty() && plan.removeRemote.isEmpty()) return@withLock
        val agreed = known.toMutableMap()
        plan.upsertRemote.forEach { p -> if (access.upsertProfile(p)) agreed[p.slot] = p }
        plan.removeRemote.forEach { slot -> if (access.removeProfile(slot)) agreed.remove(slot) }
        saveKnown(agreed)
    }

    private fun readKnown(): Map<Int, RctvRemoteProfile> {
        val json = prefs.getString(KEY_KNOWN, null) ?: return emptyMap()
        return runCatching { RctvControlParser.profilesOf(JSONArray(json)).associateBy { it.slot } }.getOrDefault(emptyMap())
    }

    private fun saveKnown(known: Map<Int, RctvRemoteProfile>) {
        val array = JSONArray()
        known.values.sortedBy { it.slot }.forEach { p ->
            array.put(
                JSONObject()
                    .put("slot", p.slot)
                    .put("name", p.name)
                    .put("color", p.color)
                    .put("avatar", p.avatar ?: "default")
                    .put("avatar_url", p.avatarUrl ?: JSONObject.NULL)
                    .put("is_kids", p.isKids)
            )
        }
        prefs.edit().putString(KEY_KNOWN, array.toString()).apply()
    }

    companion object {
        private const val PREFS = "rctv_control_profiles"
        private const val KEY_KNOWN = "known"
        private const val PUSH_DELAY_MS = 1_500L
        private val COLOR = Regex("^#[0-9A-Fa-f]{6}$")

        /** A TV profile as the account keeps it. */
        fun toRemote(profile: UserProfile): RctvRemoteProfile = RctvRemoteProfile(
            slot = profile.id,
            name = profile.name.trim().take(60).ifBlank { "Profile ${profile.id}" },
            color = profile.avatarColorHex.takeIf { COLOR.matches(it) } ?: RctvRemoteProfile.DEFAULT_COLOR,
            avatar = profile.avatarId?.takeIf { it.isNotBlank() && it.length <= 200 && it != "default" },
            avatarUrl = profile.avatarUrl?.takeIf { it.startsWith("https://") && it.length <= 500 },
            isKids = profile.isKids && profile.id != RctvProfileSyncPlanner.MAIN_SLOT
        )

        /**
         * An account profile on this TV, keeping what only this TV has (its background, its addons).
         * A profile new on this TV uses the main profile's addons, so it can play right away.
         */
        fun toLocal(remote: RctvRemoteProfile, existing: UserProfile?): UserProfile {
            val base = existing ?: UserProfile(
                id = remote.slot,
                name = remote.name,
                avatarColorHex = remote.color,
                usesPrimaryAddons = remote.slot != RctvProfileSyncPlanner.MAIN_SLOT,
                usesPrimaryPlugins = remote.slot != RctvProfileSyncPlanner.MAIN_SLOT
            )
            return base.copy(
                name = remote.name,
                avatarColorHex = remote.color,
                avatarId = remote.avatar,
                avatarUrl = remote.avatarUrl,
                isKids = remote.isKids
            )
        }
    }
}
