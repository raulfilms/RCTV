package com.nuvio.tv.core.smartscraper

import com.nuvio.tv.domain.model.Stream

/*
 * RCTV Smart Scraper: what a stream is (read from its release name and what its addon says about
 * it), whether it's the title asked for, and how good a choice it is for this person, this device
 * and this connection. Where a stream comes from (its addon, its debrid service) is known here only
 * to rank and group; the app never shows it.
 */

/** A picture's height, as releases name it ("4K", "UHD", "3840x2160" are all 2160p). */
enum class VideoResolution(val level: Int, val label: String) {
    UHD_2160(5, "4K"),
    QHD_1440(4, "2K"),
    FHD_1080(3, "1080p"),
    HD_720(2, "720p"),
    /** 480p/576p. */
    SD(1, "SD"),
    /** 360p and lower. */
    LOW(0, "360p"),
    UNKNOWN(-1, "");

    val isKnown: Boolean get() = this != UNKNOWN

    companion object {
        fun ofHeight(height: Int): VideoResolution = when {
            height >= 2000 -> UHD_2160
            height >= 1400 -> QHD_1440
            height >= 1000 -> FHD_1080
            height >= 700 -> HD_720
            height >= 450 -> SD
            height > 0 -> LOW
            else -> UNKNOWN
        }

        fun ofLevel(level: Int): VideoResolution = entries.firstOrNull { it.level == level } ?: UNKNOWN
    }
}

/** Where the release was taken from, best first. CAM, TS, TC and screeners are never offered. */
enum class ReleaseSource(val label: String, val isLowQuality: Boolean = false) {
    REMUX("Remux"),
    BLURAY("BluRay"),
    WEB_DL("WEB-DL"),
    WEBRIP("WEBRip"),
    HDTV("HDTV"),
    DVD("DVD"),
    SCREENER("SCR", isLowQuality = true),
    TELECINE("TC", isLowQuality = true),
    TELESYNC("TS", isLowQuality = true),
    CAM("CAM", isLowQuality = true),
    UNKNOWN("")
}

enum class VideoCodec(val label: String) {
    HEVC("HEVC"),
    AV1("AV1"),
    AVC("H.264"),
    VP9("VP9"),
    LEGACY("XviD"),
    UNKNOWN("")
}

enum class HdrFormat(val label: String) {
    DOLBY_VISION("Dolby Vision"),
    HDR10_PLUS("HDR10+"),
    HDR10("HDR10"),
    HDR("HDR"),
    HLG("HLG"),
    SDR("SDR")
}

/** An audio track's format; Atmos is told apart on its own (TrueHD Atmos, DDP Atmos...). */
enum class AudioCodec(val label: String, val shortLabel: String) {
    TRUEHD("Dolby TrueHD", "TrueHD"),
    DTS_X("DTS:X", "DTS:X"),
    DTS_HD_MA("DTS-HD MA", "DTS-HD MA"),
    DTS_HD("DTS-HD", "DTS-HD"),
    DDP("Dolby Digital Plus", "DDP"),
    DTS("DTS", "DTS"),
    DD("Dolby Digital", "DD"),
    LPCM("LPCM", "LPCM"),
    FLAC("FLAC", "FLAC"),
    AAC("AAC", "AAC"),
    OPUS("Opus", "Opus"),
    MP3("MP3", "MP3"),
    UNKNOWN("", "")
}

enum class AudioChannels(val label: String, val count: Int) {
    CH_7_1("7.1", 8),
    CH_5_1("5.1", 6),
    CH_2_0("2.0", 2),
    CH_1_0("1.0", 1),
    UNKNOWN("", 0)
}

/** How sure the parser is of each thing it read (0 = not found: UNKNOWN; 1 = said plainly by the addon). */
data class FieldConfidence(
    val title: Float = 0f,
    val year: Float = 0f,
    val episode: Float = 0f,
    val resolution: Float = 0f,
    val source: Float = 0f,
    val codec: Float = 0f,
    val hdr: Float = 0f,
    val audio: Float = 0f,
    val channels: Float = 0f,
    val size: Float = 0f
) {
    /** All of them together (what a stream's metadata is worth overall). */
    val overall: Float
        get() = (title + year + episode + resolution + codec + hdr + audio + size) / 8f
}

