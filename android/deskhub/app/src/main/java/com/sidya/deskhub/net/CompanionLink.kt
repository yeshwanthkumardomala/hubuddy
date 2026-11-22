package com.sidya.deskhub.net

import com.sidya.deskhub.core.SecretStore
import com.sidya.deskhub.core.Settings
import org.json.JSONObject

/** The authenticated link to the PC companion bridge (see companion-bridge/). */
class CompanionLink(
    private val settings: Settings,
    private val secrets: SecretStore,
    private val api: ApiClient,
) {
    val paired: Boolean get() = !secrets.get(TOKEN).isNullOrEmpty()

    fun address(): AddressValidator.Result = AddressValidator.validate(settings.companionAddress())

    private fun url(path: String): Pair<String?, ApiResult.Err?> =
        when (val a = address()) {
            is AddressValidator.Result.Ok -> a.baseUrl + path to null
            is AddressValidator.Result.Error -> null to ApiResult.Err(ErrKind.BAD_ADDRESS, a.message)
        }

    /** Unauthenticated reachability check; reports nothing sensitive. */
    fun ping(): ApiResult {
        val (u, e) = url("/api/ping"); if (e != null) return e
        return api.request(u!!)
    }

    /** Exchange the one-time pairing code shown on the PC for a device token. */
    fun pair(code: String, deviceName: String): ApiResult {
        val clean = code.filter { it.isDigit() }
        if (clean.length != 6) return ApiResult.Err(ErrKind.BAD_ADDRESS, "The pairing code has 6 digits.")
        val (u, e) = url("/api/pair"); if (e != null) return e
        val r = api.request(u!!, "POST", JSONObject().put("code", clean).put("device", deviceName.take(40)))
        if (r is ApiResult.Ok) {
            val token = r.json.optString("token")
            if (token.length < 20) return ApiResult.Err(ErrKind.BAD_RESPONSE, "No token in pairing reply.")
            secrets.put(TOKEN, token)
        }
        return r
    }

    fun unpair() = secrets.put(TOKEN, null)

    fun get(path: String): ApiResult = call(path, "GET", null)

    fun post(path: String, body: JSONObject): ApiResult = call(path, "POST", body)

    private fun call(path: String, method: String, body: JSONObject?): ApiResult {
        val token = secrets.get(TOKEN) ?: return ApiResult.Err(ErrKind.NOT_PAIRED, "not paired")
        val (u, e) = url(path); if (e != null) return e
        return api.request(u!!, method, body, bearer = token)
    }

    companion object {
        const val TOKEN = "companion.token"
    }
}
