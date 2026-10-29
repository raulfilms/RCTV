package com.nuvio.tv.data.local

import android.content.Context
import com.nuvio.tv.core.smartscraper.AddonRole
import com.nuvio.tv.core.smartscraper.AddonRoles
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * RCTV: which addon link is which server (AIO Stream Main, Torrentio TorBox...), as set up from the
 * phone. The links carry the person's debrid keys: kept only on the TV, with the installed addons,
 * and never logged or shown in full.
 */
@Singleton
class AddonRoleStore @Inject constructor(
    @ApplicationContext context: Context,
    addonRepository: AddonRepository
) {
    private val prefs = context.getSharedPreferences("rctv_addon_roles", Context.MODE_PRIVATE)
    private val _assignments = MutableStateFlow(read())
    val assignments: StateFlow<Map<AddonRole, String>> = _assignments.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Keeps the engine's map (addon's name in the app → server) up to date.
        scope.launch {
            combine(addonRepository.getInstalledAddons(), assignments) { addons, roles ->
                buildMap {
                    roles.forEach { (role, url) ->
                        addons.firstOrNull { AddonRoles.sameAddon(it.baseUrl, url) }?.let { put(it.displayName, role) }
                    }
                }
            }
                .catch { emit(emptyMap()) }
                .collect { AddonRoles.update(it) }
        }
    }

    fun urlOf(role: AddonRole): String? = _assignments.value[role]

    fun set(role: AddonRole, url: String) {
        prefs.edit().putString(role.key, url).apply()
        _assignments.value = read()
    }

    fun clear(role: AddonRole) {
        prefs.edit().remove(role.key).apply()
        _assignments.value = read()
    }

    private fun read(): Map<AddonRole, String> = AddonRole.entries.mapNotNull { role ->
        prefs.getString(role.key, null)?.takeIf { it.isNotBlank() }?.let { role to it }
    }.toMap()
}