/**
 * What a release is, read from its name and its addon's words: anything not found stays unknown
 * (null, empty or UNKNOWN), never guessed.
 */
data class ParsedRelease(
    val releaseName: String? = null,
    val title: String? = null,
    val year: Int? = null,
    val seasons: List<Int> = emptyList(),
    val episodes: List<Int> = emptyList(),
    val isSeasonPack: Boolean = false,
    val resolution: VideoResolution = VideoResolution.UNKNOWN,
    val source: ReleaseSource = ReleaseSource.UNKNOWN,
    val codec: VideoCodec = VideoCodec.UNKNOWN,
    val hdr: Set<HdrFormat> = emptySet(),
    val audioCodec: AudioCodec = AudioCodec.UNKNOWN,
    val channels: AudioChannels = AudioChannels.UNKNOWN,
    val atmos: Boolean = false,
    val imax: Boolean = false,
    val is3d: Boolean = false,
    val tenBit: Boolean = false,
    val sizeBytes: Long? = null,
    /** True when [sizeBytes] is the whole season pack's, not the episode's. */
    val sizeIsPack: Boolean = false,
    val languages: List<String> = emptyList(),
    val confidence: FieldConfidence = FieldConfidence()
) {
    val hasEpisodeInfo: Boolean get() = seasons.isNotEmpty() || episodes.isNotEmpty()
    val sizeGb: Double? get() = sizeBytes?.let { it / BYTES_PER_GB }

    companion object {
        const val BYTES_PER_GB = 1_073_741_824.0
    }
}

/** Which addon family a stream came from (only to rank, group and learn; never shown). */
enum class ProviderFamily { AIOSTREAMS, TORRENTIO, OTHER }

/** The AIOStreams setups the person runs (each its own addon). */
enum class ProviderVariant { MAIN, IMAX, EXTENDED, DUBBED, NONE }

/** The debrid service a stream plays through. */
enum class DebridService { REAL_DEBRID, TORBOX, PREMIUMIZE, ALL_DEBRID, DEBRID_LINK, OTHER, NONE }

/** Whether the file is ready on the debrid service (plays at once) or would have to be fetched first. */
enum class CacheState { CACHED, UNCACHED, UNKNOWN }

data class ProviderIdentity(
    /** The addon's name as installed: the key its reliability is kept under. */
    val key: String,
    val family: ProviderFamily,
    val variant: ProviderVariant = ProviderVariant.NONE,
    val debrid: DebridService = DebridService.NONE
)

/** A stream read and normalized by its provider's adapter: every provider's look the same here. */
data class NormalizedSource(
    val stream: Stream,
    val provider: ProviderIdentity,
    val release: ParsedRelease,
    val cache: CacheState,
    val infoHash: String?,
    val fileIdx: Int?,
    /** A stream that can't play here (a web page, no link at all). */
    val isPlayable: Boolean,
    /** Played from a torrent on this device (no debrid in between). */
    val isPeerToPeer: Boolean
)

/** The title the person asked for, as the app knows it (TMDB/IMDb metadata). */
data class SmartSourceRequest(
    val title: String?,
    val alternateTitles: List<String> = emptyList(),
    val year: Int?,
    val isSeries: Boolean,
    val season: Int? = null,
    val episode: Int? = null,
    val imdbId: String? = null,
    val tmdbId: String? = null,
    /** The movie's or episode's length: tells a file's bitrate from its size. */
    val runtimeMinutes: Int? = null,
    /**
     * The other numbers [episode] goes by (a cartoon's halves TMDB numbers apart that IMDb and the
     * addons tell as one episode): a file named by any of them is this episode too.
     */
    val equivalentEpisodes: Set<Int> = emptySet()
) {
    val isEpisode: Boolean get() = isSeries && season != null && episode != null

    companion object {
        /** Nothing known about the title: streams are only ranked, never checked against it. */
        val UNKNOWN = SmartSourceRequest(title = null, year = null, isSeries = false)

        private val YEAR = Regex("(?:19|20)\\d{2}")

        /** "2022", "2008-2013", "2008–" → 2008/2022 (the first year in it). */
        fun yearOf(text: String?): Int? = text?.let { YEAR.find(it)?.value?.toIntOrNull() }
    }
}

