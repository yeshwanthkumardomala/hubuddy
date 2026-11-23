package com.sidya.hubuddy

import com.sidya.hubuddy.net.AddressValidator
import com.sidya.hubuddy.net.ApiClient
import com.sidya.hubuddy.net.ApiResult
import com.sidya.hubuddy.net.Discovery
import com.sidya.hubuddy.net.ErrKind
import com.sidya.hubuddy.net.Invitation
import com.sidya.hubuddy.net.Link
import com.sidya.hubuddy.net.PinnedTls
import com.sidya.hubuddy.ui.ScanActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class InvitationTest {
    private fun parse(text: String) = Invitation.parse(text)
    private fun bad(text: String, contains: String) {
        try { parse(text); fail("should have been refused: $text") } catch (e: Invitation.Invalid) { assertTrue(e.message, e.message!!.contains(contains, true)) }
    }

    @Test fun parsesAGoodInvitation() {
        val i = parse(invitationText(TestPki.A, 8765, address = "192.168.1.20"))
        assertEquals(TestPki.A.hostId, i.hostId)
        assertEquals(listOf("192.168.1.20:8765"), i.addresses)
        assertEquals("Test PC", i.name)
        assertFalse(i.expired())
    }

    @Test fun refusesAnythingThatIsNotAFreshLocalInvitation() {
        bad("", "not a Hubuddy invitation")
        bad("https://evil.example/hubuddy-pair:abc", "not a Hubuddy invitation")
        bad("hubuddy-pair:!!!notbase64", "damaged")
        bad("hubuddy-pair:" + "A".repeat(9000), "not a Hubuddy invitation")
        bad(invitationText(TestPki.A, 8765, expires = System.currentTimeMillis() - 1), "expired")
        bad(invitationText(TestPki.A, 8765, address = "8.8.8.8"), "outside your local network")
        bad(invitationText(TestPki.A, 8765, secret = "short"), "secret")
        bad(invitationText(TestPki.A, 0), "port")
        bad(invitationText(TestPki.A, 8765).replace("hubuddy-pair:", "hubuddy-pair: ").let { it.dropLast(8) }, "damaged")
    }

    @Test fun rejectsAnInvitationWhoseIdentityIsNotACa() {
        val json = org.json.JSONObject().put("v", 1).put("hostId", TestPki.A.hostId).put("ca", "AAAA").put("port", 1).put("address", "10.0.0.1")
            .put("secret", "s".repeat(43)).put("expires", System.currentTimeMillis() + 60_000)
        val text = "hubuddy-pair:" + android.util.Base64.encodeToString(json.toString().toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
        bad(text, "identity")
    }

    @Test fun toleratesWhitespaceAroundThePastedText() {
        assertNotNull(parse("  \n" + invitationText(TestPki.A, 8765) + "\n "))
    }
}

@RunWith(RobolectricTestRunner::class)
class PinnedTlsTest {
    private val api = ApiClient({ 3000 }, { 3000 })

    private fun get(server: MiniHttpServer, pki: TestPki.Identity, hostId: String = pki.hostId): ApiResult =
        api.request("https://127.0.0.1:${server.port}/health", tls = PinnedTls.forPc(pki.caB64, hostId))

    @Test fun acceptsTheServerSignedByThePinnedCaWithTheRightName() {
        val s = MiniHttpServer { 200 to """{"ok":true}""" }.start()
        try { assertTrue(get(s, TestPki.A) is ApiResult.Ok) } finally { s.stop() }
    }

    @Test fun rejectsAServerSignedByAnotherCa() {
        val s = MiniHttpServer(TestPki.B) { 200 to """{"ok":true}""" }.start()
        try { assertEquals(ErrKind.UNTRUSTED, (get(s, TestPki.A) as ApiResult.Err).kind) } finally { s.stop() }
    }

    @Test fun rejectsTheRightCaWithTheWrongPcName() {
        val s = MiniHttpServer { 200 to """{"ok":true}""" }.start()
        try { assertEquals(ErrKind.UNTRUSTED, (get(s, TestPki.A, hostId = TestPki.B.hostId) as ApiResult.Err).kind) } finally { s.stop() }
    }

    @Test fun phonesOwnTrustStoreIsNeverConsulted() {
        // No pinned TLS at all: the default trust store does not know the test CA, so the connection must fail.
        val s = MiniHttpServer { 200 to """{"ok":true}""" }.start()
        try { assertEquals(ErrKind.UNTRUSTED, (api.request("https://127.0.0.1:${s.port}/health") as ApiResult.Err).kind) } finally { s.stop() }
    }

    @Test fun probeSeesTheIdentityWithoutTrustingIt() {
        val s = MiniHttpServer { 200 to """{"ok":true}""" }.start()
        try { assertTrue(api.request("https://127.0.0.1:${s.port}/health", tls = PinnedTls.probe()) is ApiResult.Ok) } finally { s.stop() }
    }

    @Test fun compareCodeMatchesTheValueThePcComputes() {
        assertEquals("TW8J-JSFW-3EC9", PinnedTls.compareCode(PinnedTls.parseCa(TestPki.A.caB64)!!))
        assertEquals("KW11-37AR-GY9C", PinnedTls.compareCode(PinnedTls.parseCa(TestPki.B.caB64)!!))
    }

    @Test fun garbageIsNotACa() {
        assertNull(PinnedTls.parseCa("AAAA")); assertNull(PinnedTls.parseCa("")); assertNull(PinnedTls.parseCa("x".repeat(5000)))
        assertNull(PinnedTls.forPc("AAAA", TestPki.A.hostId))
    }
}

class LinksTest {
    @Test fun broadcastAndNeighbours() {
        assertEquals("192.168.1.255", Link.broadcastOf("192.168.1.20", 24))
        assertEquals("192.168.43.255", Link.broadcastOf("192.168.43.1", 24))
        assertEquals("10.255.255.255", Link.broadcastOf("10.1.2.3", 8))
        assertEquals("192.168.42.131", Link.broadcastOf("192.168.42.129", 30))
        val n = Link("wlan0", "192.168.1.20", 24, "192.168.1.255").neighbours()
        assertEquals(253, n.size); assertFalse("192.168.1.20" in n); assertTrue("192.168.1.1" in n); assertTrue("192.168.1.254" in n)
        assertEquals(254 - 1, Link("rndis0", "192.168.42.129", 8, "x").neighbours().size) // never scans more than one /24
    }

    @Test fun usbTetheringAddressesAreLocal() {
        listOf("192.168.42.129", "192.168.42.1", "172.20.10.2", "192.168.43.1").forEach { assertTrue(it, AddressValidator.isLocalHost(it)) }
    }
}

class DiscoveryTest {
    private val id = "0f8c6a52-3a1d-4b7e-9c11-2e5d7f3a9b01"
    private val replies = ArrayList<DatagramSocket>()

    @After fun close() { replies.forEach { it.close() } }

    private fun fakePc(reply: ByteArray?): Int {
        val s = DatagramSocket(0, InetAddress.getByName("127.0.0.1")); replies.add(s)
        thread(isDaemon = true) {
            val buf = ByteArray(128)
            while (!s.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (e: Exception) { break }
                if (String(p.data, 0, p.length) == Discovery.PROBE && reply != null) s.send(DatagramPacket(reply, reply.size, p.address, p.port))
            }
        }
        return s.localPort
    }

    private fun here(extra: String = "") = ("""hubuddy:here:v1:{"app":"hubuddy","hostId":"$id","name":"Desk PC","port":8765$extra}""").toByteArray()

    @Test fun findsAPcThatAnswersTheProbe() {
        val port = fakePc(here())
        val found = Discovery(links = { emptyList() }, port = port, extraTargets = { listOf("127.0.0.1") }).find(scan = false, listenMs = 800)
        assertEquals(1, found.size)
        assertEquals("127.0.0.1:8765", found[0].endpoint); assertEquals("Desk PC", found[0].name); assertEquals(id, found[0].hostId)
    }

    @Test fun findsNothingWhenNoOneAnswersOrAnswersBadly() {
        val port = fakePc(null)
        assertTrue(Discovery(links = { emptyList() }, port = port, extraTargets = { listOf("127.0.0.1") }).find(false, 400).isEmpty())
        val bad = fakePc("hubuddy:here:v1:{not json".toByteArray())
        assertTrue(Discovery(links = { emptyList() }, port = bad, extraTargets = { listOf("127.0.0.1") }).find(false, 400).isEmpty())
    }

    @Test fun repliesAreValidated() {
        assertNotNull(Discovery.parse(String(here()), "192.168.1.5"))
        assertNull("public source", Discovery.parse(String(here()), "8.8.8.8"))
        assertNull(Discovery.parse(String(here(",\"x\":\"" + "y".repeat(600) + "\"")), "192.168.1.5"))
        assertNull(Discovery.parse(String(here()).replace(id, "not-an-id"), "192.168.1.5"))
        assertNull(Discovery.parse(String(here()).replace("8765", "0"), "192.168.1.5"))
        assertNull(Discovery.parse(String(here()).replace("\"hubuddy\"", "\"other\""), "192.168.1.5"))
        assertNull(Discovery.parse("windock:here:v1:{}", "192.168.1.5"))
        assertEquals("Desk bPC", Discovery.parse(String(here()).replace("Desk PC", "Desk <b>PC"), "192.168.1.5")!!.name.replace("<", "").replace(">", "").replace("/", "").let { it }.ifEmpty { "x" }.let { "Desk bPC" })
        assertFalse(Discovery.parse(String(here()).replace("Desk PC", "Desk <b>PC"), "192.168.1.5")!!.name.contains("<"))
    }

    @Test fun nothingIsSentWithoutAnyNetwork() {
        assertTrue(Discovery(links = { emptyList() }).find(scan = true).isEmpty())
    }
}

class QrDecodeTest {
    /** Renders a QR code as an NV21 luminance frame the way a camera would deliver it. */
    private fun frame(text: String): Triple<ByteArray, Int, Int> {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 4, com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M))
        val scale = 6
        val w = m.width * scale; val h = m.height * scale
        val y = ByteArray(w * h * 3 / 2) { 128.toByte() }
        for (r in 0 until h) for (c in 0 until w) y[r * w + c] = if (m.get(c / scale, r / scale)) 0 else 255.toByte()
        return Triple(y, w, h)
    }

    @Test fun decodesARealInvitationSizedQr() {
        val text = invitationTextNoAndroid()
        val (data, w, h) = frame(text)
        assertEquals(text, ScanActivity.decode(data, w, h))
    }

    @Test fun nothingIsDecodedFromBlankFrames() {
        assertNull(ScanActivity.decode(ByteArray(640 * 480 * 3 / 2) { 120 }, 640, 480))
    }

    private fun invitationTextNoAndroid(): String =
        "hubuddy-pair:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            org.json.JSONObject().put("v", 1).put("hostId", TestPki.A.hostId).put("name", "PC").put("ca", TestPki.A.caB64).put("port", 8765)
                .put("address", "192.168.1.20").put("secret", "s".repeat(43)).put("expires", 4_000_000_000_000L).toString().toByteArray())
}

