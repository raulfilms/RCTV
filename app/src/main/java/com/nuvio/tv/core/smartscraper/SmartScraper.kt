package com.nuvio.tv.core.smartscraper

import android.util.Log
import com.nuvio.tv.domain.model.Stream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RCTV's Smart Scraper: every addon's streams for a title (they're asked all at once, each with
 * its own time limit, and one failing never holds the rest) are read the same way, checked against
 * the title, filtered, scored and ranked, best first; the same file from several addons/services
 * once. It learns which providers answer and play, and how the connection does, to rank better.
 */
@Singleton
class SmartScraper @Inject constructor(
    private val settings: SmartScraperSettingsStore,
    private val reliability: SourceReliabilityStore,
    private val connection: ConnectionMonitor,
    private val device: DeviceCapabilities
) {
    private val normalized = ConcurrentHashMap<String, NormalizedSource>()
    private val searchIds = AtomicLong(0)

    init {
        SmartScraperEnvironment.install(this)
    }

    val config: SmartScraperConfig get() = settings.config.value

    /** Everything the ranking knows beyond the streams themselves, as it is now. */
    fun rankingContext(): SmartRankingContext {
        val config = settings.config.value
        return SmartRankingContext(
            config = config,
            connection = if (config.connectionAware) connection.estimate(learn = config.learnFromPlayback) else ConnectionEstimate.UNKNOWN,
            device = device.profile(),
            providerStats = if (config.learnFromPlayback) reliability.providerStats() else emptyMap(),
            failedReleaseKeys = if (config.learnFromPlayback) reliability.failedReleaseKeys() else emptySet()
        )
    }

    /** [stream] read by its provider's adapter (kept, so a stream is read once however often the list changes). */
    fun normalize(stream: Stream): NormalizedSource {
        val key = cacheKey(stream)
        // The same stream read before (its badges may have changed since: the stream itself is this one).
        normalized[key]?.let { return if (it.stream === stream) it else it.copy(stream = stream) }
        val source = runCatching { SourceProviderAdapters.normalize(stream) }.getOrElse {
            Log.w(TAG, "Couldn't read a stream: ${it.message}")
            GenericStreamAdapter.normalize(stream)
        }
        if (normalized.size > MAX_CACHED) normalized.clear()
        normalized[key] = source
        return source
    }

    /** A title's streams ranked for this person, device and connection. */
    fun rank(
        streams: List<Stream>,
        request: SmartSourceRequest,
        context: SmartRankingContext = rankingContext()
    ): SmartSourcesResult {
        if (streams.isEmpty()) return SmartSourcesResult.EMPTY
        val result = SmartScoreEngine.rank(streams.map(::normalize), request, context)
        if (context.config.debug) log(result, request, context)
        return result
    }

    /** An addon answered for a title after [responseMs] (or failed, or timed out). */
    fun recordProviderResponse(provider: String, responseMs: Long?, ok: Boolean) {
        if (!config.learnFromPlayback) return
        reliability.recordResponse(provider, responseMs, ok)
    }

    /** A stream played or failed to (its file is ranked down for a while when it failed). */
    fun recordPlayback(stream: Stream, ok: Boolean) {
        if (!config.learnFromPlayback) return
        val source = normalize(stream)
        reliability.recordPlayback(source.provider.key, SourceProviderAdapters.releaseKey(source), ok)
    }

    /** What a play showed about the connection (its download speed; whether its file buffered). */
    fun recordConnection(throughputMbps: Double?, fileBitrateMbps: Double?, rebuffered: Boolean) {
        if (!config.learnFromPlayback) return
        throughputMbps?.let(connection::recordThroughput)
        fileBitrateMbps?.let { if (rebuffered) connection.recordRebuffering(it) else connection.recordSmoothPlayback(it) }
    }

    private fun cacheKey(stream: Stream): String = buildString {
        append(stream.stableKey())
        append('\u0000').append(stream.debridCacheStatus?.state ?: "")
        append('\u0000').append(stream.behaviorHints?.videoSize ?: "")
        append('\u0000').append(stream.behaviorHints?.filename ?: "")
    }

    // ------------------------------------------------------------------ Debug mode

    private fun log(result: SmartSourcesResult, request: SmartSourceRequest, context: SmartRankingContext) {
        val id = searchIds.incrementAndGet()
        Log.d(
            TAG,
            "Search #$id: ${request.title} (${request.year ?: "?"})" +
                (if (request.isEpisode) " S${request.season}E${request.episode}" else "") +
                " imdb=${request.imdbId ?: "?"} · ${result.all.size + result.rejected.size} found, " +
                "${result.rejected.size} rejected, ${result.smart.size} smart · " +
                "connection=${context.connection.profile} ${context.connection.availableMbps ?: "?"} Mbps · " +
                "target=${SmartScoreEngine.targetResolution(context).label}"
        )
        (result.all + result.rejected).forEach { scored ->
            val release = scored.release
            val verdict = scored.rejection?.let { "REJECTED (${it.description})" } ?: "VALID"
            val b = scored.breakdown
            Log.d(
                TAG,
                "#$id $verdict score=${scored.score} " +
                    "[meta=${f(b.metadata)} video=${f(b.video)} audio=${f(b.audio)} size=${f(b.size)} " +
                    "net=${f(b.connection)} provider=${f(b.sourceReliability)} play=${f(b.playbackReliability)}] " +
                    "title=${release.title}/${scored.titleMatch} year=${release.year} " +
                    "S${release.seasons}E${release.episodes}${if (release.isSeasonPack) " pack" else ""} " +
                    "${release.resolution.label} ${release.source.label} ${release.codec.label} ${release.hdr.joinToString("+") { it.label }} " +
                    "${release.audioCodec.shortLabel}${if (release.atmos) " Atmos" else ""} ${release.channels.label} " +
                    "${release.sizeGb?.let { String.format(Locale.US, "%.2fGB", it) } ?: "size?"} " +
                    "${scored.bitrateMbps?.let { String.format(Locale.US, "%.1fMbps", it) } ?: ""} " +
                    "cache=${scored.source.cache} provider=${scored.source.provider.family}/${scored.source.provider.variant}/${scored.source.provider.debrid} " +
                    "RAW=${release.releaseName ?: scored.stream.title?.lineSequence()?.firstOrNull()}"
            )
        }
        result.smart.forEachIndexed { index, entry ->
            Log.d(TAG, "#$id SMART ${index + 1}: score=${entry.best.score} ${entry.labels} copies=${entry.copyCount} ${entry.best.release.releaseName}")
        }
    }

    private fun f(value: Float) = String.format(Locale.US, "%.2f", value)

    private companion object {
        const val TAG = "SmartScraper"
        const val MAX_CACHED = 4_000
    }
}

/**
 * The Smart Scraper for code that isn't given it (the auto-play selector): the app's one, once it
 * exists; else the defaults.
 */
object SmartScraperEnvironment {
    @Volatile private var scraper: SmartScraper? = null

    fun install(scraper: SmartScraper) {
        this.scraper = scraper
    }

    fun rank(streams: List<Stream>, request: SmartSourceRequest): SmartSourcesResult =
        scraper?.rank(streams, request)
            ?: SmartScoreEngine.rank(streams.map(SourceProviderAdapters::normalize), request)

    fun recordPlayback(stream: Stream, ok: Boolean) {
        scraper?.recordPlayback(stream, ok)
    }

    fun recordProviderResponse(provider: String, responseMs: Long?, ok: Boolean) {
        scraper?.recordProviderResponse(provider, responseMs, ok)
    }

    fun recordConnection(throughputMbps: Double?, fileBitrateMbps: Double?, rebuffered: Boolean) {
        scraper?.recordConnection(throughputMbps, fileBitrateMbps, rebuffered)
    }

    val config: SmartScraperConfig get() = scraper?.config ?: SmartScraperConfig.DEFAULT
}
