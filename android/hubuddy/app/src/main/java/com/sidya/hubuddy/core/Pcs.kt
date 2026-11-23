package com.sidya.hubuddy.core

import com.sidya.hubuddy.net.AddressValidator
import org.json.JSONArray
import org.json.JSONObject

/** One PC or laptop running Hubuddy. [hostId] and [ca] are empty until the PC has been paired. */
data class PcProfile(
    val id: String,
    val name: String,
    /** The PC's permanent identity (from its invitation or discovery reply). */
    val hostId: String = "",
    /** The PC's certificate authority, base64url DER; the only identity this phone will accept for [hostId]. */
    val ca: String = "",
    /** Where it was last seen, "ip:port". Re-found automatically by [hostId] when it changes. */
    val address: String = "",
    /** For Wake-on-LAN, when the PC told us. */
    val mac: String = "",
) {
    fun endpoint(): String = address

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("hostId", hostId).put("ca", ca)
        .put("address", address).put("mac", mac)

    companion object {
        fun fromJson(o: JSONObject): PcProfile? {
            val id = o.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,16}")) } ?: return null
            return PcProfile(id, o.optString("name", "PC").take(24).ifBlank { "PC" }, o.optString("hostId"), o.optString("ca"),
                o.optString("address", "").trim(), o.optString("mac"))
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
                save(listOf(PcProfile("pc1", "My PC")))
                kv.putString(K_ACTIVE, "pc1")
            }
        }
    }

    fun list(): List<PcProfile> = synchronized(lock) {
        try {
            val a = JSONArray(kv.getString(K_LIST) ?: "[]")
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(PcProfile::fromJson) }.take(MAX_PCS)
        } catch (e: Exception) { emptyList() }
    }.ifEmpty { listOf(PcProfile("pc1", "My PC")) }

    fun get(id: String): PcProfile? = list().firstOrNull { it.id == id }

    var activeId: String
        get() = synchronized(lock) { kv.getString(K_ACTIVE)?.takeIf { id -> list().any { it.id == id } } ?: list().first().id }
        set(v) = synchronized(lock) { if (list().any { it.id == v }) kv.putString(K_ACTIVE, v) }

    fun active(): PcProfile = get(activeId) ?: list().first()

    val canAdd: Boolean get() = list().size < MAX_PCS

    /** Adds an empty PC and returns it, or null at the limit. */
    fun add(name: String? = null): PcProfile? = synchronized(lock) {
        val all = list()
        if (all.size >= MAX_PCS) return null
        var n = all.size + 1
        while (all.any { it.id == "pc$n" }) n++
        val p = PcProfile("pc$n", (name?.trim()?.take(24)?.takeIf { it.isNotEmpty() } ?: "PC $n"))
        save(all + p)
        p
    }

    /**
     * Records a PC that has just been paired. The same PC (by [PcProfile.hostId]) is updated in place, an unpaired
     * placeholder is filled, otherwise a new entry is added. Returns null at the limit.
     */
    fun upsertPaired(hostId: String, name: String, ca: String, address: String, mac: String): PcProfile? = synchronized(lock) {
        val all = list()
        val existing = all.firstOrNull { it.hostId == hostId } ?: all.firstOrNull { it.hostId.isEmpty() && !isPaired(it.id) }
        if (existing != null) {
            val p = existing.copy(hostId = hostId, ca = ca, address = address, mac = mac.ifEmpty { existing.mac },
                name = if (existing.hostId == hostId) existing.name else uniqueName(all, name.trim().take(24).ifBlank { "PC" }))
            save(all.map { if (it.id == p.id) p else it })
            return p
        }
        if (all.size >= MAX_PCS) return null
        var n = all.size + 1
        while (all.any { it.id == "pc$n" }) n++
        val p = PcProfile("pc$n", uniqueName(all, name.trim().take(24).ifBlank { "PC" }), hostId, ca, address, mac)
        save(all + p)
        p
    }

    private fun uniqueName(all: List<PcProfile>, wanted: String): String {
        if (all.none { it.name.equals(wanted, true) }) return wanted
        var i = 2
        while (all.any { it.name.equals("$wanted $i", true) }) i++
        return "$wanted $i"
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
