package com.nuvio.tv.core.rctvcontrol

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.livetv.LiveProgramLibrary
import com.nuvio.tv.data.local.LiveTvDataStore
import com.nuvio.tv.domain.model.LiveTvSource
import com.nuvio.tv.ui.screens.livetv.LiveGuideSnapshot
import com.nuvio.tv.ui.screens.livetv.LiveTvGuideLoader
import com.nuvio.tv.ui.screens.search.SportsSearchIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
 *
 * RCTV (owner): the screen waits for the guide's programs too (the games find their channel in
 * them), then looks up on TMDB what's on now and next on the first channels of the Guide (their
 * pictures and pages open at once); better a longer bar than waiting inside the app. The first time
 * the programs can take a few minutes on a Mi Box; after that they're kept on the TV and show at
 * once (refreshed behind when old).
 */
@Singleton
class RctvExperiencePreparer @Inject constructor(
    private val access: RctvAccessManager,
    private val profileManager: ProfileManager,
    private val liveTvDataStore: LiveTvDataStore,
    private val guideLoader: LiveTvGuideLoader,
    private val sportsIndex: SportsSearchIndex,
    private val programLibrary: LiveProgramLibrary
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
                ?: withTimeoutOrNull(sourcesWaitMs()) { liveTvDataStore.sources.first { it.isNotEmpty() } }
                ?: emptyList()
            saved.filter { it.enabled }
        }
        val withGuide = sources.isNotEmpty()
        val key = guideLoader.keyOf(sources)
        if (withGuide) guideLoader.ensure(sources)
        val withSports = full && !kids
        if (withSports) sportsIndex.ensure()

        val startedAt = System.currentTimeMillis()
        var channelsAt = 0L
        var titlesDone = !withGuide
        var titles: kotlinx.coroutines.Deferred<Unit>? = null
        coroutineScope {
            try {
                while (true) {
                    val now = System.currentTimeMillis()
                    val guide = guideLoader.guide.value?.takeIf { it.key == key }
                    // Ready when they're there: an old guide refreshing behind still shows what it had.
                    val channelsReady = !withGuide || (guide != null && (!guide.isLoading || guide.channels.isNotEmpty()))
                    if (channelsReady && channelsAt == 0L) channelsAt = now
                    val programsReady = !withGuide ||
                        (guide != null && channelsReady && (!guide.isEpgLoading || guide.epgByChannel.isNotEmpty()))
                    // The programs' TMDB titles, once the programs are in.
                    if (programsReady && withGuide && titles == null && guide != null) {
                        titles = async { lookUpTitles(guide) }
                    }
                    if (titles?.isCompleted == true) titlesDone = true
                    // The games come in a league at a time: ready when they all have.
                    // (Never more than SPORTS_MAX_MS: a league that doesn't answer doesn't hold the rest.)
                    val sportsReady = !withSports || !sportsIndex.isLoading || now - startedAt > SPORTS_MAX_MS
                    // The programs have no steps to count: their part creeps up while they come.
                    val programs = when {
                        programsReady -> 1f
                        channelsAt == 0L -> 0f
                        else -> ((now - channelsAt) / PROGRAMS_EXPECTED_MS.toFloat()).coerceAtMost(0.9f)
                    }
                    val titlesPart = when {
                        titlesDone -> 1f
                        titles == null -> 0f
                        else -> 0.5f
                    }
                    val parts = listOf(if (channelsReady) 1f else 0f, programs, if (sportsReady) 1f else 0f, titlesPart)
                    onProgress(parts.sum() / parts.size)
                    if (channelsReady && programsReady && sportsReady && titlesDone) return@coroutineScope
                    delay(CHECK_EVERY_MS)
                }
            } finally {
                titles?.cancel()
            }
        }
    }

    /**
     * What's on now and next on the Guide's first channels, looked up on TMDB (kept by
     * [LiveProgramLibrary]: the Guide's pictures and the program's page then need no wait). RCTV:
     * a series on now also gets its episode found (its page opens with it, no "Looking for TMDB").
     * At most [TITLES_MAX_MS]; what TMDB doesn't answer for is simply asked again in the Guide.
     */
    private suspend fun lookUpTitles(guide: LiveGuideSnapshot) {
        val now = System.currentTimeMillis()
        val programs = guide.channels.asSequence()
            .take(TITLE_CHANNELS)
            .flatMap { channel ->
                guide.epgByChannel[channel.epgChannelId ?: channel.id].orEmpty().asSequence()
                    .filter { it.endMs > now }
                    .take(2)
            }
            // What's on now first (the episodes looked up are those).
            .sortedBy { if (it.startMs <= now) 0 else 1 }
            .distinctBy { LiveProgramLibrary.cleanTitle(it.title).lowercase(java.util.Locale.ROOT) }
            .toList()
        if (programs.isEmpty()) return
        val gate = Semaphore(TITLES_AT_ONCE)
        kotlinx.coroutines.withTimeoutOrNull(TITLES_MAX_MS) {
            coroutineScope {
                programs.map { program ->
                    async {
                        gate.withPermit {
                            try {
                                val found = programLibrary.findTitle(program)
                                if (found != null && !found.isMovie && program.startMs <= now) programLibrary.match(program)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    /**
     * The screen is gone: the sports still loading stop until Search needs them (what's in stays),
     * as they would when Search closes. The guide keeps loading: the Guide and Home read it.
     */
    fun finish() {
        sportsIndex.pause()
    }

    private fun sourcesWaitMs(): Long =
        if (liveTvDataStore.managedByAccount) ACCOUNT_SOURCES_WAIT_MS else SOURCES_WAIT_MS

    companion object {
        /** The longest the screen waits for everything (then the app opens and the rest keeps loading). */
        const val MAX_WAIT_MS = 180_000L

        /** About how long the programs take once the channels are in (the bar's pace while they load). */
        private const val PROGRAMS_EXPECTED_MS = 60_000L

        /** The Guide's first channels whose programs are looked up on TMDB. */
        private const val TITLE_CHANNELS = 40
        private const val TITLES_AT_ONCE = 4
        private const val TITLES_MAX_MS = 30_000L

        /** The longest the screen waits for the sports. */
        private const val SPORTS_MAX_MS = 60_000L
        private const val SOURCES_WAIT_MS = 5_000L

        /**
         * With the account's IPTV set on the website, its source is there for sure, only still on
         * its way as the app opens (on a busy Mi Box that took longer than the old 1.5 s, and the
         * Networks page didn't show): waited for longer.
         */
        private const val ACCOUNT_SOURCES_WAIT_MS = 15_000L
        private const val CHECK_EVERY_MS = 200L
    }
}
