package com.anezium.rokidbus.plugin.agents

import com.anezium.rokidbus.plugin.agents.alleycat.ApprovalVerdict
import com.anezium.rokidbus.plugin.agents.alleycat.CodexInbound
import com.anezium.rokidbus.plugin.agents.alleycat.CodexThread
import com.anezium.rokidbus.plugin.agents.alleycat.CodexTurn
import com.anezium.rokidbus.plugin.agents.alleycat.JsonRpcId
import com.anezium.rokidbus.plugin.agents.alleycat.ThreadListPage
import org.json.JSONObject

/**
 * Pushes Codex app-server state into [AgentSessionStore]. Never logs a token,
 * URL userinfo, or transcript text.
 *
 * Approval requests are optional: hosts with `bypass_permissions` never send
 * them, and nothing here assumes they will.
 */
class CodexSessionBridge(
    private val store: AgentSessionStore,
    private val computer: CodexComputerRef,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    constructor(
        store: AgentSessionStore,
        computer: DirectComputer,
        nowMs: () -> Long = System::currentTimeMillis,
    ) : this(store, computer.asCodexRef(), nowMs)
    private val sessions = linkedMapOf<String, AgentSession>()
    private val openThreadId = java.util.concurrent.atomic.AtomicReference<String?>(null)

    fun onConnecting() {
        store.setConnection(AgentProvider.CODEX, ConnectionState.CONNECTING)
    }

    fun onConnected() {
        store.setConnection(AgentProvider.CODEX, ConnectionState.CONNECTED)
    }

    fun onDisconnected(detail: String? = null) {
        store.setConnection(AgentProvider.CODEX, ConnectionState.DISCONNECTED, detail)
        dropThisComputer()
    }

    fun onFailed(state: ConnectionState, detail: String) {
        store.setConnection(AgentProvider.CODEX, state, detail)
        dropThisComputer()
    }

    fun publishThreads(page: ThreadListPage) {
        val now = nowMs()
        val next = linkedMapOf<String, AgentSession>()
        page.data.forEach { thread ->
            val previous = sessions[thread.id]
            next[thread.id] = toSession(thread, previous, now)
        }
        sessions.clear()
        sessions.putAll(next)
        store.replaceMachineSessions(computer.computerId, AgentProvider.CODEX, next.values.toList(), now)
    }

    fun openThread(threadId: String) {
        openThreadId.set(threadId)
        val session = sessions[threadId] ?: return
        store.openConversation(session)
        store.setConversation(AgentProvider.CODEX, threadId, emptyList())
    }

    fun closeThread() {
        openThreadId.set(null)
        store.closeConversation()
    }

    fun onTurnStarted(threadId: String, turn: CodexTurn) {
        updateSession(threadId) { session ->
            session.copy(
                status = statusFromTurn(turn.status) ?: AgentStatus.WORKING,
                turn = AgentTurn(
                    lastTool = turn.status,
                    activeSince = nowMs(),
                ),
                lastActivityAt = nowMs(),
                pendingRequest = null,
            )
        }
    }

    fun onNotification(notification: CodexInbound.Notification) {
        val threadId = threadIdOf(notification.params) ?: openThreadId.get() ?: return
        val now = nowMs()
        val text = extractDisplayText(notification.params)
        when {
            notification.isCommandExecution -> {
                updateSession(threadId) { session ->
                    session.copy(
                        status = AgentStatus.WORKING,
                        turn = AgentTurn(
                            lastTool = toolName(notification.params) ?: session.turn?.lastTool,
                            activeSince = session.turn?.activeSince ?: now,
                        ),
                        lastActivityAt = now,
                    )
                }
                appendIfOpen(
                    threadId,
                    AgentMessage(
                        role = MessageRole.TOOL,
                        text = text ?: toolName(notification.params) ?: "command",
                        at = now,
                        tool = toolName(notification.params),
                    ),
                )
            }
            notification.isItemDelta -> {
                updateSession(threadId) { session ->
                    session.copy(
                        status = AgentStatus.WORKING,
                        lastAssistantText = text ?: session.lastAssistantText,
                        lastActivityAt = now,
                    )
                }
                if (text != null) {
                    appendIfOpen(
                        threadId,
                        AgentMessage(role = MessageRole.ASSISTANT, text = text, at = now),
                    )
                }
            }
            isTurnComplete(notification) -> {
                updateSession(threadId) { session ->
                    session.copy(
                        status = AgentStatus.IDLE,
                        turn = null,
                        lastActivityAt = now,
                    )
                }
            }
        }
    }

    fun onApproval(request: CodexInbound.ApprovalRequest) {
        val threadId = threadIdOf(request.params) ?: openThreadId.get() ?: return
        val now = nowMs()
        val summary = approvalSummary(request.params)
        val tool = toolName(request.params) ?: "tool"
        updateSession(threadId) { session ->
            session.copy(
                status = AgentStatus.NEEDS_YOU,
                pendingRequest = AgentPendingRequest(
                    kind = PendingRequestKind.PERMISSION,
                    summary = summary,
                    createdAt = now,
                ),
                lastActivityAt = now,
            )
        }
        store.upsertApproval(
            AgentApproval(
                requestId = request.id.toWire(),
                sessionId = threadId,
                provider = AgentProvider.CODEX,
                tool = tool,
                summary = summary,
                detail = approvalDetail(request.params),
                createdAt = now,
                fourVerdicts = true,
            ),
        )
    }

    private fun dropThisComputer() {
        store.clearApprovalsForMachine(computer.computerId)
        store.replaceMachineSessions(computer.computerId, AgentProvider.CODEX, emptyList())
        sessions.clear()
        openThreadId.set(null)
    }

    fun onThreadStarted(thread: CodexThread) {
        val now = nowMs()
        val session = toSession(thread, previous = null, now = now)
        sessions[thread.id] = session
        store.upsert(session, now)
    }

    private fun toSession(
        thread: CodexThread,
        previous: AgentSession?,
        now: Long,
    ): AgentSession {
        val cwd = thread.raw.optString("cwd").takeIf { it.isNotBlank() }
            ?: thread.raw.optString("cwdPath").takeIf { it.isNotBlank() }
        return AgentSession(
            id = thread.id,
            provider = AgentProvider.CODEX,
            machineId = computer.computerId,
            machineName = computer.name,
            title = thread.preview ?: previous?.title,
            cwd = cwd ?: previous?.cwd,
            project = previous?.project,
            status = previous?.status ?: AgentStatus.IDLE,
            statusDetail = previous?.statusDetail,
            lastActivityAt = previous?.lastActivityAt ?: now,
            lastAssistantText = previous?.lastAssistantText,
            turn = previous?.turn,
            pendingRequest = previous?.pendingRequest,
        )
    }

    private fun updateSession(threadId: String, transform: (AgentSession) -> AgentSession) {
        val current = sessions[threadId] ?: AgentSession(
            id = threadId,
            provider = AgentProvider.CODEX,
            machineId = computer.computerId,
            machineName = computer.name,
            status = AgentStatus.WORKING,
            lastActivityAt = nowMs(),
        )
        val next = transform(current)
        sessions[threadId] = next
        store.upsert(next)
    }

    private fun appendIfOpen(threadId: String, message: AgentMessage) {
        if (openThreadId.get() != threadId) return
        store.appendConversation(AgentProvider.CODEX, threadId, message)
    }

    companion object {
        fun threadIdOf(params: JSONObject?): String? {
            params ?: return null
            return firstNonBlank(
                params.optString("threadId"),
                params.optString("thread_id"),
                params.optJSONObject("thread")?.optString("id"),
                params.optJSONObject("item")?.optString("threadId"),
            )
        }

        fun extractDisplayText(params: JSONObject?): String? {
            params ?: return null
            return firstNonBlank(
                params.optString("delta"),
                params.optString("text"),
                params.optJSONObject("item")?.optString("text"),
                params.optJSONObject("item")?.optString("delta"),
            )?.take(MAX_HUD_TEXT_CHARS)
        }

        fun toolName(params: JSONObject?): String? {
            params ?: return null
            return firstNonBlank(
                params.optString("command"),
                params.optString("tool"),
                params.optJSONObject("item")?.optString("command"),
                params.optJSONObject("item")?.optString("name"),
            )?.take(MAX_HUD_LABEL_CHARS)
        }

        fun approvalSummary(params: JSONObject): String =
            firstNonBlank(
                params.optString("reason"),
                params.optString("summary"),
                params.optString("command"),
                params.optJSONObject("grant")?.optString("reason"),
            )?.take(MAX_HUD_LABEL_CHARS) ?: "Permission requested"

        fun approvalDetail(params: JSONObject): String? =
            firstNonBlank(
                params.optString("detail"),
                params.optJSONObject("grant")?.optString("command"),
            )?.take(MAX_HUD_TEXT_CHARS)

        fun statusFromTurn(status: String?): AgentStatus? = when (status?.lowercase()) {
            "inprogress", "in_progress", "running", "active" -> AgentStatus.WORKING
            "completed", "complete", "success", "idle" -> AgentStatus.IDLE
            "failed", "error", "cancelled", "canceled" -> AgentStatus.ERROR
            else -> null
        }

        private fun isTurnComplete(notification: CodexInbound.Notification): Boolean {
            val method = notification.method.lowercase()
            if (method.contains("complete") || method.contains("turn/completed")) return true
            val status = notification.params?.optString("status")?.lowercase()
            return status == "completed" || status == "complete"
        }

        private fun firstNonBlank(vararg values: String?): String? =
            values.firstOrNull { !it.isNullOrBlank() && it != "null" }
    }
}

fun ApprovalVerdict.toDecision(): ApprovalDecision = when (this) {
    ApprovalVerdict.ACCEPT -> ApprovalDecision.ACCEPT
    ApprovalVerdict.ACCEPT_FOR_SESSION -> ApprovalDecision.ACCEPT_FOR_SESSION
    ApprovalVerdict.DECLINE -> ApprovalDecision.DECLINE
    ApprovalVerdict.CANCEL -> ApprovalDecision.CANCEL
}

fun ApprovalDecision.toVerdict(): ApprovalVerdict? = when (this) {
    ApprovalDecision.ACCEPT -> ApprovalVerdict.ACCEPT
    ApprovalDecision.ACCEPT_FOR_SESSION -> ApprovalVerdict.ACCEPT_FOR_SESSION
    ApprovalDecision.DECLINE -> ApprovalVerdict.DECLINE
    ApprovalDecision.CANCEL -> ApprovalVerdict.CANCEL
    ApprovalDecision.ALLOW, ApprovalDecision.DENY -> null
}

