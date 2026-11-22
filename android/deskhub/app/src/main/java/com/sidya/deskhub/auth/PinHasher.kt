package com.sidya.deskhub.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PBKDF2-HMAC-SHA256 with a random 16-byte salt. Stored as "pbkdf2$<iterations>$<salt>$<hash>". */
class PinHasher(private val iterations: Int = DEFAULT_ITERATIONS) {
    private val rng = SecureRandom()

    fun hash(pin: String): String {
        val salt = ByteArray(16).also { rng.nextBytes(it) }
        val b64 = Base64.getEncoder()
        return "pbkdf2$" + iterations + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(derive(pin, salt, iterations))
    }

    fun verify(pin: String, stored: String?): Boolean {
        if (stored == null) return false
        val parts = stored.split("$")
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iters = parts[1].toIntOrNull() ?: return false
        if (iters < 1000 || iters > 2_000_000) return false
        return try {
            val d = Base64.getDecoder()
            MessageDigest.isEqual(derive(pin, d.decode(parts[2]), iters), d.decode(parts[3]))
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun derive(pin: String, salt: ByteArray, iters: Int): ByteArray {
        val chars = pin.toCharArray()
        val spec = PBEKeySpec(chars, salt, iters, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    companion object {
        /** ~0.3-0.6 s on a 2019 MediaTek phone; slows brute force of a stolen prefs file. */
        const val DEFAULT_ITERATIONS = 60_000
    }
}
