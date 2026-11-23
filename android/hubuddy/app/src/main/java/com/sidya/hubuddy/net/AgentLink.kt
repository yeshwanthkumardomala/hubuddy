package com.sidya.hubuddy.net

import com.sidya.hubuddy.core.PcProfile
import com.sidya.hubuddy.core.PcRegistry
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/** What a PC says about itself before pairing (PIN flow): shown to the user so the compare code can be checked. */
class ProbedPc(val hostId: String, val name: String, val caB64: String, val address: String) {
    val compareCode: String get() = PinnedTls.parseCa(caB64)?.let(PinnedTls::compareCode) ?: ""
}

sealed class PairOutcome {
    data class Paired(val pc: PcProfile) : PairOutcome()
    data class Failed(val err: ApiResult.Err) : PairOutcome()
}

/** The secure link to the Hubuddy programs on the user's PCs (the active one by default). */
class AgentLink(
    private val registry: PcRegistry,
    private val api: ApiClient,
    private val discovery: Discovery = Discovery(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val tlsCache = HashMap<String, PinnedTls>()
    private val relocateLock = Any()
    private var lastScan = 0L
    private var lastFound: Map<String, FoundPc> = emptyMap()

    val paired: Boolean get() = isPaired(registry.activeId)

    fun isPaired(pcId: String): Boolean = registry.isPaired(pcId) && registry.get(pcId)?.let { it.ca.isNotEmpty() && it.hostId.isNotEmpty() } == true

    private fun tlsFor(pc: PcProfile): PinnedTls? = synchronized(tlsCache) {
        val key = pc.hostId + pc.ca
        tlsCache[key] ?: PinnedTls.forPc(pc.ca, pc.hostId)?.also { tlsCache[key] = it }
    }

    private fun base(address: String): Pair<String?, ApiResult.Err?> = when (val a = AddressValidator.validate(address)) {
        is AddressValidator.Result.Ok -> a.baseUrl to null
        is AddressValidator.Result.Error -> null to ApiResult.Err(ErrKind.BAD_ADDRESS, a.message)
    }

    /** Checks that the paired PC answers and is still the same computer. */
    fun ping(pcId: String = registry.activeId): ApiResult = call(pcId, "/health", "GET", null, auth = false)

    /** First look at a PC by address (PIN flow). Nothing secret is sent; the identity it presents is returned for comparison. */
    fun probe(address: String): ApiResult {
        val (u, e) = base(address); if (e != null) return e
        val r = api.request(u!! + "/health", tls = PinnedTls.probe())
        return r
    }

    fun toProbed(address: String, r: ApiResult.Ok): ProbedPc? {
        val id = r.json.optString("hostId"); val ca = r.json.optString("ca")
        if (!Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(id) || PinnedTls.parseCa(ca) == null) return null
        return ProbedPc(id, r.json.optString("name", "PC").take(24).ifBlank { "PC" }, ca, address)
    }

    /** Pairs using a QR invitation. The invitation already carries the identity to trust. */
    fun claimInvitation(inv: Invitation, deviceName: String): PairOutcome {
        val tls = PinnedTls.forPc(inv.caB64, inv.hostId) ?: return failed(ErrKind.BAD_RESPONSE, "Invalid identity in the invitation.")
        var last: ApiResult.Err = ApiResult.Err(ErrKind.UNREACHABLE, "no address")
        for (addr in inv.addresses) {
            val (u, e) = base(addr); if (e != null) { last = e; continue }
            when (val r = api.request(u!! + "/api/pairing/claim", "POST",
                JSONObject().put("hostId", inv.hostId).put("secret", inv.secret).put("device", deviceName.take(40)), tls = tls)) {
                is ApiResult.Ok -> return finish(r, inv.hostId, inv.name, inv.caB64, addr, inv.mac)
                is ApiResult.Err -> { last = r; if (r.kind != ErrKind.UNREACHABLE && r.kind != ErrKind.TIMEOUT) return PairOutcome.Failed(r) }
            }
        }
        return PairOutcome.Failed(last)
    }

    /** Pairs with a PIN, after the user confirmed the compare code of [pc]. */
    fun claimPin(pc: ProbedPc, pin: String, deviceName: String): PairOutcome {
        val clean = pin.filter { it.isDigit() }
        if (clean.length != 6) return failed(ErrKind.BAD_ADDRESS, "The PIN has 6 digits.")
        val tls = PinnedTls.forPc(pc.caB64, pc.hostId) ?: return failed(ErrKind.BAD_RESPONSE, "Invalid identity.")
        val (u, e) = base(pc.address); if (e != null) return PairOutcome.Failed(e)
        return when (val r = api.request(u!! + "/api/pairing/pin-claim", "POST",
            JSONObject().put("hostId", pc.hostId).put("pin", clean).put("device", deviceName.take(40)), tls = tls)) {
            is ApiResult.Ok -> finish(r, pc.hostId, pc.name, pc.caB64, pc.address, "")
            is ApiResult.Err -> PairOutcome.Failed(r)
        }
    }

    private fun finish(r: ApiResult.Ok, hostId: String, name: String, ca: String, address: String, mac: String): PairOutcome {
        val token = r.json.optString("token")
        if (token.length < 20) return failed(ErrKind.BAD_RESPONSE, "No token in the reply.")
        val pc = registry.upsertPaired(hostId, name, ca, address, mac) ?: return failed(ErrKind.HTTP, "You can pair up to ${PcRegistry.MAX_PCS} PCs. Remove one first.")
        registry.setToken(pc.id, token)
        return PairOutcome.Paired(pc)
    }

    private fun failed(kind: ErrKind, msg: String) = PairOutcome.Failed(ApiResult.Err(kind, msg))

    fun unpair(pcId: String = registry.activeId) = registry.setToken(pcId, null)

    fun get(path: String, pcId: String = registry.activeId): ApiResult = call(pcId, path, "GET", null)

    fun post(path: String, body: JSONObject, pcId: String = registry.activeId): ApiResult = call(pcId, path, "POST", body)

    fun upload(name: String, input: InputStream, length: Long, pcId: String = registry.activeId): ApiResult =
        withPc(pcId) { pc, token, tls, u -> api.upload(u + "/api/files/upload?name=" + java.net.URLEncoder.encode(name, "UTF-8"), input, length, token, tls) }

    fun download(name: String, out: OutputStream, pcId: String = registry.activeId): ApiResult =
        withPc(pcId) { _, token, tls, u -> api.download(u + "/api/files/download?name=" + java.net.URLEncoder.encode(name, "UTF-8"), out, token, tls) }

    private fun withPc(pcId: String, block: (PcProfile, String, PinnedTls?, String) -> ApiResult): ApiResult {
        val pc = registry.get(pcId) ?: return ApiResult.Err(ErrKind.NOT_PAIRED, "not paired")
        val token = registry.token(pcId)?.takeIf { pc.ca.isNotEmpty() } ?: return ApiResult.Err(ErrKind.NOT_PAIRED, "not paired")
        val (u, e) = base(pc.address); if (e != null) return e
        return block(pc, token, tlsFor(pc), u!!)
    }

    private fun call(pcId: String, path: String, method: String, body: JSONObject?, auth: Boolean = true): ApiResult {
        val pc = registry.get(pcId) ?: return ApiResult.Err(ErrKind.NOT_PAIRED, "not paired")
        val token = if (auth) registry.token(pcId) else null
        if (pc.ca.isEmpty() || (auth && token == null)) return ApiResult.Err(ErrKind.NOT_PAIRED, "not paired")
        val tls = tlsFor(pc) ?: return ApiResult.Err(ErrKind.NOT_PAIRED, "not paired")
        val (u, e) = base(pc.address)
        var r: ApiResult = if (e != null) e else api.request(u!! + path, method, body, bearer = token, tls = tls)
        if (r is ApiResult.Err && (r.kind == ErrKind.UNREACHABLE || r.kind == ErrKind.TIMEOUT || r.kind == ErrKind.BAD_ADDRESS)) {
            val moved = relocate(pc)
            if (moved != null && moved != pc.address) {
                registry.update(registry.get(pcId)?.copy(address = moved) ?: pc.copy(address = moved))
                val (u2, e2) = base(moved)
                r = if (e2 != null) e2 else api.request(u2!! + path, method, body, bearer = token, tls = tls)
            }
        }
        return r
    }

    /** Finds a PC again by its permanent identity when its address changed. Shared by all PCs so a scan runs at most every 20 s. */
    private fun relocate(pc: PcProfile): String? {
        if (pc.hostId.isEmpty()) return null
        synchronized(relocateLock) {
            val now = clock()
            if (now - lastScan > RESCAN_MS) {
                lastFound = discovery.find(scan = true).associateBy { it.hostId }
                lastScan = clock()
            }
            return lastFound[pc.hostId]?.endpoint
        }
    }

    companion object { const val RESCAN_MS = 20_000L }
}
