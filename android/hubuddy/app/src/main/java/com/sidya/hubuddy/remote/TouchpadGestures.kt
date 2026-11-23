package com.sidya.hubuddy.remote

/**
 * Turns finger positions into touchpad actions: one finger moves the pointer, a short tap clicks, two fingers slide to scroll or
 * pinch to zoom, a two-finger tap is a right click, three or more fingers do nothing. A cancelled touch never clicks.
 *
 * The rules follow the touchpad design of the Tara project (MIT, see NOTICE), written here for Android.
 * Coordinates are in dp so the feel is the same on every screen; [now] is in milliseconds.
 */
class TouchpadGestures(
    private val move: (dx: Float, dy: Float) -> Unit,
    private val scroll: (dx: Float, dy: Float) -> Unit,
    private val zoom: (steps: Int) -> Unit,
    private val click: (button: String) -> Unit,
    private val dragging: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Point(var x: Float, var y: Float, val startX: Float, val startY: Float)
    private class Sequence(val start: Long) { var maximum = 0; var distance = 0f; var frozen = false; var cancelled = false }
    private class Geometry(val x: Float, val y: Float, val span: Float)
    private class Pair(val origin: Geometry) {
        var previous: Geometry = origin
        var anchor: Float = origin.span
        var mode: String? = null
        val changed = HashSet<Int>()
        var waiting: Long = -1
    }

    private val points = LinkedHashMap<Int, Point>()
    private var sequence: Sequence? = null
    private var pair: Pair? = null

    val activeFingers: Int get() = points.size

    fun reset() { points.clear(); sequence = null; pair = null }

    private fun geometry(): Geometry {
        val (a, b) = points.values.toList()
        return Geometry((a.x + b.x) / 2, (a.y + b.y) / 2, Math.max(20f, Math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()))
    }

    fun down(id: Int, x: Float, y: Float) {
        if (points.isEmpty()) sequence = Sequence(now())
        val s = sequence ?: return
        points[id] = Point(x, y, x, y)
        s.maximum = Math.max(s.maximum, points.size)
        if (points.size == 2) pair = Pair(geometry())
        if (points.size > 2) s.frozen = true
    }

    fun update(id: Int, x: Float, y: Float) {
        val p = points[id] ?: return
        val s = sequence ?: return
        val dx = x - p.x
        val dy = y - p.y
        s.distance = Math.max(s.distance, Math.hypot((x - p.startX).toDouble(), (y - p.startY).toDouble()).toFloat())
        p.x = x; p.y = y
        if (s.frozen) return
        if (points.size == 1) move(dx, dy)
        else if (points.size == 2 && (dx != 0f || dy != 0f)) pair?.changed?.add(id)
    }

    /**
     * Looks at both fingers together once per frame (events arrive one finger at a time, and judging each alone mistakes a
     * parallel slide for a pinch). Returns true when it wants to be called again shortly.
     */
    fun flush(): Boolean {
        val s = sequence ?: return false
        val pr = pair ?: return false
        if (points.size != 2 || s.frozen || dragging()) return false
        val cur = geometry()
        if (pr.mode == null) {
            val travel = Math.hypot((cur.x - pr.origin.x).toDouble(), (cur.y - pr.origin.y).toDouble()).toFloat()
            val stretch = Math.abs(cur.span - pr.origin.span)
            if (travel < SLOP_SCROLL && stretch < SLOP_ZOOM) return false
            if (pr.waiting < 0) pr.waiting = now()
            // A resting finger is a valid pinch, but let staggered events catch up first.
            if (pr.changed.size < 2 && now() - pr.waiting < STAGGER_MS) return true
            pr.mode = when {
                stretch >= SLOP_ZOOM && stretch > travel * 1.3f -> "zoom"
                travel >= SLOP_SCROLL && travel > stretch * 0.7f -> "scroll"
                else -> return false
            }
        }
        if (pr.mode == "scroll") {
            val dx = cur.x - pr.previous.x
            val dy = cur.y - pr.previous.y
            if (dx != 0f || dy != 0f) scroll(dx, dy)
        } else {
            val steps = Math.max(-8, Math.min(8, (Math.log((cur.span / pr.anchor).toDouble()) / Math.log(1.1)).toInt()))
            if (steps != 0) { zoom(steps); pr.anchor = cur.span }
        }
        pr.previous = cur
        return false
    }

    fun up(id: Int, cancelled: Boolean = false) {
        if (!cancelled) flush()
        if (points.remove(id) == null) return
        val s = sequence ?: return
        if (cancelled) s.cancelled = true
        if (points.isNotEmpty()) { s.frozen = true; return }
        if (!s.cancelled && pair?.mode == null && s.maximum <= 2 && now() - s.start < TAP_MS && s.distance < TAP_SLOP && !dragging())
            click(if (s.maximum == 2) "right" else "left")
        reset()
    }

    companion object {
        const val TAP_MS = 300L
        const val TAP_SLOP = 8f
        const val SLOP_SCROLL = 6f
        const val SLOP_ZOOM = 10f
        const val STAGGER_MS = 48L
    }
}
