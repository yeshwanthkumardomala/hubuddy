package com.sidya.hubuddy.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.sidya.hubuddy.data.DashState
import com.sidya.hubuddy.net.ApiResult
import com.sidya.hubuddy.net.describe
import com.sidya.hubuddy.ui.Fmt
import com.sidya.hubuddy.ui.MainActivity
import com.sidya.hubuddy.ui.Pal
import org.json.JSONObject

/** Claude Code usage and session state, from Hubuddy's own agent data sources. */
class AiScreen(host: MainActivity) : Screen(host) {
    override fun render(s: DashState): View = ui.vbox {
        banner(s)?.let { addView(it) }
        val claude = section(s, "claude")
        val alert = section(s, "alert")
        val sessions = section(s, "sessions_info")
        if (claude == null && alert == null) {
            addView(ui.card("Claude Code", tag = "ai_empty") {
                empty("Claude Code is not connected.")
                addView(ui.text("Install Hubuddy on your PC and pair it in Settings. Limits and alerts need its Claude setup step (docs/hubuddy/API_SETUP.md).", 12f, Pal.dim))
            })
            return@vbox
        }
        val cards = ArrayList<View>()
        cards += ui.card("Session", tag = "ai_state") {
            addView(ui.row("State", stateLabel(claude, alert, sessions), stateColor(alert)))
            claude?.optLong("last_active")?.takeIf { it > 0 }?.let { addView(ui.row("Last activity", Fmt.ago(it * 1000))) }
            val list = sessions?.optJSONArray("sessions").objects()
            if (list.isNotEmpty()) addView(ui.row("Tracked sessions", list.size.toString()))
            list.take(4).forEach { addView(ui.row("  " + it.optString("project", "session").ifEmpty { "session" }, it.optString("state"))) }
        }
        if (alert != null && !alert.optBoolean("clear")) {
            cards += ui.card(if (alert.optString("kind") == "permission") "Waiting for permission" else "Waiting for input", tag = "ai_alert") {
                background = ui.rounded(Pal.surface, 12, Pal.warn)
                addView(ui.text(alert.optString("title"), 14f, bold = true))
                alert.optString("detail").takeIf { it.isNotEmpty() }?.let { addView(ui.text(it, 12f, Pal.dim)) }
                alert.optString("project").takeIf { it.isNotEmpty() }?.let { addView(ui.text("Project: $it", 12f, Pal.dim)) }
                addView(ui.button("Dismiss", primary = false, tag = "dismissAlert") { action(alert.optString("id"), "dismiss") }, ui.lp(top = 8))
            }
        }
        val limits = claude?.optJSONObject("limits")
        cards += ui.card("Usage limits", tag = "ai_limits") {
            val five = limits?.optJSONObject("five_hour"); val week = limits?.optJSONObject("seven_day")
            if (five == null && week == null) empty("Limits not available. They come from Claude Code's status line (install Hubuddy's statusline agent).")
            five?.let { addView(ui.meter("5-hour", it.num("pct"), "resets ${Fmt.until(it.optLong("reset"))}")) }
            week?.let { addView(ui.meter("Weekly", it.num("pct"), "resets ${Fmt.until(it.optLong("reset"))}")) }
            limits?.optLong("updated")?.takeIf { it > 0 }?.let { addView(ui.text("Updated ${Fmt.ago(it * 1000)}", 11f, Pal.dim)) }
        }
        if (claude != null) {
            cards += ui.card("Tokens", tag = "ai_tokens") {
                val today = claude.optJSONObject("today")
                addView(ui.row("Today", today?.let { Fmt.tokens(it.optLong("tokens")) } ?: "-"))
                addView(ui.row("Messages today", today?.optLong("messages")?.toString() ?: "-"))
                addView(ui.row("Last hour", Fmt.tokens(claude.optLong("last_hour"))))
                val block = claude.optJSONObject("block")
                if (block?.optBoolean("active") == true) {
                    addView(ui.row("Current 5-h block", Fmt.tokens(block.optLong("tokens"))))
                    addView(ui.row("Block resets", Fmt.until(block.optLong("reset"))))
                }
                claude.optJSONArray("models").objects().forEach { addView(ui.row("  " + it.optString("name"), Fmt.tokens(it.optLong("tokens")))) }
            }
            val days = claude.optJSONArray("days")
            if (days != null && days.length() > 0) cards += ui.card("Last 7 days") { addView(chart(days)) }
        }
        addView(columns(cards))
    }

    private fun chart(days: org.json.JSONArray): View {
        val vals = (0 until days.length()).map { days.optLong(it).coerceAtLeast(0) }
        val max = (vals.maxOrNull() ?: 0).coerceAtLeast(1)
        return ui.hbox {
            gravity = Gravity.BOTTOM
            vals.forEachIndexed { i, v ->
                addView(ui.vbox {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    addView(View(host).apply { background = ui.rounded(if (i == vals.lastIndex) Pal.accent else Pal.walnut, 3) },
                        LinearLayout.LayoutParams(ui.dp(14), (ui.dp(70) * v / max).toInt().coerceAtLeast(ui.dp(2))))
                    addView(ui.text(Fmt.tokens(v), 9f, Pal.dim).apply { gravity = Gravity.CENTER })
                }, ui.lp(0, ui.dp(90), 1f))
            }
        }
    }

    private fun action(id: String, action: String) {
        app.repo.command("/api/alerts/action", JSONObject().put("id", id).put("action", action)) { r ->
            host.toast(if (r is ApiResult.Ok) "Sent." else (r as ApiResult.Err).describe())
        }
    }

    companion object {
        fun stateLabel(claude: JSONObject?, alert: JSONObject?, sessions: JSONObject?): String {
            if (alert != null && !alert.optBoolean("clear") && alert.has("kind"))
                return if (alert.optString("kind") == "permission") "Waiting for permission" else "Waiting for input"
            val states = (0 until (sessions?.optJSONArray("sessions")?.length() ?: 0))
                .map { sessions!!.optJSONArray("sessions")!!.optJSONObject(it)?.optString("state") }
            if (states.any { it == "running" || it == "working" || it == "busy" }) return "Running"
            val last = claude?.optLong("last_active") ?: 0
            if (last <= 0) return "No recent activity"
            return if (System.currentTimeMillis() / 1000 - last < 120) "Running" else "Idle"
        }

        fun stateColor(alert: JSONObject?): Int =
            if (alert != null && !alert.optBoolean("clear") && alert.has("kind")) Pal.warn else Pal.text
    }
}
