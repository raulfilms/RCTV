package com.nuvio.tv.core.bugreport

import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RCTV (bug reports): the favorites and the watch history as they stand ("library" in a report):
 * how many, and the last few saved (title, episode, how far, when). For "Favorites not working"
 * and "Watch history not updating".
 */
@Singleton
class BugReportLibraryProbe @Inject constructor(
    library: LibraryRepository,
    progress: WatchProgressRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var favorites: List<LibraryEntry>? = null
    @Volatile private var history: List<WatchProgress>? = null

    init {
        scope.launch { library.libraryItems.catch { }.collect { favorites = it } }
        scope.launch { progress.allProgress.catch { }.collect { history = it } }
        BugReportProbes.register("library", this) {
            val now = System.currentTimeMillis()
            JSONObject().apply {
                put("favorites", favorites?.size ?: -1)
                favorites?.take(LAST)?.let { list ->
                    put("favorites_first", JSONArray(list.map { "${it.name} (${it.type})" }))
                }
                val watched = history
                put("history", watched?.size ?: -1)
                watched?.sortedByDescending { it.lastWatched }?.take(LAST)?.let { list ->
                    put("history_last", JSONArray().apply {
                        list.forEach { item ->
                            put(JSONObject().apply {
                                put("title", item.name)
                                put("type", item.contentType)
                                item.season?.let { put("season", it) }
                                item.episode?.let { put("episode", it) }
                                if (item.duration > 0L) put("watched_percent", (item.position * 100 / item.duration).toInt())
                                put("minutes_ago", (now - item.lastWatched) / 60_000)
                            })
                        }
                    })
                }
            }
        }
    }

    private companion object {
        const val LAST = 5
    }
}
