package com.nuvio.tv.core.smartscraper

/** How much each part of the Smart Score weighs (any scale: they're taken as shares of their sum). */
data class SmartScoreWeights(
    val metadata: Float = 30f,
    val video: Float = 20f,
    val audio: Float = 15f,
    val size: Float = 15f,
    val connection: Float = 10f,
    val sourceReliability: Float = 5f,
    val playbackReliability: Float = 5f
) {
    val total: Float
        get() = (metadata + video + audio + size + connection + sourceReliability + playbackReliability)
            .takeIf { it > 0f } ?: 1f
}

/** The sound a person wants most. */
enum class PreferredAudio { ATMOS, SURROUND, ANY }

/**
 * Every rule of the Smart Scraper in one place (the person's choices from Settings, else these
 * defaults): nothing else in the app hard-codes them.
 */
data class SmartScraperConfig(
    /** MAX_FILE_SIZE_GB: bigger files are never smart sources (0 = no limit). */
    val maxFileSizeGb: Double = 8.0,
    /** MIN_RESOLUTION. */
    val minResolution: VideoResolution = VideoResolution.HD_720,
    /** PREFERRED_RESOLUTION. */
    val preferredResolution: VideoResolution = VideoResolution.UHD_2160,
    /** PREFERRED_SIZE_MIN / PREFERRED_SIZE_MAX (a two-hour movie's; an episode's are in proportion). */
    val preferredSizeMinGb: Double = 6.0,
    val preferredSizeMaxGb: Double = 8.0,
    /** PREFERRED_AUDIO. */
    val preferredAudio: PreferredAudio = PreferredAudio.ATMOS,
    /** Fewer channels than this are ranked down (not hidden). */
    val minAudioChannels: AudioChannels = AudioChannels.CH_5_1,
    val preferHdr: Boolean = true,
    val preferImax: Boolean = true,
    val preferHevc: Boolean = true,
    /** CONNECTION_AWARE. */
    val connectionAware: Boolean = true,
    /** AUTO_FALLBACK: the next source is tried when one fails to start. */
    val autoFallback: Boolean = true,
    /** DUPLICATE_GROUPING. */
    val duplicateGrouping: Boolean = true,
    /** Learn from how plays went (buffering, failures) to rank better. */
    val learnFromPlayback: Boolean = true,
    /** The logs say why each stream was kept or left out and its score. */
    val debug: Boolean = false,
    /** A movie year off by this much still counts as the same movie (release dates differ by country). */
    val yearTolerance: Int = 1,
    /** At most this many releases among the smart sources. */
    val maxSmartSources: Int = 12,
    /** A stream needing at most this many Mbps is a "low bandwidth" one. */
    val lowBandwidthMbps: Double = 6.0,
    /** Each addon's answer is waited for this long (the slow one never holds the rest). */
    val providerTimeoutSeconds: Int = 30,
    val weights: SmartScoreWeights = SmartScoreWeights(),
    /** One-click playback's own, stricter rules. */
    val autoStream: AutoStreamConfig = AutoStreamConfig()
) {
    val maxFileSizeBytes: Long?
        get() = maxFileSizeGb.takeIf { it > 0.0 }?.let { (it * ParsedRelease.BYTES_PER_GB).toLong() }

    companion object {
        val DEFAULT = SmartScraperConfig()

        /** A two-hour movie: what the preferred sizes are for. */
        const val REFERENCE_RUNTIME_MINUTES = 120

        /** An episode whose length isn't known is taken as this long (for its sizes only). */
        const val DEFAULT_EPISODE_MINUTES = 45
    }
}

/**
 * Auto Stream v2.0: Play starts the right title's best practical stream on its own. Stricter than the
 * smart sources: at most 8.00 GB (8.00 GB itself plays), 5.1 or 2.0 only (never 7.1), 2160p → 1440p
 * → 1080p → 720p → 480p (480p last, 360p never), English or the title's own audio, ready on the
 * debrid service. Every threshold of the spec's §13 is here (its name in capitals): nothing else
 * hard-codes them. The ones marked "Phase 2/3/4" are used once that phase is delivered.
 */
