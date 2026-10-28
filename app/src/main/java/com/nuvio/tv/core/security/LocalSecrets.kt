package com.nuvio.tv.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals and opens a short text; null when it can't (no Keystore on this device, a failure). */
interface SecretCipher {
    fun encrypt(plain: String, aad: String): String?
    fun decrypt(sealed: String, aad: String): String?
}

/**
 * RCTV (SEC-03): credentials the person typed (debrid keys, IPTV logins, Trakt and IMDb sessions)
 * are kept sealed with a key in the Android Keystore instead of in plain text.
 *
 * Compatible with what's already stored: a value without [PREFIX] is an old plain one and is read as
 * it is (and sealed the next time it's saved, or by the store's migration). A value is only stored
 * sealed after it was opened again and matched; when the device can't seal, it stays as it was, so
 * nothing is ever lost. A sealed value that can't be opened (its key is gone) reads as absent but
 * stays stored, untouched.
 */
object LocalSecrets {
    const val PREFIX = "rctv-enc:v1:"

    @Volatile
    var cipher: SecretCipher = AndroidKeystoreSecretCipher

    fun isSealed(value: String?): Boolean = value?.startsWith(PREFIX) == true

    /** The plain text of [stored] (an old plain value as it is); null when absent or it can't be opened. */
    fun open(stored: String?, aad: String): String? {
        if (stored == null || !isSealed(stored)) return stored
        return runCatching { cipher.decrypt(stored.removePrefix(PREFIX), aad) }.getOrNull()
    }

    /** What to store for [plain]: sealed when this device can seal and open it again, else [plain]. */
    fun seal(plain: String, aad: String): String {
        if (plain.isEmpty() || isSealed(plain)) return plain
        val sealed = runCatching { cipher.encrypt(plain, aad) }.getOrNull() ?: return plain
        val reopened = runCatching { cipher.decrypt(sealed, aad) }.getOrNull()
        return if (reopened == plain) PREFIX + sealed else plain
    }

    /** The sealed form of a value still stored in plain text; null when there's nothing to do or it can't be sealed. */
    fun migrated(stored: String?, aad: String): String? {
        if (stored.isNullOrEmpty() || isSealed(stored)) return null
        return seal(stored, aad).takeIf { isSealed(it) }
    }
}

/** AES-256-GCM with a non-exportable key in the Android Keystore (API 23+; the app's minimum is 24). */
internal object AndroidKeystoreSecretCipher : SecretCipher {
    private const val KEY_ALIAS = "rctv.local.secrets.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    @Volatile
    private var key: SecretKey? = null

    override fun encrypt(plain: String, aad: String): String? {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return cipher.iv.toByteString().base64Url() + "." + sealed.toByteString().base64Url()
    }

    override fun decrypt(sealed: String, aad: String): String? {
        val iv = sealed.substringBefore('.', "").decodeBase64() ?: return null
        val body = sealed.substringAfter('.', "").decodeBase64() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv.toByteArray()))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(body.toByteArray()).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        key?.let { return it }
        synchronized(this) {
            key?.let { return it }
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            val created = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                generateKey()
            }
            key = created
            return created
        }
    }
}
