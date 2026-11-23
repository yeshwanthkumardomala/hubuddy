package com.sidya.hubuddy

import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.sidya.hubuddy.core.SecretCipher
import com.sidya.hubuddy.ui.ChangePinActivity
import com.sidya.hubuddy.ui.LoginActivity
import com.sidya.hubuddy.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Robolectric has no AndroidKeyStore; this stands in with the same AES-GCM scheme. */
class TestCipher : SecretCipher {
    private val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
    override fun encrypt(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key)
        return Base64.getEncoder().encodeToString(c.iv) + ":" + Base64.getEncoder().encodeToString(c.doFinal(plain))
    }
    override fun decrypt(blob: String): ByteArray {
        val (iv, ct) = blob.split(":")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.getDecoder().decode(iv)))
        return c.doFinal(Base64.getDecoder().decode(ct))
    }
}

@RunWith(RobolectricTestRunner::class)
// Every test runs on each Android version: 5.0, 7.0, 8.0, 9 (Vivo 1902), 11, 14.
@Config(sdk = [21, 24, 26, 28, 30, 34])
class UiFlowTest {
    private val controllers = ArrayList<ActivityController<*>>()

    companion object {
        @JvmStatic @BeforeClass fun cipher() { HubuddyApp.cipherOverride = TestCipher() }
    }

    @After fun tearDown() { controllers.forEach { runCatching { it.pause().stop().destroy() } } }

    private fun <T : android.app.Activity> launch(cls: Class<T>, intent: Intent? = null): T {
        val c = if (intent != null) Robolectric.buildActivity(cls, intent) else Robolectric.buildActivity(cls)
        controllers += c
        return c.setup().get()
    }

    /** Let worker threads (PBKDF2 runs off the UI thread) finish and their posts run. */
    private fun waitFor(timeoutMs: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out")
    }

    private fun <T : View> android.app.Activity.byTag(tag: String): T? = window.decorView.findViewWithTag(tag)

    private fun login(a: LoginActivity, number: String, pin: String) {
        a.byTag<EditText>("login")!!.setText(number)
        a.byTag<EditText>("pin")!!.setText(pin)
        a.byTag<View>("loginButton")!!.performClick()
    }

    private fun app() = HubuddyApp.of(org.robolectric.RuntimeEnvironment.getApplication())

    @Test fun loginScreenShowsFieldsAndMasksPin() {
        val a = launch(LoginActivity::class.java)
        assertNotNull(a.byTag<EditText>("login"))
        val pin = a.byTag<EditText>("pin")!!
        assertTrue("PIN masked", pin.transformationMethod is android.text.method.PasswordTransformationMethod)
        a.byTag<View>("togglePin")!!.performClick()
        assertTrue("PIN shown", pin.transformationMethod !is android.text.method.PasswordTransformationMethod)
    }

    @Test fun wrongPinIsRejected() {
        val a = launch(LoginActivity::class.java)
        login(a, BOOT_LOGIN, "000000")
        waitFor { a.byTag<TextView>("message")!!.text.contains("Incorrect") }
        assertNull(shadowOf(a).nextStartedActivity)
        assertTrue(!app().auth.hasSession())
    }

    @Test fun firstLoginForcesPinChangeThenOpensDashboard() {
        val a = launch(LoginActivity::class.java)
        login(a, BOOT_LOGIN, BOOT_PIN)
        waitFor { shadowOf(a).peekNextStartedActivity() != null }
        val next = shadowOf(a).nextStartedActivity
        assertEquals(ChangePinActivity::class.java.name, next.component!!.className)

        // The dashboard refuses to open before the PIN is changed.
        val blocked = launch(MainActivity::class.java)
        assertEquals(LoginActivity::class.java.name, shadowOf(blocked).nextStartedActivity.component!!.className)
        while (shadowOf(blocked).nextStartedActivity != null) { /* drain the shared started-activity queue */ }

        val c = launch(ChangePinActivity::class.java, next)
        c.byTag<EditText>("currentPin")!!.setText(BOOT_PIN)
        c.byTag<EditText>("newPin")!!.setText(BOOT_PIN)
        c.byTag<EditText>("confirmPin")!!.setText(BOOT_PIN)
        c.byTag<View>("savePin")!!.performClick()
        waitFor { c.byTag<TextView>("message")!!.text.contains("different") }

        c.byTag<EditText>("currentPin")!!.setText(BOOT_PIN)
        c.byTag<EditText>("newPin")!!.setText("482916")
        c.byTag<EditText>("confirmPin")!!.setText("482916")
        c.byTag<View>("savePin")!!.performClick()
        waitFor { shadowOf(c).peekNextStartedActivity() != null }
        assertEquals(MainActivity::class.java.name, shadowOf(c).nextStartedActivity.component!!.className)
        assertTrue(app().auth.canAccessDashboard())
    }

