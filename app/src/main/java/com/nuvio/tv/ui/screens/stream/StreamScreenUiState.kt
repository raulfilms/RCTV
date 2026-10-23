package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.SourceChipItem
import com.nuvio.tv.ui.components.SourceChipStatus

data class StreamScreenUiState(
    val isLoading: Boolean = true,
    val isDirectAutoPlayFlow: Boolean = false,
    val showDirectAutoPlayOverlay: Boolean = false,
    val autoPlayDecided: Boolean = false,
    val externalPlayerOverlayVisible: Boolean = false,
    val directAutoPlayMessage: String? = null,
    val directAutoPlayProgress: Float? = null,
    val videoId: String = "",
    val contentType: String = "",
    val title: String = "",
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    // Episode-specific fields
    val season: Int? = null,
    val episode: Int? = null,
    val episodeName: String? = null,
    val runtime: Int? = null,
    // Movie-specific fields
    val genres: String? = null,
    val year: String? = null,
    val addonStreams: List<AddonStreams> = emptyList(),
    val allStreams: List<Stream> = emptyList(),
    val selectedAddonFilter: String? = null, // null means "All"
    val filteredStreams: List<Stream> = emptyList(),
    val availableAddons: List<String> = emptyList(),
    val sourceChips: List<SourceChipItem> = emptyList(),
    /**
     * RCTV Smart Scraper: the sources as shown, the first [smartSourceCount] of them the smart
     * ones (best first, each file once), then all of them; in manual mode, the ones that fit the
     * chosen filters, in the chosen order. [filteredStreams] is their streams.
     */
    val sourceItems: List<SmartSourceItem> = emptyList(),
    val smartSourceCount: Int = 0,
    val smartView: com.nuvio.tv.core.smartscraper.SmartSourceViewSettings = com.nuvio.tv.core.smartscraper.SmartSourceViewSettings(),
    /** What to try, one after another, when a source fails to start (the smart order). */
    val smartFallbackChain: List<Stream> = emptyList(),
    val autoPlayStream: Stream? = null,
    val autoPlayPlaybackInfo: StreamPlaybackInfo? = null,
    val error: String? = null,
    val playbackErrorMessage: String? = null
) {
    val isEpisode: Boolean get() = season != null && episode != null
}

sealed class StreamScreenEvent {
    data class OnAddonFilterSelected(val addonName: String?) : StreamScreenEvent()
    data class OnStreamSelected(val stream: Stream) : StreamScreenEvent()
    data object OnAutoPlayConsumed : StreamScreenEvent()
    data object OnRefresh : StreamScreenEvent()
    data object OnRetry : StreamScreenEvent()
    data object OnBackPress : StreamScreenEvent()
    data object OnResume : StreamScreenEvent()
}
