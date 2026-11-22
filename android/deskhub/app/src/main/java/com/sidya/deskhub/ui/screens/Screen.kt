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

    /** Lay out cards in one column (portrait) or two (landscape). */
    fun columns(cards: List<View>): View {
        if (!host.isLandscape) return ui.vbox { cards.forEach { addView(it) } }
        return ui.hbox {
            gravity = android.view.Gravity.TOP
            val left = ui.vbox(); val right = ui.vbox()
            cards.forEachIndexed { i, c -> (if (i % 2 == 0) left else right).addView(c) }
            addView(left, ui.lp(0, -2, 1f).apply { rightMargin = ui.dp(5) })
            addView(right, ui.lp(0, -2, 1f).apply { leftMargin = ui.dp(5) })
        }
    }
}
