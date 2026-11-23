package com.sidya.hubuddy.ui

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
import com.sidya.hubuddy.HubuddyApp
import com.sidya.hubuddy.R
import com.sidya.hubuddy.data.DashState
import com.sidya.hubuddy.ui.dock.DockActivity
import com.sidya.hubuddy.ui.screens.AiScreen
import com.sidya.hubuddy.ui.screens.ControlsScreen
import com.sidya.hubuddy.ui.screens.HomeScreen
import com.sidya.hubuddy.ui.screens.MonitorScreen
import com.sidya.hubuddy.ui.screens.Screen
import com.sidya.hubuddy.ui.screens.SettingsScreen

/**
 * Single activity with five tabs. Portrait: header + bottom bar. Landscape / wide screens: a
 * navigation rail on the left, so the whole height is for content. Screens are plain view
 * builders re-rendered from the latest [DashState]; polling runs only while this is resumed.
 */
class MainActivity : Activity() {
    val app by lazy { HubuddyApp.of(this) }
    lateinit var ui: Ui
        private set
    private lateinit var scroll: ScrollView
    private lateinit var navBar: LinearLayout
    private lateinit var statusChip: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var pageTitle: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val stack = ArrayList<Screen>()
    private var tab = 0
    private var resumed = false
    private var builtWithTheme = ""
    private var builtLandscape = false
    /** Last time the user touched the screen; dock mode starts after the configured idle time. */
    var lastTouchAt = System.currentTimeMillis()
    private val listener: (DashState) -> Unit = { onState(it) }

    /** Monitor tab selection: "all" or a PC id ("" = automatic). Kept while the activity lives. */
    var monitorSel = ""
    /** PC being edited in Settings ("" / null = the selected PC). */
    var editPcId: String? = null
    var pendingEditPc: String?
        get() = editPcId
        set(v) { editPcId = v }

    val tabNames = listOf("Home", "Monitor", "AI", "Controls", "Settings")
    private val tabIcons = listOf(R.drawable.ic_home, R.drawable.ic_monitor, R.drawable.ic_ai, R.drawable.ic_controls, R.drawable.ic_settings)

    private val ticker = object : Runnable {
        override fun run() {
            if (!resumed) return
            if (!app.auth.canAccessDashboard()) { toLogin("Session expired. Please log in again."); return }
            if (shouldStartDock(System.currentTimeMillis())) { openDock(); return }
            current()?.tick()
            if (System.currentTimeMillis() % 30_000 < 1000) updateKeepAwake()
            handler.postDelayed(this, 1000)
        }
    }

    /** Landscape or a wide window: rail navigation and multi-column content. */
    val isLandscape: Boolean get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** Width of the content area in dp (screen minus the rail in landscape). */
    val contentWidthDp: Int get() = resources.configuration.screenWidthDp - (if (isLandscape) RAIL_DP else 0)

    /** Number of card columns for the content width: 1 (phone portrait), 2, or 3 (large landscape). */
    val columnCount: Int get() = when {
        contentWidthDp >= 900 -> 3
        contentWidthDp >= 520 -> 2
        else -> 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyPalette()
        ui = Ui(this)
        if (!app.auth.canAccessDashboard()) { toLogin(null); return }
        tab = savedInstanceState?.getInt("tab") ?: app.settings.lastTab
        buildChrome()
        openTab(tab)
    }

    private fun applyPalette() {
        builtWithTheme = Pal.apply(app.settings.theme, this)
        Pal.styleWindow(this)
    }

