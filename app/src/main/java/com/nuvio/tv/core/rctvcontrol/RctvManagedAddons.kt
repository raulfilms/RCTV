package com.nuvio.tv.core.rctvcontrol

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.smartscraper.AddonRole
import com.nuvio.tv.core.smartscraper.AddonRoles
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.local.AddonRoleStore
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RCTV Control: the addons set on the website for the account (its list of addons: Torrentio,
 * AIOStreams...; LatinoDebrid, Addon LATAM) are on the profile watching, first and in the website's
 * order; the TV's own addons stay after them. The website wins: when it changes an address, the old
 * one goes; the same addon installed on the TV at another address is replaced. Addresses carry keys:
 * never logged. RCTV: the website's LatinoDebrid and Addon LATAM are those servers to the app, whatever
 * their addons call themselves (else their links weren't told apart and none was used).
 */
@Singleton
class RctvManagedAddons @Inject constructor(
    @ApplicationContext context: Context,
    private val managed: RctvManagedServices,
    private val profileManager: ProfileManager,
    private val addonRepository: AddonRepository,
    private val addonPreferences: AddonPreferences,
    private val addonRoles: AddonRoleStore
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Keeps the profile watching up to date while the app is open (run it from the screen's lifecycle). */
    suspend fun run() {
        combine(
            managed.services.map { it.managedAddonUrls to it.addons }.distinctUntilChanged(),
            profileManager.activeProfileId
        ) { (urls, spanish), profileId -> Triple(urls, spanish, profileId) }
            .collectLatest { (urls, spanish, profileId) ->
                try {
                    applyRoles(spanish)
                    apply(profileId, urls)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Tried again the next time the account's services or the profile change.
                }
            }
    }

    private suspend fun apply(profileId: Int, urls: List<String>) {
        val key = "p$profileId"
        val previous = prefs.getStringSet(key, emptySet()).orEmpty()
        if (previous.isEmpty() && urls.isEmpty()) return
        // Addresses the website no longer has.
        previous.filter { old -> urls.none { same(it, old) } }.forEach { addonRepository.removeAddon(it) }
        urls.forEach { url ->
            val installed = addonPreferences.installedAddonUrls.first()
            if (installed.none { same(it, url) }) addonRepository.addAddon(url)
            // The same addon at another address (typed on the TV): the website's replaces it. The
            // website can have the same addon twice (two configurations): both stay.
            val manifestId = (addonRepository.fetchAddon(url) as? NetworkResult.Success)?.data?.id ?: return@forEach
            addonRepository.getInstalledAddons().first()
                .filter { addon -> addon.id == manifestId && urls.none { same(addon.baseUrl, it) } }
                .forEach { addonRepository.removeAddon(it.baseUrl) }
        }
        // The website's first, in its order; the TV's own after them, as they were.
        if (urls.isNotEmpty()) {
            val current = addonPreferences.installedAddonUrls.first()
            val ordered = RctvAddonOrder.managedFirst(current, urls)
            if (ordered != current) addonRepository.setAddonOrder(ordered)
        }
        prefs.edit().putStringSet(key, urls.toSet()).apply()
    }

    /** The website's Spanish servers, by its service ("latinodebrid", "addon_latam"): the website wins. */
    private fun applyRoles(spanish: Map<String, String>) {
        spanish.forEach { (service, url) ->
            val role = when (service) {
                "addon_latam" -> AddonRole.LATAM
                "latinodebrid" -> AddonRole.LATINODEBRID
                else -> null
            } ?: return@forEach
            if (!AddonRoles.sameAddon(addonRoles.urlOf(role), url)) addonRoles.set(role, url)
        }
    }

    private fun same(a: String, b: String): Boolean = RctvAddonOrder.same(a, b)

    private companion object {
        const val PREFS = "rctv_control_addons"
    }
}
