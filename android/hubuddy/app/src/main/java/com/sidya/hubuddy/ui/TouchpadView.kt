package com.sidya.hubuddy.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.sidya.hubuddy.remote.TouchpadGestures

/**
 * The touchpad surface. It only reports touches to [TouchpadGestures]; it draws a soft circle under each finger so the
 * user can see that touches register. Touch coordinates are converted to dp so the feel does not depend on the screen.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class TouchpadView(ctx: Context, private val gestures: TouchpadGestures, private val onCancel: () -> Unit) : View(ctx) {
    private val density = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (Pal.accent and 0x00FFFFFF) or 0x55000000; style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.accent; style = Paint.Style.STROKE; strokeWidth = 2f * density }
    private val touches = HashMap<Int, FloatArray>()
    private val frame = Runnable { tick() }

    init { isClickable = true; isFocusable = false }

    private fun tick() { if (gestures.flush()) postOnAnimation(frame) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val i = e.actionIndex
                gestures.down(e.getPointerId(i), e.getX(i) / density, e.getY(i) / density)
                touches[e.getPointerId(i)] = floatArrayOf(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    gestures.update(id, e.getX(i) / density, e.getY(i) / density)
                    touches[id]?.let { it[0] = e.getX(i); it[1] = e.getY(i) }
                }
                if (gestures.activeFingers == 2) { removeCallbacks(frame); postOnAnimation(frame); tick() }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val i = e.actionIndex
                gestures.update(e.getPointerId(i), e.getX(i) / density, e.getY(i) / density)
                gestures.up(e.getPointerId(i))
                touches.remove(e.getPointerId(i))
                if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
            MotionEvent.ACTION_CANCEL -> { gestures.reset(); touches.clear(); onCancel() }
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** Called when the screen is left: forget fingers, never click. */
    fun cancelTouches() { gestures.reset(); touches.clear(); invalidate() }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        for ((_, t) in touches) { c.drawCircle(t[0], t[1], 28f * density, paint); c.drawCircle(t[0], t[1], 28f * density, ring) }
    }
}
