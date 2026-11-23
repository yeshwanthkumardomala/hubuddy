package com.sidya.deskhub.ui.desk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.sidya.deskhub.DeskHubApp
import com.sidya.deskhub.data.DashState
import com.sidya.deskhub.ui.Fmt
import com.sidya.deskhub.ui.LoginActivity
import com.sidya.deskhub.ui.Pal
import com.sidya.deskhub.ui.Ui
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * DeskHub-style ambient display for a phone standing next to the PC: full-screen landscape,
 * a side rail of pages, and an automatic rotation through every page that has data.
 *
 * * Read-only: any touch leaves desk mode. If the app's login session expired meanwhile,
 *   the PIN is required again (MainActivity routes to the login screen).
 * * A new Claude Code "waiting for you" alert jumps straight to the Claude page.
 * * At night, or while the PC's display sleeps, only a dim clock is shown.
 * * The layout drifts a few pixels each minute to avoid screen burn-in.
 * * The screen is kept on only while charging, unless the user allows it on battery.
 */
class DeskActivity : Activity() {
    val app by lazy { DeskHubApp.of(this) }
    lateinit var ui: Ui
        private set
    private val handler = Handler(Looper.getMainLooper())
    private val panels = DeskPanel.all()
    private var order: List<DeskPanel> = emptyList()
    private var current: DeskPanel? = null
    private var shownAt = 0L
    private var lastShift = 0L
    private var lastAlertId: String? = null
    private var night = false
    private var resumed = false

    private lateinit var rail: LinearLayout
    private lateinit var body: FrameLayout
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var miniClock: TextView
    private lateinit var progress: View
    private lateinit var main: LinearLayout
    private lateinit var root: FrameLayout

