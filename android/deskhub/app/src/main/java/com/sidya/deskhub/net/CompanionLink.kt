package com.sidya.deskhub.net

import com.sidya.deskhub.core.PcRegistry
import org.json.JSONObject

/** The authenticated link to the Hubuddy bridge on one of the user's PCs (the active one by default). */
class CompanionLink(
    private val registry: PcRegistry,
    private val api: ApiClient,
) {
    /** Pairing state of the active PC. */
    val paired: Boolean get() = registry.isPaired(registry.activeId)

    fun isPaired(pcId: String): Boolean = registry.isPaired(pcId)

    fun address(pcId: String = registry.activeId): AddressValidator.Result =
        AddressValidator.validate(registry.get(pcId)?.endpoint() ?: "")

    private fun url(pcId: String, path: String): Pair<String?, ApiResult.Err?> =
        when (val a = address(pcId)) {
            is AddressValidator.Result.Ok -> a.baseUrl + path to null
            is AddressValidator.Result.Error -> null to ApiResult.Err(ErrKind.BAD_ADDRESS, a.message)
        }

    /** Unauthenticated reachability check; reports nothing sensitive. */
    fun ping(pcId: String = registry.activeId): ApiResult {
        val (u, e) = url(pcId, "/api/ping"); if (e != null) return e
        return api.request(u!!)
    }

    /** Exchange the one-time pairing code shown on the PC for a device token. */
    fun pair(code: String, deviceName: String, pcId: String = registry.activeId): ApiResult {
        val clean = code.filter { it.isDigit() }
        if (clean.length != 6) return ApiResult.Err(ErrKind.BAD_ADDRESS, "The pairing code has 6 digits.")
        val (u, e) = url(pcId, "/api/pair"); if (e != null) return e
        val r = api.request(u!!, "POST", JSONObject().put("code", clean).put("device", deviceName.take(40)))
        if (r is ApiResult.Ok) {
            val token = r.json.optString("token")
            if (token.length < 20) return ApiResult.Err(ErrKind.BAD_RESPONSE, "No token in pairing reply.")
            registry.setToken(pcId, token)
        }
        return r
    }

    fun unpair(pcId: String = registry.activeId) = registry.setToken(pcId, null)

    fun get(path: String, pcId: String = registry.activeId): ApiResult = call(pcId, path, "GET", null)

    fun post(path: String, body: JSONObject, pcId: String = registry.activeId): ApiResult = call(pcId, path, "POST", body)

    private fun call(pcId: String, path: String, method: String, body: JSONObject?): ApiResult {
        val token = registry.token(pcId) ?: return ApiResult.Err(ErrKind.NOT_PAIRED, "not paired")
        val (u, e) = url(pcId, path); if (e != null) return e
        return api.request(u!!, method, body, bearer = token)
    }
}
