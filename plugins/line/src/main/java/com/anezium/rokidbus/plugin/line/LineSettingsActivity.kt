package com.anezium.rokidbus.plugin.line

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anezium.rokidbus.client.ui.NexusUi

class LineSettingsActivity : Activity() {
    private val owner = Any()
    private lateinit var status: TextView
    private lateinit var pin: TextView
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var link: Button
    private lateinit var openLine: Button
    private lateinit var cancel: Button
    private lateinit var forget: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val content = NexusUi.contentColumn(this)
        status = NexusUi.cardTitle(this, "Checking LINE…")
        content.addView(status, NexusUi.block())
        content.addView(NexusUi.cardBody(this,
            "Unofficial LINE client. LINE may restrict accounts using third-party clients. " +
                "This connection uses LINE's Chrome session and can replace an existing LINE Chrome login. " +
                "This prototype reads recent chats and sends text with Letter Sealing; media and calls are not supported."), NexusUi.block())
        content.addView(NexusUi.cardBody(this,
            "Use the email registered in LINE Settings › Account, and enable Allow login there. " +
                "Sign in below, then open LINE on this phone to approve using the PIN shown here. " +
                "Return to Nexus afterwards. Your password is used once and is not saved. Sign-in expires after 3 minutes."), NexusUi.block())
        email = credentialField("LINE account email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        password = credentialField("LINE password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        content.addView(email, NexusUi.block())
        content.addView(password, NexusUi.block())
        pin = NexusUi.cardTitle(this, "").apply { setTextIsSelectable(false) }
        content.addView(pin, NexusUi.block())
        link = NexusUi.outlinePillButton(this, "Sign in").apply {
            setOnClickListener {
                val address = email.text.toString().trim()
                val secret = password.text.toString()
                if (address.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
                    email.error = "Enter the email registered in LINE"
                    return@setOnClickListener
                }
                if (secret.isEmpty() || secret.length > 1024) {
                    password.error = "Enter your LINE password"
                    return@setOnClickListener
                }
                isEnabled = false
                LineSignInRequest.put(LineCredentials(address, secret))
                email.text.clear()
                password.text.clear()
                if (runCatching { startForegroundService(Intent(this@LineSettingsActivity, LinePairingService::class.java)) }.isFailure) {
                    LineSignInRequest.clear()
                    status.text = "Sign-in could not start; keep Nexus visible and try again"
                    isEnabled = true
                }
            }
        }
        content.addView(link, NexusUi.block())
        openLine = NexusUi.outlinePillButton(this, "Open LINE").apply {
            setOnClickListener {
                val launch = packageManager.getLaunchIntentForPackage("jp.naver.line.android")
                if (launch == null || runCatching { startActivity(launch) }.isFailure) {
                    status.text = "Open the LINE app on this phone to approve sign-in"
                }
            }
        }
        content.addView(openLine, NexusUi.block())
        cancel = NexusUi.textButton(this, "Cancel sign-in").apply {
            setOnClickListener { stopService(Intent(this@LineSettingsActivity, LinePairingService::class.java)); LineSession.abortLogin() }
        }
        content.addView(cancel, NexusUi.block())
        forget = NexusUi.textButton(this, "Forget this connection", danger = true).apply {
            setOnClickListener {
                AlertDialog.Builder(this@LineSettingsActivity).setTitle("Forget LINE connection?")
                    .setMessage("Remove this plugin's saved session and keys. No messages are deleted. " +
                        "You can also revoke the Chrome session from LINE's logged-in devices.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Forget") { _, _ ->
                        stopService(Intent(this@LineSettingsActivity, LinePairingService::class.java))
                        LineSession.forget(this@LineSettingsActivity) { ok ->
                            if (ok) render() else status.text = "Could not forget the connection; close LINE pages and try again"
                        }
                    }.show()
            }
        }
        content.addView(forget, NexusUi.block())
        content.addView(NexusUi.textButton(this, "Open-source licenses").apply {
            setOnClickListener {
                val notice = assets.open("licenses/NOTICE.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
                val text = NexusUi.cardBody(this@LineSettingsActivity, notice).apply { setTextIsSelectable(true) }
                AlertDialog.Builder(this@LineSettingsActivity).setTitle("Open-source licenses")
                    .setView(ScrollView(this@LineSettingsActivity).apply { addView(text) })
                    .setPositiveButton("Close", null).show()
            }
        }, NexusUi.block())
        val root = NexusUi.fixedRoot(this)
        root.addView(NexusUi.pluginHeader(this, NexusUi.iconTile(this, "L", 48), "LINE Messages", "Personal account · text prototype", ::finish))
        root.addView(NexusUi.screen(this, content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        render()
    }

    override fun onStart() { super.onStart(); LineSession.acquire(this, owner, ::render) }
    override fun onStop() {
        email.text.clear()
        password.text.clear()
        pin.text = ""
        LineSession.release(owner)
        super.onStop()
    }

    private fun credentialField(hint: String, type: Int): EditText = NexusUi.field(this, hint).apply {
        inputType = type
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
    }

    private fun render() {
        if (isFinishing || isDestroyed) return
        val login = LineSession.login
        val pairing = login.phase in setOf("starting", "approve", "pin")
        val linked = LineSession.snapshot.state in setOf("offline", "connecting", "ready")
        status.text = when (login.phase) {
            "starting" -> "Signing in to LINE…"
            "approve" -> "Approve this sign-in in LINE on the phone"
            "pin" -> "Enter this PIN in LINE"
            "failed" -> "Sign-in failed or expired · check your account and try again"
            "linked" -> "Account linked · open LINE Messages on the glasses"
            else -> if (linked) "Account linked" else LinePages.status(LineSession.snapshot.state)
        }
        pin.text = login.pin
        email.visibility = if (linked || pairing) View.GONE else View.VISIBLE
        password.visibility = email.visibility
        link.isEnabled = !pairing && LineSession.snapshot.state == "signed_out"
        link.visibility = if (linked || LineSession.snapshot.state == "failed") View.GONE else View.VISIBLE
        openLine.visibility = if (login.phase == "pin" || login.phase == "approve") View.VISIBLE else View.GONE
        cancel.visibility = if (pairing) View.VISIBLE else View.GONE
        forget.visibility = if (linked || LineSession.snapshot.state == "failed") View.VISIBLE else View.GONE
    }
}
