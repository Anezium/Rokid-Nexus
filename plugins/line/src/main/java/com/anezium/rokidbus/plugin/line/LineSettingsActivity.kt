package com.anezium.rokidbus.plugin.line

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi

/**
 * The one phone screen of the plugin: link a LINE account, follow the sign-in through
 * its PIN approval, and forget it again. The layout is built once; [render] shows the
 * part of the account card that matches the engine's state so the owner only ever
 * sees the step they are on.
 */
/** `ClipDescription.EXTRA_IS_SENSITIVE`, spelled out because the constant only exists from API 33. */
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

class LineSettingsActivity : Activity() {
    private val owner = Any()
    private lateinit var status: LineUi.StatusLine
    private lateinit var accountTitle: TextView
    private lateinit var accountBody: TextView
    private lateinit var steps: LineUi.Steps
    private lateinit var loginForm: View
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var signIn: Button
    private lateinit var approval: View
    private lateinit var pin: LineUi.PinCode
    private lateinit var pinHint: TextView
    private lateinit var copyPin: Button
    private lateinit var openLine: Button
    private lateinit var cancel: Button
    private lateinit var forget: View
    /** The PIN last placed on the clipboard by the owner's tap, so it can be taken back off. */
    private var copiedPin: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Credentials and the PIN are typed and shown here; nothing of it reaches a screenshot.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        val content = NexusUi.contentColumn(this, topDp = 14)
        status = LineUi.StatusLine(this).also { it.set("Checking LINE…", LineUi.Tone.IDLE) }
        content.addView(status.view, NexusUi.block())

        LineUi.section(this, content, "Account")
        content.addView(accountCard(), NexusUi.block())
        content.addView(
            LineUi.note(
                this,
                "Unofficial client, not made by LY Corporation. LINE may sign this device out or " +
                    "restrict the account; start with a test account. Nexus signs in as LINE for Chrome, " +
                    "which replaces an existing Chrome login.",
            ),
            NexusUi.block().apply { topMargin = NexusUi.dp(this@LineSettingsActivity, 8) },
        )

        LineUi.section(this, content, "Plugin")
        content.addView(NexusUi.card(this).apply {
            setPadding(0, 0, 0, 0)
            addView(LineUi.actionRow(context, "Open-source licenses", "The LINE engine and what it is built on") { showLicenses() })
        }, NexusUi.block())
        forget = LineUi.dangerRow(
            this,
            "Forget this connection",
            "Remove the saved session and keys from this phone",
            "FORGET",
        ) { confirmForget() }
        content.addView(forget, NexusUi.block().apply {
            topMargin = NexusUi.dp(this@LineSettingsActivity, 10)
            bottomMargin = NexusUi.dp(this@LineSettingsActivity, 8)
        })
        content.addView(NexusUi.uninstallCard(this, "LINE Messages") {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
        }, NexusUi.block())

