package com.sidya.deskhub.auth

object PinPolicy {
    const val MIN = 6
    const val MAX = 8

    /** Null when acceptable, otherwise a short human-readable reason. */
    fun problem(pin: String, bootstrapPin: String): String? {
        if (pin.length < MIN || pin.length > MAX) return "PIN must be $MIN-$MAX digits."
        if (!pin.all { it in '0'..'9' }) return "PIN must contain digits only."
        if (pin == bootstrapPin) return "Choose a PIN different from the temporary one."
        if (pin.all { it == pin[0] }) return "PIN cannot be one repeated digit."
        val asc = pin.zipWithNext().all { (a, b) -> b - a == 1 }
        val desc = pin.zipWithNext().all { (a, b) -> a - b == 1 }
        if (asc || desc) return "PIN cannot be a simple sequence."
        return null
    }
}
