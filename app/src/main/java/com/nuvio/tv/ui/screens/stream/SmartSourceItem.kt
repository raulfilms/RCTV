package com.nuvio.tv.ui.screens.stream

import androidx.compose.runtime.Immutable
import com.nuvio.tv.core.smartscraper.AudioChannels
import com.nuvio.tv.core.smartscraper.AudioCodec
import com.nuvio.tv.core.smartscraper.HdrFormat
import com.nuvio.tv.core.smartscraper.ParsedRelease
import com.nuvio.tv.core.smartscraper.RejectReason
import com.nuvio.tv.core.smartscraper.ScoredSource
import com.nuvio.tv.core.smartscraper.SmartLabel
import com.nuvio.tv.core.smartscraper.VideoCodec
import com.nuvio.tv.core.smartscraper.VideoResolution
import com.nuvio.tv.domain.model.Stream
import java.util.Locale

/**
 * A source as the sources screen shows it: what it is (its picture, size, sound), its Smart labels,
 * and how many addons/services offer the same file; never where it comes from.
 */
@Immutable
data class SmartSourceItem(
    val stream: Stream,
    val release: ParsedRelease,
    val labels: List<SmartLabel>,
    /** How many addons/services offer this same file (a smart source's copies, to fall back on). */
    val copies: Int,
    val score: Int,
    val isSmart: Boolean,
    /** Why it isn't a smart source (all sources only). */
    val rejection: RejectReason?,
    /** Debug mode: its Smart Score and its parts. */
    val debugText: String?
)

/** A source's metadata as text: what's known only, never guessed. */
internal object SmartSourceText {

    /** "4K · Dolby Vision · HDR10", "1080p", null when nothing about its picture is known. */
    fun picture(release: ParsedRelease): String? = buildList {
        if (release.resolution != VideoResolution.UNKNOWN) add(release.resolution.label)
        if (HdrFormat.DOLBY_VISION in release.hdr) add(HdrFormat.DOLBY_VISION.label)
        when {
            HdrFormat.HDR10_PLUS in release.hdr -> add(HdrFormat.HDR10_PLUS.label)
            HdrFormat.HDR10 in release.hdr -> add(HdrFormat.HDR10.label)
            HdrFormat.HDR in release.hdr -> add(HdrFormat.HDR.label)
            HdrFormat.HLG in release.hdr -> add(HdrFormat.HLG.label)
        }
        if (release.is3d) add("3D")
    }.joinToString(" · ").ifBlank { null }

    /** "7.4 GB · DDP Atmos 5.1 · HEVC · WEB-DL · 🇪🇸 Latino". */
    fun details(release: ParsedRelease): String? = buildList {
        release.sizeBytes?.let { add(formatSize(it)) }
        audio(release)?.let(::add)
        if (release.codec != VideoCodec.UNKNOWN) add(release.codec.label)
        if (release.source.label.isNotEmpty()) add(release.source.label)
        if (release.languages.isNotEmpty()) add(release.languages.joinToString(" "))
    }.joinToString(" · ").ifBlank { null }

    /** "TrueHD Atmos 7.1", "DDP 5.1", "Atmos", null when nothing about its sound is known. */
    private fun audio(release: ParsedRelease): String? = buildList {
        if (release.audioCodec != AudioCodec.UNKNOWN) add(release.audioCodec.shortLabel)
        if (release.atmos) add("Atmos")
        if (release.channels != AudioChannels.UNKNOWN) add(release.channels.label)
    }.joinToString(" ").ifBlank { null }

    fun formatSize(bytes: Long): String {
        val gb = bytes / ParsedRelease.BYTES_PER_GB
        return if (gb >= 1.0) String.format(Locale.US, "%.1f GB", gb)
        else String.format(Locale.US, "%.0f MB", bytes / 1_048_576.0)
    }

    /** Debug mode: "Score 92 · meta 1.00 video 0.93 audio 1.00 size 1.00 net 0.60 provider 0.60 play 1.00". */
    fun debug(scored: ScoredSource): String {
        val b = scored.breakdown
        fun f(value: Float) = String.format(Locale.US, "%.2f", value)
        return buildString {
            append("Score ").append(scored.score)
            append(" · meta ").append(f(b.metadata))
            append(" video ").append(f(b.video))
            append(" audio ").append(f(b.audio))
            append(" size ").append(f(b.size))
            append(" net ").append(f(b.connection))
            append(" provider ").append(f(b.sourceReliability))
            append(" play ").append(f(b.playbackReliability))
            append(" · ").append(scored.titleMatch.name.lowercase(Locale.ROOT))
            scored.bitrateMbps?.let { append(String.format(Locale.US, " · %.1f Mbps", it)) }
            scored.rejection?.let { append(" · ").append(it.description) }
        }
    }
}
