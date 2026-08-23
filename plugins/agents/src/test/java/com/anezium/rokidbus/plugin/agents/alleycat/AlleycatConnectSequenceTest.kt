package com.anezium.rokidbus.plugin.agents.alleycat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AlleycatConnectSequenceTest {
    @Test
    fun autoPicksASingletonCodexList() {
        val choice = AlleycatConnectSequence.chooseAgent(listOf("codex"), saved = null)
        assertEquals(AgentChoice.Selected("codex"), choice)
    }

    @Test
    fun autoPicksAnySingletonAgent() {
        val choice = AlleycatConnectSequence.chooseAgent(listOf("claude"), saved = null)
        assertEquals(AgentChoice.Selected("claude"), choice)
    }

    @Test
    fun multipleAgentsNeedAPickerUnlessSaved() {
        val names = listOf("codex", "claude")
        assertEquals(
            AgentChoice.NeedsPicker(names),
            AlleycatConnectSequence.chooseAgent(names, saved = null),
        )
        assertEquals(
            AgentChoice.Selected("claude"),
            AlleycatConnectSequence.chooseAgent(names, saved = "claude"),
        )
    }

    @Test
    fun emptyListIsNone() {
        assertEquals(AgentChoice.None, AlleycatConnectSequence.chooseAgent(emptyList(), null))
    }

    @Test
    fun handshakeSequenceListConnectThenDriftReloadUsesThreadList() {
        val list = utf8Frame("""{"v":1,"ok":true,"agents":["codex"]}""")
        val connect = utf8Frame("""{"v":1,"ok":true,"session":{"attached":"drift_reload","last_seq":9}}""")
        val threads = utf8Frame(
            """{"id":1,"result":{"data":[{"id":"thr_reload","preview":"x"}],"nextCursor":null}}""",
        )
        val transport = ScriptedTransport(list + connect + threads)
        val pipe = FramedJsonPipe(transport)
        val flow = AlleycatHandshakeFlow(pipe, TEST_TOKEN)
        val attached = flow.attach(savedAgent = null, lastSeq = 8L) as AlleycatAttachResult.Attached
        assertEquals("codex", attached.agent)
        assertEquals(SessionAttached.DRIFT_RELOAD, attached.session!!.attached)
        assertTrue(attached.session!!.requiresStateReload)
        assertEquals(9L, AlleycatConnectSequence.sessionSeq(attached.session!!))

        val page = CodexAppServerClient(pipe).reloadState()
        assertEquals("thr_reload", page.data.single().id)

        val sent = decodeFrames(transport.written())
        assertEquals("list_agents", sent[0].getString("op"))
        assertEquals(TEST_TOKEN, sent[0].getString("token"))
        assertEquals("connect", sent[1].getString("op"))
        assertEquals(8L, sent[1].getJSONObject("resume").getLong("last_seq"))
        assertEquals("thread/list", sent[2].getString("method"))
        sent.forEach { json ->
            if (json.has("op")) {
                // Wire must carry the token; log-safe objects must not.
            }
        }
        assertTrue(!HandshakeRequest.Connect(TEST_TOKEN, "codex", 8L).toString().contains(TEST_TOKEN))
        assertTrue(!flow.toString().contains(TEST_TOKEN))
    }

    @Test
    fun authFailureDoesNotRestartTheAgent() {
        val list = utf8Frame("""{"v":1,"ok":false,"error":"unauthorized"}""")
        val transport = ScriptedTransport(list)
        val flow = AlleycatHandshakeFlow(FramedJsonPipe(transport), TEST_TOKEN)
        val result = flow.attach(savedAgent = null, lastSeq = null)
        assertTrue(result is AlleycatAttachResult.HandshakeFailed)
        assertEquals("unauthorized", (result as AlleycatAttachResult.HandshakeFailed).error)
        assertTrue(AlleycatConnectSequence.isAuthFailure(result.error))
        val sent = decodeFrames(transport.written())
        assertEquals(listOf("list_agents"), sent.map { it.getString("op") })
    }

    @Test
    fun connectFailureRestartsOnceThenConnects() {
        val list = utf8Frame("""{"v":1,"ok":true,"agents":["codex"]}""")
        val fail = utf8Frame("""{"v":1,"ok":false,"error":"agent not running"}""")
        val restart = utf8Frame("""{"v":1,"ok":true}""")
        val ok = utf8Frame("""{"v":1,"ok":true,"session":{"attached":"fresh"}}""")
        val transport = ScriptedTransport(list + fail + restart + ok)
        val result = AlleycatHandshakeFlow(FramedJsonPipe(transport), TEST_TOKEN)
            .attach(savedAgent = null, lastSeq = null)
        assertTrue(result is AlleycatAttachResult.Attached)
        val ops = decodeFrames(transport.written()).map { it.getString("op") }
        assertEquals(listOf("list_agents", "connect", "restart_agent", "connect"), ops)
    }

    @Test
    fun versionMismatchIsAClearErrorWithNoFallback() {
        val list = utf8Frame("""{"v":2,"ok":true,"agents":["codex"]}""")
        try {
            AlleycatHandshakeFlow(FramedJsonPipe(ScriptedTransport(list)), TEST_TOKEN)
                .attach(savedAgent = null, lastSeq = null)
            fail("expected version rejection")
        } catch (e: AlleycatException) {
            assertTrue(e.message!!.contains("unsupported handshake version 2"))
            assertTrue(!e.message!!.contains(TEST_TOKEN))
            assertTrue(AlleycatConnectSequence.isVersionOrAlpnFailure(e.message))
        }
    }

    @Test
    fun redactSecretStripsTheTokenFromErrorText() {
        val leaked = "unauthorized token=$TEST_TOKEN"
        assertEquals(
            "unauthorized token=<redacted>",
            AlleycatConnectSequence.redactSecret(leaked, TEST_TOKEN),
        )
    }

    @Test
    fun needsPickerWhenSeveralAgentsAreAdvertised() {
        val list = utf8Frame("""{"v":1,"ok":true,"agents":["codex","claude"]}""")
        val transport = ScriptedTransport(list)
        val result = AlleycatHandshakeFlow(FramedJsonPipe(transport), TEST_TOKEN)
            .attach(savedAgent = null, lastSeq = null)
        assertEquals(
            listOf("codex", "claude"),
            (result as AlleycatAttachResult.NeedsPicker).agents,
        )
        val sent = decodeFrames(transport.written())
        assertEquals(listOf("list_agents"), sent.map { it.getString("op") })
        assertTrue(!HandshakeRequest.ListAgents(TEST_TOKEN).toString().contains(TEST_TOKEN))
    }

    @Test
    fun handshakeTimesOutInsteadOfHangingWhenNoFrameArrives() {
        try {
            AlleycatHandshakeFlow(FramedJsonPipe(ScriptedTransport()), TEST_TOKEN)
                .attach(savedAgent = null, lastSeq = null)
            fail("expected handshake timeout")
        } catch (e: AlleycatException) {
            assertTrue(e.message!!.contains("timed out"))
            assertTrue(!e.message!!.contains(TEST_TOKEN))
        }
    }
}
