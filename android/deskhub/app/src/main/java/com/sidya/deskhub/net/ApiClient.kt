package com.sidya.deskhub.net

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

enum class ErrKind { BAD_ADDRESS, NOT_PAIRED, UNAUTHORIZED, TIMEOUT, UNREACHABLE, HTTP, BAD_RESPONSE, RATE_LIMITED }

sealed class ApiResult {
    data class Ok(val json: JSONObject, val latencyMs: Long) : ApiResult()
    data class Err(val kind: ErrKind, val message: String, val code: Int = 0) : ApiResult()
}

/** Human-readable text for an error, shown in the UI. Never contains the token. */
fun ApiResult.Err.describe(): String = when (kind) {
    ErrKind.BAD_ADDRESS -> message
    ErrKind.NOT_PAIRED -> "Not paired with the PC companion. Pair in Settings."
    ErrKind.UNAUTHORIZED -> "Companion rejected the pairing token. Pair again in Settings."
    ErrKind.TIMEOUT -> "Companion did not answer in time."
    ErrKind.UNREACHABLE -> "Companion unreachable. Is it running? (USB: run adb reverse)"
    ErrKind.HTTP -> "Companion error (HTTP $code)."
    ErrKind.BAD_RESPONSE -> "Unexpected response from companion."
    ErrKind.RATE_LIMITED -> "Too many attempts. Wait a minute and try again."
}

/**
 * Small blocking JSON client over HttpURLConnection. Call from a background thread.
 * Generic over the host so it also serves Open-Meteo and GitHub (with auth=false).
 */
class ApiClient(
    private val connectTimeoutMs: () -> Int,
    private val readTimeoutMs: () -> Int,
) {
    fun request(
        url: String,
        method: String = "GET",
        body: JSONObject? = null,
        bearer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): ApiResult {
        val started = System.nanoTime()
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: MalformedURLException) {
            return ApiResult.Err(ErrKind.BAD_ADDRESS, "Invalid address.")
        } catch (e: IOException) {
            return ApiResult.Err(ErrKind.UNREACHABLE, "Cannot open connection.")
        }
        try {
            conn.connectTimeout = connectTimeoutMs()
            conn.readTimeout = readTimeoutMs()
            conn.requestMethod = method
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "HubuddyDeskHub/1.0")
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val latency = (System.nanoTime() - started) / 1_000_000
            if (code == 401 || code == 403) return ApiResult.Err(ErrKind.UNAUTHORIZED, "unauthorized", code)
            if (code == 429) return ApiResult.Err(ErrKind.RATE_LIMITED, "rate limited", code)
            if (code !in 200..299) {
                val msg = conn.errorStream?.let { readCapped(it) }?.let { safeError(it) } ?: ""
                return ApiResult.Err(ErrKind.HTTP, msg, code)
            }
            val text = readCapped(conn.inputStream)
            return try {
                ApiResult.Ok(JSONObject(text), latency)
            } catch (e: JSONException) {
                ApiResult.Err(ErrKind.BAD_RESPONSE, "Response was not JSON.")
            }
        } catch (e: SocketTimeoutException) {
            return ApiResult.Err(ErrKind.TIMEOUT, "timeout")
        } catch (e: ConnectException) {
            return ApiResult.Err(ErrKind.UNREACHABLE, "refused")
        } catch (e: NoRouteToHostException) {
            return ApiResult.Err(ErrKind.UNREACHABLE, "no route")
        } catch (e: UnknownHostException) {
            return ApiResult.Err(ErrKind.UNREACHABLE, "unknown host")
        } catch (e: IOException) {
            return ApiResult.Err(ErrKind.UNREACHABLE, e.javaClass.simpleName)
        } catch (e: SecurityException) {
            return ApiResult.Err(ErrKind.UNREACHABLE, "network permission")
        } catch (e: IllegalArgumentException) {
            return ApiResult.Err(ErrKind.BAD_ADDRESS, "Invalid address.")
        } finally {
            conn.disconnect()
        }
    }

    private fun safeError(text: String): String = try {
        JSONObject(text).optString("error").take(120)
    } catch (e: JSONException) {
        ""
    }

    private fun readCapped(input: InputStream): String = input.use { s ->
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val n = s.read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
            if (buf.size() > MAX_BYTES) throw IOException("response too large")
        }
        buf.toString("UTF-8")
    }

    companion object {
        const val MAX_BYTES = 1_000_000
    }
}
