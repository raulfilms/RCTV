package com.nuvio.tv.ui.screens.library

import com.nuvio.tv.ui.screens.sports.isLiveAt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.cloud.CloudLibraryFile
import com.nuvio.tv.core.cloud.CloudLibraryItem
import com.nuvio.tv.core.cloud.CloudLibraryItemType
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackInfo
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackContext
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackResult
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackSessionStore
import com.nuvio.tv.core.cloud.CloudLibraryRepository
import com.nuvio.tv.core.cloud.CloudLibraryUiState
import com.nuvio.tv.core.player.ExternalPlaybackMetadata
import com.nuvio.tv.core.player.ExternalPlaybackTracker
import com.nuvio.tv.core.debrid.DebridProviderCapability
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.debrid.supports
import com.nuvio.tv.core.tracking.TrackingListManagementCapabilities
import com.nuvio.tv.core.tracking.TrackingLibraryProviderRegistry
import com.nuvio.tv.core.tracking.providerId
import com.nuvio.tv.core.poster.withCustomPosterUrls
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.livetv.LiveProgramLibrary
import com.nuvio.tv.data.local.LiveTvDataStore
import com.nuvio.tv.data.local.SportsLibraryStore
import com.nuvio.tv.data.remote.espn.EspnSportsClient
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import com.nuvio.tv.domain.model.SportsLeague
import com.nuvio.tv.domain.model.SportsTeam
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.LibraryListPrivacy
import com.nuvio.tv.domain.repository.LibraryRepository
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.nuvio.tv.R
import java.util.Locale
import javax.inject.Inject

private val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")

data class LibraryTypeTab(
    val key: String,
    val label: String
) {
    companion object {
        const val ALL_KEY = "__all__"
        val All = LibraryTypeTab(key = ALL_KEY, label = "")
    }
}

enum class LibrarySortOption(
    val key: String,
    val labelResId: Int
) {
    DEFAULT("default", R.string.library_sort_provider_order),
    ADDED_DESC("added_desc", R.string.library_sort_added_desc),
    ADDED_ASC("added_asc", R.string.library_sort_added_asc),
    TITLE_ASC("title_asc", R.string.library_sort_title_asc),
    TITLE_DESC("title_desc", R.string.library_sort_title_desc);

    companion object {
        val TrackingOptions = listOf(DEFAULT, ADDED_DESC, ADDED_ASC, TITLE_ASC, TITLE_DESC)
        val LocalOptions = listOf(ADDED_DESC, ADDED_ASC, TITLE_ASC, TITLE_DESC)
    }
}

enum class LibraryWatchedFilter(val key: String, val labelResId: Int) {
    ALL("all", R.string.library_watched_filter_all),
    WATCHED("watched", R.string.library_watched_filter_watched),
    UNWATCHED("unwatched", R.string.library_watched_filter_unwatched);
}

data class FilterOption(
    val key: String,
    val label: String,
    val count: Int
)

data class LibraryListEditorState(
    val mode: Mode,
    val listId: String? = null,
    val name: String = "",
    val description: String = "",
    val privacy: LibraryListPrivacy = LibraryListPrivacy.PRIVATE
) {
    enum class Mode {
        CREATE,
        EDIT
    }
}

