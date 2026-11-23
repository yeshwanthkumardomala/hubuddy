package com.sidya.hubuddy.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.view.View
import com.sidya.hubuddy.data.DashState
import com.sidya.hubuddy.net.ApiResult
import com.sidya.hubuddy.net.describe
import com.sidya.hubuddy.ui.Fmt
import com.sidya.hubuddy.ui.MainActivity
import com.sidya.hubuddy.ui.Pal
import org.json.JSONObject

class NetworkScreen(host: MainActivity) : Screen(host) {
    override fun render(s: DashState): View = ui.vbox {
        addView(ui.card("This phone", tag = "net_phone") {
            addView(ui.row("Network", phoneNetwork(host)))
            addView(ui.row("PC address", (app.pcs.active().endpoint().ifEmpty { "not set" })))
            addView(ui.row("PC reachable", if (s.online) "yes" else "no", if (s.online) Pal.ok else Pal.bad))
            addView(ui.row("Round-trip latency", if (s.latencyMs >= 0) "${s.latencyMs} ms" else "-"))
            if (!s.online && s.error != null) addView(ui.text(s.error, 12f, Pal.dim))
        })
        val net = section(s, "network")
        addView(ui.card("PC internet", tag = "net_pc") {
            if (net == null) { empty("Internet stats come from the PC agent."); return@card }
            val on = net.optBoolean("online")
            addView(ui.row("Status", if (on) "online" else "offline", if (on) Pal.ok else Pal.bad))
            addView(ui.row("Latency", net.num("latency_ms")?.let { "%.0f ms".format(it) } ?: "-"))
            addView(ui.row("Jitter", net.num("jitter_ms")?.let { "%.1f ms".format(it) } ?: "-"))
            addView(ui.row("Packet loss", net.num("loss_pct")?.let { "%.0f %%".format(it) } ?: "-"))
            addView(ui.row("Data today", Fmt.bytes(net.optLong("today_bytes", -1))))
            addView(ui.row("Data this month", Fmt.bytes(net.optLong("month_bytes", -1))))
            val sp = net.optJSONObject("speed")
            if (sp != null) {
                addView(ui.row("Speed test ↓ / ↑", "%.1f / %.1f Mbps".format(sp.optDouble("down_mbps"), sp.optDouble("up_mbps"))))
                addView(ui.row("Tested", Fmt.ago(sp.optLong("at") * 1000)))
            }
            addView(ui.button(if (net.optBoolean("testing")) "Speed test running…" else "Run speed test on PC", primary = false) {
                host.confirm("Run speed test?", "The PC will download and upload test data (at most once every 2 minutes).", "Run") {
                    app.repo.command("/api/speedtest", JSONObject()) { r -> host.toast(if (r is ApiResult.Ok) "Speed test started." else (r as ApiResult.Err).describe()) }
                }
            }, ui.lp(top = 8))
        })
    }

    companion object {
        fun phoneNetwork(ctx: Context): String = try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT < 23) legacyNetwork(cm) else modernNetwork(cm)
        } catch (e: Exception) { "unknown" }

        @android.annotation.TargetApi(23)
        private fun modernNetwork(cm: ConnectivityManager): String {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            return when {
                caps == null -> "offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> "USB"
                else -> "connected"
            }
        }

        @Suppress("DEPRECATION")
        private fun legacyNetwork(cm: ConnectivityManager): String {
            val info = cm.activeNetworkInfo ?: return "offline"
            if (!info.isConnected) return "offline"
            return when (info.type) {
                ConnectivityManager.TYPE_WIFI -> "Wi-Fi"
                ConnectivityManager.TYPE_MOBILE -> "Mobile data"
                ConnectivityManager.TYPE_ETHERNET -> "Ethernet"
                else -> "connected"
            }
        }
    }
}
