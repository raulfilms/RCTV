package com.nuvio.tv.ui.screens.studios

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.streaming.StreamingServicesRepository
import com.nuvio.tv.domain.model.StreamingService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A page of studio tiles opened from a Home tile (e.g. Independent Film Company): the tiles show right
 * away, and where each one leads (its TMDB company page) is filled in after the lookup.
 */
@HiltViewModel
class StudioGroupViewModel @Inject constructor(
    private val repository: StreamingServicesRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val groupKey: String? = savedStateHandle.get<String>("group")

    /** The Home tile that opened this page: its logo heads the page. */
    val owner: StreamingService? = repository.groupOwner(groupKey)

    private val _studios = MutableStateFlow(repository.group(groupKey))
    val studios: StateFlow<List<StreamingService>> = _studios.asStateFlow()

    private var resolveJob: Job? = null

    init {
        resolve()
    }

    /** Looks up the studios that don't have a destination yet (e.g. after a network error). */
    fun resolve() {
        if (resolveJob?.isActive == true) return
        resolveJob = viewModelScope.launch {
            _studios.value = repository.resolveGroup(groupKey)
        }
    }
}
