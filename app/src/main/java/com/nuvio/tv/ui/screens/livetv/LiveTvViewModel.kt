package com.nuvio.tv.ui.screens.livetv

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LiveTvDataStore
import com.nuvio.tv.data.remote.stalker.StalkerClient
import com.nuvio.tv.data.remote.xtream.XtreamClient
import com.nuvio.tv.domain.model.CustomLiveChannel
import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.LiveTvConnection
import com.nuvio.tv.domain.model.LiveTvSource
import com.nuvio.tv.ui.util.M3uPlaylistParser
import com.nuvio.tv.ui.util.XmltvGuide
import com.nuvio.tv.ui.util.XmltvParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.nuvio.tv.R
import com.nuvio.tv.data.livetv.LiveLibraryResult
import com.nuvio.tv.data.livetv.LiveProgramLibrary
import com.nuvio.tv.data.livetv.LiveProgramMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Named

enum class LiveTvSetupMethod { M3U, XTREAM, STALKER }

/** What to add from a program on now: the episode airing, the whole series, or the movie. */
enum class LiveLibraryChoice { EPISODE, SERIES, MOVIE }

/** The "add to library" question for a program on now, and its TMDB lookup. */
data class LiveLibraryPrompt(
    val channel: LiveTvChannel,
    val program: EpgProgram,
    /** The guide calls it a movie (until TMDB says what it is). */
    val looksLikeMovie: Boolean,
    /** TMDB is being asked. */
    val isMatching: Boolean = true,
    /** What TMDB has (null: not found, once [isMatching] is over). */
    val match: LiveProgramMatch? = null,
    /** TMDB couldn't be reached. */
    val lookupFailed: Boolean = false,
    /** An answer is being saved. */
    val isAdding: Boolean = false
) {
    /** Movie (one choice) or series (episode or series). */
    val isMovie: Boolean get() = match?.isMovie ?: looksLikeMovie
}

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
    val channelSearchInput: String = "",
    /**
     * True while the guide shows the built-in sample EPG (no IPTV source added yet): its channels
     * come from the EPG itself and have no stream, so they can't be played.
     */
    val isPreviewGuide: Boolean = false,
    /** Why the sample EPG couldn't load, if it failed. */
    val previewError: String? = null,
    /** Guide's first menu: how to list channels (Live Now, A to Z, Custom) or which category. */
    val guideBrowse: GuideBrowse = GuideBrowse.LIVE_NOW,
    /** Guide filter: ISO country code, or null for every country. */
    val guideCountry: String? = null,
    /** Channels the user added to "Custom". */
    val customChannelIds: Set<String> = emptySet(),
    /** Guide categories of each channel id. */
    val channelCategories: Map<String, Set<GuideBrowse>> = emptyMap()
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

/**
 * A guide program's artwork from TMDB, as the rest of the app shows titles: a backdrop with the
 * title already on it ([titledBackdrop]), else the plain [backdrop] with the title's [logo] (or its
 * [name]) laid over it. [NONE]: TMDB has no such title (a newscast, a game...).
 */
data class GuideProgramArt(
    val name: String,
    val backdrop: String?,
    val titledBackdrop: String?,
    val logo: String?
) {
    val hasArt: Boolean get() = backdrop != null || titledBackdrop != null

    companion object {
        val NONE = GuideProgramArt(name = "", backdrop = null, titledBackdrop = null, logo = null)
    }
}

