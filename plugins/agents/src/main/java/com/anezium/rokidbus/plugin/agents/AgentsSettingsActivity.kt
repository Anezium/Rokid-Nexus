package com.anezium.rokidbus.plugin.agents

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.client.ui.NexusUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AgentsSettingsActivity : Activity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val endpointStore by lazy { LitterEndpointStore(applicationContext) }
    private var saved: LitterEndpoint? = null
    private lateinit var name: EditText
    private lateinit var url: EditText
    private lateinit var token: EditText
    private lateinit var cwd: EditText
    private lateinit var insecure: Switch
    private lateinit var status: TextView
    private lateinit var sessions: LinearLayout
    private lateinit var more: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        buildUi()
        runCatching { endpointStore.load() }.onSuccess { endpoint ->
            saved = endpoint
            name.setText(endpoint?.name.orEmpty())
            url.setText(endpoint?.url.orEmpty())
            cwd.setText(endpoint?.cwd.orEmpty())
            insecure.isChecked = endpoint?.allowInsecureNetwork == true
            if (endpoint?.token?.isNotEmpty() == true) token.hint = "Token saved · leave empty to keep it"
        }.onFailure { status.text = "Saved credentials are unavailable. Enter and save the server again." }
        uiScope.launch { LitterRuntime.client.message.collect { status.text = it } }
        uiScope.launch { AgentsRuntime.store.sessions.collect { renderSessions(it) } }
        uiScope.launch { LitterRuntime.client.hasMore.collect { more.visibility = if (it) View.VISIBLE else View.GONE } }
    }

    override fun onStart() { super.onStart(); LitterRuntime.acquire(this, this) }
    override fun onStop() { LitterRuntime.release(this); super.onStop() }
    override fun onDestroy() { uiScope.cancel(); super.onDestroy() }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        name = field("Server name", 80)
        url = field("wss://computer.example:8390", 2_048).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        token = field("Bearer token, if required", 8_192).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveEnabled = false
        }
        cwd = field("Project folder on the server (optional filter)", 4_096)
        insecure = NexusUi.switch(this)
        status = NexusUi.cardBody(this, "Add a Litter-compatible Codex server.")
        sessions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        more = NexusUi.outlinePillButton(this, "Load more sessions").apply {
            visibility = View.GONE
            setOnClickListener { LitterRuntime.run { refresh(more = true) } }
        }
        val content = NexusUi.contentColumn(this).apply {
            addView(NexusUi.cardBody(this@AgentsSettingsActivity,
                "Open Codex sessions from a server supported by Litter. Progress and approval notices reach the glasses while Agents is open. Closing Agents disconnects; work already started continues on the server."), NexusUi.block())
            addView(status, NexusUi.block())
            addView(NexusUi.sectionRow(this@AgentsSettingsActivity, "Server"), NexusUi.block())
            listOf(name, url, token, cwd).forEach { addView(it, NexusUi.block()); addView(BusTheme.gap(this@AgentsSettingsActivity, 8)) }
            addView(NexusUi.switchRow(this@AgentsSettingsActivity, "Allow unencrypted network access",
                "For a network you explicitly trust. ws:// exposes prompts and tokens; prefer TLS or a loopback tunnel.", insecure), NexusUi.block())
            addView(NexusUi.pillButton(this@AgentsSettingsActivity, "Save and connect").apply { setOnClickListener { save() } }, NexusUi.block())
            addView(NexusUi.textButton(this@AgentsSettingsActivity, "Forget this server", danger = true).apply {
                setOnClickListener {
                    runCatching { endpointStore.forget() }.onSuccess {
                        saved = null; token.setText(""); url.setText(""); name.setText(""); cwd.setText("")
                        token.hint = "Bearer token, if required"
                        LitterRuntime.reload(this@AgentsSettingsActivity)
                    }.onFailure { toast("The phone could not remove the saved server.") }
                }
            }, NexusUi.block())
            addView(BusTheme.gap(this@AgentsSettingsActivity, 16))
            addView(NexusUi.sectionRow(this@AgentsSettingsActivity, "Sessions"), NexusUi.block())
            addView(NexusUi.pillButton(this@AgentsSettingsActivity, "New session").apply {
                setOnClickListener { startActivity(Intent(this@AgentsSettingsActivity, LitterConversationActivity::class.java)) }
            }, NexusUi.block())
            addView(NexusUi.outlinePillButton(this@AgentsSettingsActivity, "Refresh sessions").apply {
                setOnClickListener { LitterRuntime.run { refresh() } }
            }, NexusUi.block())
            addView(sessions, NexusUi.block())
            addView(more, NexusUi.block())
            addView(NexusUi.uninstallCard(this@AgentsSettingsActivity, "Agents") {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
            }, NexusUi.block())
        }
        setContentView(NexusUi.fixedRoot(this).apply {
            addView(NexusUi.pluginHeader(this@AgentsSettingsActivity, NexusPluginIcons.drawableFor("terminal"),
                "Agents", "Litter-compatible servers · alpha"), NexusUi.block())
            addView(NexusUi.screen(this@AgentsSettingsActivity, content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    private fun save() {
        val address = url.text.toString().trim()
        val credential = token.text.toString().ifEmpty { saved?.takeIf { it.url == address }?.token.orEmpty() }
        val endpoint = LitterEndpoint(name.text.toString().trim(), address, credential, insecure.isChecked, cwd.text.toString().trim())
        endpoint.validate()?.let { toast(it); return }
        runCatching { endpointStore.save(endpoint) }.onSuccess {
            saved = endpoint
            token.setText("")
            token.hint = if (credential.isEmpty()) "Bearer token, if required" else "Token saved · leave empty to keep it"
            LitterRuntime.reload(this)
        }.onFailure { toast("The phone could not encrypt and save this server.") }
    }

    private fun renderSessions(items: List<AgentSession>) {
        sessions.removeAllViews()
        if (items.isEmpty()) sessions.addView(NexusUi.rowSub(this, "No sessions yet. Connect a server or start a session."), NexusUi.block())
        items.forEach { session ->
            sessions.addView(NexusUi.card(this).apply {
                addView(NexusUi.rowTitle(this@AgentsSettingsActivity, session.displayTitle))
                addView(NexusUi.rowSub(this@AgentsSettingsActivity,
                    (if (session.stale) "Disconnected" else session.status.wireValue.replace('_', ' ')) +
                        session.cwd?.let { " · $it" }.orEmpty()))
                setOnClickListener {
                    startActivity(Intent(this@AgentsSettingsActivity, LitterConversationActivity::class.java)
                        .putExtra(LitterConversationActivity.SESSION_ID, session.id))
                }
                contentDescription = "${session.displayTitle}, ${session.status.wireValue}, open session"
            }, NexusUi.block())
        }
    }

    private fun field(hint: String, max: Int) = NexusUi.field(this, hint).apply { filters = arrayOf(InputFilter.LengthFilter(max)) }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
}

internal fun ProviderConnectionState.displayText(authFailure: String): String {
    val label = if (state == ConnectionState.AUTH_FAILED) authFailure else state.wireValue.uppercase()
    return detail?.takeIf { it.isNotBlank() && !it.equals(label, true) }?.let { "$label · $it" } ?: label
}
