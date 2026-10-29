package com.nuvio.tv.core.smartscraper

import com.nuvio.tv.domain.model.Stream
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.ceil

/** Why a stream can't be Auto Stream's choice (spec §3), in the order the rules are checked. */
enum class AutoStreamReject(val description: String) {
    WRONG_CONTENT("Wrong title, year, season or episode"),
    EPISODE_NOT_IDENTIFIED("Its episode isn't identified (a whole season, or an episode with no season)"),
    NOT_PLAYABLE("Not playable here"),
    LOW_QUALITY_SOURCE("CAM/TS/TC/SCR release"),
    SIZE_UNKNOWN("Size not given (it can't be checked under the limit)"),
    TOO_LARGE("Over the Auto Stream size limit"),
    LOW_RESOLUTION("Below the lowest resolution allowed"),
    THREE_D("3D"),
    AUDIO_NOT_ALLOWED("Audio other than 5.1 or 2.0"),
    AUDIO_UNVERIFIED("Audio layout not given"),
    NO_ORIGINAL_AUDIO("No English or original-language audio"),
    NO_SPANISH_AUDIO("No Spanish audio"),
    NOT_CACHED("Not marked ready on the debrid service")
}

/** Which streams a ranking is for. */
enum class AutoStreamPool {
    /** The video that plays: it has English or the title's own audio (§3 rule 6). */
    MAIN,

    /**
     * Streams with Spanish audio. RCTV, until the dual-language engine (Phase 3) adds Spanish to the
     * main video: what plays when Spanish is the language chosen, as Auto Stream v1 did.
     */
    SPANISH
}

/** A release Auto Stream can play, its copies from other addons/services kept to fall back on. */
data class AutoStreamCandidate(
    val scored: ScoredSource,
    val pool: AutoStreamPool = AutoStreamPool.MAIN,
    /** Its place in the resolution order for this TV (higher first): see [AutoStreamEngine.tier]. */
    val tier: Int = 0,
    /** Its audio layout: [AutoStreamEngine.LAYOUT_5_1], [AutoStreamEngine.LAYOUT_2_0] or not said (0). */
    val layout: Int = AutoStreamEngine.LAYOUT_UNKNOWN,
    /** E-AC-3 (Atmos too) 4 > DTS 3 > AC-3 2 > AAC 1 > the rest 0; one this box can't play: -1. */
    val audioCodecRank: Int = 0,
    /** Its average bitrate (Mbps) from its size and the title's length; null when its size isn't known. */
    val bitrateMbps: Double? = null,
    /** What it needs from the connection (Mbps): its bitrate times the headroom. */
    val requiredMbps: Double = 0.0,
    /** The connection keeps up with it. */
    val fitsConnection: Boolean = true,
    /**
     * The TV shows its picture as meant: no HDR on a TV without HDR, no Dolby Vision-only file on
     * one without Dolby Vision, a codec it decodes in hardware.
     */
    val tvCompatible: Boolean = true,
    /** Its picture beyond the bitrate, for this TV: release type, HDR, video codec, IMAX (higher better). */
    val videoPoints: Int = 0,
    /** It already has a Spanish track (no outside audio needed): the tie-breaker among comparable files. */
    val embeddedSpanish: Boolean = false,
    /** Its provider's success rate (0..1; 0.5 while not known; 0 when this very file failed lately). */
    val reliability: Double = 0.5,
    /** Its provider's average answer time (ms), when known. */
    val responseMs: Double? = null,
    /** This very file failed to play lately. */
    val recentlyFailed: Boolean = false,
    /** Its size is known (else it may play only once its link's size is checked: ranked after). */
    val sizeKnown: Boolean = true,
    /** It says it has English or the title's own audio (else that's presumed, and checked before it plays). */
    val originalAudioConfirmed: Boolean = false,
    /** The language wanted is in it (or none is wanted). */
    val languageMatch: Boolean = true,
    /** Its resolution is within what the connection and the TV allow (§5); else it's only a last resort. */
    val tierAllowed: Boolean = true,
    val copies: List<ScoredSource> = emptyList()
) {
    val stream: Stream get() = scored.stream
    val release: ParsedRelease get() = scored.release

    /** Its audio layout (5.1 or 2.0) and resolution are said; unverified ones come after. */
    val verified: Boolean get() = layout != AutoStreamEngine.LAYOUT_UNKNOWN && release.resolution.isKnown

    /** What's checked when it's opened, before its first picture (§3.1, §8.3). */
    val needsVerification: Boolean
        get() = !verified || !sizeKnown || (pool == AutoStreamPool.MAIN && !originalAudioConfirmed)
}

