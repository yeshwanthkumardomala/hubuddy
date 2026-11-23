package com.sidya.hubuddy.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.sidya.hubuddy.net.ApiResult
import com.sidya.hubuddy.net.ErrKind
import com.sidya.hubuddy.net.describe
import org.json.JSONObject

/**
 * File exchange with a PC. The phone picks and saves files through Android's own file pickers, so no storage
 * permission is needed and the user sees exactly which file goes where.
 */
class Transfers(private val host: MainActivity) {
    private val app get() = host.app
    private var pendingSave: Pair<String, String>? = null // pc id to file name
    var onDone: () -> Unit = {}

    fun pickFile() {
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
        host.startActivityForResult(Intent.createChooser(i, "Send a file to the PC"), REQ_PICK)
    }

    fun saveFile(pcId: String, name: String) {
        pendingSave = pcId to name
        host.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE, name), REQ_SAVE)
    }

    fun onResult(request: Int, result: Int, data: Intent?) {
        val uri = data?.data
        if (result != Activity.RESULT_OK || uri == null) { if (request == REQ_SAVE) pendingSave = null; return }
        when (request) {
            REQ_PICK -> upload(uri)
            REQ_SAVE -> pendingSave?.let { (pc, name) -> download(pc, name, uri) }.also { pendingSave = null }
        }
    }

    private fun upload(uri: Uri) {
        val pc = app.pcs.activeId
        var name = "file"; var size = -1L
        try {
            host.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { c.getString(0)?.let { name = it }; if (!c.isNull(1)) size = c.getLong(1) }
            }
        } catch (e: Exception) { /* fall through with defaults */ }
        if (size <= 0) { host.toast("That file is empty or its size is unknown."); return }
        host.toast("Sending $name…")
        app.repo.background({
            val input = try { host.contentResolver.openInputStream(uri) } catch (e: Exception) { null }
            if (input == null) ApiResult.Err(ErrKind.HTTP, "Could not read that file.") else app.agent.upload(name, input, size, pc)
        }) { r -> host.toast(if (r is ApiResult.Ok) "Sent $name to the PC." else (r as ApiResult.Err).describe()); onDone() }
    }

    private fun download(pc: String, name: String, uri: Uri) {
        host.toast("Receiving $name…")
        app.repo.background({
            val out = try { host.contentResolver.openOutputStream(uri) } catch (e: Exception) { null }
            if (out == null) ApiResult.Err(ErrKind.HTTP, "Could not write the file.") else out.use { app.agent.download(name, it, pc) }
        }) { r -> host.toast(if (r is ApiResult.Ok) "Saved $name." else (r as ApiResult.Err).describe()); onDone() }
    }

    companion object { const val REQ_PICK = 7002; const val REQ_SAVE = 7003 }
}
