package com.nuvio.tv.core.imdb

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbEpisodeEnrichment
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.domain.model.Meta
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ImdbEpisodeOrder"

/**
 * RCTV: cartoons told in halves (SpongeBob, The Fairly OddParents, Rugrats...) are listed the IMDb
 * way, the way the stream addons (Torrentio, AIOStreams) and the files they find number them: a
 * series' episodes are checked against IMDb's (kept on the device for a week), and a season IMDb
 * lists with its halves together is rebuilt so ("Help Wanted / Reef Blower / Tea at the Treedome" is
 * episode 1, "Bubblestand / Ripped Pants" episode 2); every other season stays as the addon sends it.
 * Only cartoons (animated, or short episodes) are checked.
 */
@Singleton
class ImdbEpisodeOrder @Inject constructor(
    @ApplicationContext context: Context,
    okHttpClient: OkHttpClient,
    private val tmdbService: TmdbService
) {
    private val source = ImdbEpisodeSource(okHttpClient)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = File(context.filesDir, FILE_NAME)
    private val fileLock = Any()

    private class Known(val savedAtMs: Long, val episodes: List<ImdbEpisode>)

    private val known = ConcurrentHashMap<String, Known>()
    private val failedAtMs = ConcurrentHashMap<String, Long>()
    private val inFlight = HashMap<String, Deferred<List<ImdbEpisode>?>>()
    @Volatile private var loaded = false

    /**
     * [meta] with the seasons IMDb tells another way rebuilt as IMDb has them; and whether that's
     * settled (false while IMDb is still being asked: what's shown is the addon's for now, and is
     * asked again shortly).
     */
    suspend fun apply(meta: Meta): Pair<Meta, Boolean> {
        if (!isCartoonSeries(meta)) return meta to true
        val imdbId = imdbIdOf(meta) ?: return meta to true
        val seasons = meta.videos.mapNotNull { it.season }.filter { it > 0 }.toSet()
        if (seasons.isEmpty()) return meta to true
        ensureLoaded()

        val now = System.currentTimeMillis()
        val cached = known[imdbId]?.takeIf { now - it.savedAtMs < KNOWN_MAX_AGE_MS }
        val episodes = when {
            cached != null -> cached.episodes
            (failedAtMs[imdbId]?.let { now - it < RETRY_AFTER_MS } == true) -> return meta to true
            else -> {
                val lookup = synchronized(inFlight) {
                    inFlight[imdbId]?.takeIf { !it.isCompleted } ?: scope.async(start = CoroutineStart.LAZY) {
                        fetch(imdbId, seasons)
                    }.also {
                        inFlight[imdbId] = it
                        it.start()
                    }
                }
                val result = withTimeoutOrNull(WAIT_MS) { lookup.await() }
                when {
                    result != null -> result
                    lookup.isCompleted -> return meta to true // IMDb couldn't be asked: as the addon has it
                    else -> return meta to false
                }
            }
        }
        if (episodes.isEmpty()) return meta to true
        val (videos, orders) = runCatching { ImdbEpisodeOrderMatcher.reorder(meta.videos, episodes) }
            .onFailure { Log.w(TAG, "Couldn't line up $imdbId: ${it.message}") }
            .getOrNull() ?: return meta to true
        if (orders.isEmpty()) return meta to true
        Log.i(TAG, "${meta.name} ($imdbId): seasons ${orders.keys} as IMDb has them")
        register(meta, imdbId, orders)
        return meta.copy(videos = videos) to true
    }

    private fun fetch(imdbId: String, seasons: Set<Int>): List<ImdbEpisode>? {
        val episodes = source.episodes(imdbId, seasons)
        if (episodes == null) {
            failedAtMs[imdbId] = System.currentTimeMillis()
            return null
        }
        failedAtMs.remove(imdbId)
        known[imdbId] = Known(System.currentTimeMillis(), episodes)
        save()
        return episodes
    }

    /** TMDB's episodes of this show will be told the same way (its own numbers are the halves too). */
    private fun register(meta: Meta, imdbId: String, orders: Map<Int, ImdbSeasonOrder>) {
        val tmdbIdFromMeta = meta.id.removePrefix("tmdb:").substringBefore(':').takeIf { meta.id.startsWith("tmdb:") && it.all(Char::isDigit) }
        if (tmdbIdFromMeta != null) {
            EpisodeOrderRegistry.registerTmdb(tmdbIdFromMeta, orders)
            return
        }
        scope.launch {
            val tmdbId = runCatching { tmdbService.ensureTmdbId(imdbId, "series", fallbackImdbId = imdbId) }.getOrNull()
                ?: return@launch
            EpisodeOrderRegistry.registerTmdb(tmdbId, orders)
        }
    }

    // ------------------------------------------------------------------ Which shows

    /** A series of cartoons: animated (in any language's word), for kids, or with short episodes. */
    private fun isCartoonSeries(meta: Meta): Boolean {
        if (meta.apiType !in SERIES_TYPES) return false
        if (meta.videos.size < 2) return false
        val genres = meta.genres.map { it.lowercase() }
        if (genres.any { genre -> CARTOON_WORDS.any { genre.contains(it) } }) return true
        val minutes = meta.runtime?.let { RUNTIME_MINUTES.find(it)?.value?.toIntOrNull() }
        return minutes != null && minutes in 1..SHORT_EPISODE_MINUTES
    }

    private fun imdbIdOf(meta: Meta): String? =
        meta.imdbId?.substringBefore(':')?.takeIf { it.startsWith("tt") }
            ?: meta.id.substringBefore(':').takeIf { it.startsWith("tt") }

    // ------------------------------------------------------------------ On the device

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(fileLock) {
            if (loaded) return
            runCatching { read() }.onFailure { Log.w(TAG, "Couldn't read the kept episodes: ${it.message}") }
            loaded = true
        }
    }

    private fun read() {
        if (!file.exists()) return
        val json = JSONObject(file.readText())
        if (json.optInt("v") != FILE_VERSION) return
        val shows = json.optJSONObject("shows") ?: return
        val keys = shows.keys()
        while (keys.hasNext()) {
            val imdbId = keys.next()
            val entry = shows.optJSONObject(imdbId) ?: continue
            val list = entry.optJSONArray("e") ?: continue
            val episodes = (0 until list.length()).mapNotNull { i ->
                val item = list.optJSONArray(i) ?: return@mapNotNull null
                ImdbEpisode(item.optInt(0), item.optInt(1), item.optString(2))
            }
            known.putIfAbsent(imdbId, Known(entry.optLong("t"), episodes))
        }
    }

    private fun save() {
        scope.launch {
            synchronized(fileLock) {
                runCatching {
                    // The most recently checked shows only.
                    val kept = known.entries.sortedByDescending { it.value.savedAtMs }.take(MAX_SHOWS_KEPT)
                    val shows = JSONObject()
                    kept.forEach { (imdbId, entry) ->
                        shows.put(imdbId, JSONObject().apply {
                            put("t", entry.savedAtMs)
                            put("e", JSONArray().apply {
                                entry.episodes.forEach { put(JSONArray().put(it.season).put(it.episode).put(it.title)) }
                            })
                        })
                    }
                    val tmp = File(file.parentFile, "$FILE_NAME.tmp")
                    tmp.writeText(JSONObject().put("v", FILE_VERSION).put("shows", shows).toString())
                    if (!tmp.renameTo(file)) {
                        file.delete()
                        tmp.renameTo(file)
                    }
                }.onFailure { Log.w(TAG, "Couldn't keep the episodes: ${it.message}") }
            }
        }
    }

    private companion object {
        const val FILE_NAME = "imdb_episode_order.json"
        const val FILE_VERSION = 1
        const val KNOWN_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        const val RETRY_AFTER_MS = 30L * 60 * 1000
        /** How long a show's page waits for IMDb the first time (then it's kept). */
        const val WAIT_MS = 6_000L
        const val MAX_SHOWS_KEPT = 200
        const val SHORT_EPISODE_MINUTES = 15
        val SERIES_TYPES = setOf("series", "tv")
        val CARTOON_WORDS = listOf("anima", "cartoon", "kids", "children", "infantil", "niños", "ninos")
        val RUNTIME_MINUTES = Regex("\\d+")
    }
}

