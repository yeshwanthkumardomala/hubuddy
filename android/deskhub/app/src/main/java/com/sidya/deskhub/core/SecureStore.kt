package com.sidya.deskhub.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets. The production implementation keeps its key in the Android Keystore. */
interface SecretCipher {
    /** Returns "iv:ciphertext" in Base64, or throws if the platform keystore is unusable. */
    fun encrypt(plain: ByteArray): String
    fun decrypt(blob: String): ByteArray
}

/** AES-256-GCM with a non-exportable key in AndroidKeyStore. Requires Android 6 (API 23); see DeskHubApp. */
@android.annotation.TargetApi(23)
class KeystoreCipher(private val alias: String = "sidya_deskhub_master") : SecretCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(plain), Base64.NO_WRAP)
    }

    override fun decrypt(blob: String): ByteArray {
        val parts = blob.split(":")
        require(parts.size == 2) { "bad blob" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
    }
}

/** Secrets (companion token, GitHub token, session id) encrypted at rest. */
interface SecretStore {
    fun get(name: String): String?
    fun put(name: String, value: String?)
}

class EncryptedSecretStore(private val kv: KeyValue, private val cipher: SecretCipher) : SecretStore {
    override fun get(name: String): String? {
        val blob = kv.getString("secret.$name") ?: return null
        return try {
            String(cipher.decrypt(blob), Charsets.UTF_8)
        } catch (e: Exception) {
            // Key lost (e.g. app data restored on another device): forget the secret.
            kv.remove("secret.$name")
            null
        }
    }

    override fun put(name: String, value: String?) {
        if (value.isNullOrEmpty()) kv.remove("secret.$name")
        else kv.putString("secret.$name", cipher.encrypt(value.toByteArray(Charsets.UTF_8)))
    }
}

class MemorySecretStore : SecretStore {
    private val map = HashMap<String, String>()
    override fun get(name: String) = map[name]
    override fun put(name: String, value: String?) { if (value.isNullOrEmpty()) map.remove(name) else map[name] = value }
}
