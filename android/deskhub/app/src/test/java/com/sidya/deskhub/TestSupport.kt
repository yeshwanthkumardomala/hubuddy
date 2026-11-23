package com.sidya.deskhub

/** Point the selected PC at a fake companion on [port] (USB mode = 127.0.0.1) and pair it. */
fun DeskHubApp.pairActivePc(port: Int, token: String = "k".repeat(43)) {
    val p = pcs.active()
    pcs.update(p.copy(mode = "usb", usbPort = port))
    pcs.setToken(p.id, token)
}

/** Add another PC that talks to a fake companion on [port] and pair it. Returns its id. */
fun DeskHubApp.addPairedPc(name: String, port: Int, token: String = "k".repeat(43)): String {
    val p = pcs.add(name)!!
    pcs.update(p.copy(mode = "usb", usbPort = port))
    pcs.setToken(p.id, token)
    return p.id
}
