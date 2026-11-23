package com.sidya.deskhub.ui.desk

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.sidya.deskhub.data.DashState
import com.sidya.deskhub.ui.Fmt
import com.sidya.deskhub.ui.Pal
import com.sidya.deskhub.ui.screens.AiScreen
import com.sidya.deskhub.ui.screens.CalendarScreen
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One full-screen page of desk mode. */
abstract class DeskPanel(val id: String, val title: String) {
    /** False when there is nothing worth showing, so the rotation skips it. */
    abstract fun available(s: DashState, d: DeskActivity): Boolean
    abstract fun build(s: DashState, d: DeskActivity): View
    open fun tick() {}

    protected fun JSONObject.num(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
    protected fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    /** A labelled value block: small caps label over a big number. */
    protected fun stat(d: DeskActivity, label: String, value: String, color: Int = Pal.text, size: Float = 26f): View = d.ui.vbox {
        addView(d.ui.text(label.uppercase(), 11f, Pal.dim, bold = true).apply { letterSpacing = 0.08f })
        addView(d.ui.mono(value, size, color))
        setPadding(0, 0, 0, d.ui.dp(10))
    }

    protected fun panelCard(d: DeskActivity, block: LinearLayout.() -> Unit): LinearLayout = d.ui.vbox(14) {
        background = d.ui.rounded(Pal.surface, 16, Pal.walnutDim)
        block()
    }

    companion object {
        fun all(): List<DeskPanel> = listOf(ClockPanel(), FleetPanel(), PcPanel(), ClaudePanel(), MediaPanel(), CalendarPanel(),
            WeatherPanel(), NetworkPanel(), GitHubPanel())
    }
}

class ClockPanel : DeskPanel("clock", "Clock") {
    private var time: TextView? = null
    private var secs: TextView? = null
    private var date: TextView? = null
    private val tf = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val sf = SimpleDateFormat(":ss", Locale.getDefault())
    private val df = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())

    override fun available(s: DashState, d: DeskActivity) = true

    override fun tick() {
        val now = Date()
        time?.text = tf.format(now); secs?.text = sf.format(now); date?.text = df.format(now)
    }

    override fun build(s: DashState, d: DeskActivity): View = d.ui.vbox {
        gravity = Gravity.CENTER
        addView(d.ui.hbox {
            gravity = Gravity.CENTER
            time = d.ui.mono("", 104f).apply { tag = "desk_clock"; includeFontPadding = false }
            secs = d.ui.mono("", 34f, Pal.dim).apply { includeFontPadding = false }
            addView(time); addView(secs)
        })
        date = d.ui.text("", 20f, Pal.dim).apply { gravity = Gravity.CENTER }
        addView(date)
        tick()
        if (d.isNightMode) return@vbox // night: just the dim clock
        // A row of quiet chips summarising the other panels.
        val chips = ArrayList<Pair<String, Int>>()
        chips += (if (s.online) "PC online" else if (s.paired) "PC offline" else "Not paired") to (if (s.online) Pal.ok else Pal.dim)
        s.weather?.optJSONObject("current")?.let { chips += "%.0f° %s".format(it.optDouble("temperature_2m"), Fmt.weather(it.optInt("weather_code"))) to Pal.text }
        d.section(s, "calendar")?.optJSONArray("events").objects()
            .firstOrNull { it.optLong("end", it.optLong("start")) * 1000 >= System.currentTimeMillis() }
            ?.let { chips += ("Next: " + it.optString("title").take(28)) to Pal.text }
        val claude = d.section(s, "claude"); val alert = d.section(s, "alert")
        if (claude != null || alert != null) chips += ("Claude: " + AiScreen.stateLabel(claude, alert, d.section(s, "sessions_info"))) to AiScreen.stateColor(alert)
        addView(d.ui.hbox {
            gravity = Gravity.CENTER
            chips.forEach { (t, c) ->
                addView(d.ui.text(t, 13f, c).apply {
                    background = d.ui.rounded(Pal.surface, 14, Pal.walnutDim)
                    setPadding(d.ui.dp(12), d.ui.dp(6), d.ui.dp(12), d.ui.dp(6))
                }, d.ui.lp(-2, -2).apply { leftMargin = d.ui.dp(4); rightMargin = d.ui.dp(4) })
            }
        }, d.ui.lp(top = 18))
    }
}

