package com.nuvio.tv.core.smartscraper

import com.nuvio.tv.domain.model.Stream
import java.util.Locale

/** Why a stream can't be Auto Stream's choice. */
enum class AutoStreamReject(val description: String) {
    WRONG_CONTENT("Wrong title, year, season or episode"),
    NOT_PLAYABLE("Not playable here"),
    NOT_CACHED("Not cached on the debrid service"),
    LOW_QUALITY_SOURCE("CAM/TS/TC release"),
    SIZE_UNKNOWN("Size not given (it can't be checked under the limit)"),
    TOO_LARGE("At or over the Auto Stream size limit"),
    AUDIO_NOT_ALLOWED("Audio other than 5.1 or stereo"),
    AUDIO_UNVERIFIED("Audio not given"),
    LOW_RESOLUTION("Below 480p"),
    THREE_D("3D")
}

/** A release Auto Stream can play, its copies from other addons/services kept to fall back on. */
data class AutoStreamCandidate(
    val scored: ScoredSource,
    val score: Int,
    /** Its audio (5.1 or stereo) and resolution are known; unverified ones are tried last. */
    val verified: Boolean,
    /** The connection should play it smoothly. */
    val fitsConnection: Boolean,
    val copies: List<ScoredSource> = emptyList()
) {
    val stream: Stream get() = scored.stream
    val release: ParsedRelease get() = scored.release
}

data class AutoStreamResult(
    /** What Auto Stream may play, best first, each release once. */
    val candidates: List<AutoStreamCandidate>,
    val rejected: List<Pair<ScoredSource, AutoStreamReject>>,
    /** What to play, then what to try next when it fails, in order (never another title or episode). */
    val chain: List<Stream>
) {
    val best: AutoStreamCandidate? get() = candidates.firstOrNull()

    companion object {
        val EMPTY = AutoStreamResult(emptyList(), emptyList(), emptyList())
    }
}

/**
 * Auto Stream's choice: not the absolute best release, the highest practical quality that starts
 * quickly and plays smoothly, under hard rules: the right title, year, season and episode; under
 * 6 GB; 480p to 4K; 5.1 or stereo only. Then the highest resolution the connection plays (4K → 2K →
 * 1080p → 720p → 480p), 5.1 over stereo, and among near-equals the source that starts fastest and
 * most reliably. HDR, IMAX and the codec only tip the balance; they never break a rule.
 */
object AutoStreamEngine {

    fun rank(
        sources: List<NormalizedSource>,
        request: SmartSourceRequest,
        context: SmartRankingContext = SmartRankingContext.DEFAULT
    ): AutoStreamResult {
        if (sources.isEmpty()) return AutoStreamResult.EMPTY
        val config = context.config.autoStream
        val rejected = ArrayList<Pair<ScoredSource, AutoStreamReject>>()
        val valid = ArrayList<AutoStreamCandidate>()
        for (source in sources) {
            val scored = SmartScoreEngine.judge(source, request, context)
            val reject = rejection(scored, request, config)
            if (reject != null) {
                rejected += scored to reject
                continue
            }
            val fits = fitsConnection(scored.requiredMbps, context)
            valid += AutoStreamCandidate(
                scored = scored,
                score = score(scored, fits, context),
                verified = scored.release.channels in config.allowedChannels && scored.release.resolution.isKnown,
                fitsConnection = fits
            )
        }
        val ordered = valid.sortedWith(ORDER)
        // The same file from several addons/services: its fastest, most reliable copy first.
        val groups = LinkedHashMap<String, MutableList<AutoStreamCandidate>>()
        ordered.forEach { groups.getOrPut(SourceProviderAdapters.releaseKey(it.scored.source)) { mutableListOf() }.add(it) }
        val candidates = groups.values.map { members -> members.first().copy(copies = members.drop(1).map { it.scored }) }
        val chain = candidates
            .flatMap { candidate -> listOf(candidate.stream) + candidate.copies.map { it.stream } }
            .take(1 + MAX_CHAIN)
        return AutoStreamResult(candidates = candidates, rejected = rejected, chain = chain)
    }

