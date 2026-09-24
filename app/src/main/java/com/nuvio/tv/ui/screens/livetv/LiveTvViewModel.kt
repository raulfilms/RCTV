package com.nuvio.tv.ui.screens.livetv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LiveTvDataStore
import com.nuvio.tv.data.remote.stalker.StalkerClient
import com.nuvio.tv.data.remote.xtream.XtreamClient
import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.LiveTvConnection
import com.nuvio.tv.domain.model.LiveTvSource
import com.nuvio.tv.ui.util.M3uPlaylistParser
import com.nuvio.tv.ui.util.XmltvParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Named

enum class LiveTvSetupMethod { M3U, XTREAM, STALKER }

data class LiveTvUiState(
    val connection: LiveTvConnection? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val channels: List<LiveTvChannel> = emptyList(),
    val groups: List<String> = emptyList(),
    val selectedGroup: String? = null,
    val showGuide: Boolean = false,
    /** Programs per EPG channel id, each list sorted by start time. Empty when no EPG is available. */
    val epgByChannel: Map<String, List<EpgProgram>> = emptyMap(),
    val isEpgLoading: Boolean = false,
    val nowMs: Long = System.currentTimeMillis(),
    val selectedProgramChannel: LiveTvChannel? = null,
    val selectedProgram: EpgProgram? = null,
    val setupMethod: LiveTvSetupMethod = LiveTvSetupMethod.M3U,
    val m3uUrlInput: String = "",
    val m3uEpgInput: String = "",
    val xtreamServerInput: String = "",
    val xtreamUsernameInput: String = "",
    val xtreamPasswordInput: String = "",
    val stalkerPortalInput: String = "",
    val stalkerMacInput: String = "",
    val channelSearchInput: String = ""
) {
    val visibleChannels: List<LiveTvChannel>
        get() {
            val query = channelSearchInput.trim()
            return channels
                .filter { selectedGroup == null || it.groupTitle == selectedGroup }
                .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        }

    /** How many channels each group has, for the category sidebar - independent of [selectedGroup] so counts don't collapse to the selected group's own size. */
    val groupCounts: Map<String, Int>
        get() {
            val query = channelSearchInput.trim()
            val searched = if (query.isBlank()) channels else channels.filter { it.name.contains(query, ignoreCase = true) }
            return searched.mapNotNull { it.groupTitle }.groupingBy { it }.eachCount()
        }

    val hasEpg: Boolean get() = epgByChannel.isNotEmpty()

    fun currentProgram(channel: LiveTvChannel): EpgProgram? {
        val id = channel.epgChannelId ?: return null
        return epgByChannel[id]?.firstOrNull { it.isAiringAt(nowMs) }
    }

    fun nextProgram(channel: LiveTvChannel): EpgProgram? {
        val id = channel.epgChannelId ?: return null
        return epgByChannel[id]?.firstOrNull { it.startMs > nowMs }
    }

    fun upcomingPrograms(channel: LiveTvChannel, limit: Int = 5): List<EpgProgram> {
        val id = channel.epgChannelId ?: return emptyList()
        return epgByChannel[id]?.filter { it.endMs > nowMs }?.take(limit) ?: emptyList()
    }
}

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val liveTvDataStore: LiveTvDataStore,
    private val xtreamClient: XtreamClient,
    private val stalkerClient: StalkerClient,
    @Named("addonPermissive") private val okHttpClient: OkHttpClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(LiveTvUiState())
    val uiState: StateFlow<LiveTvUiState> = _uiState.asStateFlow()

    /** Cached Stalker sessions, keyed by [LiveTvSource.id], so channel/EPG loads don't re-handshake a portal every time. */
    private val stalkerSessions = mutableMapOf<String, StalkerClient.Session>()

    /** The most recently seen source list, kept around so [retry] doesn't need a parameter. */
    private var currentSources: List<LiveTvSource> = emptyList()

    init {
        viewModelScope.launch {
            liveTvDataStore.sources.collect { sources ->
                currentSources = sources
                val enabled = sources.filter { it.enabled }
                _uiState.update { it.copy(connection = enabled.firstOrNull()?.connection) }
                if (enabled.isNotEmpty()) {
                    loadChannels(enabled)
                } else {
                    stalkerSessions.clear()
                    _uiState.update {
                        it.copy(
                            channels = emptyList(),
                            groups = emptyList(),
                            selectedGroup = null,
                            error = null,
                            epgByChannel = emptyMap(),
                            showGuide = false
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(60_000L)
                _uiState.update { it.copy(nowMs = System.currentTimeMillis()) }
            }
        }
    }

    /**
     * Resolves a channel's actual playable stream URL right before playback starts. A no-op for
     * M3U/Xtream channels (already a static URL); for a Stalker channel, [LiveTvChannel.streamUrl]
     * holds the portal's short-lived `cmd` token instead, so it's exchanged for a real URL here.
     * Falls back to the raw value on any failure so playback at least attempts to start.
     */
    suspend fun resolvePlaybackUrl(channel: LiveTvChannel): String {
        val sourceId = channel.sourceId ?: return channel.streamUrl
        val connection = currentSources.firstOrNull { it.id == sourceId }?.connection
        if (connection !is LiveTvConnection.Stalker) return channel.streamUrl
        return withContext(Dispatchers.IO) {
            runCatching {
                val session = stalkerSessions.getOrPut(sourceId) { stalkerClient.authenticate(connection) }
                stalkerClient.resolveStreamUrl(session, channel.streamUrl) ?: channel.streamUrl
            }.getOrDefault(channel.streamUrl)
        }
    }

    fun selectSetupMethod(method: LiveTvSetupMethod) {
        _uiState.update { it.copy(setupMethod = method) }
    }

    fun onM3uUrlChange(value: String) = _uiState.update { it.copy(m3uUrlInput = value) }
    fun onM3uEpgChange(value: String) = _uiState.update { it.copy(m3uEpgInput = value) }
    fun onXtreamServerChange(value: String) = _uiState.update { it.copy(xtreamServerInput = value) }
    fun onXtreamUsernameChange(value: String) = _uiState.update { it.copy(xtreamUsernameInput = value) }
    fun onXtreamPasswordChange(value: String) = _uiState.update { it.copy(xtreamPasswordInput = value) }
    fun onStalkerPortalChange(value: String) = _uiState.update { it.copy(stalkerPortalInput = value) }
    fun onStalkerMacChange(value: String) = _uiState.update { it.copy(stalkerMacInput = value) }

    fun connectM3u() {
        val url = _uiState.value.m3uUrlInput.trim()
        if (url.isBlank()) return
        val epg = _uiState.value.m3uEpgInput.trim().ifBlank { null }
        viewModelScope.launch { liveTvDataStore.setM3uConnection(url, epg) }
    }

    fun connectXtream() {
        val state = _uiState.value
        val server = state.xtreamServerInput.trim()
        val user = state.xtreamUsernameInput.trim()
        val pass = state.xtreamPasswordInput.trim()
        if (server.isBlank() || user.isBlank() || pass.isBlank()) return
        viewModelScope.launch { liveTvDataStore.setXtreamConnection(server, user, pass) }
    }

    fun connectStalker() {
        val state = _uiState.value
        val portal = state.stalkerPortalInput.trim()
        val mac = state.stalkerMacInput.trim()
        if (portal.isBlank() || mac.isBlank()) return
        viewModelScope.launch { liveTvDataStore.setStalkerConnection(portal, mac) }
    }

    fun disconnect() {
        viewModelScope.launch {
            stalkerSessions.clear()
            liveTvDataStore.clearConnection()
            _uiState.update {
                it.copy(
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
    }

    fun selectGroup(group: String?) {
        _uiState.update { it.copy(selectedGroup = group) }
    }

    fun onChannelSearchChange(value: String) {
        _uiState.update { it.copy(channelSearchInput = value) }
    }

    fun setShowGuide(show: Boolean) {
        _uiState.update { it.copy(showGuide = show) }
    }

    fun openProgramDetails(channel: LiveTvChannel, program: EpgProgram) {
        _uiState.update { it.copy(selectedProgramChannel = channel, selectedProgram = program) }
    }

    fun dismissProgramDetails() {
        _uiState.update { it.copy(selectedProgramChannel = null, selectedProgram = null) }
    }

    fun retry() {
        currentSources.filter { it.enabled }.takeIf { it.isNotEmpty() }?.let { sources ->
            viewModelScope.launch { loadChannels(sources) }
        }
    }

    /** Fetches one source's channels; never throws - a failing source just contributes nothing to the merged list. */
    private suspend fun loadSourceChannels(source: LiveTvSource): Result<List<LiveTvChannel>> = runCatching {
        val rawChannels = when (val connection = source.connection) {
            is LiveTvConnection.M3u -> {
                val request = Request.Builder().url(connection.playlistUrl).build()
                val body = okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
                M3uPlaylistParser.parse(body)
            }
            is LiveTvConnection.Xtream -> xtreamClient.fetchChannels(connection)
            is LiveTvConnection.Stalker -> {
                val session = stalkerSessions.getOrPut(source.id) { stalkerClient.authenticate(connection) }
                stalkerClient.fetchChannels(session)
            }
        }
        // Prefix ids with the source id so channels from several playlists/accounts never collide,
        // and tag each with its source so resolvePlaybackUrl/loadEpg know where it came from.
        rawChannels.map { channel ->
            channel.copy(
                id = "${source.id}:${channel.id}",
                epgChannelId = channel.epgChannelId?.let { "${source.id}:$it" },
                sourceId = source.id
            )
        }
    }

    /** Loads and merges channels from every enabled source. A source that fails doesn't blank out the others. */
    private suspend fun loadChannels(sources: List<LiveTvSource>) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        val results = withContext(Dispatchers.IO) {
            sources.map { source -> source to loadSourceChannels(source) }
        }
        val channels = results.flatMap { (_, result) -> result.getOrDefault(emptyList()) }
        val failures = results.mapNotNull { (source, result) -> result.exceptionOrNull()?.let { source.name to it } }

        val groups = channels.mapNotNull { it.groupTitle }.distinct().sorted()
        val error = when {
            channels.isEmpty() && failures.isNotEmpty() ->
                failures.first().second.message ?: "Couldn't load channels for ${failures.first().first}."
            channels.isEmpty() -> "No channels found for this connection."
            failures.isNotEmpty() -> "Loaded channels, but ${failures.size} source(s) failed: ${failures.joinToString { it.first }}."
            else -> null
        }
        _uiState.update {
            it.copy(
                isLoading = false,
                error = error,
                channels = channels,
                groups = groups,
                selectedGroup = it.selectedGroup?.takeIf { g -> g in groups }
            )
        }
        if (channels.isNotEmpty()) loadEpg(sources)
    }

    /** Fetches and prefixes one source's EPG programs (see [loadSourceChannels] for the id-prefixing reasoning). Stalker EPG is deferred to a later phase. */
    private suspend fun loadSourceEpg(source: LiveTvSource): Map<String, List<EpgProgram>> {
        val connection = source.connection
        val epgUrl = when (connection) {
            is LiveTvConnection.Xtream -> null // fetched via xtreamClient.fetchXmltv below
            is LiveTvConnection.M3u -> connection.epgUrl
            is LiveTvConnection.Stalker -> return emptyMap()
        }
        if (connection is LiveTvConnection.M3u && epgUrl == null) return emptyMap()

        return runCatching {
            val xmltvText = when (connection) {
                is LiveTvConnection.Xtream -> xtreamClient.fetchXmltv(connection)
                is LiveTvConnection.M3u -> {
                    val request = Request.Builder().url(requireNotNull(epgUrl)).build()
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("HTTP ${response.code}")
                        response.body?.string().orEmpty()
                    }
                }
                is LiveTvConnection.Stalker -> return emptyMap()
            }
            XmltvParser.parse(xmltvText)
                .groupBy { "${source.id}:${it.channelId}" }
                .mapValues { (_, list) -> list.sortedBy { it.startMs } }
        }.getOrDefault(emptyMap())
    }

    private suspend fun loadEpg(sources: List<LiveTvSource>) {
        _uiState.update { it.copy(isEpgLoading = true) }
        val merged = withContext(Dispatchers.IO) {
            sources.fold(emptyMap<String, List<EpgProgram>>()) { acc, source -> acc + loadSourceEpg(source) }
        }
        // EPG is a nice-to-have on top of channel playback; an empty result (whether every source
        // has none, or every fetch failed) never blocks watching live TV.
        _uiState.update { it.copy(isEpgLoading = false, epgByChannel = merged, nowMs = System.currentTimeMillis()) }
    }
}
