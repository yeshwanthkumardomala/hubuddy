package com.sidya.deskhub.ui.screens

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import com.sidya.deskhub.core.LocalShortcut
import com.sidya.deskhub.data.DashState
import com.sidya.deskhub.net.ApiResult
import com.sidya.deskhub.net.describe
import com.sidya.deskhub.ui.MainActivity
import com.sidya.deskhub.ui.Pal
import org.json.JSONObject

/** Media controls, PC shortcut buttons (run on the PC, confirmed first) and phone shortcuts. */
class ControlsScreen(host: MainActivity) : Screen(host) {
    override fun render(s: DashState): View = ui.vbox {
        banner(s)?.let { addView(it) }
        val m = section(s, "media")
        addView(ui.card("Media", tag = "media") {
            if (m == null) { empty("Media info comes from the PC companion."); return@card }
            if (!m.optBoolean("active")) empty("Nothing playing on the PC.")
            else {
                addView(ui.text(m.optString("title"), 16f, bold = true))
                addView(ui.text(listOf(m.optString("artist"), m.optString("album")).filter { it.isNotEmpty() }.joinToString(" · "), 12f, Pal.dim))
                m.optString("source").takeIf { it.isNotEmpty() }?.let { addView(ui.text("Source: $it", 11f, Pal.dim)) }
            }
            val enabled = s.online && m.optBoolean("active")
            addView(ui.hbox {
                listOf("⏮" to "prev", if (m.optBoolean("playing")) "⏸" to "toggle" else "▶" to "toggle", "⏭" to "next").forEach { (label, cmd) ->
                    addView(ui.button(label, primary = cmd == "toggle", tag = "media_$cmd") { media(cmd) }.apply {
                        isEnabled = enabled; alpha = if (enabled) 1f else 0.4f
                    }, ui.lp(0, -2, 1f).apply { leftMargin = ui.dp(3); rightMargin = ui.dp(3) })
                }
            }, ui.lp(top = 8))
        })
        val sc = section(s, "shortcuts")
        addView(ui.card("PC shortcuts", tag = "pc_shortcuts") {
            val tiles = sc?.optJSONArray("tiles").objects()
            if (sc == null) { empty("Shortcuts are defined in the PC companion's config.toml ([[tiles]])."); return@card }
            if (tiles.isEmpty()) { empty("No shortcuts configured on the PC."); return@card }
            val on = sc.optJSONArray("on")?.let { a -> (0 until a.length()).map { a.optInt(it) }.toSet() } ?: emptySet()
            tiles.chunked(2).forEach { rowTiles ->
                addView(ui.hbox {
                    rowTiles.forEach { t ->
                        val id = t.optInt("id")
                        val label = t.optString("label")
                        addView(ui.button(label + if (id in on) " ●" else "", primary = false) {
                            host.confirm("Run on PC?", "“$label” will run on your PC.") { runTile(id) }
                        }.apply {
                            background = ui.rounded(tileColor(t.optString("color")), 10)
                            isEnabled = s.online; alpha = if (s.online) 1f else 0.4f
                        }, ui.lp(0, -2, 1f).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
                    }
                    if (rowTiles.size == 1) addView(View(host), ui.lp(0, 1, 1f))
                })
            }
        })
        addView(ui.card("Phone shortcuts", tag = "local_shortcuts") {
            val list = LocalShortcut.parse(app.settings.localShortcuts)
            if (list.isEmpty()) empty("Add links or apps in Settings → Phone shortcuts.")
            list.forEach { sh ->
                addView(ui.button(sh.label, primary = false) { open(sh) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
            }
        })
    }

    private fun tileColor(c: String): Int = try { Color.parseColor(c) } catch (e: Exception) { Pal.surface2 }

    private fun media(cmd: String) {
        app.repo.command("/api/media", JSONObject().put("cmd", cmd)) { r -> if (r is ApiResult.Err) host.toast(r.describe()) }
    }

    private fun runTile(id: Int) {
        app.repo.command("/api/shortcuts/run", JSONObject().put("id", id)) { r ->
            host.toast(if (r is ApiResult.Ok) "Done." else (r as ApiResult.Err).describe())
        }
    }

    private fun open(sh: LocalShortcut) {
        try {
            val intent = if (sh.isApp) host.packageManager.getLaunchIntentForPackage(sh.target.removePrefix("app:").trim())
            else Intent(Intent.ACTION_VIEW, Uri.parse(sh.target))
            if (intent == null) host.toast("App not installed.") else host.startActivity(intent)
        } catch (e: Exception) {
            host.toast("Cannot open this shortcut.")
        }
    }
}