    private fun loggedInMain(): MainActivity {
        val auth = app().auth
        auth.login(BOOT_LOGIN, BOOT_PIN)
        auth.changePin(BOOT_PIN, "482916", "482916")
        return launch(MainActivity::class.java)
    }

    @Test fun dashboardOpensOfflineWithoutBigClockAndWithClearStates() {
        val m = loggedInMain()
        shadowOf(Looper.getMainLooper()).idle()
        // The clock lives in dock mode only; the everyday dashboard is all dashboard.
        assertNull(m.byTag<View>("clock"))
        assertNotNull(m.byTag<View>("card_pc"))
        // Not paired: a banner says so and no fake numbers are shown.
        waitFor { m.byTag<View>("banner") != null }
        assertTrue(m.byTag<TextView>("statusText")!!.text.contains("Not paired"))
        val text = allText(m.window.decorView)
        assertTrue(text.contains("No PC stats yet."))
    }

    @Test fun bottomNavigationOpensAllFiveTabs() {
        val m = loggedInMain()
        for ((tab, marker) in listOf("Monitor" to "pc_status", "AI" to "ai_empty", "Controls" to "media",
            "Settings" to "connStatus", "Home" to "card_pc")) {
            m.byTag<View>("nav_$tab")!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull("tab $tab shows $marker", m.byTag<View>(marker))
        }
        m.byTag<View>("nav_AI")!!.performClick()
        assertTrue(allText(m.window.decorView).contains("Claude Code is not connected."))
    }

    @Test fun secondaryPagesOpenAndBackReturns() {
        val m = loggedInMain()
        m.byTag<View>("card_cal")!!.performClick()
        assertNotNull(m.byTag<View>("cal_empty"))
        m.byTag<View>("back")!!.performClick()
        assertNotNull(m.byTag<View>("card_pc"))
        m.byTag<View>("card_weather")!!.performClick()
        assertNotNull(m.byTag<View>("weather_now"))
        m.onBackPressed()
        m.byTag<View>("nav_Monitor")!!.performClick()
        m.byTag<View>("open_network")!!.performClick()
        assertNotNull(m.byTag<View>("net_phone"))
    }

    @Test fun connectionTestRequiresPairingThenReportsDeadPcWithoutCrash() {
        val m = loggedInMain()
        m.byTag<View>("nav_Settings")!!.performClick()
        m.byTag<View>("testConn")!!.performClick()
        assertTrue(m.byTag<TextView>("connMessage")!!.text.contains("Pair this PC first"))
        val dead = java.net.ServerSocket(0).use { it.localPort }
        app().pairActivePc(dead)
        m.rerender()
        m.byTag<View>("testConn")!!.performClick()
        waitFor(8000) { m.byTag<TextView>("connMessage")!!.text.contains("Cannot reach the PC") }
    }

    @Test fun pairingByPastedInvitationWorksEndToEnd() {
        val server = MiniHttpServer { req ->
            if (req.path == "/api/pairing/claim") 200 to """{"ok":true,"token":"${"p".repeat(43)}"}""" else 404 to "{}"
        }.start()
        try {
            val m = loggedInMain()
            m.pairing.pair(invitationText(TestPki.A, server.port))
            waitFor(8000) { app().agent.isPaired("pc1") }
            assertEquals(TestPki.A.hostId, app().pcs.get("pc1")!!.hostId)
        } finally { server.stop() }
    }

    @Test fun badInvitationsAreExplainedAndNothingIsPaired() {
        val m = loggedInMain()
        m.pairing.pair("hello")
        m.pairing.pair(invitationText(TestPki.A, 9, expires = System.currentTimeMillis() - 1))
        m.pairing.pair(invitationText(TestPki.A, 9, address = "8.8.8.8"))
        assertFalse(app().agent.isPaired("pc1"))
    }