/** How a release's title stands against the one asked for. */
enum class TitleMatch(val confidence: Float) {
    EXACT(1f),
    NORMALIZED(0.95f),
    ALIAS(0.9f),
    PARTIAL(0.75f),
    UNKNOWN(0.6f),
    MISMATCH(0.45f)
}

/** Why a stream isn't offered among the smart sources (or at all). */
enum class RejectReason(val removesFromAll: Boolean, val description: String) {
    /** Another title with no year or episode to tell: kept among all sources (it may be a localized name), never a smart one. */
    WRONG_TITLE(false, "Wrong title"),
    WRONG_YEAR(true, "Year mismatch"),
    WRONG_TYPE(true, "Wrong media type"),
    WRONG_SEASON(true, "Wrong season"),
    WRONG_EPISODE(true, "Wrong episode"),
    LOW_RESOLUTION(false, "Below the minimum resolution"),
    LOW_QUALITY_SOURCE(false, "CAM/TS/TC release"),
    TOO_LARGE(false, "Maximum size exceeded"),
    SEASON_PACK_TOO_LARGE(false, "Season pack, not an episode source"),
    NOT_PLAYABLE(false, "Not playable here"),
    NOT_CACHED(false, "Not cached on the debrid service"),
    FILTERED(false, "Outside the chosen filters")
}

/** The informational tags on a smart source (they always tell the truth about its metadata). */
enum class SmartLabel {
    BEST_MATCH,
    BEST_4K,
    BEST_AUDIO,
    LOW_BANDWIDTH,
    FAST_SOURCE,
    IMAX,
    ATMOS,
    CHANNELS_7_1,
    CHANNELS_5_1
}

/** The parts of a stream's Smart Score, each 0..1. */
data class ScoreBreakdown(
    val metadata: Float,
    val video: Float,
    val audio: Float,
    val size: Float,
    val connection: Float,
    val sourceReliability: Float,
    val playbackReliability: Float
)

/** One stream, judged. */
data class ScoredSource(
    val source: NormalizedSource,
    val titleMatch: TitleMatch,
    val rejection: RejectReason?,
    val score: Int,
    val breakdown: ScoreBreakdown,
    /** The bitrate its size and the title's length give (Mbps); null when either is unknown. */
    val bitrateMbps: Double?,
    /** What it needs from the connection to play smoothly (Mbps), estimated from its bitrate or picture. */
    val requiredMbps: Double
) {
    val stream: Stream get() = source.stream
    val release: ParsedRelease get() = source.release
}

/** A release, once: the same file from several addons/services is one entry, its other copies kept to fall back on. */
data class SmartSourceEntry(
    val best: ScoredSource,
    val copies: List<ScoredSource>,
    val labels: List<SmartLabel>
) {
    val stream: Stream get() = best.stream
    /** How many addons/services offer this same file. */
    val copyCount: Int get() = 1 + copies.size
}

/** Everything the scraper made of a title's streams. */
data class SmartSourcesResult(
    /** The best practical choices, best first, each release once. */
    val smart: List<SmartSourceEntry>,
    /** Every stream that is the right title (the person can still pick any of them), in Smart order. */
    val all: List<ScoredSource>,
    /** What was left out entirely (wrong title, year, season or episode), for the logs. */
    val rejected: List<ScoredSource>,
    /** Smart's order, with every copy after its release: what to try, one after another, if one fails. */
    val fallbackChain: List<Stream>
) {
    companion object {
        val EMPTY = SmartSourcesResult(emptyList(), emptyList(), emptyList(), emptyList())
    }
}
