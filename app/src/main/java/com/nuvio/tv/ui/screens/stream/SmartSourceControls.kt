@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.smartscraper.AudioFilter
import com.nuvio.tv.core.smartscraper.HdrFilter
import com.nuvio.tv.core.smartscraper.SmartLabel
import com.nuvio.tv.core.smartscraper.SmartSort
import com.nuvio.tv.core.smartscraper.SmartSourceViewSettings
import com.nuvio.tv.core.smartscraper.VideoCodec
import com.nuvio.tv.core.smartscraper.VideoResolution
import com.nuvio.tv.ui.components.RefreshFilterChip
import com.nuvio.tv.ui.components.SourceStatusFilterChip
import com.nuvio.tv.ui.theme.NuvioTheme

/*
 * RCTV Smart Scraper on the sources screen: Automatic (the smart sources, then all of them) or
 * Manual (the person's filters, each exactly as chosen, and their order). Each filter is one chip
 * that steps through its choices on every press. There's no filter or order by provider: providers
 * are never shown.
 */

@Composable
internal fun SmartSourceControls(
    view: SmartSourceViewSettings,
    isStillFetching: Boolean,
    onRefresh: () -> Unit,
    onViewChange: ((SmartSourceViewSettings) -> SmartSourceViewSettings) -> Unit,
    modeFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val any = stringResource(R.string.smart_filter_any)
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
        contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs)
    ) {
        item(key = "refresh") {
            RefreshFilterChip(onClick = onRefresh, isLoading = isStillFetching)
        }
        item(key = "automatic") {
            SourceStatusFilterChip(
                name = stringResource(R.string.smart_sources_automatic),
                isSelected = !view.manual,
                onClick = { onViewChange { it.copy(manual = false) } },
                modifier = if (!view.manual) Modifier.focusRequester(modeFocusRequester) else Modifier
            )
        }
        item(key = "manual") {
            SourceStatusFilterChip(
                name = stringResource(R.string.smart_sources_manual),
                isSelected = view.manual,
                onClick = { onViewChange { it.copy(manual = true) } },
                modifier = if (view.manual) Modifier.focusRequester(modeFocusRequester) else Modifier
            )
        }
        if (!view.manual) return@LazyRow

        item(key = "sort") {
            val value = when (view.sort) {
                SmartSort.SMART -> stringResource(R.string.smart_sort_smart)
                SmartSort.RESOLUTION -> stringResource(R.string.smart_sort_resolution)
                SmartSort.AUDIO -> stringResource(R.string.smart_sort_audio)
                SmartSort.SIZE -> stringResource(R.string.smart_sort_size)
            }
            SourceStatusFilterChip(
                name = stringResource(R.string.smart_sort_format, value),
                isSelected = view.sort != SmartSort.SMART,
                onClick = { onViewChange { it.copy(sort = next(it.sort, SmartSort.entries)) } }
            )
        }
        item(key = "resolution") {
            val resolution = view.filters.resolution
            SourceStatusFilterChip(
                name = stringResource(R.string.smart_filter_resolution_format, resolution?.label ?: any),
                isSelected = resolution != null,
                onClick = { onViewChange { it.copy(filters = it.filters.copy(resolution = next(it.filters.resolution, RESOLUTIONS))) } }
            )
        }
        item(key = "audio") {
            val value = when (view.filters.audio) {
                AudioFilter.ANY -> any
                AudioFilter.ATMOS -> "Atmos"
                AudioFilter.CH_7_1 -> "7.1"
                AudioFilter.CH_5_1 -> "5.1+"
            }
            SourceStatusFilterChip(
                name = stringResource(R.string.smart_filter_audio_format, value),
                isSelected = view.filters.audio != AudioFilter.ANY,
                onClick = { onViewChange { it.copy(filters = it.filters.copy(audio = next(it.filters.audio, AudioFilter.entries))) } }
            )
        }
        item(key = "hdr") {
            val value = when (view.filters.hdr) {
                HdrFilter.ANY -> any
                HdrFilter.HDR -> "HDR"
                HdrFilter.DOLBY_VISION -> "Dolby Vision"
                HdrFilter.SDR -> "SDR"
            }
            SourceStatusFilterChip(
                name = stringResource(R.string.smart_filter_hdr_format, value),
                isSelected = view.filters.hdr != HdrFilter.ANY,
                onClick = { onViewChange { it.copy(filters = it.filters.copy(hdr = next(it.filters.hdr, HdrFilter.entries))) } }
            )
        }
        item(key = "codec") {
            val codec = view.filters.codec
            SourceStatusFilterChip(
                name = stringResource(R.string.smart_filter_codec_format, codec?.label ?: any),
                isSelected = codec != null,
                onClick = { onViewChange { it.copy(filters = it.filters.copy(codec = next(it.filters.codec, CODECS))) } }
            )
        }
        item(key = "imax") {
            val only = view.filters.imaxOnly
            SourceStatusFilterChip(
                name = stringResource(R.string.smart_filter_imax_format, if (only) stringResource(R.string.smart_filter_only) else any),
                isSelected = only,
                onClick = { onViewChange { it.copy(filters = it.filters.copy(imaxOnly = !it.filters.imaxOnly)) } }
            )
        }
        item(key = "size") {
            val max = view.filters.maxSizeGb
            SourceStatusFilterChip(
                name = stringResource(
                    R.string.smart_filter_size_format,
                    max?.let { "${it.toInt()} GB" } ?: stringResource(R.string.smart_filter_no_limit)
                ),
                isSelected = max != null,
                onClick = { onViewChange { it.copy(filters = it.filters.copy(maxSizeGb = next(it.filters.maxSizeGb, SIZES))) } }
            )
        }
    }
}