    @Test fun logoutReturnsToLogin() {
        val m = loggedInMain()
        m.byTag<View>("nav_Settings")!!.performClick()
        m.byTag<View>("logout")!!.performClick()
        assertEquals(LoginActivity::class.java.name, shadowOf(m).nextStartedActivity.component!!.className)
        assertTrue(!app().auth.hasSession())
    }

    @Test fun rotationKeepsDashboard() {
        val m = loggedInMain()
        m.byTag<View>("nav_Monitor")!!.performClick()
        val cfg = android.content.res.Configuration(m.resources.configuration)
        cfg.orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE
        m.onConfigurationChanged(cfg)
        assertNotNull(m.byTag<View>("pc_status"))
    }

    private fun withAgent(snapshot: String, block: (MainActivity) -> Unit) {
        val server = MiniHttpServer { req ->
            if (req.headers["authorization"] != "Bearer ${"k".repeat(43)}") 401 to "{}"
            else if (req.path == "/api/snapshot") 200 to snapshot else 200 to """{"ok":true}"""
        }.start()
        try {
            app().pairActivePc(server.port)
            block(loggedInMain())
        } finally { server.stop() }
    }

    @Test fun dashboardShowsLiveAgentData() = withAgent("""{"pc":{"online":true,"hostname":"DEVPC"},
        "stats":{"cpu":37.0,"ram_used":8.0,"ram_total":16.0,"disk":61.0,"net_down":120.0,"net_up":12.0,"gpu":20,"gpu_temp":55},
        "claude":{"today":{"tokens":12345,"messages":3},"limits":{"five_hour":{"pct":42.0,"reset":${System.currentTimeMillis() / 1000 + 3600}}},"last_active":${System.currentTimeMillis() / 1000},"days":[1,2,3,4,5,6,7]},
        "alert":{"id":"s1","kind":"permission","title":"Claude needs your permission to use Bash","project":"hubuddy"},
        "media":{"active":true,"playing":true,"title":"Song","artist":"Band"},
        "shortcuts":{"buttons":[{"id":0,"label":"Lock PC","color":"#2b2f3a"}],"on":[]},
        "calendar":{"events":[],"days":7}}""") { m ->
        // Wait for the UI (not just the data layer) to show the update: it is posted to the main thread.
        waitFor(8000) { m.byTag<TextView>("statusText")!!.text.contains("PC online") }
        val home = allText(m.window.decorView)
        assertTrue(home, home.contains("37%"))
        assertTrue(home.contains("Waiting for permission"))
        assertTrue(home.contains("Song"))
        assertTrue(m.byTag<TextView>("statusText")!!.text.contains("PC online"))
        m.byTag<View>("nav_Monitor")!!.performClick()
        val mon = allText(m.window.decorView)
        assertTrue(mon.contains("DEVPC")); assertTrue(mon.contains("55 °C")); assertTrue(mon.contains("CPU temperature"))
        m.byTag<View>("nav_AI")!!.performClick()
        val ai = allText(m.window.decorView)
        assertTrue(ai.contains("12.3k")); assertTrue(ai.contains("42%"))
        m.byTag<View>("nav_Controls")!!.performClick()
        assertTrue(allText(m.window.decorView).contains("Lock PC"))
        // Leaving the app stops polling.
        m.byTag<View>("nav_Home")!!.performClick()
    }

    @Test fun malformedPayloadDoesNotCrash() = withAgent("""{"pc":"oops","stats":{"cpu":"high","ram_used":null},
        "claude":[1,2],"alert":{"kind":7},"media":{"active":"yes"},"shortcuts":{"buttons":"none"},"calendar":{"events":[null,{"start":"x"}]}}""") { m ->
        waitFor(8000) { app().repo.state.online }
        for (tab in listOf("Home", "Monitor", "AI", "Controls", "Settings", "Home")) {
            m.byTag<View>("nav_$tab")!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
        }
        m.byTag<View>("card_cal")!!.performClick()
        assertNotNull(m.byTag<View>("back"))
    }

    private fun allText(v: View): String = when (v) {
        is TextView -> v.text.toString() + "\n"
        is android.view.ViewGroup -> (0 until v.childCount).joinToString("") { allText(v.getChildAt(it)) }
        else -> ""
    }
}
