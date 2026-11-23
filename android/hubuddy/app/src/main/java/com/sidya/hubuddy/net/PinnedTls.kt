package com.sidya.hubuddy.net

import android.util.Base64
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Trust for exactly one PC. Each PC has its own private certificate authority; the phone learns it from the QR
 * invitation (or from the PC while the user compares a code) and from then on accepts only a server certificate that
 * is signed by that authority and carries that PC's signed name. Nothing else, including the phone's own list of
 * trusted authorities, is consulted, and the connection is made by IP address so no name lookup is involved.
 */
class PinnedTls private constructor(private val pinnedCa: X509Certificate?, val hostId: String) {
    val ca: X509Certificate get() = pinnedCa ?: throw IllegalStateException("probe connection has no CA")

    val socketFactory: SSLSocketFactory by lazy {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(trustManager), null)
        ctx.socketFactory
    }

    /** The name is checked inside the trust manager, so the generic host-name check can accept (the host is an IP). */
    val hostnameVerifier = HostnameVerifier { _, _ -> true }

    // Deliberate: trust is exactly one pinned CA plus a signed PC name, never the phone's list of authorities.
    @android.annotation.SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("not a server")
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val ca = pinnedCa ?: return // probe connection (see probe())
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            leaf.checkValidity()
            ca.checkValidity()
            leaf.verify(ca.publicKey)
            val names = leaf.subjectAlternativeNames.orEmpty().filter { it.size >= 2 && it[0] == 2 }.map { it[1].toString() }
            if (tlsName(hostId) !in names) throw CertificateException("certificate is not for this PC")
        }
    }

    companion object {
        fun tlsName(hostId: String) = "host-$hostId.hubuddy.invalid"

        fun decode(b64url: String): ByteArray =
            Base64.decode(b64url, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        /** The CA certificate carried by an invitation or a discovery reply, or null when it is not a usable CA. */
        fun parseCa(b64url: String): X509Certificate? = try {
            if (b64url.length > 4000) null else {
                val c = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(decode(b64url))) as X509Certificate
                if (c.basicConstraints < 0) null else c
            }
        } catch (e: Exception) { null }

        /** Pins [ca] for the PC [hostId]. Null when the CA is not valid. */
        fun forPc(caB64: String, hostId: String): PinnedTls? = parseCa(caB64)?.let { PinnedTls(it, hostId) }

        /**
         * Only for the first look at a PC during PIN pairing: reads its identity so the user can compare the code with
         * the one on the PC. No token or PIN is ever sent over a connection made this way.
         */
        fun probe(): PinnedTls = PinnedTls(null, "")

        /** XXXX-XXXX-XXXX, the same short fingerprint the PC shows (60 bits of the SHA-256 of the CA's public key). */
        fun compareCode(ca: X509Certificate): String {
            val h = MessageDigest.getInstance("SHA-256").digest(ca.publicKey.encoded)
            var bits = 0L
            for (i in 0 until 8) bits = (bits shl 8) or (h[i].toLong() and 0xff)
            bits = bits ushr 4
            val chars = CharArray(12)
            for (i in 11 downTo 0) { chars[i] = ALPHABET[(bits and 31).toInt()]; bits = bits ushr 5 }
            val s = String(chars)
            return "${s.substring(0, 4)}-${s.substring(4, 8)}-${s.substring(8)}"
        }

        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    }
}