data class AutoStreamConfig(
    // ------------------------------------------------------------------ Rules (§3)
    /** AUTO_STREAM_MAX_SIZE_GB, inclusive: 8.00 GB plays, 8.01 GB doesn't. */
    val maxFileSizeGb: Double = 8.0,
    /**
     * SIZE_UNIT_BYTES_PER_GB: 1024³, the one unit of the app (the parser's and the summary's), so
     * the size shown and the size the rule checks always agree.
     */
    val bytesPerGb: Long = ParsedRelease.BYTES_PER_GB.toLong(),
    /** AUTO_STREAM_RESOLUTIONS, best first: 480p is the last resort; anything lower never plays. */
    val resolutions: List<VideoResolution> = listOf(
        VideoResolution.UHD_2160,
        VideoResolution.QHD_1440,
        VideoResolution.FHD_1080,
        VideoResolution.HD_720,
        VideoResolution.SD
    ),
    /** AUTO_STREAM_ALLOWED_CHANNELS: by layout, not tags (DD+ 5.1 Atmos is 5.1; 7.1 never). */
    val allowedChannels: Set<AudioChannels> = setOf(AudioChannels.CH_5_1, AudioChannels.CH_2_0),
    /** REQUIRE_CACHED: only what its addon marks ready on the debrid service ([RD+], ⚡...). */
    val requireCached: Boolean = true,
    /** §3.1: a file whose layout isn't said may play, after every one said to be 5.1 or 2.0. */
    val allowUnverifiedAudio: Boolean = true,

    // ------------------------------------------------------------------ Ranking, connection (§4, §5)
    /** COMPARABLE_BITRATE_MARGIN: bitrates this close (share of the higher) are the same picture. */
    val comparableBitrateMargin: Double = 0.15,
    /** BITRATE_HEADROOM: a file needs its average bitrate times this from the connection (VBR peaks). */
    val bitrateHeadroom: Double = 1.5,
    /** When the connection's speed isn't measured, what a medium / slow one is taken to keep up with (Mbps). */
    val mediumConnectionMaxMbps: Double = 15.0,
    val slowConnectionMaxMbps: Double = 6.0,
    /** A movie's / an episode's length when the title's isn't known (a bitrate from a size). */
    val typicalMovieMinutes: Int = 110,
    val typicalEpisodeMinutes: Int = 45,

    // ------------------------------------------------------------------ Pipeline (§8; Phase 2, Phase 4)
    /** STARTUP_DEADLINE_S: from Play to the first picture. */
    val startupDeadlineSeconds: Double = 10.0,
    /** PROVIDER_TIMEOUT_S: each addon's answer is waited for this long. */
    val providerTimeoutSeconds: Double = 4.0,
    /** EARLY_DECISION_S / EARLY_DECISION_MIN_RELIABILITY (§8.1). */
    val earlyDecisionSeconds: Double = 2.5,
    val earlyDecisionMinReliability: Double = 0.90,
    /** RESOLVE_TIMEOUT_S: following the link and its first-bytes check (§8, Phase 2). */
    val resolveVerifyTimeoutSeconds: Double = 2.5,

    // ------------------------------------------------------------------ Dual-language engine (§6, §7; Phase 3)
    /** PREFERRED_AUDIO_LANGUAGE: "en" or "es". */
    val preferredAudioLanguage: String = "en",
    /** SPANISH_VARIANT_ORDER: Latin American first, Castilian after. */
    val spanishVariantOrder: List<String> = listOf("es-419", "es-ES"),
    /** SPANISH_POOL. */
    val spanishPool: List<String> = listOf("LatinoDebrid", "Addon LATAM", "AIO Stream (Spanish)"),
    /** DONOR_PROBE_COUNT / DONOR_PROBE_TIMEOUT_S / DONOR_DURATION_TOLERANCE_S / DONOR_IDLE_BUFFER_S. */
    val donorProbeCount: Int = 3,
    val donorProbeTimeoutSeconds: Double = 4.0,
    val donorDurationToleranceSeconds: Double = 1.0,
    val donorIdleBufferSeconds: Int = 20,
    /** AUTO_SWITCH_WHEN_SPANISH_READY. */
    val autoSwitchWhenSpanishReady: Boolean = true,
    /** AUDIO_SWITCH_MAX_GAP_MS / SYNC_TARGET_MS / DRIFT_CHECK_INTERVAL_MS / DRIFT_HARD_SEEK_MS. */
    val audioSwitchMaxGapMs: Int = 300,
    val syncTargetMs: Int = 40,
    val driftCheckIntervalMs: Int = 250,
    val driftHardSeekMs: Int = 250,
    /** MANUAL_OFFSET_STEP_MS / MANUAL_OFFSET_RANGE_MS. */
    val manualOffsetStepMs: Int = 50,
    val manualOffsetRangeMs: Int = 5000,
    /** AUTO_OFFSET_DETECTION (Phase 4). */
    val autoOffsetDetection: Boolean = false,

    // ------------------------------------------------------------------ Learning, caching (§10; Phase 4)
    /** RELIABILITY_HALF_LIFE_DAYS / RESULT_CACHE_TTL_MIN. */
    val reliabilityHalfLifeDays: Int = 14,
    val resultCacheTtlMinutes: Int = 30,

    // ------------------------------------------------------------------ Today's flow (until Phase 2 replaces it)
    /** Addons' answers are gathered this long (sooner when they've all answered) before choosing. */
    val collectSeconds: Int = 5,
    /** From Play to playing: past this, nothing valid found means the sources are shown. */
    val budgetSeconds: Int = 10,
    /** Each source gets this long to give its link before the next is tried. */
    val resolveTimeoutSeconds: Int = 4,
    /** Each source gets this long to show its first picture before the next is tried. */
    val startupTimeoutSeconds: Int = 12,
    /** At most this many sources are tried after the first. */
    val maxFallbacks: Int = 4,
    /** At most this many sources are lined up after the first. */
    val maxChain: Int = 15
) {
    /** The limit in bytes (a file of exactly this many bytes plays). */
    val maxFileSizeBytes: Long get() = (maxFileSizeGb * bytesPerGb).toLong()

    /** The lowest resolution allowed (480p by default). */
    val minResolution: VideoResolution get() = resolutions.minByOrNull { it.level } ?: VideoResolution.SD
}

