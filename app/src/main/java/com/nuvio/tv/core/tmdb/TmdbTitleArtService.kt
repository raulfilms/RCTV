package com.nuvio.tv.core.tmdb

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbImage
import com.nuvio.tv.data.remote.api.TmdbImagesResponse
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.TitleArtResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TmdbTitleArt"
private const val IMAGE_BASE = "https://image.tmdb.org/t/p/"
private const val MAX_ATTEMPTS = 4

/** What TMDB has for a title's "name on the picture" cards. */
data class TmdbTitleArt(
    /** The title's logo (transparent artwork of its name), English first. */
    val logo: String?,
    /**
     * A 16:9 backdrop with the title already on it. TMDB tags backdrops that carry text with their
     * language and leaves clean ones untagged, so an English-tagged backdrop is a title card.
     */
    val titledBackdrop: String?
)

/**
 * Title art for the horizontal cards (rows, See All pages, hubs, channel pages) from TMDB's images
 * endpoint, shared by every screen so a title is only looked up once per app run.
 *
 * A few lookups run at a time, and when TMDB says "too many requests" (HTTP 429) the lookup waits and
 * tries again instead of giving up, so busy pages (many rows loading at once) still get every card's
 * art. Only real answers are cached; a lookup that failed is tried again the next time.
 */
