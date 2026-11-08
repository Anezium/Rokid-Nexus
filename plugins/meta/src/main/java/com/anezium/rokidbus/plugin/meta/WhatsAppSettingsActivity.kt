package com.anezium.rokidbus.plugin.meta

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi

/**
 * The WhatsApp page: header, one status line, the Account card that walks the owner
 * through linking this phone as a companion device, then the destructive actions.
 * Everything shown is derived from [WhatsAppSettingsView]; the session decides what is
 * possible and this screen only words it, see [WhatsAppCopy].
 */
class WhatsAppSettingsActivity : Activity() {
    private lateinit var session: WhatsAppSettingsSession
    private lateinit var status: MetaUi.StatusLine
    private lateinit var refresh: View
    private lateinit var accountTitle: TextView
    private lateinit var accountSub: TextView
    private lateinit var steps: MetaUi.Steps
    private lateinit var linkForm: View
    private lateinit var phone: EditText
    private lateinit var link: View
    private lateinit var codeBlock: View
    private lateinit var code: MetaUi.PairingCode
    private lateinit var copyCode: Button
    private lateinit var openWhatsApp: Button
    private lateinit var unlink: View
    private var whatsAppLaunch: Intent? = null
    private var shownCode = ""
    private var codeCopied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val main = Handler(Looper.getMainLooper())
        session = lastNonConfigurationInstance as? WhatsAppSettingsSession ?: WhatsAppSettingsSession(
            NativeWhatsAppSettingsBackend(applicationContext),
            SettingsScheduler { delay, task ->
                val runnable = Runnable { task() }
                main.postDelayed(runnable, delay)
                val cancel: () -> Unit = { main.removeCallbacks(runnable) }
                cancel
            },
            SystemClock::elapsedRealtime,
            holdInBackground = {
                runCatching { applicationContext.startForegroundService(Intent(applicationContext, WhatsAppPairingGraceService::class.java)) }.isSuccess
            },
            endBackgroundHold = { applicationContext.stopService(Intent(applicationContext, WhatsAppPairingGraceService::class.java)) },
        )
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        whatsAppLaunch = WHATSAPP_PACKAGES.firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
        val content = NexusUi.contentColumn(this, topDp = 14)

        refresh = NexusUi.textButton(this, "Refresh").apply {
            setOnClickListener { session.refresh() }
            visibility = View.GONE
        }
        status = MetaUi.StatusLine(this, refresh).also { it.set("Checking your link…", AccountStatus.Tone.BUSY) }
        content.addView(status.view, NexusUi.block())

        MetaUi.section(this, content, "Account")
        content.addView(accountCard(), NexusUi.block())
        content.addView(MetaUi.unofficialNote(this, "WhatsApp"), NexusUi.block().apply { topMargin = NexusUi.dp(this@WhatsAppSettingsActivity, 8) })

        MetaUi.section(this, content, "Plugin")
        unlink = MetaUi.dangerRow(
            this,
            "Unlink this phone",
            "Sign Nexus out of your WhatsApp linked devices",
            "UNLINK",
        ) {
            AlertDialog.Builder(this)
                .setMessage("Unlink this phone from WhatsApp? Your glasses will stop showing WhatsApp until you link it again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Unlink") { _, _ -> session.unlink() }.show()
        }
        content.addView(unlink, NexusUi.block().apply { bottomMargin = NexusUi.dp(this@WhatsAppSettingsActivity, 8) })
        content.addView(NexusUi.uninstallCard(this, "Meta Messages") {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
        }, NexusUi.block())

