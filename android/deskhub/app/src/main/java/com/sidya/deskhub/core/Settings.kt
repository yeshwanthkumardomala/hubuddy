package com.sidya.deskhub.core

import com.sidya.deskhub.net.AddressValidator

/** User configuration (non-secret). Secrets live in [SecretStore]. */
class Settings(private val kv: KeyValue) {
    /** "usb" = adb reverse to 127.0.0.1; "wifi" = the PC's LAN address. */
    var mode: String
        get() = kv.getString("cfg.mode")?.takeIf { it == "usb" || it == "wifi" } ?: "usb"
        set(v) = kv.putString("cfg.mode", if (v == "wifi") "wifi" else "usb")

    var usbPort: Int
        get() = kv.getInt("cfg.usbPort", AddressValidator.DEFAULT_PORT).coerceIn(1, 65535)
        set(v) = kv.putInt("cfg.usbPort", v.coerceIn(1, 65535))

    var wifiAddress: String
        get() = kv.getString("cfg.wifiAddress") ?: ""
        set(v) = kv.putString("cfg.wifiAddress", v.trim())

    fun companionAddress(): String = if (mode == "usb") "127.0.0.1:$usbPort" else wifiAddress

    var refreshSec: Int
        get() = kv.getInt("cfg.refreshSec", 5).coerceIn(2, 300)
        set(v) = kv.putInt("cfg.refreshSec", v.coerceIn(2, 300))

    var timeoutSec: Int
        get() = kv.getInt("cfg.timeoutSec", 4).coerceIn(1, 30)
        set(v) = kv.putInt("cfg.timeoutSec", v.coerceIn(1, 30))

    /** "charcoal" or "black" (pure black for OLED-like power saving). */
    var theme: String
        get() = kv.getString("cfg.theme")?.takeIf { it == "black" } ?: "charcoal"
        set(v) = kv.putString("cfg.theme", if (v == "black") "black" else "charcoal")

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

    var lastTab: Int
        get() = kv.getInt("ui.lastTab", 0).coerceIn(0, 4)
        set(v) = kv.putInt("ui.lastTab", v)
}

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
