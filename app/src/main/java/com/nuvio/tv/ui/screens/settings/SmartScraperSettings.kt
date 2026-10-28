package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import com.nuvio.tv.R
import com.nuvio.tv.core.smartscraper.PreferredAudio
import com.nuvio.tv.core.smartscraper.SmartScraperConfig
import com.nuvio.tv.core.smartscraper.SmartScraperSettingsStore
import com.nuvio.tv.core.smartscraper.VideoResolution
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Settings → Playback → Smart Scraper: the rules the sources are checked, filtered and ranked by. */
@HiltViewModel
class SmartScraperSettingsViewModel @Inject constructor(
    private val store: SmartScraperSettingsStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore
) : ViewModel() {
    val config: StateFlow<SmartScraperConfig> = store.config

    /** Auto Stream on: Play starts the best stream on its own. */
    val autoStreamEnabled: StateFlow<Boolean> = playerSettingsDataStore.playerSettings
        .map { it.streamAutoPlayMode == StreamAutoPlayMode.AUTO_STREAM }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun update(transform: (SmartScraperConfig) -> SmartScraperConfig) = store.updateConfig(transform)

    fun setAutoStream(enabled: Boolean) {
        viewModelScope.launch {
            playerSettingsDataStore.setStreamAutoPlayMode(if (enabled) StreamAutoPlayMode.AUTO_STREAM else StreamAutoPlayMode.MANUAL)
        }
    }
}

/** Settings → Playback → Auto Stream: one switch, and its few rules. */
internal fun LazyListScope.autoStreamSettingsItems(
    enabled: Boolean,
    config: SmartScraperConfig,
    onSetEnabled: (Boolean) -> Unit,
    onUpdate: ((SmartScraperConfig) -> SmartScraperConfig) -> Unit,
    onItemFocused: () -> Unit
) {
    toggle("auto_stream_enabled", Icons.Default.PlayCircle, R.string.auto_stream_title, R.string.auto_stream_sub, enabled, onItemFocused, onSetEnabled)
    if (!enabled) return
    item(key = "auto_stream_max_size") {
        NavigationSettingsItem(
            icon = Icons.Default.Storage,
            title = stringResource(R.string.auto_stream_max_size),
            subtitle = "≤ ${formatGb(config.autoStream.maxFileSizeGb)} · ${stringResource(R.string.auto_stream_max_size_sub)}",
            onClick = {
                onUpdate { it.copy(autoStream = it.autoStream.copy(maxFileSizeGb = nextOf(it.autoStream.maxFileSizeGb, AUTO_STREAM_SIZES))) }
            },
            onFocused = onItemFocused
        )
    }
    item(key = "auto_stream_collect") {
        NavigationSettingsItem(
            icon = Icons.Default.Timer,
            title = stringResource(R.string.auto_stream_collect),
            subtitle = "${config.autoStream.collectSeconds} s · ${stringResource(R.string.auto_stream_collect_sub)}",
            onClick = {
                onUpdate { it.copy(autoStream = it.autoStream.copy(collectSeconds = nextOf(it.autoStream.collectSeconds, AUTO_STREAM_COLLECT))) }
            },
            onFocused = onItemFocused
        )
    }
    toggle(
        "auto_stream_unverified_audio",
        Icons.Default.SurroundSound,
        R.string.auto_stream_unverified_audio,
        R.string.auto_stream_unverified_audio_sub,
        config.autoStream.allowUnverifiedAudio,
        onItemFocused
    ) { on -> onUpdate { it.copy(autoStream = it.autoStream.copy(allowUnverifiedAudio = on)) } }
}

private fun formatGb(gb: Double): String = if (gb % 1.0 == 0.0) "${gb.toInt()} GB" else "$gb GB"

private val AUTO_STREAM_SIZES = listOf(8.0, 6.0, 5.0, 4.0)
private val AUTO_STREAM_COLLECT = listOf(5, 3, 7)