data class AutoStreamResult(
    /** What Auto Stream may play, best first, each release once. */
    val candidates: List<AutoStreamCandidate>,
    val rejected: List<Pair<ScoredSource, AutoStreamReject>>,
    /** What to play, then what to try next when it fails, in order (never another title or episode). */
    val chain: List<Stream>
) {
    val best: AutoStreamCandidate? get() = candidates.firstOrNull()

    /**
     * §8.1: the best needn't wait for slower addons: ready on the debrid service, in the highest
     * resolution this TV and connection allow, 5.1, everything about it said, from a source that
     * starts at least [AutoStreamConfig.earlyDecisionMinReliability] of the time.
     */
    fun bestIsIdeal(context: SmartRankingContext): Boolean {
        val best = best ?: return false
        return best.tierAllowed && best.languageMatch && best.fitsConnection && best.tvCompatible &&
            best.verified && best.sizeKnown && best.scored.source.cache == CacheState.CACHED &&
            best.layout == AutoStreamEngine.LAYOUT_5_1 &&
            best.reliability >= context.config.autoStream.earlyDecisionMinReliability &&
            best.tier >= AutoStreamEngine.highestAllowedTier(context)
    }

    companion object {
        val EMPTY = AutoStreamResult(emptyList(), emptyList(), emptyList())
    }
}

/**
 * Auto Stream v2.0's choice, made alone (no list, ever).
 *
 * Hard rules (§3), any failed and the stream is out: the right title, year, season and episode;
 * playable; never CAM/TS/TC/SCR; at most 8.00 GB (the episode's file, never a whole season's); 2160p
 * to 480p; 5.1 or 2.0 (one whose layout isn't said may play, after the rest); English or the title's
 * own audio (a Spanish-only file is never the video); marked ready on the debrid service. Nothing is
 * ever relaxed: when nothing passes, there's no automatic stream.
 *
 * Ranking (§4, §5): the resolution first (a resolution whose best file the connection can't keep up
 * with, or the TV can't show as meant, steps down to the next); then 5.1 over 2.0; then the audio
 * format (E-AC-3 > DTS > AC-3 > AAC, one this box can't play last); then the picture: bitrate (files
 * within [AutoStreamConfig.comparableBitrateMargin] of each other are the same picture), release
 * type, HDR, codec, IMAX; then among comparable files one that already has Spanish, then the most
 * reliable source, then the fastest. Never the largest or the smallest for being so: the size is a
 * ceiling and a sign of the bitrate only.
 */
object AutoStreamEngine {

    const val LAYOUT_5_1 = 2
    const val LAYOUT_2_0 = 1
    const val LAYOUT_UNKNOWN = 0

    /**
     * The title's streams ranked for Auto Stream. [canVerifySize]: a file whose size isn't given may
     * be a candidate (its link's size is checked before it plays, §3.1); false until that check exists.
     */
    fun rank(
        sources: List<NormalizedSource>,
        request: SmartSourceRequest,
        context: SmartRankingContext = SmartRankingContext.DEFAULT,
        canVerifySize: Boolean = false
    ): AutoStreamResult {
        if (sources.isEmpty()) return AutoStreamResult.EMPTY
        val judged = sources.map { SmartScoreEngine.judge(it, request, context) }
        val main = rankJudged(judged, request, context, AutoStreamPool.MAIN, canVerifySize)
        if (!spanishFirst(request)) return main
        // RCTV (until Phase 3): Spanish chosen for a title in another language: a Spanish stream
        // first, then the main ones.
        val spanish = rankJudged(judged, request, context, AutoStreamPool.SPANISH, canVerifySize)
        if (spanish.candidates.isEmpty()) return main
        val spanishKeys = spanish.candidates
            .flatMap { listOf(it.scored) + it.copies }
            .mapTo(HashSet()) { SourceProviderAdapters.releaseKey(it.source) }
        val candidates = spanish.candidates +
            main.candidates.filter { SourceProviderAdapters.releaseKey(it.scored.source) !in spanishKeys }
        return AutoStreamResult(candidates, main.rejected, chainOf(candidates, context.config.autoStream))
    }

