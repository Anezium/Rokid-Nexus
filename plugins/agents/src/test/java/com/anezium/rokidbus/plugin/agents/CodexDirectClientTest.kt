package com.anezium.rokidbus.plugin.agents

import com.anezium.rokidbus.plugin.agents.alleycat.AlleycatException
import com.anezium.rokidbus.plugin.agents.alleycat.ApprovalVerdict
import com.anezium.rokidbus.plugin.agents.alleycat.QueueJsonPipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexDirectClientTest {
    private val computer = DirectComputer(
        computerId = "direct-test",
        name = "box",
        url = "ws://box.example:4000",
    )

    @Test
    fun threadListPropagatesIntoTheStore() = withClientScope { scope ->
        val pipe = QueueJsonPipe()
        pipe.enqueue(JSONObject("""{"id":1,"result":{"data":[{"id":"thr_1","preview":"hi"}]}}"""))
        val store = AgentSessionStore()
        val client = CodexDirectClient(store, scope, openPipe = { _ -> pipe })
        try {
            client.start(computer)
            await(store) { it.connections.value[AgentProvider.CODEX]?.state == ConnectionState.CONNECTED }
            assertEquals("thr_1", store.sessions.value.single().id)
            assertEquals("hi", store.sessions.value.single().title)
            assertEquals("direct-test", store.sessions.value.single().machineId)
        } finally {
            client.stop(clearSessions = true)
        }
    }

    @Test
    fun rejectedOpenIsAuthFailedNotAHang() = withClientScope { scope ->
        val store = AgentSessionStore()
        val client = CodexDirectClient(store, scope, openPipe = { _ ->
            throw AlleycatException("app-server rejected the connection")
        })
        try {
            client.start(computer)
            await(store) { it.connections.value[AgentProvider.CODEX]?.state == ConnectionState.AUTH_FAILED }
            assertEquals(
                "app-server rejected the connection",
                store.connections.value.getValue(AgentProvider.CODEX).detail,
            )
        } finally {
            client.stop(clearSessions = true)
        }
    }

    @Test
    fun openTimeoutSurfacesDisconnectedWithAClearDetail() = withClientScope { scope ->
        val store = AgentSessionStore()
        val client = CodexDirectClient(store, scope, openPipe = { _ ->
            throw AlleycatException("websocket open timed out")
        })
        try {
            client.start(computer)
            await(store) {
                val state = it.connections.value[AgentProvider.CODEX]
                state?.state == ConnectionState.DISCONNECTED &&
                    state.detail == "websocket open timed out"
            }
        } finally {
            client.stop(clearSessions = true)
        }
    }

    @Test
    fun approvalRoundTripWritesAcceptOnThePipe() = withClientScope { scope ->
        val pipe = QueueJsonPipe()
        pipe.enqueue(JSONObject("""{"id":1,"result":{"data":[{"id":"thr_1"}]}}"""))
        val store = AgentSessionStore()
        val client = CodexDirectClient(store, scope, openPipe = { _ -> pipe })
        try {
            client.start(computer)
            await(store) { it.connections.value[AgentProvider.CODEX]?.state == ConnectionState.CONNECTED }
            client.openDetail("thr_1")
            await(store) { pipe.sent().any { it.optString("method") == "thread/resume" } }
            pipe.enqueue(JSONObject("""{"id":2,"result":{"thread":{"id":"thr_1"}}}"""))
            await(store) { it.conversation.value?.sessionId == "thr_1" }
            pipe.enqueue(
                JSONObject(
                    """{"id":9,"method":"item/commandExecution/requestApproval","params":{"threadId":"thr_1","command":"ls"}}""",
                ),
            )
            await(store) { it.approvals.value.any { approval -> approval.fourVerdicts } }
            val approval = store.approvals.value.single()
            assertEquals("n:9", approval.requestId)
            client.decideApproval(approval.requestId, ApprovalVerdict.ACCEPT)
            await(store) { it.approvals.value.isEmpty() }
            await(store) {
                pipe.sent().any { json ->
                    json.optJSONObject("result")?.optString("decision") == "Accept"
                }
            }
        } finally {
            client.stop(clearSessions = true)
        }
    }

    @Test
    fun turnStartMarksTheSessionWorking() = withClientScope { scope ->
        val pipe = QueueJsonPipe()
        pipe.enqueue(JSONObject("""{"id":1,"result":{"data":[]}}"""))
        val store = AgentSessionStore()
        val client = CodexDirectClient(store, scope, openPipe = { _ -> pipe })
        try {
            client.start(computer)
            await(store) { it.connections.value[AgentProvider.CODEX]?.state == ConnectionState.CONNECTED }
            client.requestThreadStart("req-1", prompt = "do the thing", cwd = "/tmp")
            await(store) { pipe.sent().any { it.optString("method") == "thread/start" } }
            pipe.enqueue(JSONObject("""{"id":2,"result":{"thread":{"id":"thr_new"}}}"""))
            await(store) { pipe.sent().any { it.optString("method") == "turn/start" } }
            pipe.enqueue(JSONObject("""{"id":3,"result":{"turn":{"id":"turn_1","status":"inProgress"}}}"""))
            await(store) {
                it.threadStart.value?.requestId == "req-1" && it.threadStart.value?.ok == true
            }
            assertEquals("thr_new", store.threadStart.value?.sessionId)
            await(store) {
                it.sessions.value.any { session ->
                    session.id == "thr_new" && session.status == AgentStatus.WORKING
                }
            }
        } finally {
            client.stop(clearSessions = true)
        }
    }

    private fun withClientScope(block: suspend (CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            block(scope)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun await(store: AgentSessionStore, predicate: (AgentSessionStore) -> Boolean) {
        withTimeout(3_000) {
            while (!predicate(store)) delay(10)
        }
    }
}