        setContentView(MetaUi.screen(this, "WhatsApp", "Companion device", content) { finish() })
    }

    // ---- building blocks of the page -------------------------------------------------

    private fun accountCard(): LinearLayout = NexusUi.card(this).apply {
        accountTitle = NexusUi.cardTitle(context, "Checking your link…")
        addView(accountTitle)
        accountSub = NexusUi.cardBody(context, "")
        addView(accountSub, NexusUi.block().apply { topMargin = NexusUi.dp(context, 5) })

        steps = MetaUi.Steps(context, listOf(
            "Enter the phone number of your WhatsApp account and tap Get code.",
            "Open WhatsApp on the phone that has your account: Settings › Linked devices › Link a device.",
            "Choose Link with phone number instead and type the code shown here.",
            "Done. Come back to Nexus; the link finishes on its own.",
        ))
        addView(steps.view, NexusUi.block().apply { topMargin = NexusUi.dp(context, 16) })

        phone = MetaUi.secretField(context, "Your WhatsApp number, with country code", InputType.TYPE_CLASS_PHONE)
        link = NexusUi.pillButton(context, "Get code").apply {
            setOnClickListener {
                val number = phone.text.toString().trim()
                // An empty or partial number would only come back as a vague linking failure.
                if (number.count(Char::isDigit) < 6) {
                    phone.error = "Enter your WhatsApp number first, with the country code"
                    phone.requestFocus()
                    return@setOnClickListener
                }
                phone.text.clear()
                session.startLink(number)
            }
        }
        linkForm = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(BusTheme.gap(context, 16))
            addView(phone, NexusUi.block())
            addView(BusTheme.gap(context, 12))
            addView(link, NexusUi.block())
            visibility = View.GONE
        }
        addView(linkForm, NexusUi.block())

        // The pairing code is shown once, in large cells, and never saved with the view state.
        code = MetaUi.PairingCode(context)
        copyCode = NexusUi.pillButton(context, "Copy code").apply { setOnClickListener { copyShownCode() } }
        openWhatsApp = NexusUi.outlinePillButton(context, "Open WhatsApp").apply {
            setOnClickListener { whatsAppLaunch?.let { runCatching { startActivity(it) } } }
            visibility = if (whatsAppLaunch == null) View.GONE else View.VISIBLE
        }
        codeBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(BusTheme.gap(context, 16))
            addView(NexusUi.metaLabel(context, "Your code · valid for 3 minutes", NexusUi.GREEN_DIM))
            addView(BusTheme.gap(context, 10))
            addView(code.view, NexusUi.block())
            addView(BusTheme.gap(context, 14))
            addView(copyCode, NexusUi.block())
            addView(openWhatsApp, NexusUi.block().apply { topMargin = NexusUi.dp(context, 10) })
            addView(BusTheme.gap(context, 14))
            addView(NexusUi.cardBody(context,
                "If WhatsApp is on this phone, switching to it is fine: Nexus keeps the link alive " +
                    "until the code is used or expires. Then come back here."))
            visibility = View.GONE
        }
        addView(codeBlock, NexusUi.block())
    }

    // ---- lifecycle -------------------------------------------------------------------

    override fun onRetainNonConfigurationInstance(): Any = session

    override fun onStart() {
        super.onStart()
        session.start(::render)
    }

    override fun onStop() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        phone.text.clear()
        // The cells empty with the screen; the clipboard is left alone so the code can be pasted in WhatsApp.
        code.set("")
        shownCode = ""
        session.stop(isChangingConfigurations)
        super.onStop()
    }

    // ---- rendering -------------------------------------------------------------------

    private fun render(view: WhatsAppSettingsView) {
        if (view.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pairing = view.code.isNotEmpty()
        val authenticated = view.text == AUTHENTICATED
        status.set(WhatsAppCopy.headline(view), when {
            view.busy -> AccountStatus.Tone.BUSY
            authenticated -> AccountStatus.Tone.GOOD
            view.canLink -> AccountStatus.Tone.IDLE
            else -> AccountStatus.Tone.BAD
        })
        // The hub shows what this page last learned about the linked device, without an engine.
        if (!view.busy) MetaPresence.set(MetaMessagesActivity.WHATSAPP_ID,
            if (view.canLink) MetaPresence.State.Absent else MetaPresence.State.Present(WhatsAppCopy.presence(view)))
        refresh.visibility = if (view.busy) View.GONE else View.VISIBLE
        val explanation = WhatsAppCopy.explain(view)
        when {
            pairing -> {
                accountTitle.text = "Enter this code in WhatsApp"
                accountSub.text = "You have 3 minutes. Keep this screen open or switch to WhatsApp."
                steps.highlight(2)
            }
            view.busy -> {
                accountTitle.text = WhatsAppCopy.headline(view)
                accountSub.text = "This takes a moment."
                steps.highlight(if (view.text == "Finishing link…") 3 else 1)
            }
            view.canLink -> {
                accountTitle.text = "Link this phone to WhatsApp"
                accountSub.text = if (explanation.isEmpty()) {
                    "Nexus becomes one of your account's linked devices, like WhatsApp Web. " +
                        "Your glasses show WhatsApp once the link is done."
                } else {
                    explanation
                }
                steps.highlight(0)
            }
            authenticated -> {
                accountTitle.text = "Linked"
                accountSub.text = explanation
            }
            else -> {
                accountTitle.text = WhatsAppCopy.headline(view)
                accountSub.text = explanation.ifEmpty { "Check Linked devices in WhatsApp." }
            }
        }
        steps.view.visibility = if (view.canLink || view.busy || pairing) View.VISIBLE else View.GONE
        showCode(view.code)
        codeBlock.visibility = if (pairing) View.VISIBLE else View.GONE
        linkForm.visibility = if (view.canLink) View.VISIBLE else View.GONE
        phone.isEnabled = view.canLink
        link.isEnabled = view.canLink
        unlink.visibility = if (!view.busy && !view.canLink) View.VISIBLE else View.GONE
    }

    private fun showCode(value: String) {
        // The code leaves the clipboard with the pairing, but only if the owner put it there.
        if (value.isEmpty() && codeCopied) clearOwnClip()
        if (value == shownCode) return
        shownCode = value
        copyCode.text = "Copy code"
        code.set(value)
    }

    // ---- clipboard -------------------------------------------------------------------

    private val clipboard get() = getSystemService(ClipboardManager::class.java)

    /** An explicit tap only; the clip is flagged sensitive so the system preview hides it. */
    private fun copyShownCode() {
        if (shownCode.isEmpty()) return
        val clip = ClipData.newPlainText(CLIP_LABEL, shownCode)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        clipboard.setPrimaryClip(clip)
        codeCopied = true
        copyCode.text = "Copied"
    }

    private fun clearOwnClip() {
        codeCopied = false
        val manager = clipboard
        if (manager.primaryClipDescription?.label != CLIP_LABEL) return
        if (Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip()
        else manager.setPrimaryClip(ClipData.newPlainText("", ""))
    }

    private companion object {
        /** The session's label for a linked, connected, logged-in device. */
        val AUTHENTICATED = whatsappConnectionStatus("linked", connected = true, loggedIn = true, blocker = "")
        const val CLIP_LABEL = "WhatsApp pairing code"
        val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
    }
}