    /** One pool's ranking alone (the rules and the order, nothing before or after it). */
    fun rankPool(
        sources: List<NormalizedSource>,
        request: SmartSourceRequest,
        context: SmartRankingContext,
        pool: AutoStreamPool,
        canVerifySize: Boolean = false
    ): AutoStreamResult {
        if (sources.isEmpty()) return AutoStreamResult.EMPTY
        return rankJudged(sources.map { SmartScoreEngine.judge(it, request, context) }, request, context, pool, canVerifySize)
    }

    /**
     * §8.3: the audio languages the video must have one of, checked with its tracks before the first
     * picture: English, the title's own ([originalLanguageCode], any form), and Spanish when it's the
     * language chosen (RCTV, until Phase 3: a Spanish stream may play then).
     */
    fun wantedAudioLanguages(originalLanguageCode: String?, preferred: AudioLanguage?): Set<String> = buildSet {
        add(AudioLanguage.ENGLISH.code)
        originalLanguageCode?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
        if (preferred == AudioLanguage.SPANISH) add(AudioLanguage.SPANISH.code)
    }

    /** RCTV (until Phase 3): Spanish is the language chosen and the title's own isn't. */
    fun spanishFirst(request: SmartSourceRequest): Boolean =
        request.preferredLanguage == AudioLanguage.SPANISH && request.originalLanguage != AudioLanguage.SPANISH

    private fun rankJudged(
        judged: List<ScoredSource>,
        request: SmartSourceRequest,
        context: SmartRankingContext,
        pool: AutoStreamPool,
        canVerifySize: Boolean
    ): AutoStreamResult {
        val config = context.config.autoStream
        val rejected = ArrayList<Pair<ScoredSource, AutoStreamReject>>()
        val valid = judged.mapNotNull { scored ->
            val reject = rejection(scored, request, config, pool, canVerifySize)
            if (reject != null) {
                rejected += scored to reject
                null
            } else {
                candidate(scored, request, context, pool)
            }
        }
        val ordered = order(valid, config)
        // §9: the same file from several addons/services is one choice; its most reliable, fastest
        // copy first (the order already put it first), the others to fall back on.
        val groups = LinkedHashMap<String, MutableList<AutoStreamCandidate>>()
        ordered.forEach { groups.getOrPut(SourceProviderAdapters.releaseKey(it.scored.source)) { mutableListOf() }.add(it) }
        val candidates = groups.values.map { members -> members.first().copy(copies = members.drop(1).map { it.scored }) }
        return AutoStreamResult(candidates = candidates, rejected = rejected, chain = chainOf(candidates, config))
    }

    private fun chainOf(candidates: List<AutoStreamCandidate>, config: AutoStreamConfig): List<Stream> =
        candidates
            .flatMap { candidate -> listOf(candidate.stream) + candidate.copies.map { it.stream } }
            .take(1 + config.maxChain)

    // ==================================================================== Hard rules (§3)

    /** The hard rules, in their order; null: none failed. */
    fun rejection(
        scored: ScoredSource,
        request: SmartSourceRequest,
        config: AutoStreamConfig,
        pool: AutoStreamPool = AutoStreamPool.MAIN,
        canVerifySize: Boolean = false
    ): AutoStreamReject? {
        if (scored.rejection in WRONG_CONTENT) return AutoStreamReject.WRONG_CONTENT
        val source = scored.source
        val release = scored.release
        if (request.isEpisode && !episodeIdentified(source)) return AutoStreamReject.EPISODE_NOT_IDENTIFIED
        if (!source.isPlayable) return AutoStreamReject.NOT_PLAYABLE
        if (release.source.isLowQuality) return AutoStreamReject.LOW_QUALITY_SOURCE
        val size = fileSize(release, request)
        if (size == null) {
            if (!canVerifySize) return AutoStreamReject.SIZE_UNKNOWN
        } else if (size > config.maxFileSizeBytes) {
            return AutoStreamReject.TOO_LARGE
        }
        if (release.resolution.isKnown && release.resolution !in config.resolutions) return AutoStreamReject.LOW_RESOLUTION
        if (release.is3d) return AutoStreamReject.THREE_D
        when {
            release.channels in config.allowedChannels -> Unit
            release.channels != AudioChannels.UNKNOWN -> return AutoStreamReject.AUDIO_NOT_ALLOWED
            !config.allowUnverifiedAudio -> return AutoStreamReject.AUDIO_UNVERIFIED
        }
        val said = saidLanguages(source)
        when (pool) {
            AutoStreamPool.MAIN -> if (!mayHaveOriginalAudio(said, release, request)) return AutoStreamReject.NO_ORIGINAL_AUDIO
            AutoStreamPool.SPANISH -> if (AudioLanguage.SPANISH !in said) return AutoStreamReject.NO_SPANISH_AUDIO
        }
        when (source.cache) {
            CacheState.CACHED -> Unit
            CacheState.UNCACHED -> return AutoStreamReject.NOT_CACHED
            // RCTV: the Spanish streams keep v1's rule (only a file said not to be ready is out) until
            // the dual-language engine checks them itself (Phase 3).
            CacheState.UNKNOWN -> if (config.requireCached && pool == AutoStreamPool.MAIN) return AutoStreamReject.NOT_CACHED
        }
        return null
    }

