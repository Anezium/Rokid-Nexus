package com.anezium.rokidbus.plugin.agents

import com.anezium.rokidbus.plugin.agents.alleycat.AlleycatException
import com.anezium.rokidbus.plugin.agents.alleycat.ChunkedByteTransport
import com.anezium.rokidbus.plugin.agents.alleycat.HandshakeRequest
import com.anezium.rokidbus.plugin.agents.alleycat.JsonPipe
import com.anezium.rokidbus.plugin.agents.alleycat.QueueJsonPipe
import com.anezium.rokidbus.plugin.agents.alleycat.TEST_TOKEN
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AlleycatClientTest {
    private val computer = AlleycatComputer(
        computerId = AlleycatComputer.computerIdFor("node-id"),
        nodeId = "node-id",
        name = "desk",
        relay = "https://relay.example",
    )

    @Test
    fun handshakeThenThreadListConnectsAndDoesNotLogTheToken() = withClientScope { scope ->
        val pipe = AlleycatScriptedPipe(attached = "fresh")
        val secrets = MemoryAlleycatSecretStore()
        secrets.putToken(computer.computerId, TEST_TOKEN)
        val store = AgentSessionStore()
        val client = AlleycatClient(store, scope, secrets, { pipe })
        try {
            client.start(computer)
            await(store) { it.connections.value[AgentProvider.CODEX]?.state == ConnectionState.CONNECTED }
            assertEquals("thr_1", store.sessions.value.single().id)
            assertEquals("desk", store.sessions.value.single().machineName)
            assertEquals(listOf("list_agents", "connect", "thread/list"), pipe.ops)
            val list = pipe.sent().first { it.optString("op") == "list_agents" }
            assertEquals(TEST_TOKEN, list.getString("token"))
            assertEquals(1, list.getInt("v"))
            val connect = pipe.sent().first { it.optString("op") == "connect" }
            assertEquals("codex", connect.getString("agent"))
            assertFalse(computer.toString().contains(TEST_TOKEN))
            assertFalse(secrets.toString().contains(TEST_TOKEN))
            assertFalse(HandshakeRequest.ListAgents(TEST_TOKEN).toString().contains(TEST_TOKEN))
            assertFalse(HandshakeRequest.Connect(TEST_TOKEN, "codex", 8L).toString().contains(TEST_TOKEN))
            pipe.sent().forEach { json ->
                if (json.has("method")) {
                    assertFalse(json.toString().contains(TEST_TOKEN))
                }
            }
        } finally {
            client.stop(clearSessions = true)
        }
    }

    @Test
    fun driftReloadStillReloadsViaThreadList() = withClientScope { scope ->
        val pipe = AlleycatScriptedPipe(attached = "drift_reload", lastSeq = 9L)
        val secrets = MemoryAlleycatSecretStore()
        secrets.putToken(computer.computerId, TEST_TOKEN)
        val store = AgentSessionStore()
        var savedSeq: Long? = null
        val client = AlleycatClient(
            store,
            scope,
            secrets,
            { pipe },
            AlleycatSessionHooks(onLastSeq = { _, seq -> savedSeq = seq }),
        )
        try {
            client.start(computer.copy(lastSeq = 8L))
            await(store) { it.connections.value[AgentProvider.CODEX]?.state == ConnectionState.CONNECTED }
            assertEquals("thr_1", store.sessions.value.single().id)
            assertEquals(listOf("list_agents", "connect", "thread/list"), pipe.ops)
            assertEquals(9L, savedSeq)
            val connect = pipe.sent().first { it.optString("op") == "connect" }
            assertEquals(8L, connect.getJSONObject("resume").getLong("last_seq"))
        } finally {
            client.stop(clearSessions = true)
        }
    }

    @Test
    fun authFailureLandsInAuthFailedWithoutRetry() = withClientScope { scope ->
        val opens = AtomicInteger(0)
        val pipe = AuthFailPipe()
        val secrets = MemoryAlleycatSecretStore()
        secrets.putToken(computer.computerId, TEST_TOKEN)
        val store = AgentSessionStore()
        var needsRePair: Boolean? = null
        val client = AlleycatClient(
            store,
            scope,
            secrets,
            {
                opens.incrementAndGet()
                pipe
            },
            AlleycatSessionHooks(onNeedsRePair = { _, needs -> needsRePair = needs }),
        )
        try {
            client.start(computer)
            await(store) { it.connections.value[AgentProvider.CODEX]?.state == ConnectionState.AUTH_FAILED }
            val detail = store.connections.value.getValue(AgentProvider.CODEX).detail.orEmpty()
            assertTrue(detail.contains("Re-pair"))
            assertTrue(detail.contains("unauthorized"))
            assertFalse(detail.contains(TEST_TOKEN))
            assertEquals(true, needsRePair)
            assertEquals(1, opens.get())
            delay(400)
            assertEquals(ConnectionState.AUTH_FAILED, store.connections.value.getValue(AgentProvider.CODEX).state)
            assertEquals(1, opens.get())
            assertEquals(1, pipe.attempts.get())
        } finally {
            client.stop(clearSessions = true)
        }
    }

    @Test
    fun versionMismatchIsTerminalDisconnectedWithAClearDetail() = withClientScope { scope ->
        val opens = AtomicInteger(0)
        val pipe = VersionFailPipe()
        val secrets = MemoryAlleycatSecretStore()
        secrets.putToken(computer.computerId, TEST_TOKEN)
        val store = AgentSessionStore()
        val client = AlleycatClient(store, scope, secrets, {
            opens.incrementAndGet()
            pipe
        })
        try {
            client.start(computer)
            await(store) {
                val state = it.connections.value[AgentProvider.CODEX]
                state?.state == ConnectionState.DISCONNECTED &&
                    state.detail.orEmpty().contains("unsupported handshake version 2")
            }
            delay(250)
            assertEquals(1, opens.get())
            assertFalse(
                store.connections.value.getValue(AgentProvider.CODEX).detail.orEmpty()
                    .contains(TEST_TOKEN),
            )
        } finally {
            client.stop(clearSessions = true)
        }
    }

    @Test
    fun alpnMismatchFromTheOpenerIsFailedNotRetried() = withClientScope { scope ->
        val opens = AtomicInteger(0)
        val secrets = MemoryAlleycatSecretStore()
        secrets.putToken(computer.computerId, TEST_TOKEN)
        val store = AgentSessionStore()
        val client = AlleycatClient(store, scope, secrets, {
            opens.incrementAndGet()
            throw AlleycatException("ALPN mismatch: peer negotiated 'other/1' (expected alleycat/1)")
        })
        try {
            client.start(computer)
            await(store) {
                val state = it.connections.value[AgentProvider.CODEX]
                state?.state == ConnectionState.DISCONNECTED &&
                    state.detail.orEmpty().contains("ALPN mismatch")
            }
            delay(250)
            assertEquals(1, opens.get())
        } finally {
            client.stop(clearSessions = true)
        }
    }

    @Test
    fun missingTokenNeverOpensAStream() = withClientScope { scope ->
        val opens = AtomicInteger(0)
        val store = AgentSessionStore()
        val client = AlleycatClient(store, scope, MemoryAlleycatSecretStore(), {
            opens.incrementAndGet()
            error("must not open")
        })
        try {
            client.start(computer)
            await(store) { it.connections.value[AgentProvider.CODEX]?.state == ConnectionState.AUTH_FAILED }
            assertEquals(0, opens.get())
            assertTrue(
                store.connections.value.getValue(AgentProvider.CODEX).detail.orEmpty()
                    .contains("Re-pair"),
            )
        } finally {
            client.stop(clearSessions = true)
        }
    }

    @Test
    fun connectResumeHonorsLastSeqOnTheComputer() = withClientScope { scope ->
        val pipe = AlleycatScriptedPipe(attached = "resumed")
        val secrets = MemoryAlleycatSecretStore()
        secrets.putToken(computer.computerId, TEST_TOKEN)
        val store = AgentSessionStore()
        val client = AlleycatClient(store, scope, secrets, { pipe })
        try {
            client.start(computer.copy(lastSeq = 42L))
            await(store) { it.connections.value[AgentProvider.CODEX]?.state == ConnectionState.CONNECTED }
            val connect = pipe.sent().first { it.optString("op") == "connect" }
            assertEquals(42L, connect.getJSONObject("resume").getLong("last_seq"))
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

class ChunkedByteTransportTest {
    @Test
    fun receiveTimeoutReturnsNullInsteadOfHanging() {
        val transport = ChunkedByteTransport()
        val started = System.currentTimeMillis()
        val got = transport.receiveExactly(4, timeoutMs = 80)
        assertNull(got)
        assertTrue(System.currentTimeMillis() - started >= 80)
        transport.close()
    }
}

private class AlleycatScriptedPipe(
    private val attached: String,
    private val lastSeq: Long? = null,
) : JsonPipe {
    private val inner = QueueJsonPipe()
    val ops = mutableListOf<String>()

    fun sent(): List<JSONObject> = inner.sent()

    override fun sendJson(message: JSONObject) {
        inner.sendJson(message)
        val op = message.optString("op")
        val method = message.optString("method")
        if (op.isNotEmpty()) ops += op else if (method.isNotEmpty()) ops += method
        when (op) {
            "list_agents" -> inner.enqueue(
                JSONObject()
                    .put("v", 1)
                    .put("ok", true)
                    .put("agents", JSONArray().put("codex")),
            )
            "connect" -> {
                val session = JSONObject().put("attached", attached)
                if (lastSeq != null) session.put("last_seq", lastSeq)
                inner.enqueue(JSONObject().put("v", 1).put("ok", true).put("session", session))
            }
        }
        if (method == "thread/list") {
            inner.enqueue(
                JSONObject()
                    .put("id", message.getLong("id"))
                    .put(
                        "result",
                        JSONObject().put(
                            "data",
                            JSONArray().put(JSONObject().put("id", "thr_1").put("preview", "hi")),
                        ),
                    ),
            )
        }
    }

    override fun receiveJson(): JSONObject = inner.receiveJson()

    override fun receiveJson(timeoutMs: Long): JSONObject? = inner.receiveJson(timeoutMs)

    override fun close() = inner.close()
}

private class AuthFailPipe : JsonPipe {
    private val inner = QueueJsonPipe()
    val attempts = AtomicInteger(0)

    override fun sendJson(message: JSONObject) {
        inner.sendJson(message)
        attempts.incrementAndGet()
        inner.enqueue(
            JSONObject()
                .put("v", 1)
                .put("ok", false)
                .put("error", "unauthorized"),
        )
    }

    override fun receiveJson(): JSONObject = inner.receiveJson()

    override fun receiveJson(timeoutMs: Long): JSONObject? = inner.receiveJson(timeoutMs)

    override fun close() = inner.close()
}

private class VersionFailPipe : JsonPipe {
    private val inner = QueueJsonPipe()

    override fun sendJson(message: JSONObject) {
        inner.sendJson(message)
        inner.enqueue(
            JSONObject().put("v", 2).put("ok", true).put("agents", JSONArray().put("codex")),
        )
    }

    override fun receiveJson(): JSONObject = inner.receiveJson()

    override fun receiveJson(timeoutMs: Long): JSONObject? = inner.receiveJson(timeoutMs)

    override fun close() = inner.close()
}
