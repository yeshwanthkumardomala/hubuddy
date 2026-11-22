package com.sidya.deskhub

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.sidya.deskhub.auth.AuthManager
import com.sidya.deskhub.core.EncryptedSecretStore
import com.sidya.deskhub.core.KeystoreCipher
import com.sidya.deskhub.core.MemorySecretStore
import com.sidya.deskhub.core.PrefsKeyValue
import com.sidya.deskhub.core.SecretCipher
import com.sidya.deskhub.core.SecretStore
import com.sidya.deskhub.core.Settings
import com.sidya.deskhub.core.SystemClock
import com.sidya.deskhub.data.DashboardRepository
import com.sidya.deskhub.net.ApiClient
import com.sidya.deskhub.net.CompanionLink
import java.util.concurrent.Executor

/** Hand-wired service locator: no DI framework, everything created lazily. */
class DeskHubApp : Application() {
    private val kv by lazy { PrefsKeyValue(getSharedPreferences("deskhub", Context.MODE_PRIVATE)) }

    /** False when the Android Keystore could not be used; secrets then live in RAM only. */
    var secureStorageAvailable = true
        private set

    val secrets: SecretStore by lazy {
        val cipher = cipherOverride ?: KeystoreCipher()
        try {
            val probe = cipher.encrypt(byteArrayOf(1, 2, 3))
            check(cipher.decrypt(probe).contentEquals(byteArrayOf(1, 2, 3)))
            EncryptedSecretStore(kv, cipher)
        } catch (e: Exception) {
            // Never fall back to plaintext on disk: keep secrets in memory for this run.
            secureStorageAvailable = false
            MemorySecretStore()
        }
    }

    val settings by lazy { Settings(kv) }

    val auth by lazy {
        AuthManager(kv, secrets, SystemClock, BuildConfig.BOOTSTRAP_LOGIN, BuildConfig.BOOTSTRAP_PIN)
    }

    val api by lazy { ApiClient({ settings.timeoutSec * 1000 }, { settings.timeoutSec * 1000 + 1000 }) }

    val companion by lazy { CompanionLink(settings, secrets, api) }

    val repo by lazy {
        val main = Handler(Looper.getMainLooper())
        DashboardRepository(settings, secrets, companion, api, cacheDir, SystemClock, Executor { main.post(it) })
    }

    companion object {
        /** Tests (Robolectric has no AndroidKeyStore) inject a cipher here before first use. */
        @JvmStatic var cipherOverride: SecretCipher? = null

        fun of(ctx: Context) = ctx.applicationContext as DeskHubApp
    }
}
