package com.sidya.hubuddy

import android.os.Looper
import android.view.View
import android.widget.TextView
import com.sidya.hubuddy.core.MemoryKeyValue
import com.sidya.hubuddy.core.MemorySecretStore
import com.sidya.hubuddy.core.PcRegistry
import com.sidya.hubuddy.ui.MainActivity
import com.sidya.hubuddy.ui.Pal
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

class PcRegistryTest {
    private val kv = MemoryKeyValue()
    private val secrets = MemorySecretStore()

    @Test fun startsWithOnePcAndAddsUpToEight() {
        val r = PcRegistry(kv, secrets)
        assertEquals(1, r.list().size)
        repeat(7) { assertNotNull(r.add()) }
        assertEquals(8, r.list().size)
        assertFalse(r.canAdd)
        assertNull("limit", r.add())
        assertEquals(8, r.list().map { it.id }.toSet().size)
    }

    @Test fun tokensAreSeparatePerPcAndRemovedWithIt() {
        val r = PcRegistry(kv, secrets)
        val b = r.add("Laptop")!!
        r.setToken("pc1", "token-one"); r.setToken(b.id, "token-two")
        assertEquals("token-one", r.token("pc1")); assertEquals("token-two", r.token(b.id))
        assertTrue(r.isPaired(b.id))
        r.activeId = b.id
        assertTrue(r.remove(b.id))
        assertNull(r.token(b.id))
        assertEquals("falls back to the first PC", "pc1", r.activeId)
        assertFalse("last PC cannot be removed", r.remove("pc1"))
    }

    @Test fun persistsAcrossRestartAndValidatesNames() {
        val r = PcRegistry(kv, secrets)
        val p = r.add("  A very long name that must be cut to twenty-four characters  ")!!
        assertEquals(24, p.name.length)
        r.update(p.copy(mode = "wifi", address = "192.168.1.9:8765"))
        val again = PcRegistry(kv, secrets)
        assertEquals("192.168.1.9:8765", again.get(p.id)!!.endpoint())
        assertEquals("127.0.0.1:8765", again.get("pc1")!!.endpoint())
    }
}

class PaletteTest {
    @Test fun everyLookIsReadable() {
        for (look in listOf("dark", "black", "light")) {
            Pal.apply(look)
            assertEquals(look, Pal.current)
            for ((name, fg, bg, min) in listOf(
                Quad("text on bg", Pal.text, Pal.bg, 7.0), Quad("text on card", Pal.text, Pal.surface, 7.0),
                Quad("dim on card", Pal.dim, Pal.surface, 4.5), Quad("dim on bg", Pal.dim, Pal.bg, 4.5),
                Quad("button label", Pal.onAccent, Pal.accent, 4.5), Quad("accent on card", Pal.accent, Pal.surface, 3.0),
                Quad("ok", Pal.ok, Pal.surface, 3.0), Quad("warn", Pal.warn, Pal.surface, 3.0), Quad("bad", Pal.bad, Pal.surface, 3.0),
            )) assertTrue("$look: $name contrast ${"%.1f".format(Pal.contrast(fg, bg))} < $min", Pal.contrast(fg, bg) >= min)
        }
        Pal.apply("dark")
    }

    private data class Quad(val n: String, val a: Int, val b: Int, val c: Double)