    /**
     * §3 rule 2 for an episode (its season and episode, when said, were already checked against the
     * one asked for): a whole season's pack only when its addon points to one file in it; an episode
     * number with no season isn't this exact episode. A file that says nothing of either is the
     * addon's answer for this episode's id.
     */
    private fun episodeIdentified(source: NormalizedSource): Boolean {
        val release = source.release
        if (release.seasons.isNotEmpty() && release.episodes.isNotEmpty()) return true
        if (release.isSeasonPack) return source.fileIdx != null
        return release.episodes.isEmpty()
    }

    /** The file's own size: a whole season's pack size isn't an episode's (unknown then). */
    private fun fileSize(release: ParsedRelease, request: SmartSourceRequest): Long? =
        if (release.sizeIsPack && request.isSeries) null else release.sizeBytes

    /** The audio languages the release or its provider says it has (empty: it says none). */
    private fun saidLanguages(source: NormalizedSource): Set<AudioLanguage> =
        source.release.audioLanguages + source.providerLanguages

    /**
     * §3 rule 6: it has English or the title's own audio. One that says no language is in the
     * title's own (an untagged release is); "Dual"/"Multi" has it among its tracks. Both are
     * checked before the first picture (Phase 2).
     */
    private fun mayHaveOriginalAudio(said: Set<AudioLanguage>, release: ParsedRelease, request: SmartSourceRequest): Boolean {
        if (said.isEmpty() || AudioLanguage.ENGLISH in said) return true
        val original = request.originalLanguage
        if (original != null && original in said) return true
        return MULTI_LABEL in release.languages
    }

    // ==================================================================== Ranking (§4, §5)

    private fun candidate(
        scored: ScoredSource,
        request: SmartSourceRequest,
        context: SmartRankingContext,
        pool: AutoStreamPool
    ): AutoStreamCandidate {
        val config = context.config.autoStream
        val release = scored.release
        val source = scored.source
        val device = context.device
        val compatible = tvCompatible(release, device)
        val bitrate = bitrateMbps(scored, request, config)
        val estimate = bitrate ?: SmartScoreEngine.defaultMbps(release.resolution)
        val stats = context.providerStats[source.provider.key]?.takeIf { it.samples >= 1.0 }
        val failed = SourceProviderAdapters.releaseKey(source) in context.failedReleaseKeys
        val said = saidLanguages(source)
        val original = request.originalLanguage
        return AutoStreamCandidate(
            scored = scored,
            pool = pool,
            tier = tier(release.resolution, device),
            layout = layoutOf(release.channels),
            audioCodecRank = audioCodecRank(release.audioCodec, device),
            bitrateMbps = bitrate,
            requiredMbps = estimate * config.bitrateHeadroom,
            fitsConnection = fitsConnection(estimate, context),
            tvCompatible = compatible,
            videoPoints = videoPoints(release, compatible, context),
            embeddedSpanish = AudioLanguage.SPANISH in said,
            reliability = if (failed) 0.0 else stats?.successRate ?: ProviderStats.NONE.successRate,
            responseMs = stats?.averageResponseMs,
            recentlyFailed = failed,
            sizeKnown = fileSize(release, request) != null,
            originalAudioConfirmed = AudioLanguage.ENGLISH in said || (original != null && original in said),
            languageMatch = request.preferredLanguage == null || request.preferredLanguage in scored.audioLanguages
        )
    }