class PcPanel : DeskPanel("pc", "PC") {
    override fun available(s: DashState, d: DeskActivity) = d.section(s, "stats") != null

    override fun build(s: DashState, d: DeskActivity): View {
        val st = d.section(s, "stats")!!
        val ui = d.ui
        val used = st.num("ram_used"); val total = st.num("ram_total")
        val ram = if (used != null && total != null && total > 0) used / total * 100 else null
        return ui.vbox {
            addView(ui.hbox {
                fun gauge(v: Double?, label: String, sub: String) =
                    addView(GaugeView(d, v, label, sub).apply { tag = "gauge_$label" }, ui.lp(0, ui.dp(150), 1f))
                gauge(st.num("cpu"), "CPU", st.optString("cpu_name").take(18))
                gauge(ram, "RAM", if (used != null && total != null) "%.1f / %.0f GB".format(used, total) else "")
                gauge(st.num("gpu"), "GPU", st.num("gpu_temp")?.let { "%.0f °C".format(it) } ?: "")
                gauge(st.num("disk"), "Disk", "")
            })
            addView(ui.hbox {
                gravity = Gravity.TOP
                addView(panelCard(d) {
                    addView(ui.text("CPU · last ${s.cpuHistory.size} samples", 11f, Pal.dim, bold = true))
                    addView(SparkView(d, s.cpuHistory, 100.0), ui.lp(-1, ui.dp(64), top = 6))
                }, ui.lp(0, -2, 2f).apply { rightMargin = ui.dp(10) })
                addView(panelCard(d) {
                    addView(ui.row("Down", Fmt.kbps(st.num("net_down"))))
                    addView(ui.row("Up", Fmt.kbps(st.num("net_up"))))
                    addView(ui.row("GPU temp", st.num("gpu_temp")?.let { "%.0f °C".format(it) } ?: "n/a"))
                    d.section(s, "pc")?.optString("hostname")?.takeIf { it.isNotEmpty() }?.let { addView(ui.row("Host", it)) }
                }, ui.lp(0, -2, 1f))
            }, ui.lp(top = 8))
        }
    }
}

class ClaudePanel : DeskPanel("claude", "Claude") {
    override fun available(s: DashState, d: DeskActivity) = d.section(s, "claude") != null || d.section(s, "alert") != null

