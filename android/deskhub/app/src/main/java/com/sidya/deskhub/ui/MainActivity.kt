package com.sidya.deskhub.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.sidya.deskhub.DeskHubApp
import com.sidya.deskhub.data.DashState
import com.sidya.deskhub.ui.screens.AiScreen
import com.sidya.deskhub.ui.screens.ControlsScreen
import com.sidya.deskhub.ui.screens.HomeScreen
import com.sidya.deskhub.ui.screens.MonitorScreen
import com.sidya.deskhub.ui.screens.Screen
import com.sidya.deskhub.ui.screens.SettingsScreen

/**
 * Single activity holding the five tabs. Screens are plain view builders, re-rendered
 * from the latest [DashState]; polling runs only while this activity is resumed.
 */
class MainActivity : Activity() {
    val app by lazy { DeskHubApp.of(this) }
    lateinit var ui: Ui
        private set
    private lateinit var scroll: ScrollView
    private lateinit var navBar: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val stack = ArrayList<Screen>()
    private var tab = 0
    private var resumed = false
    private val listener: (DashState) -> Unit = { onState(it) }

    val tabNames = listOf("Home", "Monitor", "AI", "Controls", "Settings")

    private val ticker = object : Runnable {
        override fun run() {
            if (!resumed) return
            if (!app.auth.canAccessDashboard()) { toLogin("Session expired. Please log in again."); return }
            current()?.tick()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pal.apply(app.settings.theme)
        ui = Ui(this)
        if (!app.auth.canAccessDashboard()) { toLogin(null); return }
        tab = savedInstanceState?.getInt("tab") ?: app.settings.lastTab
        buildChrome()
        openTab(tab)
    }

    private fun buildChrome() {
        val root = ui.vbox {
            setBackgroundColor(Pal.bg)
            // Header: brand + connection indicator.
            addView(ui.hbox {
                setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(8))
                addView(ui.vbox {
                    addView(ui.text("Hubuddy DeskHub", 16f, Pal.text, bold = true))
                    addView(ui.text("SIDYA EDITION", 9f, Pal.walnut, bold = true).apply { letterSpacing = 0.2f })
                }, ui.lp(0, -2, 1f))
                statusDot = ui.dot(Pal.dim)
                addView(statusDot)
                statusText = ui.text("…", 12f, Pal.dim, tag = "statusText")
                addView(statusText)
            })
            scroll = ScrollView(this@MainActivity).apply {
                isFillViewport = true
                setBackgroundColor(Pal.bg)
                tag = "content"
            }
            addView(FrameLayout(this@MainActivity).apply { addView(scroll) }, ui.lp(-1, 0, 1f))
            navBar = ui.hbox {
                setBackgroundColor(Pal.surface)
                setPadding(0, ui.dp(2), 0, ui.dp(2))
            }
            addView(navBar, ui.lp(-1, ui.dp(52)))
        }
        tabNames.forEachIndexed { i, name ->
            navBar.addView(ui.text(name, 12f, Pal.dim, tag = "nav_$name").apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { openTab(i) }
            }, ui.lp(0, -1, 1f))
        }
        setContentView(root)
    }

    fun openTab(i: Int) {
        tab = i.coerceIn(0, 4)
        app.settings.lastTab = tab
        stack.clear()
        stack.add(when (tab) {
            0 -> HomeScreen(this)
            1 -> MonitorScreen(this)
            2 -> AiScreen(this)
            3 -> ControlsScreen(this)
            else -> SettingsScreen(this)
        })
        for (c in 0 until navBar.childCount) {
            val v = navBar.getChildAt(c) as TextView
            val sel = c == tab
            v.setTextColor(if (sel) Pal.accent else Pal.dim)
            v.background = if (sel) ui.rounded(Pal.surface2, 10) else null
        }
        render(resetScroll = true)
    }

    /** Open a secondary page (calendar, weather, ...) on top of the current tab. */
    fun push(s: Screen) { stack.add(s); render(resetScroll = true) }

    fun current(): Screen? = stack.lastOrNull()

    private fun render(resetScroll: Boolean = false) {
        val s = current() ?: return
        val y = scroll.scrollY
        scroll.removeAllViews()
        val page = ui.vbox(12)
        if (stack.size > 1) page.addView(ui.text("‹  Back", 14f, Pal.accent, tag = "back").apply {
            setPadding(0, 0, 0, ui.dp(10)); isClickable = true; setOnClickListener { onBackPressed() }
        })
        try {
            page.addView(s.render(app.repo.state))
        } catch (e: Exception) {
            // A malformed payload must never crash the dashboard.
            page.addView(ui.card("Display error") { addView(ui.text("This page could not show the latest data (${e.javaClass.simpleName}).", 13f, Pal.dim)) })
        }
        scroll.addView(page)
        if (!resetScroll) scroll.post { scroll.scrollTo(0, y) } else scroll.scrollTo(0, 0)
    }

    private fun onState(s: DashState) {
        if (!::statusText.isInitialized) return
        val (color, label) = when {
            !s.paired -> Pal.warn to "Not paired"
            s.online -> Pal.ok to (if (s.latencyMs >= 0) "PC online · ${s.latencyMs} ms" else "PC online")
            s.connecting && s.failures == 0 -> Pal.dim to "Connecting…"
            else -> Pal.bad to "PC offline"
        }
        statusText.text = label
        statusText.setTextColor(color)
        statusDot.background = ui.dot(color).background
        val cur = current() ?: return
        if (cur.live) render() else cur.onState(s)
    }

    fun rerender() = render()

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** Confirmation for actions that run something on the PC. */
    fun confirm(title: String, message: String, yes: String = "Run", action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(yes) { _, _ -> action() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun logout() {
        app.auth.logout()
        toLogin("Logged out.")
    }

    private fun toLogin(msg: String?) {
        if (msg != null) toast(msg)
        startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    fun applyTheme() {
        Pal.apply(app.settings.theme)
        buildChrome()
        openTab(tab)
        onState(app.repo.state)
    }

    override fun onResume() {
        super.onResume()
        if (!app.auth.canAccessDashboard()) { toLogin(null); return }
        resumed = true
        if (app.settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app.repo.addListener(listener)
        app.repo.start()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(ticker)
        if (::ui.isInitialized) {
            app.repo.removeListener(listener)
            app.repo.stop() // no background polling: saves battery
        }
        super.onPause()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        app.auth.touch()
    }

    @Deprecated("Activity API on Android 9")
    override fun onBackPressed() {
        when {
            stack.size > 1 -> { stack.removeAt(stack.size - 1); render(resetScroll = true) }
            tab != 0 -> openTab(0)
            else -> super.onBackPressed()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::scroll.isInitialized) render()
    }

    val isLandscape: Boolean get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }
}
