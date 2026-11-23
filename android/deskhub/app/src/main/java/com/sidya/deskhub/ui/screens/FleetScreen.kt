package com.sidya.deskhub.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.sidya.deskhub.core.PcRegistry
import com.sidya.deskhub.data.DashState
import com.sidya.deskhub.data.PcLive
import com.sidya.deskhub.ui.Fmt
import com.sidya.deskhub.ui.MainActivity
import com.sidya.deskhub.ui.Pal

/** Every PC and laptop side by side: one tile per machine with its live readings. */
class FleetScreen(host: MainActivity) : Screen(host) {
    override val title: String get() = "All PCs"

    override fun render(s: DashState): View = ui.vbox {
        addView(summary(s))
        addView(columns(s.pcs.map { tile(it, s) }, minColumnDp = 200))
        if (s.pcs.size < PcRegistry.MAX_PCS) addView(ui.card(tag = "fleet_add") {
            addView(ui.text(if (s.pcs.size < 2) "Add your other computers to see all of their readings here." else "You can connect up to ${PcRegistry.MAX_PCS} PCs and laptops.", 12f, Pal.dim))
            addView(ui.button("Add a PC", primary = false, tag = "fleet_add_button") {
                host.app.pcs.add()?.let { host.app.settings.lastTab = 4; host.pendingEditPc = it.id; host.openTab(4) }
            }, ui.lp(top = 8))
        })
    }

    private fun summary(s: DashState): View {
        val waiting = s.pcs.count { p -> p.section("alert")?.let { !it.optBoolean("clear") && it.has("kind") } == true }
        val unpaired = s.pcs.count { !it.paired }
        return ui.card(tag = "fleet_summary") {
            addView(ui.hbox {
                addView(ui.vbox {
                    addView(ui.text("${s.onlineCount} of ${s.pcs.size} online", 20f, if (s.onlineCount == s.pcs.size && s.pcs.isNotEmpty()) Pal.ok else Pal.text, bold = true))
                    val extra = listOfNotNull(
                        if (waiting > 0) "$waiting Claude waiting" else null,
                        if (unpaired > 0) "$unpaired not paired" else null,
                    ).joinToString(" · ")
                    if (extra.isNotEmpty()) addView(ui.text(extra, 12f, Pal.warn))
                }, ui.lp(0, -2, 1f))
                val cpus = s.pcs.mapNotNull { it.takeIf { p -> p.online }?.num("stats", "cpu") }
                if (cpus.isNotEmpty()) addView(ui.vbox {
                    gravity = Gravity.END
                    addView(ui.text("avg CPU", 11f, Pal.dim).apply { gravity = Gravity.END })
                    addView(ui.text("%.0f%%".format(cpus.average()), 20f, bold = true).apply { gravity = Gravity.END })
                })
            })
        }
    }

    private fun tile(p: PcLive, s: DashState): View {
        val showData = p.online || app.settings.showCachedOffline
        val stats = if (showData) p.section("stats") else null
        val color = when { !p.paired -> Pal.warn; p.online -> Pal.ok; else -> Pal.bad }
        val statusLine = when {
            !p.paired -> "Not paired"
            p.online -> "Online · ${p.latencyMs} ms"
            p.snapshot != null && showData -> "Offline · last seen ${Fmt.ago(p.snapshotAt)}"
            p.connecting -> "Connecting…"
            else -> "Offline"
        }
        return ui.card(tag = "pc_tile_${p.pc.id}", onClick = {
            host.monitorSel = p.pc.id
            app.repo.selectPc(p.pc.id)
            host.openTab(1)
        }) {
            if (p.pc.id == s.activeId) background = ui.rounded(Pal.surface, 12, Pal.accent)
            addView(ui.hbox {
                addView(ui.dot(color))
                addView(ui.text(p.pc.name, 16f, bold = true, tag = "pc_name_${p.pc.id}"), ui.lp(0, -2, 1f))
                addView(ui.text(if (p.pc.mode == "usb") "USB" else "Wi-Fi", 10f, Pal.dim))
            })
            addView(ui.text(statusLine, 12f, if (p.online) Pal.dim else color), ui.lp(bottom = 6))
            if (stats == null) {
                addView(ui.text(if (!p.paired) "Pair this PC in Settings to see its readings." else "No readings yet.", 12f, Pal.dim))
                return@card
            }
            fun num(k: String) = if (stats.has(k) && !stats.isNull(k)) stats.optDouble(k).takeIf { !it.isNaN() } else null
            val used = num("ram_used"); val total = num("ram_total")
            addView(ui.hbox {
                alpha = if (p.online) 1f else 0.6f
                gravity = Gravity.TOP
                val cells = listOf(
                    "CPU" to num("cpu"),
                    "RAM" to (if (used != null && total != null && total > 0) used / total * 100 else null),
                    (if (num("gpu") != null) "GPU" else "Disk") to (num("gpu") ?: num("disk")),
                )
                cells.forEachIndexed { i, (l, v) ->
                    addView(ui.miniStat(l, v, "pc_${p.pc.id}_$l"), ui.lp(0, -2, 1f).apply { if (i > 0) leftMargin = ui.dp(10) })
                }
            })
            val bits = listOfNotNull(
                num("gpu_temp")?.let { "%.0f °C".format(it) },
                num("net_down")?.let { "↓ ${Fmt.kbps(it)}" },
            ).joinToString("   ")
            if (bits.isNotEmpty()) addView(ui.text(bits, 11f, Pal.dim), ui.lp(top = 6))
            val claude = if (showData) p.section("claude") else null
            val alert = if (showData) p.section("alert") else null
            if (claude != null || alert != null) {
                addView(ui.text("Claude · " + AiScreen.stateLabel(claude, alert, p.section("sessions_info")), 12f, AiScreen.stateColor(alert)), ui.lp(top = 2))
            }
        }
    }

    companion object {
        /** One-line summary rows used by the Home card. */
        fun rows(host: MainActivity, ui: com.sidya.deskhub.ui.Ui, s: DashState, parent: LinearLayout) {
            s.pcs.forEach { p ->
                val cpu = if (p.online) p.num("stats", "cpu") else null
                val used = if (p.online) p.num("stats", "ram_used") else null
                val total = if (p.online) p.num("stats", "ram_total") else null
                val ram = if (used != null && total != null && total > 0) used / total * 100 else null
                val color = when { !p.paired -> Pal.warn; p.online -> Pal.ok; else -> Pal.bad }
                parent.addView(ui.hbox {
                    tag = "home_pc_${p.pc.id}"
                    setPadding(0, ui.dp(4), 0, ui.dp(4))
                    addView(ui.dot(color))
                    addView(ui.text(p.pc.name, 14f, Pal.text), ui.lp(0, -2, 1f))
                    addView(ui.text(when {
                        !p.paired -> "not paired"
                        !p.online -> "offline"
                        else -> listOfNotNull(cpu?.let { "CPU %.0f%%".format(it) }, ram?.let { "RAM %.0f%%".format(it) }).joinToString("  ").ifEmpty { "online" }
                    }, 13f, if (p.online) Pal.text else Pal.dim))
                })
            }
        }
    }
}
