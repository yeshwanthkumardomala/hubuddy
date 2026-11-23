package com.sidya.hubuddy

import com.sidya.hubuddy.net.ApiResult
import com.sidya.hubuddy.net.ErrKind
import com.sidya.hubuddy.remote.InputSender
import com.sidya.hubuddy.remote.TouchpadGestures
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TouchpadGesturesTest {
    private var t = 0L
    private val moves = ArrayList<Pair<Float, Float>>()
    private val scrolls = ArrayList<Pair<Float, Float>>()
    private val zooms = ArrayList<Int>()
    private val clicks = ArrayList<String>()
    private var drag = false
    private val g = TouchpadGestures({ x, y -> moves.add(x to y) }, { x, y -> scrolls.add(x to y) }, { zooms.add(it) }, { clicks.add(it) }, { drag }, { t })

    /** Moves two fingers together in small steps, letting the staggered-event wait pass. */
    private fun twoFingers(ax: Float, ay: Float, bx: Float, by: Float, steps: Int, dax: Float, day: Float, dbx: Float, dby: Float) {
        g.down(1, ax, ay); g.down(2, bx, by)
        repeat(steps) { i ->
            t += 16
            g.update(1, ax + dax * (i + 1), ay + day * (i + 1)); g.update(2, bx + dbx * (i + 1), by + dby * (i + 1))
            if (g.flush()) { t += 60; g.flush() }
        }
    }

    @Test fun oneFingerMovesThePointerAndDoesNotClick() {
        g.down(1, 50f, 50f); t += 20; g.update(1, 60f, 55f); g.update(1, 80f, 70f); g.up(1)
        assertEquals(listOf(10f to 5f, 20f to 15f), moves)
        assertTrue(clicks.isEmpty())
    }

    @Test fun aShortTapClicksLeft() {
        g.down(1, 50f, 50f); t += 120; g.up(1)
        assertEquals(listOf("left"), clicks)
    }

    @Test fun aLongPressOrALargeMovementIsNotAClick() {
        g.down(1, 0f, 0f); t += 400; g.up(1); assertTrue(clicks.isEmpty())
        g.down(1, 0f, 0f); t += 100; g.update(1, 9f, 0f); g.up(1); assertTrue("moved past the tap slop", clicks.isEmpty())
        g.down(1, 0f, 0f); t += 100; g.update(1, 6f, 0f); g.up(1); assertEquals(listOf("left"), clicks)
    }

    @Test fun aTwoFingerTapIsARightClick() {
        g.down(1, 40f, 50f); g.down(2, 100f, 50f); t += 100; g.up(1); g.up(2)
        assertEquals(listOf("right"), clicks)
        assertTrue(moves.isEmpty() && scrolls.isEmpty())
    }

    @Test fun twoFingersSlidingTogetherScrollAndNeverClickOrZoom() {
        twoFingers(40f, 50f, 100f, 50f, steps = 6, dax = 0f, day = 10f, dbx = 0f, dby = 10f)
        g.up(1); g.up(2)
        assertTrue(zooms.isEmpty())
        assertTrue(clicks.isEmpty())
        assertTrue(scrolls.isNotEmpty())
        assertEquals(60f, scrolls.sumOf { it.second.toDouble() }.toFloat(), 12f)   // follows the fingers (the first slop is absorbed)
        assertEquals(0f, scrolls.sumOf { it.first.toDouble() }.toFloat(), 0.01f)
        assertTrue("one finger's movement was not used as pointer movement", moves.isEmpty())
    }

    @Test fun pinchingOutZoomsInAndPinchingInZoomsOut() {
        twoFingers(100f, 100f, 140f, 100f, steps = 8, dax = -6f, day = 0f, dbx = 6f, dby = 0f)
        assertTrue(zooms.isNotEmpty() && zooms.all { it > 0 }); assertTrue(scrolls.isEmpty())
        g.up(1); g.up(2); zooms.clear()
        twoFingers(40f, 100f, 200f, 100f, steps = 8, dax = 6f, day = 0f, dbx = -6f, dby = 0f)
        assertTrue(zooms.isNotEmpty() && zooms.all { it < 0 })
        assertTrue(clicks.isEmpty())
    }

    @Test fun threeFingersDoNothing() {
        g.down(1, 10f, 10f); g.down(2, 60f, 10f); g.down(3, 110f, 10f)
        g.update(1, 10f, 80f); g.update(2, 60f, 80f); g.update(3, 110f, 80f); g.flush()
        g.up(1); g.up(2); g.up(3)
        assertTrue(moves.isEmpty() && scrolls.isEmpty() && zooms.isEmpty() && clicks.isEmpty())
    }

    @Test fun aCancelledTouchNeverClicks() {
        g.down(1, 10f, 10f); t += 50; g.up(1, cancelled = true)
        assertTrue(clicks.isEmpty())
        g.down(1, 10f, 10f); t += 50; g.reset(); assertEquals(0, g.activeFingers)
    }

    @Test fun whileDraggingTapsDoNotClickAndTwoFingersDoNotScroll() {
        drag = true
        g.down(1, 10f, 10f); t += 50; g.up(1); assertTrue(clicks.isEmpty())
        twoFingers(40f, 50f, 100f, 50f, 5, 0f, 10f, 0f, 10f)
        assertTrue(scrolls.isEmpty())
    }

    @Test fun staggeredFingerEventsAreGivenAMomentToCatchUp() {
        g.down(1, 40f, 50f); g.down(2, 100f, 50f)
        t += 16; g.update(1, 40f, 70f)         // only one finger reported so far
        assertTrue("asks to be called again", g.flush())
        assertTrue(scrolls.isEmpty())
        g.update(2, 100f, 70f)                 // the other arrives
        assertFalse(g.flush())
        assertTrue(scrolls.isNotEmpty())
    }
}

