package com.nuvio.tv.core.kids

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RCTV, Kids profiles: on a Kids profile only movies and shows for children up to 13 show and play
 * ([KidsRatings]). A title's age ratings come from TMDB once and are kept for the session.
 * On any other profile nothing changes and nothing is looked up.
 */
@Singleton
class KidsContentGate @Inject constructor(
    private val profileManager: ProfileManager,
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService
) {
    /** True while a Kids profile is the one watching. */
    val active: StateFlow<Boolean> get() = profileManager.kidsProfileActive

    val isActive: Boolean get() = active.value

    /** Verdicts by "movie:<tmdbId>" / "tv:<tmdbId>". */
    private val verdicts = ConcurrentHashMap<String, KidsRatings.Verdict>()
    private val lookups = Semaphore(LOOKUPS_AT_ONCE)

    /** [items] as a Kids profile may see them (all of them on any other profile). */
    suspend fun keep(items: List<MetaPreview>): List<MetaPreview> {
        if (!isActive || items.isEmpty()) return items
        return coroutineScope {
            items.map { item -> async { item.takeIf { allows(it) } } }.awaitAll().filterNotNull()
        }
    }

    suspend fun allows(item: MetaPreview): Boolean =
        allows(item.id, isTv(item.type, item.rawType), item.genres, item.imdbId)

    /**
     * Whether a Kids profile may see and play this title (always true on other profiles). A title
     * that can't be checked (no connection, not on TMDB) isn't shown to a Kids profile.
     */
    suspend fun allows(id: String, isTv: Boolean, genres: List<String> = emptyList(), imdbId: String? = null): Boolean {
        if (!isActive) return true
        val verdict = verdictOf(id, isTv, imdbId) ?: return false
        return when (verdict) {
            KidsRatings.Verdict.ALLOWED -> true
            KidsRatings.Verdict.BLOCKED -> false
            KidsRatings.Verdict.UNKNOWN -> KidsRatings.isKidsGenre(genres)
        }
    }

    private suspend fun verdictOf(id: String, isTv: Boolean, imdbId: String?): KidsRatings.Verdict? {
        val tmdbId = directTmdbId(id) ?: resolveTmdbId(id, isTv, imdbId) ?: return null
        val cacheKey = key(isTv, tmdbId)
        verdicts[cacheKey]?.let { return it }
        val found = lookups.withPermit {
            withContext(Dispatchers.IO) {
                try {
                    if (isTv) {
                        val ratings = tmdbApi.getTvContentRatings(tmdbId, BuildConfig.TMDB_API_KEY)
                            .takeIf { it.isSuccessful }?.body()?.results ?: return@withContext null
                        KidsRatings.show(
                            ratings.mapNotNull { r -> r.iso31661?.let { it to listOfNotNull(r.rating) } }
                                .groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.flatten() }
                        )
                    } else {
                        val releases = tmdbApi.getMovieReleaseDates(tmdbId, BuildConfig.TMDB_API_KEY)
                            .takeIf { it.isSuccessful }?.body()?.results ?: return@withContext null
                        KidsRatings.movie(
                            releases.mapNotNull { c ->
                                c.iso31661?.let { it to c.releaseDates.orEmpty().mapNotNull { d -> d.certification } }
                            }.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.flatten() }
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        } ?: return null
        if (verdicts.size > MAX_CACHED) verdicts.clear()
        verdicts[cacheKey] = found
        return found
    }

    private suspend fun resolveTmdbId(id: String, isTv: Boolean, imdbId: String?): Int? = try {
        tmdbService.ensureTmdbId(id, if (isTv) "tv" else "movie", imdbId)?.toIntOrNull()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun key(isTv: Boolean, tmdbId: Int) = "${if (isTv) "tv" else "movie"}:$tmdbId"

    private companion object {
        const val LOOKUPS_AT_ONCE = 6
        const val MAX_CACHED = 5000

        fun directTmdbId(id: String): Int? =
            id.takeIf { it.startsWith("tmdb:") }?.removePrefix("tmdb:")?.substringBefore(':')?.toIntOrNull()

        fun isTv(type: ContentType, rawType: String?): Boolean =
            type == ContentType.SERIES || rawType.equals("series", true) || rawType.equals("tv", true)
    }
}
