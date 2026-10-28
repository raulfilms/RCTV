package com.nuvio.tv.core.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.data.local.SecretPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** RCTV (SEC-03): a store's existing plain credentials are sealed in place, and nothing else changes. */
class SecretPreferencesTest {

    private val original = LocalSecrets.cipher
    private val apiKey = stringPreferencesKey("real_debrid_api_key")
    private val other = stringPreferencesKey("stream_sort_mode")

    @After
    fun restore() {
        LocalSecrets.cipher = original
    }

    @Test
    fun `plain credentials are sealed in place and read back the same`() = runBlocking {
        LocalSecrets.cipher = ReversibleCipher
        val store = MemoryStore()
        store.edit { it[apiKey] = "rd-123"; it[other] = "QUALITY" }
        val secrets = SecretPreferences("debrid_settings", listOf(apiKey))

        assertTrue(secrets.needsSealing(store.data.first()))
        assertTrue(secrets.sealNow(store))

        val prefs = store.data.first()
        assertTrue(LocalSecrets.isSealed(prefs[apiKey]))
        assertEquals("rd-123", secrets.read(prefs, apiKey))
        assertEquals("QUALITY", prefs[other])
        assertFalse(secrets.needsSealing(prefs))
    }

    @Test
    fun `a device that can't seal keeps every credential as it was`() = runBlocking {
        LocalSecrets.cipher = object : SecretCipher {
            override fun encrypt(plain: String, aad: String): String? = null
            override fun decrypt(sealed: String, aad: String): String? = null
        }
        val store = MemoryStore()
        store.edit { it[apiKey] = "rd-123" }
        val secrets = SecretPreferences("debrid_settings", listOf(apiKey))

        assertFalse(secrets.sealNow(store))
        assertEquals("rd-123", store.data.first()[apiKey])
        assertEquals("rd-123", secrets.read(store.data.first(), apiKey))
    }

    @Test
    fun `a saved credential is sealed and an empty one stays empty`() {
        LocalSecrets.cipher = ReversibleCipher
        val secrets = SecretPreferences("debrid_settings", listOf(apiKey))
        assertTrue(LocalSecrets.isSealed(secrets.seal(apiKey, "rd-123")))
        assertEquals("", secrets.seal(apiKey, ""))
    }

    private object ReversibleCipher : SecretCipher {
        override fun encrypt(plain: String, aad: String): String =
            Base64.getEncoder().encodeToString("$aad|$plain".toByteArray())

        override fun decrypt(sealed: String, aad: String): String? =
            String(Base64.getDecoder().decode(sealed)).takeIf { it.startsWith("$aad|") }?.removePrefix("$aad|")
    }

    private class MemoryStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
