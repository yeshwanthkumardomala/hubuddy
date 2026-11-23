package com.sidya.hubuddy.auth

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PBKDF2-HMAC-SHA256 (RFC 8018) with a random 16-byte salt, stored as
 * "pbkdf2-sha256$<iterations>$<salt hex>$<hash hex>".
 *
 * Implemented on top of HmacSHA256 because the platform's "PBKDF2WithHmacSHA256"
 * and java.util.Base64 only exist from Android 8; this works on Android 5+.
 */
class PinHasher(private val iterations: Int = DEFAULT_ITERATIONS) {
    private val rng = SecureRandom()

    fun hash(pin: String): String {
        require(pin.isNotEmpty())
        val salt = ByteArray(16).also { rng.nextBytes(it) }
        return "$PREFIX$" + iterations + "$" + hex(salt) + "$" + hex(derive(pin, salt, iterations))
    }

    fun verify(pin: String, stored: String?): Boolean {
        if (stored == null || pin.isEmpty()) return false
        val parts = stored.split("$")
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iters = parts[1].toIntOrNull() ?: return false
        if (iters < 1000 || iters > 2_000_000) return false
        val salt = unhex(parts[2]) ?: return false
        val expected = unhex(parts[3]) ?: return false
        if (salt.isEmpty() || expected.size != 32) return false
        return MessageDigest.isEqual(derive(pin, salt, iters), expected)
    }

    companion object {
        /** Roughly 0.3-1 s on older phones; slows brute force of a stolen prefs file. */
        const val DEFAULT_ITERATIONS = 60_000
        private const val PREFIX = "pbkdf2-sha256"

        /** PBKDF2-HMAC-SHA256 with a 32-byte output (a single block). */
        fun derive(pin: String, salt: ByteArray, iters: Int): ByteArray {
            val key = pin.toByteArray(Charsets.UTF_8)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            key.fill(0)
            mac.update(salt)
            mac.update(byteArrayOf(0, 0, 0, 1))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (n in 1 until iters) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            return t
        }

        fun hex(b: ByteArray): String {
            val sb = StringBuilder(b.size * 2)
            for (x in b) { sb.append(HEX[(x.toInt() shr 4) and 15]); sb.append(HEX[x.toInt() and 15]) }
            return sb.toString()
        }

        fun unhex(s: String): ByteArray? {
            if (s.length % 2 != 0) return null
            val out = ByteArray(s.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(s[2 * i], 16); val lo = Character.digit(s[2 * i + 1], 16)
                if (hi < 0 || lo < 0) return null
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }

        private const val HEX = "0123456789abcdef"
    }
}
