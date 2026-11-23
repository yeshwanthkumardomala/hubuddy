package com.sidya.hubuddy

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

/** Throw-away identities (see tools/make_test_pki.py) so tests run the real pinned-TLS client against a real TLS server. */
object TestPki {
    /**
     * Robolectric puts Conscrypt first in the provider list, and the JDK's TLS server cannot initialise its curve table
     * through it (the first class to touch it wins, so results would depend on test order). Hide it while the server side
     * is created; the phone-side client under test is unaffected.
     */
    @Synchronized fun <T> withJdkProviders(block: () -> T): T {
        val hidden = java.security.Security.getProviders().filter { it.name.equals("Conscrypt", true) }
        hidden.forEach { java.security.Security.removeProvider(it.name) }
        try { return block() } finally { hidden.forEachIndexed { i, p -> java.security.Security.insertProviderAt(p, i + 1) } }
    }

    class Identity(private val tag: String) {
        private fun res(name: String) = TestPki::class.java.getResource("/pki/$tag/$name")!!.readText()
        val hostId: String get() = res("host-id.txt").trim()
        val caB64: String get() = res("ca.b64").trim()
        val serverSocketFactory: SSLServerSocketFactory by lazy {
            val cf = CertificateFactory.getInstance("X.509")
            val leaf = cf.generateCertificate(ByteArrayInputStream(res("leaf.pem").toByteArray())) as X509Certificate
            val keyDer = Base64.getMimeDecoder().decode(res("key.pem").lines().filter { !it.startsWith("-----") }.joinToString(""))
            val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyDer))
            val ks = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry("server", key, "x".toCharArray(), arrayOf(leaf)) }
            val kmf = KeyManagerFactory.getInstance("SunX509", "SunJSSE").apply { init(ks, "x".toCharArray()) }
            SSLContext.getInstance("TLS", "SunJSSE").apply { init(kmf.keyManagers, null, null) }.serverSocketFactory
        }
    }

    val A = Identity("a")
    val B = Identity("b")
}