    @Test fun lightIsLightAndDarkIsDark() {
        Pal.apply("light"); assertTrue(Pal.isLight); assertTrue(Pal.contrast(Pal.bg, 0xFFFFFFFF.toInt()) < 1.2)
        Pal.apply("black"); assertFalse(Pal.isLight); assertEquals(android.graphics.Color.BLACK, Pal.bg)
        Pal.apply("dark")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MultiPcUiTest {
    private val servers = ArrayList<MiniHttpServer>()
    @Volatile private var up = HashMap<Int, Boolean>()

    @Before fun setUp() {
        HubuddyApp.cipherOverride = TestCipher()
        app().auth.login(BOOT_LOGIN, BOOT_PIN); app().auth.changePin(BOOT_PIN, "482916", "482916")
        app().settings.theme = "dark"
    }

    @After fun tearDown() { servers.forEach { it.stop() } }

    private fun app() = HubuddyApp.of(RuntimeEnvironment.getApplication())

    private fun fake(cpu: Double, host: String, alert: Boolean = false): Int {
        val snap = """{"pc":{"online":true,"hostname":"$host"},"stats":{"cpu":$cpu,"ram_used":8.0,"ram_total":16.0,"disk":40.0,"gpu":10,"gpu_temp":50,"net_down":100.0,"net_up":10.0},
            "claude":{"today":{"tokens":1000,"messages":2},"last_active":${System.currentTimeMillis() / 1000},"limits":{}}${if (alert) ""","alert":{"id":"a","kind":"permission","title":"x"}""" else ""}}"""
        val s = MiniHttpServer { req ->
            if (req.headers["authorization"] != "Bearer ${"k".repeat(43)}") 401 to "{}" else if (req.path == "/api/snapshot") 200 to snap else 200 to "{}"
        }.start()
        servers += s
        return s.port
    }

    private fun idle(ms: Long = 0) {
        val end = System.currentTimeMillis() + ms
        do { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) } while (System.currentTimeMillis() < end)
    }

    private fun <T : View> android.app.Activity.tagged(t: String): T? = window.decorView.findViewWithTag(t)

    @Test fun threePcsAreAllPolledAndShownTogether() {
        val p1 = fake(20.0, "DOCK-PC"); val p2 = fake(70.0, "LAPTOP-A", alert = true)
        val dead = java.net.ServerSocket(0).use { it.localPort } // nothing listens: offline PC
        app().pairActivePc(p1); app().addPairedPc("Laptop A", p2); app().addPairedPc("Old PC", dead)
        val m = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val end = System.currentTimeMillis() + 10_000
        while (app().repo.state.pcs.count { it.online } < 2 && System.currentTimeMillis() < end) idle(50)
        idle(300)
        val st = app().repo.state
        assertEquals(3, st.pcs.size)
        assertEquals(2, st.onlineCount)
        assertEquals(70.0, st.pcs[1].num("stats", "cpu")!!, 0.01)
        assertFalse(st.pcs[2].online)

        // Monitor tab defaults to the "All PCs" overview with one tile per machine.
        m.tagged<View>("nav_Monitor")!!.performClick(); idle(100)
        for (id in listOf("pc1", "pc2", "pc3")) assertNotNull("tile $id", m.tagged<View>("pc_tile_$id"))
        val text = allText(m.window.decorView)
        assertTrue(text, text.contains("2 of 3 online")); assertTrue(text.contains("Claude waiting") || text.contains("1 Claude waiting"))
        assertTrue(text.contains("DOCK-PC")); assertTrue(text.contains("Laptop A"))

        // Tapping a tile selects that PC; Home then reads from it.
        m.tagged<View>("pc_tile_pc2")!!.performClick(); idle(100)
        assertEquals("pc2", app().pcs.activeId)
        m.tagged<View>("nav_Home")!!.performClick(); idle(100)
        assertNotNull(m.tagged<View>("card_fleet")) // Home shows the fleet summary when several PCs exist
        assertTrue(allText(m.window.decorView).contains("70%"))
    }

    @Test fun anOfflinePcDoesNotBlockTheOthers() {
        val p1 = fake(33.0, "A")
        val dead = java.net.ServerSocket(0).use { it.localPort }
        app().settings.timeoutSec = 1
        app().addPairedPc("Dead", dead); app().pairActivePc(p1)
        val m = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val end = System.currentTimeMillis() + 8000
        while (!app().repo.state.pcs.first { it.pc.id == "pc1" }.online && System.currentTimeMillis() < end) idle(50)
        assertTrue(app().repo.state.pcs.first { it.pc.id == "pc1" }.online)
    }

    @Test fun settingsAddsEditsAndRemovesPcs() {
        val m = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        m.tagged<View>("nav_Settings")!!.performClick(); idle(100)
        assertNull(m.tagged<View>("removePc"))
        m.tagged<View>("setchip_add")!!.performClick(); idle(100)
        assertEquals(2, app().pcs.list().size)
        val name = m.tagged<android.widget.EditText>("pcName")!!
        name.setText("Studio laptop")
        m.tagged<View>("mode_wifi")!!.performClick()
        m.tagged<android.widget.EditText>("wifiAddress")!!.setText("192.168.1.50:8765")
        m.tagged<View>("saveConn")!!.performClick()
        assertEquals("Studio laptop", app().pcs.list()[1].name)
        assertEquals("192.168.1.50:8765", app().pcs.list()[1].endpoint())
        // Duplicate names are refused.
        name.setText("my pc"); m.tagged<View>("saveConn")!!.performClick()
        assertTrue(m.tagged<TextView>("connMessage")!!.text.contains("already has this name"))
        m.tagged<View>("removePc")!!.performClick()
        assertNotNull(m.tagged<View>("nav_Settings"))
    }

    @Test fun lightThemeBuildsAndSwitches() {
        val m = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        m.tagged<View>("nav_Settings")!!.performClick(); idle(100)
        m.tagged<View>("theme_light")!!.performClick(); idle(100)
        assertEquals("light", app().settings.theme)
        assertTrue(Pal.isLight)
        assertEquals(Pal.bg, (m.window.decorView.findViewWithTag<View>("content").parent as View).let { Pal.bg })
        m.tagged<View>("nav_Home")!!.performClick(); idle(50)
        m.tagged<View>("theme_dark")?.let { }
        app().settings.theme = "dark"; m.applyTheme()
        assertFalse(Pal.isLight)
    }

    @Test @Config(sdk = [28], qualifiers = "w772dp-h360dp-land-xhdpi") fun landscapeUsesARailAndColumns() {
        val m = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle(200)
        assertNotNull("side rail", m.tagged<View>("rail"))
        assertTrue(m.isLandscape); assertEquals(2, m.columnCount)
        assertNotNull(m.tagged<View>("pageTitle"))
        for (t in listOf("Monitor", "AI", "Controls", "Settings", "Home")) { m.tagged<View>("nav_$t")!!.performClick(); idle(50) }
    }

    @Test @Config(sdk = [28], qualifiers = "w360dp-h760dp-xhdpi") fun portraitUsesBottomBarAndOneColumn() {
        val m = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNull(m.tagged<View>("rail")); assertEquals(1, m.columnCount)
        assertNotNull(m.tagged<View>("nav_Home"))
    }

    private fun allText(v: View): String = when (v) {
        is TextView -> v.text.toString() + "\n"
        is android.view.ViewGroup -> (0 until v.childCount).joinToString("") { allText(v.getChildAt(it)) }
        else -> ""
    }
}
