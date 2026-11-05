package com.nuvio.tv.data.streaming

import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.domain.model.ContentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RCTV: where a title comes from originally ([OriginalHome]), as a small round badge on its Continue
 * Watching card: that service's square app icon from TMDB when the title is on it, else the app's
 * own logo of that service or studio, else the network's or studio's logo.
 */
data class TitleBrand(
    /** A square icon (the service's app icon): fills the circle. */
    val iconUrl: String? = null,
    /** The app's own logo of the service or studio: drawn small inside the circle. */
    val logoRes: Int? = null,
    /** A company's wordmark (transparent): drawn small and white inside the circle. */
    val wordmarkUrl: String? = null,
    val name: String? = null
) {
    val isEmpty: Boolean get() = iconUrl.isNullOrBlank() && logoRes == null && wordmarkUrl.isNullOrBlank()
}

@Singleton
class TitleBrandRepository @Inject constructor(
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore
) {
    private val brands = ConcurrentHashMap<String, TitleBrand>()

    /** A few titles at a time: the Home row asks for each of its cards. */
    private val requests = Semaphore(3)

    /** [contentId]'s brand (kept for the app's life); null when TMDB is off or knows none. */
    suspend fun brandOf(contentId: String, contentType: String): TitleBrand? {
        val key = "${contentType.lowercase()}|$contentId"
        brands[key]?.let { return it.takeIf { brand -> !brand.isEmpty } }
        val brand = try {
            requests.withPermit { lookUp(contentId, contentType) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        } ?: TitleBrand()
        brands[key] = brand
        return brand.takeIf { !it.isEmpty }
    }

    private suspend fun lookUp(contentId: String, contentType: String): TitleBrand? {
        if (!tmdbSettingsDataStore.settings.first().enabled) return null
        val isSeries = contentType.lowercase() in setOf("series", "tv")
        val tmdbId = tmdbService.ensureTmdbId(contentId, if (isSeries) "series" else "movie") ?: return null
        val type = if (isSeries) ContentType.SERIES else ContentType.MOVIE
        val enrichment = tmdbMetadataService.fetchEnrichment(tmdbId = tmdbId, contentType = type)
        val providers = tmdbMetadataService.fetchTitleStreamingProviders(tmdbId, type)
        val home = OriginalHome.of(
            isSeries = isSeries,
            networks = enrichment?.networks.orEmpty(),
            companies = enrichment?.productionCompanies.orEmpty(),
            providers = providers
        ) ?: return null
        return TitleBrand(
            iconUrl = home.iconUrl,
            logoRes = home.logoRes,
            wordmarkUrl = home.logoUrl.takeIf { home.logoRes == null },
            name = home.name
        )
    }
}