    /**
     * Resolutions highest first. A resolution whose best file the connection can't keep up with,
     * or the TV can't show as meant, steps down to the next (§5): its files are only a last resort,
     * after every other; so is any file the connection or the TV can't take within one that's allowed.
     */
    private fun order(candidates: List<AutoStreamCandidate>, config: AutoStreamConfig): List<AutoStreamCandidate> {
        val allowed = ArrayList<AutoStreamCandidate>(candidates.size)
        val capped = ArrayList<AutoStreamCandidate>()
        candidates.groupBy { it.tier }.toSortedMap(compareByDescending { it }).values.forEach { members ->
            val sorted = withinResolution(members, config)
            val best = sorted.first()
            if (best.fitsConnection && best.tvCompatible) {
                sorted.forEach { candidate ->
                    if (candidate.fitsConnection && candidate.tvCompatible) allowed += candidate
                    else capped += candidate.copy(tierAllowed = false)
                }
            } else {
                sorted.mapTo(capped) { it.copy(tierAllowed = false) }
            }
        }
        return allowed + capped
    }

    /**
     * One resolution's files, best first (see [AutoStreamEngine]). [readyFirst]: the ones that play at
     * once (ready on the debrid service, or a direct link) before the ones still to be fetched.
     */
    private fun withinResolution(
        members: List<AutoStreamCandidate>,
        config: AutoStreamConfig,
        readyFirst: Boolean = false
    ): List<AutoStreamCandidate> {
        fun waits(candidate: AutoStreamCandidate): Boolean = readyFirst && !playsAtOnce(candidate.scored.source)
        // Comparable files (§4): the same layout and audio format, bitrates within the margin of the
        // group's highest. A file whose bitrate isn't known comes after the ones whose is.
        val group = IdentityHashMap<AutoStreamCandidate, Int>()
        members.groupBy { listOf(it.tvCompatible, waits(it), it.recentlyFailed, it.layout, it.audioCodecRank) }.values.forEach { part ->
            var head = 0.0
            var index = -1
            part.filter { it.bitrateMbps != null }.sortedByDescending { it.bitrateMbps }.forEach { candidate ->
                val bitrate = candidate.bitrateMbps!!
                if (index < 0 || head <= 0.0 || (head - bitrate) / head > config.comparableBitrateMargin) {
                    head = bitrate
                    index++
                }
                group[candidate] = index
            }
            part.filter { it.bitrateMbps == null }.forEach { group[it] = Int.MAX_VALUE }
        }
        return members.sortedWith(
            compareBy<AutoStreamCandidate> { if (it.tvCompatible) 0 else 1 }
                .thenBy { if (waits(it)) 1 else 0 }
                .thenBy { if (it.recentlyFailed) 1 else 0 }
                .thenByDescending { it.layout }
                .thenByDescending { it.audioCodecRank }
                .thenBy { group.getValue(it) }
                .thenByDescending { it.videoPoints }
                .thenByDescending { it.embeddedSpanish }
                .thenByDescending { it.reliability }
                .thenBy { it.responseMs ?: Double.MAX_VALUE }
                .thenByDescending { it.bitrateMbps ?: -1.0 }
        )
    }

    /** Plays at once: ready on the debrid service, or a direct link (not a torrent to fetch). */
    private fun playsAtOnce(source: NormalizedSource): Boolean = when (source.cache) {
        CacheState.CACHED -> true
        CacheState.UNCACHED -> false
        CacheState.UNKNOWN -> source.isPlayable && !source.isPeerToPeer && source.stream.getStreamUrl() != null
    }

    // ==================================================================== The player's source list

