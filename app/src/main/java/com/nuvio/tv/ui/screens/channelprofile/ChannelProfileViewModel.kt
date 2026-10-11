package com.nuvio.tv.ui.screens.channelprofile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.data.channelprofile.ChannelBrandIds
import com.nuvio.tv.data.channelprofile.ChannelProfileRepository
import com.nuvio.tv.data.channelprofile.EpgMovieTitle
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.livetv.cleanProgramTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class ChannelProfileUiState(
    val brandKey: String? = null,
    /** Featured shows (or movies) for the banner under the logo. */
    val heroItems: List<MetaPreview> = emptyList(),
    val isHeroLoading: Boolean = true,
    val tvShows: CatalogRow? = null,
    val movies: CatalogRow? = null,
    val isRowsLoading: Boolean = true,
    /** The person's in-progress titles that belong to this channel. */
    val continueWatching: List<ContinueWatchingItem> = emptyList(),
    /** Whether each featured title is in My List, by id. */
    val heroInLibrary: Map<String, Boolean> = emptyMap()
)

/**
 * A channel's profile page (opened from its logo in the Guide): the family's featured shows,
 * TV shows and movies from TMDB, and Continue Watching narrowed to the family's titles.
 * The live rows come straight from the Guide's channels (see [ChannelProfileScreen]).
 */