        setContentView(LineUi.screen(this, "Personal account", content, ::finish))
        render()
    }

    // ---- the account card: one block per state, shown or hidden by render() ------------

    private fun accountCard(): LinearLayout = NexusUi.card(this).apply {
        accountTitle = NexusUi.cardTitle(context, "Checking LINE…")
        addView(accountTitle)
        accountBody = NexusUi.cardBody(context, "")
        addView(accountBody, NexusUi.block().apply { topMargin = NexusUi.dp(context, 5) })

        steps = LineUi.Steps(
            context,
            listOf(
                "In LINE on this phone, open Settings › Account: set an email and password, then turn on Allow login.",
                "Sign in here with that email. The password is used once and never saved.",
                "LINE asks for a PIN: type the one shown here, then come back to Nexus.",
            ),
        )
        addView(steps.view, NexusUi.block().apply { topMargin = NexusUi.dp(context, 16) })

        email = LineUi.secretField(context, "Email registered in LINE", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        password = LineUi.secretField(context, "LINE password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        signIn = NexusUi.pillButton(context, "Sign in").apply { setOnClickListener { signIn() } }
        loginForm = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(BusTheme.gap(context, 16))
            addView(email, NexusUi.block())
            addView(BusTheme.gap(context, 8))
            addView(LineUi.revealable(context, password), NexusUi.block())
            addView(BusTheme.gap(context, 12))
            addView(signIn, NexusUi.block())
        }
        addView(loginForm, NexusUi.block())

        pin = LineUi.PinCode(context)
        pinHint = NexusUi.cardBody(context, "")
        copyPin = NexusUi.outlinePillButton(context, "Copy PIN").apply { setOnClickListener { copyPin() } }
        openLine = NexusUi.pillButton(context, "Open LINE").apply { setOnClickListener { openLine() } }
        cancel = NexusUi.textButton(context, "Cancel sign-in").apply { setOnClickListener { cancelSignIn() } }
        approval = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(BusTheme.gap(context, 18))
            addView(pin.view, NexusUi.block())
            addView(BusTheme.gap(context, 10))
            addView(pinHint, NexusUi.block())
            addView(BusTheme.gap(context, 12))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(copyPin, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = NexusUi.dp(context, 8)
                })
                addView(openLine, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }, NexusUi.block())
            addView(cancel, NexusUi.block())
            visibility = View.GONE
        }
        addView(approval, NexusUi.block())
    }

    // ---- actions ------------------------------------------------------------------------

    private fun signIn() {
        val address = email.text.toString().trim()
        val secret = password.text.toString()
        if (address.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
            email.error = "Enter the email registered in LINE"
            return
        }
        if (secret.isEmpty() || secret.length > 1024) {
            password.error = "Enter your LINE password"
            return
        }
        signIn.isEnabled = false
        LineSignInRequest.put(LineCredentials(address, secret))
        email.text.clear()
        password.text.clear()
        if (runCatching { startForegroundService(Intent(this, LinePairingService::class.java)) }.isFailure) {
            LineSignInRequest.clear()
            status.set("Sign-in could not start · keep Nexus visible and try again", LineUi.Tone.BAD)
            signIn.isEnabled = true
        }
    }

    /**
     * Only this tap puts the PIN on the clipboard, marked sensitive so the system keeps it
     * out of its preview, and [forgetCopiedPin] takes it back off once the sign-in is over.
     */
    private fun copyPin() {
        val code = LineSession.login.pin.filter { it.isDigit() }
        if (code.isEmpty()) return
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("LINE PIN", code)
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        clipboard.setPrimaryClip(clip)
        copiedPin = code
        // Android 13 shows its own "copied" overlay; older phones say nothing.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "PIN copied", Toast.LENGTH_SHORT).show()
        }
    }

    /** Clears the clipboard if it still holds the PIN this screen put there; leaves anything else alone. */
    private fun forgetCopiedPin() {
        val code = copiedPin ?: return
        copiedPin = null
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        runCatching {
            val current = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            if (current == code) clipboard.clearPrimaryClip()
        }
    }

    private fun openLine() {
        val launch = packageManager.getLaunchIntentForPackage("jp.naver.line.android")
        if (launch == null || runCatching { startActivity(launch) }.isFailure) {
            pinHint.text = "Open the LINE app on this phone yourself and enter the PIN there."
        }
    }

    private fun cancelSignIn() {
        stopService(Intent(this, LinePairingService::class.java))
        LineSession.abortLogin()
    }

    private fun confirmForget() {
        AlertDialog.Builder(this).setTitle("Forget LINE connection?")
            .setMessage(
                "Remove this plugin's saved session and keys. No messages are deleted. " +
                    "You can also revoke the Chrome session from LINE's logged-in devices.",
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Forget") { _, _ ->
                stopService(Intent(this, LinePairingService::class.java))
                LineSession.forget(this) { ok ->
                    if (ok) render() else status.set("Could not forget the connection · close LINE pages and try again", LineUi.Tone.BAD)
                }
            }.show()
    }

    private fun showLicenses() {
        val notice = assets.open("licenses/NOTICE.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val text = NexusUi.cardBody(this, notice).apply { setTextIsSelectable(true) }
        AlertDialog.Builder(this).setTitle("Open-source licenses")
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("Close", null).show()
    }

    // ---- lifecycle ----------------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        LineSession.acquire(this, owner, ::render)
    }

    override fun onStop() {
        email.text.clear()
        password.text.clear()
        pin.set("")
        LineSession.release(owner)
        super.onStop()
    }

    private fun render() {
        if (isFinishing || isDestroyed) return
        val login = LineSession.login
        val engine = LineSession.snapshot.state
        val pairing = login.phase in setOf("starting", "approve", "pin")
        val linked = engine in setOf("standby", "offline", "connecting", "ready")
        val failed = login.phase == "failed" || engine == "failed"

        val (headline, tone) = when {
            login.phase == "starting" -> "Signing in to LINE…" to LineUi.Tone.BUSY
            login.phase == "approve" -> "Approve this sign-in in LINE" to LineUi.Tone.BUSY
            login.phase == "pin" -> "Enter the PIN in LINE" to LineUi.Tone.BUSY
            login.phase == "failed" -> "Sign-in failed or expired" to LineUi.Tone.BAD
            engine == "ready" -> "Linked · ready" to LineUi.Tone.GOOD
            engine == "connecting" -> "Linked · connecting…" to LineUi.Tone.BUSY
            engine == "standby" -> "Linked · on standby" to LineUi.Tone.IDLE
            engine == "offline" -> "Linked · reconnecting…" to LineUi.Tone.BUSY
            engine == "signed_out" -> "Not linked" to LineUi.Tone.IDLE
            engine == "loading" -> "Checking LINE…" to LineUi.Tone.IDLE
            else -> "LINE unavailable" to LineUi.Tone.BAD
        }
        status.set(headline, tone)

        when {
            pairing -> {
                accountTitle.text = if (login.phase == "pin") "Your PIN" else "Signing in…"
                accountBody.text = if (login.phase == "pin") "LINE on this phone is asking for it."
                else "Nexus is contacting LINE with the email you entered."
                steps.highlight(2)
                pin.set(login.pin)
                if (login.phase != "pin") forgetCopiedPin()
                pinHint.text = if (login.phase == "pin") "Type it in LINE within three minutes, then come back here."
                else "Keep Nexus visible; the PIN appears here in a moment."
            }
            linked -> {
                accountTitle.text = "Account linked"
                accountBody.text = when (engine) {
                    "ready" -> "On the glasses, open Applications › LINE Messages. Dictate or type a reply; only Send sends it."
                    "connecting" -> "Reconnecting to LINE. Conversations show on the glasses once it is through."
                    "offline" -> "The connection was interrupted. LINE retries while this session is open."
                    else -> "Your account is saved. Open Applications › LINE Messages on the glasses to connect. Nexus keeps LINE disconnected while it is not in use."
                }
                pin.set("")
                forgetCopiedPin()
            }
            failed -> {
                accountTitle.text = if (login.phase == "failed") "Sign-in did not go through" else "LINE unavailable"
                accountBody.text = if (login.phase == "failed") "Check Allow login in LINE › Settings › Account and your password, then try again."
                else "The LINE engine could not start. Forget the connection and sign in again."
                steps.highlight(0)
                pin.set("")
                forgetCopiedPin()
            }
            else -> {
                accountTitle.text = "Link your LINE account"
                accountBody.text = "Three steps, all on this phone."
                steps.highlight(0)
                pin.set("")
            }
        }

        val showForm = !pairing && !linked && engine != "failed"
        steps.view.visibility = if (linked) View.GONE else View.VISIBLE
        loginForm.visibility = if (showForm) View.VISIBLE else View.GONE
        signIn.isEnabled = showForm && engine == "signed_out"
        approval.visibility = if (pairing) View.VISIBLE else View.GONE
        openLine.visibility = if (login.phase == "pin" || login.phase == "approve") View.VISIBLE else View.GONE
        copyPin.visibility = if (login.phase == "pin" && login.pin.any { it.isDigit() }) View.VISIBLE else View.GONE
        forget.visibility = if (linked || failed) View.VISIBLE else View.GONE
    }
}
