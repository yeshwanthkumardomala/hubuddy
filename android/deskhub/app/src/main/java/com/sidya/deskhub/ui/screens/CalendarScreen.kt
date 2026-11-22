package com.sidya.deskhub.ui.screens

import android.graphics.Color
import android.view.View
import com.sidya.deskhub.data.DashState
import com.sidya.deskhub.ui.MainActivity
import com.sidya.deskhub.ui.Pal
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CalendarScreen(host: MainActivity) : Screen(host) {
    override fun render(s: DashState): View = ui.vbox {
        banner(s)?.let { addView(it) }
        val cal = section(s, "calendar")
        if (cal == null) {
            addView(ui.card("Calendar", tag = "cal_empty") {
                empty("Calendar is provided by the PC companion. Add ICS / Google / iCloud / Outlook feeds in its config.toml ([calendar] feeds).")
            })
            return@vbox
        }
        val now = System.currentTimeMillis()
        val events = cal.optJSONArray("events").objects().filter { it.optLong("end", it.optLong("start")) * 1000 >= now }
        if (events.isEmpty()) {
            addView(ui.card("Calendar", tag = "cal_empty") { empty("No events in the next ${cal.optInt("days", 7)} days.") })
            return@vbox
        }
        val dayFmt = SimpleDateFormat("EEEE, d MMM", Locale.getDefault())
        events.groupBy { dayFmt.format(Date(it.optLong("start") * 1000)) }.forEach { (day, list) ->
            addView(ui.card(day) {
                list.forEach { ev ->
                    addView(ui.hbox {
                        addView(ui.dot(parseColor(ev.optString("color"))))
                        addView(ui.vbox {
                            addView(ui.text(ev.optString("title", "(untitled)"), 14f))
                            addView(ui.text(listOf(whenText(ev), ev.optString("loc"), ev.optString("cal"))
                                .filter { it.isNotEmpty() }.joinToString(" · "), 11f, Pal.dim))
                        })
                        setPadding(0, ui.dp(4), 0, ui.dp(4))
                    })
                }
            })
        }
    }

    private fun parseColor(c: String): Int = try { Color.parseColor(c) } catch (e: Exception) { Pal.accent }

    companion object {
        fun whenText(ev: JSONObject): String {
            val start = ev.optLong("start") * 1000
            if (ev.optBoolean("all_day")) return SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(start)) + " · all day"
            val f = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())
            val end = ev.optLong("end") * 1000
            return f.format(Date(start)) + if (end > start) "–" + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(end)) else ""
        }
    }
}
