package com.sidya.hubuddy.core

import com.sidya.hubuddy.net.AddressValidator
import org.json.JSONArray
import org.json.JSONObject

/** One PC or laptop running the Hubuddy agent. */
data class PcProfile(
    val id: String,
    val name: String,
    /** "usb" = adb reverse to 127.0.0.1 (one PC per cable); "wifi" = the PC's LAN address. */
    val mode: String,
    val usbPort: Int,
    val address: String,
) {
    fun endpoint(): String = if (mode == "usb") "127.0.0.1:$usbPort" else address

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("mode", mode)
        .put("usbPort", usbPort).put("address", address)

    companion object {
        fun fromJson(o: JSONObject): PcProfile? {
            val id = o.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,16}")) } ?: return null
            return PcProfile(id, o.optString("name", "PC").take(24).ifBlank { "PC" },
                if (o.optString("mode") == "wifi") "wifi" else "usb",
                o.optInt("usbPort", AddressValidator.DEFAULT_PORT).coerceIn(1, 65535), o.optString("address", "").trim())
        }
    }
}

/**
 * The list of PCs (up to [MAX_PCS]) with the pairing token of each in the encrypted [SecretStore].

 */
class PcRegistry(private val kv: KeyValue, private val secrets: SecretStore) {
    private val lock = Any()

    init {
        synchronized(lock) {
            if (kv.getString(K_LIST) == null) {
                save(listOf(PcProfile("pc1", "My PC", "usb", AddressValidator.DEFAULT_PORT, "")))
                kv.putString(K_ACTIVE, "pc1")
            }
        }
    }

    fun list(): List<PcProfile> = synchronized(lock) {
        try {
            val a = JSONArray(kv.getString(K_LIST) ?: "[]")
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(PcProfile::fromJson) }.take(MAX_PCS)
        } catch (e: Exception) { emptyList() }
    }.ifEmpty { listOf(PcProfile("pc1", "My PC", "usb", AddressValidator.DEFAULT_PORT, "")) }

    fun get(id: String): PcProfile? = list().firstOrNull { it.id == id }

    var activeId: String
        get() = synchronized(lock) { kv.getString(K_ACTIVE)?.takeIf { id -> list().any { it.id == id } } ?: list().first().id }
        set(v) = synchronized(lock) { if (list().any { it.id == v }) kv.putString(K_ACTIVE, v) }

    fun active(): PcProfile = get(activeId) ?: list().first()

    val canAdd: Boolean get() = list().size < MAX_PCS

    /** Adds a PC and returns it, or null at the limit. */
    fun add(name: String? = null, mode: String = "wifi"): PcProfile? = synchronized(lock) {
        val all = list()
        if (all.size >= MAX_PCS) return null
        var n = all.size + 1
        while (all.any { it.id == "pc$n" }) n++
        val p = PcProfile("pc$n", (name?.trim()?.take(24)?.takeIf { it.isNotEmpty() } ?: "PC $n"), mode, AddressValidator.DEFAULT_PORT, "")
        save(all + p)
        p
    }

    fun update(p: PcProfile) = synchronized(lock) {
        save(list().map { if (it.id == p.id) p.copy(name = p.name.trim().take(24).ifBlank { it.name }) else it })
    }

    /** Removes a PC and its token. The last PC cannot be removed. */
    fun remove(id: String): Boolean = synchronized(lock) {
        val all = list()
        if (all.size <= 1 || all.none { it.id == id }) return false
        save(all.filter { it.id != id })
        secrets.put(tokenKey(id), null)
        if (kv.getString(K_ACTIVE) == id) kv.putString(K_ACTIVE, list().first().id)
        true
    }

    fun token(id: String): String? = secrets.get(tokenKey(id))
    fun setToken(id: String, token: String?) = secrets.put(tokenKey(id), token)
    fun isPaired(id: String): Boolean = !token(id).isNullOrEmpty()

    private fun save(l: List<PcProfile>) {
        kv.putString(K_LIST, JSONArray().also { a -> l.forEach { a.put(it.toJson()) } }.toString())
    }

    companion object {
        const val MAX_PCS = 8
        private const val K_LIST = "pcs.list"
        private const val K_ACTIVE = "pcs.active"
        fun tokenKey(id: String) = "pc.$id.token"
    }
}
