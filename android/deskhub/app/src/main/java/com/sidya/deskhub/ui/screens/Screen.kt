package com.sidya.deskhub.ui.screens

import android.view.View
import android.widget.LinearLayout
import com.sidya.deskhub.data.DashState
import com.sidya.deskhub.ui.Fmt
import com.sidya.deskhub.ui.MainActivity
import com.sidya.deskhub.ui.Pal
import org.json.JSONArray
import org.json.JSONObject

abstract class Screen(val host: MainActivity) {
    val ui get() = host.ui
    val app get() = host.app

    /** Re-render automatically on every state update (false for pages with text inputs). */
    open val live: Boolean = true

    abstract fun render(s: DashState): View

    /** Called every second while visible. */
    open fun tick() {}

    /** For non-live screens: refresh status labels only. */
    open fun onState(s: DashState) {}

    /** The snapshot section to show, or null when there is no data or cached data is disabled. */
    fun section(s: DashState, name: String): JSONObject? {
        if (!s.online && !app.settings.showCachedOffline) return null
        return s.section(name)
    }

    /** Banner explaining where the data comes from when it is not live. */
    fun banner(s: DashState): View? {
        if (s.online) return null
        val text = when {
            !s.paired -> "PC companion not paired. Open Settings → Connection to pair."
            s.snapshot != null && app.settings.showCachedOffline ->
                "PC offline — showing last saved data from ${Fmt.ago(s.snapshotAt)}. ${s.error ?: ""}"
            s.connecting && s.failures == 0 -> "Connecting to the PC companion…"
            else -> "PC offline. ${s.error ?: ""}"
        }
        val wait = (s.nextRetryAt - System.currentTimeMillis()) / 1000
        val retry = if (wait > 0 && s.paired) " Retrying in ${wait}s." else ""
        return ui.card(tag = "banner") {
            background = ui.rounded(Pal.surface2, 10, if (s.paired) Pal.bad else Pal.warn)
            addView(ui.text(text.trim() + retry, 12f, Pal.text))
        }
    }

    fun LinearLayout.empty(msg: String) = addView(ui.text(msg, 13f, Pal.dim))

    fun JSONObject.num(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

    fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    /** Page title shown in the landscape header for secondary pages (null = the tab name). */
    open val title: String? get() = null

    /** Lay out cards in 1-3 columns by screen width, putting each card in the currently shortest column. */
    fun columns(cards: List<View>, minColumnDp: Int? = null): View {
        val n = if (minColumnDp == null) host.columnCount else ((host.contentWidthDp - 24) / minColumnDp).coerceIn(1, 4)
        if (n <= 1) return ui.vbox { cards.forEach { addView(it) } }
        val cols = List(n) { ui.vbox() }
        val weight = IntArray(n)
        cards.forEach { c ->
            val i = weight.indices.minByOrNull { weight[it] } ?: 0
            cols[i].addView(c)
            weight[i] += 2 + ((c as? android.view.ViewGroup)?.let { countViews(it) } ?: 1)
        }
        return ui.hbox {
            gravity = android.view.Gravity.TOP
            cols.forEachIndexed { i, col ->
                addView(col, ui.lp(0, -2, 1f).apply { leftMargin = if (i == 0) 0 else ui.dp(5); rightMargin = if (i == n - 1) 0 else ui.dp(5) })
            }
        }
    }

    private fun countViews(g: android.view.ViewGroup): Int {
        var c = 0
        for (i in 0 until g.childCount) { val v = g.getChildAt(i); c += if (v is android.view.ViewGroup) countViews(v) else 1 }
        return c
    }
}