    /** The hard rules, in their order: right content, playable, a real release, the size, the audio, the picture. */
    fun rejection(scored: ScoredSource, request: SmartSourceRequest, config: AutoStreamConfig): AutoStreamReject? {
        if (scored.rejection in WRONG_CONTENT) return AutoStreamReject.WRONG_CONTENT
        val source = scored.source
        val release = scored.release
        if (!source.isPlayable) return AutoStreamReject.NOT_PLAYABLE
        if (source.cache == CacheState.UNCACHED) return AutoStreamReject.NOT_CACHED
        if (release.source.isLowQuality) return AutoStreamReject.LOW_QUALITY_SOURCE
        val size = if (release.sizeIsPack && request.isEpisode) null else release.sizeBytes
        if (size == null) return AutoStreamReject.SIZE_UNKNOWN
        if (size >= config.maxFileSizeBytes) return AutoStreamReject.TOO_LARGE
        when {
            release.channels in config.allowedChannels -> Unit
            release.channels != AudioChannels.UNKNOWN -> return AutoStreamReject.AUDIO_NOT_ALLOWED
            !config.allowUnverifiedAudio -> return AutoStreamReject.AUDIO_UNVERIFIED
        }
        if (release.resolution.isKnown && release.resolution.level < config.minResolution.level) {
            return AutoStreamReject.LOW_RESOLUTION
        }
        if (release.is3d) return AutoStreamReject.THREE_D
        return null
    }

    /**
     * Whether the connection plays a stream needing [requiredMbps] smoothly: what plays have shown
     * (its speed, the bitrate above which they buffered), else the kind of network.
     */
    fun fitsConnection(requiredMbps: Double, context: SmartRankingContext): Boolean {
        if (!context.config.connectionAware) return true
        val connection = context.connection
        connection.learnedMaxMbps?.let { if (requiredMbps > it) return false }
        connection.availableMbps?.let { return requiredMbps * CONNECTION_MARGIN <= it }
        return when (connection.profile) {
            ConnectionProfile.FAST, ConnectionProfile.UNKNOWN -> true
            ConnectionProfile.MEDIUM -> requiredMbps <= MEDIUM_MAX_MBPS
            ConnectionProfile.SLOW -> requiredMbps <= SLOW_MAX_MBPS
        }
    }

