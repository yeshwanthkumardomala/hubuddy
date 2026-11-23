package com.sidya.deskhub.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Colour palette with four looks: "dark" (charcoal), "black" (pure black), "light" and "auto"
 * (follows the phone's dark-mode setting). Values are read at view-build time, so changing the
 * theme only needs the screens to be rebuilt.
 */
object Pal {
    var bg = 0xFF0E0F11.toInt()
    var surface = 0xFF1A1C1F.toInt()
    var surface2 = 0xFF24272B.toInt()
    var walnut = 0xFF8A5A3B.toInt()
    var walnutDim = 0xFF5C3D29.toInt()
    var accent = 0xFF2EC4B6.toInt()
    var onAccent = Color.BLACK
    var text = 0xFFF2F3F5.toInt()
    var dim = 0xFF9AA0A6.toInt()
    var ok = 0xFF3DDC84.toInt()
    var warn = 0xFFF4B400.toInt()
    var bad = 0xFFEF5350.toInt()
    var isLight = false
    /** The concrete look in use: "dark", "black" or "light". */
    var current = "dark"

    fun resolve(theme: String, ctx: Context?): String {
        if (theme != "auto") return if (theme == "black" || theme == "light") theme else "dark"
        val night = ctx != null &&
            (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (night) "dark" else "light"
    }

    fun apply(theme: String, ctx: Context? = null): String {
        val look = resolve(theme, ctx)
        isLight = look == "light"
        current = look
        when (look) {
            "light" -> {
                bg = 0xFFF2F3F5.toInt(); surface = 0xFFFFFFFF.toInt(); surface2 = 0xFFE6E8EB.toInt()
                walnut = 0xFF8A5A3B.toInt(); walnutDim = 0xFFDCCFC4.toInt()
                accent = 0xFF0B8379.toInt(); onAccent = Color.WHITE
                text = 0xFF15181B.toInt(); dim = 0xFF5B626A.toInt()
                ok = 0xFF18864F.toInt(); warn = 0xFF9A5B00.toInt(); bad = 0xFFC62828.toInt()
            }
            "black" -> darkBase(Color.BLACK, 0xFF111214.toInt(), 0xFF1B1D20.toInt())
            else -> darkBase(0xFF0E0F11.toInt(), 0xFF1A1C1F.toInt(), 0xFF24272B.toInt())
        }
        return look
    }

    private fun darkBase(b: Int, s1: Int, s2: Int) {
        bg = b; surface = s1; surface2 = s2
        walnut = 0xFF8A5A3B.toInt(); walnutDim = 0xFF5C3D29.toInt()
        accent = 0xFF2EC4B6.toInt(); onAccent = Color.BLACK
        text = 0xFFF2F3F5.toInt(); dim = 0xFF9AA0A6.toInt()
        ok = 0xFF3DDC84.toInt(); warn = 0xFFF4B400.toInt(); bad = 0xFFEF5350.toInt()
    }

    /** Status/navigation bar colours and icon contrast to match the current look. */
    @Suppress("DEPRECATION")
    fun styleWindow(a: android.app.Activity) {
        val w = a.window
        w.statusBarColor = bg
        w.navigationBarColor = bg
        if (android.os.Build.VERSION.SDK_INT < 23) {
            // Dark status-bar icons need Android 6. Before that, keep the icons light and give the bar a dark shade.
            if (isLight) w.statusBarColor = 0xFF4A4F55.toInt()
            return
        }
        if (isLight && android.os.Build.VERSION.SDK_INT < 26) w.navigationBarColor = 0xFF4A4F55.toInt() // no dark nav icons before Android 8
        var flags = w.decorView.systemUiVisibility
        var light = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (android.os.Build.VERSION.SDK_INT >= 26) light = light or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        flags = if (isLight) flags or light else flags and light.inv()
        w.decorView.systemUiVisibility = flags
    }

    /** WCAG contrast ratio between two opaque colours. */
    fun contrast(a: Int, b: Int): Double {
        fun lum(c: Int): Double {
            fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch((c shr 16) and 255) + 0.7152 * ch((c shr 8) and 255) + 0.0722 * ch(c and 255)
        }
        val l1 = lum(a); val l2 = lum(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }
}

/** Tiny view-building helpers. Programmatic views keep the APK small and startup fast. */
class Ui(val ctx: Context) {
    fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun rounded(color: Int, radiusDp: Int = 12, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    fun vbox(padDp: Int = 0, block: LinearLayout.() -> Unit = {}): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
            block()
        }

    fun hbox(block: LinearLayout.() -> Unit = {}): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            block()
        }

    fun text(s: CharSequence, sizeSp: Float = 14f, color: Int = Pal.text, bold: Boolean = false, tag: String? = null): TextView =
        TextView(ctx).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            if (tag != null) this.tag = tag
        }

    fun mono(s: CharSequence, sizeSp: Float, color: Int = Pal.text): TextView =
        text(s, sizeSp, color).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }

    /** A compact card with an optional title; walnut hairline at the top as the brand accent. */
    fun card(title: String? = null, tag: String? = null, onClick: (() -> Unit)? = null, block: LinearLayout.() -> Unit = {}): LinearLayout {
        val c = vbox(12) {
            background = rounded(Pal.surface, 12, Pal.walnutDim)
            if (title != null) {
                addView(hbox {
                    addView(View(ctx).apply {
                        background = rounded(Pal.walnut, 2)
                        layoutParams = LinearLayout.LayoutParams(dp(3), dp(14)).apply { rightMargin = dp(8) }
                    })
                    addView(text(title.uppercase(), 11f, Pal.dim, bold = true).apply { letterSpacing = 0.08f })
                    if (onClick != null) addView(text("  ›", 14f, Pal.dim))
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            }
            block()
        }
        c.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        if (tag != null) c.tag = tag
        if (onClick != null) {
            c.isClickable = true
            c.setOnClickListener { onClick() }
        }
        return c
    }

    fun row(label: String, value: CharSequence, valueColor: Int = Pal.text): LinearLayout = hbox {
        addView(text(label, 13f, Pal.dim), LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(value, 13f, valueColor).apply { gravity = Gravity.END })
        setPadding(0, dp(3), 0, dp(3))
    }

    /** Labeled usage bar. A null percentage renders as "unavailable", never a fake value. */
    fun meter(label: String, pct: Double?, detail: String? = null): LinearLayout = vbox {
        addView(hbox {
            addView(text(label, 13f, Pal.dim), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(if (pct == null) "unavailable" else "%.0f%%".format(pct) + (detail?.let { "  $it" } ?: ""),
                13f, if (pct == null) Pal.dim else Pal.text))
        })
        addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = (pct ?: 0.0).toInt().coerceIn(0, 100)
            isIndeterminate = false
            progressTintList = ColorStateList.valueOf(barColor(pct))
            progressBackgroundTintList = ColorStateList.valueOf(Pal.surface2)
            alpha = if (pct == null) 0.35f else 1f
        }, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(3); bottomMargin = dp(6) })
    }

    fun barColor(pct: Double?): Int = when {
        pct == null -> Pal.dim
        pct >= 90 -> Pal.bad
        pct >= 75 -> Pal.warn
        else -> Pal.accent
    }

    fun button(label: String, primary: Boolean = true, tag: String? = null, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            setTextColor(if (primary) Pal.onAccent else Pal.text)
            background = rounded(if (primary) Pal.accent else Pal.surface2, 10)
            stateListAnimator = null
            minHeight = dp(44)
            setPadding(dp(14), 0, dp(14), 0)
            if (tag != null) this.tag = tag
            setOnClickListener { onClick() }
        }

    fun input(hint: String, tag: String? = null, kind: Int = InputType.TYPE_CLASS_TEXT, value: String = ""): EditText =
        EditText(ctx).apply {
            this.hint = hint
            // setSingleLine() replaces the transformation method, so it must run BEFORE
            // inputType; otherwise password/PIN fields would be shown in clear text.
            setSingleLine(kind and InputType.TYPE_TEXT_FLAG_MULTI_LINE == 0)
            inputType = kind
            setText(value)
            setTextColor(Pal.text)
            setHintTextColor(Pal.dim)
            background = rounded(Pal.surface2, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            if (tag != null) this.tag = tag
        }

    /** Compact reading for dense tiles: small label, big value, thin bar. A null value reads "n/a". */
    fun miniStat(label: String, pct: Double?, tag: String? = null): LinearLayout = vbox {
        if (tag != null) this.tag = tag
        addView(text(label.uppercase(), 9.5f, Pal.dim, bold = true))
        addView(text(if (pct == null) "n/a" else "%.0f%%".format(pct), 17f, if (pct == null) Pal.dim else Pal.text))
        addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = (pct ?: 0.0).toInt().coerceIn(0, 100); isIndeterminate = false
            progressTintList = ColorStateList.valueOf(barColor(pct))
            progressBackgroundTintList = ColorStateList.valueOf(Pal.surface2)
            alpha = if (pct == null) 0.35f else 1f
        }, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(2) })
    }

    fun icon(res: Int, tint: Int, sizeDp: Int = 22): android.widget.ImageView = android.widget.ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** A small rounded selector chip (PC picker, filters). */
    fun chip(label: String, selected: Boolean, tag: String? = null, onClick: () -> Unit): TextView =
        text(label, 13f, if (selected) Pal.onAccent else Pal.text, bold = selected).apply {
            background = rounded(if (selected) Pal.accent else Pal.surface2, 16)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(14), dp(7), dp(14), dp(7))
            isClickable = true
            if (tag != null) this.tag = tag
            setOnClickListener { onClick() }
        }

    fun dot(color: Int): View = View(ctx).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(6) }
    }

    fun spacer(hDp: Int): View = View(ctx).apply { layoutParams = ViewGroup.LayoutParams(1, dp(hDp)) }

    fun lp(w: Int = -1, h: Int = -2, weight: Float = 0f, top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
}

