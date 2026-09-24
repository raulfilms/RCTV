package com.nuvio.tv.ui.screens.livetv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LiveTvDataStore
import com.nuvio.tv.domain.model.LiveTvConnection
import com.nuvio.tv.domain.model.LiveTvSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class IptvSourceType { M3U, XTREAM, STALKER }

data class IptvSourcesUiState(
    val sources: List<LiveTvSource> = emptyList(),
    val isAdding: Boolean = false,
    val addType: IptvSourceType = IptvSourceType.M3U,
    val nameInput: String = "",
    val m3uUrlInput: String = "",
    val m3uEpgInput: String = "",
    val xtreamServerInput: String = "",
    val xtreamUsernameInput: String = "",
    val xtreamPasswordInput: String = "",
    val stalkerPortalInput: String = "",
    val stalkerMacInput: String = ""
)

/**
 * Manages every configured [LiveTvSource] (M3U playlists, Xtream accounts, Stalker portals) at
 * once - the multi-source management UI promised when [LiveTvDataStore] was redesigned to hold a
 * list of sources instead of a single connection (ARVIO merge, phase 1 step 3). The simpler
 * single-source setup flow in [LiveTvScreen] still exists unchanged for a first-time setup; this
 * screen is where a second, third, etc. source gets added, and where any source can be toggled
 * on/off or removed.
 */
@HiltViewModel
class IptvSourcesViewModel @Inject constructor(
    private val liveTvDataStore: LiveTvDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(IptvSourcesUiState())
    val uiState: StateFlow<IptvSourcesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            liveTvDataStore.sources.collectLatest { sources ->
                _uiState.update { it.copy(sources = sources) }
            }
        }
    }

    fun startAdding() {
        _uiState.update {
            it.copy(
                isAdding = true,
                addType = IptvSourceType.M3U,
                nameInput = "",
                m3uUrlInput = "",
                m3uEpgInput = "",
                xtreamServerInput = "",
                xtreamUsernameInput = "",
                xtreamPasswordInput = "",
                stalkerPortalInput = "",
                stalkerMacInput = ""
            )
        }
    }

    fun cancelAdding() = _uiState.update { it.copy(isAdding = false) }

    fun selectAddType(type: IptvSourceType) = _uiState.update { it.copy(addType = type) }

    fun onNameChange(value: String) = _uiState.update { it.copy(nameInput = value) }
    fun onM3uUrlChange(value: String) = _uiState.update { it.copy(m3uUrlInput = value) }
    fun onM3uEpgChange(value: String) = _uiState.update { it.copy(m3uEpgInput = value) }
    fun onXtreamServerChange(value: String) = _uiState.update { it.copy(xtreamServerInput = value) }
    fun onXtreamUsernameChange(value: String) = _uiState.update { it.copy(xtreamUsernameInput = value) }
    fun onXtreamPasswordChange(value: String) = _uiState.update { it.copy(xtreamPasswordInput = value) }
    fun onStalkerPortalChange(value: String) = _uiState.update { it.copy(stalkerPortalInput = value) }
    fun onStalkerMacChange(value: String) = _uiState.update { it.copy(stalkerMacInput = value) }

    /** Validates the form for the currently selected [IptvSourcesUiState.addType] and, if valid, adds a new enabled source. No-op otherwise. */
    fun saveNewSource() {
        val state = _uiState.value
        val connection: LiveTvConnection = when (state.addType) {
            IptvSourceType.M3U -> {
                val url = state.m3uUrlInput.trim()
                if (url.isBlank()) return
                LiveTvConnection.M3u(playlistUrl = url, epgUrl = state.m3uEpgInput.trim().ifBlank { null })
            }
            IptvSourceType.XTREAM -> {
                val server = state.xtreamServerInput.trim()
                val user = state.xtreamUsernameInput.trim()
                val pass = state.xtreamPasswordInput.trim()
                if (server.isBlank() || user.isBlank() || pass.isBlank()) return
                LiveTvConnection.Xtream(serverUrl = server, username = user, password = pass)
            }
            IptvSourceType.STALKER -> {
                val portal = state.stalkerPortalInput.trim()
                val mac = state.stalkerMacInput.trim()
                if (portal.isBlank() || mac.isBlank()) return
                LiveTvConnection.Stalker(portalUrl = portal, macAddress = mac.uppercase())
            }
        }
        val name = state.nameInput.trim().ifBlank { defaultNameFor(state.addType, state.sources.size) }
        viewModelScope.launch {
            liveTvDataStore.addSource(
                LiveTvSource(id = liveTvDataStore.newSourceId(), name = name, connection = connection, enabled = true)
            )
        }
        _uiState.update { it.copy(isAdding = false) }
    }

    private fun defaultNameFor(type: IptvSourceType, existingCount: Int): String = when (type) {
        IptvSourceType.M3U -> "M3U Playlist ${existingCount + 1}"
        IptvSourceType.XTREAM -> "Xtream Account ${existingCount + 1}"
        IptvSourceType.STALKER -> "Stalker Portal ${existingCount + 1}"
    }

    fun setSourceEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { liveTvDataStore.setSourceEnabled(id, enabled) }
    }

    fun removeSource(id: String) {
        viewModelScope.launch { liveTvDataStore.removeSource(id) }
    }
}
