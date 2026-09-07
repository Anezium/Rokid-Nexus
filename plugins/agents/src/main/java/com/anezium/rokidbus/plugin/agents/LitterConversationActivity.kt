package com.anezium.rokidbus.plugin.agents

import android.app.Activity
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.NexusUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class LitterConversationActivity : Activity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionId: String? = null
    private var active = false
    private var opening = false
    private lateinit var prompt: EditText
    private lateinit var folder: EditText
    private lateinit var send: Button
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var approvals: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        sessionId = savedInstanceState?.getString(SESSION_ID) ?: intent.getStringExtra(SESSION_ID)
        buildUi()
        uiScope.launch { LitterRuntime.client.message.collect { status.text = it } }
        uiScope.launch {
            combine(AgentsRuntime.store.connections, AgentsRuntime.store.sessions) { connections, sessions -> connections to sessions }
                .collect { (connections, sessions) ->
                    if (active && !opening && connections[AgentProvider.CODEX]?.state == ConnectionState.CONNECTED) {
                        val id = sessionId
                        if (id != null && AgentsRuntime.store.conversation.value?.sessionId != id) {
                            (sessions.firstOrNull { it.id == id } ?: savedSession(id)).let { session ->
                                opening = true
                                LitterRuntime.run { try { openSession(session) } finally { opening = false } }
                            }
                        }
                    }
                    send.isEnabled = active && connections[AgentProvider.CODEX]?.state == ConnectionState.CONNECTED
                }
        }
        uiScope.launch { AgentsRuntime.store.conversation.collect { conversation ->
            if (conversation != null && conversation.sessionId == sessionId) {
                transcript.text = if (conversation.loading) "Loading conversation…" else conversation.messages.joinToString("\n\n") {
                    "${if (it.role == MessageRole.ASSISTANT) "AGENT" else it.role.label}\n${it.text}"
                }.ifEmpty { "The session is ready for a prompt." }
            }
        } }
        uiScope.launch { AgentsRuntime.store.approvals.collect { renderApprovals() } }
    }

    override fun onStart() {
        super.onStart()
        active = true
        LitterRuntime.acquire(this, this)
        send.isEnabled = AgentsRuntime.store.connections.value[AgentProvider.CODEX]?.state == ConnectionState.CONNECTED
        sessionId?.let { id ->
            (AgentsRuntime.store.sessions.value.firstOrNull { it.id == id } ?: savedSession(id)).let { session ->
                if (AgentsRuntime.store.connections.value[AgentProvider.CODEX]?.state == ConnectionState.CONNECTED) {
                    LitterRuntime.run { openSession(session) }
                }
            }
        }
    }

    override fun onStop() { active = false; LitterRuntime.release(this); super.onStop() }
    override fun onDestroy() { uiScope.cancel(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString(SESSION_ID, sessionId); super.onSaveInstanceState(outState) }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        status = NexusUi.cardBody(this, "Connecting…")
        transcript = NexusUi.cardBody(this, if (sessionId == null) "Choose a server folder and describe the work." else "Loading conversation…").apply { setTextIsSelectable(true) }
        approvals = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        folder = NexusUi.field(this, "Project folder on the server").apply {
            setText(runCatching { LitterEndpointStore(this@LitterConversationActivity).load()?.cwd }.getOrNull().orEmpty())
            filters = arrayOf(InputFilter.LengthFilter(4_096))
            visibility = if (sessionId == null) View.VISIBLE else View.GONE
        }
        prompt = NexusUi.field(this, "Write a prompt or follow-up").apply {
            setSingleLine(false); minLines = 3; maxLines = 6; gravity = Gravity.TOP
            filters = arrayOf(InputFilter.LengthFilter(LitterProtocol.MAX_TEXT))
            isSaveEnabled = false
        }
        send = NexusUi.pillButton(this, if (sessionId == null) "Start session" else "Send follow-up").apply {
            isEnabled = false
            setOnClickListener { submit() }
        }
        val content = NexusUi.contentColumn(this).apply {
            addView(status, NexusUi.block())
            addView(approvals, NexusUi.block())
            addView(transcript, NexusUi.block())
        }
        setContentView(NexusUi.fixedRoot(this).apply {
            addView(NexusUi.textButton(this@LitterConversationActivity, "‹ Sessions").apply { setOnClickListener { finish() } }, NexusUi.block())
            addView(NexusUi.screen(this@LitterConversationActivity, content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(NexusUi.contentColumn(this@LitterConversationActivity).apply {
                addView(folder, NexusUi.block())
                addView(prompt, NexusUi.block())
                addView(send, NexusUi.block())
                addView(NexusUi.textButton(this@LitterConversationActivity, "Stop current turn").apply {
                    setOnClickListener { sessionId?.let { id -> LitterRuntime.run { interrupt(id) } } }
                }, NexusUi.block())
            }, NexusUi.block())
        })
    }

    private fun submit() {
        val text = prompt.text.toString().trim()
        if (text.isBlank()) { status.text = "Enter a prompt first."; return }
        send.isEnabled = false
        val id = sessionId
        val cwd = folder.text.toString().trim()
        LitterRuntime.run {
            try {
                if (id == null) {
                    sessionId = createSession(cwd)
                    folder.visibility = View.GONE
                    send.text = "Send follow-up"
                }
                submit(sessionId ?: id ?: return@run, text)
                prompt.setText("")
                renderApprovals()
            } finally { send.isEnabled = active && AgentsRuntime.store.connections.value[AgentProvider.CODEX]?.state == ConnectionState.CONNECTED }
        }
    }

    private fun renderApprovals() {
        approvals.removeAllViews()
        AgentsRuntime.store.approvals.value.filter { it.sessionId == sessionId }.forEach { approval ->
            approvals.addView(NexusUi.card(this).apply {
                addView(NexusUi.cardTitle(this@LitterConversationActivity, "${approval.tool} · approval required"))
                addView(NexusUi.cardBody(this@LitterConversationActivity, approval.detail ?: approval.summary).apply { setTextIsSelectable(true) })
                addView(NexusUi.pillButton(this@LitterConversationActivity, "Allow this request once").apply {
                    isEnabled = LitterRuntime.client.canAllow(approval.requestId)
                    setOnClickListener { decide(approval, true) }
                })
                addView(NexusUi.outlinePillButton(this@LitterConversationActivity, "Deny").apply {
                    setOnClickListener { decide(approval, false) }
                })
            }, NexusUi.block())
        }
    }

    private fun decide(approval: AgentApproval, allow: Boolean) {
        if (!LitterRuntime.client.decide(approval.requestId, approval.sessionId, allow)) status.text = "This request expired or disconnected. Review it on the computer."
        renderApprovals()
    }

    private fun savedSession(id: String) = AgentSession(id = id, provider = AgentProvider.CODEX, status = AgentStatus.IDLE)

    companion object { const val SESSION_ID = "litterSessionId" }
}