    override fun build(s: DashState, d: DeskActivity): View {
        val ui = d.ui
        val claude = d.section(s, "claude"); val alert = d.section(s, "alert")
        val waiting = alert != null && !alert.optBoolean("clear") && alert.has("kind")
        val limits = claude?.optJSONObject("limits")
        return ui.hbox {
            gravity = Gravity.TOP
            addView(panelCard(d) {
                if (waiting) background = ui.rounded(Pal.surface, 16, Pal.warn)
                addView(ui.text("CLAUDE CODE", 11f, Pal.dim, bold = true))
                addView(ui.text(AiScreen.stateLabel(claude, alert, d.section(s, "sessions_info")), 24f, if (waiting) Pal.warn else Pal.text, bold = true, tag = "desk_claude_state"), ui.lp(top = 4, bottom = 6))
                if (waiting) {
                    addView(ui.text(alert!!.optString("title"), 14f))
                    alert.optString("detail").takeIf { it.isNotEmpty() }?.let { addView(ui.text(it, 13f, Pal.dim)) }
                    alert.optString("project").takeIf { it.isNotEmpty() }?.let { addView(ui.text("Project: $it", 13f, Pal.dim)) }
                    addView(ui.text("Tap the phone, then AI → Focus on PC", 12f, Pal.dim), ui.lp(top = 8))
                } else {
                    claude?.optLong("last_active")?.takeIf { it > 0 }?.let { addView(ui.text("Last activity ${Fmt.ago(it * 1000)}", 13f, Pal.dim)) }
                    claude?.optJSONArray("models").objects().take(3).forEach { addView(ui.row(it.optString("name"), Fmt.tokens(it.optLong("tokens")))) }
                }
            }, ui.lp(0, -1, 1.2f).apply { rightMargin = ui.dp(10) })
            addView(ui.vbox {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(ui.hbox {
                    val five = limits?.optJSONObject("five_hour"); val week = limits?.optJSONObject("seven_day")
                    addView(GaugeView(d, five?.num("pct"), "5-hour", five?.let { "resets " + Fmt.until(it.optLong("reset")) } ?: "no data"), ui.lp(0, ui.dp(150), 1f))
                    addView(GaugeView(d, week?.num("pct"), "Weekly", week?.let { "resets " + Fmt.until(it.optLong("reset")) } ?: "no data"), ui.lp(0, ui.dp(150), 1f))
                })
            }, ui.lp(0, -2, 1.3f))
            addView(panelCard(d) {
                val today = claude?.optJSONObject("today")
                addView(stat(d, "Tokens today", today?.let { Fmt.tokens(it.optLong("tokens")) } ?: "n/a", Pal.accent, 30f))
                addView(ui.row("Last hour", claude?.let { Fmt.tokens(it.optLong("last_hour")) } ?: "-"))
                addView(ui.row("Messages", today?.optLong("messages")?.toString() ?: "-"))
                val days = claude?.optJSONArray("days")
                if (days != null && days.length() > 0) {
                    val vals = (0 until days.length()).map { days.optLong(it) }
                    val f = SimpleDateFormat("EEE", Locale.getDefault())
                    val labels = vals.indices.map { f.format(Date(System.currentTimeMillis() - (vals.size - 1 - it) * 86_400_000L)).take(2) }
                    addView(BarsView(d, vals, labels), ui.lp(-1, ui.dp(70), top = 6))
                }
            }, ui.lp(0, -1, 1f).apply { leftMargin = ui.dp(10) })
        }
    }
}

class MediaPanel : DeskPanel("media", "Media") {
    override fun available(s: DashState, d: DeskActivity) = d.section(s, "media")?.optBoolean("active") == true

    override fun build(s: DashState, d: DeskActivity): View {
        val ui = d.ui
        val m = d.section(s, "media")!!
        return ui.vbox {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(24), 0, ui.dp(24), 0)
            addView(ui.text(if (m.optBoolean("playing")) "NOW PLAYING" else "PAUSED", 12f, if (m.optBoolean("playing")) Pal.accent else Pal.dim, bold = true).apply { letterSpacing = 0.15f })
            addView(ui.text(m.optString("title"), 36f, bold = true, tag = "desk_media_title").apply { maxLines = 2 }, ui.lp(top = 6))
            addView(ui.text(listOf(m.optString("artist"), m.optString("album")).filter { it.isNotEmpty() }.joinToString(" · "), 20f, Pal.dim).apply { maxLines = 1 })
            m.optString("source").takeIf { it.isNotEmpty() }?.let { addView(ui.text(it, 14f, Pal.walnut), ui.lp(top = 4)) }
            val pos = m.optLong("pos"); val dur = m.optLong("dur")
            if (dur > 0) {
                addView(ProgressBar(d, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = dur.toInt(); progress = pos.toInt().coerceIn(0, dur.toInt())
                    progressTintList = android.content.res.ColorStateList.valueOf(Pal.accent)
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Pal.surface2)
                }, ui.lp(-1, ui.dp(6), top = 24))
                addView(ui.hbox {
                    addView(ui.text(mmss(pos), 13f, Pal.dim), ui.lp(0, -2, 1f))
                    addView(ui.text(mmss(dur), 13f, Pal.dim))
                })
            }
        }
    }

    private fun mmss(sec: Long) = "%d:%02d".format(sec / 60, sec % 60)
}

class CalendarPanel : DeskPanel("calendar", "Calendar") {
    override fun available(s: DashState, d: DeskActivity) = d.section(s, "calendar") != null