    /**
     * RCTV: the player's list of sources (its cloud button), for choosing another one: the title's
     * right sources (the wrong title, year, season or episode are left out, as the Smart Scraper's
     * list leaves them out) at most [AutoStreamConfig.maxFileSizeBytes] (never a bigger file), only
     * the ones the connection keeps up with (all of them within the size when none does), in quality
     * order: the resolution first, the ones that play at once before the ones to be fetched, then
     * Auto Stream's order (5.1, the audio format, the picture, reliability). A file whose size isn't
     * given comes after every one whose size is (in the same order), but never a remux (always far
     * bigger than the limit).
     */
    fun panelSources(
        sources: List<NormalizedSource>,
        request: SmartSourceRequest,
        context: SmartRankingContext = SmartRankingContext.DEFAULT
    ): List<ScoredSource> {
        if (sources.isEmpty()) return emptyList()
        val config = context.config.autoStream
        val candidates = sources
            .map { SmartScoreEngine.judge(it, request, context) }
            .filter { it.rejection?.removesFromAll != true }
            .filter { scored ->
                val size = fileSize(scored.release, request)
                if (size == null) scored.release.source != ReleaseSource.REMUX else size <= config.maxFileSizeBytes
            }
            .map { candidate(it, request, context, AutoStreamPool.MAIN) }
        val shown = candidates.filter { it.fitsConnection }.ifEmpty { candidates }
        val (sized, unsized) = shown.partition { it.sizeKnown }
        return (inQualityOrder(sized, config) + inQualityOrder(unsized, config)).map { it.scored }
    }

    private fun inQualityOrder(candidates: List<AutoStreamCandidate>, config: AutoStreamConfig): List<AutoStreamCandidate> =
        candidates.groupBy { it.tier }
            .toSortedMap(compareByDescending { it })
            .values
            .flatMap { withinResolution(it, config, readyFirst = true) }

    /**
     * Its place in the resolution order for [device] (higher first): up to the TV's own resolution
     * 4K 60 → 2K 50 → 1080p 40 → 720p 30 → 480p 20 → 360p 10 → unknown 0 (§3.1: the lowest). Above
     * the TV's own (a 4K on a 1080p TV) it comes right after the TV's own resolution.
     */
    fun tier(resolution: VideoResolution, device: DeviceProfile): Int {
        val level = resolution.level
        if (level < 0) return 0
        val cap = tvMaxLevel(device)
        return if (level <= cap) (level + 1) * 10 else (cap + 1) * 10 - (level - cap)
    }

    /** The tier of the TV's own resolution. */
    fun tvTier(device: DeviceProfile): Int = (tvMaxLevel(device) + 1) * 10

    /**
     * The highest tier this TV and connection allow (§8.1): the TV's own resolution, or the highest
     * below it whose usual bitrate the connection keeps up with.
     */
    fun highestAllowedTier(context: SmartRankingContext): Int {
        val config = context.config.autoStream
        val cap = tvMaxLevel(context.device)
        val allowed = config.resolutions
            .filter { it.level <= cap }
            .sortedByDescending { it.level }
            .firstOrNull { fitsConnection(SmartScoreEngine.defaultMbps(it), context) }
            ?: return 0
        return tier(allowed, context.device)
    }

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
     * Its average bitrate (Mbps): size × 8 ÷ the title's length (a movie's or an episode's usual one
     * when it isn't known); null when its size isn't.
     */
    fun bitrateMbps(scored: ScoredSource, request: SmartSourceRequest, config: AutoStreamConfig): Double? {
        scored.bitrateMbps?.let { return it }
        val size = fileSize(scored.release, request) ?: return null
        val minutes = if (request.isSeries) config.typicalEpisodeMinutes else config.typicalMovieMinutes
        return size * 8.0 / (minutes * 60.0) / 1_000_000.0
    }

    /**
     * §5: the connection keeps up with a file of [bitrateMbps] (its average bitrate) times the
     * headroom: what plays have shown (the bitrate above which they buffered, the speed measured),
     * else the kind of network. A connection not known yet keeps up with anything.
     */
    fun fitsConnection(bitrateMbps: Double, context: SmartRankingContext): Boolean {
        if (!context.config.connectionAware) return true
        val config = context.config.autoStream
        val connection = context.connection
        val required = bitrateMbps * config.bitrateHeadroom
        connection.learnedMaxMbps?.let { if (bitrateMbps > it) return false }
        connection.availableMbps?.let { return required <= it }
        return when (connection.profile) {
            ConnectionProfile.FAST, ConnectionProfile.UNKNOWN -> true
            ConnectionProfile.MEDIUM -> required <= config.mediumConnectionMaxMbps
            ConnectionProfile.SLOW -> required <= config.slowConnectionMaxMbps
        }
    }

