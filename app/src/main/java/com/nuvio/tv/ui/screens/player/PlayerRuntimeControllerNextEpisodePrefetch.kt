package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.debrid.DirectDebridPlayableResult
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.core.smartscraper.AudioLanguage
import com.nuvio.tv.core.smartscraper.SmartScraperEnvironment
import com.nuvio.tv.core.smartscraper.SmartSourceRequest
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.enabledAddons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

/*
 * RCTV: the next episode is ready before it's asked for. With about 7 minutes of the episode left,
 * the app finds the next one's sources, picks the best the Auto Stream way (the right episode, the
 * resolution the TV and the internet take, the language chosen), gets its link from the debrid
 * service and opens it (its first megabytes, on the player's own connection). "Next episode" or
 * the auto-play then start it at once: no "finding sources".
 */

/** The next episode made ready: what will play ([playable], its link already given) and what to fall back on. */
internal class NextEpisodePrefetch(
    val videoId: String,
    val original: Stream,
    val playable: Stream,
    val fallbacks: List<Stream>,
    val preparedAtMs: Long
)

private const val TAG = "NextEpisodePrefetch"
/** How long before the end of the episode the next one is made ready. */
private const val PREFETCH_BEFORE_END_MS = 7L * 60 * 1000
/** The addons' answers for the next episode are waited for this long at most. */
private const val PREFETCH_SEARCH_TIMEOUT_MS = 45_000L
/** A ready link is used within this long (debrid links last hours). */
private const val PREFETCH_MAX_AGE_MS = 60L * 60 * 1000
/** "Next episode" pressed while it's still being made ready: waited for this long at most. */
private const val PREFETCH_WAIT_MS = 15_000L
/** The first megabytes opened (the server is woken up, the connection kept for the player). */
private const val WARM_BYTES = 2L * 1024 * 1024
private const val MAX_TRIES = 4

/** Called with the playback's progress: the next episode is made ready ~7 minutes before the end. */
internal fun PlayerRuntimeController.maybePrefetchNextEpisode(positionMs: Long, durationMs: Long) {
    val next = nextEpisodeVideo ?: return
    if (_uiState.value.nextEpisode?.hasAired != true) return
    if (contentType.equals("cloud", ignoreCase = true)) return
    if (durationMs <= 0L || positionMs <= 0L) return
    val remaining = durationMs - positionMs
    // A short episode (a cartoon's 11 minutes): from its middle.
    if (remaining <= 0L || remaining > minOf(PREFETCH_BEFORE_END_MS, durationMs / 2)) return
    if (nextEpisodePrefetchVideoId == next.id) return
    nextEpisodePrefetchVideoId = next.id
    nextEpisodePrefetch = null
    nextEpisodePrefetchJob?.cancel()
    nextEpisodePrefetchJob = scope.launch {
        runCatching { prefetchNextEpisode(next) }
            .onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.w(TAG, "Couldn't prepare the next episode: ${error.message}")
            }
    }
}

/**
 * The next episode's ready stream for [videoId], waiting a little if it's still being made ready;
 * null when there's none (then it's searched for as before).
 */
internal suspend fun PlayerRuntimeController.awaitPrefetchedNextEpisode(videoId: String): NextEpisodePrefetch? {
    val job = nextEpisodePrefetchJob
    if (job?.isActive == true && nextEpisodePrefetchVideoId == videoId) {
        withTimeoutOrNull(PREFETCH_WAIT_MS) { job.join() }
    }
    val ready = nextEpisodePrefetch?.takeIf {
        it.videoId == videoId && System.currentTimeMillis() - it.preparedAtMs < PREFETCH_MAX_AGE_MS
    } ?: return null
    nextEpisodePrefetch = null
    return ready
}

