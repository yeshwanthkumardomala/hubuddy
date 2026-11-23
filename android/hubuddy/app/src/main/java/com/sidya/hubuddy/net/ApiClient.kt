package com.sidya.hubuddy.net

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
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

enum class ErrKind { BAD_ADDRESS, NOT_PAIRED, UNAUTHORIZED, TIMEOUT, UNREACHABLE, HTTP, BAD_RESPONSE, RATE_LIMITED, UNTRUSTED }

sealed class ApiResult {
    data class Ok(val json: JSONObject, val latencyMs: Long) : ApiResult()
    data class Err(val kind: ErrKind, val message: String, val code: Int = 0) : ApiResult()
}

/** Human-readable text for an error, shown in the UI. Never contains the token. */
fun ApiResult.Err.describe(): String = when (kind) {
    ErrKind.BAD_ADDRESS -> message
    ErrKind.NOT_PAIRED -> "Not paired with this PC. Pair in Settings."
    ErrKind.UNAUTHORIZED -> if (message == "pairing") "The invitation or PIN is wrong or has expired. Start pairing again on the PC." else "This PC no longer trusts this phone. Pair again in Settings."
    ErrKind.TIMEOUT -> "The PC did not answer in time."
    ErrKind.UNREACHABLE -> "Cannot reach the PC. Is Hubuddy running there, on the same Wi-Fi or LAN (or USB tethering)?"
    ErrKind.HTTP -> if (message.isNotEmpty()) message else "The PC reported an error (HTTP $code)."
    ErrKind.BAD_RESPONSE -> "Unexpected response from the PC."
    ErrKind.RATE_LIMITED -> "Too many attempts. Wait a minute and try again."
    ErrKind.UNTRUSTED -> "This computer's identity does not match the one you paired with. If you reinstalled Hubuddy there, pair again."
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
        tls: PinnedTls? = null,
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
            if (conn is HttpsURLConnection && tls != null) { conn.sslSocketFactory = tls.socketFactory; conn.hostnameVerifier = tls.hostnameVerifier }
            conn.connectTimeout = connectTimeoutMs()
            conn.readTimeout = readTimeoutMs()
            conn.requestMethod = method
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "Hubuddy/1.0")
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
            if (code == 401 || code == 403) return ApiResult.Err(ErrKind.UNAUTHORIZED, if (bearer == null) "pairing" else "unauthorized", code)
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
        } catch (e: SSLException) {
            return ApiResult.Err(ErrKind.UNTRUSTED, "untrusted")
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

    /** Sends a file as the request body. Streams from [input], so large files never sit in memory. */
    fun upload(url: String, input: InputStream, length: Long, bearer: String, tls: PinnedTls?): ApiResult {
        val conn = try { URL(url).openConnection() as HttpURLConnection } catch (e: Exception) { return ApiResult.Err(ErrKind.BAD_ADDRESS, "Invalid address.") }
        try {
            if (conn is HttpsURLConnection && tls != null) { conn.sslSocketFactory = tls.socketFactory; conn.hostnameVerifier = tls.hostnameVerifier }
            conn.connectTimeout = connectTimeoutMs(); conn.readTimeout = 60_000
            conn.requestMethod = "PUT"; conn.useCaches = false; conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $bearer")
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setFixedLengthStreamingMode(length)
            conn.outputStream.use { out -> input.use { it.copyTo(out, 16384) } }
            val code = conn.responseCode
            if (code == 401 || code == 403) return ApiResult.Err(ErrKind.UNAUTHORIZED, "unauthorized", code)
            if (code !in 200..299) return ApiResult.Err(ErrKind.HTTP, conn.errorStream?.let { readCapped(it) }?.let { safeError(it) } ?: "", code)
            return ApiResult.Ok(try { JSONObject(readCapped(conn.inputStream)) } catch (e: JSONException) { JSONObject() }, 0)
        } catch (e: SocketTimeoutException) { return ApiResult.Err(ErrKind.TIMEOUT, "timeout")
        } catch (e: SSLException) { return ApiResult.Err(ErrKind.UNTRUSTED, "untrusted")
        } catch (e: IOException) { return ApiResult.Err(ErrKind.UNREACHABLE, e.javaClass.simpleName)
        } finally { conn.disconnect() }
    }

    /** Copies a file from the PC into [out]. */
    fun download(url: String, out: java.io.OutputStream, bearer: String, tls: PinnedTls?, maxBytes: Long = 2_000_000_000L): ApiResult {
        val conn = try { URL(url).openConnection() as HttpURLConnection } catch (e: Exception) { return ApiResult.Err(ErrKind.BAD_ADDRESS, "Invalid address.") }
        try {
            if (conn is HttpsURLConnection && tls != null) { conn.sslSocketFactory = tls.socketFactory; conn.hostnameVerifier = tls.hostnameVerifier }
            conn.connectTimeout = connectTimeoutMs(); conn.readTimeout = 60_000
            conn.useCaches = false
            conn.setRequestProperty("Authorization", "Bearer $bearer")
            val code = conn.responseCode
            if (code == 401 || code == 403) return ApiResult.Err(ErrKind.UNAUTHORIZED, "unauthorized", code)
            if (code !in 200..299) return ApiResult.Err(ErrKind.HTTP, conn.errorStream?.let { readCapped(it) }?.let { safeError(it) } ?: "", code)
            var total = 0L
            conn.inputStream.use { s ->
                val buf = ByteArray(16384)
                while (true) {
                    val n = s.read(buf); if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IOException("file too large")
                    out.write(buf, 0, n)
                }
            }
            return ApiResult.Ok(JSONObject().put("bytes", total), 0)
        } catch (e: SocketTimeoutException) { return ApiResult.Err(ErrKind.TIMEOUT, "timeout")
        } catch (e: SSLException) { return ApiResult.Err(ErrKind.UNTRUSTED, "untrusted")
        } catch (e: IOException) { return ApiResult.Err(ErrKind.UNREACHABLE, e.javaClass.simpleName)
        } finally { conn.disconnect() }
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
