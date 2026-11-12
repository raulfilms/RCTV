package com.nuvio.tv.core.rctvcontrol

import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RCTV Control: the Home banners the profile watching sees right now: the published ones in their
 * dates, by plan (sports and Live TV: Full only) and Kids profile. Checked again every minute, so a
 * banner appears and goes at its times; Home only changes when the list does.
 */
@Singleton
class RctvHomeBanners @Inject constructor(
    publicFiles: RctvPublicFiles,
    access: RctvAccessManager,
    profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val minutes = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(CHECK_EVERY_MS)
        }
    }

    val visible: StateFlow<List<RctvBanner>> = combine(
        publicFiles.banners,
        publicFiles.collections,
        access.state.map { it.plan == RctvPlan.FULL }.distinctUntilChanged(),
        profileManager.kidsProfileActive,
        minutes
    ) { banners, collections, full, kids, now ->
        RctvBannerParser.visible(banners, collections, now, full = full, kids = kids)
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), emptyList())

    private companion object {
        const val CHECK_EVERY_MS = 60_000L
        const val STOP_AFTER_MS = 5_000L
    }
}
