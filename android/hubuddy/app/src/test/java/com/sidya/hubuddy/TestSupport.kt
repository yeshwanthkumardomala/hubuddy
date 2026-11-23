package com.sidya.hubuddy

/** Point the selected PC at a fake agent on [port] (USB mode = 127.0.0.1) and pair it. */
fun HubuddyApp.pairActivePc(port: Int, token: String = "k".repeat(43)) {
    val p = pcs.active()
    pcs.update(p.copy(mode = "usb", usbPort = port))
    pcs.setToken(p.id, token)
}

/** Add another PC that talks to a fake agent on [port] and pair it. Returns its id. */
fun HubuddyApp.addPairedPc(name: String, port: Int, token: String = "k".repeat(43)): String {
    val p = pcs.add(name)!!
    pcs.update(p.copy(mode = "usb", usbPort = port))
    pcs.setToken(p.id, token)
    return p.id
}

/** The temporary first-login credentials of this build (they are not stored in the repository). */
val BOOT_LOGIN: String = BuildConfig.BOOTSTRAP_LOGIN
val BOOT_PIN: String = BuildConfig.BOOTSTRAP_PIN
