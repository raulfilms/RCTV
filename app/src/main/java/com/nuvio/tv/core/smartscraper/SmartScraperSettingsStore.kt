package com.nuvio.tv.core.smartscraper

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** How the sources screen shows them: Automatic (smart, then all) or Manual (the person's filters and order). */
data class SmartSourceViewSettings(
    val manual: Boolean = false,
    val sort: SmartSort = SmartSort.SMART,
    val filters: SmartSourceFilters = SmartSourceFilters()
)

/** The person's Smart Scraper choices (Settings → Playback → Smart Scraper) and the sources screen's mode. */
@Singleton
class SmartScraperSettingsStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(readConfig())
    val config: StateFlow<SmartScraperConfig> = _config.asStateFlow()

    private val _view = MutableStateFlow(readView())
    val view: StateFlow<SmartSourceViewSettings> = _view.asStateFlow()

    fun updateConfig(transform: (SmartScraperConfig) -> SmartScraperConfig) {
        _config.update { current -> transform(current).also(::writeConfig) }
    }

    fun updateView(transform: (SmartSourceViewSettings) -> SmartSourceViewSettings) {
        _view.update { current -> transform(current).also(::writeView) }
    }

    private fun readConfig(): SmartScraperConfig {
        val defaults = SmartScraperConfig.DEFAULT
        return defaults.copy(
            maxFileSizeGb = prefs.getFloat(KEY_MAX_SIZE, defaults.maxFileSizeGb.toFloat()).toDouble(),
            minResolution = enumOr(prefs.getString(KEY_MIN_RES, null), defaults.minResolution),
            preferredResolution = enumOr(prefs.getString(KEY_PREF_RES, null), defaults.preferredResolution),
            preferredAudio = enumOr(prefs.getString(KEY_PREF_AUDIO, null), defaults.preferredAudio),
            preferHdr = prefs.getBoolean(KEY_HDR, defaults.preferHdr),
            preferImax = prefs.getBoolean(KEY_IMAX, defaults.preferImax),
            preferHevc = prefs.getBoolean(KEY_HEVC, defaults.preferHevc),
            connectionAware = prefs.getBoolean(KEY_CONNECTION, defaults.connectionAware),
            autoFallback = prefs.getBoolean(KEY_FALLBACK, defaults.autoFallback),
            duplicateGrouping = prefs.getBoolean(KEY_GROUPING, defaults.duplicateGrouping),
            learnFromPlayback = prefs.getBoolean(KEY_LEARN, defaults.learnFromPlayback),
            debug = prefs.getBoolean(KEY_DEBUG, defaults.debug)
        )
    }

    private fun writeConfig(config: SmartScraperConfig) {
        prefs.edit()
            .putFloat(KEY_MAX_SIZE, config.maxFileSizeGb.toFloat())
            .putString(KEY_MIN_RES, config.minResolution.name)
            .putString(KEY_PREF_RES, config.preferredResolution.name)
            .putString(KEY_PREF_AUDIO, config.preferredAudio.name)
            .putBoolean(KEY_HDR, config.preferHdr)
            .putBoolean(KEY_IMAX, config.preferImax)
            .putBoolean(KEY_HEVC, config.preferHevc)
            .putBoolean(KEY_CONNECTION, config.connectionAware)
            .putBoolean(KEY_FALLBACK, config.autoFallback)
            .putBoolean(KEY_GROUPING, config.duplicateGrouping)
            .putBoolean(KEY_LEARN, config.learnFromPlayback)
            .putBoolean(KEY_DEBUG, config.debug)
            .apply()
    }

    private fun readView(): SmartSourceViewSettings = SmartSourceViewSettings(
        manual = prefs.getBoolean(KEY_VIEW_MANUAL, false),
        sort = enumOr(prefs.getString(KEY_VIEW_SORT, null), SmartSort.SMART),
        filters = SmartSourceFilters(
            resolution = enumOrNull<VideoResolution>(prefs.getString(KEY_F_RES, null)),
            audio = enumOr(prefs.getString(KEY_F_AUDIO, null), AudioFilter.ANY),
            hdr = enumOr(prefs.getString(KEY_F_HDR, null), HdrFilter.ANY),
            codec = enumOrNull<VideoCodec>(prefs.getString(KEY_F_CODEC, null)),
            imaxOnly = prefs.getBoolean(KEY_F_IMAX, false),
            maxSizeGb = prefs.getFloat(KEY_F_SIZE, 0f).takeIf { it > 0f }?.toDouble()
        )
    )

    private fun writeView(view: SmartSourceViewSettings) {
        prefs.edit()
            .putBoolean(KEY_VIEW_MANUAL, view.manual)
            .putString(KEY_VIEW_SORT, view.sort.name)
            .putString(KEY_F_RES, view.filters.resolution?.name)
            .putString(KEY_F_AUDIO, view.filters.audio.name)
            .putString(KEY_F_HDR, view.filters.hdr.name)
            .putString(KEY_F_CODEC, view.filters.codec?.name)
            .putBoolean(KEY_F_IMAX, view.filters.imaxOnly)
            .putFloat(KEY_F_SIZE, view.filters.maxSizeGb?.toFloat() ?: 0f)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T = enumOrNull<T>(name) ?: fallback

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        name?.let { value -> enumValues<T>().firstOrNull { it.name == value } }

    private companion object {
        const val PREFS = "smart_scraper"
        const val KEY_MAX_SIZE = "max_file_size_gb"
        const val KEY_MIN_RES = "min_resolution"
        const val KEY_PREF_RES = "preferred_resolution"
        const val KEY_PREF_AUDIO = "preferred_audio"
        const val KEY_HDR = "prefer_hdr"
        const val KEY_IMAX = "prefer_imax"
        const val KEY_HEVC = "prefer_hevc"
        const val KEY_CONNECTION = "connection_aware"
        const val KEY_FALLBACK = "auto_fallback"
        const val KEY_GROUPING = "duplicate_grouping"
        const val KEY_LEARN = "learn_from_playback"
        const val KEY_DEBUG = "debug"
        const val KEY_VIEW_MANUAL = "view_manual"
        const val KEY_VIEW_SORT = "view_sort"
        const val KEY_F_RES = "filter_resolution"
        const val KEY_F_AUDIO = "filter_audio"
        const val KEY_F_HDR = "filter_hdr"
        const val KEY_F_CODEC = "filter_codec"
        const val KEY_F_IMAX = "filter_imax"
        const val KEY_F_SIZE = "filter_max_size_gb"
    }
}
