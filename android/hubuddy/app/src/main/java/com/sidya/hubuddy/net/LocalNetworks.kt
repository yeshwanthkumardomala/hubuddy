package com.sidya.hubuddy.net

import java.net.Inet4Address
import java.net.NetworkInterface

/** One usable IPv4 link of the phone: Wi-Fi, wired through an adapter, hotspot, or USB tethering. */
data class Link(val name: String, val address: String, val prefix: Int, val broadcast: String) {
    /** The other addresses on the same subnet (at most one /24 worth), used when a router blocks broadcasts. */
    fun neighbours(): List<String> {
        val a = ipToInt(address) ?: return emptyList()
        val p = prefix.coerceIn(24, 30)
        val mask = (-1 shl (32 - p))
        val base = a and mask
        val count = (1 shl (32 - p)) - 2
        return (1..count).map { intToIp(base + it) }.filter { it != address }
    }

    companion object {
        fun ipToInt(ip: String): Int? {
            val p = ip.split('.')
            if (p.size != 4) return null
            var v = 0
            for (x in p) v = (v shl 8) or (x.toIntOrNull()?.takeIf { it in 0..255 } ?: return null)
            return v
        }

        fun intToIp(v: Int) = "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}"

        /** Broadcast address for [address]/[prefix]. */
        fun broadcastOf(address: String, prefix: Int): String? =
            ipToInt(address)?.let { a -> intToIp(a or (-1 ushr prefix.coerceIn(0, 32)).let { if (prefix >= 32) 0 else it }) }
    }
}

object LocalNetworks {
    /** Interfaces that are up with a private IPv4 address. USB tethering (rndis, usb, ncm) simply appears here as another LAN. */
    fun links(): List<Link> = try {
        val out = ArrayList<Link>()
        for (ni in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
            if (!ni.isUp || ni.isLoopback) continue
            for (ia in ni.interfaceAddresses) {
                val a = ia.address as? Inet4Address ?: continue
                val host = a.hostAddress ?: continue
                if (!AddressValidator.isLocalHost(host)) continue
                val prefix = ia.networkPrefixLength.toInt().takeIf { it in 8..30 } ?: 24
                val b = Link.broadcastOf(host, prefix) ?: continue
                out.add(Link(ni.name, host, prefix, b))
            }
        }
        out
    } catch (e: Exception) { emptyList() }
}
