package com.sidya.hubuddy.net

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** A PC that answered the search. */
data class FoundPc(val hostId: String, val name: String, val address: String, val port: Int) {
    val endpoint get() = "$address:$port"
}

/**
 * Finds Hubuddy PCs on the networks the phone is on. The PC answers a tiny UDP probe with its name and port; the reply
 * holds nothing secret. Broadcast first; when a router drops broadcasts, the same probe goes to every neighbour address.
 */
class Discovery(
    private val links: () -> List<Link> = LocalNetworks::links,
    private val port: Int = PORT,
    /** Extra explicit targets (tests use loopback). */
    private val extraTargets: () -> List<String> = { emptyList() },
) {
    fun find(scan: Boolean, listenMs: Int = 1500): List<FoundPc> {
        val socket = try { DatagramSocket().apply { broadcast = true; soTimeout = 200 } } catch (e: Exception) { return emptyList() }
        socket.use { s ->
            val probe = PROBE.toByteArray()
            val ls = links()
            val targets = LinkedHashSet<String>()
            ls.forEach { targets.add(it.broadcast) }
            if (ls.isNotEmpty()) targets.add("255.255.255.255")
            targets.addAll(extraTargets())
            if (scan) ls.take(3).forEach { targets.addAll(it.neighbours()) }
            if (targets.isEmpty()) return emptyList()
            for (t in targets) {
                try { s.send(DatagramPacket(probe, probe.size, InetAddress.getByName(t), port)) } catch (e: Exception) { /* unreachable link */ }
            }
            val found = LinkedHashMap<String, FoundPc>()
            val end = System.currentTimeMillis() + listenMs + (if (scan) 500 else 0)
            val buf = ByteArray(1024)
            while (System.currentTimeMillis() < end) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (e: SocketTimeoutException) { continue } catch (e: Exception) { break }
                parse(String(p.data, 0, p.length, Charsets.UTF_8), p.address.hostAddress ?: continue)?.let { if (!found.containsKey(it.hostId)) found[it.hostId] = it }
            }
            return found.values.toList()
        }
    }

    companion object {
        const val PORT = 41236
        const val PROBE = "hubuddy:discover:v1"
        const val REPLY = "hubuddy:here:v1:"
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** A reply as a [FoundPc], or null for anything that is not a well-formed answer from a local address. */
        fun parse(text: String, source: String): FoundPc? {
            if (!text.startsWith(REPLY) || text.length > 500 || !AddressValidator.isLocalHost(source)) return null
            return try {
                val o = JSONObject(text.removePrefix(REPLY))
                val id = o.optString("hostId")
                val port = o.optInt("port", -1)
                if (o.optString("app") != "hubuddy" || !UUID.matches(id) || port !in 1..65535) null
                else FoundPc(id, o.optString("name", "PC").filter { it.isLetterOrDigit() || it in " -_.()" }.take(24).ifBlank { "PC" }, source, port)
            } catch (e: Exception) { null }
        }
    }
}