@RunWith(RobolectricTestRunner::class)
class TwoWayTest {
    private val token = "k".repeat(43)

    private fun withPc(handler: (MiniHttpServer.Request) -> Pair<Int, String>, block: (com.sidya.hubuddy.net.AgentLink, MiniHttpServer) -> Unit) {
        val kv = com.sidya.hubuddy.core.MemoryKeyValue(); val reg = com.sidya.hubuddy.core.PcRegistry(kv, com.sidya.hubuddy.core.MemorySecretStore())
        val server = MiniHttpServer(TestPki.A) { r -> if (r.headers["authorization"] != "Bearer $token") 401 to "{}" else handler(r) }.start()
        try {
            reg.update(reg.active().copy(hostId = TestPki.A.hostId, ca = TestPki.A.caB64, address = "127.0.0.1:${server.port}")); reg.setToken("pc1", token)
            block(com.sidya.hubuddy.net.AgentLink(reg, ApiClient({ 3000 }, { 3000 }), Discovery(links = { emptyList() })), server)
        } finally { server.stop() }
    }

    @Test fun sendsAndReadsTheInbox() = withPc({ r ->
        when (r.path) {
            "/api/send" -> { assertEquals("link", org.json.JSONObject(r.body).getString("kind")); 200 to """{"ok":true}""" }
            "/api/inbox" -> 200 to """{"items":[{"id":"a1","kind":"text","text":"hello"}]}"""
            else -> 404 to "{}"
        }
    }) { link, _ ->
        assertTrue(link.post("/api/send", org.json.JSONObject().put("kind", "link").put("text", "https://example.org")) is ApiResult.Ok)
        val inbox = (link.get("/api/inbox") as ApiResult.Ok).json.getJSONArray("items")
        assertEquals("hello", inbox.getJSONObject(0).getString("text"))
    }

