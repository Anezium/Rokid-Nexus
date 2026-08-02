package com.anezium.rokidbus.plugin.t3code

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import java.util.concurrent.Executors

class T3SettingsActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val settings by lazy { T3Settings(this) }
    private val http = T3HttpApi()
    private lateinit var statusText: TextView
    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var codeField: EditText
    private lateinit var linkButton: Button
    private lateinit var inlineStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        buildUi()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        http.cancel()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        val content = NexusUi.contentColumn(this).apply {
            addView(NexusUi.sectionRow(this@T3SettingsActivity, "Status"), NexusUi.block())
            addView(BusTheme.gap(this@T3SettingsActivity, 10))
            addView(statusCard(), NexusUi.block())
            addView(BusTheme.gap(this@T3SettingsActivity, 24))
            addView(NexusUi.sectionRow(this@T3SettingsActivity, "Computer"), NexusUi.block())
            addView(BusTheme.gap(this@T3SettingsActivity, 10))
            addView(linkCard(), NexusUi.block())
            addView(BusTheme.gap(this@T3SettingsActivity, 8))
            addView(forgetCard(), NexusUi.block())
            addView(BusTheme.gap(this@T3SettingsActivity, 8))
            addView(
                NexusUi.card(this@T3SettingsActivity).apply {
                    addView(NexusUi.rowSub(this@T3SettingsActivity, "Create a code on the PC:"))
                    addView(BusTheme.gap(this@T3SettingsActivity, 5))
                    addView(NexusUi.cardBody(this@T3SettingsActivity, "t3 auth pairing create"))
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@T3SettingsActivity, 24))
            addView(NexusUi.sectionRow(this@T3SettingsActivity, "Plugin"), NexusUi.block())
            addView(BusTheme.gap(this@T3SettingsActivity, 10))
            addView(
                NexusUi.uninstallCard(this@T3SettingsActivity, "T3 Code") {
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
                },
                NexusUi.block(),
            )
        }
        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@T3SettingsActivity,
                    R.drawable.nexus_glyph_t3code,
                    "T3 Code",
                    "Remote agent threads on Rokid glasses",
                ),
                NexusUi.block(),
            )
            addView(
                NexusUi.screen(this@T3SettingsActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
    }

    private fun statusCard(): LinearLayout = NexusUi.card(this).apply {
        addView(NexusUi.cardTitle(this@T3SettingsActivity, "T3 Code server"))
        addView(BusTheme.gap(this@T3SettingsActivity, 5))
        addView(NexusUi.cardBody(this@T3SettingsActivity, "").also { statusText = it })
    }

    private fun linkCard(): LinearLayout = NexusUi.card(this).apply {
        addView(NexusUi.rowTitle(this@T3SettingsActivity, "Host"))
        addView(BusTheme.gap(this@T3SettingsActivity, 6))
        addView(NexusUi.field(this@T3SettingsActivity, "192.168.1.117").also { field ->
            hostField = field
            field.setText(settings.savedHost())
        }, NexusUi.block())
        addView(BusTheme.gap(this@T3SettingsActivity, 12))
        addView(NexusUi.rowTitle(this@T3SettingsActivity, "Port"))
        addView(BusTheme.gap(this@T3SettingsActivity, 6))
        addView(NexusUi.field(this@T3SettingsActivity, T3Settings.DEFAULT_PORT.toString()).also { field ->
            portField = field
            field.inputType = InputType.TYPE_CLASS_NUMBER
            field.setText(settings.savedPort().toString())
        }, NexusUi.block())
        addView(BusTheme.gap(this@T3SettingsActivity, 12))
        addView(NexusUi.rowTitle(this@T3SettingsActivity, "Pairing code"))
        addView(BusTheme.gap(this@T3SettingsActivity, 6))
        addView(NexusUi.field(this@T3SettingsActivity, "12-character code").also { field ->
            codeField = field
            field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }, NexusUi.block())
        addView(BusTheme.gap(this@T3SettingsActivity, 12))
        addView(NexusUi.outlinePillButton(this@T3SettingsActivity, "Link").also { button ->
            linkButton = button
            button.setOnClickListener { link() }
        }, NexusUi.block())
        addView(BusTheme.gap(this@T3SettingsActivity, 10))
        addView(NexusUi.statusLine(this@T3SettingsActivity).also { inlineStatus = it }, NexusUi.block())
    }

    private fun forgetCard(): LinearLayout = NexusUi.pressableCard(this).apply {
        addView(
            LinearLayout(this@T3SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(NexusUi.rowTitle(this@T3SettingsActivity, "Forget this computer"))
                addView(BusTheme.gap(this@T3SettingsActivity, 4))
                addView(NexusUi.rowSub(this@T3SettingsActivity, "Remove the saved bearer token"))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(NexusUi.rowSub(this@T3SettingsActivity, "FORGET ›").apply { setTextColor(NexusUi.DANGER) })
        setOnClickListener {
            settings.forget()
            codeField.text?.clear()
            inlineStatus.text = "Forgotten."
            inlineStatus.setTextColor(NexusUi.INK2)
            refreshStatus()
        }
    }

    private fun link() {
        val host = hostField.text.toString()
        val port = portField.text.toString().toIntOrNull()
        val code = codeField.text.toString()
        if (port == null) {
            showInlineError("Enter a valid port")
            return
        }
        linkButton.isEnabled = false
        inlineStatus.setTextColor(NexusUi.INK2)
        inlineStatus.text = "Testing and linking…"
        worker.execute {
            val result = http.link(host, port, code)
            main.post {
                if (isDestroyed) return@post
                linkButton.isEnabled = true
                when (result) {
                    is T3LinkResult.Success -> {
                        settings.save(result.endpoint)
                        codeField.text?.clear()
                        inlineStatus.setTextColor(NexusUi.GREEN)
                        inlineStatus.text = "Linked."
                        refreshStatus()
                    }
                    is T3LinkResult.Failure -> showInlineError(result.message)
                }
            }
        }
    }

    private fun refreshStatus() {
        if (!::statusText.isInitialized) return
        val endpoint = settings.endpoint()
        statusText.text = if (endpoint == null) {
            "Not linked"
        } else {
            "Linked to ${endpoint.label} · ${endpoint.host}:${endpoint.port}"
        }
    }

    private fun showInlineError(message: String) {
        inlineStatus.setTextColor(NexusUi.DANGER)
        inlineStatus.text = message
    }
}
