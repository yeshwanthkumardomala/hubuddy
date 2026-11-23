package com.sidya.hubuddy.ui.screens

import android.view.View
import com.sidya.hubuddy.data.DashState
import com.sidya.hubuddy.ui.Fmt
import com.sidya.hubuddy.ui.MainActivity
import com.sidya.hubuddy.ui.Pal
import java.text.SimpleDateFormat
import java.util.Locale

/** Weather straight from Open-Meteo (no key, no PC needed); needs internet. */
class WeatherScreen(host: MainActivity) : Screen(host) {
    override fun render(s: DashState): View = ui.vbox {
        val w = s.weather
        addView(ui.card("Now · ${app.settings.weatherName.ifEmpty { "no location" }}", tag = "weather_now") {
            val cur = w?.optJSONObject("current")
            if (cur == null) { empty(s.weatherError ?: "Loading…"); return@card }
            val units = w.optJSONObject("current_units")
            addView(ui.text("%.0f%s".format(cur.optDouble("temperature_2m"), units?.optString("temperature_2m") ?: "°"), 40f))
            addView(ui.text(Fmt.weather(cur.optInt("weather_code")), 16f))
            addView(ui.row("Humidity", "%.0f %%".format(cur.optDouble("relative_humidity_2m"))))
            addView(ui.row("Wind", "%.0f %s".format(cur.optDouble("wind_speed_10m"), units?.optString("wind_speed_10m") ?: "")))
            addView(ui.row("Updated", if (s.weatherAt > 0) Fmt.ago(s.weatherAt) else "cached"))
            s.weatherError?.let { addView(ui.text(it, 11f, Pal.warn)) }
        })
        val daily = w?.optJSONObject("daily")
        if (daily != null) addView(ui.card("Forecast", tag = "weather_days") {
            val days = daily.optJSONArray("time"); val hi = daily.optJSONArray("temperature_2m_max")
            val lo = daily.optJSONArray("temperature_2m_min"); val code = daily.optJSONArray("weather_code")
            val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US); val outFmt = SimpleDateFormat("EEE d", Locale.getDefault())
            for (i in 0 until (days?.length() ?: 0)) {
                val label = try { outFmt.format(inFmt.parse(days!!.optString(i))!!) } catch (e: Exception) { days!!.optString(i) }
                addView(ui.row("$label  ${Fmt.weather(code?.optInt(i) ?: -1)}",
                    "%.0f° / %.0f°".format(hi?.optDouble(i) ?: Double.NaN, lo?.optDouble(i) ?: Double.NaN)))
            }
        })
        addView(ui.button("Refresh", primary = false) { app.repo.refreshInternet() })
    }
}
