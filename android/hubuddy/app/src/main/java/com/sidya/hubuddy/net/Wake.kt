package com.sidya.hubuddy.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Wake-on-LAN: the magic packet is sent from the phone on its own Wi-Fi/LAN, so it works only on the same network. */
object Wake {
    private val MAC = Regex("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}")

    /** The 102-byte magic packet for [mac], or null when it is not a MAC address. */
    fun packet(mac: String): ByteArray? {
        if (!MAC.matches(mac)) return null
        val bytes = mac.split(':', '-').map { it.toInt(16).toByte() }.toByteArray()
        return ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { bytes[it % 6] }
    }

    /** Sends the packet to every local broadcast address. Returns how many networks it was sent on. */
    fun send(mac: String, links: List<Link> = LocalNetworks.links()): Int {
        val pkt = packet(mac) ?: return 0
        var n = 0
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                (links.map { it.broadcast } + "255.255.255.255").distinct().forEach { b ->
                    try { s.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName(b), 9)); n++ } catch (e: Exception) { }
                }
            }
        } catch (e: Exception) { }
        return n
    }
}
