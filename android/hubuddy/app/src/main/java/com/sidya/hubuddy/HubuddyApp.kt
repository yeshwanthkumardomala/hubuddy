package com.sidya.hubuddy

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.sidya.hubuddy.auth.AuthManager
import com.sidya.hubuddy.core.EncryptedSecretStore
import com.sidya.hubuddy.core.KeystoreCipher
import com.sidya.hubuddy.core.MemorySecretStore
import com.sidya.hubuddy.core.PcRegistry
import com.sidya.hubuddy.core.PrefsKeyValue
import com.sidya.hubuddy.core.SecretCipher
import com.sidya.hubuddy.core.SecretStore
import com.sidya.hubuddy.core.Settings
import com.sidya.hubuddy.core.SystemClock
import com.sidya.hubuddy.data.DashboardRepository
import com.sidya.hubuddy.net.ApiClient
import com.sidya.hubuddy.net.AgentLink
import java.util.concurrent.Executor

/** Hand-wired service locator: no DI framework, everything created lazily. */
class HubuddyApp : Application() {
    private val kv by lazy { PrefsKeyValue(getSharedPreferences("hubuddy", Context.MODE_PRIVATE)) }

    /** False when the Android Keystore could not be used; secrets then live in RAM only. */
    var secureStorageAvailable = true
        private set

    val secrets: SecretStore by lazy {
        try {
            // Android 5.x has no AES keys in the Keystore: keep secrets in memory there.
            val cipher = cipherOverride ?: if (Build.VERSION.SDK_INT >= 23) KeystoreCipher() else throw IllegalStateException("no AES keystore")
            val probe = cipher.encrypt(byteArrayOf(1, 2, 3))
            check(cipher.decrypt(probe).contentEquals(byteArrayOf(1, 2, 3)))
            EncryptedSecretStore(kv, cipher)
        } catch (e: Throwable) {
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

    val pcs by lazy { PcRegistry(kv, secrets) }

    val agent by lazy { AgentLink(pcs, api) }

    val repo by lazy {
        val main = Handler(Looper.getMainLooper())
        DashboardRepository(settings, secrets, agent, api, cacheDir, SystemClock, Executor { main.post(it) }, pcs)
    }

    companion object {
        /** Tests (Robolectric has no AndroidKeyStore) inject a cipher here before first use. */
        @JvmStatic var cipherOverride: SecretCipher? = null

        fun of(ctx: Context) = ctx.applicationContext as HubuddyApp
    }
}