@HiltViewModel
class ChannelProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ChannelProfileRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val libraryRepository: LibraryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChannelProfileUiState())
    val uiState: StateFlow<ChannelProfileUiState> = _uiState.asStateFlow()

    private var boundKey: String? = null
    private var loadJob: Job? = null
    private var continueWatchingJob: Job? = null
    private var libraryJob: Job? = null

    /**
     * Loads the page for [brand]. Called again once the guide arrives (its shows and movies help
     * find the channel's content); the same brand with the same guide state is a no-op.
     */
    fun bind(brand: ChannelBrand, epgSeries: List<String>, epgMovies: List<EpgMovieTitle>) {
        val key = "${brand.key}|${epgSeries.size}|${epgMovies.size}"
        if (key == boundKey) return
        boundKey = key
        loadJob?.cancel()
        _uiState.update { state ->
            if (state.brandKey == brand.key) state.copy(isRowsLoading = true)
            else ChannelProfileUiState(brandKey = brand.key)
        }
        loadJob = viewModelScope.launch {
            val ids = safe { repository.brandIds(brand, epgSeries) } ?: ChannelBrandIds(emptyList(), emptyList())
            val showsTitle = context.getString(R.string.channel_profile_tv_shows)
            val moviesTitle = context.getString(R.string.channel_profile_movies)
            val (shows, movies) = coroutineScope {
                val showsJob = async { safe { repository.tvShows(brand, ids, epgSeries, showsTitle) } }
                val moviesJob = async { safe { repository.movies(brand, ids, epgMovies, moviesTitle) } }
                showsJob.await() to moviesJob.await()
            }
            _uiState.update { it.copy(tvShows = shows, movies = movies, isRowsLoading = false) }

            val heroSource = shows?.items.orEmpty().ifEmpty { movies?.items.orEmpty() }
            val hero = safe { repository.heroItems(heroSource) }.orEmpty()
            _uiState.update { it.copy(heroItems = hero, isHeroLoading = false) }
            observeLibrary(hero)

            val rowIds = (shows?.items.orEmpty() + movies?.items.orEmpty()).map { it.id }.toSet()
            observeContinueWatching(brand, ids, rowIds)
        }
    }

    private suspend fun <T> safe(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * Continue Watching here keeps only titles TMDB credits to the channel's networks (shows) or
     * studios (movies), plus anything on the page's own rows.
     */
    @OptIn(FlowPreview::class)
    private fun observeContinueWatching(brand: ChannelBrand, ids: ChannelBrandIds, rowIds: Set<String>) {
        continueWatchingJob?.cancel()
        continueWatchingJob = viewModelScope.launch {
            watchProgressRepository.continueWatching
                .debounce(300)
                .collectLatest { progressList ->
                    val latestPerTitle = progressList
                        .filter { it.isInProgress() }
                        .sortedByDescending { it.lastWatched }
                        .distinctBy { it.contentId }
                        .take(40)
                    val mine = coroutineScope {
                        latestPerTitle.map { progress ->
                            async {
                                progress.takeIf {
                                    safe { repository.belongsToBrand(it.contentId, it.contentType, brand, ids, rowIds) } == true
                                }
                            }
                        }.awaitAll()
                    }.filterNotNull()
                    _uiState.update { state ->
                        state.copy(continueWatching = mine.map { ContinueWatchingItem.InProgress(progress = it) })
                    }
                }
        }
    }

    private fun observeLibrary(items: List<MetaPreview>) {
        libraryJob?.cancel()
        if (items.isEmpty()) return
        libraryJob = viewModelScope.launch {
            combine(items.map { item -> libraryRepository.isInLibrary(item.id, item.apiType).map { item.id to it } }) { pairs ->
                pairs.toMap()
            }.collect { membership -> _uiState.update { it.copy(heroInLibrary = membership) } }
        }
    }

    /** Adds the featured title to My List, or takes it out. */
    fun toggleLibrary(item: MetaPreview) {
        viewModelScope.launch {
            safe { libraryRepository.toggleDefault(item.toLibraryEntryInput()) }
        }
    }

    fun removeContinueWatching(item: ContinueWatchingItem) {
        val contentId = when (item) {
            is ContinueWatchingItem.InProgress -> item.progress.contentId
            is ContinueWatchingItem.NextUp -> item.info.contentId
        }
        _uiState.update { state ->
            state.copy(continueWatching = state.continueWatching.filterNot { existing ->
                when (existing) {
                    is ContinueWatchingItem.InProgress -> existing.progress.contentId == contentId
                    is ContinueWatchingItem.NextUp -> existing.info.contentId == contentId
                }
            })
        }
        viewModelScope.launch {
            safe { watchProgressRepository.removeProgress(contentId = contentId, season = null, episode = null) }
        }
    }

    private fun MetaPreview.toLibraryEntryInput(): LibraryEntryInput {
        val year = Regex("(\\d{4})").find(releaseInfo ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        val parsedIds = parseContentIds(id)
        return LibraryEntryInput(
            itemId = id,
            itemType = apiType,
            title = name,
            year = year,
            traktId = parsedIds.trakt,
            simklId = parsedIds.simkl,
            imdbId = parsedIds.imdb,
            tmdbId = parsedIds.tmdb,
            poster = poster,
            posterShape = posterShape,
            background = background,
            logo = logo,
            description = description,
            releaseInfo = releaseInfo,
            imdbRating = imdbRating,
            genres = genres,
            addonBaseUrl = null
        )
    }
}

/** Shows and movies seen in a channel family's guide, used to find its content on TMDB. */
object ChannelGuideTitles {

    private val notShows = setOf(
        "paid programming", "paid program", "to be announced", "tba", "off air", "sign off", "local programming",
        "programming", "infomercial", "movie", "movies", "news", "sports", "special", "film"
    )
    private val trailingYear = Regex("""\s*\((\d{4})\)\s*$""")

    private fun EpgProgram.categoryWords(): List<String> =
        (categories + listOfNotNull(category)).map { it.lowercase(Locale.ROOT) }

    private fun EpgProgram.isMovie(): Boolean = categoryWords().any { "movie" in it || "film" in it }

    /** Shows and talk shows, not games, newscasts, movies or shopping. */
    private fun EpgProgram.isShow(): Boolean = categoryWords().none { word ->
        listOf("sport", "news", "paid", "shopping", "infomercial", "movie", "film").any { it in word }
    }

    private fun programsOf(channels: List<LiveTvChannel>, epg: Map<String, List<EpgProgram>>): List<EpgProgram> =
        channels.mapNotNull { it.epgChannelId }.distinct().flatMap { epg[it].orEmpty() }

    /** The family's shows, most aired first. */
    fun series(channels: List<LiveTvChannel>, epg: Map<String, List<EpgProgram>>, limit: Int = 12): List<String> =
        programsOf(channels, epg)
            .filter { it.isShow() }
            .map { cleanProgramTitle(it.title) }
            .filter { it.length >= 2 && it.lowercase(Locale.ROOT) !in notShows }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }

    /** The family's movies still to air (or airing), soonest first: "Heat (1995)" -> Heat, 1995. */
    fun movies(
        channels: List<LiveTvChannel>,
        epg: Map<String, List<EpgProgram>>,
        nowMs: Long,
        limit: Int = 20
    ): List<EpgMovieTitle> =
        programsOf(channels, epg)
            .filter { it.isMovie() && it.endMs > nowMs }
            .sortedBy { it.startMs }
            .map { program ->
                val title = cleanProgramTitle(program.title)
                val year = trailingYear.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
                EpgMovieTitle(title = title.replace(trailingYear, "").trim(), year = year)
            }
            .filter { it.title.length >= 2 && it.title.lowercase(Locale.ROOT) !in notShows }
            .distinctBy { it.title.lowercase(Locale.ROOT) }
            .take(limit)
}
