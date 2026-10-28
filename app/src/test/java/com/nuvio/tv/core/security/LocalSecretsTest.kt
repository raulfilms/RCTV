package com.nuvio.tv.core.security

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** RCTV (SEC-03): sealing credentials never loses one, and old plain values keep working. */
class LocalSecretsTest {

    private val original = LocalSecrets.cipher

    @After
    fun restore() {
        LocalSecrets.cipher = original
    }

    @Test
    fun `a sealed value opens to what was stored and doesn't show it`() {
        LocalSecrets.cipher = FakeCipher()
        val stored = LocalSecrets.seal("rd-key-123", "debrid_settings:real_debrid_api_key")
        assertTrue(LocalSecrets.isSealed(stored))
        assertFalse(stored.contains("rd-key-123"))
        assertEquals("rd-key-123", LocalSecrets.open(stored, "debrid_settings:real_debrid_api_key"))
    }

    @Test
    fun `an old plain value is read as it is and can be migrated`() {
        LocalSecrets.cipher = FakeCipher()
        assertEquals("old-key", LocalSecrets.open("old-key", "a:b"))
        val migrated = LocalSecrets.migrated("old-key", "a:b")!!
        assertEquals("old-key", LocalSecrets.open(migrated, "a:b"))
        assertNull(LocalSecrets.migrated(migrated, "a:b"))
        assertNull(LocalSecrets.migrated("", "a:b"))
        assertNull(LocalSecrets.migrated(null, "a:b"))
    }

    @Test
    fun `a device that can't seal keeps the value as it was`() {
        LocalSecrets.cipher = object : SecretCipher {
            override fun encrypt(plain: String, aad: String): String? = throw IllegalStateException("no keystore")
            override fun decrypt(sealed: String, aad: String): String? = throw IllegalStateException("no keystore")
        }
        assertEquals("key", LocalSecrets.seal("key", "a:b"))
        assertNull(LocalSecrets.migrated("key", "a:b"))
    }

    @Test
    fun `a value is only stored sealed after it opened again`() {
        LocalSecrets.cipher = object : SecretCipher {
            override fun encrypt(plain: String, aad: String): String = "x.y"
            override fun decrypt(sealed: String, aad: String): String = "something else"
        }
        assertEquals("key", LocalSecrets.seal("key", "a:b"))
    }

    @Test
    fun `a sealed value is bound to its key and reads as absent when its key is gone`() {
        LocalSecrets.cipher = FakeCipher()
        val stored = LocalSecrets.seal("token", "trakt_auth_store:access_token")
        assertNull(LocalSecrets.open(stored, "trakt_auth_store:refresh_token"))
        LocalSecrets.cipher = FakeCipher(lost = true)
        assertNull(LocalSecrets.open(stored, "trakt_auth_store:access_token"))
    }

    @Test
    fun `empty stays empty and a sealed value isn't sealed twice`() {
        LocalSecrets.cipher = FakeCipher()
        assertEquals("", LocalSecrets.seal("", "a:b"))
        val once = LocalSecrets.seal("v", "a:b")
        assertEquals(once, LocalSecrets.seal(once, "a:b"))
    }

    /** Reversible, bound to the AAD; [lost] behaves like a Keystore key that no longer exists. */
    private class FakeCipher(private val lost: Boolean = false) : SecretCipher {
        override fun encrypt(plain: String, aad: String): String =
            Base64.getEncoder().encodeToString("$aad\u0000$plain".reversed().toByteArray())

        override fun decrypt(sealed: String, aad: String): String? {
            if (lost) throw IllegalStateException("key gone")
            val text = String(Base64.getDecoder().decode(sealed)).reversed()
            return text.removePrefix("$aad\u0000").takeIf { text.startsWith("$aad\u0000") }
        }
    }
}
