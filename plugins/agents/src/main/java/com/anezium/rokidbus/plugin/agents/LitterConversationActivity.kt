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
import kotlinx.coroutines.launch

class LitterConversationActivity : Activity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var selection: LitterConversationSelection
    private val sessionId: String? get() = selection.sessionId
    private var active = false
    private var opening = false
    private var sending = false
    private lateinit var prompt: EditText
    private lateinit var folder: EditText
    private lateinit var send: Button
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var approvals: LinearLayout
    private lateinit var conversationTitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        selection = LitterConversationSelection(savedInstanceState?.getString(SESSION_ID) ?: intent.getStringExtra(SESSION_ID))
        buildUi()
        uiScope.launch { LitterRuntime.client.message.collect { status.text = it } }
        uiScope.launch {
            AgentsRuntime.store.connections.collect { resumeIfNeeded(); updateSendEnabled() }
        }
        uiScope.launch { AgentsRuntime.store.conversation.collect { conversation ->
            if (conversation != null) {
                selection.observe(conversation.sessionId, prompt.text.toString())?.let { prompt.setText(it) }
            }
            if (conversation != null && conversation.sessionId == sessionId) {
                conversationTitle.text = AgentsRuntime.store.sessions.value.firstOrNull { it.id == sessionId }?.displayTitle ?: "Session"
                transcript.text = if (conversation.loading) "Loading conversation…" else conversation.messages.joinToString("\n\n") {
                    "${if (it.role == MessageRole.ASSISTANT) "AGENT" else it.role.label}\n${it.text}"
                }.ifEmpty { "The session is ready for a prompt." }
                renderApprovals()
            }
            updateSendEnabled()
        } }
        uiScope.launch { AgentsRuntime.store.approvals.collect { renderApprovals() } }
    }

    override fun onStart() {
        super.onStart()
        active = true
        LitterRuntime.acquire(this, this)
        resumeIfNeeded()
        updateSendEnabled()
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
        conversationTitle = NexusUi.cardTitle(this, if (sessionId == null) "New session" else "Session")
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
            addView(conversationTitle, NexusUi.block())
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
        if (sending) return
        sending = true
        send.isEnabled = false
        val id = sessionId
        val cwd = folder.text.toString().trim()
        LitterRuntime.run {
            try {
                val targetId = if (id == null) {
                    val created = createSession(cwd)
                    selection.created(created)
                    conversationTitle.text = AgentsRuntime.store.sessions.value.firstOrNull { it.id == created }?.displayTitle ?: "Session"
                    transcript.text = "The session is ready for a prompt."
                    folder.visibility = View.GONE
                    send.text = "Send follow-up"
                    created
                } else id
                submit(targetId, text)
                selection.sent(targetId, text)
                if (sessionId == targetId && prompt.text.toString().trim() == text) prompt.setText("")
                renderApprovals()
            } finally { sending = false; updateSendEnabled() }
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

    private fun resumeIfNeeded() {
        val connected = AgentsRuntime.store.connections.value[AgentProvider.CODEX]?.state == ConnectionState.CONNECTED
        val id = selection.requestResume(active && !opening, connected, AgentsRuntime.store.conversation.value?.sessionId) ?: return
        val session = AgentsRuntime.store.sessions.value.firstOrNull { it.id == id } ?: savedSession(id)
        opening = true
        LitterRuntime.run { try { openSession(session) } finally { opening = false; updateSendEnabled() } }
    }

    private fun updateSendEnabled() {
        val conversation = AgentsRuntime.store.conversation.value
        send.isEnabled = active && !opening && !sending &&
            AgentsRuntime.store.connections.value[AgentProvider.CODEX]?.state == ConnectionState.CONNECTED &&
            (sessionId == null || (conversation?.sessionId == sessionId && conversation?.loading == false))
    }

    companion object { const val SESSION_ID = "litterSessionId" }
}
