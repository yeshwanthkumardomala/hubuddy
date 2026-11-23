package com.sidya.hubuddy

import com.sidya.hubuddy.core.PcProfile

/** Point the selected PC at a fake PC on [port] (identity [pki]) and mark it paired. */
fun HubuddyApp.pairActivePc(port: Int, token: String = "k".repeat(43), pki: TestPki.Identity = TestPki.A) {
    val p = pcs.active()
    pcs.update(p.copy(hostId = pki.hostId, ca = pki.caB64, address = "127.0.0.1:$port"))
    pcs.setToken(p.id, token)
}

/** Add another PC that talks to a fake PC on [port] and pair it. Returns its id. */
fun HubuddyApp.addPairedPc(name: String, port: Int, token: String = "k".repeat(43), pki: TestPki.Identity = TestPki.A): String {
    val p = pcs.add(name)!!
    // each fake PC needs its own identity in the registry; the host id only has to be unique per PC
    pcs.update(p.copy(hostId = pki.hostId, ca = pki.caB64, address = "127.0.0.1:$port"))
    pcs.setToken(p.id, token)
    return p.id
}

/** A paired profile for registry-level tests. */
fun pairedProfile(id: String, name: String, port: Int, pki: TestPki.Identity = TestPki.A) =
    PcProfile(id, name, pki.hostId, pki.caB64, "127.0.0.1:$port")

/** The temporary first-login credentials of this build (they are not stored in the repository). */
val BOOT_LOGIN: String = BuildConfig.BOOTSTRAP_LOGIN
val BOOT_PIN: String = BuildConfig.BOOTSTRAP_PIN

/** The text a PC shows in its QR code, for the fake PC at [port]. */
fun invitationText(pki: TestPki.Identity, port: Int, secret: String = "s".repeat(43), expires: Long = System.currentTimeMillis() + 120_000,
                   address: String = "127.0.0.1", name: String = "Test PC"): String {
    val json = org.json.JSONObject().put("v", 1).put("hostId", pki.hostId).put("name", name).put("ca", pki.caB64).put("port", port)
        .put("address", address).put("addresses", org.json.JSONArray()).put("mac", "").put("secret", secret).put("expires", expires)
    return "hubuddy-pair:" + android.util.Base64.encodeToString(json.toString().toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
}
