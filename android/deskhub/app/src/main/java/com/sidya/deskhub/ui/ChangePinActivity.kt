package com.sidya.deskhub.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import com.sidya.deskhub.DeskHubApp
import com.sidya.deskhub.auth.AuthManager.ChangeResult
import com.sidya.deskhub.auth.PinPolicy
import java.util.concurrent.Executors

/** Shown after the first login (mandatory) and from Settings (voluntary). */
class ChangePinActivity : Activity() {
    private lateinit var current: EditText
    private lateinit var newPin: EditText
    private lateinit var confirm: EditText
    private lateinit var message: TextView
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val app = DeskHubApp.of(this)
        Pal.apply(app.settings.theme)
        if (!app.auth.hasSession()) {
            startActivity(Intent(this, LoginActivity::class.java)); finish(); return
        }
        val mandatory = app.auth.mustChangePin
        val ui = Ui(this)
        val pinType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val form = ui.vbox(24) {
            setBackgroundColor(Pal.bg)
            addView(ui.text(if (mandatory) "Set your own PIN" else "Change PIN", 22f, bold = true))
            addView(ui.text(
                if (mandatory) "The temporary PIN must be replaced before you can use DeskHub."
                else "Enter your current PIN and choose a new one.", 13f, Pal.dim), ui.lp(bottom = 16))
            addView(ui.card("PIN") {
                current = ui.input(if (mandatory) "Temporary PIN" else "Current PIN", "currentPin", pinType)
                newPin = ui.input("New PIN (${PinPolicy.MIN}-${PinPolicy.MAX} digits)", "newPin", pinType)
                confirm = ui.input("Confirm new PIN", "confirmPin", pinType)
                addView(current, ui.lp(bottom = 8)); addView(newPin, ui.lp(bottom = 8)); addView(confirm, ui.lp(bottom = 12))
                addView(ui.button("Save PIN", tag = "savePin") { save() })
                message = ui.text("", 13f, Pal.bad, tag = "message").apply { setPadding(0, ui.dp(10), 0, 0) }
                addView(message)
            })
            if (!mandatory) addView(ui.button("Cancel", primary = false) { finish() })
            else addView(ui.button("Log out", primary = false) {
                app.auth.logout(); startActivity(Intent(this@ChangePinActivity, LoginActivity::class.java)); finish()
            })
            gravity = Gravity.TOP
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Pal.bg); isFillViewport = true; addView(form) })
    }

    private fun save() {
        if (busy) return
        busy = true
        message.setTextColor(Pal.dim); message.text = "Saving..."
        val c = current.text.toString(); val n = newPin.text.toString(); val f = confirm.text.toString()
        val auth = DeskHubApp.of(this).auth
        worker.execute {
            val r = auth.changePin(c, n, f)
            main.post {
                busy = false
                if (isFinishing || isDestroyed) return@post
                message.setTextColor(Pal.bad)
                when (r) {
                    ChangeResult.Ok -> {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        finish()
                    }
                    ChangeResult.WrongCurrent -> message.text = "Current PIN is incorrect."
                    ChangeResult.Mismatch -> message.text = "New PIN and confirmation do not match."
                    ChangeResult.NotLoggedIn -> { startActivity(Intent(this, LoginActivity::class.java)); finish() }
                    is ChangeResult.Weak -> message.text = r.reason
                }
                if (r != ChangeResult.Ok) { current.setText(""); newPin.setText(""); confirm.setText("") }
            }
        }
    }

    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
