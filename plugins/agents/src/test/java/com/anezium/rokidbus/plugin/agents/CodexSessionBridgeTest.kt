package com.anezium.rokidbus.plugin.agents

import com.anezium.rokidbus.plugin.agents.alleycat.ApprovalVerdict
import com.anezium.rokidbus.plugin.agents.alleycat.CodexInbound
import com.anezium.rokidbus.plugin.agents.alleycat.CodexThread
import com.anezium.rokidbus.plugin.agents.alleycat.CodexTurn
import com.anezium.rokidbus.plugin.agents.alleycat.JsonRpcId
import com.anezium.rokidbus.plugin.agents.alleycat.ThreadListPage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexSessionBridgeTest {
    private val computer = DirectComputer("direct-box", "box", "ws://box.example:4000")

    @Test
    fun threadListReplacesThatComputerInTheStore() {
        val store = AgentSessionStore()
        val bridge = CodexSessionBridge(store, computer, nowMs = { 1_000L })
        bridge.publishThreads(
            ThreadListPage(
                data = listOf(
                    CodexThread("thr_1", preview = "hello", raw = JSONObject().put("id", "thr_1")),
                ),
                nextCursor = null,
            ),
        )
        val session = store.sessions.value.single()
        assertEquals("thr_1", session.id)
        assertEquals(AgentProvider.CODEX, session.provider)
        assertEquals("direct-box", session.machineId)
        assertEquals("hello", session.title)
        assertEquals(AgentStatus.IDLE, session.status)
    }

    @Test
    fun itemDeltaUpdatesTranscriptWithoutAssumingApprovals() {
        val store = AgentSessionStore()
        val bridge = CodexSessionBridge(store, computer, nowMs = { 2_000L })
        bridge.publishThreads(
            ThreadListPage(
                data = listOf(CodexThread("thr_1", raw = JSONObject().put("id", "thr_1"))),
                nextCursor = null,
            ),
        )
        bridge.openThread("thr_1")
        bridge.onNotification(
            CodexInbound.Notification(
                method = "item/agentMessage/delta",
                params = JSONObject().put("threadId", "thr_1").put("delta", "Hi"),
            ),
        )
        assertEquals(AgentStatus.WORKING, store.sessions.value.single().status)
        assertEquals("Hi", store.conversation.value!!.messages.single().text)
        assertTrue(store.approvals.value.isEmpty())
    }

    @Test
    fun approvalRoundTripThroughTheStoreUsesFourVerdicts() {
        val store = AgentSessionStore()
        val bridge = CodexSessionBridge(store, computer, nowMs = { 3_000L })
        bridge.publishThreads(
            ThreadListPage(
                data = listOf(CodexThread("thr_1", raw = JSONObject().put("id", "thr_1"))),
                nextCursor = null,
            ),
        )
        bridge.onApproval(
            CodexInbound.ApprovalRequest(
                id = JsonRpcId.NumberId(9),
                method = "item/commandExecution/requestApproval",
                params = JSONObject().put("threadId", "thr_1").put("command", "ls"),
            ),
        )
        val approval = store.approvals.value.single()
        assertEquals("n:9", approval.requestId)
        assertTrue(approval.fourVerdicts)
        assertEquals(AgentStatus.NEEDS_YOU, store.sessions.value.single().status)
        assertEquals(ApprovalDecision.ACCEPT, ApprovalVerdict.ACCEPT.toDecision())
        assertEquals(ApprovalVerdict.DECLINE, ApprovalDecision.DECLINE.toVerdict())
        store.resolveApproval(approval.requestId)
        assertTrue(store.approvals.value.isEmpty())
    }

    @Test
    fun turnLifecycleGoesWorkingThenIdle() {
        val store = AgentSessionStore()
        val bridge = CodexSessionBridge(store, computer, nowMs = { 4_000L })
        bridge.publishThreads(
            ThreadListPage(
                data = listOf(CodexThread("thr_1", raw = JSONObject().put("id", "thr_1"))),
                nextCursor = null,
            ),
        )
        bridge.onTurnStarted(
            "thr_1",
            CodexTurn("turn_1", status = "inProgress", raw = JSONObject().put("id", "turn_1")),
        )
        assertEquals(AgentStatus.WORKING, store.sessions.value.single().status)
        bridge.onNotification(
            CodexInbound.Notification(
                method = "turn/completed",
                params = JSONObject().put("threadId", "thr_1").put("status", "completed"),
            ),
        )
        val done = store.sessions.value.single()
        assertEquals(AgentStatus.IDLE, done.status)
        assertNull(done.turn)
    }

    @Test
    fun connectionFailureIsAClearStateNotAHang() {
        val store = AgentSessionStore()
        val bridge = CodexSessionBridge(store, computer, nowMs = { 5_000L })
        bridge.onConnecting()
        assertEquals(ConnectionState.CONNECTING, store.connections.value.getValue(AgentProvider.CODEX).state)
        bridge.onFailed(ConnectionState.AUTH_FAILED, "app-server rejected the connection")
        val state = store.connections.value.getValue(AgentProvider.CODEX)
        assertEquals(ConnectionState.AUTH_FAILED, state.state)
        assertEquals("app-server rejected the connection", state.detail)
        assertTrue(store.sessions.value.isEmpty())
    }
}
