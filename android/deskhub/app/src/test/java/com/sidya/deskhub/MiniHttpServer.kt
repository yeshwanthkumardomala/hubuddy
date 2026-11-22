package com.sidya.deskhub

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Just enough HTTP/1.1 for tests (the JDK's HttpServer is not on the Android test classpath). */
class MiniHttpServer(private val handler: (Request) -> Pair<Int, String>) {
    data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    @Volatile private var running = true

    fun start(): MiniHttpServer {
        thread(isDaemon = true, name = "mini-http") {
            while (running) {
                val s = try { socket.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { serve(s) }
            }
        }
        return this
    }

    private fun serve(s: Socket) = s.use {
        try {
            val input = BufferedInputStream(s.getInputStream())
            val head = ByteArrayOutputStream()
            var last4 = 0
            while (true) {
                val b = input.read(); if (b < 0) return
                head.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0D0A0D0A) break
            }
            val lines = head.toString("ISO-8859-1").split("\r\n")
            val (method, target) = lines[0].split(" ")
            val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(len).also { var off = 0; while (off < len) { val n = input.read(it, off, len - off); if (n < 0) break; off += n } }
            val (code, text) = handler(Request(method, target.substringBefore('?'), headers, String(body)))
            val bytes = text.toByteArray()
            val out = s.getOutputStream()
            out.write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(bytes); out.flush()
        } catch (e: Exception) { /* client went away (e.g. timeout test) */ }
    }

    fun stop() { running = false; runCatching { socket.close() } }
}
