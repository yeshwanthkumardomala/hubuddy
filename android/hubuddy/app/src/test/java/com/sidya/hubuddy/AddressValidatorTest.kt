package com.sidya.hubuddy

import com.sidya.hubuddy.core.LocalShortcut
import com.sidya.hubuddy.core.MemoryKeyValue
import com.sidya.hubuddy.core.Settings
import com.sidya.hubuddy.net.AddressValidator
import com.sidya.hubuddy.net.AddressValidator.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressValidatorTest {
    private fun ok(s: String) = AddressValidator.validate(s) as Result.Ok

    @Test fun acceptsLocalAddresses() {
        assertEquals("https://127.0.0.1:8765", ok("127.0.0.1:8765").baseUrl)
        assertEquals("https://192.168.1.100:8765", ok("192.168.1.100").baseUrl)
        assertEquals("https://10.0.0.2:9000", ok("https://10.0.0.2:9000/").baseUrl)
        assertEquals("https://172.20.1.1:8765", ok("https://172.20.1.1").baseUrl)
        assertEquals("https://my-pc.local:8765", ok("MY-PC.local:8765").baseUrl)
        assertEquals("https://[fe80::1]:8765", ok("[fe80::1]:8765").baseUrl)
        assertEquals("https://localhost:8765", ok("localhost").baseUrl)
    }

    @Test fun rejectsPublicAndMalformed() {
        listOf("", "8.8.8.8:8765", "example.com", "http://192.168.1.2:8765", "http://evil.com:8765", "172.32.0.1", "192.169.0.1",
            "ftp://192.168.1.2", "192.168.1.2:0", "192.168.1.2:70000", "192.168.1.2:abc", "192.168.1.2/path",
            "user@192.168.1.2", "256.1.1.1", "fe80::1:8765", "1.2.3", ".local", "a b.local"
        ).forEach { assertTrue("should reject '$it'", AddressValidator.validate(it) is Result.Error) }
    }
}

class SettingsTest {
    @Test fun persistsAndClamps() {
        val kv = MemoryKeyValue()
        Settings(kv).apply { refreshSec = 1; timeoutSec = 99; theme = "light" }
        val s = Settings(kv) // a "restart"
        assertEquals(2, s.refreshSec)
        assertEquals(30, s.timeoutSec)
        assertEquals("light", s.theme)
        listOf("auto", "dark", "black", "light").forEach { s.theme = it; assertEquals(it, Settings(kv).theme) }
        s.theme = "neon"; assertEquals("dark", s.theme)
    }

    @Test fun oldCharcoalThemeMapsToDark() {
        val kv = MemoryKeyValue(); kv.putString("cfg.theme", "charcoal")
        assertEquals("dark", Settings(kv).theme)
    }

    @Test fun parsesShortcuts() {
        val list = LocalShortcut.parse("Docs | https://example.org\nbad line\nCam|app:com.android.camera\nX | javascript:alert(1)\n | https://x")
        assertEquals(listOf("Docs", "Cam"), list.map { it.label })
        assertTrue(list[1].isApp)
    }
}