    private fun score(scored: ScoredSource, fits: Boolean, context: SmartRankingContext): Int {
        val release = scored.release
        val source = scored.source
        val config = context.config
        val device = context.device
        var score = when (release.resolution) {
            VideoResolution.UHD_2160 -> 100
            VideoResolution.QHD_1440 -> 88
            VideoResolution.FHD_1080 -> 78
            VideoResolution.HD_720 -> 55
            VideoResolution.SD -> 30
            VideoResolution.LOW -> 0
            VideoResolution.UNKNOWN -> 25
        }
        // A picture the connection won't keep up with falls below the next one down that it will.
        if (!fits) score -= 45
        score += when (release.channels) {
            AudioChannels.CH_5_1 -> 12
            AudioChannels.CH_2_0 -> 0
            else -> -8
        }
        val hdr10 = release.hdr.any { it == HdrFormat.HDR10 || it == HdrFormat.HDR10_PLUS || it == HdrFormat.HDR }
        val dolbyVisionOnly = HdrFormat.DOLBY_VISION in release.hdr && !hdr10
        when {
            dolbyVisionOnly && device.isKnown && !device.supportsDolbyVision -> score -= 10
            config.preferHdr && release.hdr.any { it != HdrFormat.SDR } && (!device.isKnown || device.supportsHdr10) -> score += 3
        }
        score += when (release.codec) {
            VideoCodec.HEVC -> if (device.hasHardwareHevc) 2 else 0
            VideoCodec.AV1 -> if (device.isKnown && !device.hasHardwareAv1) -15 else 1
            VideoCodec.LEGACY -> -10
            else -> 0
        }
        if (release.imax && config.preferImax) score += 2
        score += when (release.source) {
            ReleaseSource.REMUX, ReleaseSource.BLURAY, ReleaseSource.WEB_DL -> 2
            ReleaseSource.HDTV -> -3
            ReleaseSource.DVD -> -5
            else -> 0
        }
        // Right content, surely (a release that says its title and year over one that says nothing).
        score += ((scored.breakdown.metadata - 0.8f) * 20f).toInt()
        // Starts fast and reliably: ready on the debrid service, a provider that answers and plays.
        score += when {
            source.cache == CacheState.CACHED -> 6
            source.isPeerToPeer -> -25
            source.stream.getStreamUrl() != null -> 2
            else -> 0
        }
        context.providerStats[source.provider.key]?.takeIf { it.samples >= 1.0 }?.let { stats ->
            score += ((stats.successRate - 0.5) * 10).toInt()
            stats.averageResponseMs?.let { ms ->
                if (ms <= 1_500) score += 3 else if (ms >= 6_000) score -= 3
            }
        }
        if (SourceProviderAdapters.releaseKey(source) in context.failedReleaseKeys) score -= 40
        // A little more of the size allowed is a little more picture (never more than the rest).
        release.sizeGb?.let { gb -> score += ((gb / config.autoStream.maxFileSizeGb).coerceIn(0.0, 1.0) * 4).toInt() }
        return score
    }

    /** "4K · 5.7 GB · 5.1 · HDR10 · IMAX": what will play (never where from). */
    fun summary(release: ParsedRelease): String = buildList {
        if (release.resolution.isKnown) add(release.resolution.label)
        release.sizeBytes?.takeIf { !release.sizeIsPack }?.let { add(formatSize(it)) }
        if (release.channels != AudioChannels.UNKNOWN) add(release.channels.label)
        when {
            HdrFormat.DOLBY_VISION in release.hdr -> add(HdrFormat.DOLBY_VISION.label)
            HdrFormat.HDR10_PLUS in release.hdr -> add(HdrFormat.HDR10_PLUS.label)
            HdrFormat.HDR10 in release.hdr -> add(HdrFormat.HDR10.label)
            HdrFormat.HDR in release.hdr -> add(HdrFormat.HDR.label)
        }
        if (release.imax) add("IMAX")
    }.joinToString(" · ")

    private fun formatSize(bytes: Long): String {
        val gb = bytes / ParsedRelease.BYTES_PER_GB
        return if (gb >= 1.0) String.format(Locale.US, "%.1f GB", gb) else String.format(Locale.US, "%.0f MB", bytes / 1_048_576.0)
    }

    private val WRONG_CONTENT = setOf(
        RejectReason.WRONG_TITLE,
        RejectReason.WRONG_YEAR,
        RejectReason.WRONG_TYPE,
        RejectReason.WRONG_SEASON,
        RejectReason.WRONG_EPISODE
    )

    /**
     * Verified ones first (audio and picture known); among them, what the connection keeps up
     * with before what it doesn't (a 480p that plays beats a 4K that buffers); then the score.
     */
    private val ORDER: Comparator<AutoStreamCandidate> =
        compareBy<AutoStreamCandidate> { if (it.verified) 0 else 1 }
            .thenBy { if (it.fitsConnection) 0 else 1 }
            .thenByDescending { it.score }
            .thenByDescending { it.scored.breakdown.playbackReliability }

    /** How many sources at most are lined up after the first. */
    private const val MAX_CHAIN = 15

    /** A measured speed must be this much above what a stream needs (the network's ups and downs). */
    private const val CONNECTION_MARGIN = 1.2
    private const val MEDIUM_MAX_MBPS = 15.0
    private const val SLOW_MAX_MBPS = 6.0
}
