package com.sidya.hubuddy.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.sidya.hubuddy.net.ApiResult
import com.sidya.hubuddy.net.ErrKind
import com.sidya.hubuddy.net.FoundPc
import com.sidya.hubuddy.net.Invitation
import com.sidya.hubuddy.net.PairOutcome
import com.sidya.hubuddy.net.ProbedPc
import com.sidya.hubuddy.net.describe

/**
 * The four ways to add a PC: scan its QR code, paste its invitation, find it on the network and enter the PIN it shows,
 * or type its address (advanced). All of them end in the same pinned, encrypted connection.
 */
class PairingFlows(private val host: MainActivity) {
    private val app get() = host.app
    private val ui get() = host.ui
    private val deviceName get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    // ---- QR and paste ------------------------------------------------------------
    fun scanQr() = host.startActivityForResult(Intent(host, ScanActivity::class.java), REQ_SCAN)

    fun onResult(request: Int, result: Int, data: Intent?) {
        if (request != REQ_SCAN || result != Activity.RESULT_OK) return
        data?.getStringExtra(ScanActivity.EXTRA_TEXT)?.let(::pair)
    }

    fun pasteInvitation() {
        val box = ui.input("hubuddy-pair:…", "inviteText", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply { minLines = 3 }
        host.dialog().setTitle("Paste invitation")
            .setMessage("On the PC, open Hubuddy, choose Pair a phone, and copy the invitation text shown under the QR code.")
            .setView(pad(box))
            .setPositiveButton("Pair") { _, _ -> pair(box.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Validates an invitation and exchanges it for this phone's own key to that PC. */
    fun pair(text: String, then: () -> Unit = {}) {
        val inv = try { Invitation.parse(text) } catch (e: Invitation.Invalid) { host.toast(e.message ?: "Invalid invitation."); return }
        host.toast("Pairing with ${inv.name}…")
        app.repo.background({ outcomeToResult(app.agent.claimInvitation(inv, deviceName)) }) { r -> finished(r); then() }
    }

    // ---- find on the network + PIN ----------------------------------------------------
    fun findPcs() {
        val status = ui.text("Searching your network…", 14f)
        val list = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        val body = ui.vbox(12) { addView(status); addView(list) }
        val dlg = host.dialog().setTitle("Find PCs").setView(body).setNegativeButton("Close", null).create()
        dlg.show()
        Thread {
            var found = app.discovery.find(scan = false)
            if (found.isEmpty()) found = app.discovery.find(scan = true)
            host.runOnUiThread {
                if (!dlg.isShowing) return@runOnUiThread
                if (found.isEmpty()) {
                    status.text = "No PC found. On the PC, open Hubuddy and make sure it is on the same Wi-Fi or LAN (or USB tethering). " +
                        "If Windows asked about the firewall, allow private networks. You can also scan a QR code or enter the address."
                } else {
                    status.text = "Tap your PC, then compare the code it shows."
                    found.forEach { f -> list.addView(ui.button("${f.name}  ·  ${f.address}", primary = false) { dlg.dismiss(); compare(f.endpoint) }, ui.lp(top = 6)) }
                }
            }
        }.start()
    }

    fun enterAddress() {
        val box = ui.input("192.168.1.20:8765", "pcAddress", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        host.dialog().setTitle("PC address").setMessage("Only needed when searching does not find your PC. It is shown in Hubuddy on the PC.")
            .setView(pad(box)).setPositiveButton("Connect") { _, _ -> compare(box.text.toString()) }.setNegativeButton("Cancel", null).show()
    }

    /** Reads the PC's identity, shows the compare code, and only after the user confirms it matches asks for the PIN. */
    private fun compare(address: String) {
        host.toast("Contacting the PC…")
        app.repo.background({ app.agent.probe(address) }) { r ->
            val probed = (r as? ApiResult.Ok)?.let { app.agent.toProbed(address, it) }
            if (probed == null) { host.toast(if (r is ApiResult.Err) r.describe() else "That is not a Hubuddy PC."); return@background }
            confirmCode(probed)
        }
    }

    private fun confirmCode(p: ProbedPc) {
        val code = ui.text(p.compareCode, 24f, Pal.accent, bold = true, tag = "compareCode").apply { gravity = android.view.Gravity.CENTER }
        val body = ui.vbox(14) {
            addView(ui.text("On ${p.name}, in Hubuddy, choose “Pair with a PIN”. It shows a compare code. It must be exactly this:", 14f))
            addView(code, ui.lp(top = 10, bottom = 10))
            addView(ui.text("If the codes differ, do not continue: someone else on the network may be pretending to be that PC.", 12f, Pal.warn))
        }
        host.dialog().setTitle("Compare the code").setView(body)
            .setPositiveButton("They match") { _, _ -> askPin(p) }.setNegativeButton("They differ", null).show()
    }

    private fun askPin(p: ProbedPc) {
        val pin = ui.input("6-digit PIN", "pinInput", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        host.dialog().setTitle("Enter the PIN").setMessage("The PIN is shown on the PC for two minutes.").setView(pad(pin))
            .setPositiveButton("Pair") { _, _ ->
                app.repo.background({ outcomeToResult(app.agent.claimPin(p, pin.text.toString(), deviceName)) }) { r -> finished(r) }
            }.setNegativeButton("Cancel", null).show()
    }

    // ---- results --------------------------------------------------------------------
    private fun outcomeToResult(o: PairOutcome): ApiResult = when (o) {
        is PairOutcome.Paired -> ApiResult.Ok(org.json.JSONObject().put("name", o.pc.name).put("id", o.pc.id), 0)
        is PairOutcome.Failed -> o.err
    }

    private fun finished(r: ApiResult) {
        if (r is ApiResult.Ok) {
            host.toast("Paired with ${r.json.optString("name")}.")
            app.repo.refreshNow()
            host.rerender()
        } else host.toast((r as ApiResult.Err).describe())
    }

    private fun pad(v: View) = ui.vbox(16) { addView(v) }

    companion object { const val REQ_SCAN = 7001 }
}
