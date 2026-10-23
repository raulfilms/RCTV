package com.nuvio.tv.core.smartscraper

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Smart Scraper's ranking: every stream of a title is checked against the title asked for
 * (wrong title, year, season or episode is left out), kept from the smart sources when it breaks a
 * hard rule (under 720p, CAM/TS/TC, over 8 GB, can't play), and given a Smart Score from its
 * metadata, picture, sound, size, the connection, its provider's reliability and how it plays.
 * The same file from several providers is one smart source, its copies kept to fall back on.
 * Nothing here is shown as it is: the UI shows what a source is, never where it comes from.
 */
object SmartScoreEngine {

    fun rank(
        sources: List<NormalizedSource>,
        request: SmartSourceRequest,
        context: SmartRankingContext = SmartRankingContext.DEFAULT
    ): SmartSourcesResult {
        if (sources.isEmpty()) return SmartSourcesResult.EMPTY
        val judged = sources.map { judge(it, request, context) }
        val (rejected, kept) = judged.partition { it.rejection?.removesFromAll == true }
        val ordered = kept.sortedWith(SMART_ORDER)
        val eligible = ordered.filter { it.rejection == null }
        val groups = group(eligible, context.config).take(context.config.maxSmartSources.coerceAtLeast(1))
        val smart = label(groups, context)
        val fallbackChain = smart.flatMap { entry -> listOf(entry.best.stream) + entry.copies.map { it.stream } }
        return SmartSourcesResult(smart = smart, all = ordered, rejected = rejected, fallbackChain = fallbackChain)
    }

    /** One stream checked and scored. */
    fun judge(source: NormalizedSource, request: SmartSourceRequest, context: SmartRankingContext): ScoredSource {
        val config = context.config
        val release = source.release
        val titleMatch = TitleMatcher.match(release.title, request)
        val metadata = checkMetadata(release, titleMatch, request, config)
        val bitrate = bitrateOf(release, request)
        val required = (bitrate ?: defaultMbps(release.resolution)) * HEADROOM
        val rejection = metadata.rejection ?: qualityRejection(source, request, config)
        val breakdown = ScoreBreakdown(
            metadata = metadata.score,
            video = videoScore(release, bitrate, context),
            audio = audioScore(release, config),
            size = sizeScore(release, request, context),
            connection = connectionScore(required, context),
            sourceReliability = sourceReliabilityScore(source, context),
            playbackReliability = playbackReliabilityScore(source, context)
        )
        return ScoredSource(
            source = source,
            titleMatch = titleMatch,
            rejection = rejection,
            score = weighted(breakdown, config.weights),
            breakdown = breakdown,
            bitrateMbps = bitrate,
            requiredMbps = required
        )
    }

    // ==================================================================== Metadata (hard checks first)

    private data class MetadataCheck(val score: Float, val rejection: RejectReason?)

    private fun checkMetadata(
        release: ParsedRelease,
        titleMatch: TitleMatch,
        request: SmartSourceRequest,
        config: SmartScraperConfig
    ): MetadataCheck {
        val title = titleMatch.confidence
        val wantedYear = request.year
        val foundYear = release.year
        if (!request.isSeries) {
            // A movie asked for: an episode or a season isn't it.
            if (release.seasons.isNotEmpty()) return MetadataCheck(0f, RejectReason.WRONG_TYPE)
            val year = when {
                wantedYear == null || foundYear == null -> 0.6f
                foundYear == wantedYear -> 1f
                abs(foundYear - wantedYear) <= config.yearTolerance -> 0.7f
                else -> return MetadataCheck(0f, RejectReason.WRONG_YEAR)
            }
            // Another title of another year was already left out; another title of the same year is
            // the movie under another name (its provider found it by its id), anything else isn't sure.
            if (titleMatch == TitleMatch.MISMATCH && (foundYear == null || wantedYear == null)) {
                return MetadataCheck(0.6f * title + 0.4f * year, RejectReason.WRONG_TITLE)
            }
            return MetadataCheck(0.6f * title + 0.4f * year, null)
        }

        val year = when {
            wantedYear == null || foundYear == null -> 0.8f
            abs(foundYear - wantedYear) <= config.yearTolerance -> 1f
            else -> 0.5f
        }
        if (!request.isEpisode) return MetadataCheck(0.7f * title + 0.3f * year, null)
        val season = request.season!!
        val episode = request.episode!!
        val episodeScore = when {
            release.seasons.isNotEmpty() && release.episodes.isNotEmpty() -> {
                if (season !in release.seasons) return MetadataCheck(0f, RejectReason.WRONG_SEASON)
                if (episode !in release.episodes && release.episodes.none { it in request.equivalentEpisodes }) {
                    return MetadataCheck(0f, RejectReason.WRONG_EPISODE)
                }
                1f
            }
            release.isSeasonPack -> {
                if (release.seasons.isNotEmpty() && season !in release.seasons) {
                    return MetadataCheck(0f, RejectReason.WRONG_SEASON)
                }
                0.75f
            }
            // "Episode 14" with no season (or an absolute number): can't be checked.
            release.episodes.isNotEmpty() -> if (episode in release.episodes) 0.8f else 0.5f
            else -> 0.5f
        }
        val score = 0.35f * title + 0.15f * year + 0.5f * episodeScore
        if (titleMatch == TitleMatch.MISMATCH && episodeScore < 0.75f) {
            return MetadataCheck(score, RejectReason.WRONG_TITLE)
        }
        return MetadataCheck(score, null)
    }

    // ==================================================================== Hard rules (smart sources only)

    private fun qualityRejection(
        source: NormalizedSource,
        request: SmartSourceRequest,
        config: SmartScraperConfig
    ): RejectReason? {
        val release = source.release
        if (!source.isPlayable) return RejectReason.NOT_PLAYABLE
        if (source.cache == CacheState.UNCACHED) return RejectReason.NOT_CACHED
        if (release.source.isLowQuality) return RejectReason.LOW_QUALITY_SOURCE
        if (release.resolution.isKnown && release.resolution.level < config.minResolution.level) {
            return RejectReason.LOW_RESOLUTION
        }
        val maxBytes = config.maxFileSizeBytes ?: return null
        val size = release.sizeBytes ?: return null
        if (size <= maxBytes) return null
        // A whole season's pack is never an episode's source when it's bigger than the limit.
        return if (release.sizeIsPack && request.isEpisode) RejectReason.SEASON_PACK_TOO_LARGE else RejectReason.TOO_LARGE
    }

    // ==================================================================== Picture

    private fun videoScore(release: ParsedRelease, bitrate: Double?, context: SmartRankingContext): Float {
        val config = context.config
        val resolution = resolutionScore(release.resolution, context)
        val hdr = if (config.preferHdr) hdrScore(release.hdr, context.device) else 0.5f
        val codec = codecScore(release.codec, config, context.device)
        val source = sourceScore(release.source)
        val bitrateScore = bitrateScore(bitrate, release)
        var video = 0.55f * resolution + 0.15f * hdr + 0.10f * codec + 0.12f * source + 0.08f * bitrateScore
        if (release.imax && config.preferImax) video += 0.06f
        if (release.is3d) video -= 0.25f
        return video.coerceIn(0f, 1f)
    }

    /** The picture wanted: the preferred one, less on a slower connection or a smaller screen. */
    fun targetResolution(context: SmartRankingContext): VideoResolution {
        val config = context.config
        var target = config.preferredResolution.level
        if (config.connectionAware) {
            when (context.connection.profile) {
                ConnectionProfile.MEDIUM -> target = minOf(target, VideoResolution.FHD_1080.level)
                ConnectionProfile.SLOW -> target = minOf(target, VideoResolution.HD_720.level)
                else -> Unit
            }
        }
        if (context.device.isKnown && context.device.maxResolution.isKnown) {
            target = minOf(target, context.device.maxResolution.level)
        }
        return VideoResolution.ofLevel(target.coerceAtLeast(VideoResolution.HD_720.level))
    }

    private fun resolutionScore(resolution: VideoResolution, context: SmartRankingContext): Float {
        if (!resolution.isKnown) return 0.35f
        val difference = resolution.level - targetResolution(context).level
        return when {
            difference == 0 -> 1f
            difference < 0 -> BELOW_TARGET[minOf(-difference - 1, BELOW_TARGET.lastIndex)]
            else -> ABOVE_TARGET[minOf(difference - 1, ABOVE_TARGET.lastIndex)]
        }
    }

    /** Dolby Vision counts where the TV shows it; where it doesn't, a file with HDR10 too is the same as HDR10. */
    private fun hdrScore(hdr: Set<HdrFormat>, device: DeviceProfile): Float {
        if (hdr.isEmpty()) return 0.45f
        if (hdr == setOf(HdrFormat.SDR)) return if (device.isKnown && !device.supportsHdr10) 0.75f else 0.5f
        val dolbyVision = HdrFormat.DOLBY_VISION in hdr
        val hdr10Plus = HdrFormat.HDR10_PLUS in hdr
        val hdr10 = HdrFormat.HDR10 in hdr || HdrFormat.HDR in hdr || hdr10Plus
        val hdrDevice = !device.isKnown || device.supportsHdr10
        return when {
            dolbyVision && (device.supportsDolbyVision) -> 1f
            dolbyVision && !device.isKnown -> 0.92f
            dolbyVision && hdr10 -> if (hdrDevice) 0.88f else 0.4f
            dolbyVision -> 0.25f
            hdr10Plus -> if (device.supportsHdr10Plus) 0.95f else if (hdrDevice) 0.9f else 0.4f
            hdr10 -> if (hdrDevice) 0.9f else 0.4f
            HdrFormat.HLG in hdr -> if (!device.isKnown || device.supportsHlg) 0.8f else 0.4f
            else -> 0.45f
        }
    }

    private fun codecScore(codec: VideoCodec, config: SmartScraperConfig, device: DeviceProfile): Float = when (codec) {
        VideoCodec.HEVC -> if (config.preferHevc && device.hasHardwareHevc) 1f else 0.8f
        VideoCodec.AV1 -> when {
            device.hasHardwareAv1 -> 0.95f
            !device.isKnown -> 0.7f
            else -> 0.25f
        }
        VideoCodec.AVC -> 0.75f
        VideoCodec.VP9 -> 0.7f
        VideoCodec.LEGACY -> 0.2f
        VideoCodec.UNKNOWN -> 0.5f
    }

    private fun sourceScore(source: ReleaseSource): Float = when (source) {
        ReleaseSource.REMUX -> 1f
        ReleaseSource.BLURAY -> 0.95f
        ReleaseSource.WEB_DL -> 0.9f
        ReleaseSource.WEBRIP -> 0.75f
        ReleaseSource.HDTV -> 0.55f
        ReleaseSource.DVD -> 0.35f
        ReleaseSource.UNKNOWN -> 0.5f
        else -> 0f
    }

    /** A file's bitrate (Mbps) from its size and the title's length; null when either is unknown. */
    fun bitrateOf(release: ParsedRelease, request: SmartSourceRequest): Double? {
        val minutes = request.runtimeMinutes?.takeIf { it > 0 } ?: return null
        if (release.sizeIsPack) return null
        val bytes = release.sizeBytes ?: return null
        return bytes * 8.0 / (minutes * 60.0) / 1_000_000.0
    }

    /** A good bitrate for the picture: below it a file of that resolution looks worse. */
    private fun bitrateScore(bitrate: Double?, release: ParsedRelease): Float {
        bitrate ?: return 0.5f
        var reference = when (release.resolution) {
            VideoResolution.UHD_2160 -> 14.0
            VideoResolution.QHD_1440 -> 9.0
            VideoResolution.FHD_1080 -> 5.0
            VideoResolution.HD_720 -> 2.5
            VideoResolution.SD -> 1.2
            VideoResolution.UNKNOWN -> 5.0
        }
        when (release.codec) {
            VideoCodec.AVC, VideoCodec.LEGACY -> reference *= 1.6
            VideoCodec.AV1 -> reference *= 0.8
            else -> Unit
        }
        return (bitrate / reference).toFloat().coerceIn(0.15f, 1f)
    }

    /** What a file of this picture usually needs when its bitrate isn't known (Mbps). */
    private fun defaultMbps(resolution: VideoResolution): Double = when (resolution) {
        VideoResolution.UHD_2160 -> 20.0
        VideoResolution.QHD_1440 -> 12.0
        VideoResolution.FHD_1080 -> 8.0
        VideoResolution.HD_720 -> 4.0
        VideoResolution.SD -> 2.0
        VideoResolution.UNKNOWN -> 8.0
    }

    // ==================================================================== Sound

    /** Atmos > TrueHD > DTS:X > DTS-HD MA > DDP > DTS > DD, then 7.1 > 5.1 > stereo. */
    private fun audioScore(release: ParsedRelease, config: SmartScraperConfig): Float {
        val codecBase = when (release.audioCodec) {
            AudioCodec.TRUEHD -> 0.9f
            AudioCodec.DTS_X -> 0.88f
            AudioCodec.DTS_HD_MA -> 0.84f
            AudioCodec.DTS_HD -> 0.78f
            AudioCodec.DDP -> 0.74f
            AudioCodec.LPCM -> 0.7f
            AudioCodec.FLAC -> 0.68f
            AudioCodec.DTS -> 0.66f
            AudioCodec.DD -> 0.6f
            AudioCodec.AAC, AudioCodec.OPUS -> 0.42f
            AudioCodec.MP3 -> 0.3f
            AudioCodec.UNKNOWN -> null
        }
        val channels = release.channels
        var audio = when {
            release.atmos && config.preferredAudio == PreferredAudio.ATMOS -> 1f
            release.atmos -> maxOf(codecBase ?: 0.74f, 0.8f)
            codecBase != null -> codecBase
            else -> when (channels) {
                AudioChannels.CH_7_1 -> 0.56f
                AudioChannels.CH_5_1 -> 0.5f
                AudioChannels.CH_2_0 -> 0.3f
                AudioChannels.CH_1_0 -> 0.2f
                AudioChannels.UNKNOWN -> 0.35f
            }
        }
        if (codecBase != null || release.atmos) {
            audio += when (channels) {
                AudioChannels.CH_7_1 -> 0.06f
                AudioChannels.CH_5_1 -> 0.03f
                AudioChannels.CH_2_0 -> -0.15f
                AudioChannels.CH_1_0 -> -0.25f
                AudioChannels.UNKNOWN -> 0f
            }
        }
        if (config.preferredAudio == PreferredAudio.SURROUND && channels.count >= AudioChannels.CH_5_1.count) audio += 0.05f
        if (config.preferredAudio == PreferredAudio.ANY) audio = 0.5f + (audio - 0.5f) * 0.4f
        if (channels != AudioChannels.UNKNOWN && channels.count < config.minAudioChannels.count) audio *= 0.7f
        return audio.coerceIn(0f, 1f)
    }

    // ==================================================================== Size

    /**
     * 6–8 GB is best for a movie (4–6 high, 2–4 moderate, under 2 lower); an episode's sizes are
     * in proportion to its length. Never "the smallest" or "the biggest": the most practical.
     */
    private fun sizeScore(release: ParsedRelease, request: SmartSourceRequest, context: SmartRankingContext): Float {
        val config = context.config
        val bytes = if (release.sizeIsPack && request.isEpisode) null else release.sizeBytes
        bytes ?: return 0.35f
        val gb = bytes / ParsedRelease.BYTES_PER_GB
        val scale = if (request.isSeries) {
            ((request.runtimeMinutes ?: SmartScraperConfig.DEFAULT_EPISODE_MINUTES).toDouble() /
                SmartScraperConfig.REFERENCE_RUNTIME_MINUTES).coerceIn(0.15, 1.0)
        } else {
            1.0
        }
        val preferredMin = config.preferredSizeMinGb * scale
        val preferredMax = config.preferredSizeMaxGb * scale
        val profile = if (config.connectionAware) context.connection.profile else ConnectionProfile.UNKNOWN
        return when (profile) {
            ConnectionProfile.SLOW -> when {
                gb <= 2 * scale -> 0.65f
                gb <= 4 * scale -> 1f
                gb <= 6 * scale -> 0.8f
                gb <= preferredMax -> 0.55f
                else -> 0.4f
            }
            ConnectionProfile.MEDIUM -> when {
                gb < 2 * scale -> 0.3f
                gb < 4 * scale -> 0.6f
                gb <= preferredMax -> 1f
                else -> 0.75f
            }
            else -> when {
                gb < 2 * scale -> 0.3f
                gb < 4 * scale -> 0.55f
                gb < preferredMin -> 0.8f
                gb <= preferredMax -> 1f
                else -> 0.85f
            }
        }
    }

    // ==================================================================== Connection, reliability

    private fun connectionScore(requiredMbps: Double, context: SmartRankingContext): Float {
        if (!context.config.connectionAware) return 0.6f
        val connection = context.connection
        val available = listOfNotNull(connection.availableMbps, connection.learnedMaxMbps).minOrNull()
        if (available != null && available > 0) {
            val ratio = available / requiredMbps
            return when {
                ratio >= 2.0 -> 1f
                ratio >= 1.4 -> 0.85f
                ratio >= 1.0 -> 0.6f
                ratio >= 0.7 -> 0.35f
                else -> 0.1f
            }
        }
        return when (connection.profile) {
            ConnectionProfile.FAST -> if (requiredMbps <= 60) 1f else 0.8f
            ConnectionProfile.MEDIUM -> when {
                requiredMbps <= 12 -> 1f
                requiredMbps <= 20 -> 0.7f
                else -> 0.4f
            }
            ConnectionProfile.SLOW -> when {
                requiredMbps <= 5 -> 1f
                requiredMbps <= 10 -> 0.6f
                else -> 0.25f
            }
            ConnectionProfile.UNKNOWN -> 0.6f
        }
    }

    private fun sourceReliabilityScore(source: NormalizedSource, context: SmartRankingContext): Float {
        val stats = context.providerStats[source.provider.key] ?: return 0.6f
        if (stats.samples < 1.0) return 0.6f
        val speed = stats.averageResponseMs?.let { ms ->
            when {
                ms <= 1500 -> 1.0
                ms >= 10_000 -> 0.2
                else -> 1.0 - (ms - 1500) / 8500 * 0.8
            }
        } ?: 0.6
        return (0.75 * stats.successRate + 0.25 * speed).toFloat().coerceIn(0f, 1f)
    }

    private fun playbackReliabilityScore(source: NormalizedSource, context: SmartRankingContext): Float {
        var score = when {
            !source.isPlayable -> 0f
            source.cache == CacheState.CACHED -> 1f
            source.cache == CacheState.UNCACHED -> 0.1f
            source.stream.debridCacheStatus?.state == com.nuvio.tv.domain.model.StreamDebridCacheState.CHECKING -> 0.5f
            source.isPeerToPeer -> 0.35f
            source.stream.getStreamUrl() != null -> 0.8f
            else -> 0.5f
        }
        if (SourceProviderAdapters.releaseKey(source) in context.failedReleaseKeys) score *= 0.2f
        return score
    }

    private fun weighted(breakdown: ScoreBreakdown, weights: SmartScoreWeights): Int {
        val sum = weights.metadata * breakdown.metadata +
            weights.video * breakdown.video +
            weights.audio * breakdown.audio +
            weights.size * breakdown.size +
            weights.connection * breakdown.connection +
            weights.sourceReliability * breakdown.sourceReliability +
            weights.playbackReliability * breakdown.playbackReliability
        return (sum / weights.total * 100f).roundToInt().coerceIn(0, 100)
    }

    // ==================================================================== Duplicates, labels

    private fun group(sorted: List<ScoredSource>, config: SmartScraperConfig): List<List<ScoredSource>> {
        if (!config.duplicateGrouping) return sorted.map { listOf(it) }
        val byKey = LinkedHashMap<String, MutableList<ScoredSource>>()
        sorted.forEach { byKey.getOrPut(SourceProviderAdapters.releaseKey(it.source)) { mutableListOf() }.add(it) }
        return byKey.values.toList()
    }

    private fun label(groups: List<List<ScoredSource>>, context: SmartRankingContext): List<SmartSourceEntry> {
        if (groups.isEmpty()) return emptyList()
        val bests = groups.map { it.first() }
        val best4k = bests.indexOfFirst { it.release.resolution == VideoResolution.UHD_2160 }
        val bestAudio = bests.indices.maxByOrNull { bests[it].breakdown.audio }
            ?.takeIf { bests[it].breakdown.audio >= 0.74f }
        val lowBandwidth = bests.indexOfFirst {
            it.requiredMbps <= context.config.lowBandwidthMbps && it.release.resolution.level >= VideoResolution.HD_720.level
        }
        return groups.mapIndexed { index, members ->
            val best = members.first()
            val release = best.release
            val labels = buildList {
                if (index == 0) add(SmartLabel.BEST_MATCH)
                if (index == best4k && index != 0) add(SmartLabel.BEST_4K)
                if (index == bestAudio && index != 0) add(SmartLabel.BEST_AUDIO)
                if (index == lowBandwidth && index != 0) add(SmartLabel.LOW_BANDWIDTH)
                if (members.any { isFastSource(it, context) }) add(SmartLabel.FAST_SOURCE)
                addAll(attributeLabels(release))
            }
            SmartSourceEntry(best = best, copies = members.drop(1), labels = labels)
        }
    }

    /** The labels that only tell what a source is (IMAX, Atmos, 7.1, 5.1). */
    fun attributeLabels(release: ParsedRelease): List<SmartLabel> = buildList {
        if (release.imax) add(SmartLabel.IMAX)
        if (release.atmos) add(SmartLabel.ATMOS)
        when (release.channels) {
            AudioChannels.CH_7_1 -> add(SmartLabel.CHANNELS_7_1)
            AudioChannels.CH_5_1 -> add(SmartLabel.CHANNELS_5_1)
            else -> Unit
        }
    }

    /** A copy that's ready at once from a provider that has answered fast and reliably lately. */
    private fun isFastSource(source: ScoredSource, context: SmartRankingContext): Boolean {
        if (source.source.cache != CacheState.CACHED) return false
        val stats = context.providerStats[source.source.provider.key] ?: return false
        val responseMs = stats.averageResponseMs ?: return false
        return stats.samples >= 3 && stats.successRate >= 0.85 && responseMs <= 2_000
    }

    // ==================================================================== Manual mode

    /** The sources that fit the person's filters exactly, in the order chosen. */
    fun applyManual(all: List<ScoredSource>, filters: SmartSourceFilters, sort: SmartSort): List<ScoredSource> {
        val filtered = all.filter { filters.accepts(it) }
        return when (sort) {
            SmartSort.SMART -> filtered.sortedWith(SMART_ORDER)
            SmartSort.RESOLUTION -> filtered.sortedWith(
                compareByDescending<ScoredSource> { it.release.resolution.level }.thenByDescending { it.score }
            )
            SmartSort.AUDIO -> filtered.sortedWith(
                compareByDescending<ScoredSource> { it.breakdown.audio }.thenByDescending { it.score }
            )
            SmartSort.SIZE -> filtered.sortedWith(
                compareByDescending<ScoredSource> { it.release.sizeBytes ?: -1L }.thenByDescending { it.score }
            )
        }
    }

    private val SMART_ORDER: Comparator<ScoredSource> =
        compareByDescending<ScoredSource> { it.score }
            .thenByDescending { it.breakdown.playbackReliability }
            .thenByDescending { it.breakdown.metadata }

    private val BELOW_TARGET = floatArrayOf(0.78f, 0.5f, 0.3f, 0.15f)
    private val ABOVE_TARGET = floatArrayOf(0.9f, 0.72f, 0.45f)

    /** What a stream needs beyond its average bitrate to play smoothly (its peaks, the network's ups and downs). */
    private const val HEADROOM = 1.3
}

/** How the person sorts the sources in manual mode (never by provider: providers aren't shown). */
enum class SmartSort { SMART, RESOLUTION, AUDIO, SIZE }

enum class AudioFilter { ANY, ATMOS, CH_7_1, CH_5_1 }

enum class HdrFilter { ANY, HDR, DOLBY_VISION, SDR }

/** Manual mode's filters: each one exactly as chosen (null / ANY = all). */
data class SmartSourceFilters(
    val resolution: VideoResolution? = null,
    val audio: AudioFilter = AudioFilter.ANY,
    val hdr: HdrFilter = HdrFilter.ANY,
    val codec: VideoCodec? = null,
    val imaxOnly: Boolean = false,
    val maxSizeGb: Double? = null
) {
    val isEmpty: Boolean
        get() = resolution == null && audio == AudioFilter.ANY && hdr == HdrFilter.ANY && codec == null &&
            !imaxOnly && maxSizeGb == null

    fun accepts(source: ScoredSource): Boolean {
        val release = source.release
        if (resolution != null && release.resolution != resolution) return false
        when (audio) {
            AudioFilter.ANY -> Unit
            AudioFilter.ATMOS -> if (!release.atmos) return false
            AudioFilter.CH_7_1 -> if (release.channels != AudioChannels.CH_7_1) return false
            AudioFilter.CH_5_1 -> if (release.channels.count < AudioChannels.CH_5_1.count) return false
        }
        when (hdr) {
            HdrFilter.ANY -> Unit
            HdrFilter.HDR -> if (release.hdr.none { it != HdrFormat.SDR }) return false
            HdrFilter.DOLBY_VISION -> if (HdrFormat.DOLBY_VISION !in release.hdr) return false
            HdrFilter.SDR -> if (release.hdr.any { it != HdrFormat.SDR }) return false
        }
        if (codec != null && release.codec != codec) return false
        if (imaxOnly && !release.imax) return false
        if (maxSizeGb != null) {
            val gb = release.sizeGb ?: return false
            if (gb > maxSizeGb) return false
        }
        return true
    }
}
