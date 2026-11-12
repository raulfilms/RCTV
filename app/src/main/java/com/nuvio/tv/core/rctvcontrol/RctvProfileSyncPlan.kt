package com.nuvio.tv.core.rctvcontrol

/**
 * RCTV Control: keeping a TV's profiles the same as its account's (every TV of the account has the
 * same ones). [known] is what the TV and the account last agreed on, slot by slot; it tells a profile
 * deleted on the account (known, now gone) from one just made on this TV (never known).
 */
data class ProfileSyncPlan(
    /** Account profiles to put on this TV (new or changed elsewhere). */
    val upsertLocal: List<RctvRemoteProfile> = emptyList(),
    /** This TV's profiles deleted on the account (never the main one). */
    val deleteLocal: List<Int> = emptyList(),
    /** This TV's profiles to send to the account (made or changed here). */
    val upsertRemote: List<RctvRemoteProfile> = emptyList(),
    /** Profiles deleted on this TV to delete on the account. */
    val removeRemote: List<Int> = emptyList(),
    /** What both sides have once the plan is done. */
    val known: Map<Int, RctvRemoteProfile> = emptyMap()
)

object RctvProfileSyncPlanner {
    const val MAIN_SLOT = 1

    /** The account's list arrived: what changes where. The account wins unless only this TV changed. */
    fun afterPull(
        local: List<RctvRemoteProfile>,
        remote: List<RctvRemoteProfile>,
        known: Map<Int, RctvRemoteProfile>
    ): ProfileSyncPlan {
        val localBySlot = local.associateBy { it.slot }
        val remoteBySlot = remote.associateBy { it.slot }
        val upsertLocal = mutableListOf<RctvRemoteProfile>()
        val upsertRemote = mutableListOf<RctvRemoteProfile>()
        val deleteLocal = mutableListOf<Int>()
        val after = mutableMapOf<Int, RctvRemoteProfile>()

        remote.forEach { r ->
            val l = localBySlot[r.slot]
            when {
                l == r -> after[r.slot] = r
                // Only this TV changed it since they last agreed: this TV's goes up.
                l != null && known[r.slot] == r -> {
                    upsertRemote += l
                    after[r.slot] = l
                }
                else -> {
                    upsertLocal += r
                    after[r.slot] = r
                }
            }
        }
        local.filter { it.slot !in remoteBySlot }.forEach { l ->
            if (l.slot in known && l.slot != MAIN_SLOT) {
                // Deleted on the website or on another TV.
                deleteLocal += l.slot
            } else {
                // Made on this TV (or the main profile, which the account always has).
                upsertRemote += l
                after[l.slot] = l
            }
        }
        return ProfileSyncPlan(
            upsertLocal = upsertLocal,
            deleteLocal = deleteLocal,
            upsertRemote = upsertRemote,
            known = after
        )
    }

    /** This TV's profiles changed: what to send to the account. */
    fun afterLocalChange(local: List<RctvRemoteProfile>, known: Map<Int, RctvRemoteProfile>): ProfileSyncPlan {
        val slots = local.mapTo(HashSet()) { it.slot }
        return ProfileSyncPlan(
            upsertRemote = local.filter { known[it.slot] != it },
            removeRemote = known.keys.filter { it !in slots && it != MAIN_SLOT }.sorted(),
            known = known
        )
    }
}
