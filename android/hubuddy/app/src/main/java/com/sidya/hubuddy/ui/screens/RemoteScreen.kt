package com.sidya.hubuddy.ui.screens

import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import com.sidya.hubuddy.data.DashState
import com.sidya.hubuddy.net.ApiResult
import com.sidya.hubuddy.net.describe
import com.sidya.hubuddy.remote.InputSender
import com.sidya.hubuddy.remote.TouchpadGestures
import com.sidya.hubuddy.ui.MainActivity
import com.sidya.hubuddy.ui.Pal
import com.sidya.hubuddy.ui.TouchpadView
import org.json.JSONObject

/**
 * Touchpad, keyboard, shortcuts, volume and power for the selected PC. The PC must have switched "Remote control" on for this
 * phone; otherwise every action is refused there and this screen says how to turn it on.
 */
class RemoteScreen(host: MainActivity) : Screen(host) {
    override val live = false
    override val title: String get() = "Remote control"

    private var allowed: Boolean? = null
    private var note: String? = null
    private var dragging = false
    private val mods = linkedSetOf<String>()   // sticky Ctrl/Shift/Alt/Win for the next key
    private var sender: InputSender? = null
    private var pad: TouchpadView? = null
    private var speedLabel: TextView? = null

    init { check() }

    private fun pc() = app.pcs.activeId

    private fun check() {
        if (!app.agent.isPaired(pc())) { allowed = false; note = "Pair this PC first (Settings)."; return }
        app.repo.background({ app.agent.get("/api/remote", pc()) }) { r ->
            if (r is ApiResult.Ok) { allowed = r.json.optBoolean("allowed"); note = null } else { allowed = false; note = (r as ApiResult.Err).describe() }
            host.rerender()
        }
    }

    private fun sender(): InputSender = sender ?: run {
        val id = pc()
        InputSender(
            post = { body -> app.agent.post("/api/input", body, id) },
            onError = { err ->
                host.runOnUiThread {
                    if (err.code == 403) { allowed = false; dragging = false; host.rerender() } else host.toast(err.describe())
                }
            },
        ).also { sender = it }
    }

    override fun onLeave() {
        pad?.cancelTouches()
        sender?.close(); sender = null     // releases any held mouse button first
        dragging = false
    }

    private val speed get() = app.settings.remoteSpeed

    private val gestures by lazy {
        TouchpadGestures(
            move = { dx, dy -> sender().move(dx * speed, dy * speed) },
            scroll = { dx, dy -> sender().scroll(dx * SCROLL_GAIN, dy * SCROLL_GAIN) },
            zoom = { steps -> sender().zoom(steps) },
            click = { b -> sender().click(b) },
            dragging = { dragging },
        )
    }

    private fun key(k: String) { sender().key(k, mods.toList()); mods.clear(); host.rerender() }

    private fun post(path: String, body: JSONObject, ok: String? = null) {
        app.repo.background({ app.agent.post(path, body, pc()) }) { r ->
            if (r is ApiResult.Err) host.toast(if (r.code == 403) "Remote control is off for this phone." else r.describe()) else if (ok != null) host.toast(ok)
        }
    }

    private fun android.widget.LinearLayout.keyRow(vararg keys: Pair<String, String>) {
        addView(ui.hbox {
            keys.forEach { (label, k) -> addView(ui.button(label, primary = false, tag = "key_$k") { key(k) }, ui.lp(0, -2, 1f).apply { rightMargin = ui.dp(3) }) }
        })
    }

