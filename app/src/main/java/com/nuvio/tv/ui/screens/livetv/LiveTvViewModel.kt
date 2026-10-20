package com.nuvio.tv.ui.screens.livetv

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LiveTvDataStore
import com.nuvio.tv.domain.model.CustomLiveChannel
import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.LiveTvConnection
import com.nuvio.tv.domain.model.LiveTvSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.nuvio.tv.R
import com.nuvio.tv.data.livetv.LiveLibraryResult
import com.nuvio.tv.data.livetv.LiveProgramLibrary
import com.nuvio.tv.data.livetv.LiveProgramMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LiveTvSetupMethod { M3U, XTREAM, STALKER }

/**
 * What to add from a program on now: the episode airing, the whole series, the movie, or (not on
 * TMDB) the program itself, to the Library's Events.
 */
enum class LiveLibraryChoice { EPISODE, SERIES, MOVIE, EVENT }

/** The "add to library" question for a program on now, and its TMDB lookup. */
data class LiveLibraryPrompt(
    val channel: LiveTvChannel,
    val program: EpgProgram,
    /** The guide calls it a movie (until TMDB says what it is). */
    val looksLikeMovie: Boolean,
    /** It looks like a game (ESPN is asked too): no episode or series to choose meanwhile. */
    val looksLikeGame: Boolean = false,
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
    // Worked out once per state (not on every redraw that reads them): thousands of channels.
    val visibleChannels: List<LiveTvChannel> by lazy {
        val query = channelSearchInput.trim()
        channels
            .distinctBy { it.id }
            .filter { selectedGroup == null || it.groupTitle == selectedGroup }
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    }

    /** How many channels each group has, for the category sidebar - independent of [selectedGroup] so counts don't collapse to the selected group's own size. */
    val groupCounts: Map<String, Int> by lazy {
        val query = channelSearchInput.trim()
        val searched = if (query.isBlank()) channels else channels.filter { it.name.contains(query, ignoreCase = true) }
        searched.mapNotNull { it.groupTitle }.groupingBy { it }.eachCount()
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

/**
 * What TMDB has for the program whose details are open (a later show chosen in the guide): its
 * movie or series, artwork, and whether it's in the library.
 */
data class ProgramDetailsTmdb(
    /** TMDB is being asked. */
    val isLoading: Boolean = true,
    /** The movie or series (null: TMDB doesn't have it, once [isLoading] is over). */
    val match: LiveProgramMatch? = null,
    /** The game it is, on ESPN (it goes to the Library's Events as the game). */
    val game: com.nuvio.tv.domain.model.SportsEvent? = null,
    val art: GuideProgramArt? = null,
    /** Its movie or series, its game, or (neither) the program itself is in the library. */
    val inLibrary: Boolean = false,
    /** It's being added to the library. */
    val isAdding: Boolean = false
)

/** Where the Guide puts focus when it shows again: a channel's logo, or what's on now on it. */
data class GuideReturnFocus(val channelId: String, val onLogo: Boolean)

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val liveTvDataStore: LiveTvDataStore,
    private val guideLoader: LiveTvGuideLoader,
    private val liveProgramLibrary: LiveProgramLibrary,
    private val liveEventLibrary: LiveEventLibrary,
    private val titleArtService: com.nuvio.tv.core.tmdb.TmdbTitleArtService,
    private val playerSession: com.nuvio.tv.data.livetv.LiveTvPlayerSession
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

    /** The ESPN lookup of the program Select was last held on, when it looks like a game. */
    private var gameMatch: Deferred<com.nuvio.tv.domain.model.SportsEvent?>? = null

    /**
     * Select held on what's on now. A movie goes straight into the library, with nothing asked; a
     * series asks right away whether to add the episode or the whole series, while TMDB is looked
     * up. When TMDB turns out to have a movie, it's added and the question closes on its own. A game
     * (on ESPN) goes to the Library's Events as the game; anything else TMDB doesn't have (a
     * special, an award show) goes there as the program.
     */
    fun openLibraryPrompt(channel: LiveTvChannel, program: EpgProgram) {
        libraryMatch?.cancel()
        gameMatch?.cancel()
        val looksLikeMovie = (program.categories + listOfNotNull(program.category))
            .any { val word = it.lowercase(java.util.Locale.ROOT); "movie" in word || "film" in word }
        val looksLikeGame = !looksLikeMovie && LiveEventLibrary.looksLikeGame(program)
        val lookup = viewModelScope.async { liveProgramLibrary.match(program) }
        val gameLookup = if (looksLikeGame) viewModelScope.async { liveEventLibrary.findGame(program, channel.name) } else null
        libraryMatch = lookup
        gameMatch = gameLookup
        _libraryPrompt.value = if (looksLikeMovie) null else LiveLibraryPrompt(channel, program, looksLikeMovie = false, looksLikeGame = looksLikeGame)
        viewModelScope.launch {
            // A game ESPN has: saved as the game, nothing to ask.
            if (gameLookup != null) {
                val game = try {
                    gameLookup.await()
                } catch (e: CancellationException) {
                    return@launch
                } catch (e: Exception) {
                    null
                }
                if (libraryMatch !== lookup) return@launch
                if (game != null) {
                    if (_libraryPrompt.value?.isAdding == true) return@launch
                    _libraryPrompt.update { prompt -> if (prompt?.program == program) prompt.copy(isAdding = true) else prompt }
                    val result = liveEventLibrary.addGame(game)
                    toastEvent(result, gameName(game))
                    if (result == LiveEventResult.FAILED) {
                        _libraryPrompt.update { prompt -> if (prompt?.program == program) prompt.copy(isAdding = false, isMatching = false) else prompt }
                    } else if (_libraryPrompt.value?.program == program || _libraryPrompt.value == null) {
                        dismissLibraryPrompt()
                    }
                    return@launch
                }
            }
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
                // Not on TMDB (a special, a game ESPN doesn't have): the program, in the Library's Events.
                match == null && result.isSuccess -> {
                    if (_libraryPrompt.value?.isAdding != true) addProgramEvent(channel, program)
                }
                // The guide called it a movie, but TMDB couldn't be reached.
                looksLikeMovie && match == null -> {
                    libraryMatch = null
                    toast(appContext.getString(R.string.livetv_library_lookup_failed))
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
        gameMatch?.cancel()
        gameMatch = null
        _libraryPrompt.value = null
    }

    /** The answer to the question: the episode or the series (once TMDB has been asked). */
    fun addFromLibraryPrompt(choice: LiveLibraryChoice) {
        val prompt = _libraryPrompt.value ?: return
        if (prompt.isAdding) return
        if (choice == LiveLibraryChoice.EVENT) {
            viewModelScope.launch { addProgramEvent(prompt.channel, prompt.program) }
            return
        }
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
                // Not on TMDB: the program, in the Library's Events.
                addProgramEvent(prompt.channel, prompt.program)
                return@launch
            }
            addMatched(match, choice, prompt.program)
        }
    }

    /** Saves the program itself to the Library's Events, says how it went, and closes the question. */
    private suspend fun addProgramEvent(channel: LiveTvChannel, program: EpgProgram) {
        _libraryPrompt.update { prompt -> if (prompt?.program == program) prompt.copy(isAdding = true) else prompt }
        val result = liveEventLibrary.addProgram(channel, program)
        toastEvent(result, LiveProgramLibrary.cleanTitle(program.title).ifBlank { program.title })
        if (result == LiveEventResult.FAILED) {
            _libraryPrompt.update { prompt -> if (prompt?.program == program) prompt.copy(isAdding = false, isMatching = false) else prompt }
        } else if (_libraryPrompt.value?.program == program) {
            dismissLibraryPrompt()
        }
    }

    private fun gameName(game: com.nuvio.tv.domain.model.SportsEvent): String =
        game.name.ifBlank { "${game.awayTeamName} vs ${game.homeTeamName}" }

    private fun toastEvent(result: LiveEventResult, name: String) {
        toast(
            when (result) {
                LiveEventResult.ADDED -> appContext.getString(R.string.livetv_library_added_event, name)
                LiveEventResult.ALREADY_IN_LIBRARY -> appContext.getString(R.string.livetv_library_already, name)
                LiveEventResult.FAILED -> appContext.getString(R.string.livetv_library_failed)
            }
        )
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

    /** The sample guide was left for the IPTV setup form: it isn't shown again until Retry. */
    private var setupFormShown = false

    /** The saved sources have been read (until then, which guide to show isn't known). */
    private var sourcesKnown = false

    /** The most recently seen source list, kept around so [retry] doesn't need a parameter. */
    private var currentSources: List<LiveTvSource> = emptyList()

    /** How the Live TV player gets a channel's address (switching channels from its Networks row). */
    private val playableUrlResolver: suspend (LiveTvChannel) -> String = { channel -> resolvePlaybackUrl(channel) }

    init {
        // Titles the guide saved before under their TMDB id become the titles' own library entries.
        viewModelScope.launch { liveProgramLibrary.mergeGuideEntries() }
        viewModelScope.launch {
            liveTvDataStore.sources.collect { sources ->
                currentSources = sources
                sourcesKnown = true
                val enabled = sources.filter { it.enabled }
                _uiState.update { it.copy(connection = enabled.firstOrNull()?.connection) }
                if (enabled.isNotEmpty()) {
                    // Real sources replace the sample guide.
                    setupFormShown = false
                    _uiState.update { it.copy(isPreviewGuide = false, previewError = null) }
                } else {
                    guideLoader.clearSessions()
                    if (_uiState.value.channels.isNotEmpty() && !_uiState.value.isPreviewGuide) {
                        // The sources were removed: their channels go.
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
                // The guide loaded for the app shows at once (loaded, or refreshed, only when needed).
                applyGuide(guideLoader.guide.value)
                if (enabled.isNotEmpty() || !setupFormShown) guideLoader.ensure(enabled)
            }
        }
        viewModelScope.launch {
            guideLoader.guide.collect { guide -> applyGuide(guide) }
        }
        viewModelScope.launch {
            liveTvDataStore.customChannelIds.collect { ids ->
                _uiState.update { it.copy(customChannelIds = ids) }
            }
        }
        // The Live TV player shows the Guide's channels and programs (what's on, the Networks row).
        viewModelScope.launch {
            _uiState
                .map { state ->
                    com.nuvio.tv.data.livetv.LiveTvPlayerSession.Guide(
                        channels = state.channels,
                        epgByChannel = state.epgByChannel,
                        customChannelIds = state.customChannelIds,
                        isPreview = state.isPreviewGuide
                    )
                }
                .distinctUntilChanged { old, new ->
                    old.channels === new.channels && old.epgByChannel === new.epgByChannel &&
                        old.customChannelIds == new.customChannelIds && old.isPreview == new.isPreview
                }
                .collect { guide -> if (guide.channels.isNotEmpty()) playerSession.publish(guide) }
        }
        playerSession.resolver = playableUrlResolver
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
    override fun onCleared() {
        if (playerSession.resolver === playableUrlResolver) playerSession.resolver = null
        super.onCleared()
    }

    suspend fun resolvePlaybackUrl(channel: LiveTvChannel): String =
        guideLoader.resolvePlaybackUrl(channel, currentSources)

    /**
     * Shows the guide loaded for the app, when it's for the sources in use (the sample guide when
     * there are none, unless it was left for the setup form). The Guide's own choices (group,
     * country, what's selected) stay.
     */
    private fun applyGuide(guide: LiveGuideSnapshot?) {
        if (guide == null || !sourcesKnown) return
        val enabled = currentSources.filter { it.enabled }
        if (guide.key != guideLoader.keyOf(enabled)) return
        if (guide.isPreview) {
            if (setupFormShown) return
            when {
                guide.isLoading -> _uiState.update {
                    it.copy(isLoading = true, error = null, previewError = null, isPreviewGuide = true)
                }
                guide.channels.isEmpty() -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        isPreviewGuide = false,
                        previewError = guide.error ?: "No channels found in the sample guide.",
                        channels = emptyList(),
                        epgByChannel = emptyMap(),
                        showGuide = false
                    )
                }
                else -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = guide.error,
                        previewError = null,
                        isPreviewGuide = true,
                        channels = guide.channels,
                        channelCategories = guide.channelCategories,
                        groups = emptyList(),
                        selectedGroup = null,
                        epgByChannel = guide.epgByChannel,
                        isEpgLoading = false,
                        showGuide = true,
                        nowMs = System.currentTimeMillis()
                    )
                }
            }
            return
        }
        if (guide.isLoading) {
            _uiState.update { it.copy(isLoading = true, error = null) }
            return
        }
        _uiState.update {
            it.copy(
                isLoading = false,
                error = guide.error,
                channels = guide.channels,
                groups = guide.groups,
                selectedGroup = it.selectedGroup?.takeIf { g -> g in guide.groups },
                guideCountry = it.guideCountry?.takeIf { c -> guide.channels.any { ch -> ch.country == c } },
                channelCategories = guide.channelCategories,
                epgByChannel = guide.epgByChannel,
                isEpgLoading = guide.isEpgLoading,
                nowMs = System.currentTimeMillis(),
                // With programs, the Guide page opens on them.
                showGuide = if (guide.epgByChannel.isNotEmpty() && !it.hasEpg) true else it.showGuide
            )
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
            guideLoader.clearSessions()
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

    fun setGuideCountry(country: String?) {
        _uiState.update { it.copy(guideCountry = country) }
    }

    private val _programDetailsTmdb = MutableStateFlow(ProgramDetailsTmdb())

    /** The open program's movie or series on TMDB (see [openProgramDetails]). */
    val programDetailsTmdb: StateFlow<ProgramDetailsTmdb> = _programDetailsTmdb.asStateFlow()
    private var programDetailsJob: Job? = null

    /**
     * Opens a program's details: what the guide says, and its movie or series from TMDB (artwork,
     * "Add to library", and its page) as soon as TMDB answers.
     */
    fun openProgramDetails(channel: LiveTvChannel, program: EpgProgram) {
        _uiState.update { it.copy(selectedProgramChannel = channel, selectedProgram = program) }
        programDetailsJob?.cancel()
        _programDetailsTmdb.value = ProgramDetailsTmdb(art = cachedProgramArt(program))
        programDetailsJob = viewModelScope.launch {
            // A game: ESPN is asked meanwhile (only the leagues it can be).
            val gameLookup = if (LiveEventLibrary.looksLikeGame(program)) {
                async {
                    try {
                        liveEventLibrary.findGame(program, channel.name)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
            } else {
                null
            }
            // TMDB's answer shows (and its actions work) as soon as it's in: it never waits for ESPN.
            // Not on TMDB, the program itself can be added to the Library from what the guide says.
            val art = programArt(program)
            val found = try {
                liveProgramLibrary.findTitle(program)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _programDetailsTmdb.value = ProgramDetailsTmdb(isLoading = false, match = found, art = art)
            val titleInLibrary = launch { watchInLibrary(channel, program, game = null, match = found) }
            // Then the game, when it's one ESPN has: it's what the program is (and what's added).
            val game = gameLookup?.await() ?: return@launch
            titleInLibrary.cancel()
            _programDetailsTmdb.update { it.copy(match = null, game = game, inLibrary = false) }
            launch { watchInLibrary(channel, program, game = game, match = null) }
        }
    }

    /** Whether what the details would add (the game, the title, or the program) is in the Library. */
    private suspend fun watchInLibrary(
        channel: LiveTvChannel,
        program: EpgProgram,
        game: com.nuvio.tv.domain.model.SportsEvent?,
        match: LiveProgramMatch?
    ) {
        val inLibrary = when {
            game != null -> liveEventLibrary.isGameInLibrary(game)
            match != null -> liveProgramLibrary.isInLibrary(match)
            else -> liveEventLibrary.isProgramInLibrary(channel, program)
        }
        runCatching {
            inLibrary.collect { saved -> _programDetailsTmdb.update { it.copy(inLibrary = saved) } }
        }.onFailure { if (it is CancellationException) throw it }
    }

    fun dismissProgramDetails() {
        programDetailsJob?.cancel()
        programDetailsJob = null
        _programDetailsTmdb.value = ProgramDetailsTmdb()
        _uiState.update { it.copy(selectedProgramChannel = null, selectedProgram = null) }
    }

    /**
     * "Add to library" in a program's details: its movie, or its whole series; not on TMDB, its game
     * (or the program itself) in the Library's Events.
     */
    fun addProgramDetailsToLibrary() {
        val state = _programDetailsTmdb.value
        if (state.isLoading || state.isAdding || state.inLibrary) return
        val match = state.match
        if (match == null) {
            val game = state.game
            val channel = _uiState.value.selectedProgramChannel
            val program = _uiState.value.selectedProgram
            if (game == null && (channel == null || program == null)) return
            _programDetailsTmdb.update { it.copy(isAdding = true) }
            viewModelScope.launch {
                val result = when {
                    game != null -> liveEventLibrary.addGame(game)
                    channel != null && program != null -> liveEventLibrary.addProgram(channel, program)
                    else -> LiveEventResult.FAILED
                }
                val name = when {
                    game != null -> gameName(game)
                    program != null -> LiveProgramLibrary.cleanTitle(program.title).ifBlank { program.title }
                    else -> ""
                }
                toastEvent(result, name)
                _programDetailsTmdb.update { it.copy(isAdding = false) }
            }
            return
        }
        _programDetailsTmdb.update { it.copy(isAdding = true) }
        viewModelScope.launch {
            val result = liveProgramLibrary.addTitle(match)
            toast(
                when (result) {
                    LiveLibraryResult.ADDED, LiveLibraryResult.ADDED_SERIES_INSTEAD ->
                        appContext.getString(R.string.livetv_library_added, match.name)
                    LiveLibraryResult.ALREADY_IN_LIBRARY -> appContext.getString(R.string.livetv_library_already, match.name)
                    LiveLibraryResult.NOT_FOUND -> appContext.getString(R.string.livetv_library_not_found, match.name)
                    LiveLibraryResult.FAILED -> appContext.getString(R.string.livetv_library_failed)
                }
            )
            _programDetailsTmdb.update { it.copy(isAdding = false) }
        }
    }

    /** Leaves the sample guide for the IPTV setup form. */
    fun showSetupForm() {
        setupFormShown = true
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
        setupFormShown = false
        guideLoader.ensure(currentSources.filter { it.enabled }, force = true)
        // What's loaded shows meanwhile (the sample guide again, after the setup form).
        applyGuide(guideLoader.guide.value)
    }

    companion object {
        private val COUNTRY_PREFIX = Regex("^([A-Z]{2,3})\\s*[-:|]\\s+")

        /** "US - FOX News Detroit" -> "FOX News Detroit"; "MTV - Music Television" stays (MTV isn't a country). */
        fun stripCountryTag(name: String): String {
            val match = COUNTRY_PREFIX.find(name) ?: return name
            if (LiveTvCountry.normalize(match.groupValues[1]) == null) return name
            return name.substring(match.range.last + 1).trim().ifBlank { name }
        }
    }
}