private suspend fun PlayerRuntimeController.prefetchNextEpisode(next: Video) {
    val type = contentType ?: return
    val playerSettings = playerSettingsDataStore.playerSettings.first()
    // Only when the next episode is picked the Auto Stream way (as playNextEpisode would pick it).
    val manual = playerSettings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL
    val manualAutoNext = manual &&
        (playerSettings.streamAutoPlayNextEpisodeEnabled || playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode)
    if (playerSettings.streamAutoPlayMode != StreamAutoPlayMode.AUTO_STREAM && !manualAutoNext) return
    val bingeGroupOnly = manualAutoNext &&
        (!playerSettings.streamAutoPlayNextEpisodeEnabled || !playerSettings.streamAutoPlayNextEpisodeFallbackEnabled) &&
        playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode
    val startedAt = System.currentTimeMillis()

    var latest: List<AddonStreams> = emptyList()
    withTimeoutOrNull(PREFETCH_SEARCH_TIMEOUT_MS) {
        streamRepository.getStreamsFromAllAddons(
            type = type,
            videoId = next.id,
            season = next.season,
            episode = next.episode
        ).collect { result -> if (result is NetworkResult.Success) latest = result.data }
    }
    val installedAddonOrder = addonRepository.getInstalledAddons().first().enabledAddons().map { it.displayName }
    val streams = StreamAutoPlaySelector.orderAddonStreams(latest, installedAddonOrder).flatMap { it.streams }
    if (streams.isEmpty()) {
        Log.i(TAG, "No sources for the next episode yet")
        return
    }

    val request = nextEpisodeSmartRequest(next, playerSettings)
    val ranked = SmartScraperEnvironment.autoStreamRank(streams, request)
    val preferredGroup = currentStreamBingeGroup?.takeIf { playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode }
    val first = StreamAutoPlaySelector.selectAutoPlayStream(
        streams = streams,
        mode = StreamAutoPlayMode.AUTO_STREAM,
        regexPattern = "",
        source = if (manualAutoNext) StreamAutoPlaySource.ALL_SOURCES else playerSettings.streamAutoPlaySource,
        installedAddonNames = installedAddonOrder.toSet(),
        selectedAddons = if (manualAutoNext) emptySet() else playerSettings.streamAutoPlaySelectedAddons,
        selectedPlugins = if (manualAutoNext) emptySet() else playerSettings.streamAutoPlaySelectedPlugins,
        preferredBingeGroup = preferredGroup,
        preferBingeGroupInSelection = playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode,
        bingeGroupOnly = bingeGroupOnly,
        smartRequest = request,
        smartRanking = ranked.chain
    ) ?: return
    val chain = listOf(first) + ranked.chain.filterNot { it == first }

    // Its link from the debrid service, opened: the first one that answers is the one.
    chain.take(MAX_TRIES).forEachIndexed { index, candidate ->
        val playable = when (val result = directDebridResolver.resolveToPlayableStream(candidate, next.season, next.episode)) {
            is DirectDebridPlayableResult.Success -> result.stream
            else -> null
        } ?: run {
            SmartScraperEnvironment.recordPlayback(candidate, ok = false)
            return@forEachIndexed
        }
        val url = playable.getStreamUrl()
        if (url != null && !warmUp(url, playable.behaviorHints?.proxyHeaders?.request.orEmpty())) {
            Log.i(TAG, "A source of the next episode didn't answer: the next one")
            SmartScraperEnvironment.recordPlayback(candidate, ok = false)
            return@forEachIndexed
        }
        nextEpisodePrefetch = NextEpisodePrefetch(
            videoId = next.id,
            original = candidate,
            playable = playable,
            fallbacks = chain.drop(index + 1),
            preparedAtMs = System.currentTimeMillis()
        )
        Log.i(TAG, "S${next.season}E${next.episode} ready in ${System.currentTimeMillis() - startedAt} ms: " +
            sourceSummary(candidate).orEmpty())
        return
    }
}

/** The next episode as the app knows it (its streams are checked against it). */
internal fun PlayerRuntimeController.nextEpisodeSmartRequest(next: Video, playerSettings: PlayerSettings) = SmartSourceRequest(
    title = contentName ?: title,
    alternateTitles = listOfNotNull(title.takeIf { it.isNotBlank() && it != contentName }),
    year = SmartSourceRequest.yearOf(year),
    isSeries = true,
    season = next.season,
    episode = next.episode,
    imdbId = contentId?.takeIf { it.startsWith("tt") }?.substringBefore(':'),
    runtimeMinutes = next.runtime,
    equivalentEpisodes = com.nuvio.tv.core.imdb.EpisodeOrderRegistry.equivalentEpisodes(contentId, next.season, next.episode),
    preferredLanguage = AudioLanguage.ofCode(playerSettings.preferredAudioLanguage) ?: AudioLanguage.ofCode(contentLanguage),
    originalLanguage = AudioLanguage.ofCode(contentLanguage)
)

/**
 * Opens [url]'s first megabytes on the player's own connection: false when it doesn't answer, or
 * when it's a debrid error clip instead of the file (RCTV: a few megabytes in all, or one of the
 * addons' error videos: "Debrid not working"...).
 */
internal suspend fun warmUp(url: String, headers: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-${WARM_BYTES - 1}")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        PlayerPlaybackNetworking.playbackHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use false
            if (isDebridErrorClip(response)) return@use false
            val input = response.body?.byteStream() ?: return@use false
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (total < WARM_BYTES) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
            }
            true
        }
    }.getOrDefault(false)
}

/** Smaller than this in all, a movie's or an episode's "file" is an addon's error clip. */
private const val ERROR_CLIP_MAX_BYTES = 40L * 1024 * 1024

/** RCTV: the answer is an addon's error video (its address, or a whole file of a few megabytes). */
internal fun isDebridErrorClip(response: okhttp3.Response): Boolean {
    val path = response.request.url.encodedPath.lowercase()
    if (path.contains("/videos/") && path.endsWith(".mp4")) return true
    val total = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
        ?: response.body?.contentLength()?.takeIf { response.code == 200 && it > 0 }
    return total != null && total in 1 until ERROR_CLIP_MAX_BYTES
}
