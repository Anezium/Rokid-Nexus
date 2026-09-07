package com.anezium.rokidbus.plugin.agents

import android.os.SystemClock
import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusCardLine
import com.anezium.rokidbus.client.plugin.NexusNotice
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusReader
import com.anezium.rokidbus.client.plugin.NexusReaderAnchor
import com.anezium.rokidbus.client.plugin.NexusReaderSegment
import com.anezium.rokidbus.client.plugin.NexusReaderSegmentKind
import com.anezium.rokidbus.client.plugin.NexusRowTone
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.EditableSurfaceField
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class AgentsPluginService : NexusPluginService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var surface: NexusSurfaceSession? = null
    private var opened = false
    private var page = Page.BOARD
    private var selectedKey: String? = null
    private var actionIndex = 0
    private var lastDirectionAt = Long.MIN_VALUE
    private var decisionOpenedAt = 0L
    private var pendingDecision: AgentApproval? = null
    private var allowSelected = false
    private var composeSession: String? = null
    private var activeSurfaceId = "agents"
    private var noticeTarget: String? = null
    private var status = ""
    private val fingerprints = mutableMapOf<String, String>()
    private val attention = AttentionDecisionEngine(fingerprints::get) { key, value -> fingerprints[key] = value }

    private enum class Page { BOARD, ACTIONS, READER, COMPOSE, APPROVAL_DETAIL, DECISION }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            combine(AgentsRuntime.store.sessions, AgentsRuntime.store.conversation,
                AgentsRuntime.store.approvals, LitterRuntime.client.message) { _, _, _, message -> message }
                .collect { message ->
                    status = message
                    if (opened) {
                        // Coalesce token bursts before crossing the glasses transport.
                        delay(120)
                        render()
                        raiseAttention()
                    }
                }
        }
    }

    override fun onNexusOpen() {
        opened = true
        AgentsRuntime.hudOpen = true
        page = Page.BOARD
        pendingDecision = null
        composeSession = null
        activeSurfaceId = "agents"
        surface = nexusSurfaceSession(activeSurfaceId)
        LitterRuntime.acquire(this, this)
        render(show = true)
    }

    override fun onNexusClose() {
        opened = false
        AgentsRuntime.hudOpen = false
        pendingDecision = null
        composeSession = null
        surface = null
        LitterRuntime.release(this)
    }

    override fun onDestroy() {
        LitterRuntime.release(this)
        scope.cancel()
        super.onDestroy()
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (!opened || event.action != KeyEvent.ACTION_DOWN) return
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            when (page) {
                Page.BOARD -> surface?.hide()
                Page.ACTIONS -> { page = Page.BOARD; pendingDecision = null }
                else -> { page = Page.ACTIONS; composeSession = null; pendingDecision = null }
            }
            render()
            return
        }
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> 1
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> -1
            else -> 0
        }
        if (direction != 0) {
            val now = SystemClock.elapsedRealtime()
            if (lastDirectionAt != Long.MIN_VALUE && now - lastDirectionAt < 250) return
            lastDirectionAt = now
            when (page) {
                Page.BOARD -> {
                    val sessions = AgentsRuntime.store.sessions.value
                    if (sessions.isNotEmpty()) {
                        val index = sessions.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
                        selectedKey = sessions[Math.floorMod(index + direction, sessions.size)].key
                    }
                }
                Page.ACTIONS -> actionIndex = Math.floorMod(actionIndex + direction, actions().size)
                Page.DECISION -> allowSelected = !allowSelected
                else -> return
            }
            render()
            return
        }
        if (event.keyCode != KeyEvent.KEYCODE_ENTER && event.keyCode != KeyEvent.KEYCODE_DPAD_CENTER) return
        when (page) {
            Page.BOARD -> selected()?.let { session ->
                selectedKey = session.key
                page = Page.ACTIONS
                actionIndex = 0
                LitterRuntime.run { openSession(session) }
            }
            Page.ACTIONS -> activateAction()
            Page.READER -> page = Page.ACTIONS
            Page.APPROVAL_DETAIL -> {
                page = Page.DECISION
                allowSelected = false
                decisionOpenedAt = SystemClock.elapsedRealtime()
            }
            Page.DECISION -> {
                if (SystemClock.elapsedRealtime() - decisionOpenedAt < 600) return
                pendingDecision?.let { approval ->
                    if (allowSelected && !canAllowOnHud(approval)) {
                        status = "Review and approve the complete request on the phone."
                        render()
                        return
                    }
                    LitterRuntime.client.decide(approval.requestId, approval.sessionId, allowSelected)
                }
                pendingDecision = null
                page = Page.ACTIONS
            }
            Page.COMPOSE -> Unit
        }
        render()
    }

    override fun onNexusSurfaceTextCommitted(surfaceId: String, text: String, cancelled: Boolean) {
        val id = composeSession ?: return
        if (!opened || page != Page.COMPOSE || surfaceId != activeSurfaceId) return
        composeSession = null
        page = Page.ACTIONS
        if (!cancelled && text.isNotBlank()) LitterRuntime.run { submit(id, text) }
        render()
    }

    override fun onNexusNoticeInput(event: NexusInputEvent) {
        if (!opened || event.action != KeyEvent.ACTION_DOWN) return
        if (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            page = Page.BOARD
            noticeTarget?.let { selectedKey = it }
            render()
        }
    }

    private fun selected(): AgentSession? = AgentsRuntime.store.sessions.value.let { sessions ->
        sessions.firstOrNull { it.key == selectedKey } ?: sessions.firstOrNull()
    }

    private fun actions(): List<String> = buildList {
        add("Read conversation")
        add("Write a follow-up")
        if (selected()?.let { AgentsRuntime.store.approvalFor(it.key) } != null) add("Review approval")
        add("Back to sessions")
    }

    private fun activateAction() {
        val session = selected() ?: return
        when (actions().getOrNull(actionIndex)) {
            "Read conversation" -> page = Page.READER
            "Write a follow-up" -> if (nexusClient?.supportsEditableSurface == true) {
                composeSession = session.id
                page = Page.COMPOSE
                val previous = surface
                activeSurfaceId = "reply-${UUID.randomUUID()}"
                surface = nexusSurfaceSession(activeSurfaceId)
                render(show = true)
                previous?.hide()
            } else status = "Write the follow-up in Agents on the phone."
            "Review approval" -> {
                pendingDecision = AgentsRuntime.store.approvalFor(session.key)
                page = Page.APPROVAL_DETAIL
            }
            else -> page = Page.BOARD
        }
    }

    private fun render(show: Boolean = false) {
        if (!opened) return
        val surface = surface ?: return
        val approval = pendingDecision?.takeIf { pending -> AgentsRuntime.store.approvals.value.any { it.requestId == pending.requestId } }
        if (page in setOf(Page.APPROVAL_DETAIL, Page.DECISION) && approval == null) {
            pendingDecision = null
            page = Page.ACTIONS
        }
        if (page == Page.READER || page == Page.APPROVAL_DETAIL) {
            val text = if (page == Page.APPROVAL_DETAIL) {
                if (approval != null && canAllowOnHud(approval)) approval.detail ?: approval.summary
                else "Review the full request on the phone or computer. You can deny it here.\n\n${approval?.summary.orEmpty()}"
            }
            else AgentsRuntime.store.conversation.value?.takeIf { it.sessionId == selected()?.id }?.let { conversation ->
                if (conversation.loading) "Loading…" else conversation.messages.takeLast(30).joinToString("\n\n") {
                    "${if (it.role == MessageRole.ASSISTANT) "AGENT" else it.role.label}\n${it.text}"
                }
            }.orEmpty()
            val reader = NexusReader(title = selected()?.displayTitle?.take(120) ?: "Agents",
                subtitle = if (page == Page.APPROVAL_DETAIL) "Review the complete request" else "Live conversation",
                footer = if (page == Page.APPROVAL_DETAIL) "tap decision · back cancel" else "back actions",
                handlesBack = true, contentKey = "${page.name}:${selected()?.id.orEmpty()}".digest(),
                anchor = if (page == Page.APPROVAL_DETAIL) NexusReaderAnchor.TOP else NexusReaderAnchor.BOTTOM,
                segments = text.takeLast(10_000).ifBlank { "No messages yet." }.chunked(4_000).map {
                    NexusReaderSegment(NexusReaderSegmentKind.PROSE, it)
                })
            if (show) surface.showReader(reader) else surface.updateReader(reader)
            return
        }
        val rows = when (page) {
            Page.BOARD -> {
                val sessions = AgentsRuntime.store.sessions.value
                val index = sessions.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
                selectedKey = sessions.getOrNull(index)?.key
                val start = (index - 25).coerceIn(0, (sessions.size - 60).coerceAtLeast(0))
                sessions.drop(start).take(60).map { session ->
                    NexusCardLine(text = session.displayTitle.singleLine(200),
                        sub = if (session.stale) "disconnected" else (session.pendingRequest?.summary ?: session.lastAssistantText ?: session.status.wireValue).singleLine(180),
                        tone = if (session.status == AgentStatus.NEEDS_YOU) NexusRowTone.ALERT else NexusRowTone.NORMAL,
                        selected = session.key == selectedKey)
                }.ifEmpty { listOf(NexusCardLine("Connect a server in Agents on the phone", tone = NexusRowTone.DIM)) }
            }
            Page.ACTIONS -> actions().mapIndexed { index, action -> NexusCardLine(action, selected = index == actionIndex) }
            Page.DECISION -> listOf(NexusCardLine("Deny", selected = !allowSelected),
                NexusCardLine("Allow once", sub = if (approval?.let(::canAllowOnHud) == true) "Only this request" else "Review on the phone", selected = allowSelected))
            else -> emptyList()
        }
        val card = NexusCard(title = if (page == Page.BOARD) "Agents" else selected()?.displayTitle?.take(120) ?: "Agents",
            lines = emptyList(), richLines = rows, subtitle = status.singleLine(200),
            footer = if (page == Page.BOARD) "swipe sessions · tap open · back exit" else "swipe choose · tap select · back",
            handlesBack = true, contentKey = "${page.name}:${selectedKey.orEmpty()}".digest(),
            editable = if (page == Page.COMPOSE) EditableSurfaceField(label = "Follow-up", placeholder = "What should the agent do?", submitLabel = "Send") else null)
        if (show) surface.showCard(card) else surface.updateCard(card)
    }

    private fun raiseAttention() {
        val client = nexusClient ?: return
        if (!opened || !client.supportsNoticeSurface) return
        val pending = attention.pending(AgentsRuntime.store.sessions.value.filterNot { it.stale })
        val item = pending.firstOrNull() ?: return
        val notice = NexusNotice(title = item.session.displayTitle.singleLine(32),
            body = (item.session.pendingRequest?.summary ?: "The agent needs your attention.").singleLine(240),
            footer = "TAP open", interactive = true, ttlMs = 8_000)
        if (client.showNotice(notice) == NexusSdkResult.SENT) {
            attention.commit(item)
            noticeTarget = item.session.key
        }
    }

    private fun canAllowOnHud(approval: AgentApproval): Boolean =
        LitterRuntime.client.canAllow(approval.requestId) && (approval.detail ?: approval.summary).length <= 10_000

    private fun String.digest(): String = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        // Kept for source compatibility with the unregistered prototype monitor.
        const val ACTION_MONITOR_ACTIVE = "com.anezium.rokidbus.plugin.agents.action.MONITOR_ACTIVE"
    }
}
