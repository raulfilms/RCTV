package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.nuvio.tv.core.security.LocalSecrets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * RCTV (SEC-03): a store's credential keys, kept sealed ([LocalSecrets]). Reads open them (an old
 * plain value is read as it is); writes seal them. [sealInBackground] seals what an earlier version
 * left in plain text, once per profile per run, in one transaction of that store; a value that can't
 * be sealed stays as it was. The sealing key isn't tied to the profile, so copying a profile's
 * settings on the device keeps them readable; values excluded from cloud sync stay excluded.
 */
internal class SecretPreferences(
    private val feature: String,
    private val keys: List<Preferences.Key<String>>
) {
    private val sealedProfiles = ConcurrentHashMap.newKeySet<Int>()

    fun read(prefs: Preferences, key: Preferences.Key<String>): String? = LocalSecrets.open(prefs[key], aad(key))

    fun seal(key: Preferences.Key<String>, value: String): String = LocalSecrets.seal(value, aad(key))

    fun needsSealing(prefs: Preferences): Boolean =
        keys.any { key -> prefs[key].let { !it.isNullOrEmpty() && !LocalSecrets.isSealed(it) } }

    fun sealInBackground(profileId: Int, prefs: Preferences, store: () -> DataStore<Preferences>) {
        if (!needsSealing(prefs) || !sealedProfiles.add(profileId)) return
        scope.launch { sealNow(store()) }
    }

    /** Seals the plain-text values in [store]; true when every credential there is sealed afterwards. */
    suspend fun sealNow(store: DataStore<Preferences>): Boolean {
        var allSealed = true
        runCatching {
            store.edit { prefs ->
                keys.forEach { key ->
                    val sealed = LocalSecrets.migrated(prefs[key], aad(key))
                    if (sealed != null) prefs[key] = sealed
                    else if (!prefs[key].isNullOrEmpty() && !LocalSecrets.isSealed(prefs[key])) allSealed = false
                }
            }
        }.onFailure { allSealed = false }
        return allSealed
    }

    private fun aad(key: Preferences.Key<String>): String = "$feature:${key.name}"

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
