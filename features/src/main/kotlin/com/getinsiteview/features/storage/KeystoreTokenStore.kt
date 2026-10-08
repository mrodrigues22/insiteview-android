package com.getinsiteview.features.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.getinsiteview.api.ApiClient
import com.getinsiteview.api.AuthTokens
import com.getinsiteview.api.TokenStore
import com.getinsiteview.core.KeyValueStore
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The signed-in user's tokens, encrypted (iOS `KeychainTokenStore`, docs/PLAN.md §3 "Networking and
 * auth"): an AES-256-GCM key that never leaves the Android Keystore ([alias]), and the ciphertext
 * (12-byte IV, then the sealed JSON of [AuthTokens]) as base64 under [key] in the [KeyValueStore]
 * (the app's DataStore).
 *
 * Tokens that can't be decrypted (the key was lost, e.g. after a backup restore to another phone)
 * read as signed out and are removed.
 */
class KeystoreTokenStore(
    private val store: KeyValueStore,
    private val alias: String = KEY_ALIAS,
    private val key: String = STORE_KEY,
) : TokenStore {
    override suspend fun load(): AuthTokens? {
        val stored = store.getString(key) ?: return null
        val tokens = withContext(Dispatchers.IO) {
            try {
                val json = decrypt(Base64.getDecoder().decode(stored))
                ApiClient.json.decodeFromString(AuthTokens.serializer(), json)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        if (tokens == null) store.remove(key)
        return tokens
    }

    override suspend fun save(tokens: AuthTokens) {
        val sealed = withContext(Dispatchers.IO) {
            encrypt(ApiClient.json.encodeToString(AuthTokens.serializer(), tokens))
        }
        store.putString(key, Base64.getEncoder().encodeToString(sealed))
    }

    override suspend fun clear() {
        store.remove(key)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return iv + sealed
    }

    private fun decrypt(data: ByteArray): String {
        require(data.size > IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES))
        return cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES).toString(Charsets.UTF_8)
    }

    companion object {
        const val KEY_ALIAS = "insiteview.tokens"
        const val STORE_KEY = "com.getinsiteview.tokens"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
