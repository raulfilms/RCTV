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
 * Auto Stream: Play starts a stream on its own. Stricter than the smart sources: under 6 GB (never
 * at or over it), 5.1 or stereo only (never 7.1), 4K → 2K → 1080p → 720p → 480p, the highest the
 * connection plays smoothly, chosen within seconds; a source that fails is skipped for the next.
 */
data class AutoStreamConfig(
    /** Files must be smaller than this (6.0 GB itself isn't allowed). */
    val maxFileSizeGb: Double = 6.0,
    /** The lowest resolution allowed (480p, then 360p, are the last resorts). */
    val minResolution: VideoResolution = VideoResolution.LOW,
    /** Only these audio layouts (never 7.1 or more). */
    val allowedChannels: Set<AudioChannels> = setOf(AudioChannels.CH_5_1, AudioChannels.CH_2_0),
    /** A file whose audio isn't said is tried only after every one whose audio is 5.1 or stereo. */
    val allowUnverifiedAudio: Boolean = true,
    /** Addons' answers are gathered this long (sooner when they've all answered) before choosing. */
    val collectSeconds: Int = 5,
    /** From Play to playing: past this, nothing valid found means the sources are shown. */
    val budgetSeconds: Int = 10,
    /** Each source gets this long to give its link before the next is tried. */
    val resolveTimeoutSeconds: Int = 4,
    /** Each source gets this long to show its first picture before the next is tried. */
    val startupTimeoutSeconds: Int = 12,
    /** At most this many sources are tried after the first. */
    val maxFallbacks: Int = 4
) {
    val maxFileSizeBytes: Long get() = (maxFileSizeGb * ParsedRelease.BYTES_PER_GB).toLong()
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
    val isKnown: Boolean = false
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
