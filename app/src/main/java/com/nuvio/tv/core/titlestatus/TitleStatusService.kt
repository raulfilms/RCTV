package com.nuvio.tv.core.titlestatus

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.imdb.ImdbSyncService
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
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
    /** RCTV: whether a title with any of [ids] is in the IMDb watchlist (a title's page). */
    fun inImdbList(vararg ids: String?): Boolean = ids.any { !it.isNullOrBlank() && it in imdbList }

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
    @ApplicationContext context: Context,
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

    /**
     * RCTV (owner): the ids one title goes by ("tmdb:603" on a TMDB row, "tt0133093" once its page
     * opened): a title saved in the Library or watched under one id shows its marks on the cards
     * carrying the other. Learned as title pages open; kept on the TV.
     */
    private val prefs = context.getSharedPreferences(ALIAS_PREFS, Context.MODE_PRIVATE)
    private val aliases = MutableStateFlow(loadAliases())

    /** RCTV: Library changes made on a title's page show at once, until the Library itself has them. */
    private data class LibraryChanges(val added: Set<String> = emptySet(), val removed: Set<String> = emptySet())
    private val libraryChanges = MutableStateFlow(LibraryChanges())

    private val realLibrary = libraryRepository.libraryItems.map { items -> items.mapTo(HashSet()) { it.id } }
    private val shownLibrary = combine(realLibrary, libraryChanges, aliases) { library, changes, sameTitle ->
        (library.withAliases(sameTitle) + changes.added.withAliases(sameTitle)) - changes.removed.withAliases(sameTitle)
    }

    val status: StateFlow<TitleStatus> = combine(
        watchProgressRepository.observeWatchedMovieIds(),
        watchedSeries.fullyWatchedSeriesIds,
        shownLibrary,
        imdbIds,
        aliases
    ) { movies, series, library, imdb, sameTitle ->
        TitleStatus(
            watchedMovies = movies.withAliases(sameTitle),
            watchedSeries = series.withAliases(sameTitle),
            library = library,
            imdbList = imdb.withAliases(sameTitle)
        )
    }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, TitleStatus())

    /** RCTV: [ids] are one title (its page opened with one id and loaded the other). */
    fun noteSameTitle(ids: Collection<String?>) {
        val clean = ids.mapNotNull { it?.trim()?.takeIf { id -> id.isNotEmpty() } }.toSet()
        if (clean.size < 2) return
        val current = aliases.value
        val group = clean + clean.flatMap { current[it].orEmpty() }
        if (group.all { current[it] == group }) return
        var updated = current + group.associateWith { group }
        if (updated.size > MAX_ALIAS_IDS) {
            updated = updated.entries.drop(updated.size - MAX_ALIAS_IDS).associate { it.key to it.value }
        }
        aliases.value = updated
        scope.launch { saveAliases(updated) }
    }

    /** RCTV: the title was just added to (or removed from) the Library on its page: its cards show it now. */
    fun noteLibrary(ids: Collection<String?>, inLibrary: Boolean) {
        val clean = ids.mapNotNull { it?.trim()?.takeIf { id -> id.isNotEmpty() } }.toSet()
        if (clean.isEmpty()) return
        libraryChanges.update { changes ->
            if (inLibrary) LibraryChanges(changes.added + clean, changes.removed - clean)
            else LibraryChanges(changes.added - clean, changes.removed + clean)
        }
    }

    private fun loadAliases(): Map<String, Set<String>> = runCatching {
        val groups = JSONArray(prefs.getString(ALIAS_KEY, "[]") ?: "[]")
        buildMap {
            for (i in 0 until groups.length()) {
                val ids = groups.optJSONArray(i) ?: continue
                val group = (0 until ids.length()).mapNotNull { ids.optString(it).takeIf(String::isNotBlank) }.toSet()
                if (group.size >= 2) group.forEach { put(it, group) }
            }
        }
    }.getOrDefault(emptyMap())

    private fun saveAliases(map: Map<String, Set<String>>) {
        val json = JSONArray().apply { map.values.distinct().forEach { group -> put(JSONArray(group.toList())) } }
        runCatching { prefs.edit().putString(ALIAS_KEY, json.toString()).apply() }
    }

    init {
        // Each profile's fully watched series (kept on the TV).
        scope.launch {
            profileManager.activeProfileId.collect { id -> runCatching { watchedSeries.loadFromDisk(id) } }
        }
        // RCTV: a change made on a title's page leaves the list once the Library itself has it.
        scope.launch {
            realLibrary.collect { library ->
                libraryChanges.update { changes ->
                    if (changes.added.isEmpty() && changes.removed.isEmpty()) return@update changes
                    val known = library.withAliases(aliases.value)
                    LibraryChanges(
                        added = changes.added.filterNot { it in known }.toSet(),
                        removed = changes.removed.filter { it in known }.toSet()
                    )
                }
            }
        }
    }

    private companion object {
        const val ALIAS_PREFS = "rctv_title_aliases"
        const val ALIAS_KEY = "groups"
        const val MAX_ALIAS_IDS = 4000

        fun Set<String>.withAliases(sameTitle: Map<String, Set<String>>): Set<String> =
            if (sameTitle.isEmpty() || isEmpty()) this else this + flatMap { sameTitle[it].orEmpty() }
    }
}