    override fun build(s: DashState, d: DeskActivity): View {
        val ui = d.ui
        val cal = d.section(s, "calendar")!!
        val now = System.currentTimeMillis()
        val events = cal.optJSONArray("events").objects().filter { it.optLong("end", it.optLong("start")) * 1000 >= now }.take(5)
        if (events.isEmpty()) return ui.vbox {
            gravity = Gravity.CENTER
            addView(ui.text("No events", 30f, bold = true).apply { gravity = Gravity.CENTER })
            addView(ui.text("in the next ${cal.optInt("days", 7)} days", 16f, Pal.dim).apply { gravity = Gravity.CENTER })
        }
        val dayF = SimpleDateFormat("EEE d MMM", Locale.getDefault()); val timeF = SimpleDateFormat("HH:mm", Locale.getDefault())
        return ui.vbox {
            events.forEach { ev ->
                val start = Date(ev.optLong("start") * 1000)
                addView(ui.hbox {
                    background = ui.rounded(Pal.surface, 12, Pal.walnutDim)
                    setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                    addView(View(d).apply { background = ui.rounded(color(ev.optString("color")), 2) }, ui.lp(ui.dp(4), ui.dp(36)).apply { rightMargin = ui.dp(12) })
                    addView(ui.vbox {
                        addView(ui.mono(if (ev.optBoolean("all_day")) "All day" else timeF.format(start), 22f))
                        addView(ui.text(dayF.format(start), 11f, Pal.dim))
                    }, ui.lp(ui.dp(110), -2))
                    addView(ui.vbox {
                        addView(ui.text(ev.optString("title", "(untitled)"), 17f, bold = true).apply { maxLines = 1 })
                        addView(ui.text(listOf(ev.optString("loc"), ev.optString("cal")).filter { it.isNotEmpty() }.joinToString(" · "), 12f, Pal.dim).apply { maxLines = 1 })
                    }, ui.lp(0, -2, 1f))
                    addView(ui.text(if (start.time > now) Fmt.until(start.time / 1000) else "now", 13f, Pal.accent))
                }, ui.lp(bottom = 6))
            }
        }
    }

    private fun color(c: String) = try { Color.parseColor(c) } catch (e: Exception) { Pal.accent }

    @Suppress("unused") private fun whenText(ev: JSONObject) = CalendarScreen.whenText(ev)
}

class WeatherPanel : DeskPanel("weather", "Weather") {
    override fun available(s: DashState, d: DeskActivity) = s.weather?.optJSONObject("current") != null

    override fun build(s: DashState, d: DeskActivity): View {
        val ui = d.ui
        val w = s.weather!!; val cur = w.optJSONObject("current")!!
        val units = w.optJSONObject("current_units")
        val unit = units?.optString("temperature_2m") ?: "°"
        return ui.hbox {
            addView(ui.vbox {
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.text(d.app.settings.weatherName.ifEmpty { "Weather" }.uppercase(), 12f, Pal.dim, bold = true))
                addView(ui.mono("%.0f%s".format(cur.optDouble("temperature_2m"), unit), 84f).apply { tag = "desk_temp"; includeFontPadding = false })
                addView(ui.text(Fmt.weather(cur.optInt("weather_code")), 22f))
                addView(ui.text("Humidity %.0f %% · Wind %.0f %s".format(cur.optDouble("relative_humidity_2m"), cur.optDouble("wind_speed_10m"), units?.optString("wind_speed_10m") ?: ""), 13f, Pal.dim), ui.lp(top = 6))
                if (s.weatherError != null) addView(ui.text(s.weatherError, 12f, Pal.warn))
            }, ui.lp(0, -1, 1f))
            val daily = w.optJSONObject("daily")
            if (daily != null) addView(ui.hbox {
                val days = daily.optJSONArray("time"); val hi = daily.optJSONArray("temperature_2m_max")
                val lo = daily.optJSONArray("temperature_2m_min"); val code = daily.optJSONArray("weather_code")
                val inF = SimpleDateFormat("yyyy-MM-dd", Locale.US); val outF = SimpleDateFormat("EEE", Locale.getDefault())
                for (i in 0 until (days?.length() ?: 0)) {
                    addView(ui.vbox {
                        gravity = Gravity.CENTER_HORIZONTAL
                        background = ui.rounded(if (i == 0) Pal.surface2 else Pal.surface, 12, Pal.walnutDim)
                        setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12))
                        val label = try { outF.format(inF.parse(days!!.optString(i))!!) } catch (e: Exception) { days!!.optString(i) }
                        addView(ui.text(if (i == 0) "Today" else label, 13f, Pal.dim, bold = true).apply { gravity = Gravity.CENTER })
                        addView(ui.text(Fmt.weather(code?.optInt(i) ?: -1), 11f).apply { gravity = Gravity.CENTER; maxLines = 2 }, ui.lp(top = 8, bottom = 8))
                        addView(ui.mono("%.0f°".format(hi?.optDouble(i) ?: Double.NaN), 24f).apply { gravity = Gravity.CENTER })
                        addView(ui.mono("%.0f°".format(lo?.optDouble(i) ?: Double.NaN), 16f, Pal.dim).apply { gravity = Gravity.CENTER })
                    }, ui.lp(0, -2, 1f).apply { leftMargin = ui.dp(3); rightMargin = ui.dp(3) })
                }
            }, ui.lp(0, -2, 1.6f))
        }
    }
}