/** How fast and steady the connection is now. */
enum class ConnectionProfile { FAST, MEDIUM, SLOW, UNKNOWN }

/** The connection, as measured and learned (never anything that identifies the person). */
data class ConnectionEstimate(
    val profile: ConnectionProfile = ConnectionProfile.UNKNOWN,
    /** Usable download speed (Mbps), when known. */
    val availableMbps: Double? = null,
    /** Above this bitrate plays have been buffering lately (learned), when known. */
    val learnedMaxMbps: Double? = null
) {
    companion object {
        val UNKNOWN = ConnectionEstimate()

        fun profileOf(mbps: Double): ConnectionProfile = when {
            mbps >= 60.0 -> ConnectionProfile.FAST
            mbps >= 20.0 -> ConnectionProfile.MEDIUM
            else -> ConnectionProfile.SLOW
        }
    }
}

/** What this TV can show and play. */
data class DeviceProfile(
    val maxResolution: VideoResolution = VideoResolution.UHD_2160,
    val supportsHdr10: Boolean = true,
    val supportsHdr10Plus: Boolean = false,
    val supportsDolbyVision: Boolean = false,
    val supportsHlg: Boolean = false,
    val hasHardwareAv1: Boolean = false,
    val hasHardwareHevc: Boolean = true,
    /** Unknown device: nothing is ranked down for it. */
    val isKnown: Boolean = false,
    /**
     * The audio formats this box can play: decoded (its own decoders, the app's FFmpeg one) or
     * passed through to the receiver over HDMI. Null: not known (no audio format is ranked down).
     * Only Auto Stream reads it.
     */
    val audioOutputs: Set<AudioCodec>? = null
) {
    companion object {
        val UNKNOWN = DeviceProfile()
    }
}

/** How an addon/service has been doing (learned over time). */
data class ProviderStats(
    /** Recent successes and failures, older ones counting less. */
    val successes: Double = 0.0,
    val failures: Double = 0.0,
    /** How long it takes to answer, on average (ms), when known. */
    val averageResponseMs: Double? = null
) {
    val samples: Double get() = successes + failures

    /** 0..1, with a little doubt for few samples (no samples: 0.5). */
    val successRate: Double get() = (successes + 1.0) / (successes + failures + 2.0)

    companion object {
        val NONE = ProviderStats()
    }
}

/** What the ranking knows beyond the streams themselves. */
data class SmartRankingContext(
    val config: SmartScraperConfig = SmartScraperConfig.DEFAULT,
    val connection: ConnectionEstimate = ConnectionEstimate.UNKNOWN,
    val device: DeviceProfile = DeviceProfile.UNKNOWN,
    /** By provider key. */
    val providerStats: Map<String, ProviderStats> = emptyMap(),
    /** Releases (by their key) that failed to play lately. */
    val failedReleaseKeys: Set<String> = emptySet()
) {
    companion object {
        val DEFAULT = SmartRankingContext()
    }
}
