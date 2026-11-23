package com.sidya.hubuddy

import android.os.Looper
import android.view.View
import android.widget.TextView
import com.sidya.hubuddy.auth.AuthManager.LoginResult
import com.sidya.hubuddy.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Android 5.x has no AES keys in the Keystore: the app must still work, keeping secrets in memory only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 22])
class OldAndroidCompatTest {
    @Before fun realKeystorePath() { HubuddyApp.cipherOverride = null }

    @Test fun worksWithoutKeystoreAndNeverWritesSecretsToDisk() {
        val app = HubuddyApp.of(RuntimeEnvironment.getApplication())
        assertTrue(app.auth.login(BOOT_LOGIN, BOOT_PIN) is LoginResult.Success)
        assertFalse("falls back to memory", app.secureStorageAvailable)
        app.auth.changePin(BOOT_PIN, "482916", "482916")
        assertTrue(app.auth.canAccessDashboard())
        val prefs = app.getSharedPreferences("hubuddy", 0).all
        assertFalse("no secret.* keys persisted", prefs.keys.any { it.startsWith("secret.") })

        val m = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(m.window.decorView.findViewWithTag<View>("card_pc"))
        for (tab in listOf("Monitor", "AI", "Controls", "Settings")) {
            m.window.decorView.findViewWithTag<View>("nav_$tab").performClick()
        }
        m.window.decorView.findViewWithTag<View>("nav_Monitor").performClick()
        m.window.decorView.findViewWithTag<View>("open_network").performClick()
        assertNotNull(m.window.decorView.findViewWithTag<View>("net_phone")) // legacy network API path
        assertEquals(android.os.Build.VERSION.SDK_INT <= 22, true)
    }
}