class NetworkPanel : DeskPanel("network", "Network") {
    override fun available(s: DashState, d: DeskActivity) = d.section(s, "network") != null

    override fun build(s: DashState, d: DeskActivity): View {
        val ui = d.ui
        val net = d.section(s, "network")!!
        val on = net.optBoolean("online")
        val hist = net.optJSONArray("hist")?.let { a -> (0 until a.length()).map { a.optDouble(it, -1.0) } } ?: emptyList()
        return ui.hbox {
            gravity = Gravity.TOP
            addView(ui.vbox {
                addView(stat(d, "Internet", if (on) "Online" else "Offline", if (on) Pal.ok else Pal.bad, 30f))
                addView(stat(d, "Latency", net.num("latency_ms")?.let { "%.0f ms".format(it) } ?: "-", Pal.accent, 30f))
                addView(ui.row("Jitter", net.num("jitter_ms")?.let { "%.1f ms".format(it) } ?: "-"))
                addView(ui.row("Packet loss", net.num("loss_pct")?.let { "%.0f %%".format(it) } ?: "-"))
                addView(ui.row("Phone ↔ PC", if (s.latencyMs >= 0) "${s.latencyMs} ms" else "-"))
            }, ui.lp(0, -2, 1f).apply { rightMargin = ui.dp(12) })
            addView(panelCard(d) {
                addView(ui.text("LATENCY · LAST ${hist.size} PROBES (gaps = lost)", 11f, Pal.dim, bold = true))
                addView(SparkView(d, hist), ui.lp(-1, ui.dp(110), top = 8))
                addView(ui.hbox {
                    addView(stat(d, "Today", Fmt.bytes(net.optLong("today_bytes", -1)), size = 18f), ui.lp(0, -2, 1f))
                    addView(stat(d, "This month", Fmt.bytes(net.optLong("month_bytes", -1)), size = 18f), ui.lp(0, -2, 1f))
                    val sp = net.optJSONObject("speed")
                    addView(stat(d, "Speed test", sp?.let { "%.0f↓ %.0f↑".format(it.optDouble("down_mbps"), it.optDouble("up_mbps")) } ?: "-", size = 18f), ui.lp(0, -2, 1f))
                }, ui.lp(top = 10))
            }, ui.lp(0, -2, 2f))
        }
    }
}

class GitHubPanel : DeskPanel("github", "GitHub") {
    override fun available(s: DashState, d: DeskActivity) = d.section(s, "github") != null || s.github != null