object Fmt {
    fun kbps(kb: Double?): String = when {
        kb == null || kb.isNaN() -> "-"
        kb >= 1024 -> "%.1f MB/s".format(kb / 1024)
        else -> "%.0f KB/s".format(kb)
    }

    fun bytes(b: Long?): String = when {
        b == null || b < 0 -> "-"
        b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
        b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
        else -> "%.0f KB".format(b / 1024.0)
    }

    fun tokens(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1e6)
        n >= 1_000 -> "%.1fk".format(n / 1e3)
        else -> n.toString()
    }

    fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
        if (ms <= 0) return "never"
        val s = (now - ms) / 1000
        return when {
            s < 5 -> "just now"
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> "${s / 3600} h ago"
            else -> "${s / 86400} d ago"
        }
    }

    /** "in 2 h 5 min" for a future Unix time in seconds. */
    fun until(unixSec: Long, now: Long = System.currentTimeMillis()): String {
        val m = (unixSec * 1000 - now) / 60_000
        return when {
            m <= 0 -> "now"
            m < 60 -> "in $m min"
            m < 1440 -> "in ${m / 60} h ${m % 60} min"
            else -> "in ${m / 1440} d ${(m % 1440) / 60} h"
        }
    }

    fun weather(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm, hail"
        else -> "Unknown"
    }
}
