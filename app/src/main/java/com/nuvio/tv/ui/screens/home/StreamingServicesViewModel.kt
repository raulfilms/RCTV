package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.rctvcontrol.RctvPublicFileParser
import com.nuvio.tv.core.rctvcontrol.RctvPublicFiles
import com.nuvio.tv.data.streaming.StreamingServicesRepository
import com.nuvio.tv.domain.model.StreamingService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Feeds the "Streaming Services" row on the home screen. */
@HiltViewModel
class StreamingServicesViewModel @Inject constructor(
    private val repository: StreamingServicesRepository,
    /** RCTV Control: the row's order and hidden tiles, as the owner set them on the website. */
    publicFiles: RctvPublicFiles
) : ViewModel() {

    // The tiles show right away; where each one leads is filled in after the TMDB lookup.
    private val _services = MutableStateFlow(repository.services)
    val services: StateFlow<List<StreamingService>> = combine(_services, publicFiles.streamingLayout) { services, layout ->
        RctvPublicFileParser.ordered(services, layout) { it.key }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RctvPublicFileParser.ordered(repository.services, publicFiles.streamingLayout.value) { it.key })

    private var resolveJob: Job? = null

    init {
        resolve()
    }

    /** Looks up the tiles that don't have a destination yet (e.g. after a network error). */
    fun resolve() {
        if (resolveJob?.isActive == true) return
        resolveJob = viewModelScope.launch {
            _services.value = repository.resolveTargets()
        }
    }
}
