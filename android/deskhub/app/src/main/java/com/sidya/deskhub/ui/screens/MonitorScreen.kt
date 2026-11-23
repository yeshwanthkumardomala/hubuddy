package com.sidya.deskhub.ui.screens

import android.view.View
import com.sidya.deskhub.data.DashState
import com.sidya.deskhub.ui.Fmt
import com.sidya.deskhub.ui.MainActivity
import com.sidya.deskhub.ui.Pal

class MonitorScreen(host: MainActivity) : Screen(host) {
    override fun render(s: DashState): View {
        val sel = host.monitorSel.ifEmpty { if (s.pcs.size > 1) "all" else s.activeId }
        if (s.pcs.size > 1 && sel == "all") return ui.vbox {
            addView(selector(s, sel))
            addView(FleetScreen(host).render(s))
        }
        return ui.vbox {
            if (s.pcs.size > 1) addView(selector(s, sel))
            addView(detail(s))
        }
    }

    /** Chips: All, then each PC; picking a PC also makes it the one Home, AI and Controls show. */
    private fun selector(s: DashState, sel: String): View = android.widget.HorizontalScrollView(host).apply {
        isHorizontalScrollBarEnabled = false
        tag = "pc_selector"
        addView(ui.hbox {
            addView(ui.chip("All PCs", sel == "all", "chip_all") { host.monitorSel = "all"; host.rerender() }, ui.lp(-2, -2).apply { rightMargin = ui.dp(6) })
            s.pcs.forEach { p ->
                addView(ui.chip(p.pc.name, sel == p.pc.id, "chip_${p.pc.id}") {
                    host.monitorSel = p.pc.id; app.repo.selectPc(p.pc.id); host.rerender()
                }, ui.lp(-2, -2).apply { rightMargin = ui.dp(6) })
            }
        })
        setPadding(0, 0, 0, ui.dp(10))
    }

    private fun detail(s: DashState): View = ui.vbox {
        addView(ui.hbox {
            addView(ui.button("Network", primary = false, tag = "open_network") { host.push(NetworkScreen(host)) }, ui.lp(0, -2, 1f).apply { rightMargin = ui.dp(6) })
            addView(ui.button("GitHub", primary = false, tag = "open_github") { host.push(GitHubScreen(host)) }, ui.lp(0, -2, 1f))
        }, ui.lp(bottom = 10))
        banner(s)?.let { addView(it) }
        val st = section(s, "stats")
        val pc = section(s, "pc")
        val cards = ArrayList<View>()
        cards += ui.card("PC status", tag = "pc_status") {
            addView(ui.row("Companion", if (s.online) "online" else "offline", if (s.online) Pal.ok else Pal.bad))
            pc?.optString("hostname")?.takeIf { it.isNotEmpty() }?.let { addView(ui.row("Host", it)) }
            if (pc?.optBoolean("asleep") == true) addView(ui.row("Display", "asleep", Pal.warn))
            addView(ui.row("Last update", Fmt.ago(s.snapshotAt)))
            st?.optString("cpu_name")?.takeIf { it.isNotEmpty() }?.let { addView(ui.row("CPU", it.take(32))) }
            st?.optString("gpu_name")?.takeIf { it.isNotEmpty() }?.let { addView(ui.row("GPU", it.take(32))) }
        }
        cards += ui.card("Usage", tag = "pc_usage") {
            if (st == null) { empty("No PC stats yet."); return@card }
            addView(ui.meter("CPU", st.num("cpu")))
            val used = st.num("ram_used"); val total = st.num("ram_total")
            addView(ui.meter("RAM", if (used != null && total != null && total > 0) used / total * 100 else null,
                if (used != null && total != null) "%.1f / %.0f GB".format(used, total) else null))
            addView(ui.meter("Disk", st.num("disk")))
            addView(ui.meter("GPU", st.num("gpu")))
            val vu = st.num("vram_used"); val vt = st.num("vram_total")
            if (vu != null && vt != null && vt > 0) addView(ui.meter("VRAM", vu / vt * 100, "%.1f / %.0f GB".format(vu, vt)))
        }
        cards += ui.card("Sensors & network", tag = "pc_sensors") {
            addView(ui.row("GPU temperature", st?.num("gpu_temp")?.let { "%.0f °C".format(it) } ?: "unavailable"))
            addView(ui.row("CPU temperature", st?.num("cpu_temp")?.let { "%.0f °C".format(it) } ?: "unavailable"))
            addView(ui.row("Download", Fmt.kbps(st?.num("net_down"))))
            addView(ui.row("Upload", Fmt.kbps(st?.num("net_up"))))
        }
        addView(columns(cards))
    }
}
