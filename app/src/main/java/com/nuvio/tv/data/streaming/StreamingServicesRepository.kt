package com.nuvio.tv.data.streaming

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.tmdb.STREAMING_PROVIDER_REGION
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbWatchProviderEntry
import com.nuvio.tv.domain.model.StreamingService
import com.nuvio.tv.domain.model.StreamingServiceTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "StreamingServicesRepo"

/**
 * The "Streaming Services" row on the home screen: a fixed list of services, in the order
 * they appear, with logos bundled in the app. Where each tile leads is looked up on TMDB:
 * the service's watch provider page (what its subscription includes), or for studios like
 * A24 the studio's page.
 */
@Singleton
class StreamingServicesRepository @Inject constructor(
    private val tmdbApi: TmdbApi
) {
    private data class Entry(
        val service: StreamingService,
        /** TMDB watch provider ids, best match first. */
        val providerIds: List<Int> = emptyList(),
        /** Provider names to match if TMDB renumbers a service. */
        val providerNames: List<String> = emptyList(),
        /** TMDB company to open when the service isn't a watch provider (studios). */
        val companyId: Int? = null,
        /** Company name to look up as a last resort. */
        val companyName: String? = null
    )

    private val entries = listOf(
        Entry(StreamingService("netflix", "Netflix", R.drawable.streaming_logo_netflix),
            providerIds = listOf(8), providerNames = listOf("Netflix")),
        Entry(StreamingService("prime", "Prime Video", R.drawable.streaming_logo_prime),
            providerIds = listOf(9, 119), providerNames = listOf("Amazon Prime Video", "Prime Video")),
        Entry(StreamingService("hbomax", "HBO Max", R.drawable.streaming_logo_hbomax, darkTile = true),
            providerIds = listOf(1899, 384), providerNames = listOf("HBO Max", "Max")),
        Entry(StreamingService("appletv", "Apple TV+", R.drawable.streaming_logo_appletv),
            providerIds = listOf(350), providerNames = listOf("Apple TV Plus", "Apple TV+", "Apple TV")),
        Entry(StreamingService("disney", "Disney+", R.drawable.streaming_logo_disney, opensDisneyHub = true),
            providerIds = listOf(337), providerNames = listOf("Disney Plus", "Disney+")),
        Entry(StreamingService("paramount", "Paramount+", R.drawable.streaming_logo_paramount),
            providerIds = listOf(531, 2303), providerNames = listOf("Paramount Plus", "Paramount+", "Paramount Plus Premium")),
        Entry(StreamingService("peacock", "Peacock", R.drawable.streaming_logo_peacock),
            providerIds = listOf(386, 387), providerNames = listOf("Peacock Premium", "Peacock", "Peacock Premium Plus")),
        Entry(StreamingService("discovery", "Discovery+", R.drawable.streaming_logo_discovery),
            providerIds = listOf(520), providerNames = listOf("Discovery+", "Discovery Plus")),
        Entry(StreamingService("a24", "A24", R.drawable.streaming_logo_a24),
            companyId = 41077, companyName = "A24"),
        Entry(StreamingService("angel", "Angel Studios", R.drawable.streaming_logo_angel),
            providerNames = listOf("Angel Studios"), companyName = "Angel Studios"),
        Entry(StreamingService("shudder", "Shudder", R.drawable.streaming_logo_shudder),
            providerIds = listOf(99), providerNames = listOf("Shudder"), companyName = "Shudder"),
        Entry(StreamingService("foxnation", "Fox Nation", R.drawable.streaming_logo_foxnation),
            providerNames = listOf("Fox Nation"), companyName = "Fox Nation")
    )

    /** The tiles, in order, before anything is looked up (so the row shows instantly). */
    val services: List<StreamingService> = entries.map { it.service }

    private val targetCache = ConcurrentHashMap<String, StreamingServiceTarget>()

    /** Looks up where each tile leads. Tiles TMDB can't place keep a null target. */
    suspend fun resolveTargets(region: String = STREAMING_PROVIDER_REGION): List<StreamingService> =
        withContext(Dispatchers.IO) {
            if (entries.all { it.service.opensDisneyHub || targetCache.containsKey(it.service.key) }) {
                return@withContext withTargets()
            }

            val providers: List<TmdbWatchProviderEntry> = try {
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
            val providersById = providers.associateBy { it.providerId }

            for (entry in entries) {
                val key = entry.service.key
                if (entry.service.opensDisneyHub || targetCache.containsKey(key)) continue

                val provider = entry.providerIds.firstNotNullOfOrNull { providersById[it] }
                    ?: providers.firstOrNull { candidate ->
                        val name = candidate.providerName?.trim().orEmpty()
                        entry.providerNames.any { it.equals(name, ignoreCase = true) }
                    }
                val target = when {
                    provider != null -> StreamingServiceTarget("provider", provider.providerId)
                    entry.companyId != null -> StreamingServiceTarget("company", entry.companyId)
                    entry.companyName != null -> findCompanyId(entry.companyName)
                        ?.let { StreamingServiceTarget("company", it) }
                    else -> null
                }
                if (target != null) targetCache[key] = target
            }
            withTargets()
        }

    private fun withTargets(): List<StreamingService> =
        services.map { service -> service.copy(target = targetCache[service.key]) }

    private suspend fun findCompanyId(name: String): Int? = try {
        tmdbApi.searchCompanies(BuildConfig.TMDB_API_KEY, name).body()?.results.orEmpty()
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.id
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Company lookup failed for $name: ${e.message}")
        null
    }
}