    fun layoutOf(channels: AudioChannels): Int = when (channels) {
        AudioChannels.CH_5_1 -> LAYOUT_5_1
        AudioChannels.CH_2_0 -> LAYOUT_2_0
        else -> LAYOUT_UNKNOWN
    }

    /**
     * §4: E-AC-3 (Atmos too) > DTS > AC-3 > AAC > the rest; a format this box can neither decode nor
     * pass to the receiver comes after every one it can.
     */
    fun audioCodecRank(codec: AudioCodec, device: DeviceProfile): Int {
        val outputs = device.audioOutputs
        if (outputs != null && codec != AudioCodec.UNKNOWN && codec !in outputs) return -1
        return when (codec) {
            AudioCodec.DDP -> 4
            AudioCodec.DTS, AudioCodec.DTS_HD, AudioCodec.DTS_HD_MA, AudioCodec.DTS_X -> 3
            AudioCodec.DD -> 2
            AudioCodec.AAC -> 1
            else -> 0
        }
    }

    /**
     * §4: the picture beyond its bitrate, for this TV: release type (BluRay > WEB-DL > WEBRip >
     * HDTV; not said: in the middle), HDR the TV shows (Dolby Vision only where it shows it; HDR10+
     * and HDR10 over SDR), a codec it decodes in hardware, IMAX.
     */
    private fun videoPoints(release: ParsedRelease, compatible: Boolean, context: SmartRankingContext): Int {
        val device = context.device
        var points = when (release.source) {
            ReleaseSource.REMUX, ReleaseSource.BLURAY -> 3
            ReleaseSource.WEB_DL -> 2
            ReleaseSource.WEBRIP, ReleaseSource.UNKNOWN -> 1
            ReleaseSource.HDTV -> 0
            else -> -1
        }
        val hdr = release.hdr - HdrFormat.SDR
        if (compatible && device.isKnown && context.config.preferHdr && hdr.isNotEmpty()) {
            points += when {
                HdrFormat.DOLBY_VISION in hdr && device.supportsDolbyVision -> 3
                hdr.any { it == HdrFormat.HDR10_PLUS || it == HdrFormat.HDR10 || it == HdrFormat.HDR } && device.supportsHdr10 -> 2
                HdrFormat.HLG in hdr && device.supportsHlg -> 1
                else -> 0
            }
        }
        points += when (release.codec) {
            VideoCodec.HEVC -> if (device.hasHardwareHevc) 1 else 0
            VideoCodec.AV1 -> if (device.isKnown && device.hasHardwareAv1) 1 else 0
            VideoCodec.LEGACY -> -2
            else -> 0
        }
        if (release.imax && context.config.preferImax) points += 1
        return points
    }

    // ==================================================================== What will play

    /**
     * "4K · 5.7 GB · 5.1 · HDR10 · IMAX · Español": what will play (never where from). The size is
     * rounded up, in [bytesPerGb] (the rule's unit): a file shown at the limit is never over it.
     */
    fun summary(
        release: ParsedRelease,
        language: AudioLanguage? = null,
        bytesPerGb: Double = ParsedRelease.BYTES_PER_GB
    ): String = buildList {
        if (release.resolution.isKnown) add(release.resolution.label)
        release.sizeBytes?.takeIf { !release.sizeIsPack }?.let { add(formatSize(it, bytesPerGb)) }
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

    /** "7.4 GB" (tenths, rounded up), "980 MB" under 1 GB. */
    fun formatSize(bytes: Long, bytesPerGb: Double = ParsedRelease.BYTES_PER_GB): String {
        val gb = bytes / bytesPerGb
        if (gb < 1.0) return String.format(Locale.US, "%.0f MB", bytes / (bytesPerGb / 1024.0))
        // A hair over a tenth (a float's error) isn't the next tenth.
        val tenths = ceil(gb * 10.0 - 1e-6) / 10.0
        return String.format(Locale.US, "%.1f GB", tenths)
    }

    private val WRONG_CONTENT = setOf(
        RejectReason.WRONG_TITLE,
        RejectReason.WRONG_YEAR,
        RejectReason.WRONG_TYPE,
        RejectReason.WRONG_SEASON,
        RejectReason.WRONG_EPISODE
    )

    /** The parser's word for "Dual" / "Multi" audio. */
    private const val MULTI_LABEL = "Multi"
}
