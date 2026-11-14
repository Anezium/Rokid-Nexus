package com.anezium.rokidbus.plugin.meta

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * One service's account page: header, one status line, Account, Check connection, then
 * the destructive actions. The same section order hosts every signed-in Meta service, so
 * the layout is built once and each block is shown or hidden from the engine status.
 */
class AccountSettingsActivity : Activity() {
    companion object {
        const val EXTRA_ACCOUNT = "account"
        private const val FAST_POLL_MS = 1_000L
        private const val SLOW_POLL_MS = 5_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var account: MetaAccount
    private val host get() = account.host
    private var started = false
    private var connectRequested = false
    private var sending = false
    private var pendingActions = 0
    private var notice = ""
    private var current: AccountStatus? = null

    private lateinit var status: MetaUi.StatusLine
    private lateinit var noticeText: TextView
    private lateinit var accountTitle: TextView
    private lateinit var accountSub: TextView
    private lateinit var loginForm: View
    private lateinit var codeForm: View
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var code: EditText
    private lateinit var logIn: View
    private lateinit var submitCode: View
    private lateinit var cancelLogin: View
    private lateinit var reconnect: View
    private lateinit var testSection: View
    private lateinit var sendText: View
    private lateinit var sendImage: View
    private lateinit var result: TextView
    private lateinit var forget: View

    private val poll = Runnable { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        account = MetaAccount.byId(intent.getStringExtra(EXTRA_ACCOUNT))?.takeIf { it.open != null } ?: run { finish(); return }
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        val content = NexusUi.contentColumn(this, topDp = 14)

        status = MetaUi.StatusLine(this).also { it.set("Starting…", AccountStatus.Tone.IDLE) }
        content.addView(status.view, NexusUi.block())
        noticeText = MetaUi.notice(this)
        content.addView(noticeText, NexusUi.block().apply { topMargin = NexusUi.dp(this@AccountSettingsActivity, 6) })

        MetaUi.section(this, content, "Account")
        content.addView(accountCard(), NexusUi.block())
        content.addView(MetaUi.unofficialNote(this, account.title), NexusUi.block().apply { topMargin = NexusUi.dp(this@AccountSettingsActivity, 8) })

        testSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            MetaUi.section(this@AccountSettingsActivity, this, "Check connection", "Only to you")
            addView(testCard(), NexusUi.block())
            visibility = View.GONE
        }
        content.addView(testSection, NexusUi.block())

        MetaUi.section(this, content, "Plugin")
        forget = MetaUi.dangerRow(
            this,
            "Forget this session",
            "Remove the saved login from this phone",
            "FORGET",
        ) { confirmForget() }
        content.addView(forget, NexusUi.block().apply { bottomMargin = NexusUi.dp(this@AccountSettingsActivity, 8) })
        content.addView(NexusUi.uninstallCard(this, "Meta Messages") {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
        }, NexusUi.block())

        setContentView(MetaUi.screen(this, account.title, "Direct messages", content) { finish() })
    }

    // ---- building blocks of the per-service page -------------------------------------

    private fun accountCard(): LinearLayout = NexusUi.card(this).apply {
        accountTitle = NexusUi.cardTitle(context, "Starting…")
        addView(accountTitle)
        accountSub = NexusUi.cardBody(context, "")
        addView(accountSub, NexusUi.block().apply { topMargin = NexusUi.dp(context, 5) })

        username = MetaUi.secretField(this@AccountSettingsActivity, account.identifierHint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        password = MetaUi.secretField(this@AccountSettingsActivity, "Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        logIn = NexusUi.pillButton(context, "Log in").apply { setOnClickListener { logIn() } }
        loginForm = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(BusTheme.gap(context, 14))
            addView(username, NexusUi.block())
            addView(BusTheme.gap(context, 8))
            addView(MetaUi.revealable(context, password), NexusUi.block())
            addView(BusTheme.gap(context, 12))
            addView(logIn, NexusUi.block())
            visibility = View.GONE
        }
        addView(loginForm, NexusUi.block())

        code = MetaUi.secretField(this@AccountSettingsActivity, "Verification code", InputType.TYPE_CLASS_NUMBER)
        submitCode = NexusUi.pillButton(context, "Submit code").apply { setOnClickListener { submitCode() } }
        codeForm = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(BusTheme.gap(context, 14))
            addView(code, NexusUi.block())
            addView(BusTheme.gap(context, 12))
            addView(submitCode, NexusUi.block())
            visibility = View.GONE
        }
        addView(codeForm, NexusUi.block())

        cancelLogin = NexusUi.outlinePillButton(context, "Cancel login").apply {
            setOnClickListener { host.worker.execute { host.engine?.abortLogin() }; refresh() }
            visibility = View.GONE
        }
        addView(cancelLogin, NexusUi.block().apply { topMargin = NexusUi.dp(context, 12) })

        reconnect = NexusUi.outlinePillButton(context, "Reconnect").apply {
            setOnClickListener { connect() }
            visibility = View.GONE
        }
        addView(reconnect, NexusUi.block().apply { topMargin = NexusUi.dp(context, 12) })
    }

