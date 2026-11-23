package com.sidya.hubuddy.ui.screens

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import com.sidya.hubuddy.data.DashState
import com.sidya.hubuddy.net.ApiResult
import com.sidya.hubuddy.net.describe
import com.sidya.hubuddy.ui.MainActivity
import com.sidya.hubuddy.ui.Pal
import org.json.JSONArray
import org.json.JSONObject

/**
 * Touchpad, keyboard, volume and power for the selected PC. The PC must have switched "Remote control" on for this phone;
 * otherwise every action is refused there and this screen says how to turn it on.
 */
class RemoteScreen(host: MainActivity) : Screen(host) {
    override val live = false
    override val title: String get() = "Remote control"

    private var allowed: Boolean? = null
    private var note: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var dx = 0f
    private var dy = 0f
    private var scroll = 0f
    private val pending = JSONArray()
    private var flushing = false

    init { check() }

    private fun pc() = app.pcs.activeId

    private fun check() {
        if (!app.agent.isPaired(pc())) { allowed = false; note = "Pair this PC first (Settings)."; return }
        app.repo.background({ app.agent.get("/api/remote", pc()) }) { r ->
            if (r is ApiResult.Ok) { allowed = r.json.optBoolean("allowed"); note = null } else { allowed = false; note = (r as ApiResult.Err).describe() }
            host.rerender()
        }
    }

    /** Sends [events] now (batched by the touchpad). Errors show once as a toast. */
    private fun send(events: JSONArray) {
        if (events.length() == 0) return
        val id = pc()
        app.repo.background({ app.agent.post("/api/input", JSONObject().put("events", events), id) }) { r ->
            if (r is ApiResult.Err) { if (r.code == 403) allowed = false; host.toast(r.describe().let { if (r.code == 403) "Remote control is off for this phone." else it }) }
        }
    }

    private fun one(vararg kv: Pair<String, Any>) = send(JSONArray().put(JSONObject().also { o -> kv.forEach { (k, v) -> o.put(k, v) } }))

    private fun post(path: String, body: JSONObject, ok: String? = null) {
        app.repo.background({ app.agent.post(path, body, pc()) }) { r ->
            if (r is ApiResult.Err) host.toast(if (r.code == 403) "Remote control is off for this phone." else r.describe()) else if (ok != null) host.toast(ok)
        }
    }

    private val flusher = object : Runnable {
        override fun run() {
            val ev = JSONArray()
            val mx = dx.toInt().coerceIn(-2048, 2048); val my = dy.toInt().coerceIn(-2048, 2048)
            if (mx != 0 || my != 0) { ev.put(JSONObject().put("t", "move").put("dx", mx).put("dy", my)); dx -= mx; dy -= my }
            val sc = scroll.toInt().coerceIn(-2048, 2048)
            if (sc != 0) { ev.put(JSONObject().put("t", "scroll").put("dy", sc)); scroll -= sc }
            if (ev.length() > 0) send(ev)
            if (flushing) handler.postDelayed(this, 60)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
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
        addView(ui.card("Touchpad", tag = "touchCard") {
            val pad = View(host).apply {
                tag = "touchpad"; background = ui.rounded(Pal.surface2, 12, Pal.accent)
                var downAt = 0L; var moved = 0f; var lastX = 0f; var lastY = 0f; var fingers = 0
                setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { downAt = System.currentTimeMillis(); moved = 0f; lastX = e.x; lastY = e.y; fingers = 1; flushing = true; handler.post(flusher) }
                        MotionEvent.ACTION_POINTER_DOWN -> fingers = 2
                        MotionEvent.ACTION_MOVE -> {
                            val ddx = e.x - lastX; val ddy = e.y - lastY; lastX = e.x; lastY = e.y; moved += Math.abs(ddx) + Math.abs(ddy)
                            if (e.pointerCount >= 2) scroll += ddy / 3f else { dx += ddx * 1.6f; dy += ddy * 1.6f }
                        }
                        MotionEvent.ACTION_UP -> {
                            if (moved < 12f && System.currentTimeMillis() - downAt < 250) one("t" to "click", "button" to (if (fingers >= 2) "right" else "left"))
                            flushing = false; handler.removeCallbacks(flusher); handler.post(flusher.also { }); v.performClick()
                        }
                        MotionEvent.ACTION_CANCEL -> { flushing = false; handler.removeCallbacks(flusher) }
                    }
                    true
                }
            }
            addView(pad, ui.lp(-1, ui.dp(200)))
            addView(ui.text("Drag to move, tap to click, two fingers to scroll or right-click.", 11f, Pal.dim), ui.lp(top = 6, bottom = 6))
            addView(ui.hbox {
                addView(ui.button("Left", primary = false, tag = "clickLeft") { one("t" to "click", "button" to "left") }, ui.lp(0, -2, 1f).apply { rightMargin = ui.dp(4) })
                addView(ui.button("Double", primary = false, tag = "clickDouble") { one("t" to "click", "button" to "left", "double" to true) }, ui.lp(0, -2, 1f).apply { rightMargin = ui.dp(4) })
                addView(ui.button("Right", primary = false, tag = "clickRight") { one("t" to "click", "button" to "right") }, ui.lp(0, -2, 1f))
            })
        })
        addView(ui.card("Keyboard", tag = "keyCard") {
            val box = ui.input("Type text to send to the PC", "remoteText", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
            addView(box, ui.lp(bottom = 6))
            addView(ui.button("Type on PC", tag = "typeText") {
                val t = box.text.toString()
                if (t.isNotEmpty()) { one("t" to "text", "text" to t.take(500)); box.setText("") }
            }, ui.lp(bottom = 8))
            listOf(listOf("Enter" to "enter", "Backspace" to "backspace", "Esc" to "esc", "Tab" to "tab"), listOf("◀" to "left", "▲" to "up", "▼" to "down", "▶" to "right")).forEach { row ->
                addView(ui.hbox {
                    row.forEach { (label, key) -> addView(ui.button(label, primary = false, tag = "key_$key") { one("t" to "key", "key" to key) }, ui.lp(0, -2, 1f).apply { rightMargin = ui.dp(3) }) }
                })
            }
        })
        addView(ui.card("Volume", tag = "volCard") {
            addView(ui.hbox {
                listOf("Quieter" to "down", "Mute" to "mute", "Louder" to "up").forEach { (label, a) ->
                    addView(ui.button(label, primary = a == "mute", tag = "vol_$a") { post("/api/volume", JSONObject().put("action", a)) }, ui.lp(0, -2, 1f).apply { rightMargin = ui.dp(3) })
                }
            })
        })
        addView(ui.card("Power", tag = "powerCard") {
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
    }
}
