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
        val companyName: String? = null,
        /** Other names the company may have on TMDB, tried in order after [companyName]. */
        val altCompanyNames: List<String> = emptyList()
    )

    private val entries = listOf(
        Entry(StreamingService("netflix", "Netflix", R.drawable.streaming_logo_netflix),
            providerIds = listOf(8), providerNames = listOf("Netflix")),
        Entry(StreamingService("prime", "Prime Video", R.drawable.streaming_logo_prime),
            providerIds = listOf(9, 119), providerNames = listOf("Amazon Prime Video", "Prime Video")),
        Entry(StreamingService("hbomax", "HBO Max", R.drawable.streaming_logo_hbomax, darkTile = true, appHub = "hbomax"),
            providerIds = listOf(1899, 384), providerNames = listOf("HBO Max", "Max")),
        Entry(StreamingService("appletv", "Apple TV+", R.drawable.streaming_logo_appletv),
            providerIds = listOf(350), providerNames = listOf("Apple TV Plus", "Apple TV+", "Apple TV")),
        Entry(StreamingService("disney", "Disney+", R.drawable.streaming_logo_disney, appHub = "main"),
            providerIds = listOf(337), providerNames = listOf("Disney Plus", "Disney+")),
        Entry(StreamingService("paramount", "Paramount+", R.drawable.streaming_logo_paramount, appHub = "paramount"),
            providerIds = listOf(531, 2303), providerNames = listOf("Paramount Plus", "Paramount+", "Paramount Plus Premium")),
        Entry(StreamingService("peacock", "Peacock", R.drawable.streaming_logo_peacock, appHub = "peacock"),
            providerIds = listOf(386, 387), providerNames = listOf("Peacock Premium", "Peacock", "Peacock Premium Plus")),
        Entry(StreamingService("discovery", "Discovery+", R.drawable.streaming_logo_discovery, appHub = "discovery"),
            providerIds = listOf(520), providerNames = listOf("Discovery+", "Discovery Plus")),
        Entry(StreamingService("a24", "A24", R.drawable.streaming_logo_a24),
            companyId = 41077, companyName = "A24"),
        Entry(StreamingService("angel", "Angel Studios", R.drawable.streaming_logo_angel),
            providerNames = listOf("Angel Studios"), companyName = "Angel Studios"),
        Entry(StreamingService("blumhouse", "Blumhouse", R.drawable.streaming_logo_blumhouse),
            companyName = "Blumhouse Productions"),
        // Opens a page of independent studios (see [groups]).
        Entry(StreamingService("ifc", "Independent Film Company", R.drawable.streaming_logo_ifc, group = "ifc")),
        Entry(StreamingService("shudder", "Shudder", R.drawable.streaming_logo_shudder),
            providerIds = listOf(99), providerNames = listOf("Shudder"), companyName = "Shudder"),
        Entry(StreamingService("foxnation", "Fox Nation", R.drawable.streaming_logo_foxnation),
            providerNames = listOf("Fox Nation"), companyName = "Fox Nation")
    )

    /**
     * Pages of studio tiles opened from a Home tile, by its [StreamingService.group] key. "ifc"
     * (Independent Film Company): eight independent studios, four on top and four below, each opening
     * its TMDB company page.
     */
    private val groups: Map<String, List<Entry>> = mapOf(
        "ifc" to listOf(
            Entry(StreamingService("bleecker_street", "Bleecker Street", R.drawable.indie_brand_logo_bleecker),
                companyName = "Bleecker Street"),
            Entry(StreamingService("ifc_films", "IFC Films", R.drawable.indie_brand_logo_ifcfilms),
                companyName = "IFC Films"),
            Entry(StreamingService("magnolia_pictures", "Magnolia Pictures", R.drawable.indie_brand_logo_magnolia),
                companyName = "Magnolia Pictures"),
            Entry(StreamingService("annapurna_pictures", "Annapurna Pictures", R.drawable.indie_brand_logo_annapurna),
                companyName = "Annapurna Pictures"),
            Entry(StreamingService("millennium_media", "Millennium Media", R.drawable.indie_brand_logo_millennium),
                companyName = "Millennium Media", altCompanyNames = listOf("Millennium Films")),
            Entry(StreamingService("neon", "NEON", R.drawable.indie_brand_logo_neon),
                companyName = "NEON"),
            Entry(StreamingService("plan_b", "Plan B", R.drawable.indie_brand_logo_planb),
                companyName = "Plan B Entertainment"),
            Entry(StreamingService("roadside_attractions", "Roadside Attractions", R.drawable.indie_brand_logo_roadside),
                companyName = "Roadside Attractions")
        )
    )

    /** The tiles, in order, before anything is looked up (so the row shows instantly). */
    val services: List<StreamingService> = entries.map { it.service }

    /** A tile's bundled logo by its key (e.g. "netflix", or a studio on a group page), for the top of its page. */
    fun logoRes(key: String?): Int? =
        (entries + groups.values.flatten()).firstOrNull { it.service.key == key }?.service?.logoRes

    /** The Home tile that opens group [key] (its name and logo head the group page). */
    fun groupOwner(key: String?): StreamingService? = entries.firstOrNull { it.service.group == key }?.service

    /** A group page's studio tiles, with the destinations already known. */
    fun group(key: String?): List<StreamingService> =
        groups[key].orEmpty().map { it.service.copy(target = targetCache[it.service.key]) }

    /** Looks up each studio of group [key] on TMDB (by company name) that isn't known yet. */
    suspend fun resolveGroup(key: String?): List<StreamingService> = withContext(Dispatchers.IO) {
        val missing = groups[key].orEmpty().filter { !targetCache.containsKey(it.service.key) }
        if (missing.isNotEmpty()) {
            coroutineScope {
                missing.map { entry ->
                    async {
                        companyTarget(entry)?.let { targetCache[entry.service.key] = it }
                    }
                }.forEach { it.await() }
            }
        }
        group(key)
    }

    private suspend fun companyTarget(entry: Entry): StreamingServiceTarget? {
        entry.companyId?.let { return StreamingServiceTarget("company", it) }
        val id = (listOfNotNull(entry.companyName) + entry.altCompanyNames).firstNotNullOfOrNull { findCompanyId(it) }
        return id?.let { StreamingServiceTarget("company", it) }
    }

    private val targetCache = ConcurrentHashMap<String, StreamingServiceTarget>()

    /** Looks up where each tile leads. Tiles TMDB can't place keep a null target. */
    suspend fun resolveTargets(region: String = STREAMING_PROVIDER_REGION): List<StreamingService> =
        withContext(Dispatchers.IO) {
            if (entries.all { it.opensOwnPage || targetCache.containsKey(it.service.key) }) {
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
                if (entry.opensOwnPage || targetCache.containsKey(key)) continue

                val provider = entry.providerIds.firstNotNullOfOrNull { providersById[it] }
                    ?: providers.firstOrNull { candidate ->
                        val name = candidate.providerName?.trim().orEmpty()
                        entry.providerNames.any { it.equals(name, ignoreCase = true) }
                    }
                val target = when {
                    provider != null -> StreamingServiceTarget("provider", provider.providerId)
                    else -> companyTarget(entry)
                }
                if (target != null) targetCache[key] = target
            }
            withTargets()
        }

    /** Tiles that open the app's own page (a hub or a group of studios) need no TMDB lookup. */
    private val Entry.opensOwnPage: Boolean
        get() = service.appHub != null || service.group != null

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