class InputSenderTest {
    private val posted = CopyOnWriteArrayList<List<JSONObject>>()
    private val errors = CopyOnWriteArrayList<ApiResult.Err>()
    private fun events(body: JSONObject) = (0 until body.getJSONArray("events").length()).map { body.getJSONArray("events").getJSONObject(it) }
    private fun sender(gate: CountDownLatch? = null, answer: () -> ApiResult = { ApiResult.Ok(JSONObject(), 1) }, hb: Long = 40) =
        InputSender(post = { b -> gate?.await(2, TimeUnit.SECONDS); posted.add(events(b)); answer() }, onError = { errors.add(it) }, heartbeatMs = hb)

    private fun waitFor(ms: Long = 3000, c: () -> Boolean) { val end = System.currentTimeMillis() + ms; while (!c() && System.currentTimeMillis() < end) Thread.sleep(10); assertTrue("timed out", c()) }
    private fun all() = posted.flatten()

    @Test fun movementWhileARequestIsInFlightIsMergedNotQueued() {
        val gate = CountDownLatch(1)
        val s = sender(gate)
        s.move(1f, 1f)                                 // goes out and blocks
        Thread.sleep(80)
        repeat(50) { s.move(2f, 1f) }                  // piles up behind it
        gate.countDown()
        waitFor { all().sumOf { it.optInt("dx") } == 101 }
        assertTrue("merged into very few events", all().filter { it.getString("t") == "move" }.size <= 3)
        assertEquals(51, all().sumOf { it.optInt("dy") })
        s.close()
    }

    @Test fun fractionsAreCarriedNotLost() {
        val s = sender()
        repeat(10) { s.move(0.3f, 0f) }
        waitFor { all().isNotEmpty() && all().sumOf { it.optInt("dx") } == 3 }
        s.close()
    }

    @Test fun eventsStayInOrder() {
        val gate = CountDownLatch(1)
        val s = sender(gate)
        s.button("left", true); s.move(5f, 0f); s.scroll(0f, 3f); s.button("left", false); s.click("right")
        gate.countDown()
        waitFor { all().size >= 5 }
        assertEquals(listOf("button", "move", "scroll", "button", "click"), all().take(5).map { it.getString("t") })
        assertEquals(listOf(true, false), all().filter { it.getString("t") == "button" }.map { it.getBoolean("down") })
        s.close()
    }

    @Test fun aHeldButtonSendsAHeartbeatAndTheFinalReleaseIsSentOnClose() {
        val s = sender()
        s.button("left", true)
        waitFor { all().count { it.getString("t") == "ping" } >= 3 }
        assertTrue(s.holding)
        s.close()
        Thread.sleep(150)
        assertEquals("release", all().last { it.getString("t") != "ping" }.getString("t"))
        val after = all().size
        Thread.sleep(150)
        assertEquals("nothing is sent after close", after, all().size)
    }

    @Test fun noHeartbeatWhenNothingIsHeld() {
        val s = sender()
        s.move(3f, 0f); Thread.sleep(250)
        assertTrue(all().none { it.getString("t") == "ping" })
        s.close()
    }

    @Test fun forbiddenStopsEverythingAndIsReportedImmediately() {
        val s = sender(answer = { ApiResult.Err(ErrKind.UNAUTHORIZED, "no", 403) })
        s.button("left", true)
        waitFor { errors.isNotEmpty() }
        assertEquals(403, errors[0].code)
        Thread.sleep(150)
        assertFalse("held state cleared so no heartbeat is sent", s.holding)
        s.close()
    }

    @Test fun repeatedErrorsAreReportedOnlyOccasionally() {
        val s = sender(answer = { ApiResult.Err(ErrKind.UNREACHABLE, "x") })
        repeat(6) { s.click("left"); Thread.sleep(30) }
        waitFor { posted.size >= 6 }
        assertEquals(1, errors.size)
        s.close()
    }
}
