package com.nuvio.tv.core.titlestatus

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.imdb.ImdbSyncService
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RCTV: what the profile watching has done with each title, for the marks on every movie and
 * series thumbnail ([com.nuvio.tv.ui.components.TitleStatusBadges]): watched all the way through,
 * in the app's Library, in the IMDb watchlist. Ids are the app's ("tmdb:603") and IMDb's ("tt…").
 */
data class TitleStatus(
    val watchedMovies: Set<String> = emptySet(),
    val watchedSeries: Set<String> = emptySet(),
    val library: Set<String> = emptySet(),
    val imdbList: Set<String> = emptySet()
) {
    fun of(item: MetaPreview): TitleMarks {
        val ids = listOfNotNull(item.id.takeIf { it.isNotBlank() }, item.imdbId?.takeIf { it.isNotBlank() })
        if (ids.isEmpty()) return TitleMarks()
        val isSeries = item.apiType.equals("series", ignoreCase = true) || item.apiType.equals("tv", ignoreCase = true)
        val watched = if (isSeries) watchedSeries else watchedMovies
        return TitleMarks(
            watched = ids.any { it in watched },
            inLibrary = ids.any { it in library },
            inImdbList = ids.any { it in imdbList }
        )
    }
}

/** The marks a thumbnail shows. */
data class TitleMarks(val watched: Boolean = false, val inLibrary: Boolean = false, val inImdbList: Boolean = false)

@Singleton
class TitleStatusService @Inject constructor(
    watchProgressRepository: WatchProgressRepository,
    private val watchedSeries: WatchedSeriesStateHolder,
    libraryRepository: LibraryRepository,
    imdbSync: ImdbSyncService,
    profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The IMDb watchlist by IMDb id and by the app's id (its TMDB title). */
    private val imdbIds = combine(imdbSync.watchlist, imdbSync.watchlistLinked) { titles, linked ->
        buildSet {
            titles.forEach { add(it.id) }
            linked.forEach { add(it.tmdb.contentId) }
        }
    }

    val status: StateFlow<TitleStatus> = combine(
        watchProgressRepository.observeWatchedMovieIds(),
        watchedSeries.fullyWatchedSeriesIds,
        libraryRepository.libraryItems.map { items -> items.mapTo(HashSet()) { it.id } },
        imdbIds
    ) { movies, series, library, imdb ->
        TitleStatus(watchedMovies = movies, watchedSeries = series, library = library, imdbList = imdb)
    }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, TitleStatus())

    init {
        // Each profile's fully watched series (kept on the TV).
        scope.launch {
            profileManager.activeProfileId.collect { id -> runCatching { watchedSeries.loadFromDisk(id) } }
        }
    }
}
