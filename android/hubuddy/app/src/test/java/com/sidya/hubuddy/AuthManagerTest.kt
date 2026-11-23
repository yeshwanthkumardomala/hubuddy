package com.sidya.hubuddy

import com.sidya.hubuddy.auth.AuthManager
import com.sidya.hubuddy.auth.AuthManager.ChangeResult
import com.sidya.hubuddy.auth.AuthManager.LoginResult
import com.sidya.hubuddy.auth.PinHasher
import com.sidya.hubuddy.auth.PinPolicy
import com.sidya.hubuddy.core.Clock
import com.sidya.hubuddy.core.MemoryKeyValue
import com.sidya.hubuddy.core.MemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthManagerTest {
    private var now = 1_700_000_000_000L
    private val clock = Clock { now }
    private val kv = MemoryKeyValue()
    private val secrets = MemorySecretStore()
    private val fast = PinHasher(iterations = 1000)
    private fun auth() = AuthManager(kv, secrets, clock, "123456789", "123456", fast).also { it.lockEnabled = true }

    @Test fun bootstrapLoginSucceedsAndRequiresPinChange() {
        val a = auth()
        assertEquals(LoginResult.Success(mustChangePin = true), a.login("123456789", "123456"))
        assertTrue(a.hasSession())
        assertFalse("dashboard locked until PIN changed", a.canAccessDashboard())
    }

    @Test fun pinNeverStoredInPlaintext() {
        auth()
        kv.map.values.forEach { assertFalse(it.toString().contains("123456\"")); assertNotEquals("123456", it) }
        assertTrue((kv.getString("auth.pinHash") ?: "").startsWith("pbkdf2-sha256$"))
    }

    @Test fun wrongPinAndWrongLoginRejected() {
        val a = auth()
        assertEquals(LoginResult.Invalid(4), a.login("123456789", "000000"))
        assertEquals(LoginResult.Invalid(3), a.login("987654321", "123456"))
        assertFalse(a.hasSession())
        assertEquals(LoginResult.Empty, a.login("", ""))
    }

    @Test fun lockoutAfterFiveFailuresThenExpires() {
        val a = auth()
        repeat(4) { assertTrue(a.login("123456789", "999999") is LoginResult.Invalid) }
        val locked = a.login("123456789", "999999")
        assertTrue(locked is LoginResult.LockedOut)
        // Even the right PIN is refused while locked.
        assertTrue(a.login("123456789", "123456") is LoginResult.LockedOut)
        now += AuthManager.BASE_LOCK_MS + 1
        assertTrue(a.login("123456789", "123456") is LoginResult.Success)
    }

    @Test fun lockoutGrowsAndPersistsAcrossRestart() {
        repeat(5) { auth().login("123456789", "1") }
        now += AuthManager.BASE_LOCK_MS + 1
        repeat(5) { auth().login("123456789", "1") }
        val second = auth().login("123456789", "123456") as LoginResult.LockedOut
        assertTrue(second.untilMs - now > AuthManager.BASE_LOCK_MS) // doubled
    }

    @Test fun changePinFlow() {
        val a = auth()
        a.login("123456789", "123456")
        assertEquals(ChangeResult.WrongCurrent, a.changePin("111111", "482916", "482916"))
        assertEquals(ChangeResult.Mismatch, a.changePin("123456", "482916", "482917"))
        assertTrue(a.changePin("123456", "123456", "123456") is ChangeResult.Weak)
        assertTrue(a.changePin("123456", "111111", "111111") is ChangeResult.Weak)
        assertTrue(a.changePin("123456", "234567", "234567") is ChangeResult.Weak)
        assertTrue(a.changePin("123456", "12a456", "12a456") is ChangeResult.Weak)
        assertEquals(ChangeResult.Ok, a.changePin("123456", "482916", "482916"))
        assertTrue(a.canAccessDashboard())
        a.logout()
        // The temporary PIN no longer works; the new one does, without another forced change.
        assertTrue(a.login("123456789", "123456") is LoginResult.Invalid)
        assertEquals(LoginResult.Success(mustChangePin = false), a.login("123456789", "482916"))
    }

    @Test fun changePinNeedsSession() {
        assertEquals(ChangeResult.NotLoggedIn, auth().changePin("123456", "482916", "482916"))
    }

    @Test fun authPersistsAcrossAppRestart() {
        auth().apply { login("123456789", "123456"); changePin("123456", "482916", "482916") }
        val restarted = auth()
        assertTrue(restarted.canAccessDashboard())
        assertFalse(restarted.mustChangePin)
    }

    @Test fun sessionTimesOutAfterInactivity() {
        val a = auth()
        a.login("123456789", "123456"); a.changePin("123456", "482916", "482916")
        a.sessionTimeoutMin = 5
        now += 4 * 60_000; a.touch(); assertTrue(a.canAccessDashboard())
        now += 4 * 60_000; assertTrue("activity extended the session", a.canAccessDashboard())
        now += 2 * 60_000; assertFalse(a.canAccessDashboard())
    }

    @Test fun logoutEndsSession() {
        val a = auth(); a.login("123456789", "123456"); a.logout(); assertFalse(a.hasSession())
    }

    @Test fun clockMovedBackDoesNotLockForever() {
        val a = auth()
        kv.putLong("auth.lockUntil", now + 365L * 24 * 3600_000)
        assertTrue(a.lockedUntil() <= now + AuthManager.MAX_LOCK_MS)
    }
}

class PinHasherTest {
    private val h = PinHasher(iterations = 1000)

    @Test fun verifiesAndSalts() {
        val a = h.hash("482916"); val b = h.hash("482916")
        assertNotEquals("random salt", a, b)
        assertTrue(h.verify("482916", a)); assertFalse(h.verify("482917", a))
    }

    @Test fun rejectsGarbage() {
        assertFalse(h.verify("1", null)); assertFalse(h.verify("1", "")); assertFalse(h.verify("", h.hash("482916"))); assertFalse(h.verify("1", "pbkdf2\$x\$y\$z"))
        assertFalse(h.verify("1", "md5\$1000\$AAAA\$BBBB")); assertFalse(h.verify("1", "pbkdf2\$10\$AAAA\$BBBB"))
    }

    @Test fun matchesJdkPbkdf2Sha256() {
        // Our portable implementation must equal the standard algorithm (JDK reference).
        val salt = ByteArray(16) { it.toByte() }
        for (pin in listOf("482916", "123456", "00000000")) {
            val ref = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, 2000, 256)).encoded
            assertTrue(ref.contentEquals(PinHasher.derive(pin, salt, 2000)))
        }
    }

    @Test fun rfc7914TestVector() {
        // RFC 7914 section 11: PBKDF2-HMAC-SHA256("passwd", "salt", c=1), first 32 bytes.
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc",
            PinHasher.hex(PinHasher.derive("passwd", "salt".toByteArray(), 1)))
    }

    @Test fun policy() {
        assertNull(PinPolicy.problem("482916", "123456"))
        assertNotNull(PinPolicy.problem("12345", "123456"))
        assertNotNull(PinPolicy.problem("123456789", "123456"))
        assertNotNull(PinPolicy.problem("987654", "123456"))
        assertNotNull(PinPolicy.problem("000000", "123456"))
    }
}
