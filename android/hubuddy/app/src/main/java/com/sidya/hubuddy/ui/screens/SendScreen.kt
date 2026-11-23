package com.sidya.hubuddy.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.View
import com.sidya.hubuddy.data.DashState
import com.sidya.hubuddy.net.ApiResult
import com.sidya.hubuddy.net.describe
import com.sidya.hubuddy.ui.MainActivity
import com.sidya.hubuddy.ui.Pal
import org.json.JSONArray
import org.json.JSONObject

/** Two-way: text, links, the clipboard and files, from the phone to the selected PC and back. */
class SendScreen(host: MainActivity) : Screen(host) {
    override val live = false
    override val title: String get() = "Send & files"

    private var items: List<JSONObject> = emptyList()
    private var files: List<String> = emptyList()
    private var note: String? = null
    private var holder: android.widget.FrameLayout? = null

    init { host.transfers.onDone = { refresh() }; refresh() }

    private fun pc() = app.pcs.activeId

    fun refresh() {
        val id = pc()
        if (!app.agent.isPaired(id)) { note = "Pair this PC first (Settings)."; rebuild(); return }
        app.repo.background({ app.agent.get("/api/inbox", id) }) { r ->
            if (r is ApiResult.Ok) { items = r.json.optJSONArray("items").objects(); note = null } else note = (r as ApiResult.Err).describe()
            app.repo.background({ app.agent.get("/api/files", id) }) { f ->
                if (f is ApiResult.Ok) files = f.json.optJSONArray("toPhone").objects().map { it.optString("name") }.filter { it.isNotEmpty() }
                rebuild()
            }
        }
    }

    private fun rebuild() { host.rerender() }

    override fun render(s: DashState): View = ui.vbox {
        val pcName = app.pcs.active().name
        addView(ui.text("Selected PC: $pcName", 12f, Pal.dim), ui.lp(bottom = 6))
        note?.let { addView(ui.text(it, 12f, Pal.warn), ui.lp(bottom = 6)) }
        addView(ui.card("Send to the PC", tag = "sendCard") {
            addView(ui.button("Send my clipboard to the PC", tag = "sendClipboard") {
                val cm = host.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(host)?.toString().orEmpty()
                if (text.isBlank()) host.toast("The clipboard is empty.") else send("clipboard", text, "Clipboard sent. Paste it on the PC.")
            }, ui.lp(bottom = 8))
            val link = ui.input("https://… link to open on the PC", "sendLink", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            addView(link, ui.lp(bottom = 6))
            addView(ui.button("Open this link on the PC", primary = false, tag = "sendLinkButton") {
                val t = link.text.toString().trim()
                if (t.isEmpty()) host.toast("Enter a link first.") else send("link", t, "Link opened on the PC.")
            }, ui.lp(bottom = 8))
            addView(ui.button("Send a file to the PC…", primary = false, tag = "sendFile") { host.transfers.pickFile() })
            addView(ui.text("Files arrive in the PC's Documents\\Hubuddy\\Received folder.", 11f, Pal.dim), ui.lp(top = 6))
        })
        addView(ui.card("From the PC", tag = "fromPc") {
            if (items.isEmpty() && files.isEmpty()) empty("Nothing waiting. On the PC, use “Send to phone” in Hubuddy, or drop files in Documents\\Hubuddy\\ToPhone.")
            items.forEach { it0 ->
                val id = it0.optString("id"); val kind = it0.optString("kind"); val text = it0.optString("text")
                addView(ui.text(if (kind == "link") "Link" else "Text", 11f, Pal.walnut, bold = true))
                addView(ui.text(text.take(300), 13f), ui.lp(bottom = 4))
                addView(ui.hbox {
                    if (kind == "link") addView(ui.button("Open", tag = "openItem_$id") { openLink(text); ack(id) }, ui.lp(0, -2, 1f).apply { rightMargin = ui.dp(6) })
                    else addView(ui.button("Copy", tag = "copyItem_$id") {
                        (host.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Hubuddy", text))
                        host.toast("Copied."); ack(id)
                    }, ui.lp(0, -2, 1f).apply { rightMargin = ui.dp(6) })
                    addView(ui.button("Dismiss", primary = false, tag = "dismissItem_$id") { ack(id) }, ui.lp(0, -2, 1f))
                }, ui.lp(bottom = 10))
            }
            files.forEach { name ->
                addView(ui.hbox {
                    addView(ui.text(name, 13f), ui.lp(0, -2, 1f))
                    addView(ui.button("Save", primary = false, tag = "saveFile_$name") { host.transfers.saveFile(pc(), name) })
                }, ui.lp(bottom = 6))
            }
            addView(ui.button("Check again", primary = false, tag = "refreshInbox") { refresh() }, ui.lp(top = 6))
        })
    }

    private fun send(kind: String, text: String, ok: String) {
        app.repo.command("/api/send", JSONObject().put("kind", kind).put("text", text)) { r -> host.toast(if (r is ApiResult.Ok) ok else (r as ApiResult.Err).describe()) }
    }

    private fun ack(id: String) {
        app.repo.command("/api/inbox/ack", JSONObject().put("ids", JSONArray().put(id))) { items = items.filter { it.optString("id") != id }; rebuild() }
    }

    private fun openLink(url: String) {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) { host.toast("Not a web link."); return }
        try { host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { host.toast("No app can open this link.") }
    }
}
