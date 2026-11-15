package com.nuvio.tv.core.rctvcontrol

import com.nuvio.tv.data.local.LiveTvDataStore
import com.nuvio.tv.domain.model.CustomLiveChannel
import com.nuvio.tv.ui.screens.customize.RctvNetworkCatalog
import com.nuvio.tv.ui.screens.livetv.LiveGuideSnapshot
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RCTV (owner): the networks a profile picked on "Customize your experience" (logos only), linked to
 * the account's IPTV: during "Creating Your Experience", once the Guide's channels are in, each
 * picked network's channel ("FOX" -> the Guide's FOX) goes into the profile's Favorite Networks (the
 * Guide's Custom list, Home's Favorite Networks row). A network the Guide doesn't have is left out.
 */
@Singleton
class RctvNetworkLinker @Inject constructor(
    private val store: RctvCustomizeStore,
    private val liveTvDataStore: LiveTvDataStore
) {
    /** Links [profileId]'s picks to [guide]'s channels; nothing when none wait or the guide has no channels yet. */
    suspend fun linkPending(profileId: Int, guide: LiveGuideSnapshot) {
        val keys = store.pendingNetworks(profileId)
        if (keys.isEmpty() || guide.channels.isEmpty()) return
        for (key in keys) {
            val network = RctvNetworkCatalog.byKey(key) ?: continue
            val channel = RctvNetworkCatalog.channelIn(network, guide.channels) ?: continue
            try {
                val position = guide.channels.indexOfFirst { it.id == channel.id }.takeIf { it >= 0 } ?: Int.MAX_VALUE
                liveTvDataStore.addCustomChannel(
                    channel.id,
                    CustomLiveChannel(id = channel.id, name = channel.name, logoUrl = channel.logoUrl, position = position)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // That one can still be added from the Guide.
            }
        }
        store.setPendingNetworks(profileId, emptyList())
    }
}