@Singleton
class TmdbTitleArtService @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService
) : TitleArtResolver {
    private val apiKey get() = BuildConfig.TMDB_API_KEY

    private val artCache = ConcurrentHashMap<String, TmdbTitleArt>()
    /** Non-TMDB ids (IMDb "tt...") already matched to their TMDB id. */
    private val tmdbIdCache = ConcurrentHashMap<String, Int>()
    /** Lookups at the same time; TMDB's "too many requests" answers are retried by the HTTP client. */
    private val limiter = Semaphore(8)

    // ------------------------------------------------------------------ TitleArtResolver

    override fun cached(item: MetaPreview): MetaPreview? {
        if (item.landscapeHasTitle) return item
        val isTv = item.isTv()
        val tmdbId = directTmdbId(item) ?: tmdbIdCache[idKey(item, isTv)]
            // Nothing TMDB can have (or it said it hasn't it): the card shows at once as it is,
            // instead of holding its picture back for a lookup that can't find anything.
            ?: return if (cannotMatch(item, isTv)) item else null
        val art = artCache[artKey(tmdbId, isTv)] ?: return null
        return apply(item, art)
    }

    private fun imdbIdOf(item: MetaPreview): String? =
        item.id.takeIf { it.startsWith("tt") } ?: item.imdbId?.takeIf { it.startsWith("tt") }

    /** True when [item] can't be matched on TMDB: not a movie or series, no IMDb id, or not on TMDB. */
    private fun cannotMatch(item: MetaPreview, isTv: Boolean): Boolean {
        if (item.type != ContentType.MOVIE && item.type != ContentType.SERIES) return true
        val imdbId = imdbIdOf(item) ?: return true
        return tmdbService.isKnownMissingOnTmdb(imdbId, if (isTv) "tv" else "movie")
    }

    override suspend fun resolve(item: MetaPreview): MetaPreview {
        if (item.landscapeHasTitle) return item
        val art = artFor(item) ?: return item
        return apply(item, art)
    }

    // ------------------------------------------------------------------ batches

    /** Gives the first [count] titles their art (see [resolve]); the rest stay as they are. */
    suspend fun withTitleArt(items: List<MetaPreview>, count: Int = items.size): List<MetaPreview> =
        withContext(Dispatchers.IO) {
            val head = items.take(count)
            val resolved = coroutineScope { head.map { item -> async { resolve(item) } }.awaitAll() }
            resolved + items.drop(count)
        }

    /** Gives each title its logo (for hero banners, which lay it over their own clean backdrop). */
    suspend fun withLogos(items: List<MetaPreview>): List<MetaPreview> = withContext(Dispatchers.IO) {
        coroutineScope {
            items.map { item ->
                async {
                    if (!item.logo.isNullOrBlank()) item
                    else artFor(item)?.logo?.let { item.copy(logo = it) } ?: item
                }
            }.awaitAll()
        }
    }

    /** The title's TMDB art, or null when TMDB couldn't be reached (or the title isn't on TMDB). */
    suspend fun artFor(item: MetaPreview): TmdbTitleArt? = withContext(Dispatchers.IO) {
        val isTv = item.isTv()
        val tmdbId = tmdbIdFor(item, isTv) ?: return@withContext null
        val key = artKey(tmdbId, isTv)
        artCache[key]?.let { return@withContext it }
        val art = limiter.withPermit {
            // Another card may have looked it up while this one waited its turn.
            artCache[key] ?: fetch(tmdbId, isTv)
        } ?: return@withContext null
        artCache[key] = art
        art
    }

    // ------------------------------------------------------------------ internals

    private fun apply(item: MetaPreview, art: TmdbTitleArt): MetaPreview {
        val logo = item.logo?.takeIf { it.isNotBlank() } ?: art.logo
        return if (art.titledBackdrop != null) {
            item.copy(logo = logo, landscapePoster = art.titledBackdrop, landscapeHasTitle = true)
        } else if (logo != item.logo) {
            item.copy(logo = logo)
        } else {
            item
        }
    }

    private suspend fun fetch(tmdbId: Int, isTv: Boolean): TmdbTitleArt? {
        for (attempt in 0 until MAX_ATTEMPTS) {
            val response: Response<TmdbImagesResponse>? = try {
                if (isTv) tmdbApi.getTvImages(tmdbId, apiKey) else tmdbApi.getMovieImages(tmdbId, apiKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Images for ${if (isTv) "tv" else "movie"} $tmdbId: ${e.message}")
                null
            }
            when {
                response == null -> Unit // network hiccup: try again below
                response.isSuccessful -> return pick(response.body())
                // Too many requests / server trouble: wait and try again.
                response.code() == 429 || response.code() >= 500 -> Unit
                // 404 and the like: TMDB has nothing for this title.
                else -> return TmdbTitleArt(logo = null, titledBackdrop = null)
            }
            if (attempt < MAX_ATTEMPTS - 1) {
                val retryAfterMs = response?.headers()?.get("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
                delay((retryAfterMs ?: (700L * (attempt + 1))).coerceIn(300L, 5_000L) + (0L..250L).random())
            }
        }
        return null
    }

    private fun pick(body: TmdbImagesResponse?): TmdbTitleArt {
        val logos = body?.logos.orEmpty().filter { !it.filePath.isNullOrBlank() }
        val logo = (logos.filter { it.iso6391 == "en" }.bestFirst() + logos.bestFirst()).firstOrNull()

        val titledBackdrops = body?.backdrops.orEmpty().filter { image ->
            !image.filePath.isNullOrBlank() &&
                image.iso6391 == "en" &&
                (image.aspectRatio == null || image.aspectRatio in 1.6..1.95)
        }
        // A sharp one if there is one (cards are drawn at up to ~780px wide).
        val backdrop = titledBackdrops.filter { (it.width ?: 0) >= 1280 }.bestFirst().firstOrNull()
            ?: titledBackdrops.bestFirst().firstOrNull()

        return TmdbTitleArt(
            logo = logo?.filePath?.let { "${IMAGE_BASE}w500$it" },
            titledBackdrop = backdrop?.filePath?.let { "${IMAGE_BASE}w780$it" }
        )
    }

    private fun List<TmdbImage>.bestFirst(): List<TmdbImage> = sortedByDescending { it.voteAverage ?: 0.0 }

    private suspend fun tmdbIdFor(item: MetaPreview, isTv: Boolean): Int? {
        directTmdbId(item)?.let { return it }
        // Only movies and series with an IMDb id can be matched (not live channels or placeholders).
        if (item.type != ContentType.MOVIE && item.type != ContentType.SERIES) return null
        val imdbId = imdbIdOf(item) ?: return null
        val key = idKey(item, isTv)
        tmdbIdCache[key]?.let { return it }
        val found = try {
            tmdbService.ensureTmdbId(imdbId, if (isTv) "tv" else "movie")?.toIntOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        tmdbIdCache[key] = found
        return found
    }

    private fun directTmdbId(item: MetaPreview): Int? =
        item.id.takeIf { it.startsWith("tmdb:") }?.removePrefix("tmdb:")?.substringBefore(':')?.toIntOrNull()

    private fun MetaPreview.isTv(): Boolean =
        type == ContentType.SERIES || rawType.equals("series", true) || rawType.equals("tv", true)

    private fun artKey(tmdbId: Int, isTv: Boolean) = "${if (isTv) "tv" else "movie"}:$tmdbId"
    private fun idKey(item: MetaPreview, isTv: Boolean) = "${if (isTv) "tv" else "movie"}:${item.id}"
}
