package com.sidya.hubuddy.remote

import com.sidya.hubuddy.net.ApiResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Sends touchpad and keyboard events to the PC in order, one request at a time. While a request is in flight new movement
 * piles up and is merged, so the pointer stays smooth on slow Wi-Fi instead of lagging behind with a long queue.
 * While a mouse button is held it also sends a heartbeat, and the PC lets the button go when the heartbeat stops.
 */
class InputSender(
    private val post: (JSONObject) -> ApiResult,
    private val onError: (ApiResult.Err) -> Unit,
    private val heartbeatMs: Long = 500,
) {
    private val lock = ReentrantLock()
    private val wake = lock.newCondition()
    private val events = ArrayList<JSONObject>()
    private var mx = 0f
    private var my = 0f
    private var sx = 0f
    private var sy = 0f
    private val held = HashSet<String>()
    @Volatile private var running = true
    @Volatile private var lastErrorAt = 0L
    private val worker = Thread({ loop() }, "hubuddy-input").apply { isDaemon = true; start() }

    val holding: Boolean get() = lock.withLock { held.isNotEmpty() }

    private fun add(e: JSONObject) = lock.withLock { events.add(e); wake.signalAll() }

    /** Relative pointer movement in PC pixels; whole numbers are sent, the fraction is kept for next time. */
    fun move(dx: Float, dy: Float) = lock.withLock {
        mx += dx; my += dy
        val ix = mx.toInt(); val iy = my.toInt()
        if (ix != 0 || iy != 0) {
            mx -= ix; my -= iy
            val last = events.lastOrNull()
            if (last != null && last.optString("t") == "move") {
                last.put("dx", (last.getInt("dx") + ix).coerceIn(-2048, 2048)).put("dy", (last.getInt("dy") + iy).coerceIn(-2048, 2048))
            } else events.add(JSONObject().put("t", "move").put("dx", ix.coerceIn(-2048, 2048)).put("dy", iy.coerceIn(-2048, 2048)))
            wake.signalAll()
        }
    }

    /** Wheel units: positive scrolls up/left (content follows the finger). */
    fun scroll(dx: Float, dy: Float) = lock.withLock {
        sx += dx; sy += dy
        val ix = sx.toInt(); val iy = sy.toInt()
        if (ix != 0 || iy != 0) {
            sx -= ix; sy -= iy
            val last = events.lastOrNull()
            if (last != null && last.optString("t") == "scroll") {
                last.put("dx", (last.getInt("dx") + ix).coerceIn(-2048, 2048)).put("dy", (last.getInt("dy") + iy).coerceIn(-2048, 2048))
            } else events.add(JSONObject().put("t", "scroll").put("dx", ix.coerceIn(-2048, 2048)).put("dy", iy.coerceIn(-2048, 2048)))
            wake.signalAll()
        }
    }

    fun click(button: String, double: Boolean = false) = add(JSONObject().put("t", "click").put("button", button).put("double", double))
    fun zoom(steps: Int) { if (steps != 0) add(JSONObject().put("t", "zoom").put("steps", steps.coerceIn(-8, 8))) }
    fun key(key: String, mods: List<String> = emptyList()) = add(JSONObject().put("t", "key").put("key", key).put("mods", JSONArray(mods)))
    fun text(text: String) = add(JSONObject().put("t", "text").put("text", text.take(500)))

    fun button(button: String, down: Boolean) = lock.withLock {
        if (down) held.add(button) else held.remove(button)
        events.add(JSONObject().put("t", "button").put("button", button).put("down", down)); wake.signalAll()
    }

    /** Lets go of everything held (touch cancelled, screen left, app paused). */
    fun release() = lock.withLock {
        held.clear()
        events.add(JSONObject().put("t", "release")); wake.signalAll()
    }

    /** Stops the sender. A held button is released first so the PC is never left with one stuck. */
    fun close() {
        if (holding) release()
        lock.withLock { running = false; wake.signalAll() }
    }

    private fun loop() {
        while (true) {
            val batch: List<JSONObject>
            lock.withLock {
                while (running && events.isEmpty()) {
                    if (held.isNotEmpty()) {
                        wake.awaitNanos(heartbeatMs * 1_000_000)
                        if (events.isEmpty() && held.isNotEmpty()) events.add(JSONObject().put("t", "ping"))   // heartbeat while a button is held
                    } else wake.await()
                }
                if (events.isEmpty()) return   // closed, and everything (including the final release) has been sent
                batch = ArrayList(events.take(MAX_BATCH)); repeat(batch.size) { events.removeAt(0) }
            }
            val r = try { post(JSONObject().put("events", JSONArray(batch))) } catch (e: Exception) { ApiResult.Err(com.sidya.hubuddy.net.ErrKind.BAD_RESPONSE, "error") }
            if (r is ApiResult.Err) {
                val t = System.currentTimeMillis()
                if (r.code == 403 || t - lastErrorAt > 3000) { lastErrorAt = t; onError(r) }
                if (r.code == 403) lock.withLock { events.clear(); held.clear() }
            }
        }
    }

    companion object { const val MAX_BATCH = 60 }
}