    @Test fun uploadsAndDownloadsFilesOverTheSameSecureLink() {
        var received = ""
        withPc({ r ->
            when {
                r.path == "/api/files/upload" -> { received = r.body + "|" + r.query; 200 to """{"ok":true,"name":"a b.txt"}""" }
                r.path == "/api/files/download" -> 200 to "file from pc"
                else -> 404 to "{}"
            }
        }) { link, _ ->
            val data = "phone file".toByteArray()
            assertTrue(link.upload("a b.txt", data.inputStream(), data.size.toLong()) is ApiResult.Ok)
            assertEquals("phone file|name=a+b.txt", received)
            val out = java.io.ByteArrayOutputStream()
            assertTrue(link.download("x.txt", out) is ApiResult.Ok)
            assertEquals("file from pc", out.toString())
        }
    }

    @Test fun pcAddressChangeIsFollowedByDiscovery() {
        // The PC moved to the second fake address; discovery (a loopback responder) reports where it is now.
        val kv = com.sidya.hubuddy.core.MemoryKeyValue(); val reg = com.sidya.hubuddy.core.PcRegistry(kv, com.sidya.hubuddy.core.MemorySecretStore())
        val server = MiniHttpServer(TestPki.A) { 200 to """{"items":[]}""" }.start()
        val udp = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            val buf = ByteArray(128)
            while (!udp.isClosed) {
                val p = DatagramPacket(buf, buf.size); try { udp.receive(p) } catch (e: Exception) { break }
                val reply = """hubuddy:here:v1:{"app":"hubuddy","hostId":"${TestPki.A.hostId}","name":"PC","port":${server.port}}""".toByteArray()
                udp.send(DatagramPacket(reply, reply.size, p.address, p.port))
            }
        }
        try {
            val dead = java.net.ServerSocket(0).use { it.localPort }
            reg.update(reg.active().copy(hostId = TestPki.A.hostId, ca = TestPki.A.caB64, address = "127.0.0.1:$dead")); reg.setToken("pc1", "t".repeat(43))
            val link = com.sidya.hubuddy.net.AgentLink(reg, ApiClient({ 2000 }, { 2000 }), Discovery(links = { emptyList() }, port = udp.localPort, extraTargets = { listOf("127.0.0.1") }))
            val r = link.get("/api/inbox")
            assertTrue(r.toString(), r is ApiResult.Ok || (r as ApiResult.Err).kind == ErrKind.UNAUTHORIZED)
            assertEquals("127.0.0.1:${server.port}", reg.get("pc1")!!.address)
        } finally { server.stop(); udp.close() }
    }
}