/** Where the Guide puts focus when it shows again: a channel's logo, or what's on now on it. */
data class GuideReturnFocus(val channelId: String, val onLogo: Boolean)

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val liveTvDataStore: LiveTvDataStore,
    private val xtreamClient: XtreamClient,
    private val stalkerClient: StalkerClient,
    @Named("addonPermissive") private val okHttpClient: OkHttpClient,
    private val liveProgramLibrary: LiveProgramLibrary,
    private val titleArtService: com.nuvio.tv.core.tmdb.TmdbTitleArtService
) : ViewModel() {

    // ------------------------------------------------------------------ guide artwork

    /** Each program title's TMDB artwork, once looked up (by its cleaned title). */
    private val programArtCache = java.util.concurrent.ConcurrentHashMap<String, GuideProgramArt>()

    private fun programArtKey(program: EpgProgram): String =
        LiveProgramLibrary.cleanTitle(program.title).lowercase(java.util.Locale.ROOT) + "|" +
            (program.categories + listOfNotNull(program.category)).any { "movie" in it.lowercase(java.util.Locale.ROOT) || "film" in it.lowercase(java.util.Locale.ROOT) }

    /** The program's TMDB artwork if it was already looked up ([GuideProgramArt.NONE]: none); null if not yet. */
    fun cachedProgramArt(program: EpgProgram): GuideProgramArt? = programArtCache[programArtKey(program)]

    /**
     * The program's TMDB artwork: its movie or series (by its exact name), with the backdrop, the
     * title card and the logo the rest of the app uses. [GuideProgramArt.NONE] when TMDB has none.
     */
    suspend fun programArt(program: EpgProgram): GuideProgramArt {
        val key = programArtKey(program)
        programArtCache[key]?.let { return it }
        val match = try {
            liveProgramLibrary.findTitle(program)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // TMDB couldn't be reached: the guide's own picture for now, and it's asked again later.
            return GuideProgramArt.NONE
        } ?: return GuideProgramArt.NONE.also { programArtCache[key] = it }
        val preview = com.nuvio.tv.domain.model.MetaPreview(
            id = match.itemId,
            type = if (match.isMovie) com.nuvio.tv.domain.model.ContentType.MOVIE else com.nuvio.tv.domain.model.ContentType.SERIES,
            name = match.name,
            poster = match.poster,
            posterShape = com.nuvio.tv.domain.model.PosterShape.LANDSCAPE,
            background = match.backdrop,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList()
        )
        val titleArt = try {
            titleArtService.artFor(preview)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val art = GuideProgramArt(
            name = match.name,
            backdrop = match.backdrop,
            titledBackdrop = titleArt?.titledBackdrop,
            logo = titleArt?.logo
        )
        // TMDB's images couldn't be reached: the backdrop shows now, and the logo is asked for again later.
        if (titleArt != null) programArtCache[key] = art
        return art
    }

    // ------------------------------------------------------------------ add to library

    private val _libraryPrompt = MutableStateFlow<LiveLibraryPrompt?>(null)

    /** The "add the episode or the series?" question for what's on now (Select held on it). */
    val libraryPrompt: StateFlow<LiveLibraryPrompt?> = _libraryPrompt.asStateFlow()

    /** The TMDB lookup of the program Select was last held on. */
    private var libraryMatch: Deferred<LiveProgramMatch?>? = null

    /**
     * Select held on what's on now. A movie goes straight into the library, with nothing asked; a
     * series asks right away whether to add the episode or the whole series, while TMDB is looked
     * up. When TMDB turns out to have a movie, it's added and the question closes on its own.
     */
    fun openLibraryPrompt(channel: LiveTvChannel, program: EpgProgram) {
        libraryMatch?.cancel()
        val looksLikeMovie = (program.categories + listOfNotNull(program.category))
            .any { val word = it.lowercase(java.util.Locale.ROOT); "movie" in word || "film" in word }
        val lookup = viewModelScope.async { liveProgramLibrary.match(program) }
        libraryMatch = lookup
        _libraryPrompt.value = if (looksLikeMovie) null else LiveLibraryPrompt(channel, program, looksLikeMovie = false)
        viewModelScope.launch {
            val result = try {
                Result.success(lookup.await())
            } catch (e: CancellationException) {
                return@launch
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (libraryMatch !== lookup) return@launch
            val match = result.getOrNull()
            when {
                // A movie: in it goes, nothing to ask (unless an answer is already being saved).
                match?.isMovie == true -> {
                    if (_libraryPrompt.value?.isAdding != true) addMatched(match, LiveLibraryChoice.MOVIE, program)
                }
                // The guide called it a movie, but TMDB doesn't have it (or couldn't be reached).
                looksLikeMovie && match == null -> {
                    libraryMatch = null
                    toast(
                        if (result.isFailure) appContext.getString(R.string.livetv_library_lookup_failed)
                        else appContext.getString(R.string.livetv_library_not_found, LiveProgramLibrary.cleanTitle(program.title))
                    )
                }
                // The guide called it a movie, but on TMDB it's a series: ask after all.
                looksLikeMovie -> _libraryPrompt.value = LiveLibraryPrompt(
                    channel = channel,
                    program = program,
                    looksLikeMovie = false,
                    isMatching = false,
                    match = match
                )
                else -> _libraryPrompt.update { prompt ->
                    if (prompt?.program != program) prompt
                    else prompt.copy(isMatching = false, match = match, lookupFailed = result.isFailure)
                }
            }
        }
    }

    fun dismissLibraryPrompt() {
        libraryMatch?.cancel()
        libraryMatch = null
        _libraryPrompt.value = null
    }

    /** The answer to the question: the episode or the series (once TMDB has been asked). */
    fun addFromLibraryPrompt(choice: LiveLibraryChoice) {
        val prompt = _libraryPrompt.value ?: return
        if (prompt.isAdding) return
        val lookup = libraryMatch ?: return
        _libraryPrompt.update { it?.copy(isAdding = true) }
        viewModelScope.launch {
            val match = try {
                lookup.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (match == null) {
                toast(appContext.getString(R.string.livetv_library_not_found, LiveProgramLibrary.cleanTitle(prompt.program.title)))
                _libraryPrompt.update { it?.copy(isAdding = false, isMatching = false) }
                return@launch
            }
            addMatched(match, choice, prompt.program)
        }
    }

    /** Saves [choice] of [match] to the library, says how it went, and closes the question. */
    private suspend fun addMatched(match: LiveProgramMatch, choice: LiveLibraryChoice, program: EpgProgram) {
        val title = match.name
        val wantsEpisode = choice == LiveLibraryChoice.EPISODE && !match.isMovie
        if (wantsEpisode && match.episode == null) {
            // Which episode it is couldn't be told: the question stays, for the series.
            toast(appContext.getString(R.string.livetv_library_episode_not_found, title))
            _libraryPrompt.update { it?.copy(isAdding = false) }
            return
        }
        _libraryPrompt.update { prompt -> if (prompt?.program == program) prompt.copy(isAdding = true) else prompt }
        val result = if (wantsEpisode) liveProgramLibrary.addEpisode(match) else liveProgramLibrary.addTitle(match)
        val shown = if (wantsEpisode && result != LiveLibraryResult.ADDED_SERIES_INSTEAD) {
            match.episode?.let { appContext.getString(R.string.livetv_library_episode_title, match.name, it.season, it.episode) } ?: title
        } else {
            title
        }
        toast(
            when (result) {
                LiveLibraryResult.ADDED -> appContext.getString(R.string.livetv_library_added, shown)
                LiveLibraryResult.ALREADY_IN_LIBRARY -> appContext.getString(R.string.livetv_library_already, shown)
                LiveLibraryResult.ADDED_SERIES_INSTEAD -> appContext.getString(R.string.livetv_library_series_instead, title)
                LiveLibraryResult.NOT_FOUND -> appContext.getString(R.string.livetv_library_not_found, title)
                LiveLibraryResult.FAILED -> appContext.getString(R.string.livetv_library_failed)
            }
        )
        if (result == LiveLibraryResult.FAILED) {
            _libraryPrompt.update { prompt -> if (prompt?.program == program) prompt.copy(isAdding = false) else prompt }
        } else if (_libraryPrompt.value?.program == program) {
            dismissLibraryPrompt()
        }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    // Starts as loading: either the user's sources or the sample guide load right away.
    private val _uiState = MutableStateFlow(LiveTvUiState(isLoading = true))
    val uiState: StateFlow<LiveTvUiState> = _uiState.asStateFlow()

    /**
     * Set when the Guide is left for a channel page or the player, so coming back puts focus on
     * that channel again instead of the first one. Read once by the Guide when it shows.
     */
    var guideReturnFocus: GuideReturnFocus? = null

    /** The sample guide, kept once loaded so coming back to the Guide is instant. */
    private var previewGuide: XmltvGuide? = null
    private var previewJob: Job? = null

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
                    // Real sources replace the sample guide.
                    previewJob?.cancel()
                    _uiState.update { it.copy(isPreviewGuide = false, previewError = null) }
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
                    startPreviewGuide()
                }
            }
        }
        viewModelScope.launch {
            liveTvDataStore.customChannelIds.collect { ids ->
                _uiState.update { it.copy(customChannelIds = ids) }
            }
        }
        // Home's Top Channels shows the "Custom" channels: keep their names and logos current.
        viewModelScope.launch {
            _uiState.map { it.channels }.distinctUntilChanged().collect { channels ->
                try {
                    liveTvDataStore.refreshCustomChannels(channels) { name -> stripCountryTag(name) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Saving failed: Home keeps what it has.
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

    fun setGuideBrowse(browse: GuideBrowse) {
        _uiState.update { it.copy(guideBrowse = browse) }
    }

    /** Adds the channel to the Guide's "Custom" list (Home's Top Channels too), or takes it out. */
    fun toggleCustomChannel(channel: LiveTvChannel) {
        val position = _uiState.value.channels.indexOfFirst { it.id == channel.id }
        val info = CustomLiveChannel(
            id = channel.id,
            name = stripCountryTag(channel.name),
            logoUrl = channel.logoUrl,
            position = if (position >= 0) position else Int.MAX_VALUE
        )
        viewModelScope.launch { liveTvDataStore.toggleCustomChannel(channel.id, info) }
    }

    /** Sorts every channel into the Guide's categories from its programs, name and group. */
    private fun categorize(channels: List<LiveTvChannel>, epg: Map<String, List<EpgProgram>>): Map<String, Set<GuideBrowse>> =
        LiveTvGuideCategories.classify(channels) { channel ->
            channel.epgChannelId?.let { epg[it] }.orEmpty()
        }

    fun setGuideCountry(country: String?) {
        _uiState.update { it.copy(guideCountry = country) }
    }

    fun openProgramDetails(channel: LiveTvChannel, program: EpgProgram) {
        _uiState.update { it.copy(selectedProgramChannel = channel, selectedProgram = program) }
    }

    fun dismissProgramDetails() {
        _uiState.update { it.copy(selectedProgramChannel = null, selectedProgram = null) }
    }

    /** Leaves the sample guide for the IPTV setup form. */
    fun showSetupForm() {
        previewJob?.cancel()
        _uiState.update {
            it.copy(
                isLoading = false,
                isPreviewGuide = false,
                previewError = null,
                channels = emptyList(),
                epgByChannel = emptyMap(),
                showGuide = false,
                selectedProgramChannel = null,
                selectedProgram = null
            )
        }
    }

    fun retry() {
        val enabled = currentSources.filter { it.enabled }
        if (enabled.isEmpty()) {
            startPreviewGuide()
        } else {
            viewModelScope.launch { loadChannels(enabled) }
        }
    }

    /**
     * Sample guide for trying the Guide's layout without an IPTV account: channels, logos and
     * programs all come from a public XMLTV feed ([PREVIEW_EPG_URL]). Shown only while no IPTV
     * source is added.
     */
    private fun startPreviewGuide() {
        if (previewJob?.isActive == true) return
        previewGuide?.let { cached ->
            applyPreviewGuide(cached)
            return
        }
        previewJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, previewError = null, isPreviewGuide = true) }
            val nowMs = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                // The app's own channel logos (unpacked once).
                ChannelLogoLibrary.ensureReady(appContext)
                runCatching {
                    val request = Request.Builder().url(PREVIEW_EPG_URL).build()
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("HTTP ${response.code}")
                        val body = response.body ?: error("Empty response")
                        XmltvParser.parseStream(
                            input = body.byteStream(),
                            fromMs = nowMs - EPG_WINDOW_PAST_MS,
                            toMs = nowMs + EPG_WINDOW_AHEAD_MS,
                            includeChannels = true,
                            shouldContinue = { isActive }
                        )
                    }
                }
            }
            if (!isActive) return@launch
            result
                .onSuccess { guide ->
                    previewGuide = guide
                    applyPreviewGuide(guide)
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isPreviewGuide = false,
                            previewError = error.message ?: error.javaClass.simpleName
                        )
                    }
                }
        }
    }

    private fun applyPreviewGuide(guide: XmltvGuide) {
        val epg = guide.programs
            .groupBy { "$PREVIEW_SOURCE_ID:${it.channelId}" }
            .mapValues { (_, list) -> list.sortedBy { it.startMs } }
        val channels = guide.channels.map { channel ->
            val key = "$PREVIEW_SOURCE_ID:${channel.id}"
            // "US - FOX News Detroit" -> "FOX News Detroit": the country tag adds nothing here.
            val name = stripCountryTag(channel.displayName)
            LiveTvChannel(
                id = key,
                name = name,
                streamUrl = "",
                // The app's logo when it has one for this channel, else the guide's own.
                logoUrl = ChannelLogoLibrary.logoUriFor(name, channel.number) ?: channel.iconUrl,
                epgChannelId = key,
                // The sample feed is EPGTalk's US guide.
                country = "US",
                number = channel.number
            )
        }
        // Keep the feed's lineup order; drop channels with nothing in the guide's window, and
        // the sample feed's adult-only channels.
        val withPrograms = channels.filter { channel ->
            val programs = epg[channel.id]
            !programs.isNullOrEmpty() && !LiveTvGuideCategories.isAdultChannel(programs)
        }
        val shown = withPrograms.ifEmpty { channels }
        _uiState.update {
            it.copy(
                isLoading = false,
                error = if (channels.isEmpty()) "No channels found in the sample guide." else null,
                previewError = null,
                isPreviewGuide = true,
                channels = shown,
                channelCategories = categorize(shown, epg),
                groups = emptyList(),
                selectedGroup = null,
                epgByChannel = epg,
                showGuide = true,
                nowMs = System.currentTimeMillis()
            )
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
            ChannelLogoLibrary.ensureReady(appContext)
            sources.map { source -> source to loadSourceChannels(source) }
        }
        val channels = results
            .flatMap { (_, result) -> result.getOrDefault(emptyList()) }
            .map { channel ->
                // Country for the Guide's filter: the playlist's own, or guessed from group/name.
                val country = LiveTvCountry.of(channel)
                // US channels get the app's own logo when it has one (they're made for dark screens).
                val bundledLogo = if (country == null || country == "US") {
                    ChannelLogoLibrary.logoUriFor(stripCountryTag(channel.name), channel.number)
                } else {
                    null
                }
                channel.copy(country = country, logoUrl = bundledLogo ?: channel.logoUrl)
            }
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
                selectedGroup = it.selectedGroup?.takeIf { g -> g in groups },
                guideCountry = it.guideCountry?.takeIf { c -> channels.any { ch -> ch.country == c } },
                // Name/group hints for now; the guide's programs refine this once the EPG loads.
                channelCategories = categorize(channels, emptyMap())
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
                    // Streamed, gzip-aware and limited to the guide's window, so a big national
                    // guide (or an .xml.gz one) doesn't have to fit in memory as one string.
                    val nowMs = System.currentTimeMillis()
                    val request = Request.Builder().url(requireNotNull(epgUrl)).build()
                    val programs = okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("HTTP ${response.code}")
                        val body = response.body ?: error("Empty response")
                        XmltvParser.parseStream(
                            input = body.byteStream(),
                            fromMs = nowMs - EPG_WINDOW_PAST_MS,
                            toMs = nowMs + EPG_WINDOW_AHEAD_MS,
                            includeChannels = false
                        ).programs
                    }
                    return@runCatching programs
                        .groupBy { "${source.id}:${it.channelId}" }
                        .mapValues { (_, list) -> list.sortedBy { it.startMs } }
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
        // With a guide available, the Guide page opens on it.
        _uiState.update {
            it.copy(
                isEpgLoading = false,
                epgByChannel = merged,
                nowMs = System.currentTimeMillis(),
                channelCategories = categorize(it.channels, merged),
                showGuide = if (merged.isNotEmpty() && !it.hasEpg) true else it.showGuide
            )
        }
    }

    companion object {
        /** Public US guide (EPGTalk) used for the sample guide. */
        const val PREVIEW_EPG_URL = "https://raw.githubusercontent.com/acidjesuz/EPGTalk/master/US_guide.xml.gz"
        private const val PREVIEW_SOURCE_ID = "preview"
        /** Programs kept per load: from 2 hours ago to 36 hours ahead. */
        private const val EPG_WINDOW_PAST_MS = 2L * 60 * 60 * 1000
        private const val EPG_WINDOW_AHEAD_MS = 36L * 60 * 60 * 1000
        private val COUNTRY_PREFIX = Regex("^([A-Z]{2,3})\\s*[-:|]\\s+")

        /** "US - FOX News Detroit" -> "FOX News Detroit"; "MTV - Music Television" stays (MTV isn't a country). */
        fun stripCountryTag(name: String): String {
            val match = COUNTRY_PREFIX.find(name) ?: return name
            if (LiveTvCountry.normalize(match.groupValues[1]) == null) return name
            return name.substring(match.range.last + 1).trim().ifBlank { name }
        }
    }
}