    override fun build(s: DashState, d: DeskActivity): View {
        val ui = d.ui
        val c = d.section(s, "github"); val repo = s.github
        return ui.vbox {
            if (c != null) {
                addView(ui.text("${c.optString("login")} · ${c.optInt("total")} contributions this year".uppercase(), 12f, Pal.dim, bold = true))
                val levels = c.optString("levels")
                if (levels.isNotEmpty()) addView(HeatmapView(d, levels, c.optInt("start_wday")).apply { tag = "desk_heatmap" }, ui.lp(-1, ui.dp(110), top = 8, bottom = 10))
                addView(ui.hbox {
                    addView(stat(d, "Today", c.optInt("today").toString(), Pal.accent), ui.lp(0, -2, 1f))
                    addView(stat(d, "This week", "${c.optInt("week")}", size = 26f), ui.lp(0, -2, 1f))
                    addView(stat(d, "Last week", "${c.optInt("last_week")}", Pal.dim), ui.lp(0, -2, 1f))
                    addView(stat(d, "Streak", "${c.optInt("streak")} d"), ui.lp(0, -2, 1f))
                    addView(stat(d, "Longest", "${c.optInt("longest")} d", Pal.dim), ui.lp(0, -2, 1f))
                })
            }
            if (repo != null) addView(panelCard(d) {
                addView(ui.hbox {
                    addView(ui.text(d.app.settings.githubRepo, 16f, bold = true), ui.lp(0, -2, 1f))
                    addView(ui.text("★ ${repo.optInt("stargazers_count")}   forks ${repo.optInt("forks_count")}   issues ${repo.optInt("open_issues_count")}", 14f, Pal.dim))
                })
                addView(ui.text("Last push " + repo.optString("pushed_at").replace('T', ' ').removeSuffix("Z"), 12f, Pal.dim))
            }, ui.lp(top = if (c != null) 4 else 0))
        }
    }
}

/** Every PC and laptop on one page (up to 8): a card each with CPU, RAM and GPU or disk. */
class FleetPanel : DeskPanel("fleet", "All PCs") {
    override fun available(s: DashState, d: DeskActivity) = s.pcs.size > 1

    override fun build(s: DashState, d: DeskActivity): View {
        val ui = d.ui
        val cols = if (s.pcs.size <= 3) s.pcs.size else 4
        val rows = s.pcs.chunked(cols)
        return ui.vbox {
            addView(ui.text("${s.onlineCount} of ${s.pcs.size} online".uppercase(), 11f, Pal.dim, bold = true), ui.lp(bottom = 6))
            rows.forEach { row ->
                addView(ui.hbox {
                    gravity = Gravity.TOP
                    row.forEach { p -> addView(card(p, d), ui.lp(0, -2, 1f).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) }) }
                    repeat(cols - row.size) { addView(View(d), ui.lp(0, 1, 1f)) }
                }, ui.lp(-1, 0, 1f).apply { height = -2 })
            }
        }
    }

    private fun card(p: com.sidya.deskhub.data.PcLive, d: DeskActivity): View {
        val ui = d.ui
        val show = p.online || d.app.settings.showCachedOffline
        val st = if (show) p.section("stats") else null
        val color = when { !p.paired -> Pal.warn; p.online -> Pal.ok; else -> Pal.bad }
        return ui.vbox(10) {
            tag = "desk_pc_${p.pc.id}"
            background = ui.rounded(Pal.surface, 14, Pal.walnutDim)
            addView(ui.hbox {
                addView(ui.dot(color))
                addView(ui.text(p.pc.name, 14f, bold = true).apply { maxLines = 1 }, ui.lp(0, -2, 1f))
            })
            addView(ui.text(when { !p.paired -> "Not paired"; p.online -> "Online"; p.snapshot != null && show -> "Offline · ${Fmt.ago(p.snapshotAt)}"; else -> "Offline" }, 11f, if (p.online) Pal.dim else color), ui.lp(bottom = 6))
            if (st == null) { addView(ui.text("No readings", 12f, Pal.dim)); return@vbox }
            fun num(k: String) = if (st.has(k) && !st.isNull(k)) st.optDouble(k).takeIf { !it.isNaN() } else null
            val used = num("ram_used"); val total = num("ram_total")
            addView(ui.hbox {
                alpha = if (p.online) 1f else 0.6f
                gravity = Gravity.TOP
                listOf("CPU" to num("cpu"), "RAM" to (if (used != null && total != null && total > 0) used / total * 100 else null),
                    (if (num("gpu") != null) "GPU" else "Disk") to (num("gpu") ?: num("disk"))).forEachIndexed { i, (l, v) ->
                    addView(ui.miniStat(l, v), ui.lp(0, -2, 1f).apply { if (i > 0) leftMargin = ui.dp(8) })
                }
            })
            p.section("alert")?.takeIf { !it.optBoolean("clear") && it.has("kind") }?.let { addView(ui.text("Claude waiting", 11f, Pal.warn, bold = true)) }
        }
    }
}
