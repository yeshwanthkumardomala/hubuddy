package com.sidya.deskhub

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.EditText
import com.sidya.deskhub.data.DashState
import com.sidya.deskhub.ui.ChangePinActivity
import com.sidya.deskhub.ui.LoginActivity
import com.sidya.deskhub.ui.MainActivity
import com.sidya.deskhub.ui.screens.CalendarScreen
import com.sidya.deskhub.ui.screens.GitHubScreen
import com.sidya.deskhub.ui.screens.NetworkScreen
import com.sidya.deskhub.ui.screens.WeatherScreen
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders real app screens to PNG with sample data (not a regular test; skipped unless
 * -Pscreenshots=<dir> is given). Phone size close to the Vivo 1902: 720 x 1520 px.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h760dp-xhdpi")
class Screenshots {
    private val outDir: String? = System.getProperty("deskhub.screenshots")

    @Before fun setUp() {
        assumeTrue(outDir != null)
        DeskHubApp.cipherOverride = TestCipher()
    }

    private fun idle(ms: Long = 0) {
        val end = System.currentTimeMillis() + ms
        do { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) } while (System.currentTimeMillis() < end)
    }

    private fun shot(a: Activity, name: String) {
        idle()
        val root = a.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(outDir!!).mkdirs()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun app() = DeskHubApp.of(RuntimeEnvironment.getApplication())

    @Test fun login() {
        val a = Robolectric.buildActivity(LoginActivity::class.java).setup().get()
        a.window.decorView.findViewWithTag<EditText>("login").setText("123456789")
        a.window.decorView.findViewWithTag<EditText>("pin").setText("123456")
        shot(a, "01-login")
    }

    @Test fun changePin() {
        app().auth.login("123456789", "123456")
        val a = Robolectric.buildActivity(ChangePinActivity::class.java).setup().get()
        shot(a, "02-set-new-pin")
    }

    @Test fun offline() {
        app().auth.login("123456789", "123456"); app().auth.changePin("123456", "482916", "482916")
        val m = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle(500)
        shot(m, "11-home-offline-not-paired")
    }

    @Test fun dashboard() {
        val now = System.currentTimeMillis() / 1000
        val snapshot = """{"pc":{"online":true,"asleep":false,"hostname":"YESH-LAPTOP"},
          "stats":{"cpu":34.0,"ram_used":9.6,"ram_total":16.0,"disk":58.0,"net_down":1840.0,"net_up":96.0,"gpu":22,"gpu_temp":54,
                   "vram_used":1.8,"vram_total":6.0,"cpu_name":"AMD Ryzen 7 5800H","gpu_name":"NVIDIA GeForce RTX 3060 Laptop"},
          "claude":{"provider":"claude","today":{"tokens":1284000,"output":48200,"messages":214},"last_hour":182000,"last_active":${now - 20},
                    "limits":{"five_hour":{"pct":46.0,"reset":${now + 2 * 3600 + 900}},"seven_day":{"pct":21.0,"reset":${now + 4 * 86400}},"updated":${now - 30}},
                    "block":{"active":true,"tokens":612000,"messages":88,"reset":${now + 2 * 3600 + 900}},
                    "days":[420000,910000,650000,1180000,300000,780000,1284000],"models":[{"name":"Opus","tokens":1010000},{"name":"Sonnet","tokens":274000}]},
          "alert":{"id":"s1","kind":"permission","title":"Claude needs your permission to use Bash","detail":"npm run build","project":"hubuddy"},
          "sessions_info":{"sessions":[{"state":"permission","project":"hubuddy","age_s":12},{"state":"running","project":"deskhub-android","age_s":4}]},
          "media":{"active":true,"playing":true,"title":"Kesariya","artist":"Arijit Singh","album":"Brahmastra","source":"Spotify"},
          "calendar":{"days":7,"events":[
             {"title":"Team stand-up","loc":"Google Meet","cal":"Work","start":${now + 3600},"end":${now + 5400},"all_day":false,"color":"#2EC4B6"},
             {"title":"Hubuddy release review","loc":"","cal":"Personal","start":${now + 26 * 3600},"end":${now + 27 * 3600},"all_day":false,"color":"#8A5A3B"},
             {"title":"Project submission","loc":"","cal":"College","start":${now + 50 * 3600},"end":${now + 51 * 3600},"all_day":true,"color":"#F4B400"}]},
          "network":{"online":true,"latency_ms":18.4,"jitter_ms":2.1,"loss_pct":0,"today_bytes":3221225472,"month_bytes":96636764160,
                     "speed":{"down_mbps":94.2,"up_mbps":41.7,"ping_ms":12.0,"at":${now - 3600}},"testing":false},
          "shortcuts":{"tiles":[{"id":0,"label":"Lock PC","color":"#3A2A20"},{"id":1,"label":"VS Code","color":"#1E3A4A"},
             {"id":2,"label":"Mute mic","color":"#2B2F3A"},{"id":3,"label":"Screenshot","color":"#2B2F3A"},
             {"id":4,"label":"Volume up","color":"#24272B"},{"id":5,"label":"Downloads","color":"#24272B"}],"on":[2]},
          "github":{"login":"yeshwanthkumardomala","total":412,"today":6,"week":31,"last_week":24,"streak":9,"longest":21}}"""
        val token = "k".repeat(43)
        val server = MiniHttpServer { req ->
            if (req.headers["authorization"] != "Bearer $token") 401 to "{}"
            else if (req.path == "/api/snapshot") 200 to snapshot else 200 to """{"ok":true}"""
        }.start()
        try {
            val a = app()
            a.settings.usbPort = server.port
            a.settings.weatherName = "Hyderabad, IN"; a.settings.weatherLat = 17.38; a.settings.weatherLon = 78.49
            a.secrets.put(com.sidya.deskhub.net.CompanionLink.TOKEN, token)
            a.auth.login("123456789", "123456"); a.auth.changePin("123456", "482916", "482916")
            val m = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            val end = System.currentTimeMillis() + 8000
            while (!a.repo.state.online && System.currentTimeMillis() < end) idle(50)
            idle(1500) // let the (sandboxed) internet poll finish, then show sample weather/GitHub
            injectInternetSamples()
            fun tab(name: String) { m.window.decorView.findViewWithTag<View>("nav_$name").performClick(); idle(100) }
            m.rerender(); shot(m, "03-home")
            tab("Monitor"); shot(m, "04-monitor")
            m.push(NetworkScreen(m)); shot(m, "05-network")
            tab("Monitor"); m.push(GitHubScreen(m)); shot(m, "06-github")
            tab("AI"); shot(m, "07-ai-claude-code")
            tab("Controls"); shot(m, "08-controls")
            tab("Home"); m.push(CalendarScreen(m)); shot(m, "09-calendar")
            tab("Home"); m.push(WeatherScreen(m)); shot(m, "10-weather")
            tab("Settings"); shot(m, "12-settings")
            tab("Home")
        } finally { server.stop() }
    }

    private fun injectInternetSamples() {
        val now = System.currentTimeMillis()
        val weather = JSONObject("""{"current_units":{"temperature_2m":"°C","wind_speed_10m":"km/h"},
          "current":{"temperature_2m":29.4,"relative_humidity_2m":58,"weather_code":2,"wind_speed_10m":11.2},
          "daily":{"time":["2026-11-23","2026-11-24","2026-11-25","2026-11-26","2026-11-27","2026-11-28"],
            "weather_code":[2,1,3,61,80,0],"temperature_2m_max":[31,30,29,26,27,30],"temperature_2m_min":[19,18,19,20,19,18]}}""")
        val github = JSONObject("""{"description":"Hubuddy: DeskHub SIDYA Edition and more","stargazers_count":3,"forks_count":0,
          "open_issues_count":1,"default_branch":"feature/deskhub-android","pushed_at":"2026-11-23T09:12:44Z","private":false}""")
        val repo = app().repo
        val f = repo.javaClass.getDeclaredField("state").apply { isAccessible = true }
        val s = f.get(repo) as DashState
        f.set(repo, s.copy(weather = weather, weatherAt = now, weatherError = null, github = github, githubAt = now, githubError = null))
    }

    @Test @Config(qualifiers = "w760dp-h360dp-land-xhdpi") fun landscape() {
        app().auth.login("123456789", "123456"); app().auth.changePin("123456", "482916", "482916")
        val m = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        m.window.decorView.findViewWithTag<View>("nav_Monitor").performClick()
        idle(300)
        shot(m, "13-landscape-monitor-offline")
    }
}