data class LibraryUiState(
    val sourceMode: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val allItems: List<LibraryEntry> = emptyList(),
    val visibleItems: List<LibraryEntry> = emptyList(),
    val cloudLibrary: CloudLibraryUiState = CloudLibraryUiState(),
    val visibleCloudItems: List<CloudLibraryItem> = emptyList(),
    val availableCloudProviders: List<FilterOption> = emptyList(),
    val availableCloudTypes: List<FilterOption> = emptyList(),
    val selectedCloudProviderId: String? = null,
    val selectedCloudType: CloudLibraryItemType? = null,
    /** Free-text filter applied to the cloud library only. Never queries addons or the saved list. */
    val cloudSearchQuery: String = "",
    val resolvingCloudFileKey: String? = null,
    val cloudLibrarySettingsVersion: Long = 0L,
    val listTabs: List<LibraryListTab> = emptyList(),
    val availableTypeTabs: List<LibraryTypeTab> = emptyList(),
    val availableSortOptions: List<LibrarySortOption> = emptyList(),
    val selectedListKey: String? = null,
    val selectedTypeTab: LibraryTypeTab? = null,
    val selectedSortOption: LibrarySortOption = LibrarySortOption.DEFAULT,
    val sortSelectionVersion: Long = 0L,
    val providerAddedOrder: LibraryProviderOrder? = null,
    val availableGenres: List<FilterOption> = emptyList(),
    val availableYears: List<FilterOption> = emptyList(),
    val selectedGenre: String? = null,
    val selectedYear: String? = null,
    val selectedWatchedFilter: LibraryWatchedFilter = LibraryWatchedFilter.ALL,
    val watchedMovieIds: Set<String> = emptySet(),
    val watchedSeriesIds: Set<String> = emptySet(),
    val isNuvioAccount: Boolean = false,
    val isTrackingAuthenticated: Boolean = false,
    val posterCardWidthDp: Int = 126,
    val posterCardCornerRadiusDp: Int = 12,
    val isLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val errorMessage: String? = null,
    val transientMessage: String? = null,
    val listManagement: TrackingListManagementCapabilities? = null,
    val showManageDialog: Boolean = false,
    val manageSelectedListKey: String? = null,
    val listEditorState: LibraryListEditorState? = null,
    val pendingOperation: Boolean = false,
    val customPosterUrlPattern: String = "",
    /** Sports teams, leagues, events and the Guide's custom channels: the Library's extra types. */
    val extraItems: List<LibraryEntry> = emptyList(),
    /** The logos each extra item shows (a game: both teams'), by "type:id". */
    val extraLogos: Map<String, List<String>> = emptyMap(),
    /** The saved games (when they start, whether they're on), by "type:id". */
    val extraEvents: Map<String, SportsEvent> = emptyMap(),
    /** The IMDb watchlist, as titles (the "IMDb List" type). */
    val imdbItems: List<LibraryEntry> = emptyList(),
    /** The series rated on IMDb (the "IMDb TV Rated" type). */
    val imdbRatedSeries: List<LibraryEntry> = emptyList(),
    /** An IMDb account is linked (the "IMDb List" type shows). */
    val imdbConnected: Boolean = false,
    /** Why the IMDb watchlist couldn't be read last time (private...). */
    val imdbError: com.nuvio.tv.data.remote.imdb.ImdbException.Reason? = null
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val cloudLibraryRepository: CloudLibraryRepository,
    private val cloudPlaybackSessionStore: CloudLibraryPlaybackSessionStore,
    private val externalPlaybackTracker: ExternalPlaybackTracker,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val metaRepository: com.nuvio.tv.domain.repository.MetaRepository,
    private val debridSettingsDataStore: DebridSettingsDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val libraryPreferences: LibraryPreferences,
    private val authManager: AuthManager,
    private val trackingProviderRegistry: TrackingLibraryProviderRegistry,
    private val watchProgressRepository: com.nuvio.tv.domain.repository.WatchProgressRepository,
    private val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    private val profileManager: com.nuvio.tv.core.profile.ProfileManager,
    val posterOptions: com.nuvio.tv.ui.components.posteroptions.PosterOptionsController,
    private val sportsLibraryStore: SportsLibraryStore,
    private val sportsPreferences: com.nuvio.tv.data.local.SportsPreferencesDataStore,
    private val liveTvDataStore: LiveTvDataStore,
    private val liveProgramLibrary: LiveProgramLibrary,
    private val imdbSync: com.nuvio.tv.data.imdb.ImdbSyncService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val _watchedMovieIds = MutableStateFlow<Set<String>>(emptySet())
    val watchedMovieIds: StateFlow<Set<String>> = _watchedMovieIds.asStateFlow()
    val watchedSeriesIds: StateFlow<Set<String>> = watchedSeriesStateHolder.fullyWatchedSeriesIds

    private class ListManagementContext(val profileId: Int, val source: LibrarySourceMode)
    private var listManagementContext: ListManagementContext? = null
    private var messageClearJob: Job? = null
    private var cloudRefreshJob: Job? = null
    private var selectedSortOverride: Pair<Int, LibrarySortOption>? = null

    init {
        posterOptions.bind(viewModelScope)
        observeLayoutPreferences()
        observeLibraryData()
        observeLibraryExtras()
        observeImdbList()
        observeProviderSortOrder()
        viewModelScope.launch {
            profileManager.activeProfileId.collect { profileId ->
                if (selectedSortOverride?.first != profileId) selectedSortOverride = null
                onCloseManageLists()
            }
        }
        observeCloudLibrarySettings()
        viewModelScope.launch {
            // Titles the Live TV guide saved under their TMDB id become the titles' own entries.
            profileManager.activeProfileId.collectLatest { liveProgramLibrary.mergeGuideEntries() }
        }
        viewModelScope.launch {
            watchProgressRepository.observeWatchedMovieIds()
                .collect { ids ->
                    _watchedMovieIds.value = ids
                    _uiState.update { current ->
                        current.copy(watchedMovieIds = ids).withVisibleItems()
                    }
                }
        }
        viewModelScope.launch {
            watchedSeriesStateHolder.fullyWatchedSeriesIds
                .collect { ids ->
                    _uiState.update { current ->
                        current.copy(watchedSeriesIds = ids).withVisibleItems()
                    }
                }
        }
    }

    private val metaPrefetchedIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var metaPrefetchJob: Job? = null

    /**
     * Prefetch meta from addons in background when an item receives focus.
     * Warms the MetaRepository cache so the detail screen loads instantly.
     * Debounced to avoid flooding the network during rapid scrolling.
     */
    fun prefetchMetaOnFocus(id: String, type: String) {
        if (id.isBlank() || id in metaPrefetchedIds) return
        // Teams, leagues, games and channels aren't addon titles.
        if (type.lowercase(Locale.ROOT) in LIBRARY_EXTRA_TYPES) return
        metaPrefetchJob?.cancel()
        metaPrefetchJob = viewModelScope.launch {
            delay(150)
            if (id in metaPrefetchedIds) return@launch
            metaPrefetchedIds.add(id)
            metaRepository.getMetaFromAllAddons(type = type, id = id)
                .first { it !is com.nuvio.tv.core.network.NetworkResult.Loading }
            watchProgressRepository.getAllEpisodeProgress(id.substringBefore(":")).first()
        }
    }

    fun getCachedBackdrop(id: String, type: String): String? {
        return metaRepository.getCachedMeta(type, id)?.backdropUrl
    }

    fun onSelectTypeTab(tab: LibraryTypeTab) {
        _uiState.update { current ->
            val updated = current.copy(selectedTypeTab = tab)
            updated.withVisibleItems()
        }
        viewModelScope.launch {
            libraryPreferences.setLastSelectedType(tab.key)
        }
    }

    fun onSelectListTab(listKey: String) {
        _uiState.update { current ->
            val updated = current.copy(selectedListKey = listKey)
            updated.withVisibleItems()
        }
        viewModelScope.launch {
            libraryPreferences.setLastSelectedList(listKey)
        }
    }

    fun onSelectGenre(key: String?) {
        _uiState.update { current ->
            val updated = current.copy(selectedGenre = key)
            updated.withVisibleItems()
        }
    }

    fun onSelectYear(key: String?) {
        _uiState.update { current ->
            val updated = current.copy(selectedYear = key)
            updated.withVisibleItems()
        }
    }

    fun onSelectWatchedFilter(filter: LibraryWatchedFilter) {
        _uiState.update { current ->
            val updated = current.copy(selectedWatchedFilter = filter)
            updated.withVisibleItems()
        }
    }

    fun onSelectSortOption(option: LibrarySortOption) {
        val profileId = profileManager.activeProfileId.value
        selectedSortOverride = profileId to option
        _uiState.update { current ->
            val nextVersion = if (current.selectedSortOption != option) {
                current.sortSelectionVersion + 1L
            } else {
                current.sortSelectionVersion
            }
            val updated = current.copy(
                selectedSortOption = option,
                sortSelectionVersion = nextVersion
            )
            updated.withVisibleItems()
        }
        viewModelScope.launch {
            if (profileManager.activeProfileId.value == profileId) libraryPreferences.setSortOption(option.key)
        }
    }

    private fun observeProviderSortOrder() {
        viewModelScope.launch {
            combine(profileManager.activeProfileId, uiState) { profileId, state ->
                profileId to Triple(state.sourceMode.takeIf { state.isTrackingAuthenticated },
                    state.selectedListKey, state.selectedSortOption)
            }.distinctUntilChanged().collectLatest { (profileId, selection) ->
                _uiState.update { it.copy(providerAddedOrder = null).withVisibleItems() }
                val (source, listKey, sortOption) = selection
                if (source == null || listKey == null ||
                    sortOption !in listOf(LibrarySortOption.ADDED_ASC, LibrarySortOption.ADDED_DESC)) return@collectLatest
                val sorter = source.providerId?.let(trackingProviderRegistry::provider)?.listSorter ?: return@collectLatest
                try {
                    sorter.observeAddedOrder(listKey, sortOption == LibrarySortOption.ADDED_DESC).collect { keys ->
                        if (profileManager.activeProfileId.value != profileId) return@collect
                        _uiState.update { current ->
                            if (current.sourceMode != source || current.selectedListKey != listKey ||
                                current.selectedSortOption != sortOption) current else current.copy(
                                providerAddedOrder = keys?.let { LibraryProviderOrder(source, listKey, sortOption,
                                    it.withIndex().associate { (index, key) -> key to index }) }
                            ).withVisibleItems()
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    val current = _uiState.value
                    if (profileManager.activeProfileId.value == profileId && current.sourceMode == source &&
                        current.selectedListKey == listKey && current.selectedSortOption == sortOption) {
                        onSelectSortOption(LibrarySortOption.DEFAULT)
                        setError(context.getString(R.string.library_error_sort_failed))
                    }
                }
            }
        }
    }

    fun ensureCloudLibraryLoaded() {
        val current = _uiState.value.cloudLibrary
        if (current.isLoaded || current.isRefreshing) return
        refreshCloudLibrary()
    }

    fun refreshCloudLibrary() {
        val current = _uiState.value.cloudLibrary
        if (current.isRefreshing) return
        cloudRefreshJob = viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    cloudLibrary = state.cloudLibrary.copy(
                        isRefreshing = true,
                        providers = state.cloudLibrary.providers.map { provider -> provider.copy(isLoading = true, errorMessage = null) }
                    )
                )
            }
            runCatching {
                cloudLibraryRepository.refresh()
            }.onSuccess { refreshed ->
                _uiState.update { state ->
                    state.copy(cloudLibrary = refreshed).withVisibleCloudItems()
                }
            }.onFailure { error ->
                if (error is CancellationException) return@launch
                setError(error.message ?: context.getString(R.string.cloud_library_play_failed))
                _uiState.update { state ->
                    state.copy(cloudLibrary = state.cloudLibrary.copy(isLoaded = true, isRefreshing = false)).withVisibleCloudItems()
                }
            }
        }
    }

    fun onSelectCloudProvider(providerId: String?) {
        _uiState.update { current ->
            current.copy(selectedCloudProviderId = providerId).withVisibleCloudItems()
        }
    }

    fun onCloudSearchQueryChange(query: String) {
        _uiState.update { current ->
            current.copy(cloudSearchQuery = query).withVisibleCloudItems()
        }
    }

    fun onSelectCloudType(type: CloudLibraryItemType?) {
        _uiState.update { current ->
            current.copy(selectedCloudType = type).withVisibleCloudItems()
        }
    }

    fun onCloudItemHasNoPlayableFiles() {
        setError(context.getString(R.string.cloud_library_no_playable_files))
    }

    fun resolveCloudPlayback(
        item: CloudLibraryItem,
        file: CloudLibraryFile,
        onResolved: (CloudLibraryPlaybackInfo) -> Unit
    ) {
        val resolveKey = "${item.stableKey}:${file.stableKey}"
        if (_uiState.value.resolvingCloudFileKey != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(resolvingCloudFileKey = resolveKey, errorMessage = null) }
            val result = cloudLibraryRepository.resolvePlayback(item, file)
            _uiState.update { it.copy(resolvingCloudFileKey = null) }
            when (result) {
                is CloudLibraryPlaybackResult.Success -> {
                    val playbackContext = CloudLibraryPlaybackContext.create(item, file)
                    if (playbackContext == null) {
                        setError(context.getString(R.string.cloud_library_play_failed))
                        return@launch
                    }
                    val sessionToken = cloudPlaybackSessionStore.create(playbackContext)
                    onResolved(
                        CloudLibraryPlaybackInfo(
                            item = item,
                            file = file,
                            url = result.url,
                            filename = result.filename ?: file.name.takeIf { it.isNotBlank() },
                            videoSizeBytes = result.videoSizeBytes ?: file.sizeBytes,
                            sessionToken = sessionToken,
                            sequenceIndex = playbackContext.currentIndex
                        )
                    )
                }
                CloudLibraryPlaybackResult.MissingCredentials -> {
                    setError(context.getString(R.string.cloud_library_connect_message))
                }
                CloudLibraryPlaybackResult.NotPlayable -> {
                    setError(context.getString(R.string.cloud_library_no_playable_files))
                }
                is CloudLibraryPlaybackResult.Failed -> {
                    setError(result.message ?: context.getString(R.string.cloud_library_play_failed))
                }
            }
        }
    }

    suspend fun launchCloudPlaybackExternally(
        info: CloudLibraryPlaybackInfo,
        activityContext: Context
    ): Boolean {
        val filename = info.filename ?: info.file.name
        val metadata = ExternalPlaybackMetadata(
            contentId = info.item.stableKey,
            contentType = "cloud",
            contentName = info.item.name,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "${info.item.stableKey}:${info.file.stableKey}",
            season = 1,
            episode = info.sequenceIndex + 1,
            episodeTitle = filename,
            year = null,
            profileId = profileManager.activeProfileId.value
        )
        val launched = runCatching {
            externalPlaybackTracker.launchPlayer(
                metadata = metadata,
                url = info.url,
                title = filename,
                headers = null,
                cloudSessionToken = info.sessionToken,
                context = activityContext
            )
        }.getOrDefault(false)
        if (!launched) setError(context.getString(R.string.cloud_library_play_failed))
        return launched
    }

    suspend fun getPlayerPreference(): PlayerPreference =
        playerSettingsDataStore.playerSettings.first().playerPreference

    fun onRefresh() {
        if (_uiState.value.isSyncing) return
        viewModelScope.launch {
            setTransientMessage(context.getString(R.string.library_syncing_library))
            runCatching {
                libraryRepository.refreshNow()
                setTransientMessage(context.getString(R.string.library_synced))
            }.onFailure { error ->
                setError(error.message ?: context.getString(R.string.library_error_refresh_failed))
            }
        }
    }

    fun onOpenManageLists() {
        val current = _uiState.value
        if (current.listManagement == null || !current.isTrackingAuthenticated) return
        listManagementContext = ListManagementContext(profileManager.activeProfileId.value, current.sourceMode)
        _uiState.update {
            it.copy(
                showManageDialog = true,
                manageSelectedListKey = it.manageSelectedListKey
                    ?: it.listTabs.firstOrNull { tab -> tab.type == LibraryListTab.Type.PERSONAL }?.key
            )
        }
    }

    fun onCloseManageLists() {
        listManagementContext = null
        _uiState.update { it.copy(showManageDialog = false, listEditorState = null, pendingOperation = false, errorMessage = null) }
    }

    fun onSelectManageList(listKey: String) {
        if (_uiState.value.pendingOperation) return
        _uiState.update { it.copy(manageSelectedListKey = listKey) }
    }

    fun onStartCreateList() {
        if (!isListManagementCurrent()) return
        _uiState.update {
            it.copy(listEditorState = LibraryListEditorState(mode = LibraryListEditorState.Mode.CREATE), errorMessage = null)
        }
    }

    fun onStartEditList() {
        if (!isListManagementCurrent()) return
        val selected = selectedManagePersonalList() ?: return
        _uiState.update {
            it.copy(
                listEditorState = LibraryListEditorState(
                    mode = LibraryListEditorState.Mode.EDIT,
                    listId = selected.key,
                    name = selected.title,
                    description = selected.description.orEmpty(),
                    privacy = selected.privacy ?: LibraryListPrivacy.PRIVATE
                ),
                errorMessage = null
            )
        }
    }

    fun onUpdateEditorName(value: String) = updateListEditor { copy(name = value) }

    fun onUpdateEditorDescription(value: String) {
        if (_uiState.value.listManagement?.supportsDescription == true) updateListEditor { copy(description = value) }
    }

    fun onUpdateEditorPrivacy(value: LibraryListPrivacy) {
        if (value in _uiState.value.listManagement?.privacyOptions.orEmpty()) updateListEditor { copy(privacy = value) }
    }

    private fun updateListEditor(update: LibraryListEditorState.() -> LibraryListEditorState) {
        if (!isListManagementCurrent() || _uiState.value.pendingOperation) return
        _uiState.update { current -> current.copy(listEditorState = current.listEditorState?.update()) }
    }

    fun onCancelEditor() {
        _uiState.update { it.copy(listEditorState = null, errorMessage = null) }
    }

    fun onSubmitEditor() {
        val editor = _uiState.value.listEditorState ?: return
        val name = editor.name.trim()
        if (name.isBlank()) {
            setError(context.getString(R.string.library_error_list_name_required))
            return
        }
        val message = if (editor.mode == LibraryListEditorState.Mode.CREATE) R.string.library_list_created else R.string.library_list_updated
        runListOperation(message, R.string.library_error_save_list_failed) { source ->
            val description = editor.description.trim().takeIf { _uiState.value.listManagement?.supportsDescription == true && it.isNotBlank() }
            when (editor.mode) {
                LibraryListEditorState.Mode.CREATE -> libraryRepository.createPersonalList(name, description, editor.privacy, source)
                LibraryListEditorState.Mode.EDIT -> libraryRepository.updatePersonalList(
                    requireNotNull(editor.listId), name, description, editor.privacy, source
                )
            }
        }
    }

    fun onDeleteSelectedList() {
        val key = selectedManagePersonalList()?.key ?: return
        runListOperation(R.string.library_list_deleted, R.string.library_error_delete_list_failed) { source ->
            libraryRepository.deletePersonalList(key, source)
        }
    }

    fun onMoveSelectedListUp() = reorderSelectedList(moveUp = true)

    fun onMoveSelectedListDown() = reorderSelectedList(moveUp = false)

    fun onClearTransientMessage() {
        _uiState.update { it.copy(transientMessage = null) }
    }

    private fun isListManagementCurrent(expected: ListManagementContext? = listManagementContext): Boolean =
        expected != null && expected == listManagementContext && expected.profileId == profileManager.activeProfileId.value &&
            expected.source == _uiState.value.sourceMode && _uiState.value.showManageDialog &&
            _uiState.value.isTrackingAuthenticated && _uiState.value.listManagement != null

    private fun runListOperation(successMessage: Int, errorMessage: Int, action: suspend (LibrarySourceMode) -> Unit) {
        if (!isListManagementCurrent() || _uiState.value.pendingOperation) return
        val operationContext = requireNotNull(listManagementContext)
        _uiState.update { it.copy(pendingOperation = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                if (!isListManagementCurrent(operationContext)) return@launch
                action(operationContext.source)
                if (isListManagementCurrent(operationContext)) {
                    _uiState.update { it.copy(listEditorState = null) }
                    setTransientMessage(context.getString(successMessage))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isListManagementCurrent(operationContext)) setError(error.message ?: context.getString(errorMessage))
            } finally {
                if (operationContext == listManagementContext) _uiState.update { it.copy(pendingOperation = false) }
            }
        }
    }

    private fun observeLibraryData() {
        val selectedProviderAuthenticated = libraryRepository.sourceMode.flatMapLatest { sourceMode ->
            sourceMode.providerId
                ?.let { providerId -> trackingProviderRegistry.provider(providerId)?.isAuthenticated }
                ?: flowOf(true)
        }
        viewModelScope.launch {
            combine(
                libraryRepository.sourceMode,
                libraryRepository.isSyncing,
                libraryRepository.libraryItems,
                libraryRepository.listTabs,
                libraryPreferences.sortOption,
                authManager.authState,
                selectedProviderAuthenticated,
                libraryPreferences.lastSelectedList,
                libraryPreferences.lastSelectedType
            ) { args ->
                val sourceMode = args[0] as LibrarySourceMode
                val isSyncing = args[1] as Boolean
                @Suppress("UNCHECKED_CAST")
                val items = args[2] as List<LibraryEntry>
                @Suppress("UNCHECKED_CAST")
                val listTabs = args[3] as List<LibraryListTab>
                val persistedSortKey = args[4] as String?
                val authState = args[5] as AuthState
                val isTrackingAuthenticated = args[6] as Boolean
                val persistedListKey = args[7] as String?
                val persistedTypeKey = args[8] as String?
                DataBundle(
                    sourceMode = sourceMode,
                    isSyncing = isSyncing,
                    items = items,
                    listTabs = listTabs,
                    persistedSortKey = persistedSortKey,
                    authState = authState,
                    isTrackingAuthenticated = isTrackingAuthenticated,
                    persistedListKey = persistedListKey,
                    persistedTypeKey = persistedTypeKey
                )
            }.collectLatest { bundle ->
                val (sourceMode, isSyncing, items, listTabs, persistedSortKey, authState, isTrackingAuthenticated, persistedListKey, persistedTypeKey) = bundle
                if (_uiState.value.sourceMode != sourceMode || !isTrackingAuthenticated) onCloseManageLists()
                _uiState.update { current ->
                    val nextSelectedList = when {
                        sourceMode.providerId != null && isTrackingAuthenticated -> {
                            current.selectedListKey
                                ?.takeIf { key -> listTabs.any { it.key == key } }
                                ?: persistedListKey?.takeIf { key -> listTabs.any { it.key == key } }
                                ?: listTabs.firstOrNull()?.key
                        }
                        else -> null
                    }

                    val nextManageSelected = current.manageSelectedListKey
                        ?.takeIf { key ->
                            listTabs.any { tab ->
                                tab.key == key && tab.type == LibraryListTab.Type.PERSONAL
                            }
                        }
                        ?: listTabs.firstOrNull { it.type == LibraryListTab.Type.PERSONAL }?.key

                    val nextSelectedType = current.selectedTypeTab
                        ?: persistedTypeKey?.let { key ->
                            LibraryTypeTab(key = if (key == LibraryTypeTab.ALL_KEY) key else normalizeLibraryTypeKey(key), label = "")
                        }
                        ?: LibraryTypeTab.All.copy(label = context.getString(R.string.library_type_all))
                    val sortOptions = if (sourceMode.providerId != null && isTrackingAuthenticated) {
                        LibrarySortOption.TrackingOptions
                    } else {
                        LibrarySortOption.LocalOptions
                    }
                    val modeDefault = if (sourceMode.providerId != null && isTrackingAuthenticated) LibrarySortOption.DEFAULT else LibrarySortOption.ADDED_DESC
                    val persistedSort = persistedSortKey?.let { key ->
                        LibrarySortOption.entries.find { it.key == key }
                    }
                    val sessionSort = selectedSortOverride?.takeIf { it.first == profileManager.activeProfileId.value }?.second
                    val nextSelectedSort = (sessionSort ?: persistedSort ?: current.selectedSortOption)
                        .takeIf { it in sortOptions }
                        ?: modeDefault

                    val isNuvioAccount = sourceMode == LibrarySourceMode.LOCAL && authState is AuthState.FullAccount

                    val updated = current.copy(
                        sourceMode = sourceMode,
                        listManagement = sourceMode.providerId?.let(trackingProviderRegistry::provider)?.listManager?.capabilities,
                        allItems = items,
                        listTabs = listTabs,
                        availableSortOptions = sortOptions,
                        selectedTypeTab = nextSelectedType,
                        selectedListKey = nextSelectedList,
                        selectedSortOption = nextSelectedSort,
                        manageSelectedListKey = nextManageSelected,
                        isNuvioAccount = isNuvioAccount,
                        isTrackingAuthenticated = isTrackingAuthenticated,
                        isSyncing = isSyncing,
                        isLoading = isSyncing && items.isEmpty()
                    )
                    updated.withVisibleItems().withVisibleCloudItems()
                }
            }
        }
    }

    private fun observeLayoutPreferences() {
        viewModelScope.launch {
            combine(
                layoutPreferenceDataStore.posterCardWidthDp,
                layoutPreferenceDataStore.posterCardCornerRadiusDp
            ) { widthDp, cornerRadiusDp ->
                widthDp to cornerRadiusDp
            }.collectLatest { (widthDp, cornerRadiusDp) ->
                _uiState.update { current ->
                    if (current.posterCardWidthDp == widthDp &&
                        current.posterCardCornerRadiusDp == cornerRadiusDp
                    ) {
                        current
                    } else {
                        current.copy(
                            posterCardWidthDp = widthDp,
                            posterCardCornerRadiusDp = cornerRadiusDp
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.customPosterUrlPattern
                .distinctUntilChanged()
                .collectLatest { pattern ->
                    _uiState.update { current ->
                        if (current.customPosterUrlPattern == pattern) current
                        else current.copy(customPosterUrlPattern = pattern).withVisibleItems()
                    }
                }
        }
    }

    private fun observeCloudLibrarySettings() {
        viewModelScope.launch {
            debridSettingsDataStore.settings
                .map { settings ->
                    CloudLibrarySettingsSnapshot(
                        enabled = settings.cloudLibraryEnabled,
                        connectionKeys = DebridProviders.configuredServices(settings)
                            .filter { credential -> credential.provider.supports(DebridProviderCapability.CloudLibrary) }
                            .map { credential -> "${credential.provider.id}:${credential.apiKey}" }
                    )
                }
                .distinctUntilChanged()
                .collectLatest { snapshot ->
                    if (!snapshot.enabled) {
                        cloudRefreshJob?.cancel()
                        _uiState.update { state ->
                            state.copy(
                                cloudLibrary = CloudLibraryUiState(isLoaded = true, isEnabled = false),
                                visibleCloudItems = emptyList(),
                                availableCloudProviders = emptyList(),
                                availableCloudTypes = emptyList(),
                                selectedCloudProviderId = null,
                                selectedCloudType = null,
                                resolvingCloudFileKey = null,
                                cloudLibrarySettingsVersion = state.cloudLibrarySettingsVersion + 1L
                            )
                        }
                    } else if (snapshot.connectionKeys.isEmpty()) {
                        cloudRefreshJob?.cancel()
                        _uiState.update { state ->
                            state.copy(
                                cloudLibrary = CloudLibraryUiState(isLoaded = true, isEnabled = true),
                                visibleCloudItems = emptyList(),
                                availableCloudProviders = emptyList(),
                                availableCloudTypes = emptyList(),
                                selectedCloudProviderId = null,
                                selectedCloudType = null,
                                resolvingCloudFileKey = null,
                                cloudLibrarySettingsVersion = state.cloudLibrarySettingsVersion + 1L
                            )
                        }
                    } else {
                        cloudRefreshJob?.cancel()
                        _uiState.update { state ->
                            state.copy(
                                cloudLibrary = CloudLibraryUiState(isLoaded = false, isEnabled = true),
                                visibleCloudItems = emptyList(),
                                availableCloudProviders = emptyList(),
                                availableCloudTypes = emptyList(),
                                selectedCloudProviderId = null,
                                selectedCloudType = null,
                                resolvingCloudFileKey = null,
                                cloudLibrarySettingsVersion = state.cloudLibrarySettingsVersion + 1L
                            )
                        }
                    }
                }
        }
    }

    private data class DataBundle(
        val sourceMode: LibrarySourceMode,
        val isSyncing: Boolean,
        val items: List<LibraryEntry>,
        val listTabs: List<LibraryListTab>,
        val persistedSortKey: String?,
        val authState: AuthState,
        val isTrackingAuthenticated: Boolean,
        val persistedListKey: String? = null,
        val persistedTypeKey: String? = null
    )

    private data class CloudLibrarySettingsSnapshot(
        val enabled: Boolean,
        val connectionKeys: List<String>
    )

    private fun reorderSelectedList(moveUp: Boolean) {
        val state = _uiState.value
        if (state.listManagement?.supportsReordering != true) return
        val personalTabs = state.listTabs.filter { it.type == LibraryListTab.Type.PERSONAL }
        val selectedIndex = personalTabs.indexOfFirst { it.key == state.manageSelectedListKey }
        if (selectedIndex < 0) return
        val targetIndex = if (moveUp) selectedIndex - 1 else selectedIndex + 1
        if (targetIndex !in personalTabs.indices) return
        val reordered = personalTabs.toMutableList().apply { add(targetIndex, removeAt(selectedIndex)) }
        runListOperation(R.string.library_list_order_updated, R.string.library_error_reorder_lists_failed) { source ->
            libraryRepository.reorderPersonalLists(reordered.map { it.key }, source)
        }
    }

    private fun selectedManagePersonalList(): LibraryListTab? {
        val state = _uiState.value
        val selectedKey = state.manageSelectedListKey ?: return null
        return state.listTabs.firstOrNull { it.key == selectedKey && it.type == LibraryListTab.Type.PERSONAL }
    }

    private fun setError(message: String) {
        _uiState.update { it.copy(errorMessage = message, transientMessage = message) }
        messageClearJob?.cancel()
        messageClearJob = viewModelScope.launch {
            delay(2800)
            _uiState.update { it.copy(transientMessage = null) }
        }
    }

    private fun setTransientMessage(message: String) {
        _uiState.update { it.copy(transientMessage = message, errorMessage = null) }
        messageClearJob?.cancel()
        messageClearJob = viewModelScope.launch {
            delay(2200)
            _uiState.update { it.copy(transientMessage = null) }
        }
    }

    private fun prettifyTypeLabel(key: String): String {
        return key
            .replace('_', ' ')
            .replace('-', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString()
                }
            }
            .ifBlank { context.getString(R.string.type_unknown) }
    }

    private fun LibraryEntry.extractYear(): String? =
        releaseInfo?.let { YEAR_REGEX.find(it)?.value }

    private fun LibraryUiState.withVisibleItems(): LibraryUiState {
        // Games bookmarked locally come in with the extra types, not with the titles.
        val titleItems = allItems.filterNot { it.isLibraryExtra() }
        val listFiltered = if (sourceMode.providerId != null) {
            val listKey = selectedListKey ?: ""
            titleItems.filter { entry -> entry.listKeys.contains(listKey) }
        } else {
            titleItems
        }

        // Step 2: Type filter. Teams, leagues, events and custom channels aren't in any list, and
        // have no genres, years or watched state to filter by.
        val selectedTypeKey = selectedTypeTab?.key
        val extraSelected = selectedTypeKey != null && selectedTypeKey in LIBRARY_EXTRA_TYPES
        // The IMDb watchlist and rated series: titles of their own, not the Library's.
        val imdbSelected = selectedTypeKey in LIBRARY_IMDB_TYPES
        val typeFiltered = when {
            extraSelected -> extraItems.filter { it.type == selectedTypeKey }
            selectedTypeKey == LIBRARY_TYPE_IMDB_LIST -> imdbItems
            selectedTypeKey == LIBRARY_TYPE_IMDB_TV_RATED -> imdbRatedSeries
            else -> listFiltered.filter { entry ->
                selectedTypeKey == null ||
                    selectedTypeKey == LibraryTypeTab.ALL_KEY ||
                    entry.libraryTypeKey() == selectedTypeKey
            }
        }

        // Step 3: Genre filter
        val genreFiltered = if (selectedGenre != null && !extraSelected) {
            typeFiltered.filter { entry ->
                entry.genres.any { it.equals(selectedGenre, ignoreCase = true) }
            }
        } else {
            typeFiltered
        }

        // Step 4: Year filter
        val yearFiltered = if (selectedYear != null && !extraSelected) {
            genreFiltered.filter { entry -> entry.extractYear() == selectedYear }
        } else {
            genreFiltered
        }

        // Step 5: Watched status filter
        val watchedFiltered = if (extraSelected) yearFiltered else when (selectedWatchedFilter) {
            LibraryWatchedFilter.ALL -> yearFiltered
            LibraryWatchedFilter.WATCHED -> yearFiltered.filter { entry ->
                val isMovie = entry.type.equals("movie", ignoreCase = true)
                if (isMovie) watchedMovieIds.contains(entry.id)
                else watchedSeriesIds.contains(entry.id)
            }
            LibraryWatchedFilter.UNWATCHED -> yearFiltered.filter { entry ->
                val isMovie = entry.type.equals("movie", ignoreCase = true)
                if (isMovie) !watchedMovieIds.contains(entry.id)
                else !watchedSeriesIds.contains(entry.id)
            }
        }

        // Faceted counts — each filter counts items matching all OTHER active filters

        // Genre counts: from typeFiltered (after list+type), applying year filter but NOT genre filter
        val itemsForGenreCounts = if (selectedYear != null) {
            typeFiltered.filter { it.extractYear() == selectedYear }
        } else {
            typeFiltered
        }
        val genreCounts = mutableMapOf<String, Int>()
        itemsForGenreCounts.forEach { entry ->
            entry.genres.forEach { genre ->
                val normalized = genre.trim()
                if (normalized.isNotBlank()) {
                    genreCounts[normalized] = (genreCounts[normalized] ?: 0) + 1
                }
            }
        }
        val genreOptions = if (extraSelected) emptyList() else genreCounts.entries
            .sortedBy { it.key.lowercase(Locale.ROOT) }
            .map { (genre, count) -> FilterOption(key = genre, label = genre, count = count) }

        // Year counts: from typeFiltered (after list+type), applying genre filter but NOT year filter
        val itemsForYearCounts = if (selectedGenre != null) {
            typeFiltered.filter { entry ->
                entry.genres.any { it.equals(selectedGenre, ignoreCase = true) }
            }
        } else {
            typeFiltered
        }
        val yearCounts = mutableMapOf<String, Int>()
        itemsForYearCounts.forEach { entry ->
            val year = entry.extractYear() ?: return@forEach
            yearCounts[year] = (yearCounts[year] ?: 0) + 1
        }
        val yearOptions = if (extraSelected) emptyList() else yearCounts.entries
            .sortedByDescending { it.key }
            .map { (year, count) -> FilterOption(key = year, label = year, count = count) }

        // Type tab counts: from listFiltered, applying genre+year filters
        val itemsForTypeCounts = listFiltered.filter { entry ->
            val genreMatch = selectedGenre == null || entry.genres.any { it.equals(selectedGenre, ignoreCase = true) }
            val yearMatch = selectedYear == null || entry.extractYear() == selectedYear
            genreMatch && yearMatch
        }

        // Step 6: Sort
        val addedOrder = providerAddedOrder?.takeIf {
            !imdbSelected && it.source == sourceMode && it.listKey == selectedListKey && it.sortOption == selectedSortOption
        }?.comparator()
        val sorted = when (selectedSortOption) {
            // The IMDb List's own order is the watchlist's.
            LibrarySortOption.DEFAULT -> if (sourceMode.providerId != null && !imdbSelected) {
                watchedFiltered.sortedWith(
                    compareBy<LibraryEntry> { it.listRanks[selectedListKey] ?: it.traktRank ?: Int.MAX_VALUE }
                        .thenByDescending { it.listedAt }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                        .thenBy { it.id }
                )
            } else {
                watchedFiltered
            }
            LibrarySortOption.ADDED_DESC -> watchedFiltered.sortedWith(
                addedOrder ?: compareByDescending<LibraryEntry> { it.listedAt }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                    .thenBy { it.id }
            )
            LibrarySortOption.ADDED_ASC -> watchedFiltered.sortedWith(
                addedOrder ?: compareBy<LibraryEntry> { it.listedAt }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                    .thenBy { it.id }
            )
            LibrarySortOption.TITLE_ASC -> watchedFiltered.sortedWith(
                compareBy<LibraryEntry> { titleSortKey(it.name.ifBlank { it.id }) }
                    .thenBy { it.id }
            )
            LibrarySortOption.TITLE_DESC -> watchedFiltered.sortedWith(
                compareByDescending<LibraryEntry> { titleSortKey(it.name.ifBlank { it.id }) }
                    .thenBy { it.id }
            )
        }

        // Rebuild type tabs with counts
        fun List<LibraryEntry>.countMatching() = count { entry ->
            (selectedGenre == null || entry.genres.any { it.equals(selectedGenre, ignoreCase = true) }) &&
                (selectedYear == null || entry.extractYear() == selectedYear)
        }
        val imdbCounts = if (imdbConnected) {
            mapOf(
                LIBRARY_TYPE_IMDB_LIST to imdbItems.countMatching(),
                LIBRARY_TYPE_IMDB_TV_RATED to imdbRatedSeries.countMatching()
            )
        } else {
            emptyMap()
        }
        val typeTabsWithCounts = buildTypeTabsWithCounts(listFiltered, itemsForTypeCounts, extraItems, imdbCounts)

        // Validate selections — clear if no longer valid (kept while an extra type is shown, for
        // when the titles come back)
        val validGenre = if (extraSelected) selectedGenre else selectedGenre?.takeIf { g -> genreOptions.any { it.key.equals(g, ignoreCase = true) } }
        val validYear = if (extraSelected) selectedYear else selectedYear?.takeIf { y -> yearOptions.any { it.key == y } }

        return copy(
            visibleItems = sorted.withCustomPosterUrls(customPosterUrlPattern),
            availableTypeTabs = typeTabsWithCounts,
            availableGenres = genreOptions,
            availableYears = yearOptions,
            selectedGenre = validGenre,
            selectedYear = validYear
        )
    }

    private fun LibraryUiState.withVisibleCloudItems(): LibraryUiState {
        val allCloudItems = cloudLibrary.items
        val providerFiltered = if (selectedCloudProviderId != null) {
            allCloudItems.filter { it.providerId == selectedCloudProviderId }
        } else {
            allCloudItems
        }
        val typeFiltered = if (selectedCloudType != null) {
            providerFiltered.filter { it.type == selectedCloudType }
        } else {
            providerFiltered
        }
        // Matches the item name or any of its file names, so a release title or a filename both work.
        val query = cloudSearchQuery.trim()
        val visible = if (query.isEmpty()) {
            typeFiltered
        } else {
            typeFiltered.filter { item ->
                item.name.contains(query, ignoreCase = true) ||
                    item.files.any { file -> file.name.contains(query, ignoreCase = true) }
            }
        }
        val providerCounts = allCloudItems
            .groupBy { it.providerId to it.providerName }
            .map { (provider, items) -> FilterOption(key = provider.first, label = provider.second, count = items.size) }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
        val typeCounts = providerFiltered
            .groupBy { it.type }
            .map { (type, items) -> FilterOption(key = type.name, label = cloudTypeLabel(type), count = items.size) }
            .sortedBy { option -> CloudLibraryItemType.valueOf(option.key).ordinal }
        val validProvider = selectedCloudProviderId?.takeIf { providerId -> providerCounts.any { it.key == providerId } }
        val validType = selectedCloudType?.takeIf { type -> typeCounts.any { it.key == type.name } }
        return copy(
            visibleCloudItems = visible,
            availableCloudProviders = providerCounts,
            availableCloudTypes = typeCounts,
            selectedCloudProviderId = validProvider,
            selectedCloudType = validType
        )
    }

    private fun cloudTypeLabel(type: CloudLibraryItemType): String =
        when (type) {
            CloudLibraryItemType.Torrent -> context.getString(R.string.cloud_library_type_torrents)
            CloudLibraryItemType.Usenet -> context.getString(R.string.cloud_library_type_usenet)
            CloudLibraryItemType.WebDownload -> context.getString(R.string.cloud_library_type_web)
            CloudLibraryItemType.File -> context.getString(R.string.cloud_library_type_files)
        }

    /**
     * The Type menu: All, Movies, Shows (and any other type the titles have, like anime), then
     * Sports Teams, Sports Leagues, Events and Custom Channels - always there, even when empty.
     */
    private fun buildTypeTabsWithCounts(
        allTypeItems: List<LibraryEntry>,
        filteredItems: List<LibraryEntry>,
        extraItems: List<LibraryEntry>,
        /** How many titles each IMDb type has (empty: no IMDb account, no IMDb types). */
        imdbCounts: Map<String, Int>
    ): List<LibraryTypeTab> {
        val countByType = mutableMapOf<String, Int>()
        filteredItems.forEach { entry ->
            val key = entry.libraryTypeKey()
            countByType[key] = (countByType[key] ?: 0) + 1
        }
        extraItems.forEach { entry ->
            countByType[entry.type] = (countByType[entry.type] ?: 0) + 1
        }
        val otherTitleTypes = allTypeItems
            .map { it.libraryTypeKey() }
            .distinct()
            .filter { it != LIBRARY_TYPE_MOVIE && it != LIBRARY_TYPE_SHOW && it !in LIBRARY_EXTRA_TYPES }
            .sorted()
        countByType.putAll(imdbCounts)
        val imdbKeys = listOf(LIBRARY_TYPE_IMDB_LIST, LIBRARY_TYPE_IMDB_TV_RATED).filter { it in imdbCounts }
        val keys = listOf(LIBRARY_TYPE_MOVIE, LIBRARY_TYPE_SHOW) + otherTitleTypes + imdbKeys + LIBRARY_EXTRA_TYPE_ORDER
        val allTab = LibraryTypeTab(key = LibraryTypeTab.ALL_KEY, label = "${context.getString(R.string.library_type_all)} (${filteredItems.size})")
        return listOf(allTab) + keys.map { key ->
            LibraryTypeTab(key = key, label = "${prettifyTypeLabel(key)} (${countByType[key] ?: 0})")
        }
    }

    // ── The IMDb watchlist ──

    private fun observeImdbList() {
        imdbSync.syncIfStale()
        viewModelScope.launch {
            combine(imdbSync.account, imdbSync.watchlistLinked, imdbSync.ratedSeriesLinked) { account, watchlist, ratedSeries ->
                ImdbLibraryLists(
                    connected = account.isConnected,
                    error = account.error,
                    watchlist = watchlist.mapNotNull(::imdbEntryOf),
                    ratedSeries = ratedSeries.mapNotNull(::imdbEntryOf)
                )
            }.collectLatest { lists ->
                _uiState.update { current ->
                    current.copy(
                        imdbConnected = lists.connected,
                        imdbError = lists.error,
                        imdbItems = if (lists.connected) lists.watchlist else emptyList(),
                        imdbRatedSeries = if (lists.connected) lists.ratedSeries else emptyList()
                    ).withVisibleItems()
                }
            }
        }
    }

    private class ImdbLibraryLists(
        val connected: Boolean,
        val error: com.nuvio.tv.data.remote.imdb.ImdbException.Reason?,
        val watchlist: List<LibraryEntry>,
        val ratedSeries: List<LibraryEntry>
    )

    /**
     * An IMDb list's title as a Library title: its TMDB movie or show (the app's one catalog), so
     * its poster, backdrop, page and cast are TMDB's. IMDb only says it's on the list.
     */
    private fun imdbEntryOf(linked: com.nuvio.tv.data.imdb.ImdbLinkedTitle): LibraryEntry? {
        val title = linked.imdb
        val tmdb = linked.tmdb
        if (title.isEpisode || title.titleType == "videoGame") return null
        val poster = tmdb.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
        return LibraryEntry(
            id = tmdb.contentId,
            type = if (tmdb.isSeries) LIBRARY_TYPE_SHOW else LIBRARY_TYPE_MOVIE,
            name = tmdb.name ?: title.name,
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = tmdb.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" },
            logo = null,
            description = null,
            releaseInfo = (tmdb.year ?: title.year)?.toString(),
            imdbRating = title.imdbRating,
            genres = title.genres,
            addonBaseUrl = null,
            listedAt = title.addedAtMs,
            imdbId = title.id,
            tmdbId = tmdb.tmdbId,
            rawPosterUrl = poster
        )
    }

    // ── Sports teams, leagues, events and custom channels ──

    private var libraryTeams: Map<String, SportsTeam> = emptyMap()
    private var libraryLeagues: Map<String, SportsLeague> = emptyMap()
    private var libraryEvents: Map<String, SportsEvent> = emptyMap()

    private fun observeLibraryExtras() {
        viewModelScope.launch {
            // Sports Teams are "My Teams", Sports Leagues "My Sports" (as on the Sports page).
            val myTeams = combine(sportsLibraryStore.teams, sportsPreferences.favoriteTeamIds) { teams, ids ->
                teams.filter { it.team.id in ids }
            }
            combine(
                myTeams,
                sportsLibraryStore.leagues,
                sportsLibraryStore.eventDetails,
                libraryPreferences.libraryItems,
                liveTvDataStore.customChannels
            ) { teams, leagues, eventDetails, saved, channels ->
                libraryTeams = teams.associate { it.team.id to it.team }
                libraryLeagues = leagues.associate { it.league.id to it.league }
                libraryEvents = eventDetails
                val items = mutableListOf<LibraryEntry>()
                val logos = mutableMapOf<String, List<String>>()
                val events = mutableMapOf<String, SportsEvent>()
                teams.forEach { (team, addedAt) ->
                    items += extraEntry(
                        id = team.id, type = LIBRARY_TYPE_SPORTS_TEAM, name = team.name,
                        description = team.leagueName, genre = team.sportName, art = team.badgeUrl, addedAt = addedAt
                    )
                    logos["$LIBRARY_TYPE_SPORTS_TEAM:${team.id}"] = listOfNotNull(team.badgeUrl)
                }
                leagues.forEach { (league, addedAt) ->
                    items += extraEntry(
                        id = league.id, type = LIBRARY_TYPE_SPORTS_LEAGUE, name = league.name,
                        description = league.sportName, genre = league.sportName, art = league.badgeUrl, addedAt = addedAt
                    )
                    logos["$LIBRARY_TYPE_SPORTS_LEAGUE:${league.id}"] = listOfNotNull(league.badgeUrl)
                }
                saved.filter { it.type.equals(LIBRARY_TYPE_EVENT, ignoreCase = true) }.forEach { item ->
                    val event = eventDetails[item.id]
                    items += extraEntry(
                        id = item.id, type = LIBRARY_TYPE_EVENT, name = item.name,
                        description = item.description, genre = item.genres.firstOrNull(),
                        art = item.background, addedAt = item.addedAt
                    )
                    val badges: List<String> = if (event != null) {
                        listOfNotNull(event.homeTeamBadgeUrl, event.awayTeamBadgeUrl)
                    } else {
                        emptyList()
                    }
                    logos["$LIBRARY_TYPE_EVENT:${item.id}"] = badges.ifEmpty { listOfNotNull(item.background) }
                    if (event != null) events["$LIBRARY_TYPE_EVENT:${item.id}"] = event.withStatusNow()
                }
                channels.forEach { channel ->
                    items += extraEntry(
                        id = channel.id, type = LIBRARY_TYPE_CUSTOM_CHANNEL, name = channel.name,
                        description = null, genre = null, art = channel.logoUrl, addedAt = 0L
                    )
                    logos["$LIBRARY_TYPE_CUSTOM_CHANNEL:${channel.id}"] = listOfNotNull(channel.logoUrl)
                }
                Triple(items.toList(), logos.toMap(), events.toMap())
            }.collectLatest { (items, logos, events) ->
                _uiState.update { current ->
                    current.copy(extraItems = items, extraLogos = logos, extraEvents = events).withVisibleItems()
                }
            }
        }
    }

    private fun extraEntry(
        id: String,
        type: String,
        name: String,
        description: String?,
        genre: String?,
        art: String?,
        addedAt: Long
    ) = LibraryEntry(
        id = id,
        type = type,
        name = name,
        poster = null,
        posterShape = PosterShape.LANDSCAPE,
        background = art,
        logo = art,
        description = description,
        releaseInfo = null,
        imdbRating = null,
        genres = listOfNotNull(genre),
        addonBaseUrl = null,
        listedAt = addedAt
    )

    /**
     * Where an extra item opens: a channel its page (as from the Guide), a league its page, a team
     * its league's page, a game its page.
     */
    fun extraRoute(entry: LibraryEntry): String? = when (entry.type) {
        LIBRARY_TYPE_CUSTOM_CHANNEL -> Screen.ChannelProfile.createRoute(entry.id)
        LIBRARY_TYPE_SPORTS_LEAGUE -> {
            val league = libraryLeagues[entry.id]
            Screen.SportsLeagueDetail.createRoute(
                leagueId = entry.id,
                leagueName = league?.name ?: entry.name,
                sportName = league?.sportName.orEmpty(),
                badgeUrl = league?.badgeUrl
            )
        }
        LIBRARY_TYPE_SPORTS_TEAM -> {
            val team = libraryTeams[entry.id]
            val leagueId = team?.leagueId
            if (team != null && leagueId != null) {
                // The team's own page.
                Screen.SportsTeamDetail.createRoute(
                    leagueId = leagueId,
                    teamId = team.id,
                    name = team.name,
                    badgeUrl = team.badgeUrl,
                    color = team.color
                )
            } else {
                Screen.SportsTeams.route
            }
        }
        LIBRARY_TYPE_EVENT -> {
            // A program saved from the Guide (not a game ESPN has): its channel's page.
            val guideChannelId = com.nuvio.tv.ui.screens.livetv.LiveEventLibrary.channelIdOf(entry.id)
            if (guideChannelId != null) {
                Screen.ChannelProfile.createRoute(guideChannelId)
            } else {
                gameRoute(libraryEvents[entry.id]?.withStatusNow() ?: eventFromEntry(entry))
            }
        }
        else -> null
    }

    /**
     * Takes the item out of the Library: a team out of "My Teams", a league out of "My Sports", a
     * channel out of the Guide's Custom list.
     */
    fun removeExtra(entry: LibraryEntry) {
        viewModelScope.launch {
            when (entry.type) {
                LIBRARY_TYPE_CUSTOM_CHANNEL -> {
                    if (entry.id in liveTvDataStore.customChannelIds.value) liveTvDataStore.toggleCustomChannel(entry.id)
                }
                LIBRARY_TYPE_SPORTS_TEAM -> {
                    if (entry.id in sportsPreferences.favoriteTeamIds.value) sportsPreferences.toggleFavoriteTeam(entry.id)
                }
                LIBRARY_TYPE_SPORTS_LEAGUE -> sportsLibraryStore.removeLeague(entry.id)
                LIBRARY_TYPE_EVENT -> {
                    libraryPreferences.removeItem(itemId = entry.id, itemType = LIBRARY_TYPE_EVENT)
                    sportsLibraryStore.forgetEvent(entry.id)
                }
            }
        }
    }

    /** A saved game that's live now (it plays on its channel instead of opening its page). */
    fun liveGameOf(entry: LibraryEntry): SportsEvent? {
        if (entry.type != LIBRARY_TYPE_EVENT) return null
        if (com.nuvio.tv.ui.screens.livetv.LiveEventLibrary.isProgramEventId(entry.id)) return null
        val event = libraryEvents[entry.id]?.withStatusNow() ?: return null
        return event.takeIf { it.isLiveAt(System.currentTimeMillis()) }
    }

    /** A game saved a while ago that should be over by now shows as finished. */
    private fun SportsEvent.withStatusNow(): SportsEvent {
        val start = startTimeMs ?: return this
        val over = System.currentTimeMillis() > start + GAME_OVER_AFTER_MS
        return if (over && status != SportsEventStatus.FINISHED) copy(status = SportsEventStatus.FINISHED) else this
    }

    /** A game bookmarked before its details were kept: what its Library item says ("Home vs Away • League"). */
    private fun eventFromEntry(entry: LibraryEntry): SportsEvent {
        val matchup = entry.description?.substringBefore(" • ").orEmpty()
        val league = entry.description?.substringAfter(" • ", "")?.takeIf { it.isNotBlank() }
        val home = matchup.substringBefore(" vs ", "").ifBlank { entry.name }
        val away = matchup.substringAfter(" vs ", "")
        val parsed = EspnSportsClient.parseEventId(entry.id)
        return SportsEvent(
            id = entry.id,
            name = entry.name,
            sportName = entry.genres.firstOrNull(),
            leagueId = parsed?.let { "${it.sportPath}:${it.leaguePath}" },
            leagueName = league,
            homeTeamId = null,
            homeTeamName = home,
            homeTeamBadgeUrl = entry.background,
            awayTeamId = null,
            awayTeamName = away,
            awayTeamBadgeUrl = null,
            homeScore = null,
            awayScore = null,
            startTimeMs = null,
            venue = null,
            status = SportsEventStatus.UPCOMING
        )
    }

    private fun gameRoute(event: SportsEvent): String = Screen.SportsGameDetail.createRoute(
        eventId = event.id,
        name = event.name,
        sportName = event.sportName,
        leagueId = event.leagueId,
        leagueName = event.leagueName,
        homeTeamId = event.homeTeamId,
        homeTeamName = event.homeTeamName,
        homeTeamBadgeUrl = event.homeTeamBadgeUrl,
        homeScore = event.homeScore,
        awayTeamId = event.awayTeamId,
        awayTeamName = event.awayTeamName,
        awayTeamBadgeUrl = event.awayTeamBadgeUrl,
        awayScore = event.awayScore,
        startTimeMs = event.startTimeMs,
        venue = event.venue,
        status = event.status.name,
        statusDetail = event.statusDetail
    )

    private companion object {
        /** A game is taken as over this long after it started. */
        const val GAME_OVER_AFTER_MS = 4L * 60 * 60 * 1000
    }
}

// Strip leading English articles ("The", "A", "An") when sorting by title, so
// "The Walking Dead" sorts under W and not T. Matches how streaming services
// and most media libraries order titles alphabetically.
private val LEADING_ARTICLE_REGEX = Regex("^(the|an|a)\\s+", RegexOption.IGNORE_CASE)

private fun titleSortKey(title: String): String =
    title.trim().replace(LEADING_ARTICLE_REGEX, "").lowercase(Locale.ROOT)
