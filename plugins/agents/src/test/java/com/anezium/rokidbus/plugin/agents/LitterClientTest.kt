package com.anezium.rokidbus.plugin.agents

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** A real local WebSocket with a deterministic app-server peer; no model or device. */
class LitterClientTest {
    @Test fun `handshake hydrate stream steer and scoped approval round trip`() = fixture { client, store, peer ->
        val session = store.sessions.first { it.isNotEmpty() }.single()
        assertEquals("Fixture session", session.title)
        client.openSession(session)
        assertEquals("Existing answer", store.conversation.value!!.messages.single().text)
        peer.event("item/started", JSONObject().put("item", JSONObject().put("id", "message-b").put("type", "agentMessage").put("text", "")))
        peer.event("item/agentMessage/delta", JSONObject().put("itemId", "message-b").put("delta", "Hello"))
        peer.event("item/agentMessage/delta", JSONObject().put("itemId", "message-b").put("delta", " "))
        peer.event("item/agentMessage/delta", JSONObject().put("itemId", "message-b").put("delta", "world"))
        store.conversation.first { it?.messages?.lastOrNull()?.text == "Hello world" }

        client.submit("thread-a", "Continue the work")
        val steer = peer.received("turn/steer")
        assertEquals("turn-a", steer.getJSONObject("params").getString("expectedTurnId"))
        assertEquals("Continue the work", steer.getJSONObject("params").getJSONArray("input").getJSONObject(0).getString("text"))

        peer.approval(41)
        val approval = store.approvals.first { it.isNotEmpty() }.single()
        assertFalse(client.decide(approval.requestId, "another-thread", true))
        assertTrue(client.decide(approval.requestId, "thread-a", true))
        assertFalse(client.decide(approval.requestId, "thread-a", true))
        val reply = peer.reply(41)
        assertEquals("accept", reply.getJSONObject("result").getString("decision"))
        assertTrue(store.approvals.value.isEmpty())

        peer.event("turn/completed", JSONObject().put("turn", JSONObject().put("id", "turn-a").put("status", "completed")))
        store.sessions.first { it.single().status == AgentStatus.DONE }
        client.submit("thread-a", "Start a follow-up")
        peer.received("turn/start")
    }

    @Test fun `reconnect drops old approval and never replays prompt`() = fixture(reconnect = true) { client, store, first ->
        val session = store.sessions.first { it.isNotEmpty() }.single()
        client.openSession(session)
        first.approval("same-wire-id")
        val old = store.approvals.first { it.isNotEmpty() }.single()
        first.socket.close(1000, null)
        store.connections.first { it[AgentProvider.CODEX]?.state == ConnectionState.DISCONNECTED }
        assertTrue(store.approvals.value.isEmpty())
        assertFalse(client.decide(old.requestId, "thread-a", true))
        store.connections.first { it[AgentProvider.CODEX]?.state == ConnectionState.CONNECTED }
        assertFalse(client.decide(old.requestId, "thread-a", true))
    }

    @Test fun `unknown session approval is declined without entering the UI`() = fixture { _, store, peer ->
        store.sessions.first { it.isNotEmpty() }
        peer.approval("alien", thread = "unknown")
        assertEquals("decline", peer.reply("alien").getJSONObject("result").getString("decision"))
        assertTrue(store.approvals.value.isEmpty())
    }

    @Test fun `create session and first prompt are separate acknowledged mutations`() = fixture { client, store, peer ->
        store.sessions.first { it.isNotEmpty() }
        val id = client.createSession("/workspace/project")
        assertEquals("thread-a", id)
        assertEquals("thread-a", store.conversation.value!!.sessionId)
        val start = peer.received("thread/start")
        assertEquals("/workspace/project", start.getJSONObject("params").getString("cwd"))
        client.submit(id, "Implement this task")
        peer.received("turn/steer")
    }

    private fun fixture(reconnect: Boolean = false, body: suspend (LitterClient, AgentSessionStore, Peer) -> Unit) {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val http = OkHttpClient.Builder().build()
        val server = MockWebServer()
        val peer = Peer()
        server.enqueue(MockResponse().withWebSocketUpgrade(peer))
        if (reconnect) server.enqueue(MockResponse().withWebSocketUpgrade(Peer()))
        server.start()
        try {
            runBlocking(dispatcher) {
                val scope = CoroutineScope(SupervisorJob() + dispatcher)
                val store = AgentSessionStore()
                val client = LitterClient(http, scope, store, reconnectDelayMs = 20)
                try {
                    client.start(LitterEndpoint("Fixture", server.url("/").toString().replace("http://", "ws://")))
                    withTimeout(10_000) { body(client, store, peer) }
                } finally { client.stop(); scope.cancel() }
            }
        } finally {
            server.shutdown()
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
            dispatcher.close()
        }
    }

    private class Peer : WebSocketListener() {
        lateinit var socket: WebSocket
        private val frames = Channel<JSONObject>(Channel.UNLIMITED)

        override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = JSONObject(text)
            frames.trySend(frame)
            val result = when (frame.optString("method")) {
                "initialize" -> JSONObject().put("userAgent", "fixture")
                "thread/list" -> JSONObject().put("data", JSONArray().put(thread())).put("nextCursor", JSONObject.NULL)
                "thread/start", "thread/resume" -> JSONObject().put("thread", thread())
                "turn/start" -> JSONObject().put("turn", JSONObject().put("id", "turn-b").put("status", "inProgress"))
                "turn/steer" -> JSONObject().put("turnId", "turn-a")
                else -> return
            }
            socket.send(JSONObject().put("id", frame.get("id")).put("result", result).toString())
        }

        fun event(method: String, params: JSONObject) {
            params.put("threadId", "thread-a").put("turnId", "turn-a")
            socket.send(JSONObject().put("method", method).put("params", params).toString())
        }

        fun approval(id: Any, thread: String = "thread-a") {
            socket.send(JSONObject().put("id", id).put("method", LitterApprovals.COMMAND)
                .put("params", JSONObject().put("threadId", thread).put("turnId", "turn-a")
                    .put("itemId", "command-a").put("command", "git status --short")).toString())
        }

        suspend fun received(method: String): JSONObject {
            while (true) { val frame = frames.receive(); if (frame.optString("method") == method) return frame }
        }

        suspend fun reply(id: Any): JSONObject {
            while (true) { val frame = frames.receive(); if (!frame.has("method") && frame.opt("id").toString() == id.toString()) return frame }
        }

        private fun thread() = JSONObject().put("id", "thread-a").put("name", "Fixture session")
            .put("cwd", "/workspace/project").put("status", JSONObject().put("type", "active"))
            .put("updatedAt", 1_783_000_000L).put("turns", JSONArray().put(JSONObject()
                .put("id", "turn-a").put("status", "inProgress").put("items", JSONArray().put(JSONObject()
                    .put("id", "message-a").put("type", "agentMessage").put("text", "Existing answer")))))
    }
}
