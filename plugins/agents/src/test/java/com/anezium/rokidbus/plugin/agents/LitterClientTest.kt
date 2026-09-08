package com.anezium.rokidbus.plugin.agents

import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
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
        peer.received("turn/start")
    }

    @Test fun `approval replay before resume response waits for confirmed active turn`() = fixture { client, store, peer ->
        val session = store.sessions.first { it.isNotEmpty() }.single()
        peer.approveDuringResume = true
        client.openSession(session)
        val approval = store.approvals.first { it.isNotEmpty() }.single()
        assertTrue(client.decide(approval.requestId, "thread-a", false))
        assertEquals("decline", peer.reply("resume-approval").getJSONObject("result").getString("decision"))
    }

    @Test fun `resuming a saved session restores it even outside the first list page`() = fixture { client, store, _ ->
        val saved = store.sessions.first { it.isNotEmpty() }.single()
        store.replaceProvider(AgentProvider.CODEX, emptyList())
        client.openSession(saved)
        assertEquals("thread-a", store.sessions.value.single().id)
        assertEquals("Existing answer", store.conversation.value!!.messages.single().text)
    }

    @Test fun `resolved approvals cannot reappear after resume hydration`() = fixture { client, store, peer ->
        val session = store.sessions.first { it.isNotEmpty() }.single()
        peer.beforeResume = {
            approval("resolved")
            event("serverRequest/resolved", JSONObject().put("requestId", "resolved"))
            approval("item-completed")
            event("item/completed", JSONObject().put("item", JSONObject().put("id", "command-a").put("type", "commandExecution")))
            approval("still-pending", item = "command-b")
            event("serverRequest/resolved", JSONObject().put("requestId", "still-pending"), thread = "other-thread")
            event("item/completed", JSONObject().put("item", JSONObject().put("id", "command-b")), turn = "other-turn")
        }
        client.openSession(session)
        val remaining = store.approvals.value.single()
        assertTrue(client.decide(remaining.requestId, "thread-a", false))
        assertEquals("decline", peer.reply("still-pending").getJSONObject("result").getString("decision"))
        assertTrue(store.approvals.value.isEmpty())
    }

    @Test fun `turn completion prevents deferred approval and stale active turn resurrection`() = fixture { client, store, peer ->
        val session = store.sessions.first { it.isNotEmpty() }.single()
        peer.beforeResume = {
            approval("finished-turn")
            event("turn/completed", JSONObject().put("turn", JSONObject().put("id", "turn-a").put("status", "completed")))
        }
        client.openSession(session)
        assertTrue(store.approvals.value.isEmpty())
        client.submit("thread-a", "A new turn")
        peer.received("turn/start")
    }

    @Test fun `delta before resume retains snapshot prefix and accepts later complete item`() = fixture { client, store, peer ->
        val session = store.sessions.first { it.isNotEmpty() }.single()
        client.openSession(session)
        peer.resumeItemId = "new-message"
        peer.resumeText = "Hello "
        peer.beforeResume = {
            event("item/agentMessage/delta", JSONObject().put("itemId", "new-message").put("delta", "world"))
        }
        client.openSession(session)
        assertEquals("Hello world", store.conversation.value!!.messages.single().text)
        peer.event("item/completed", JSONObject().put("item", JSONObject().put("id", "new-message")
            .put("type", "agentMessage").put("text", "Hello world!")))
        store.conversation.first { it?.messages?.singleOrNull()?.text == "Hello world!" }
    }

    @Test fun `hydration does not duplicate an already included delta or replace completed text`() = fixture { client, store, peer ->
        val session = store.sessions.first { it.isNotEmpty() }.single()
        client.openSession(session)
        peer.resumeItemId = "new-message"
        peer.resumeText = "Hello world"
        peer.beforeResume = {
            event("item/agentMessage/delta", JSONObject().put("itemId", "new-message").put("delta", "world"))
        }
        client.openSession(session)
        assertEquals("Hello world", store.conversation.value!!.messages.single().text)
        peer.resumeText = "Hello "
        peer.beforeResume = {
            event("item/completed", JSONObject().put("item", JSONObject().put("id", "new-message")
                .put("type", "agentMessage").put("text", "Hello world!")))
        }
        client.openSession(session)
        assertEquals("Hello world!", store.conversation.value!!.messages.single().text)
    }

    @Test fun `known item baseline retains a legitimately repeated delta during resume`() = fixture { client, store, peer ->
        val session = store.sessions.first { it.isNotEmpty() }.single()
        peer.resumeText = "ha"
        client.openSession(session)
        peer.beforeResume = {
            event("item/agentMessage/delta", JSONObject().put("itemId", "message-a").put("delta", "ha"))
        }
        client.openSession(session)
        assertEquals("haha", store.conversation.value!!.messages.single().text)
    }

    @Test fun `background refresh retains loaded pages and the next page cursor`() = fixture { client, store, peer ->
        store.sessions.first { it.isNotEmpty() }
        peer.paginated = true
        client.refresh()
        client.refresh(more = true)
        assertEquals(setOf("thread-a", "thread-b"), store.sessions.value.map { it.id }.toSet())
        peer.listCursors.clear()
        client.refresh(preserveWindow = true)
        assertEquals(listOf("first", "page-two"), peer.listCursors.toList())
        assertEquals(setOf("thread-a", "thread-b"), store.sessions.value.map { it.id }.toSet())
        client.refresh(more = true)
        assertEquals("page-three", peer.listCursors.last())
        assertEquals(setOf("thread-a", "thread-b", "thread-c"), store.sessions.value.map { it.id }.toSet())
        assertFalse(client.hasMore.value)
        client.refresh()
        assertEquals("thread-a", store.sessions.value.single().id)
        assertTrue(client.hasMore.value)
    }

    private fun fixture(reconnect: Boolean = false, body: suspend (LitterClient, AgentSessionStore, Peer) -> Unit) {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val http = OkHttpClient.Builder().build()
        val server = MockWebServer()
        val peer = Peer()
        server.enqueue(MockResponse().withWebSocketUpgrade(peer))
        if (reconnect) server.enqueue(MockResponse().withWebSocketUpgrade(Peer()))
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            runBlocking(dispatcher) {
                val scope = CoroutineScope(SupervisorJob() + dispatcher)
                val store = AgentSessionStore()
                val client = LitterClient(http, scope, store, reconnectDelayMs = 20)
                try {
                    client.start(LitterEndpoint("Fixture", "ws://127.0.0.1:${server.port}/"))
                    try { withTimeout(10_000) { body(client, store, peer) } }
                    catch (timeout: TimeoutCancellationException) {
                        throw AssertionError("Fixture timeout: ${client.message.value}; received methods: ${peer.methods}", timeout)
                    }
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
        val methods = CopyOnWriteArrayList<String>()
        val listCursors = CopyOnWriteArrayList<String>()
        @Volatile var approveDuringResume = false
        @Volatile var beforeResume: (Peer.() -> Unit)? = null
        @Volatile var resumeItemId = "message-a"
        @Volatile var resumeText = "Existing answer"
        @Volatile var paginated = false

        override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = JSONObject(text)
            methods.add(frame.optString("method", "response"))
            frames.trySend(frame)
            val result = when (frame.optString("method")) {
                "initialize" -> JSONObject().put("userAgent", "fixture")
                "thread/list" -> {
                    val cursor = frame.getJSONObject("params").optString("cursor", "first")
                    listCursors.add(cursor)
                    val id = if (!paginated) "thread-a" else when (cursor) {
                        "page-two" -> "thread-b"
                        "page-three" -> "thread-c"
                        else -> "thread-a"
                    }
                    val next = if (!paginated) null else when (cursor) {
                        "first" -> "page-two"
                        "page-two" -> "page-three"
                        else -> null
                    }
                    JSONObject().put("data", JSONArray().put(thread(id))).put("nextCursor", next ?: JSONObject.NULL)
                }
                "thread/start" -> JSONObject().put("thread", thread().put("turns", JSONArray()).put("status", JSONObject().put("type", "idle")))
                "thread/resume" -> {
                    if (approveDuringResume) approval("resume-approval")
                    beforeResume?.invoke(this)
                    JSONObject().put("thread", thread(frame.getJSONObject("params").getString("threadId")))
                }
                "turn/start" -> JSONObject().put("turn", JSONObject().put("id", "turn-b").put("status", "inProgress"))
                "turn/steer" -> JSONObject().put("turnId", "turn-a")
                else -> return
            }
            socket.send(JSONObject().put("id", frame.get("id")).put("result", result).toString())
        }

        fun event(method: String, params: JSONObject, thread: String = "thread-a", turn: String = "turn-a") {
            params.put("threadId", thread).put("turnId", turn)
            socket.send(JSONObject().put("method", method).put("params", params).toString())
        }

        fun approval(id: Any, thread: String = "thread-a", item: String = "command-a") {
            socket.send(JSONObject().put("id", id).put("method", LitterApprovals.COMMAND)
                .put("params", JSONObject().put("threadId", thread).put("turnId", "turn-a")
                    .put("itemId", item).put("command", "git status --short")).toString())
        }

        suspend fun received(method: String): JSONObject {
            while (true) { val frame = frames.receive(); if (frame.optString("method") == method) return frame }
        }

        suspend fun reply(id: Any): JSONObject {
            while (true) { val frame = frames.receive(); if (!frame.has("method") && frame.opt("id")?.toString() == id.toString()) return frame }
        }

        private fun thread(id: String = "thread-a") = JSONObject().put("id", id).put("name", "Fixture session")
            .put("cwd", "/workspace/project").put("status", JSONObject().put("type", "active"))
            .put("updatedAt", 1_783_000_000L).put("turns", JSONArray().put(JSONObject()
                .put("id", "turn-a").put("status", "inProgress").put("items", JSONArray().put(JSONObject()
                    .put("id", resumeItemId).put("type", "agentMessage").put("text", resumeText)))))
    }
}