/** Each choice steps to the next on every press (no dialog); its value is shown before what it does. */
internal fun LazyListScope.smartScraperSettingsItems(
    config: SmartScraperConfig,
    onUpdate: ((SmartScraperConfig) -> SmartScraperConfig) -> Unit,
    onItemFocused: () -> Unit
) {
    item(key = "smart_max_size") {
        val value = if (config.maxFileSizeGb > 0) "${config.maxFileSizeGb.toInt()} GB" else stringResource(R.string.smart_filter_no_limit)
        NavigationSettingsItem(
            icon = Icons.Default.Storage,
            title = stringResource(R.string.smart_max_size),
            subtitle = "$value · ${stringResource(R.string.smart_max_size_sub)}",
            onClick = { onUpdate { it.copy(maxFileSizeGb = nextOf(it.maxFileSizeGb, MAX_SIZES)) } },
            onFocused = onItemFocused
        )
    }
    item(key = "smart_preferred_resolution") {
        NavigationSettingsItem(
            icon = Icons.Default.HighQuality,
            title = stringResource(R.string.smart_preferred_resolution),
            subtitle = "${config.preferredResolution.label} · ${stringResource(R.string.smart_preferred_resolution_sub)}",
            onClick = { onUpdate { it.copy(preferredResolution = nextOf(it.preferredResolution, PREFERRED_RESOLUTIONS)) } },
            onFocused = onItemFocused
        )
    }
    item(key = "smart_min_resolution") {
        NavigationSettingsItem(
            icon = Icons.Default.Tune,
            title = stringResource(R.string.smart_min_resolution),
            subtitle = "${config.minResolution.label} · ${stringResource(R.string.smart_min_resolution_sub)}",
            onClick = { onUpdate { it.copy(minResolution = nextOf(it.minResolution, MIN_RESOLUTIONS)) } },
            onFocused = onItemFocused
        )
    }
    item(key = "smart_preferred_audio") {
        val value = when (config.preferredAudio) {
            PreferredAudio.ATMOS -> stringResource(R.string.smart_audio_atmos)
            PreferredAudio.SURROUND -> stringResource(R.string.smart_audio_surround)
            PreferredAudio.ANY -> stringResource(R.string.smart_audio_any)
        }
        NavigationSettingsItem(
            icon = Icons.Default.SurroundSound,
            title = stringResource(R.string.smart_preferred_audio),
            subtitle = "$value · ${stringResource(R.string.smart_preferred_audio_sub)}",
            onClick = { onUpdate { it.copy(preferredAudio = nextOf(it.preferredAudio, PreferredAudio.entries)) } },
            onFocused = onItemFocused
        )
    }
    toggle("smart_prefer_hdr", Icons.Default.HdrOn, R.string.smart_prefer_hdr, R.string.smart_prefer_hdr_sub, config.preferHdr, onItemFocused) { on ->
        onUpdate { it.copy(preferHdr = on) }
    }
    toggle("smart_prefer_imax", Icons.Default.Theaters, R.string.smart_prefer_imax, R.string.smart_prefer_imax_sub, config.preferImax, onItemFocused) { on ->
        onUpdate { it.copy(preferImax = on) }
    }
    toggle("smart_prefer_hevc", Icons.Default.Memory, R.string.smart_prefer_hevc, R.string.smart_prefer_hevc_sub, config.preferHevc, onItemFocused) { on ->
        onUpdate { it.copy(preferHevc = on) }
    }
    toggle("smart_connection_aware", Icons.Default.NetworkCheck, R.string.smart_connection_aware, R.string.smart_connection_aware_sub, config.connectionAware, onItemFocused) { on ->
        onUpdate { it.copy(connectionAware = on) }
    }
    toggle("smart_auto_fallback", Icons.Default.SwapHoriz, R.string.smart_auto_fallback, R.string.smart_auto_fallback_sub, config.autoFallback, onItemFocused) { on ->
        onUpdate { it.copy(autoFallback = on) }
    }
    toggle("smart_grouping", Icons.Default.Layers, R.string.smart_grouping, R.string.smart_grouping_sub, config.duplicateGrouping, onItemFocused) { on ->
        onUpdate { it.copy(duplicateGrouping = on) }
    }
    toggle("smart_learn", Icons.Default.Insights, R.string.smart_learn, R.string.smart_learn_sub, config.learnFromPlayback, onItemFocused) { on ->
        onUpdate { it.copy(learnFromPlayback = on) }
    }
    toggle("smart_debug", Icons.Default.BugReport, R.string.smart_debug, R.string.smart_debug_sub, config.debug, onItemFocused) { on ->
        onUpdate { it.copy(debug = on) }
    }
}

private fun LazyListScope.toggle(
    key: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: Int,
    subtitle: Int,
    checked: Boolean,
    onFocused: () -> Unit,
    onChange: (Boolean) -> Unit
) {
    item(key = key) {
        ToggleSettingsItem(
            icon = icon,
            title = stringResource(title),
            subtitle = stringResource(subtitle),
            isChecked = checked,
            onCheckedChange = onChange,
            onFocused = onFocused
        )
    }
}

/** The choice after [current] in [choices] (after the last, the first; not one of them: the first). */
private fun <T> nextOf(current: T, choices: List<T>): T {
    val index = choices.indexOf(current)
    return choices[(index + 1) % choices.size]
}

/** 0 = no limit. */
private val MAX_SIZES = listOf(8.0, 10.0, 12.0, 16.0, 0.0, 4.0, 6.0)
private val PREFERRED_RESOLUTIONS = listOf(VideoResolution.UHD_2160, VideoResolution.FHD_1080, VideoResolution.HD_720)
private val MIN_RESOLUTIONS = listOf(VideoResolution.HD_720, VideoResolution.FHD_1080, VideoResolution.SD)
