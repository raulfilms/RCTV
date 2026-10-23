package com.nuvio.tv.data.livetv

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.smartscraper.SmartScraperEnvironment
import com.nuvio.tv.core.smartscraper.SmartSourceRequest
import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.repository.StreamRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A guide program's movie or episode, to watch from its sources (from the beginning, like any title)
 * instead of live: what TMDB says it is, and its IMDb id (what the sources are asked by).
 */
data class LiveVodTarget(
    val match: LiveProgramMatch,
    val imdbId: String
) {
    val isMovie: Boolean get() = match.isMovie
    val contentType: String get() = if (match.isMovie) "movie" else "series"
    val season: Int? get() = match.episode?.season
    val episode: Int? get() = match.episode?.episode
    val episodeName: String? get() = match.episode?.name
    /** The movie's id, or the episode's ("tt0452046:1:5"), as the sources are asked. */
    val videoId: String get() = if (match.isMovie) imdbId else "$imdbId:$season:$episode"
    val title: String get() = match.name
    val year: String? get() = match.year?.toString()
}

/** Whether a program's movie or episode can be watched from its sources. */
enum class LiveVodStatus { CHECKING, AVAILABLE, UNAVAILABLE }

data class LiveVodState(val target: LiveVodTarget, val status: LiveVodStatus) {
    val isAvailable: Boolean get() = status == LiveVodStatus.AVAILABLE
}

/**
 * Finds out, ahead of time, whether what's on in the guide can be watched from the beginning: its
 * movie or episode on TMDB (only when the guide says which episode it is, never a guess), then its
 * sources (asked the way Auto Stream asks; the answer stays in the app's stream cache, so playing it
 * then starts without searching again). Only movies and series: a newscast, a game, a talk show
 * isn't on TMDB as such and is never offered.
 */
@Singleton
class LiveVodFinder @Inject constructor(
    private val liveProgramLibrary: LiveProgramLibrary,
    private val streamRepository: StreamRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** A few searches at a time (focus moving through the guide asks for one per program). */
    private val searches = Semaphore(MAX_PARALLEL_SEARCHES)

    private val targets = ConcurrentHashMap<String, Deferred<LiveVodTarget?>>()

    private class Check(val status: MutableStateFlow<LiveVodStatus>, @Volatile var doneAtMs: Long = 0L)
    private val checks = ConcurrentHashMap<String, Check>()

    /**
     * Starts finding out about [program] (TMDB, then its sources) if it isn't known yet; returns
     * TMDB's part.
     */
    fun prepare(program: EpgProgram): Deferred<LiveVodTarget?> {
        val key = programKey(program)
        // Known (or being looked up): its sources are asked again only once their answer is old.
        targets[key]?.let { existing -> return existing.also { startCheckWhenKnown(it) } }
        if (targets.size > MAX_KEPT) targets.clear()
        val lookup = scope.async { findTarget(program) }
        targets[key] = lookup
        // TMDB couldn't be reached: asked again next time.
        lookup.invokeOnCompletion { error -> if (error != null) targets.remove(key, lookup) }
        startCheckWhenKnown(lookup)
        return lookup
    }

    /** The program's movie or episode, waiting [timeoutMs] at most for TMDB; null when there's none (yet). */
    suspend fun target(program: EpgProgram, timeoutMs: Long): LiveVodTarget? {
        val lookup = prepare(program)
        return withTimeoutOrNull(timeoutMs) {
            try {
                lookup.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Whether [target] can be watched from its sources (asked once, then kept a while). */
    fun status(target: LiveVodTarget): StateFlow<LiveVodStatus> = ensureCheck(target).status.asStateFlow()

    /** [target]'s status, waiting [timeoutMs] at most while its sources are still being asked. */
    suspend fun awaitStatus(target: LiveVodTarget, timeoutMs: Long): LiveVodStatus {
        val flow = status(target)
        return withTimeoutOrNull(timeoutMs) { flow.first { it != LiveVodStatus.CHECKING } } ?: flow.value
    }

    private fun startCheckWhenKnown(lookup: Deferred<LiveVodTarget?>) {
        scope.launch {
            val target = runCatching { lookup.await() }.getOrNull() ?: return@launch
            ensureCheck(target)
        }
    }

    private fun ensureCheck(target: LiveVodTarget): Check {
        val key = target.videoId
        val now = System.currentTimeMillis()
        checks[key]?.let { existing ->
            val status = existing.status.value
            val maxAge = if (status == LiveVodStatus.AVAILABLE) AVAILABLE_MAX_AGE_MS else UNAVAILABLE_MAX_AGE_MS
            if (status == LiveVodStatus.CHECKING || now - existing.doneAtMs < maxAge) return existing
        }
        val check = Check(MutableStateFlow(LiveVodStatus.CHECKING))
        checks[key] = check
        scope.launch {
            val available = try {
                searches.withPermit { hasSources(target) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't ask the sources: ${e.message}")
                false
            }
            check.doneAtMs = System.currentTimeMillis()
            check.status.value = if (available) LiveVodStatus.AVAILABLE else LiveVodStatus.UNAVAILABLE
            Log.i(TAG, "${target.title} ${target.season?.let { "S${it}E${target.episode}" }.orEmpty()}: " +
                if (available) "can be watched from the beginning" else "no sources")
        }
        return check
    }

    /** Throws when TMDB can't be reached (nothing is kept then). */
    private suspend fun findTarget(program: EpgProgram): LiveVodTarget? {
        // Its title first (a generic one, "News", is never looked further), then the episode airing.
        val title = liveProgramLibrary.findTitle(program) ?: return null
        val match = if (title.isMovie) title else liveProgramLibrary.match(program) ?: return null
        // A series whose airing episode can't be told: nothing is offered (never the wrong episode).
        if (!match.isMovie && match.episode == null) return null
        // No IMDb id (or it couldn't be asked): asked again next time.
        val imdb = liveProgramLibrary.imdbId(match) ?: throw java.io.IOException("No IMDb id for ${match.name}")
        return LiveVodTarget(match, imdb)
    }

    /** Asks the sources for [target] and whether Auto Stream would play one of them. */
    private suspend fun hasSources(target: LiveVodTarget): Boolean {
        val request = SmartSourceRequest(
            title = target.match.name,
            year = target.match.year,
            isSeries = !target.isMovie,
            season = target.season,
            episode = target.episode,
            imdbId = target.imdbId
        )
        // One that plays is enough to know (the search itself goes on, into the app's stream cache).
        val playable = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
            streamRepository.getStreamsFromAllAddons(
                type = target.contentType,
                videoId = target.videoId,
                season = target.season,
                episode = target.episode
            ).firstOrNull { result ->
                val streams = (result as? NetworkResult.Success)?.data?.flatMap { it.streams }.orEmpty()
                streams.isNotEmpty() && withContext(Dispatchers.Default) {
                    SmartScraperEnvironment.autoStreamRank(streams, request).chain.isNotEmpty()
                }
            }
        }
        return playable != null
    }

    private fun programKey(program: EpgProgram): String = "${program.title}|${program.subtitle}|${program.startMs}"

    private companion object {
        const val TAG = "LiveVod"
        const val MAX_PARALLEL_SEARCHES = 3
        const val MAX_KEPT = 600
        const val SEARCH_TIMEOUT_MS = 30_000L
        const val AVAILABLE_MAX_AGE_MS = 10L * 60 * 1000
        const val UNAVAILABLE_MAX_AGE_MS = 5L * 60 * 1000
    }
}
