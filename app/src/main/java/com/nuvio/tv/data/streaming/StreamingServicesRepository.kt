package com.nuvio.tv.data.streaming

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.tmdb.STREAMING_PROVIDER_REGION
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbWatchProviderEntry
import com.nuvio.tv.domain.model.StreamingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "StreamingServicesRepo"
private const val LOGO_BASE_URL = "https://image.tmdb.org/t/p/w300"

/**
 * The streaming services row on the home screen. The list of services is fixed (the big
 * subscription services), but names and logos come from TMDB for the region, so a service
 * TMDB doesn't list there is simply left out.
 */
@Singleton
class StreamingServicesRepository @Inject constructor(
    private val tmdbApi: TmdbApi
) {
    private val cache = ConcurrentHashMap<String, List<StreamingService>>()

    private data class Curated(
        /** TMDB provider ids, best match first. */
        val ids: List<Int>,
        /** Names to fall back on if TMDB renumbers a service. */
        val names: List<String>,
        /** Short name shown in the app; null keeps TMDB's own name. */
        val displayName: String?,
        val opensDisneyHub: Boolean = false
    )

    private val curated = listOf(
        Curated(listOf(8), listOf("Netflix"), "Netflix"),
        Curated(listOf(9, 119), listOf("Amazon Prime Video", "Prime Video"), "Prime Video"),
        Curated(listOf(337), listOf("Disney Plus", "Disney+"), "Disney+", opensDisneyHub = true),
        Curated(listOf(1899, 384), listOf("HBO Max", "Max"), null),
        Curated(listOf(350), listOf("Apple TV Plus", "Apple TV+", "Apple TV"), "Apple TV"),
        Curated(listOf(15), listOf("Hulu"), "Hulu"),
        Curated(listOf(531, 2303), listOf("Paramount Plus", "Paramount+", "Paramount Plus Premium"), "Paramount+"),
        Curated(listOf(386, 387), listOf("Peacock Premium", "Peacock"), "Peacock")
    )

    suspend fun services(region: String = STREAMING_PROVIDER_REGION): List<StreamingService> =
        withContext(Dispatchers.IO) {
            cache[region]?.let { return@withContext it }

            val entries: List<TmdbWatchProviderEntry> = try {
                coroutineScope {
                    val tv = async {
                        tmdbApi.getTvWatchProviders(BuildConfig.TMDB_API_KEY, region).body()?.results.orEmpty()
                    }
                    val movie = async {
                        tmdbApi.getMovieWatchProviders(BuildConfig.TMDB_API_KEY, region).body()?.results.orEmpty()
                    }
                    (tv.await() + movie.await()).distinctBy { it.providerId }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load watch providers for $region: ${e.message}")
                emptyList()
            }
            if (entries.isEmpty()) return@withContext emptyList()

            val byId = entries.associateBy { it.providerId }
            val resolved = curated.mapNotNull { service ->
                val entry = service.ids.firstNotNullOfOrNull { byId[it] }
                    ?: entries.firstOrNull { entry ->
                        service.names.any { it.equals(entry.providerName?.trim(), ignoreCase = true) }
                    }
                    ?: return@mapNotNull null
                StreamingService(
                    id = entry.providerId,
                    name = service.displayName ?: entry.providerName?.trim().orEmpty().ifBlank { service.names.first() },
                    logoUrl = entry.logoPath?.takeIf { it.isNotBlank() }?.let { "$LOGO_BASE_URL$it" },
                    opensDisneyHub = service.opensDisneyHub
                )
            }.distinctBy { it.id }

            if (resolved.isNotEmpty()) cache[region] = resolved
            resolved
        }
}
