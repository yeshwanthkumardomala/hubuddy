package com.sidya.deskhub.ui.screens

import android.content.Intent
import android.os.Build
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.sidya.deskhub.BuildConfig
import com.sidya.deskhub.data.DashState
import com.sidya.deskhub.data.DashboardRepository
import com.sidya.deskhub.net.AddressValidator
import com.sidya.deskhub.net.ApiResult
import com.sidya.deskhub.net.describe
import com.sidya.deskhub.ui.ChangePinActivity
import com.sidya.deskhub.ui.MainActivity
import com.sidya.deskhub.ui.Pal

class SettingsScreen(host: MainActivity) : Screen(host) {
    override val live = false
    private var status: TextView? = null

    override fun onState(s: DashState) {
        status?.text = statusText(s)
        status?.setTextColor(if (s.online) Pal.ok else if (s.paired) Pal.bad else Pal.warn)
    }

    private fun statusText(s: DashState) = when {
        !s.paired -> "Not paired"
        s.online -> "Connected · ${s.latencyMs} ms"
        else -> "Offline · ${s.error ?: ""}"
    }

    override fun render(s: DashState): View = ui.vbox {
        val cfg = app.settings
        // ---- Connection -------------------------------------------------------
        addView(ui.card("Connection") {
            status = ui.text(statusText(s), 13f, if (s.online) Pal.ok else Pal.warn, tag = "connStatus")
            addView(status, ui.lp(bottom = 8))
            val usb = RadioButton(host).apply { text = "USB (adb reverse → 127.0.0.1)"; setTextColor(Pal.text); id = View.generateViewId(); tag = "mode_usb" }
            val wifi = RadioButton(host).apply { text = "Local Wi-Fi"; setTextColor(Pal.text); id = View.generateViewId(); tag = "mode_wifi" }
            val group = RadioGroup(host).apply { addView(usb); addView(wifi); check(if (cfg.mode == "wifi") wifi.id else usb.id) }
            addView(group)
            val port = ui.input("USB port (8765)", "usbPort", InputType.TYPE_CLASS_NUMBER, cfg.usbPort.toString())
            val addr = ui.input("PC address, e.g. 192.168.1.100:8765", "wifiAddress", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, cfg.wifiAddress)
            addView(ui.text("USB port", 12f, Pal.dim)); addView(port, ui.lp(bottom = 6))
            addView(ui.text("Wi-Fi address (your PC's LAN IP)", 12f, Pal.dim)); addView(addr, ui.lp(bottom = 6))
            val msg = ui.text("", 12f, Pal.dim, tag = "connMessage")
            fun save(): Boolean {
                val p = port.text.toString().toIntOrNull()
                if (p == null || p !in 1..65535) { msg.setTextColor(Pal.bad); msg.text = "USB port must be 1-65535."; return false }
                val mode = if (group.checkedRadioButtonId == wifi.id) "wifi" else "usb"
                if (mode == "wifi") {
                    val v = AddressValidator.validate(addr.text.toString())
                    if (v is AddressValidator.Result.Error) { msg.setTextColor(Pal.bad); msg.text = v.message; return false }
                }
                cfg.usbPort = p; cfg.wifiAddress = addr.text.toString(); cfg.mode = mode
                return true
            }
            addView(ui.hbox {
                addView(ui.button("Save", tag = "saveConn") {
                    if (save()) { msg.setTextColor(Pal.ok); msg.text = "Saved: ${cfg.companionAddress()}"; app.repo.refreshNow() }
                }, ui.lp(0, -2, 1f).apply { rightMargin = ui.dp(6) })
                addView(ui.button("Test connection", primary = false, tag = "testConn") {
                    if (!save()) return@button
                    msg.setTextColor(Pal.dim); msg.text = "Testing ${cfg.companionAddress()}…"
                    app.repo.background({ app.companion.ping() }) { r ->
                        if (r is ApiResult.Ok) {
                            msg.setTextColor(Pal.ok)
                            msg.text = "Reachable in ${r.latencyMs} ms · ${r.json.optString("service", "companion")} ${r.json.optString("version")}" +
                                if (!app.companion.paired) " · now pair below" else ""
                        } else { msg.setTextColor(Pal.bad); msg.text = (r as ApiResult.Err).describe() }
                    }
                }, ui.lp(0, -2, 1f))
            })
            addView(msg, ui.lp(top = 6))
        })

        // ---- Pairing ---------------------------------------------------------
        addView(ui.card("Pairing") {
            val paired = app.companion.paired
            addView(ui.text(
                if (paired) "Paired. The device token is stored encrypted with the Android Keystore."
                else "Start the bridge on the PC; it prints a 6-digit pairing code. Enter it here.", 12f, Pal.dim), ui.lp(bottom = 6))
            if (!app.secureStorageAvailable) addView(ui.text("Android Keystore unavailable: the token is kept in memory only and must be re-paired after restart.", 12f, Pal.warn))
            val code = ui.input("Pairing code", "pairCode", InputType.TYPE_CLASS_NUMBER)
            val pmsg = ui.text("", 12f, Pal.dim, tag = "pairMessage")
            if (!paired) addView(code, ui.lp(bottom = 6))
            addView(ui.hbox {
                if (!paired) addView(ui.button("Pair", tag = "pairButton") {
                    pmsg.setTextColor(Pal.dim); pmsg.text = "Pairing…"
                    val c = code.text.toString()
                    app.repo.background({ app.companion.pair(c, "${Build.MANUFACTURER} ${Build.MODEL}") }) { r ->
                        if (r is ApiResult.Ok) { host.toast("Paired."); app.repo.refreshNow(); host.rerender() }
                        else { pmsg.setTextColor(Pal.bad); pmsg.text = (r as ApiResult.Err).describe().let { d -> if (r.kind.name == "BAD_ADDRESS") r.message else d } }
                    }
                }, ui.lp(0, -2, 1f))
                else addView(ui.button("Unpair", primary = false, tag = "unpairButton") {
                    host.confirm("Unpair?", "This phone will stop receiving data until you pair again.", "Unpair") {
                        app.companion.unpair(); app.repo.refreshNow(); host.rerender()
                    }
                }, ui.lp(0, -2, 1f))
            })
            addView(pmsg, ui.lp(top = 6))
        })

        // ---- Refresh & offline ----------------------------------------------
        addView(ui.card("Refresh & offline") {
            val refresh = ui.input("Refresh seconds", "refreshSec", InputType.TYPE_CLASS_NUMBER, cfg.refreshSec.toString())
            val timeout = ui.input("Timeout seconds", "timeoutSec", InputType.TYPE_CLASS_NUMBER, cfg.timeoutSec.toString())
            addView(ui.text("Refresh interval (2-300 s)", 12f, Pal.dim)); addView(refresh, ui.lp(bottom = 6))
            addView(ui.text("Request timeout (1-30 s)", 12f, Pal.dim)); addView(timeout, ui.lp(bottom = 6))
            val cached = check("Show last saved data when offline", cfg.showCachedOffline, "showCached")
            val screenOn = check("Keep screen on while dashboard is open", cfg.keepScreenOn, "keepScreenOn")
            addView(cached); addView(screenOn)
            val m = ui.text("", 12f, Pal.dim)
            addView(ui.hbox {
                addView(ui.button("Save", tag = "saveRefresh") {
                    val r = refresh.text.toString().toIntOrNull(); val t = timeout.text.toString().toIntOrNull()
                    if (r == null || t == null) { m.setTextColor(Pal.bad); m.text = "Enter whole numbers."; return@button }
                    cfg.refreshSec = r; cfg.timeoutSec = t; cfg.showCachedOffline = cached.isChecked; cfg.keepScreenOn = screenOn.isChecked
                    refresh.setText(cfg.refreshSec.toString()); timeout.setText(cfg.timeoutSec.toString())
                    m.setTextColor(Pal.ok); m.text = "Saved."
                    app.repo.refreshNow()
                }, ui.lp(0, -2, 1f).apply { rightMargin = ui.dp(6) })
                addView(ui.button("Clear saved data", primary = false) { app.repo.clearCache(); m.setTextColor(Pal.ok); m.text = "Saved dashboard data cleared." }, ui.lp(0, -2, 1f))
            }, ui.lp(top = 6))
            addView(m)
        })

        // ---- Weather ----------------------------------------------------------
        addView(ui.card("Weather location") {
            addView(ui.text(if (cfg.weatherLat != null) "Current: ${cfg.weatherName} (%.2f, %.2f)".format(cfg.weatherLat, cfg.weatherLon) else "Not set", 12f, Pal.dim))
            val city = ui.input("City name", "city")
            val f = check("Fahrenheit", cfg.fahrenheit, "fahrenheit")
            addView(city, ui.lp(top = 6, bottom = 6)); addView(f)
            val wm = ui.text("", 12f, Pal.dim)
            addView(ui.button("Find & save", primary = false) {
                cfg.fahrenheit = f.isChecked
                if (city.text.isBlank()) { wm.text = "Saved units."; app.repo.refreshInternet(); return@button }
                wm.setTextColor(Pal.dim); wm.text = "Searching…"
                app.repo.geocode(city.text.toString()) { name, lat, lon, err ->
                    if (name == null) { wm.setTextColor(Pal.bad); wm.text = err ?: "Not found." }
                    else { cfg.weatherName = name; cfg.weatherLat = lat; cfg.weatherLon = lon; wm.setTextColor(Pal.ok); wm.text = "Saved $name."; app.repo.refreshInternet() }
                }
            })
            addView(wm)
        })

        // ---- GitHub ------------------------------------------------------------
        addView(ui.card("GitHub") {
            val repo = ui.input("owner/repository", "githubRepo", value = cfg.githubRepo)
            val token = ui.input(if (app.secrets.get(DashboardRepository.GITHUB_TOKEN) != null) "Token saved (enter to replace)" else "Optional token (private repos / rate limits)",
                "githubToken", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            addView(repo, ui.lp(bottom = 6)); addView(token, ui.lp(bottom = 6))
            val gm = ui.text("", 12f, Pal.dim)
            addView(ui.hbox {
                addView(ui.button("Save", primary = false) {
                    cfg.githubRepo = repo.text.toString()
                    if (token.text.isNotEmpty()) app.secrets.put(DashboardRepository.GITHUB_TOKEN, token.text.toString().trim())
                    token.setText(""); gm.text = "Saved."; app.repo.refreshInternet()
                }, ui.lp(0, -2, 1f).apply { rightMargin = ui.dp(6) })
                addView(ui.button("Remove token", primary = false) { app.secrets.put(DashboardRepository.GITHUB_TOKEN, null); gm.text = "Token removed." }, ui.lp(0, -2, 1f))
            })
            addView(gm)
        })

        // ---- Phone shortcuts ----------------------------------------------------
        addView(ui.card("Phone shortcuts") {
            addView(ui.text("One per line: Label | https://link  or  Label | app:com.package.name", 12f, Pal.dim))
            val box = ui.input("Shortcuts", "localShortcuts", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, cfg.localShortcuts).apply { minLines = 3 }
            addView(box, ui.lp(top = 6, bottom = 6))
            addView(ui.button("Save", primary = false) { cfg.localShortcuts = box.text.toString(); host.toast("Saved.") })
        })

        // ---- Desk mode -------------------------------------------------------------
        addView(ui.card("Desk mode") {
            addView(ui.text("A full-screen landscape display that rotates through the pages below. It starts by itself when the phone is idle and charging; touch the screen to leave.", 12f, Pal.dim), ui.lp(bottom = 6))
            val idle = ui.input("Minutes", "deskIdle", InputType.TYPE_CLASS_NUMBER, cfg.deskIdleMin.toString())
            val secs = ui.input("Seconds", "deskSecs", InputType.TYPE_CLASS_NUMBER, cfg.deskPanelSec.toString())
            addView(ui.text("Start after no touch for (minutes, 0 = only with the Desk button)", 12f, Pal.dim)); addView(idle, ui.lp(bottom = 6))
            addView(ui.text("Seconds per page (4-300)", 12f, Pal.dim)); addView(secs, ui.lp(bottom = 6))
            addView(ui.text("Pages", 12f, Pal.dim))
            val chosen = cfg.deskPanels.toSet()
            val boxes = com.sidya.deskhub.core.DESK_PANELS.map { id ->
                check(id.replaceFirstChar { it.uppercase() }, id in chosen, "desk_$id").also { addView(it) }
            }
            val battery = check("Also keep the screen on when not charging", cfg.deskOnBattery, "deskBattery")
            val nightOn = check("Night: dim clock only", cfg.nightEnabled, "nightOn")
            addView(battery); addView(nightOn)
            fun hhmm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
            val nStart = ui.input("23:00", "nightStart", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME, hhmm(cfg.nightStart))
            val nEnd = ui.input("07:00", "nightEnd", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME, hhmm(cfg.nightEnd))
            addView(ui.hbox {
                addView(nStart, ui.lp(0, -2, 1f).apply { rightMargin = ui.dp(6) })
                addView(ui.text("to", 13f, Pal.dim), ui.lp(-2, -2).apply { rightMargin = ui.dp(6) })
                addView(nEnd, ui.lp(0, -2, 1f))
            }, ui.lp(top = 4, bottom = 6))
            val dm = ui.text("", 12f, Pal.dim, tag = "deskMessage")
            fun parse(t: String): Int? {
                val m = Regex("^(\\d{1,2}):(\\d{2})$").find(t.trim()) ?: return null
                val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
                return if (h in 0..23 && mi in 0..59) h * 60 + mi else null
            }
            addView(ui.hbox {
                addView(ui.button("Save", tag = "saveDesk") {
                    val i = idle.text.toString().toIntOrNull(); val sec = secs.text.toString().toIntOrNull()
                    val a = parse(nStart.text.toString()); val b = parse(nEnd.text.toString())
                    val pages = com.sidya.deskhub.core.DESK_PANELS.filterIndexed { k, _ -> boxes[k].isChecked }
                    when {
                        i == null || sec == null -> { dm.setTextColor(Pal.bad); dm.text = "Enter whole numbers." }
                        a == null || b == null -> { dm.setTextColor(Pal.bad); dm.text = "Night times must look like 23:00." }
                        pages.isEmpty() -> { dm.setTextColor(Pal.bad); dm.text = "Choose at least one page." }
                        else -> {
                            cfg.deskIdleMin = i; cfg.deskPanelSec = sec; cfg.deskPanels = pages
                            cfg.deskOnBattery = battery.isChecked; cfg.nightEnabled = nightOn.isChecked
                            cfg.nightStart = a; cfg.nightEnd = b
                            idle.setText(cfg.deskIdleMin.toString()); secs.setText(cfg.deskPanelSec.toString())
                            dm.setTextColor(Pal.ok); dm.text = "Saved."
                        }
                    }
                }, ui.lp(0, -2, 1f).apply { rightMargin = ui.dp(6) })
                addView(ui.button("Open now", primary = false, tag = "openDesk") { host.openDesk() }, ui.lp(0, -2, 1f))
            })
            addView(dm)
        })

        // ---- Theme ---------------------------------------------------------------
        addView(ui.card("Theme") {
            addView(ui.hbox {
                addView(ui.button("Charcoal", primary = cfg.theme == "charcoal") { cfg.theme = "charcoal"; host.applyTheme() }, ui.lp(0, -2, 1f).apply { rightMargin = ui.dp(6) })
                addView(ui.button("Pure black", primary = cfg.theme == "black") { cfg.theme = "black"; host.applyTheme() }, ui.lp(0, -2, 1f))
            })
        })

        // ---- Security & session -----------------------------------------------
        addView(ui.card("Security & session") {
            val t = ui.input("Minutes", "sessionTimeout", InputType.TYPE_CLASS_NUMBER, app.auth.sessionTimeoutMin.toString())
            addView(ui.text("Auto-logout after inactivity (minutes, 1-240)", 12f, Pal.dim)); addView(t, ui.lp(bottom = 6))
            addView(ui.button("Save timeout", primary = false) {
                t.text.toString().toIntOrNull()?.let { app.auth.sessionTimeoutMin = it; t.setText(app.auth.sessionTimeoutMin.toString()); host.toast("Saved.") }
            }, ui.lp(bottom = 6))
            addView(ui.button("Change PIN", primary = false, tag = "changePin") { host.startActivity(Intent(host, ChangePinActivity::class.java)) }, ui.lp(bottom = 6))
            addView(ui.button("Log out", tag = "logout") { host.logout() })
        })

        // ---- About ---------------------------------------------------------------
        addView(ui.card("About") {
            addView(ui.row("App", "Hubuddy DeskHub — SIDYA Edition"))
            addView(ui.row("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"))
            addView(ui.row("Package", BuildConfig.APPLICATION_ID))
            addView(ui.row("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"))
            addView(ui.row("Secure storage", if (app.secureStorageAvailable) "Android Keystore" else "unavailable", if (app.secureStorageAvailable) Pal.ok else Pal.warn))
            addView(ui.text("Native UI, no WebView. Adapted from DeskHub by Desk Hub contributors (MIT License). Plain HTTP on your LAN is not encrypted; prefer USB mode or a trusted network.", 11f, Pal.dim).apply { setPadding(0, ui.dp(6), 0, 0) })
        })
    }

    private fun check(label: String, value: Boolean, tag: String): CheckBox = CheckBox(host).apply {
        text = label; isChecked = value; setTextColor(Pal.text); this.tag = tag
    }

    @Suppress("unused")
    private fun EditText.int(): Int? = text.toString().toIntOrNull()
}
