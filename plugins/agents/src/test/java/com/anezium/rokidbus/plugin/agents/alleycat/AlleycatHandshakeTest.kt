package com.anezium.rokidbus.plugin.agents.alleycat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AlleycatHandshakeTest {
    @Test
    fun encodesListRestartAndConnectRequestsWithoutLeakingToken() {
        val list = HandshakeRequest.ListAgents(TEST_TOKEN).toJson()
        assertEquals("list_agents", list.getString("op"))
        assertEquals(1, list.getInt("v"))
        assertEquals(TEST_TOKEN, list.getString("token"))
        assertTrue(!HandshakeRequest.ListAgents(TEST_TOKEN).toString().contains(TEST_TOKEN))

        val restart = HandshakeRequest.RestartAgent(TEST_TOKEN, "codex").toJson()
        assertEquals("restart_agent", restart.getString("op"))
        assertEquals("codex", restart.getString("agent"))

        val connect = HandshakeRequest.Connect(TEST_TOKEN, "codex", lastSeq = 42L).toJson()
        assertEquals("connect", connect.getString("op"))
        assertEquals(42L, connect.getJSONObject("resume").getLong("last_seq"))
    }

    @Test
    fun parsesAgentsListResponse() {
        val response = HandshakeResponse.parseFrame(
            """{"v":1,"ok":true,"agents":[{"name":"codex"},"claude"]}""",
        )
        assertTrue(response.ok)
        assertEquals(listOf("codex", "claude"), response.agents!!.map { it.name })
        assertFalse(response.requiresStateReload)
    }

    @Test
    fun parsesFreshAttach() {
        val response = HandshakeResponse.parseFrame(
            """{"v":1,"ok":true,"session":{"attached":"fresh"}}""",
        )
        assertEquals(SessionAttached.FRESH, response.session!!.attached)
        assertFalse(response.requiresStateReload)
    }

    @Test
    fun parsesResumedAttach() {
        val response = HandshakeResponse.parseFrame(
            """{"v":1,"ok":true,"session":{"attached":"resumed"}}""",
        )
        assertEquals(SessionAttached.RESUMED, response.session!!.attached)
        assertFalse(response.requiresStateReload)
    }

    @Test
    fun driftReloadAttachRequiresStateReload() {
        val frame = utf8Frame("""{"v":1,"ok":true,"session":{"attached":"drift_reload"}}""")
        val transport = ScriptedTransport(frame)
        val response = AlleycatHandshakeClient(transport).exchange(
            HandshakeRequest.Connect(TEST_TOKEN, "codex", lastSeq = 9L),
        )
        assertEquals(SessionAttached.DRIFT_RELOAD, response.session!!.attached)
        assertTrue(response.requiresStateReload)

        val listFrame = utf8Frame(
            """{"id":1,"result":{"data":[{"id":"thr_reload","preview":"x"}],"nextCursor":null}}""",
        )
        val reloadTransport = ScriptedTransport(listFrame)
        val page = CodexAppServerClient(reloadTransport).reloadState()
        assertEquals("thr_reload", page.data.single().id)
        assertEquals("thread/list", decodeFrames(reloadTransport.written()).single().getString("method"))
    }

    @Test
    fun parsesErrorResponse() {
        val response = HandshakeResponse.parseFrame(
            """{"v":1,"ok":false,"error":"unauthorized"}""",
        )
        assertFalse(response.ok)
        assertEquals("unauthorized", response.error)
        assertNull(response.session)
        assertFalse(response.requiresStateReload)
    }

    @Test
    fun rejectsVersionMismatch() {
        try {
            HandshakeResponse.parseFrame("""{"v":2,"ok":true,"agents":[]}""")
            fail("expected version rejection")
        } catch (e: AlleycatException) {
            assertTrue(e.message!!.contains("unsupported handshake version 2"))
        }
    }
}

private fun HandshakeResponse.Companion.parseFrame(json: String): HandshakeResponse =
    parse(AlleycatFraming.decode(utf8Frame(json)))
