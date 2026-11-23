package com.sidya.deskhub.core

/** User configuration (non-secret). Secrets live in [SecretStore]. */
class Settings(private val kv: KeyValue) {
    var refreshSec: Int
        get() = kv.getInt("cfg.refreshSec", 5).coerceIn(2, 300)
        set(v) = kv.putInt("cfg.refreshSec", v.coerceIn(2, 300))

    var timeoutSec: Int
        get() = kv.getInt("cfg.timeoutSec", 4).coerceIn(1, 30)
        set(v) = kv.putInt("cfg.timeoutSec", v.coerceIn(1, 30))

    /** "dark" (charcoal), "black", "light" or "auto" (follow the phone). */
    var theme: String
        get() = when (val t = kv.getString("cfg.theme")) {
            "black", "light", "auto", "dark" -> t
            else -> "dark" // includes the old "charcoal"
        }
        set(v) = kv.putString("cfg.theme", if (v in listOf("dark", "black", "light", "auto")) v else "dark")

    /** Show the last saved dashboard when the companion is offline. */
    var showCachedOffline: Boolean
        get() = kv.getBoolean("cfg.showCached", true)
        set(v) = kv.putBoolean("cfg.showCached", v)

    var keepScreenOn: Boolean
        get() = kv.getBoolean("cfg.keepScreenOn", false)
        set(v) = kv.putBoolean("cfg.keepScreenOn", v)

    var weatherName: String
        get() = kv.getString("cfg.weatherName") ?: ""
        set(v) = kv.putString("cfg.weatherName", v)
    var weatherLat: Double?
        get() = kv.getString("cfg.weatherLat")?.toDoubleOrNull()
        set(v) = kv.putString("cfg.weatherLat", v?.toString())
    var weatherLon: Double?
        get() = kv.getString("cfg.weatherLon")?.toDoubleOrNull()
        set(v) = kv.putString("cfg.weatherLon", v?.toString())
    var fahrenheit: Boolean
        get() = kv.getBoolean("cfg.fahrenheit", false)
        set(v) = kv.putBoolean("cfg.fahrenheit", v)

    var githubRepo: String
        get() = kv.getString("cfg.githubRepo") ?: "yeshwanthkumardomala/hubuddy"
        set(v) = kv.putString("cfg.githubRepo", v.trim())

    /** One per line: "Label | https://url" or "Label | app:com.package.name". */
    var localShortcuts: String
        get() = kv.getString("cfg.localShortcuts") ?: "Hubuddy on GitHub | https://github.com/yeshwanthkumardomala/hubuddy"
        set(v) = kv.putString("cfg.localShortcuts", v)

    // ---- Desk mode (DeskHub-style rotating landscape display) -------------------
    /** Minutes without a touch before desk mode starts; 0 = never automatically. */
    var deskIdleMin: Int
        get() = kv.getInt("desk.idleMin", 5).coerceIn(0, 120)
        set(v) = kv.putInt("desk.idleMin", v.coerceIn(0, 120))

    /** Seconds each panel stays on screen. */
    var deskPanelSec: Int
        get() = kv.getInt("desk.panelSec", 12).coerceIn(4, 300)
        set(v) = kv.putInt("desk.panelSec", v.coerceIn(4, 300))

    /** Panels to rotate through, in order. */
    var deskPanels: List<String>
        get() = (kv.getString("desk.panels") ?: DESK_PANELS.joinToString(",")).split(',').map { it.trim() }.filter { it in DESK_PANELS }
        set(v) = kv.putString("desk.panels", v.filter { it in DESK_PANELS }.joinToString(","))

    /** Start desk mode (and keep the screen on) even when not charging; stops itself at [LOW_BATTERY_PCT]. */
    var deskOnBattery: Boolean
        get() = kv.getBoolean("desk.onBattery", true)
        set(v) = kv.putBoolean("desk.onBattery", v)

    /** Night: dim clock only. Times are minutes after midnight. */
    var nightEnabled: Boolean
        get() = kv.getBoolean("desk.night", true)
        set(v) = kv.putBoolean("desk.night", v)
    var nightStart: Int
        get() = kv.getInt("desk.nightStart", 23 * 60).coerceIn(0, 1439)
        set(v) = kv.putInt("desk.nightStart", v.coerceIn(0, 1439))
    var nightEnd: Int
        get() = kv.getInt("desk.nightEnd", 7 * 60).coerceIn(0, 1439)
        set(v) = kv.putInt("desk.nightEnd", v.coerceIn(0, 1439))

    fun isNight(minuteOfDay: Int): Boolean {
        if (!nightEnabled || nightStart == nightEnd) return false
        return if (nightStart < nightEnd) minuteOfDay in nightStart until nightEnd
        else minuteOfDay >= nightStart || minuteOfDay < nightEnd
    }

    var lastTab: Int
        get() = kv.getInt("ui.lastTab", 0).coerceIn(0, 4)
        set(v) = kv.putInt("ui.lastTab", v)
}

const val LOW_BATTERY_PCT = 15

val DESK_PANELS = listOf("clock", "fleet", "pc", "claude", "media", "calendar", "weather", "network", "github")

data class LocalShortcut(val label: String, val target: String) {
    val isApp get() = target.startsWith("app:")
    val isUrl get() = target.startsWith("https://") || target.startsWith("http://")

    companion object {
        fun parse(text: String): List<LocalShortcut> = text.lines().mapNotNull { line ->
            val i = line.indexOf('|')
            if (i <= 0) return@mapNotNull null
            val label = line.substring(0, i).trim().take(30)
            val target = line.substring(i + 1).trim()
            val s = LocalShortcut(label, target)
            if (label.isEmpty() || !(s.isApp || s.isUrl)) null else s
        }.take(16)
    }
}