    // ---- frame ---------------------------------------------------------------
    private fun buildChrome() {
        builtLandscape = isLandscape
        val land = builtLandscape
        scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Pal.bg); tag = "content" }
        pageTitle = ui.text("", 18f, Pal.text, bold = true, tag = "pageTitle")
        statusDot = ui.dot(Pal.dim)
        statusText = ui.text("…", 12f, Pal.dim, tag = "statusText")
        statusChip = ui.hbox {
            tag = "statusChip"
            background = ui.rounded(Pal.surface, 14)
            setPadding(ui.dp(10), ui.dp(6), ui.dp(12), ui.dp(6))
            isClickable = true
            addView(statusDot); addView(statusText)
            setOnClickListener { showPcPicker() }
        }
        navBar = ui.vbox()
        val root: View = if (land) landscapeFrame() else portraitFrame()
        root.setBackgroundColor(Pal.bg)
        setContentView(root)
        rebuildNavItems()
    }

    private fun portraitFrame(): View = ui.vbox {
        addView(ui.hbox {
            setPadding(ui.dp(16), ui.dp(10), ui.dp(12), ui.dp(8))
            addView(ui.vbox {
                addView(ui.text("Hubuddy", 16f, Pal.text, bold = true))
                addView(ui.text("SIDYA EDITION", 9f, Pal.walnut, bold = true).apply { letterSpacing = 0.2f })
            }, ui.lp(0, -2, 1f))
            addView(dockButton(), ui.lp(-2, -2).apply { rightMargin = ui.dp(8) })
            addView(statusChip)
        })
        addView(scroll, ui.lp(-1, 0, 1f))
        navBar.orientation = LinearLayout.HORIZONTAL
        navBar.setBackgroundColor(Pal.surface)
        navBar.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
        addView(navBar, ui.lp(-1, ui.dp(60)))
    }

    private fun landscapeFrame(): View = ui.hbox {
        gravity = Gravity.TOP
        navBar.orientation = LinearLayout.VERTICAL
        navBar.background = null
        navBar.setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4))
        addView(ui.vbox {
            tag = "rail"
            setBackgroundColor(Pal.surface)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, ui.dp(10), 0, ui.dp(8))
            addView(ui.text("Hubuddy", 12f, Pal.text, bold = true).apply { gravity = Gravity.CENTER })
            addView(ui.text("SIDYA", 8f, Pal.walnut, bold = true).apply { gravity = Gravity.CENTER; letterSpacing = 0.2f }, ui.lp(bottom = 8))
            addView(navBar, ui.lp(-1, 0, 1f))
            addView(dockButton(), ui.lp(-2, -2).apply { bottomMargin = ui.dp(4) })
        }, LinearLayout.LayoutParams(ui.dp(RAIL_DP), -1))
        addView(ui.vbox {
            addView(ui.hbox {
                setPadding(ui.dp(16), ui.dp(8), ui.dp(12), ui.dp(4))
                addView(pageTitle, ui.lp(0, -2, 1f))
                addView(statusChip)
            })
            addView(scroll, ui.lp(-1, 0, 1f))
        }, LinearLayout.LayoutParams(0, -1, 1f))
    }

    /** Starts dock mode. Icon-only in the narrow portrait header; icon and label in the landscape rail. */
    private fun dockButton(): View = ui.hbox {
        tag = "dockButton"
        gravity = Gravity.CENTER
        background = ui.rounded(Pal.surface2, 14)
        val land = isLandscape
        setPadding(ui.dp(if (land) 10 else 9), ui.dp(6), ui.dp(if (land) 12 else 9), ui.dp(6))
        isClickable = true
        contentDescription = "Start dock mode"
        addView(ui.icon(R.drawable.ic_dock, Pal.accent, if (land) 16 else 20), ui.lp(ui.dp(if (land) 16 else 20), ui.dp(if (land) 16 else 20)).apply { if (land) rightMargin = ui.dp(6) })
        if (land) addView(ui.text("Dock", 12f, Pal.accent, bold = true))
        setOnClickListener { openDock() }
    }

    private fun rebuildNavItems() {
        navBar.removeAllViews()
        val land = builtLandscape
        tabNames.forEachIndexed { i, name ->
            val sel = i == tab
            val tint = if (sel) Pal.accent else Pal.dim
            val item = ui.vbox {
                tag = "nav_$name"
                gravity = Gravity.CENTER
                isClickable = true
                contentDescription = name
                background = if (sel) ui.rounded(Pal.surface2, 12) else null
                setOnClickListener { openTab(i) }
                addView(ui.icon(tabIcons[i], tint, 22))
                addView(ui.text(name, 10.5f, tint, bold = sel).apply { gravity = Gravity.CENTER; tag = "navLabel_$name" })
            }
            navBar.addView(item, if (land) ui.lp(-1, 0, 1f).apply { bottomMargin = ui.dp(4) } else ui.lp(0, -1, 1f).apply { leftMargin = ui.dp(2); rightMargin = ui.dp(2) })
        }
    }

    // ---- navigation -------------------------------------------------------------
    fun openTab(i: Int) {
        tab = i.coerceIn(0, 4)
        app.settings.lastTab = tab
        stack.forEach { it.onLeave() }
        stack.clear()
        stack.add(when (tab) {
            0 -> HomeScreen(this)
            1 -> MonitorScreen(this)
            2 -> AiScreen(this)
            3 -> ControlsScreen(this)
            else -> SettingsScreen(this)
        })
        rebuildNavItems()
        render(resetScroll = true)
    }

    /** Open a secondary page (calendar, weather, ...) on top of the current tab. */
    fun push(s: Screen) { stack.add(s); render(resetScroll = true) }

    fun current(): Screen? = stack.lastOrNull()

    private fun render(resetScroll: Boolean = false) {
        val s = current() ?: return
        val y = scroll.scrollY
        scroll.removeAllViews()
        pageTitle.text = if (stack.size > 1) s.title ?: tabNames[tab] else tabNames[tab]
        val pad = if (isLandscape) 10 else 12
        val page = ui.vbox(pad)
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
        val name = s.activePc?.pc?.name
        val many = s.pcs.size > 1
        val (color, label) = when {
            !s.paired -> Pal.warn to "Not paired"
            s.online -> Pal.ok to (if (many) "${s.onlineCount}/${s.pcs.size} PCs online" else (if (s.latencyMs >= 0) "PC online · ${s.latencyMs} ms" else "PC online"))
            s.connecting && s.failures == 0 -> Pal.dim to "Connecting…"
            else -> Pal.bad to (if (many) "${s.onlineCount}/${s.pcs.size} PCs online" else "PC offline")
        }
        statusText.text = label
        statusText.setTextColor(color)
        statusDot.background = ui.dot(color).background
        statusChip.contentDescription = "${name ?: "PC"}: $label"
        val cur = current() ?: return
        if (cur.live) render() else cur.onState(s)
    }

    fun rerender() = render()

    /** Pick which PC the Home, AI and Controls tabs show. */
    fun showPcPicker() {
        val pcs = app.repo.state.pcs
        if (pcs.size < 2) { openTab(4); return }
        val labels = pcs.map { p ->
            val state = when {
                !p.paired -> "not paired"
                p.online -> "online"
                p.connecting -> "connecting"
                else -> "offline"
            }
            "${if (p.pc.id == app.repo.state.activeId) "● " else "   "}${p.pc.name} - $state"
        }.toTypedArray()
        dialog().setTitle("Show which PC?").setItems(labels) { _, i -> app.repo.selectPc(pcs[i].pc.id); render() }
            .setNegativeButton("Cancel", null).show()
    }

    fun dialog() = AlertDialog.Builder(this, if (Pal.isLight) R.style.Theme_Hubuddy_DialogLight else R.style.Theme_Hubuddy_Dialog)

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** Confirmation for actions that run something on the PC. */
    fun confirm(title: String, message: String, yes: String = "Run", action: () -> Unit) {
        dialog().setTitle(title).setMessage(message)
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
        applyPalette()
        buildChrome()
        rebuildNavItems()
        render(resetScroll = false)
        onState(app.repo.state)
    }

    // ---- lifecycle ----------------------------------------------------------------
    val pairing by lazy { PairingFlows(this) }
    val transfers by lazy { Transfers(this) }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        pairing.onResult(requestCode, resultCode, data)
        transfers.onResult(requestCode, resultCode, data)
    }

    override fun onResume() {
        super.onResume()
        if (!app.auth.canAccessDashboard()) { toLogin(null); return }
        resumed = true
        // Dock mode may have switched the palette (night = black): rebuild if it no longer matches.
        if (Pal.resolve(app.settings.theme, this) != builtWithTheme || Pal.current != builtWithTheme) applyTheme() else Pal.styleWindow(this)
        lastTouchAt = System.currentTimeMillis()
        updateKeepAwake()
        app.repo.addListener(listener)
        app.repo.start()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        resumed = false
        stack.forEach { it.onLeave() }   // e.g. the touchpad lets go of held mouse buttons
        handler.removeCallbacks(ticker)
        if (::ui.isInitialized) {
            app.repo.removeListener(listener)
            app.repo.stop() // no background polling: saves battery
        }
        super.onPause()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastTouchAt = System.currentTimeMillis()
        app.auth.touch()
    }

    /** Idle long enough, and the screen may stay on (charging, or allowed on battery with enough charge). */
    fun shouldStartDock(now: Long): Boolean {
        val idleMin = app.settings.dockIdleMin
        if (idleMin <= 0) return false
        if (!DockActivity.mayStayOn(this, app.settings)) return false
        return now - lastTouchAt >= idleMin * 60_000L
    }

    fun openDock() {
        lastTouchAt = System.currentTimeMillis()
        startActivity(Intent(this, DockActivity::class.java))
    }

    /** Keep the screen awake when asked to, or so the dock-mode idle timer can run out. */
    private fun updateKeepAwake() {
        val s = app.settings
        val dockWaiting = s.dockIdleMin > 0 && DockActivity.mayStayOn(this, s)
        if (s.keepScreenOn || dockWaiting) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Deprecated("Activity API on Android 9")
    override fun onBackPressed() {
        when {
            stack.size > 1 -> { stack.removeAt(stack.size - 1).onLeave(); render(resetScroll = true) }
            tab != 0 -> openTab(0)
            else -> super.onBackPressed()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::scroll.isInitialized) return
        // Rotation (or a system dark-mode change): rebuild the frame for the new shape and palette.
        applyPalette()
        buildChrome()
        render(resetScroll = false)
        onState(app.repo.state)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    companion object {
        const val RAIL_DP = 84
    }
}
