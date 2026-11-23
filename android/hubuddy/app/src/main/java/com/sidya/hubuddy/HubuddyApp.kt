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
import com.sidya.hubuddy.net.Discovery
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
        AuthManager(kv, secrets, SystemClock, BuildConfig.BOOTSTRAP_LOGIN, BuildConfig.BOOTSTRAP_PIN).also { a -> lockOverride?.let { a.lockEnabled = it } }
    }

    val api by lazy { ApiClient({ settings.timeoutSec * 1000 }, { settings.timeoutSec * 1000 + 1000 }) }

    val pcs by lazy { PcRegistry(kv, secrets) }

    val discovery by lazy { Discovery() }

    val agent by lazy { AgentLink(pcs, api, discovery) }

    val repo by lazy {
        val main = Handler(Looper.getMainLooper())
        DashboardRepository(settings, secrets, agent, api, cacheDir, SystemClock, Executor { main.post(it) }, pcs, phoneStatus = ::phoneStatusJson)
    }

    /** Battery and network of this phone, shown to the paired PCs (not location, not identity beyond the device name). */
    private fun phoneStatusJson(): org.json.JSONObject = try {
        val b = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = b?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        @Suppress("DEPRECATION") val net = cm.activeNetworkInfo
        org.json.JSONObject().apply {
            if (level >= 0 && scale > 0) put("battery", level * 100 / scale)
            put("charging", status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL)
            put("network", when (net?.type) { android.net.ConnectivityManager.TYPE_WIFI -> "Wi-Fi"; android.net.ConnectivityManager.TYPE_ETHERNET -> "USB/LAN"; null -> "offline"; else -> "mobile" })
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}".take(40))
        }
    } catch (e: Exception) { org.json.JSONObject() }

    companion object {
        /** Tests (Robolectric has no AndroidKeyStore) inject a cipher here before first use. */
        @JvmStatic var cipherOverride: SecretCipher? = null

        /** Tests that exercise the app lock switch it on here (it is off by default). */
        @JvmStatic var lockOverride: Boolean? = null

        fun of(ctx: Context) = ctx.applicationContext as HubuddyApp
    }
}
