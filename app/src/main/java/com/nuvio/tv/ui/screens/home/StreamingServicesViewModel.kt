package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.streaming.StreamingServicesRepository
import com.nuvio.tv.domain.model.StreamingService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Feeds the "Streaming Services" row on the home screen. */
@HiltViewModel
class StreamingServicesViewModel @Inject constructor(
    private val repository: StreamingServicesRepository
) : ViewModel() {

    private val _services = MutableStateFlow<List<StreamingService>>(emptyList())
    val services: StateFlow<List<StreamingService>> = _services.asStateFlow()

    init {
        viewModelScope.launch {
            _services.value = repository.services()
        }
    }
}
