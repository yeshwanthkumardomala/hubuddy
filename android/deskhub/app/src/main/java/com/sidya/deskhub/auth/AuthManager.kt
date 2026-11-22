package com.sidya.deskhub.auth

import com.sidya.deskhub.core.Clock
import com.sidya.deskhub.core.KeyValue
import com.sidya.deskhub.core.SecretStore
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Local app lock: login number + PIN, hashed with PBKDF2, failed-attempt lockout,
 * mandatory change of the temporary bootstrap PIN, and an inactivity session timeout.
 *
 * This protects the phone UI only. The PC companion has its own pairing token
 * (see ApiClient / companion-bridge) and never trusts this screen.
 */
class AuthManager(
    private val kv: KeyValue,
    private val secrets: SecretStore,
    private val clock: Clock,
    private val bootstrapLogin: String,
    private val bootstrapPin: String,
    private val hasher: PinHasher = PinHasher(),
) {
    sealed class LoginResult {
        data class Success(val mustChangePin: Boolean) : LoginResult()
        data class Invalid(val attemptsLeft: Int) : LoginResult()
        data class LockedOut(val untilMs: Long) : LoginResult()
        object Empty : LoginResult()
    }

    sealed class ChangeResult {
        object Ok : ChangeResult()
        object WrongCurrent : ChangeResult()
        object Mismatch : ChangeResult()
        object NotLoggedIn : ChangeResult()
        data class Weak(val reason: String) : ChangeResult()
    }

    init {
        if (kv.getString(K_HASH) == null) {
            // First run: seed with the temporary bootstrap credentials, change required.
            kv.putString(K_LOGIN, bootstrapLogin)
            kv.putString(K_HASH, hasher.hash(bootstrapPin))
            kv.putBoolean(K_MUST_CHANGE, true)
        }
    }

    var sessionTimeoutMin: Int
        get() = kv.getInt(K_TIMEOUT, DEFAULT_TIMEOUT_MIN).coerceIn(1, 240)
        set(v) = kv.putInt(K_TIMEOUT, v.coerceIn(1, 240))

    val mustChangePin: Boolean get() = kv.getBoolean(K_MUST_CHANGE, true)

    fun lockedUntil(): Long {
        val until = kv.getLong(K_LOCK_UNTIL, 0)
        val now = clock.now()
        // If the clock was moved backwards, never lock longer than the maximum.
        return if (until - now > MAX_LOCK_MS) now + MAX_LOCK_MS else until
    }

    fun login(login: String, pin: String): LoginResult {
        if (login.isBlank() || pin.isEmpty()) return LoginResult.Empty
        val now = clock.now()
        val until = lockedUntil()
        if (until > now) return LoginResult.LockedOut(until)

        val storedLogin = kv.getString(K_LOGIN) ?: bootstrapLogin
        val loginOk = MessageDigest.isEqual(login.trim().toByteArray(), storedLogin.toByteArray())
        // Always run the KDF so timing does not reveal whether the login number matched.
        val pinOk = hasher.verify(pin, kv.getString(K_HASH))
        if (!(loginOk && pinOk)) {
            val fails = kv.getInt(K_FAILS) + 1
            if (fails >= MAX_ATTEMPTS) {
                val level = kv.getInt(K_LOCK_LEVEL) + 1
                val lockMs = (BASE_LOCK_MS shl (level - 1).coerceAtMost(10)).coerceAtMost(MAX_LOCK_MS)
                kv.putInt(K_LOCK_LEVEL, level)
                kv.putLong(K_LOCK_UNTIL, now + lockMs)
                kv.putInt(K_FAILS, 0)
                return LoginResult.LockedOut(now + lockMs)
            }
            kv.putInt(K_FAILS, fails)
            return LoginResult.Invalid(MAX_ATTEMPTS - fails)
        }
        kv.putInt(K_FAILS, 0)
        kv.putInt(K_LOCK_LEVEL, 0)
        kv.putLong(K_LOCK_UNTIL, 0)
        startSession()
        return LoginResult.Success(mustChangePin)
    }

    fun changePin(current: String, newPin: String, confirm: String): ChangeResult {
        if (!hasSession()) return ChangeResult.NotLoggedIn
        if (!hasher.verify(current, kv.getString(K_HASH))) return ChangeResult.WrongCurrent
        if (newPin != confirm) return ChangeResult.Mismatch
        PinPolicy.problem(newPin, bootstrapPin)?.let { return ChangeResult.Weak(it) }
        if (newPin == current) return ChangeResult.Weak("New PIN must differ from the current PIN.")
        kv.putString(K_HASH, hasher.hash(newPin))
        kv.putBoolean(K_MUST_CHANGE, false)
        touch(force = true)
        return ChangeResult.Ok
    }

    /** Logged in and not idle too long (may still need the PIN change). */
    fun hasSession(): Boolean {
        val id = secrets.get(S_SESSION) ?: return false
        if (id.isEmpty()) return false
        val last = kv.getLong(K_LAST_ACTIVE, 0)
        val now = clock.now()
        if (last > now + 60_000) return false // clock went backwards: be safe
        return now - last < sessionTimeoutMin * 60_000L
    }

    fun canAccessDashboard(): Boolean = hasSession() && !mustChangePin

    /** Record activity; writes are throttled to keep flash wear low. */
    fun touch(force: Boolean = false) {
        if (!force && !hasSession()) return
        val now = clock.now()
        if (force || now - kv.getLong(K_LAST_ACTIVE, 0) > 10_000) kv.putLong(K_LAST_ACTIVE, now)
    }

    fun logout() {
        secrets.put(S_SESSION, null)
        kv.remove(K_LAST_ACTIVE)
    }

    private fun startSession() {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        secrets.put(S_SESSION, bytes.joinToString("") { "%02x".format(it) })
        touch(force = true)
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val BASE_LOCK_MS = 30_000L
        const val MAX_LOCK_MS = 15 * 60_000L
        const val DEFAULT_TIMEOUT_MIN = 15

        private const val K_LOGIN = "auth.login"
        private const val K_HASH = "auth.pinHash"
        private const val K_MUST_CHANGE = "auth.mustChange"
        private const val K_FAILS = "auth.fails"
        private const val K_LOCK_UNTIL = "auth.lockUntil"
        private const val K_LOCK_LEVEL = "auth.lockLevel"
        private const val K_LAST_ACTIVE = "session.lastActive"
        private const val K_TIMEOUT = "session.timeoutMin"
        private const val S_SESSION = "session.id"
    }
}
