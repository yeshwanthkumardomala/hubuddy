package com.sidya.hubuddy

import com.sidya.hubuddy.core.MemoryKeyValue
import com.sidya.hubuddy.core.MemorySecretStore
import com.sidya.hubuddy.core.PcRegistry
import com.sidya.hubuddy.net.AgentLink
import com.sidya.hubuddy.net.ApiClient
import com.sidya.hubuddy.net.ApiResult
import com.sidya.hubuddy.net.Discovery
import com.sidya.hubuddy.net.ErrKind
import com.sidya.hubuddy.net.Invitation
import com.sidya.hubuddy.net.PairOutcome
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.HttpURLConnection
import java.net.URL

/**
 * The app's real client code against the real PC program. Skipped unless a PC program is running and its ports are
 * passed in (tools/interop.sh does that), because it needs the Python agent.
 */
@RunWith(RobolectricTestRunner::class)
class InteropTest {
    private val lan = System.getProperty("hubuddy.interop.lan")?.toIntOrNull()
    private val local = System.getProperty("hubuddy.interop.local")?.toIntOrNull()

    private fun localCall(method: String, path: String, body: JSONObject? = null, raw: ByteArray? = null): JSONObject {
        val c = URL("http://127.0.0.1:$local$path").openConnection() as HttpURLConnection
        c.requestMethod = method; c.setRequestProperty("X-Hubuddy-Local", "1"); c.setRequestProperty("Content-Type", "application/json")
        val data = raw ?: body?.toString()?.toByteArray()
        if (data != null) { c.doOutput = true; c.outputStream.use { it.write(data) } }
        return JSONObject(c.inputStream.bufferedReader().readText())
    }

    /** The PC advertises its LAN address; inside a test container that may be a non-private one, so point it at loopback. */
    private fun loopback(invitation: String): String {
        val raw = invitation.removePrefix(Invitation.PREFIX)
        val o = JSONObject(String(android.util.Base64.decode(raw, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)))
        o.put("address", "127.0.0.1").put("addresses", JSONArray())
        return Invitation.PREFIX + android.util.Base64.encodeToString(o.toString().toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }

    @Test fun pairsAndExchangesEverythingWithTheRealPcProgram() {
        assumeTrue("no PC program running (use tools/interop.sh)", lan != null && local != null)
        val reg = PcRegistry(MemoryKeyValue(), MemorySecretStore())
        val link = AgentLink(reg, ApiClient({ 5000 }, { 8000 }), Discovery(links = { emptyList() }))

        // --- QR pairing, and the same invitation cannot be used twice
        val text = loopback(localCall("POST", "/local/invite").getString("invitation"))
        val inv = Invitation.parse(text)
        assertTrue(link.claimInvitation(inv, "Interop phone") is PairOutcome.Paired)
        assertTrue(link.isPaired("pc1"))
        val again = link.claimInvitation(inv, "Replay") as PairOutcome.Failed
        assertEquals(ErrKind.UNAUTHORIZED, again.err.kind)

        // --- authenticated reads and identity check
        assertTrue("health verifies the pinned identity", link.ping("pc1") is ApiResult.Ok)
        val snap = link.get("/api/snapshot", "pc1")
        assertTrue(snap.toString(), snap is ApiResult.Ok)

        // --- phone to PC
        val bad = link.post("/api/send", JSONObject().put("kind", "link").put("text", "javascript:alert(1)"), "pc1") as ApiResult.Err
        assertEquals(400, bad.code)

        // --- PC to phone, then acknowledge
        localCall("POST", "/local/send", JSONObject().put("kind", "text").put("text", "hello from the PC"))
        val inbox = (link.get("/api/inbox", "pc1") as ApiResult.Ok).json.getJSONArray("items")
        assertEquals("hello from the PC", inbox.getJSONObject(0).getString("text"))
        assertTrue(link.post("/api/inbox/ack", JSONObject().put("ids", JSONArray().put(inbox.getJSONObject(0).getString("id"))), "pc1") is ApiResult.Ok)
        assertEquals(0, (link.get("/api/inbox", "pc1") as ApiResult.Ok).json.getJSONArray("items").length())

        // --- files both ways
        val up = "bytes from the phone".toByteArray()
        assertTrue(link.upload("phone note.txt", up.inputStream(), up.size.toLong(), "pc1") is ApiResult.Ok)
        val state = localCall("GET", "/local/state")
        assertTrue(state.toString(), state.getJSONArray("received").toString().contains("phone note.txt"))
        localCall("PUT", "/local/files/upload?name=from-pc.txt", raw = "bytes from the PC".toByteArray())
        val out = java.io.ByteArrayOutputStream()
        assertTrue(link.download("from-pc.txt", out, "pc1") is ApiResult.Ok)
        assertEquals("bytes from the PC", out.toString())
        assertTrue("traversal is refused", link.download("../host-id", java.io.ByteArrayOutputStream(), "pc1") is ApiResult.Err)

        // --- PIN pairing with the compare code, as a second phone
        val pin = localCall("POST", "/local/pin")
        val probed = link.toProbed("127.0.0.1:$lan", link.probe("127.0.0.1:$lan") as ApiResult.Ok)!!
        assertEquals("the compare code the phone computes is the one the PC shows", pin.getString("compare"), probed.compareCode)
        val wrong = link.claimPin(probed, if (pin.getString("pin") == "000000") "111111" else "000000", "Wrong pin") as PairOutcome.Failed
        assertEquals(ErrKind.UNAUTHORIZED, wrong.err.kind)
        val ok = link.claimPin(probed, pin.getString("pin"), "Interop phone 2")
        assertTrue(ok.toString(), ok is PairOutcome.Paired)
        assertEquals(1, reg.list().size)

        // --- forget on the PC signs the phone out
        localCall("POST", "/local/forget-all", JSONObject().put("confirm", "forget-all"))
        assertEquals(ErrKind.UNAUTHORIZED, (link.get("/api/snapshot", "pc1") as ApiResult.Err).kind)
    }
}
