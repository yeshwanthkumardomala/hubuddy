package com.sidya.deskhub

import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.sidya.deskhub.core.MemoryKeyValue
import com.sidya.deskhub.core.Settings
import com.sidya.deskhub.net.CompanionLink
import com.sidya.deskhub.ui.MainActivity
import com.sidya.deskhub.ui.desk.DeskActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 28, 34], qualifiers = "w772dp-h360dp-land-xhdpi")
class DeskModeTest {
    private var server: MiniHttpServer? = null
    private val token = "k".repeat(43)

    @Before fun setUp() {
        DeskHubApp.cipherOverride = TestCipher()
        app().settings.nightEnabled = false
        app().auth.login("123456789", "123456"); app().auth.changePin("123456", "482916", "482916")
    }

    @After fun tearDown() { server?.stop() }

    private fun app() = DeskHubApp.of(RuntimeEnvironment.getApplication())

    private fun idle(ms: Long = 0) {
        val end = System.currentTimeMillis() + ms
        do { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) } while (System.currentTimeMillis() < end)
    }

    private fun companion(snapshot: String = Screenshots().sampleSnapshot()) {
        server = MiniHttpServer { req ->
            if (req.headers["authorization"] != "Bearer $token") 401 to "{}"
            else if (req.path == "/api/snapshot") 200 to snapshot else 200 to """{"ok":true}"""
        }.start()
        app().settings.usbPort = server!!.port
        app().secrets.put(CompanionLink.TOKEN, token)
    }

    private fun desk(): DeskActivity = Robolectric.buildActivity(DeskActivity::class.java).setup().get()

    private fun waitOnline() {
        val end = System.currentTimeMillis() + 8000
        while (!app().repo.state.online && System.currentTimeMillis() < end) idle(50)
        assertTrue("companion online", app().repo.state.online)
    }

    private fun title(d: DeskActivity) = d.window.decorView.findViewWithTag<TextView>("desk_title").text.toString()

    private fun charging(plugged: Boolean) {
        RuntimeEnvironment.getApplication().sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, if (plugged) BatteryManager.BATTERY_PLUGGED_USB else 0))
    }

    @Test fun startsAfterIdleWhileCharging() {
        charging(true)
        val m = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertFalse("not idle yet", m.shouldStartDesk(m.lastTouchAt + 60_000))
        assertTrue("5 min default", m.shouldStartDesk(m.lastTouchAt + 5 * 60_000))
        m.lastTouchAt = 0 // long ago
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(DeskActivity::class.java.name, shadowOf(m).nextStartedActivity.component!!.className)
    }

    @Test fun doesNotStartWhenDisabledOrOnBattery() {
        charging(false)
        val m = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertFalse("on battery, not allowed", m.shouldStartDesk(m.lastTouchAt + 60 * 60_000))
        app().settings.deskOnBattery = true
        assertTrue(m.shouldStartDesk(m.lastTouchAt + 60 * 60_000))
        app().settings.deskIdleMin = 0
        assertFalse("0 = manual only", m.shouldStartDesk(m.lastTouchAt + 600 * 60_000))
    }

    @Test fun deskButtonOpensDeskMode() {
        val m = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        m.window.decorView.findViewWithTag<View>("deskButton").performClick()
        assertEquals(DeskActivity::class.java.name, shadowOf(m).nextStartedActivity.component!!.className)
    }

    @Test fun offlineShowsOnlyTheClock() {
        // No PC, and no internet sources configured (weather/GitHub work over the phone's own internet).
        app().settings.githubRepo = ""
        val d = desk(); idle(300)
        assertEquals("CLOCK", title(d))
        assertEquals(listOf("clock"), d.rotation(app().repo.state).map { it.id })
        assertNotNull(d.window.decorView.findViewWithTag<TextView>("desk_clock"))
        d.next(); idle(100)
        assertEquals("still the clock", "CLOCK", title(d))
    }

    @Test fun rotatesThroughEveryPageWithData() {
        companion(Screenshots().sampleSnapshot().replace("\"alert\":{", "\"alert_off\":{"))
        val d = desk(); waitOnline()
        val ids = d.rotation(app().repo.state).map { it.id }
        // Every page that comes from the PC is there (weather needs a location, which this test does not set).
        assertTrue(ids.toString(), ids.containsAll(listOf("clock", "pc", "claude", "media", "calendar", "network", "github")))
        val seen = LinkedHashSet<String>()
        repeat(ids.size) { seen += title(d); d.next(); idle(50) }
        assertEquals(ids.size, seen.size)
        assertNotNull("heatmap drawn", run { while (title(d) != "GITHUB") { d.next(); idle(20) }; d.window.decorView.findViewWithTag<View>("desk_heatmap") })
    }

    @Test fun advancesAfterTheConfiguredTime() {
        companion(Screenshots().sampleSnapshot().replace("\"alert\":{", "\"alert_off\":{"))
        app().settings.deskPanelSec = 4
        val d = desk(); waitOnline(); idle(200)
        val first = title(d)
        Thread.sleep(4300)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        idle(100)
        assertTrue("moved on from $first", title(d) != first)
    }

    @Test fun claudeAlertJumpsToClaudePage() {
        companion() // sample data includes "Claude needs your permission"
        val d = desk(); waitOnline(); idle(200)
        assertEquals("CLAUDE", title(d))
        assertEquals("Waiting for permission", d.window.decorView.findViewWithTag<TextView>("desk_claude_state").text.toString())
        assertEquals(Pal_warn(), d.window.decorView.findViewWithTag<TextView>("rail_claude").currentTextColor)
    }

    private fun Pal_warn() = com.sidya.deskhub.ui.Pal.warn

    @Test fun nightShowsDimClockOnly() {
        app().settings.nightEnabled = true; app().settings.nightStart = 0; app().settings.nightEnd = 1439
        companion()
        val d = desk(); waitOnline()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2)); idle(100)
        assertTrue(d.isNightMode)
        assertNotNull(d.window.decorView.findViewWithTag<View>("desk_body_night"))
        assertEquals(View.GONE, d.window.decorView.findViewWithTag<View>("rail_clock")?.let { (it.parent as View).visibility } ?: View.GONE)
        assertEquals(0.02f, d.window.attributes.screenBrightness, 0.001f)
    }

    @Test fun touchLeavesDeskModeAndKeepsValidSession() {
        val d = desk()
        d.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        assertTrue(d.isFinishing)
        assertTrue(app().auth.canAccessDashboard())
    }

    @Test fun expiredSessionNeedsPinAfterDeskMode() {
        val d = desk()
        app().auth.sessionTimeoutMin = 1
        app().getSharedPreferences("deskhub", 0).edit().putLong("session.lastActive", 1L).commit()
        d.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        assertTrue(d.isFinishing)
        assertFalse("desk mode does not extend an expired session", app().auth.canAccessDashboard())
        val m = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(com.sidya.deskhub.ui.LoginActivity::class.java.name, shadowOf(m).nextStartedActivity.component!!.className)
    }

    @Test fun malformedDataNeverCrashesDeskMode() {
        companion("""{"pc":"x","stats":{"cpu":"hot"},"claude":[],"alert":{"kind":1,"id":5},"media":{"active":true,"title":null,"pos":"a"},
            "calendar":{"events":[{"start":"never"},null]},"network":{"online":"yes","hist":["a",null]},"github":{"levels":"9x9","start_wday":99}}""")
        val d = desk(); waitOnline()
        repeat(10) { d.next(); idle(30) }
        assertNull(null)
    }
}

class DeskSettingsTest {
    @Test fun defaultsPersistAndNightWrapsMidnight() {
        val kv = MemoryKeyValue()
        val s = Settings(kv)
        assertEquals(5, s.deskIdleMin); assertEquals(12, s.deskPanelSec)
        assertEquals(com.sidya.deskhub.core.DESK_PANELS, s.deskPanels)
        assertTrue(s.isNight(23 * 60 + 30)); assertTrue(s.isNight(3 * 60)); assertFalse(s.isNight(12 * 60)); assertFalse(s.isNight(7 * 60))
        s.deskPanels = listOf("pc", "bogus", "clock"); s.deskPanelSec = 1; s.deskIdleMin = 999
        val again = Settings(kv)
        assertEquals(listOf("pc", "clock"), again.deskPanels)
        assertEquals(4, again.deskPanelSec); assertEquals(120, again.deskIdleMin)
        again.nightStart = 22 * 60; again.nightEnd = 23 * 60
        assertTrue(again.isNight(22 * 60 + 5)); assertFalse(again.isNight(23 * 60 + 5))
        again.nightEnabled = false; assertFalse(again.isNight(22 * 60 + 5))
    }
}