/** The choice after [current] in [choices] (after the last, the first). */
private fun <T> next(current: T, choices: List<T>): T {
    val index = choices.indexOf(current)
    return choices[(index + 1) % choices.size]
}

private val RESOLUTIONS = listOf(null, VideoResolution.UHD_2160, VideoResolution.QHD_1440, VideoResolution.FHD_1080, VideoResolution.HD_720)
private val CODECS = listOf(null, VideoCodec.HEVC, VideoCodec.AVC, VideoCodec.AV1)
private val SIZES = listOf(null, 8.0, 6.0, 4.0, 2.0)

/** A section's name over its sources ("SMART SOURCES", "ALL SOURCES"). */
@Composable
internal fun SmartSourcesHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = NuvioTheme.extendedColors.textSecondary,
        modifier = modifier.padding(
            start = NuvioTheme.spacing.xs,
            top = NuvioTheme.spacing.sm,
            bottom = NuvioTheme.spacing.xxs
        )
    )
}

/** A source's Smart labels ("BEST MATCH", "BEST 4K", "ATMOS", "7.1"...), the best match's in the app's accent. */
@Composable
internal fun SmartLabelChips(labels: List<SmartLabel>, modifier: Modifier = Modifier) {
    if (labels.isEmpty()) return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEach { label ->
            val highlighted = label == SmartLabel.BEST_MATCH
            Text(
                text = smartLabelText(label),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                maxLines = 1,
                color = if (highlighted) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary.copy(alpha = 0.9f),
                modifier = Modifier
                    .background(
                        color = if (highlighted) NuvioTheme.colors.Secondary else Color.White.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun smartLabelText(label: SmartLabel): String = when (label) {
    SmartLabel.BEST_MATCH -> stringResource(R.string.smart_label_best_match)
    SmartLabel.BEST_4K -> stringResource(R.string.smart_label_best_4k)
    SmartLabel.BEST_AUDIO -> stringResource(R.string.smart_label_best_audio)
    SmartLabel.LOW_BANDWIDTH -> stringResource(R.string.smart_label_low_bandwidth)
    SmartLabel.FAST_SOURCE -> stringResource(R.string.smart_label_fast_source)
    SmartLabel.IMAX -> "IMAX"
    SmartLabel.ATMOS -> "ATMOS"
    SmartLabel.CHANNELS_7_1 -> "7.1"
    SmartLabel.CHANNELS_5_1 -> "5.1"
}