    private val listener: (DashState) -> Unit = { onState(it) }
    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val ticker = object : Runnable {
        override fun run() {
            if (!resumed) return
            tick()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pal.apply(app.settings.theme)
        ui = Ui(this)
        if (!app.auth.canAccessDashboard()) {
            startActivity(Intent(this, LoginActivity::class.java)); finish(); return
        }
        buildChrome()
    }

    private fun buildChrome() {
        root = FrameLayout(this).apply { setBackgroundColor(Pal.bg) }
        val content = ui.hbox {
            gravity = Gravity.TOP
            rail = ui.vbox { setPadding(ui.dp(8), ui.dp(14), ui.dp(4), ui.dp(8)) }
            addView(rail, LinearLayout.LayoutParams(ui.dp(92), -1))
            main = ui.vbox {
                setPadding(ui.dp(10), ui.dp(10), ui.dp(18), ui.dp(8))
                addView(ui.hbox {
                    title = ui.text("", 13f, Pal.walnut, bold = true, tag = "desk_title").apply { letterSpacing = 0.15f }
                    addView(title, ui.lp(0, -2, 1f))
                    status = ui.text("", 12f, Pal.dim, tag = "desk_status")
                    addView(status, ui.lp(-2, -2).apply { rightMargin = ui.dp(12) })
                    miniClock = ui.mono("", 18f)
                    addView(miniClock)
                }, ui.lp(bottom = 8))
                body = FrameLayout(this@DeskActivity).apply { tag = "desk_body" }
                addView(body, ui.lp(-1, 0, 1f))
                progress = View(this@DeskActivity).apply { background = ui.rounded(Pal.accent, 1) }
                addView(FrameLayout(this@DeskActivity).apply {
                    background = ui.rounded(Pal.surface2, 1)
                    addView(progress, FrameLayout.LayoutParams(0, -1))
                }, ui.lp(-1, ui.dp(3), top = 8))
            }
            addView(main, LinearLayout.LayoutParams(0, -1, 1f))
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    // ---- lifecycle -------------------------------------------------------------
    override fun onResume() {
        super.onResume()
        resumed = true
        immersive()
        updateKeepAwake()
        app.repo.addListener(listener)
        app.repo.start()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(ticker)
        app.repo.removeListener(listener)
        app.repo.stop()
        super.onPause()
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    // ---- touch = leave ---------------------------------------------------------
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) exitDesk()
        return true
    }

    @Deprecated("Activity API on older Android")
    override fun onBackPressed() = exitDesk()

    fun exitDesk() {
        // Only refresh the session if it is still valid; otherwise MainActivity asks for the PIN.
        if (app.auth.canAccessDashboard()) app.auth.touch(force = true)
        finish()
    }

    // ---- state & rotation ------------------------------------------------------
    fun section(s: DashState, name: String): JSONObject? {
        if (!s.online && !app.settings.showCachedOffline) return null
        return s.section(name)
    }

    private fun onState(s: DashState) {
        // New "waiting for you" alert: show it right away.
        val alert = section(s, "alert")
        val id = alert?.takeIf { !it.optBoolean("clear") && it.has("kind") }?.optString("id")
        if (id != null && id != lastAlertId && !night) {
            lastAlertId = id
            show(panels.first { it.id == "claude" }, s)
            return
        }
        if (id == null) lastAlertId = null
        val cur = current
        if (cur == null || night) { tick(); return }
        if (!cur.available(s, this)) next(s) else render(cur, s, fade = false)
    }

    private fun tick() {
        val s = app.repo.state
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val pcAsleep = s.online && section(s, "pc")?.optBoolean("asleep") == true
        val wantNight = app.settings.isNight(cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)) || pcAsleep
        if (wantNight != night) { night = wantNight; applyNight(s); return }
        if (night) { current?.tick(); burnInShift(now); return }
        if (current == null) { next(s); return }
        current?.tick()
        miniClock.text = clockFmt.format(Date(now))
        updateStatus(s)
        val dur = app.settings.deskPanelSec * 1000L
        val frac = ((now - shownAt).toFloat() / dur).coerceIn(0f, 1f)
        (progress.parent as View).width.takeIf { it > 0 }?.let { w ->
            progress.layoutParams = progress.layoutParams.apply { width = (w * frac).toInt() }
            progress.requestLayout()
        }
        if (now - shownAt >= dur) next(s)
        burnInShift(now)
        if (now % 30_000 < 1000) updateKeepAwake()
    }

    /** The pages to rotate through: the user's choice, minus pages without data. */
    fun rotation(s: DashState): List<DeskPanel> {
        val wanted = app.settings.deskPanels
        return wanted.mapNotNull { id -> panels.firstOrNull { it.id == id } }.filter { it.available(s, this) }
            .ifEmpty { listOf(panels.first()) }
    }

    fun next(s: DashState = app.repo.state) {
        order = rotation(s)
        val i = order.indexOf(current)
        show(order[(i + 1) % order.size], s)
    }

    private fun show(p: DeskPanel, s: DashState) {
        current = p
        shownAt = System.currentTimeMillis()
        order = rotation(s)
        render(p, s, fade = true)
    }

    private fun render(p: DeskPanel, s: DashState, fade: Boolean) {
        title.text = p.title.uppercase()
        val v = try { p.build(s, this) } catch (e: Exception) {
            ui.text("This page could not show the latest data.", 16f, Pal.dim)
        }
        body.removeAllViews()
        body.addView(v, FrameLayout.LayoutParams(-1, -1))
        if (fade) { body.alpha = 0f; body.animate().alpha(1f).setDuration(450).start() }
        buildRail(s)
        updateStatus(s)
        miniClock.text = clockFmt.format(Date())
        miniClock.visibility = if (p.id == "clock") View.INVISIBLE else View.VISIBLE
    }

    private fun buildRail(s: DashState) {
        rail.removeAllViews()
        val alertOn = section(s, "alert")?.let { !it.optBoolean("clear") && it.has("kind") } == true
        app.settings.deskPanels.mapNotNull { id -> panels.firstOrNull { it.id == id } }.forEach { p ->
            val active = p == current
            val has = p.available(s, this)
            rail.addView(ui.text(p.title, 12f, when { active -> Pal.accent; has -> Pal.text; else -> Pal.surface2 }, bold = active, tag = "rail_${p.id}").apply {
                setPadding(ui.dp(10), ui.dp(6), ui.dp(6), ui.dp(6))
                background = if (active) ui.rounded(Pal.surface, 10, Pal.walnutDim) else null
                if (p.id == "claude" && alertOn) setTextColor(Pal.warn)
            }, ui.lp(bottom = 2))
        }
    }

    private fun updateStatus(s: DashState) {
        status.text = when {
            !s.paired -> "Not paired"
            s.online -> "PC online · ${s.latencyMs} ms"
            s.snapshot != null && app.settings.showCachedOffline -> "PC offline · data from ${Fmt.ago(s.snapshotAt)}"
            else -> "PC offline"
        }
        status.setTextColor(if (s.online) Pal.ok else Pal.warn)
    }

    // ---- night, burn-in, keep-awake --------------------------------------------
    private fun applyNight(s: DashState) {
        val lp = window.attributes
        if (night) {
            rail.visibility = View.GONE
            title.visibility = View.INVISIBLE; status.visibility = View.INVISIBLE; miniClock.visibility = View.INVISIBLE
            (progress.parent as View).visibility = View.INVISIBLE
            val clock = panels.first { it.id == "clock" }
            current = clock
            body.removeAllViews()
            body.addView(clock.build(s, this), FrameLayout.LayoutParams(-1, -1))
            body.alpha = 0.45f
            body.tag = "desk_body_night"
            lp.screenBrightness = 0.02f
        } else {
            rail.visibility = View.VISIBLE
            title.visibility = View.VISIBLE; status.visibility = View.VISIBLE
            (progress.parent as View).visibility = View.VISIBLE
            body.alpha = 1f
            body.tag = "desk_body"
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            current = null
            next(s)
        }
        window.attributes = lp
    }

    val isNightMode: Boolean get() = night

    private fun burnInShift(now: Long) {
        if (now - lastShift < 60_000) return
        lastShift = now
        val r = ui.dp(4)
        main.translationX = ((Math.random() * 2 - 1) * r).toFloat()
        main.translationY = ((Math.random() * 2 - 1) * r).toFloat()
    }

    private fun updateKeepAwake() {
        if (isCharging(this) || app.settings.deskOnBattery) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        fun isCharging(ctx: Context): Boolean = try {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        } catch (e: Exception) { false }
    }
}
