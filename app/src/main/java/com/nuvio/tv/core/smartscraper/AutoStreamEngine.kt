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
    LOW_RESOLUTION("Below the lowest resolution allowed"),
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
    val copies: List<ScoredSource> = emptyList(),
    /** Its audio is in the language wanted (or none is wanted). */
    val languageMatch: Boolean = true,
    /**
     * The TV shows its picture as meant: no HDR on a TV without HDR, no Dolby Vision-only file on
     * one without Dolby Vision, a codec it decodes in hardware.
     */
    val tvCompatible: Boolean = true,
    /** Its place in the resolution order for this TV (higher first): see [AutoStreamEngine.tier]. */
    val tier: Int = 0,
    /** Outside Auto Stream's limits (size, audio): chosen only because nothing within them exists. */
    val relaxed: Boolean = false
) {
    val stream: Stream get() = scored.stream
    val release: ParsedRelease get() = scored.release
}

data class AutoStreamResult(
    /** What Auto Stream may play, best first, each release once. */
    val candidates: List<AutoStreamCandidate>,
    val rejected: List<Pair<ScoredSource, AutoStreamReject>>,
    /** What to play, then what to try next when it fails, in order (never another title or episode). */
    val chain: List<Stream>,
    /** Nothing was within Auto Stream's limits: these are the best of the rest (never a list to choose from). */
    val relaxed: Boolean = false
) {
    val best: AutoStreamCandidate? get() = candidates.firstOrNull()

    /**
     * The best is already all it can be (the TV's own resolution, in the language wanted, smooth on
     * this connection, ready at once): no need to wait for slower addons.
     */
    fun bestIsIdeal(context: SmartRankingContext): Boolean {
        val best = best ?: return false
        return !relaxed && best.languageMatch && best.fitsConnection && best.tvCompatible && best.verified &&
            best.scored.source.cache == CacheState.CACHED && best.tier >= AutoStreamEngine.tvTier(context.device)
    }

    companion object {
        val EMPTY = AutoStreamResult(emptyList(), emptyList(), emptyList())
    }
}

/**
 * Auto Stream's choice, made alone (no list, ever): the right title, year, season and episode, in
 * the language wanted; then strictly the highest resolution this TV shows and this connection
 * plays smoothly: 4K → 2K → 1080p → 720p → 480p → 360p (720p only when there's no 1080p, and so
 * on). The TV is read from the HDMI: no 4K HDR on a TV without HDR (a 1080p, or a 4K without HDR,
 * instead), Dolby Vision only where it shows. Within Auto Stream's limits (under 6 GB, 5.1 or
 * stereo) when anything is; else the best of the rest. Among equals: 5.1 over stereo, then what
 * starts fastest and most reliably.
 */
object AutoStreamEngine {

    fun rank(
        sources: List<NormalizedSource>,
        request: SmartSourceRequest,
        context: SmartRankingContext = SmartRankingContext.DEFAULT
    ): AutoStreamResult {
        if (sources.isEmpty()) return AutoStreamResult.EMPTY
        val config = context.config.autoStream
        val judged = sources.map { SmartScoreEngine.judge(it, request, context) }
        val rejected = ArrayList<Pair<ScoredSource, AutoStreamReject>>()
        val strict = judged.mapNotNull { scored ->
            val reject = rejection(scored, request, config)
            if (reject != null) {
                rejected += scored to reject
                null
            } else {
                candidate(scored, request, context, relaxed = false)
            }
        }
        // Nothing within the limits: the best of the rest still plays (the right content, ready to play).
        val relaxed = strict.isEmpty()
        val pool = if (!relaxed) strict else judged.filter(::playableAtAll).map { candidate(it, request, context, relaxed = true) }
        val ordered = pool.sortedWith(ORDER)
        // The same file from several addons/services: its fastest, most reliable copy first.
        val groups = LinkedHashMap<String, MutableList<AutoStreamCandidate>>()
        ordered.forEach { groups.getOrPut(SourceProviderAdapters.releaseKey(it.scored.source)) { mutableListOf() }.add(it) }
        val candidates = groups.values.map { members -> members.first().copy(copies = members.drop(1).map { it.scored }) }
        val chain = candidates
            .flatMap { candidate -> listOf(candidate.stream) + candidate.copies.map { it.stream } }
            .take(1 + MAX_CHAIN)
        return AutoStreamResult(candidates = candidates, rejected = rejected, chain = chain, relaxed = relaxed && candidates.isNotEmpty())
    }

    private fun candidate(
        scored: ScoredSource,
        request: SmartSourceRequest,
        context: SmartRankingContext,
        relaxed: Boolean
    ): AutoStreamCandidate {
        val fits = fitsConnection(requiredMbps(scored, request), context)
        val compatible = tvCompatible(scored.release, context.device)
        return AutoStreamCandidate(
            scored = scored,
            score = score(scored, compatible, context),
            verified = scored.release.channels in context.config.autoStream.allowedChannels,
            fitsConnection = fits,
            languageMatch = request.preferredLanguage == null || request.preferredLanguage in scored.audioLanguages,
            tvCompatible = compatible,
            tier = tier(scored.release.resolution, context.device),
            relaxed = relaxed
        )
    }

