package com.nuvio.tv.core.rctvcontrol

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.LiveTvDataStore
import com.nuvio.tv.domain.model.LiveTvSource
import com.nuvio.tv.ui.screens.livetv.LiveTvGuideLoader
import com.nuvio.tv.ui.screens.search.SportsSearchIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RCTV, "Creating Your Experience": what loads behind that screen as the app opens, besides Home's
 * own rows: the Live TV channels and their guide, and the sports (teams and games) Search and the
 * banners find. Only what the profile can see: Live TV and sports with Full; no sports on Kids.
 * When the screen goes, what's loaded is there at once (the guide stays loaded; the sports kept on
 * the TV for next time).
 */
@Singleton
class RctvExperiencePreparer @Inject constructor(
    private val access: RctvAccessManager,
    private val profileManager: ProfileManager,
    private val liveTvDataStore: LiveTvDataStore,
    private val guideLoader: LiveTvGuideLoader,
    private val sportsIndex: SportsSearchIndex
) {
    /**
     * Starts the loads and returns when they're all done, telling [onProgress] how far they are
     * (0 to 1) as they go. Cancelling it stops only the waiting: the loads go on.
     */
    suspend fun prepare(onProgress: (Float) -> Unit) {
        val full = access.state.value.plan == RctvPlan.FULL
        val kids = profileManager.kidsProfileActive.value
        val sources: List<LiveTvSource> = if (!full) {
            emptyList()
        } else {
            val saved = liveTvDataStore.sources.value.takeIf { it.isNotEmpty() }
                // Just opened: the account's IPTV login may still be on its way from storage.
                ?: withTimeoutOrNull(SOURCES_WAIT_MS) { liveTvDataStore.sources.first { it.isNotEmpty() } }
                ?: emptyList()
            saved.filter { it.enabled }
        }
        val withGuide = sources.isNotEmpty()
        val key = guideLoader.keyOf(sources)
        if (withGuide) guideLoader.ensure(sources)
        val withSports = full && !kids
        if (withSports) sportsIndex.ensure()

        while (true) {
            val guide = guideLoader.guide.value?.takeIf { it.key == key }
            // Ready when they're there: an old guide refreshing behind still shows what it had.
            val channelsReady = !withGuide || (guide != null && (!guide.isLoading || guide.channels.isNotEmpty()))
            val programsReady = !withGuide ||
                (guide != null && channelsReady && (!guide.isEpgLoading || guide.epgByChannel.isNotEmpty()))
            // The games come in a league at a time: ready when they all have.
            val sportsReady = !withSports || !sportsIndex.isLoading
            val parts = listOf(channelsReady, programsReady, sportsReady)
            onProgress(parts.count { it } / parts.size.toFloat())
            if (parts.all { it }) return
            delay(CHECK_EVERY_MS)
        }
    }

    /**
     * The screen is gone: the sports still loading stop until Search needs them (what's in stays),
     * as they would when Search closes. The guide keeps loading: the Guide and Home read it.
     */
    fun finish() {
        sportsIndex.pause()
    }

    companion object {
        /** The longest the screen waits for everything (then the app opens and the rest keeps loading). */
        const val MAX_WAIT_MS = 25_000L
        private const val SOURCES_WAIT_MS = 1_500L
        private const val CHECK_EVERY_MS = 200L
    }
}
