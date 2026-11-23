package com.sidya.hubuddy.net

/**
 * Validates the PC address. Only loopback, private LAN (including USB tethering),
 * link-local and mDNS ".local" hosts are accepted, so the app never sends the
 * pairing token to a host on the public internet.
 */
object AddressValidator {
    const val DEFAULT_PORT = 8765

    sealed class Result {
        data class Ok(val baseUrl: String, val host: String, val port: Int) : Result()
        data class Error(val message: String) : Result()
    }

    fun validate(raw: String): Result {
        var s = raw.trim()
        if (s.isEmpty()) return Result.Error("Enter the PC address, e.g. 192.168.1.20:8765")
        val scheme = "https" // the PC is only ever reached over its pinned, encrypted connection
        val sep = s.indexOf("://")
        if (sep >= 0) {
            val given = s.substring(0, sep).lowercase()
            s = s.substring(sep + 3)
            if (given != "https") return Result.Error("Hubuddy only connects securely: leave out the http:// part.")
        }
        s = s.trimEnd('/')
        if (s.contains('/') || s.contains('?') || s.contains('#') || s.contains('@') || s.contains(' '))
            return Result.Error("Enter only host and port (no path).")
        val host: String
        var port = DEFAULT_PORT
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return Result.Error("Invalid IPv6 address.")
            host = s.substring(1, end).lowercase()
            val rest = s.substring(end + 1)
            if (rest.isNotEmpty()) {
                if (!rest.startsWith(":")) return Result.Error("Invalid address.")
                port = rest.substring(1).toIntOrNull() ?: return Result.Error("Invalid port.")
            }
        } else {
            val colon = s.lastIndexOf(':')
            if (colon >= 0) {
                if (s.indexOf(':') != colon) return Result.Error("Put IPv6 addresses in brackets, e.g. [fe80::1]:8765")
                host = s.substring(0, colon).lowercase()
                port = s.substring(colon + 1).toIntOrNull() ?: return Result.Error("Invalid port.")
            } else host = s.lowercase()
        }
        if (port !in 1..65535) return Result.Error("Port must be 1-65535.")
        if (host.isEmpty()) return Result.Error("Missing host.")
        if (!isLocalHost(host)) return Result.Error(
            "Only local addresses are allowed (127.0.0.1, 10.x, 172.16-31.x, 192.168.x, *.local). " +
                "Never expose the agent to the internet."
        )
        val hostPart = if (host.contains(':')) "[$host]" else host
        return Result.Ok("$scheme://$hostPart:$port", host, port)
    }

    fun isLocalHost(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h == "::1") return true
        if (h.endsWith(".local") && h.length > 6 && h.all { it.isLetterOrDigit() || it == '-' || it == '.' }) return true
        if (h.startsWith("fe80:") || h.startsWith("fd") || h.startsWith("fc")) return h.contains(':')
        val p = h.split('.')
        if (p.size != 4) return false
        val o = p.map { part ->
            if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return false
            part.toInt().also { if (it > 255) return false }
        }
        return when {
            o[0] == 127 -> true
            o[0] == 10 -> true
            o[0] == 172 && o[1] in 16..31 -> true
            o[0] == 192 && o[1] == 168 -> true
            o[0] == 169 && o[1] == 254 -> true
            else -> false
        }
    }
}
