package com.sidya.deskhub.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import com.sidya.deskhub.DeskHubApp
import com.sidya.deskhub.auth.AuthManager.LoginResult
import java.util.concurrent.Executors

class LoginActivity : Activity() {
    private lateinit var loginField: EditText
    private lateinit var pinField: EditText
    private lateinit var message: TextView
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) // keep the PIN out of screenshots/recents
        val app = DeskHubApp.of(this)
        Pal.apply(app.settings.theme, this)
        Pal.styleWindow(this)
        val auth = app.auth
        if (auth.hasSession()) {
            route(auth.mustChangePin)
            return
        }
        val ui = Ui(this)
        var pinVisible = false
        val form = ui.vbox(24) {
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Pal.bg)
            addView(ui.spacer(24))
            addView(ui.text("Hubuddy DeskHub", 26f, Pal.text, bold = true).apply { gravity = Gravity.CENTER })
            addView(ui.text("SIDYA EDITION", 12f, Pal.walnut, bold = true).apply { gravity = Gravity.CENTER; letterSpacing = 0.2f })
            addView(ui.spacer(28))
            addView(ui.card("Sign in") {
                addView(ui.text("Login number", 12f, Pal.dim))
                loginField = ui.input("Login number", "login", InputType.TYPE_CLASS_NUMBER)
                addView(loginField, ui.lp(top = 4, bottom = 10))
                addView(ui.text("PIN", 12f, Pal.dim))
                pinField = ui.input("PIN", "pin", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
                addView(ui.hbox {
                    addView(pinField, ui.lp(0, -2, 1f))
                    addView(ui.button("Show", primary = false, tag = "togglePin") {}.apply {
                        setOnClickListener {
                            pinVisible = !pinVisible
                            pinField.transformationMethod = if (pinVisible) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
                            pinField.setSelection(pinField.text.length)
                            text = if (pinVisible) "Hide" else "Show"
                        }
                    }, ui.lp(-2, -2).apply { leftMargin = ui.dp(8) })
                }, ui.lp(top = 4, bottom = 12))
                addView(ui.button("Log in", tag = "loginButton") { attempt() }, ui.lp())
                message = ui.text("", 13f, Pal.bad, tag = "message").apply { setPadding(0, ui.dp(10), 0, 0) }
                addView(message)
            })
            addView(ui.text("First login uses the temporary credentials from the setup guide; you will be asked to set a new PIN.",
                12f, Pal.dim).apply { gravity = Gravity.CENTER })
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Pal.bg); isFillViewport = true; addView(form) })
        showLockoutIfAny()
    }

    private fun showLockoutIfAny() {
        val until = DeskHubApp.of(this).auth.lockedUntil()
        if (until > System.currentTimeMillis()) showLocked(until)
    }

    private fun showLocked(until: Long) {
        val sec = ((until - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
        message.setTextColor(Pal.bad)
        message.text = "Too many failed attempts. Try again in ${if (sec >= 60) "${sec / 60} min ${sec % 60} s" else "$sec s"}."
    }

    private fun attempt() {
        if (busy) return
        val login = loginField.text.toString()
        val pin = pinField.text.toString()
        busy = true
        message.setTextColor(Pal.dim)
        message.text = "Checking..."
        val auth = DeskHubApp.of(this).auth
        // PBKDF2 takes a moment on older phones: keep it off the UI thread.
        worker.execute {
            val r = auth.login(login, pin)
            main.post {
                busy = false
                if (isFinishing || isDestroyed) return@post
                when (r) {
                    is LoginResult.Success -> { pinField.setText(""); route(r.mustChangePin) }
                    is LoginResult.Invalid -> {
                        message.setTextColor(Pal.bad)
                        message.text = "Incorrect login number or PIN. ${r.attemptsLeft} attempt(s) left before a temporary lock."
                        pinField.setText("")
                    }
                    is LoginResult.LockedOut -> { pinField.setText(""); showLocked(r.untilMs) }
                    LoginResult.Empty -> { message.setTextColor(Pal.bad); message.text = "Enter your login number and PIN." }
                }
            }
        }
    }

    private fun route(mustChange: Boolean) {
        startActivity(Intent(this, if (mustChange) ChangePinActivity::class.java else MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
