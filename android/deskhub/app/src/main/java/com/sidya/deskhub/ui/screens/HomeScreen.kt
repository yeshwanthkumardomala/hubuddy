package com.sidya.deskhub.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.sidya.deskhub.data.DashState
import com.sidya.deskhub.ui.Fmt
import com.sidya.deskhub.ui.MainActivity
import com.sidya.deskhub.ui.Pal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeScreen(host: MainActivity) : Screen(host) {
    private var clock: TextView? = null
    private var date: TextView? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())

    override fun tick() {
        val now = Date()
        clock?.text = timeFmt.format(now)
        date?.text = dateFmt.format(now)
    }

    override fun render(s: DashState): View = ui.vbox {
        val now = Date()
        // The clock and date are local: they work with no PC and no internet.
        addView(ui.vbox {
            gravity = Gravity.CENTER_HORIZONTAL
            clock = ui.mono(timeFmt.format(now), 44f).apply { tag = "clock"; gravity = Gravity.CENTER }
            date = ui.text(dateFmt.format(now), 14f, Pal.dim, tag = "date").apply { gravity = Gravity.CENTER }
            addView(clock); addView(date)
        }, ui.lp(bottom = 10))
        banner(s)?.let { addView(it) }

        val cards = ArrayList<View>()
        val stats = section(s, "stats")
        cards += ui.card("PC", tag = "card_pc", onClick = { host.openTab(1) }) {
            val pc = section(s, "pc")
            addView(ui.row("Status", when {
                s.online && pc?.optBoolean("asleep") == true -> "Online (display asleep)"
                s.online -> "Online"
                else -> "Offline"
            }, if (s.online) Pal.ok else Pal.bad))
            if (stats == null) empty("No PC stats yet.")
            else {
                addView(ui.meter("CPU", stats.num("cpu")))
                val used = stats.num("ram_used"); val total = stats.num("ram_total")
                addView(ui.meter("RAM", if (used != null && total != null && total > 0) used / total * 100 else null,
                    if (used != null && total != null) "%.1f/%.0f GB".format(used, total) else null))
                addView(ui.meter("Disk", stats.num("disk")))
            }
        }
        cards += ui.card("Network", tag = "card_net", onClick = { host.push(NetworkScreen(host)) }) {
            val net = section(s, "network")
            addView(ui.row("Companion link", if (s.online) "${s.latencyMs} ms" else "unreachable", if (s.online) Pal.ok else Pal.bad))
            if (net != null) addView(ui.row("PC internet", if (net.optBoolean("online")) "online" else "offline",
                if (net.optBoolean("online")) Pal.ok else Pal.bad))
            if (stats != null) addView(ui.row("PC ↓ / ↑", "${Fmt.kbps(stats.num("net_down"))} / ${Fmt.kbps(stats.num("net_up"))}"))
        }
        cards += ui.card("Claude Code", tag = "card_ai", onClick = { host.openTab(2) }) {
            val claude = section(s, "claude")
            val alert = section(s, "alert")
            if (claude == null && alert == null) empty("Claude Code companion not connected.")
            else {
                addView(ui.row("State", AiScreen.stateLabel(claude, alert, section(s, "sessions_info")), AiScreen.stateColor(alert)))
                val five = claude?.optJSONObject("limits")?.optJSONObject("five_hour")
                if (five != null) addView(ui.meter("5-hour limit", five.num("pct"), Fmt.until(five.optLong("reset"))))
                claude?.optJSONObject("today")?.let { addView(ui.row("Tokens today", Fmt.tokens(it.optLong("tokens")))) }
            }
        }
        cards += ui.card("Next event", tag = "card_cal", onClick = { host.push(CalendarScreen(host)) }) {
            val ev = section(s, "calendar")?.optJSONArray("events").objects()
                .firstOrNull { it.optLong("end", it.optLong("start")) * 1000 >= System.currentTimeMillis() }
            if (section(s, "calendar") == null) empty("Calendar comes from the PC companion.")
            else if (ev == null) empty("No upcoming events.")
            else {
                addView(ui.text(ev.optString("title", "(untitled)"), 15f, bold = true))
                addView(ui.text(CalendarScreen.whenText(ev), 12f, Pal.dim))
            }
        }
        cards += ui.card("Weather", tag = "card_weather", onClick = { host.push(WeatherScreen(host)) }) {
            val cur = s.weather?.optJSONObject("current")
            if (cur == null) empty(s.weatherError ?: "Loading weather…")
            else {
                val unit = s.weather.optJSONObject("current_units")?.optString("temperature_2m") ?: "°"
                addView(ui.text("%.0f%s  %s".format(cur.optDouble("temperature_2m"), unit, Fmt.weather(cur.optInt("weather_code"))), 18f))
                addView(ui.text(app.settings.weatherName.ifEmpty { "Location" } + (s.weatherError?.let { " · $it" } ?: ""), 12f, Pal.dim))
            }
        }
        cards += ui.card("Now playing", tag = "card_media", onClick = { host.openTab(3) }) {
            val m = section(s, "media")
            if (m == null || !m.optBoolean("active")) empty(if (m == null) "Media info comes from the PC companion." else "Nothing playing.")
            else {
                addView(ui.text(m.optString("title"), 15f, bold = true))
                addView(ui.text(listOf(m.optString("artist"), if (m.optBoolean("playing")) "playing" else "paused")
                    .filter { it.isNotEmpty() }.joinToString(" · "), 12f, Pal.dim))
            }
        }
        addView(columns(cards))
    }
}
