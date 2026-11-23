package com.sidya.hubuddy

import com.sidya.hubuddy.core.Clock
import com.sidya.hubuddy.core.MemoryKeyValue
import com.sidya.hubuddy.core.MemorySecretStore
import com.sidya.hubuddy.core.PcRegistry
import com.sidya.hubuddy.core.Settings
import com.sidya.hubuddy.data.DashState
import com.sidya.hubuddy.data.DashboardRepository
import com.sidya.hubuddy.net.ApiClient
import com.sidya.hubuddy.net.ApiResult
import com.sidya.hubuddy.net.AgentLink
import com.sidya.hubuddy.net.Discovery
import com.sidya.hubuddy.net.Invitation
import com.sidya.hubuddy.net.PairOutcome
import com.sidya.hubuddy.net.ErrKind
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.Executor

/** Exercises the real HTTPS client and poller against a local fake PC. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class DashboardRepositoryTest {
    private lateinit var server: MiniHttpServer
    private var port = 0
    @Volatile private var delayMs = 0L
    @Volatile private var up = true
    private val token = "t".repeat(43)
    private val kv = MemoryKeyValue()
    private val secrets = MemorySecretStore()
    private val settings = Settings(kv)
    private val registry = PcRegistry(kv, secrets)
    private lateinit var cache: File
    private val direct = Executor { it.run() }

    @Before fun setUp() {
        cache = Files.createTempDirectory("dh").toFile()
        server = MiniHttpServer { req ->
            if (delayMs > 0) Thread.sleep(delayMs)
            val path = req.path
            val auth = req.headers["authorization"]
            when {
                !up -> 503 to """{"error":"down"}"""
                path == "/health" -> 200 to """{"ok":true,"app":"hubuddy","version":"3.0.0","hostId":"${TestPki.A.hostId}","name":"TEST","ca":"${TestPki.A.caB64}"}"""
                path == "/api/pairing/claim" -> {
                    val b = JSONObject(req.body)
                    if (b.optString("secret") == SECRET && b.optString("hostId") == TestPki.A.hostId) 200 to """{"ok":true,"token":"$token"}""" else 401 to """{"error":"bad"}"""
                }
                auth != "Bearer $token" -> 401 to """{"error":"no"}"""
                path == "/api/snapshot" -> 200 to """{"stats":{"cpu":42.0},"pc":{"online":true}}"""
                path == "/api/media" -> 200 to """{"ok":true}"""
                else -> 404 to "{}"
            }
        }.start()
        port = server.port
        registry.update(registry.active().copy(hostId = TestPki.A.hostId, ca = TestPki.A.caB64, address = "127.0.0.1:$port")); settings.timeoutSec = 1; settings.refreshSec = 2
    }

    @After fun tearDown() { server.stop(); cache.deleteRecursively() }

    private fun api() = ApiClient({ settings.timeoutSec * 1000 }, { settings.timeoutSec * 1000 })
    private fun link() = AgentLink(registry, api(), Discovery(links = { emptyList() }))
    private val SECRET = "s".repeat(43)
    private fun repo() = DashboardRepository(settings, secrets, link(), api(), cache, Clock { System.currentTimeMillis() }, direct, registry)

    private fun awaitState(r: DashboardRepository, timeoutMs: Long = 8000, cond: (DashState) -> Boolean): DashState {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond(r.state)) return r.state
            Thread.sleep(25)
        }
        throw AssertionError("condition not reached; state=${r.state.copy(snapshot = null)}")
    }

    @Test fun pingWorksWithoutPairing() {
        val r = link().ping()
        assertTrue(r is ApiResult.Ok)
        assertEquals("hubuddy", (r as ApiResult.Ok).json.getString("app"))
    }

    @Test fun pairingByInvitationStoresIdentityAndTokenAndWrongSecretFails() {
        val l = link()
        val bad = invitationText(TestPki.A, port, secret = "x".repeat(43))
        val failed = l.claimInvitation(Invitation.parse(bad), "test") as PairOutcome.Failed
        assertEquals(ErrKind.UNAUTHORIZED, failed.err.kind)
        assertFalse(l.paired)
        val ok = l.claimInvitation(Invitation.parse(invitationText(TestPki.A, port, secret = SECRET)), "test")
        assertTrue(ok is PairOutcome.Paired)
        assertTrue(l.paired)
        assertEquals(token, registry.token("pc1"))
        assertEquals(1, registry.list().size) // the empty placeholder was filled, not duplicated
        assertEquals(TestPki.A.hostId, registry.get("pc1")!!.hostId)
        assertEquals("127.0.0.1:$port", registry.get("pc1")!!.address)
    }

    @Test fun anInvitationForAnotherIdentityIsRejectedByTheClient() {
        // The server presents identity A, the invitation pins identity B: the TLS handshake must fail before any secret is sent.
        val r = link().claimInvitation(Invitation.parse(invitationText(TestPki.B, port, secret = SECRET)), "test") as PairOutcome.Failed
        assertEquals(ErrKind.UNTRUSTED, r.err.kind)
    }

    @Test fun probeReadsTheIdentityAndCompareCodeMatchesThePc() {
        val l = link()
        val r = l.probe("127.0.0.1:$port") as ApiResult.Ok
        val probed = l.toProbed("127.0.0.1:$port", r)!!
        assertEquals(TestPki.A.hostId, probed.hostId)
        assertEquals("TW8J-JSFW-3EC9", probed.compareCode) // value computed by the PC program's own code
    }

    @Test fun aPairedPcWhoseIdentityChangedIsNotTrusted() {
        registry.setToken("pc1", token)
        registry.update(registry.active().copy(hostId = TestPki.B.hostId, ca = TestPki.B.caB64))
        assertEquals(ErrKind.UNTRUSTED, (link().get("/api/snapshot") as ApiResult.Err).kind)
    }

    @Test fun unpairedRepositoryReportsNotPaired() {
        val r = repo(); r.start()
        val s = awaitState(r) { it.errorKind == ErrKind.NOT_PAIRED }
        assertFalse(s.online); assertFalse(s.paired)
        r.stop()
    }

    @Test fun dashboardLoadsAndIsCachedForOffline() {
        registry.setToken("pc1", token)
        val r = repo(); r.start()
        val s = awaitState(r) { it.online }
        assertEquals(42.0, s.section("stats")!!.getDouble("cpu"), 0.0)
        assertTrue(s.latencyMs >= 0)
        r.stop()
        // App restart while the PC is off: the cached dashboard is shown, marked as cached.
        server.stop()
        val r2 = repo()
        assertNotNull(r2.state.snapshot)
        assertTrue(r2.state.fromCache)
        assertFalse(r2.state.online)
        r2.start()
        val off = awaitState(r2) { it.errorKind == ErrKind.UNREACHABLE }
        assertTrue(off.fromCache); assertFalse(off.online); assertNotNull(off.snapshot)
        r2.stop()
    }

    @Test fun timeoutIsReportedNotThrown() {
        registry.setToken("pc1", token)
        delayMs = 2500
        val r = link().get("/api/snapshot")
        assertEquals(ErrKind.TIMEOUT, (r as ApiResult.Err).kind)
    }

    @Test fun reconnectsAfterOutage() {
        registry.setToken("pc1", token)
        up = false
        val r = repo(); r.start()
        val down = awaitState(r) { it.failures >= 1 }
        assertFalse(down.online)
        assertTrue(down.nextRetryAt > 0)
        up = true
        awaitState(r, 10_000) { it.online }
        assertEquals(0, r.state.failures)
        r.stop()
    }

    @Test fun badTokenIsUnauthorized() {
        registry.setToken("pc1", "wrong-token-wrong-token-wrong")
        assertEquals(ErrKind.UNAUTHORIZED, (link().get("/api/snapshot") as ApiResult.Err).kind)
    }

    @Test fun invalidOrPublicAddressRejectedBeforeAnyRequest() {
        registry.setToken("pc1", token)
        registry.update(registry.active().copy(address = "8.8.8.8:8765"))
        assertEquals(ErrKind.BAD_ADDRESS, (link().get("/api/snapshot") as ApiResult.Err).kind)
        registry.update(registry.active().copy(address = "not an address"))
        assertEquals(ErrKind.BAD_ADDRESS, (link().ping() as ApiResult.Err).kind)
    }

    @Test fun nothingListeningIsUnreachable() {
        val free = ServerSocket(0).use { it.localPort }
        registry.update(registry.active().copy(address = "127.0.0.1:$free"))
        assertEquals(ErrKind.UNREACHABLE, (link().ping() as ApiResult.Err).kind)
    }

    @Test fun commandsArePostedWithToken() {
        registry.setToken("pc1", token)
        assertTrue(link().post("/api/media", JSONObject().put("cmd", "toggle")) is ApiResult.Ok)
    }

    @Test fun backoffGrowsAndCaps() {
        assertEquals(2000, DashboardRepository.backoffMs(2, 1, ErrKind.UNREACHABLE))
        assertEquals(4000, DashboardRepository.backoffMs(2, 2, ErrKind.UNREACHABLE))
        assertEquals(60_000, DashboardRepository.backoffMs(5, 10, ErrKind.TIMEOUT))
        assertEquals(60_000, DashboardRepository.backoffMs(5, 1, ErrKind.UNAUTHORIZED))
    }

    @Test fun clearCacheRemovesSavedDashboard() {
        registry.setToken("pc1", token)
        val r = repo(); r.start(); awaitState(r) { it.online }; r.stop()
        r.clearCache()
        assertNull(repo().state.snapshot)
    }
}