    /** The right content and playable right away (whatever its size or audio). */
    private fun playableAtAll(scored: ScoredSource): Boolean {
        val source = scored.source
        return scored.rejection !in WRONG_CONTENT && source.isPlayable && source.cache != CacheState.UNCACHED &&
            !scored.release.source.isLowQuality && !scored.release.is3d
    }

    /**
     * Its place in the resolution order for [device] (higher first): up to the TV's own resolution
     * 4K 60 → 2K 50 → 1080p 40 → 720p 30 → 480p 20 → 360p 10 → unknown 0. Above the TV's own (a 4K
     * on a 1080p TV) it comes right after the TV's own resolution, before the next one down.
     */
    fun tier(resolution: VideoResolution, device: DeviceProfile): Int {
        val level = resolution.level
        if (level < 0) return 0
        val cap = tvMaxLevel(device)
        return if (level <= cap) (level + 1) * 10 else (cap + 1) * 10 - (level - cap)
    }

    /** The tier of the TV's own resolution (what a file needs to be all the TV shows). */
    fun tvTier(device: DeviceProfile): Int = (tvMaxLevel(device) + 1) * 10

    private fun tvMaxLevel(device: DeviceProfile): Int =
        if (device.isKnown && device.maxResolution.isKnown) device.maxResolution.level else VideoResolution.UHD_2160.level

    /**
     * Whether the TV shows the file as meant: a TV without HDR gets no HDR (it would look washed
     * out), one without Dolby Vision no Dolby Vision-only file (wrong colors); a codec it can't
     * decode in hardware stutters. Unknown TV: anything.
     */
    fun tvCompatible(release: ParsedRelease, device: DeviceProfile): Boolean {
        if (!device.isKnown) return true
        when (release.codec) {
            VideoCodec.AV1 -> if (!device.hasHardwareAv1) return false
            VideoCodec.HEVC -> if (!device.hasHardwareHevc) return false
            else -> Unit
        }
        val hdr = release.hdr - HdrFormat.SDR
        if (hdr.isEmpty()) return true
        val dolbyVision = HdrFormat.DOLBY_VISION in hdr
        val hdr10 = hdr.any { it == HdrFormat.HDR10 || it == HdrFormat.HDR10_PLUS || it == HdrFormat.HDR }
        val hlg = HdrFormat.HLG in hdr
        return (dolbyVision && device.supportsDolbyVision) ||
            (hdr10 && device.supportsHdr10) ||
            (hlg && device.supportsHlg)
    }

    /**
     * What the file needs from the connection (Mbps, with headroom): from its size and the title's
     * length; when the length isn't known, a movie's or an episode's usual one.
     */
    fun requiredMbps(scored: ScoredSource, request: SmartSourceRequest): Double {
        scored.bitrateMbps?.let { return it * SmartScoreEngine.HEADROOM }
        val release = scored.release
        val size = release.sizeBytes?.takeIf { !release.sizeIsPack } ?: return scored.requiredMbps
        val minutes = if (request.isSeries) TYPICAL_EPISODE_MINUTES else TYPICAL_MOVIE_MINUTES
        return size * 8.0 / (minutes * 60.0) / 1_000_000.0 * SmartScoreEngine.HEADROOM
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

    /** Among files of the same resolution: 5.1 over stereo, HDR the TV shows, then speed and reliability. */
    private fun score(scored: ScoredSource, tvCompatible: Boolean, context: SmartRankingContext): Int {
        val release = scored.release
        val source = scored.source
        val config = context.config
        val device = context.device
        var score = 0
        score += when (release.channels) {
            AudioChannels.CH_5_1 -> 12
            AudioChannels.CH_2_0 -> 0
            else -> -8
        }
        if (tvCompatible && config.preferHdr && release.hdr.any { it != HdrFormat.SDR } && device.isKnown) score += 3
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

    /** "4K · 5.7 GB · 5.1 · HDR10 · IMAX · Español": what will play (never where from). */
    fun summary(release: ParsedRelease, language: AudioLanguage? = null): String = buildList {
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
        language?.let { add(it.nativeName) }
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
     * The language wanted first (the other only when none is in it); then what the connection keeps
     * up with (a 720p that plays beats a 4K that buffers) and the TV shows as meant; then strictly
     * the resolution order (4K → 2K → 1080p → 720p → 480p → 360p); then audio said to be 5.1 or
     * stereo; then the score (5.1 over stereo, HDR, speed, reliability).
     */
    private val ORDER: Comparator<AutoStreamCandidate> =
        compareBy<AutoStreamCandidate> { if (it.languageMatch) 0 else 1 }
            .thenBy { if (it.fitsConnection) 0 else 1 }
            .thenBy { if (it.tvCompatible) 0 else 1 }
            .thenByDescending { it.tier }
            .thenBy { if (it.verified) 0 else 1 }
            .thenByDescending { it.score }
            .thenByDescending { it.scored.breakdown.playbackReliability }

    /** How many sources at most are lined up after the first. */
    private const val MAX_CHAIN = 15

    /** A measured speed must be this much above what a stream needs (the network's ups and downs). */
    private const val CONNECTION_MARGIN = 1.2
    private const val MEDIUM_MAX_MBPS = 15.0
    private const val SLOW_MAX_MBPS = 6.0
    private const val TYPICAL_MOVIE_MINUTES = 110
    private const val TYPICAL_EPISODE_MINUTES = 45
}
