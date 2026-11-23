package com.sidya.deskhub.ui.desk

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.sidya.deskhub.ui.Pal

private fun Context.dpf(v: Float) = v * resources.displayMetrics.density
private fun Context.spf(v: Float) = v * resources.displayMetrics.scaledDensity

/** Colour for a usage percentage: teal, amber at 75 %, red at 90 %. */
fun levelColor(pct: Double?): Int = when {
    pct == null -> Pal.dim
    pct >= 90 -> Pal.bad
    pct >= 75 -> Pal.warn
    else -> Pal.accent
}

/** DeskHub-style ring gauge: 270° arc, big value in the middle, label underneath. A null value reads "n/a". */
@SuppressLint("ViewConstructor") // created from code only
class GaugeView(ctx: Context, var value: Double?, var label: String, var sub: String = "") : View(ctx) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Pal.surface2 }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.text; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.dim; textAlign = Paint.Align.CENTER }
    private val box = RectF()

    init { contentDescription = "$label ${value?.let { "%.0f percent".format(it) } ?: "unavailable"}" }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val size = minOf(w, h * 1.05f)
        val stroke = size * 0.085f
        track.strokeWidth = stroke; arc.strokeWidth = stroke
        val r = size / 2 - stroke
        val cx = w / 2; val cy = r + stroke + context.dpf(2f)
        box.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(box, 135f, 270f, false, track)
        val v = value
        if (v != null) {
            arc.color = levelColor(v)
            c.drawArc(box, 135f, 270f * (v.coerceIn(0.0, 100.0) / 100.0).toFloat(), false, arc)
        }
        big.textSize = r * 0.62f
        big.color = if (v == null) Pal.dim else Pal.text
        c.drawText(if (v == null) "n/a" else "%.0f%%".format(v), cx, cy + big.textSize * 0.35f, big)
        small.textSize = minOf(context.spf(13f), r * 0.34f)
        c.drawText(label.uppercase(), cx, cy + r * 0.78f, small.apply { color = Pal.dim; isFakeBoldText = true })
        if (sub.isNotEmpty()) {
            small.isFakeBoldText = false
            c.drawText(sub, cx, cy + r + stroke + small.textSize * 0.9f, small.apply { color = Pal.text })
        }
    }
}

/** Line chart of the last N values (0..max), with a soft fill. Negative values are gaps. */
@SuppressLint("ViewConstructor") // created from code only
class SparkView(ctx: Context, var values: List<Double>, var max: Double? = null, var color: Int = Pal.accent) : View(ctx) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(2f); strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val grid = Paint().apply { color = Pal.surface2; strokeWidth = ctx.dpf(1f) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.dim; textSize = ctx.spf(11f) }
    private val path = Path()
    private val area = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        for (i in 0..2) c.drawLine(0f, h * i / 2f, w, h * i / 2f, grid)
        if (values.size < 2) {
            c.drawText("collecting…", context.dpf(4f), h / 2 - context.dpf(4f), label)
            return
        }
        val top = max ?: (values.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
        val step = w / (values.size - 1)
        path.reset(); area.reset()
        var started = false; var firstX = 0f; var lastX = 0f
        values.forEachIndexed { i, v ->
            if (v < 0) return@forEachIndexed
            val x = i * step; val y = h - (v / top).toFloat().coerceIn(0f, 1f) * (h - line.strokeWidth) - line.strokeWidth / 2
            if (!started) { path.moveTo(x, y); area.moveTo(x, h); area.lineTo(x, y); firstX = x; started = true }
            else { path.lineTo(x, y); area.lineTo(x, y) }
            lastX = x
        }
        if (!started) return
        area.lineTo(lastX, h); area.lineTo(firstX, h); area.close()
        fill.color = (color and 0x00FFFFFF) or 0x33000000
        line.color = color
        c.drawPath(area, fill); c.drawPath(path, line)
    }
}

/** Vertical bars with the last bar highlighted (7-day token chart). */
@SuppressLint("ViewConstructor") // created from code only
class BarsView(ctx: Context, var values: List<Long>, var labels: List<String> = emptyList()) : View(ctx) {
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.dim; textAlign = Paint.Align.CENTER; textSize = ctx.spf(10f) }
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        if (values.isEmpty()) return
        val w = width.toFloat(); val h = height.toFloat()
        val labelH = if (labels.isEmpty()) 0f else text.textSize * 1.4f
        val max = (values.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
        val slot = w / values.size; val bw = slot * 0.6f
        values.forEachIndexed { i, v ->
            val bh = ((h - labelH) * v / max).coerceAtLeast(context.dpf(2f))
            val x = i * slot + (slot - bw) / 2
            r.set(x, h - labelH - bh, x + bw, h - labelH)
            bar.color = if (i == values.lastIndex) Pal.accent else Pal.walnut
            c.drawRoundRect(r, context.dpf(3f), context.dpf(3f), bar)
            labels.getOrNull(i)?.let { c.drawText(it, x + bw / 2, h - text.textSize * 0.2f, text) }
        }
    }
}

/** GitHub-style contribution heatmap from DeskHub's `levels` string (one digit 0-4 per day). */
@SuppressLint("ViewConstructor") // created from code only
class HeatmapView(ctx: Context, private val levels: String, private val startWday: Int, private val maxWeeks: Int = 53) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val shades = intArrayOf(Pal.surface2, 0xFF0E4D47.toInt(), 0xFF167A70.toInt(), 0xFF22A094.toInt(), Pal.accent)

    override fun onDraw(c: Canvas) {
        if (levels.isEmpty()) return
        val total = levels.length + startWday
        val weeks = (total + 6) / 7
        val shownWeeks = minOf(weeks, maxWeeks)
        val firstWeek = weeks - shownWeeks
        val cell = minOf(width.toFloat() / shownWeeks, height.toFloat() / 7f)
        val gap = cell * 0.18f
        for (i in levels.indices) {
            val idx = i + startWday
            val col = idx / 7 - firstWeek
            if (col < 0) continue
            val row = idx % 7
            p.color = shades[(levels[i] - '0').coerceIn(0, 4)]
            r.set(col * cell, row * cell, col * cell + cell - gap, row * cell + cell - gap)
            c.drawRoundRect(r, gap, gap, p)
        }
    }
}