    private fun testCard(): LinearLayout = NexusUi.card(this).apply {
        setPadding(0, 0, 0, 0)
        sendText = MetaUi.actionRow(context, "Send yourself a message", "A short note to your own profile, confirmed first") { confirmText() }
        addView(sendText)
        addView(MetaUi.rowDivider(context))
        sendImage = MetaUi.actionRow(context, "Send yourself a picture", "The Nexus picture, shown before sending") { confirmImage() }
        addView(sendImage)
        result = NexusUi.rowSub(context, "").apply {
            maxLines = 3
            setTextColor(NexusUi.INK2)
            visibility = View.GONE
            setPadding(NexusUi.dp(context, 15), 0, NexusUi.dp(context, 15), NexusUi.dp(context, 14))
        }
        addView(result)
    }

    // ---- lifecycle -------------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        started = true
        host.visible = true
        // The grace service notices this screen is visible again and ends on its next check.
        connectRequested = false
        if (host.loginInterrupted) {
            host.loginInterrupted = false
            notice = "The previous login was cancelled: it was not approved within 3 minutes."
        } else if (host.graceOutcome.startsWith("failed")) {
            notice = AccountStatus(account, false, "", host.graceOutcome, false, "disconnected").describe()
        }
        host.graceOutcome = ""
        val directory = File(noBackupFilesDir, account.directory).absolutePath
        val open = checkNotNull(account.open)
        host.worker.execute {
            if (host.engine == null) host.engine = runCatching { open(directory) }.getOrNull()
            main.post { if (host.engine == null) notice = "${account.title} engine unavailable." }
        }
        main.post(poll)
    }

    override fun onStop() {
        started = false
        main.removeCallbacks(poll)
        username.text.clear()
        password.text.clear()
        code.text.clear()
        host.visible = false
        // A pending login may finish in the background for a bounded time; nothing else
        // outlives this screen, and the connection always closes with it.
        val graced = current?.loginBusy == true && runCatching {
            startForegroundService(Intent(this, AccountLoginGraceService::class.java))
        }.isSuccess
        if (graced) host.worker.execute { host.engine?.disconnect() } else host.releaseIfHidden()
        super.onStop()
    }

    private fun refresh() {
        host.probe.execute {
            val raw = runCatching { host.engine?.status() }.getOrNull()
            main.post { render(raw) }
        }
    }

    // ---- rendering -------------------------------------------------------------------

    private fun render(raw: String?) {
        if (!started) return
        val state = raw?.let {
            val json = JSONObject(it)
            AccountStatus(account, json.getBoolean("account"), json.optString("username"), json.getString("login"),
                json.getBoolean("connected"), json.getString("link"))
        }
        current = state
        renderStatus(state)
        renderAccount(state)
        val canSend = state?.canSend == true
        testSection.visibility = if (canSend && account.offersCheck) View.VISIBLE else View.GONE
        MetaUi.setRowEnabled(sendText, canSend && !sending)
        MetaUi.setRowEnabled(sendImage, canSend && !sending)
        forget.visibility =
            if (state?.account == true && !state.loginBusy && state.link != "connecting") View.VISIBLE else View.GONE
        if (state?.loginBusy == true) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (state?.wantsConnection == true && !connectRequested) connect()
        // The engine is polled quickly only while a login or connection is in flight;
        // a settled screen only needs to notice a drop.
        main.removeCallbacks(poll)
        val fast = state == null || state.inFlight || pendingActions > 0
        main.postDelayed(poll, if (fast) FAST_POLL_MS else SLOW_POLL_MS)
    }

    private fun renderStatus(state: AccountStatus?) {
        status.set(state?.headline() ?: "Starting…", state?.tone ?: AccountStatus.Tone.IDLE)
        MetaUi.setNotice(noticeText, notice)
        // The hub shows what this page last learned about the saved account, without an engine.
        if (state != null && !state.loginBusy) MetaPresence.set(account.id,
            if (state.account) MetaPresence.State.Present("Signed in as ${account.handle(state.username)}")
            else MetaPresence.State.Absent)
    }

    private fun renderAccount(state: AccountStatus?) {
        val canLogIn = state?.canLogIn == true
        val busy = state?.loginBusy == true
        val connecting = state?.link == "connecting"
        when {
            state == null -> {
                accountTitle.text = "Starting…"
                accountSub.text = "Opening the ${account.title} engine."
            }
            busy -> {
                accountTitle.text = state.headline()
                accountSub.text = state.describe()
            }
            canLogIn -> {
                accountTitle.text = "Sign in"
                accountSub.text = if (state.login == "idle" || state.login.isEmpty()) {
                    "Use the account you want on your glasses. ${account.title} may ask you to approve the login in its app or to enter a code."
                } else {
                    state.describe()
                }
            }
            else -> {
                accountTitle.text = account.handle(state.username)
                accountSub.text = when {
                    state.connected -> "Signed in and connected."
                    connecting -> "Connecting…"
                    state.link.startsWith("failed") -> state.describe()
                    else -> "Saved on this phone. Not connected."
                }
            }
        }
        loginForm.visibility = if (canLogIn) View.VISIBLE else View.GONE
        codeForm.visibility = if (state?.login == "code_required") View.VISIBLE else View.GONE
        cancelLogin.visibility = if (busy) View.VISIBLE else View.GONE
        reconnect.visibility =
            if (state?.account == true && !state.connected && !connecting && !busy) View.VISIBLE else View.GONE
    }

    // ---- actions ---------------------------------------------------------------------

    /**
     * One engine call on the worker. The screen polls quickly until the call reports back,
     * so a tap never waits for the slow cadence to show its effect. A failure becomes the
     * notice; success clears it.
     */
    private fun perform(failure: (Throwable) -> String, work: (AccountEngine) -> Unit) {
        pendingActions++
        host.worker.execute {
            val error = runCatching { work(checkNotNull(host.engine)) }.exceptionOrNull()
            main.post {
                pendingActions--
                notice = if (error == null) "" else failure(error)
                if (started) refresh()
            }
        }
        refresh()
    }

    private fun connect() {
        connectRequested = true
        notice = ""
        // A refused connection already explains itself through the link state.
        perform(failure = { "" }) { it.connect(30_000) }
    }

    private fun logIn() {
        val name = username.text.toString()
        val secret = password.text.toString()
        username.text.clear()
        password.text.clear()
        notice = ""
        connectRequested = false
        current = current?.copy(login = "running")
        perform(failure = { "Login not started: ${it.message}" }) { it.startLogin(name, secret) }
    }

    private fun submitCode() {
        val value = code.text.toString()
        code.text.clear()
        perform(failure = { "Code not accepted: ${it.message}" }) { it.submitCode(value) }
    }

    private fun confirmText() {
        AlertDialog.Builder(this)
            .setTitle("Send yourself this message?")
            .setMessage("Sent once, to your own ${account.title} profile:\n\n${account.selfText}")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send") { _, _ -> send("text") { it.sendSelfText(UUID.randomUUID().toString(), account.selfText) } }
            .show()
    }

    private fun confirmImage() {
        val bitmap = resources.openRawResource(R.raw.nexus_test_image).use { BitmapFactory.decodeStream(it) }
        val preview = ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds = true }
        AlertDialog.Builder(this)
            .setTitle("Send yourself this picture?")
            .setView(preview)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send") { _, _ ->
                val bytes = resources.openRawResource(R.raw.nexus_test_image).use { it.readBytes() }
                send("image") { it.sendSelfImage(UUID.randomUUID().toString(), bytes) }
            }
            .show()
    }

    private fun send(kind: String, work: (AccountEngine) -> String) {
        sending = true
        result.text = "Sending…"
        result.visibility = View.VISIBLE
        MetaUi.setRowEnabled(sendText, false)
        MetaUi.setRowEnabled(sendImage, false)
        host.worker.execute {
            val outcome = runCatching { JSONObject(work(checkNotNull(host.engine))) }.getOrNull()
            val text = when (outcome?.optString("state")) {
                "acked" -> "Delivered. Check your ${account.title} inbox."
                "not_sent" -> "Not sent (${outcome.optString("reason")})."
                else -> "Could not confirm delivery. Check ${account.title} before trying again."
            }
            main.post {
                sending = false
                result.text = text
                if (started) refresh()
            }
        }
    }

    private fun confirmForget() {
        AlertDialog.Builder(this)
            .setMessage("Forget the saved ${account.title} session on this phone? ${account.title} will still list this login in the account's login activity until you remove it there.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Forget") { _, _ ->
                // Pins name conversations of the forgotten account; they go with it.
                MessagingService.byId(account.id)?.let { MetaPins.clear(this, it) }
                connectRequested = true
                perform(failure = { "Forget failed." }) {
                    it.disconnect()
                    it.forget()
                }
            }
            .show()
    }
}
