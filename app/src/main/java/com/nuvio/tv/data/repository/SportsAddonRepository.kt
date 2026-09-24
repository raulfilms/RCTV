package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.SportsAddonEvent
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.StreamRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sports catalogs/streams sourced from the user's own installed addons - ARVIO's addon-driven
 * sports approach, ported onto RCTV's existing addon system ([AddonRepository]/
 * [CatalogRepository]/[StreamRepository]) instead of porting ARVIO's own addon layer. Artwork and
 * scores for the schedule-browsing rows still come directly from TheSportsDB via
 * [com.nuvio.tv.data.remote.sportsdb.SportsDbClient] - this repository is only for what's actually
 * playable.
 */
@Singleton
class SportsAddonRepository @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val streamRepository: StreamRepository
) {
    /** Every installed, enabled addon's catalog that looks like live sports. */
    suspend fun findSportsCatalogs(): List<SportsCatalogRef> {
        val addons = addonRepository.getInstalledAddons().first().enabledAddons()
        return SportsAddonCatalogClassifier.findSportsCatalogs(addons)
    }

    /**
     * Fetches events from every detected sports catalog and merges them into one list. A catalog
     * that fails or times out contributes nothing rather than failing the whole fetch - the same
     * "a bad source doesn't sink the others" principle as [findSportsCatalogs]'s caller in
     * SportsViewModel applies to TheSportsDB's own leagues/events fetches.
     */
    suspend fun fetchSportsEvents(catalogs: List<SportsCatalogRef>? = null): List<SportsAddonEvent> = coroutineScope {
        val refs = catalogs ?: findSportsCatalogs()
        refs.map { ref ->
            async {
                runCatching {
                    val result = catalogRepository.getCatalog(
                        addonBaseUrl = ref.addon.baseUrl,
                        addonId = ref.addon.id,
                        addonName = ref.addon.displayName,
                        catalogId = ref.catalog.id,
                        catalogName = ref.catalog.name,
                        type = ref.catalog.apiType
                    ).firstOrNull { it !is NetworkResult.Loading }
                    (result as? NetworkResult.Success)?.data?.items.orEmpty()
                }.getOrDefault(emptyList()).map { item -> item.toSportsAddonEvent(ref) }
            }
        }.awaitAll().flatten()
    }

    /**
     * Fetches playable streams for one event, from the same addon it was found in (an event's id
     * is only meaningful to the addon that issued it). Only streams with a direct, playable HTTP
     * URL are useful here - torrent/magnet-only streams would need the same debrid/local-resolve
     * pipeline movies and series use, which this first pass doesn't wire up for sports.
     */
    suspend fun fetchPlayableStreams(event: SportsAddonEvent): List<Stream> {
        val addon = addonRepository.getInstalledAddons().first().firstOrNull { it.id == event.addonId } ?: return emptyList()
        val result = streamRepository.getStreamsFromAddon(addon = addon, type = event.type.toApiString(), videoId = event.id)
        val streams = (result as? NetworkResult.Success)?.data.orEmpty()
        return streams.filter { !it.getStreamUrl().isNullOrBlank() }
    }

    private fun MetaPreview.toSportsAddonEvent(ref: SportsCatalogRef): SportsAddonEvent = SportsAddonEvent(
        id = id,
        name = name,
        poster = poster,
        background = background,
        description = description,
        addonId = ref.addon.id,
        addonName = ref.addon.displayName,
        catalogId = ref.catalog.id,
        type = type
    )
}
