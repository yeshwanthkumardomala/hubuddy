package com.sidya.deskhub

import com.sidya.deskhub.core.Clock
import com.sidya.deskhub.core.MemoryKeyValue
import com.sidya.deskhub.core.MemorySecretStore
import com.sidya.deskhub.core.PcRegistry
import com.sidya.deskhub.core.Settings
import com.sidya.deskhub.data.DashState
import com.sidya.deskhub.data.DashboardRepository
import com.sidya.deskhub.net.ApiClient
import com.sidya.deskhub.net.ApiResult
import com.sidya.deskhub.net.CompanionLink
import com.sidya.deskhub.net.ErrKind
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

/** Exercises the real HTTP client and poller against a local fake companion. */
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
                path == "/api/ping" -> 200 to """{"ok":true,"service":"hubuddy-bridge","version":"1.0.0"}"""
                path == "/api/pair" -> {
                    if (JSONObject(req.body).optString("code") == "424242") 200 to """{"token":"$token"}""" else 401 to """{"error":"bad code"}"""
                }
                auth != "Bearer $token" -> 401 to """{"error":"no"}"""
                path == "/api/snapshot" -> 200 to """{"stats":{"cpu":42.0},"pc":{"online":true}}"""
                path == "/api/media" -> 200 to """{"ok":true}"""
                else -> 404 to "{}"
            }
        }.start()
        port = server.port
        registry.update(registry.active().copy(mode = "usb", usbPort = port)); settings.timeoutSec = 1; settings.refreshSec = 2
    }

    @After fun tearDown() { server.stop(); cache.deleteRecursively() }

    private fun api() = ApiClient({ settings.timeoutSec * 1000 }, { settings.timeoutSec * 1000 })
    private fun link() = CompanionLink(registry, api())
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
        assertEquals("hubuddy-bridge", (r as ApiResult.Ok).json.getString("service"))
    }

    @Test fun pairingStoresTokenAndWrongCodeFails() {
        val l = link()
        assertEquals(ErrKind.UNAUTHORIZED, (l.pair("111111", "test") as ApiResult.Err).kind)
        assertFalse(l.paired)
        assertTrue(l.pair("42-42-42", "test") is ApiResult.Ok)
        assertTrue(l.paired)
        assertEquals(token, registry.token("pc1"))
        assertEquals(ErrKind.BAD_ADDRESS, (l.pair("12", "x") as ApiResult.Err).kind)
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
        registry.update(registry.active().copy(mode = "wifi", address = "8.8.8.8:8765"))
        assertEquals(ErrKind.BAD_ADDRESS, (link().get("/api/snapshot") as ApiResult.Err).kind)
        registry.update(registry.active().copy(address = "not an address"))
        assertEquals(ErrKind.BAD_ADDRESS, (link().ping() as ApiResult.Err).kind)
    }

    @Test fun nothingListeningIsUnreachable() {
        val free = ServerSocket(0).use { it.localPort }
        registry.update(registry.active().copy(usbPort = free))
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
