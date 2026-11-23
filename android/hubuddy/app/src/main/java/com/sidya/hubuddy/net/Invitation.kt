package com.sidya.hubuddy.net

import org.json.JSONObject
import java.security.cert.X509Certificate

/** What the PC's QR code (or pasted text) says: which PC, where it is, the identity to pin, and a one-time secret. */
class Invitation(
    val hostId: String,
    val name: String,
    val caB64: String,
    val ca: X509Certificate,
    val port: Int,
    /** Addresses to try, best first. */
    val addresses: List<String>,
    val mac: String,
    val secret: String,
    val expires: Long,
) {
    class Invalid(message: String) : Exception(message)

    fun expired(now: Long = System.currentTimeMillis()) = expires <= now

    companion object {
        const val PREFIX = "hubuddy-pair:"
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val SECRET = Regex("[A-Za-z0-9_-]{32,64}")
        private val MAC = Regex("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}")

        /** Parses and fully validates an invitation. Throws [Invalid] with a message fit for the user. */
        fun parse(text: String, now: Long = System.currentTimeMillis()): Invitation {
            val t = text.trim()
            if (!t.startsWith(PREFIX) || t.length > 8192) throw Invalid("This is not a Hubuddy invitation.")
            val o = try {
                JSONObject(String(PinnedTls.decode(t.removePrefix(PREFIX)), Charsets.UTF_8))
            } catch (e: Exception) { throw Invalid("The invitation is damaged. Show a new one on the PC.") }
            if (o.optInt("v") != 1) throw Invalid("This invitation is from a different Hubuddy version. Update the app and the PC program.")
            val hostId = o.optString("hostId")
            if (!UUID.matches(hostId)) throw Invalid("The invitation does not name a valid PC.")
            val caB64 = o.optString("ca")
            val ca = PinnedTls.parseCa(caB64) ?: throw Invalid("The invitation has no valid PC identity.")
            val port = o.optInt("port", -1)
            if (port !in 1..65535) throw Invalid("The invitation has no valid port.")
            val secret = o.optString("secret")
            if (!SECRET.matches(secret)) throw Invalid("The invitation has no valid secret.")
            val expires = o.optLong("expires", 0)
            if (expires <= now) throw Invalid("This invitation has expired. Show a new one on the PC.")
            val raw = ArrayList<String>()
            o.optString("address").takeIf { it.isNotEmpty() }?.let(raw::add)
            o.optJSONArray("addresses")?.let { a -> for (i in 0 until minOf(a.length(), 6)) raw.add(a.optString(i)) }
            val addresses = raw.distinct().filter { AddressValidator.isLocalHost(it) }.map { "$it:$port" }
            if (addresses.isEmpty()) throw Invalid("The invitation points to an address outside your local network, so it was refused.")
            val mac = o.optString("mac").takeIf { MAC.matches(it) } ?: ""
            return Invitation(hostId, o.optString("name", "PC").take(24).ifBlank { "PC" }, caB64, ca, port, addresses, mac, secret, expires)
        }
    }
}
