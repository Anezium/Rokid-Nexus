package com.anezium.rokidbus.plugin.agents

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentdProtocolCodecTest {
    @Test
    fun helloUsesFrozenProtocolShape() {
        val hello = JSONObject(AgentdProtocolCodec().hello("secret", "1.2.3"))
        assertEquals("hello", hello.getString("type"))
        assertEquals(1, hello.getInt("v"))
        assertEquals("secret", hello.getString("token"))
        assertEquals("plugin-agents", hello.getJSONObject("client").getString("name"))
        assertEquals("1.2.3", hello.getJSONObject("client").getString("version"))
    }

    @Test
    fun theLinkCarriesMoreThanOneHarness() {
        val codec = AgentdProtocolCodec()
        val snapshot = codec.parse(
            """{"type":"snapshot","seq":1,"sessions":[
                {"id":"s1","provider":"claude","status":"idle"},
                {"id":"s2","provider":"codex","status":"working"},
                {"id":"s3","provider":"nonsense","status":"idle"},
                {"id":"s4","status":"idle"}
            ]}""",
        ) as AgentdAction.Snapshot

        // An unknown harness is dropped; a session with no provider at all comes
        // from a daemon older than this plugin, which only ever meant Claude.
        assertEquals(
            listOf(
                "s1" to AgentProvider.CLAUDE,
                "s2" to AgentProvider.CODEX,
                "s4" to AgentProvider.CLAUDE,
            ),
            snapshot.sessions.map { it.id to it.provider },
        )

        val approval = codec.parse(
            """{"type":"approval_request","v":1,"requestId":"r1","provider":"codex",
                "sessionId":"s2","tool":"shell","summary":"npm test"}""",
        ) as AgentdAction.ApprovalRequested
        assertEquals(AgentProvider.CODEX, approval.approval.provider)
        assertEquals("codex:s2", approval.approval.sessionKey)

        val removed = codec.parse(
            """{"type":"session_removed","seq":2,"provider":"codex","sessionId":"s2"}""",
        ) as AgentdAction.Removed
        assertEquals(AgentProvider.CODEX, removed.provider)
    }

    @Test
    fun sameSessionIdInTwoHarnessesStaysTwoSessions() {
        val codec = AgentdProtocolCodec()
        val snapshot = codec.parse(
            """{"type":"snapshot","seq":1,"sessions":[
                {"id":"same","provider":"claude","status":"idle"},
                {"id":"same","provider":"codex","status":"idle"}
            ]}""",
        ) as AgentdAction.Snapshot
        assertEquals(2, snapshot.sessions.size)

        // Detail is only accepted for a session the codec has seen, and seeing
        // one harness's id must not vouch for the other's.
        val detail = codec.parse(
            """{"type":"detail","provider":"codex","sessionId":"same","messages":[{"role":"user","text":"hi"}]}""",
        ) as AgentdAction.Detail
        assertEquals(AgentProvider.CODEX, detail.provider)
        assertTrue(
            codec.parse(
                """{"type":"detail","provider":"openclaw","sessionId":"same","messages":[]}""",
            ) is AgentdAction.Ignore,
        )
    }

    @Test
    fun snapshotUpsertAndRemovedAreParsed() {
        val codec = AgentdProtocolCodec()
        val helloAck = codec.parse(
            """{"type":"hello_ack","v":1,"server":{"name":"nexus-agentd","version":"1.0.0","machineId":"pc-1","machineName":"Desk"}}""",
        ) as AgentdAction.HelloAcknowledged
        assertEquals("Desk", helloAck.machineName)
        val snapshot = codec.parse(
            """{"type":"snapshot","seq":7,"sessions":[{"id":"s1","provider":"claude","status":"idle","title":"One"}]}""",
        ) as AgentdAction.Snapshot
        assertEquals(7, snapshot.seq)
        assertEquals("One", snapshot.sessions.single().title)

        val upsert = codec.parse(
            """{"type":"session_upsert","seq":8,"session":{"id":"s1","provider":"claude","status":"needs_you","pendingRequest":{"kind":"permission","summary":"Run tests","createdAt":40}}}""",
        ) as AgentdAction.Upsert
        assertEquals(AgentStatus.NEEDS_YOU, upsert.session.status)
        assertEquals(40L, upsert.session.pendingRequest?.createdAt)

        val removed = codec.parse(
            """{"type":"session_removed","seq":9,"sessionId":"s1"}""",
        ) as AgentdAction.Removed
        assertEquals("s1", removed.sessionId)
    }

    @Test
    fun sequenceGapRequestsRefreshAndIgnoresDeltasUntilSnapshot() {
        val codec = AgentdProtocolCodec()
        codec.parse("""{"type":"snapshot","seq":10,"sessions":[]}""")
        val gap = codec.parse(
            """{"type":"session_removed","seq":12,"sessionId":"lost"}""",
        )
        assertEquals(AgentdAction.Send(AgentdProtocolCodec.REFRESH), gap)
        assertEquals(
            AgentdAction.Ignore,
            codec.parse("""{"type":"session_removed","seq":13,"sessionId":"also-lost"}"""),
        )
        assertTrue(codec.parse("""{"type":"snapshot","seq":15,"sessions":[]}""") is AgentdAction.Snapshot)
        assertTrue(
            codec.parse(
                """{"type":"session_upsert","seq":16,"session":{"id":"ok","provider":"claude","status":"working"}}""",
            ) is AgentdAction.Upsert,
        )
    }

    @Test
    fun malformedSnapshotRequestsRefreshInsteadOfProducingEmptySnapshot() {
        val codec = AgentdProtocolCodec()

        assertEquals(
            AgentdAction.Send(AgentdProtocolCodec.REFRESH),
            codec.parse("""{"type":"snapshot","seq":1}"""),
        )
        assertEquals(
            AgentdAction.Ignore,
            codec.parse("""{"type":"snapshot","seq":2,"sessions":{"id":"not-an-array"}}"""),
        )

        val recovered = codec.parse(
            """{"type":"snapshot","seq":3,"sessions":[{"id":"kept","provider":"claude","status":"idle"}]}""",
        ) as AgentdAction.Snapshot
        assertEquals("kept", recovered.sessions.single().id)
    }

    @Test
    fun snapshotAndLaterUpsertsCannotExceedProviderCap() {
        val rows = (0 until MAX_SESSIONS_PER_PROVIDER + 25).joinToString(",") {
            """{"id":"s$it","provider":"claude","status":"idle"}"""
        }
        val codec = AgentdProtocolCodec()
        val snapshot = codec.parse(
            """{"type":"snapshot","seq":1,"sessions":[$rows]}""",
        ) as AgentdAction.Snapshot
        assertEquals(MAX_SESSIONS_PER_PROVIDER, snapshot.sessions.size)

        assertEquals(
            AgentdAction.Ignore,
            codec.parse(
                """{"type":"session_upsert","seq":2,"session":{"id":"overflow","provider":"claude","status":"idle"}}""",
            ),
        )
        assertTrue(
            codec.parse("""{"type":"session_removed","seq":3,"sessionId":"s0"}""") is
                AgentdAction.Removed,
        )
        assertTrue(
            codec.parse(
                """{"type":"session_upsert","seq":4,"session":{"id":"replacement","provider":"claude","status":"idle"}}""",
            ) is AgentdAction.Upsert,
        )
    }

    @Test
    fun scalarTypesAreStrictAndRetainedStringsAreBounded() {
        val codec = AgentdProtocolCodec()
        assertEquals(
            AgentdAction.Ignore,
            codec.parse("""{"type":"hello","v":1,"machineId":"pc","token":42}"""),
        )

        val longTitle = "x".repeat(MAX_HUD_LABEL_CHARS + 20)
        val snapshot = codec.parse(
            """{"type":"snapshot","seq":1,"sessions":[
                {"id":7,"provider":"claude","status":"idle"},
                {"id":"valid","provider":"claude","status":"idle","title":"$longTitle","cwd":123}
            ]}""".trimIndent(),
        ) as AgentdAction.Snapshot
        assertEquals(1, snapshot.sessions.size)
        assertEquals(MAX_HUD_LABEL_CHARS, snapshot.sessions.single().title?.length)
        assertNull(snapshot.sessions.single().cwd)
    }

    @Test
    fun detailMessagesAreCappedAndMissingArrayDoesNotWipeConversation() {
        val codec = AgentdProtocolCodec()
        codec.parse(
            """{"type":"snapshot","seq":1,"sessions":[{"id":"s1","provider":"claude","status":"idle"}]}""",
        )
        assertEquals(
            AgentdAction.Ignore,
            codec.parse("""{"type":"detail","sessionId":"s1"}"""),
        )
        val messages = (0 until MAX_DETAIL_MESSAGES + 10).joinToString(",") {
            """{"role":"assistant","text":"message-$it"}"""
        }
        val detail = codec.parse(
            """{"type":"detail","sessionId":"s1","messages":[$messages]}""",
        ) as AgentdAction.Detail
        assertEquals(MAX_DETAIL_MESSAGES, detail.messages.size)
        assertEquals(
            AgentdAction.Ignore,
            codec.parse(
                """{"type":"detail_append","sessionId":"s1","message":{"role":"assistant","text":"overflow"}}""",
            ),
        )
    }

    @Test
    fun sessionInputRequestUsesFrozenProtocolShape() {
        val request = JSONObject(
            AgentdProtocolCodec.sessionInput("req-1", "s1", "keep going"),
        )
        assertEquals("session_input", request.getString("type"))
        assertEquals(1, request.getInt("v"))
        assertEquals("req-1", request.getString("id"))
        assertEquals("s1", request.getString("sessionId"))
        assertEquals("keep going", request.getString("text"))
    }

    @Test
    fun sessionInputResultIsMatchedByRequestId() {
        val codec = AgentdProtocolCodec()
        val ok = codec.parse(
            """{"type":"session_input_result","id":"req-1","ok":true}""",
        ) as AgentdAction.SessionInputAnswered
        assertEquals("req-1", ok.result.requestId)
        assertTrue(ok.result.ok)
        assertNull(ok.result.error)

        val failed = codec.parse(
            """{"type":"session_input_result","id":"req-2","ok":false,"error":"still working"}""",
        ) as AgentdAction.SessionInputAnswered
        assertEquals("req-2", failed.result.requestId)
        assertEquals(false, failed.result.ok)
        assertEquals("still working", failed.result.error)

        assertEquals(
            AgentdAction.Ignore,
            codec.parse("""{"type":"session_input_result","ok":true}"""),
        )
    }
}