    override fun render(s: DashState): View = ui.vbox {
        addView(ui.text("Selected PC: ${app.pcs.active().name}", 12f, Pal.dim), ui.lp(bottom = 6))
        note?.let { addView(ui.text(it, 12f, Pal.warn), ui.lp(bottom = 6)) }
        if (allowed == false) {
            addView(ui.card("Remote control is off", tag = "remoteOff") {
                addView(ui.text("On the PC, open Hubuddy and switch “Remote control” on for this phone. It is off by default so a paired phone cannot take over your PC unless you allow it.", 13f))
                addView(ui.button("Check again", primary = false, tag = "remoteCheck") { check() }, ui.lp(top = 8))
            })
            return@vbox
        }
        val cards = ArrayList<View>()
        cards.add(ui.card("Touchpad", tag = "touchCard") {
            val v = TouchpadView(host, gestures) { sender?.release(); dragging = false }.also { pad = it; it.tag = "touchpad" }
            v.background = ui.rounded(Pal.surface2, 14, Pal.accent)
            addView(v, ui.lp(-1, if (host.contentWidthDp >= 600) ui.dp(260) else ui.dp(300)))
            addView(ui.text("Drag to move · tap to click · two fingers: slide to scroll, tap for right-click, pinch to zoom", 11f, Pal.dim), ui.lp(top = 6, bottom = 6))
            addView(ui.hbox {
                addView(ui.button("Left", primary = false, tag = "clickLeft") { if (dragging) release() else sender().click("left") }, ui.lp(0, -2, 1f).apply { rightMargin = ui.dp(4) })
                addView(ui.button(if (dragging) "Release" else "Drag", primary = dragging, tag = "dragToggle") {
                    dragging = !dragging; sender().button("left", dragging); host.rerender()
                }, ui.lp(0, -2, 1f).apply { rightMargin = ui.dp(4) })
                addView(ui.button("Right", primary = false, tag = "clickRight") { if (dragging) release(); sender().click("right") }, ui.lp(0, -2, 1f).apply { rightMargin = ui.dp(4) })
                addView(ui.button("Double", primary = false, tag = "clickDouble") { sender().click("left", true) }, ui.lp(0, -2, 1f))
            })
            val label = ui.text("Pointer speed ${"%.1f".format(speed)}×", 12f, Pal.dim, tag = "speedLabel").also { speedLabel = it }
            addView(label, ui.lp(top = 8))
            addView(SeekBar(host).apply {
                tag = "speedBar"; max = 25; progress = ((speed - 0.5f) * 10).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        app.settings.remoteSpeed = 0.5f + p / 10f; label.text = "Pointer speed ${"%.1f".format(speed)}×"
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }, ui.lp(top = 2))
        })
        cards.add(ui.card("Keyboard", tag = "keyCard") {
            val box = ui.input("Type text to send to the PC", "remoteText", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
            addView(box, ui.lp(bottom = 6))
            addView(ui.button("Type on PC", tag = "typeText") {
                val t = box.text.toString()
                if (t.isNotEmpty()) { sender().text(t); box.setText("") }
            }, ui.lp(bottom = 8))
            addView(ui.text("Modifiers for the next key:", 11f, Pal.dim))
            addView(ui.hbox {
                listOf("Ctrl" to "ctrl", "Shift" to "shift", "Alt" to "alt", "Win" to "win").forEach { (label, m) ->
                    addView(ui.chip(label, m in mods, "mod_$m") { if (!mods.add(m)) mods.remove(m); host.rerender() }, ui.lp(-2, -2).apply { rightMargin = ui.dp(6) })
                }
            }, ui.lp(top = 4, bottom = 8))
            keyRow("Enter" to "enter", "⌫" to "backspace", "Del" to "delete", "Esc" to "esc", "Tab" to "tab")
            keyRow("◀" to "left", "▲" to "up", "▼" to "down", "▶" to "right")
            keyRow("Home" to "home", "End" to "end", "PgUp" to "pageup", "PgDn" to "pagedown")
        })
        cards.add(ui.card("Shortcuts", tag = "shortcutCard") {
            fun combo(label: String, k: String, vararg m: String) = ui.button(label, primary = false, tag = "sc_$label") { sender().key(k, m.toList()) }
            val rows = listOf(
                listOf(combo("Copy", "c", "ctrl"), combo("Paste", "v", "ctrl"), combo("Cut", "x", "ctrl")),
                listOf(combo("Undo", "z", "ctrl"), combo("Select all", "a", "ctrl"), combo("Save", "s", "ctrl")),
                listOf(combo("Switch app", "tab", "alt"), combo("Close", "f4", "alt"), combo("Start", "win")),
                listOf(combo("Play/Pause", "playpause"), combo("Next", "next"), combo("Previous", "prev")),
            )
            rows.forEach { r -> addView(ui.hbox { r.forEach { b -> addView(b, ui.lp(0, -2, 1f).apply { rightMargin = ui.dp(3) }) } }) }
        })
        cards.add(ui.card("Volume", tag = "volCard") {
            addView(ui.hbox {
                listOf("Quieter" to "down", "Mute" to "mute", "Louder" to "up").forEach { (label, a) ->
                    addView(ui.button(label, primary = a == "mute", tag = "vol_$a") { post("/api/volume", JSONObject().put("action", a)) }, ui.lp(0, -2, 1f).apply { rightMargin = ui.dp(3) })
                }
            })
        })
        cards.add(ui.card("Power", tag = "powerCard") {
            addView(ui.text("Restart and shut down start a short countdown on the PC (15 seconds on Windows, 1 minute on Linux); “Cancel” stops it.", 11f, Pal.dim), ui.lp(bottom = 6))
            addView(ui.hbox {
                listOf(Triple("Sleep", "sleep", false), Triple("Restart", "restart", true), Triple("Shut down", "shutdown", true)).forEach { (label, a, countdown) ->
                    addView(ui.button(label, primary = false, tag = "power_$a") {
                        host.confirm("$label the PC?", "${app.pcs.active().name} will $label.", label) { post("/api/power", JSONObject().put("action", a), if (countdown) "Starting shortly. Use Cancel to stop it." else null) }
                    }, ui.lp(0, -2, 1f).apply { rightMargin = ui.dp(3) })
                }
            })
            addView(ui.button("Cancel restart or shutdown", primary = false, tag = "power_abort") { post("/api/power", JSONObject().put("action", "abort"), "Cancelled.") }, ui.lp(top = 6))
        })
        addView(columns(cards, minColumnDp = 330))
    }

    private fun release() { dragging = false; sender().button("left", false); host.rerender() }

    companion object { const val SCROLL_GAIN = 4f }
}