/**
 * The seasons rebuilt the IMDb way (by the show's TMDB id), so TMDB's episode details (its
 * titles in the person's language, pictures, stories) are told the same way instead of laid over
 * the wrong episodes by number.
 */
object EpisodeOrderRegistry {
    private val byTmdb = ConcurrentHashMap<String, Map<Int, ImdbSeasonOrder>>()

    fun registerTmdb(tmdbId: String, orders: Map<Int, ImdbSeasonOrder>) {
        byTmdb[tmdbId] = orders
    }

    /**
     * TMDB's episodes of [tmdbId] ([episodes], by season and number) with each rebuilt season's told
     * the IMDb way: an IMDb episode's parts together. A rebuilt season TMDB has another number of
     * episodes of is left out (better the addon's than another episode's).
     */
    fun remap(
        tmdbId: String,
        episodes: Map<Pair<Int, Int>, TmdbEpisodeEnrichment>
    ): Map<Pair<Int, Int>, TmdbEpisodeEnrichment> {
        val orders = byTmdb[tmdbId.removePrefix("tmdb:")] ?: return episodes
        if (episodes.isEmpty()) return episodes
        val out = LinkedHashMap<Pair<Int, Int>, TmdbEpisodeEnrichment>()
        episodes.forEach { (key, value) -> if (key.first !in orders) out[key] = value }
        orders.forEach { (season, order) ->
            val seasonEpisodes = episodes.filterKeys { it.first == season }
            if (seasonEpisodes.size != order.originalCount) return@forEach
            order.groups.forEach { group ->
                val parts = group.episodes.mapNotNull { seasonEpisodes[season to it] }
                if (parts.isEmpty()) return@forEach
                val titles = parts.mapNotNull { it.title?.trim()?.takeIf(String::isNotEmpty) }
                val stories = parts.mapNotNull { part ->
                    part.overview?.trim()?.takeIf { it.isNotEmpty() }?.let { (part.title?.trim().orEmpty()) to it }
                }
                out[season to group.imdbEpisode] = TmdbEpisodeEnrichment(
                    title = titles.takeIf { it.size == group.episodes.size }?.joinToString(" / "),
                    overview = when {
                        stories.isEmpty() -> null
                        stories.size == 1 -> stories.single().second
                        else -> stories.joinToString("\n\n") { (title, story) -> if (title.isEmpty()) story else "$title: $story" }
                    },
                    thumbnail = parts.firstNotNullOfOrNull { it.thumbnail },
                    airDate = parts.firstNotNullOfOrNull { it.airDate },
                    runtimeMinutes = parts.map { it.runtimeMinutes }.takeIf { list -> list.all { it != null } }?.sumOf { it ?: 0 }
                )
            }
        }
        return out
    }
}
