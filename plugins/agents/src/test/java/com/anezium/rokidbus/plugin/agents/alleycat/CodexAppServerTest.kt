package com.anezium.rokidbus.plugin.agents.alleycat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexAppServerTest {
    @Test
    fun approvalRoundTripAccept() {
        val inbound = utf8Frame(
            """{"id":7,"method":"item/commandExecution/requestApproval","params":{"command":"ls"}}""",
        )
        val transport = ScriptedTransport(inbound)
        val client = CodexAppServerClient(transport)
        val request = client.receive() as CodexInbound.ApprovalRequest
        assertEquals(JsonRpcId.NumberId(7L), request.id)
        assertEquals("ls", request.params.getString("command"))

        client.replyApproval(request.id, ApprovalVerdict.ACCEPT)
        val reply = decodeFrames(transport.written()).single()
        assertEquals(7, reply.getLong("id"))
        assertEquals("Accept", reply.getJSONObject("result").getString("decision"))
        assertTrue(!reply.has("method"))
    }

    @Test
    fun encodesAllFourApprovalVerdicts() {
        val expected = mapOf(
            ApprovalVerdict.ACCEPT to "Accept",
            ApprovalVerdict.ACCEPT_FOR_SESSION to "AcceptForSession",
            ApprovalVerdict.DECLINE to "Decline",
            ApprovalVerdict.CANCEL to "Cancel",
        )
        expected.forEach { (verdict, wire) ->
            val json = CodexJsonRpc.approvalReply(JsonRpcId.StringId("a1"), verdict)
            assertEquals("a1", json.getString("id"))
            assertEquals(wire, json.getJSONObject("result").getString("decision"))
        }
    }

    @Test
    fun threadListRoundTripAndTurnRpcs() {
        val transport = ScriptedTransport(
            utf8Frame("""{"id":1,"result":{"data":[{"id":"thr_1","preview":"hi"}],"nextCursor":"c2"}}""") +
                utf8Frame("""{"id":2,"result":{"thread":{"id":"thr_2"}}}""") +
                utf8Frame("""{"id":3,"result":{"thread":{"id":"thr_1"}}}""") +
                utf8Frame("""{"id":4,"result":{"turn":{"id":"turn_9","status":"inProgress"}}}""") +
                utf8Frame("""{"id":5,"result":{"turnId":"turn_9"}}"""),
        )
        val client = CodexAppServerClient(transport)
        val page = client.threadList(cursor = "c1", limit = 25)
        assertEquals("thr_1", page.data.single().id)
        assertEquals("c2", page.nextCursor)
        assertEquals("thr_2", client.threadStart(model = "gpt-5.4").id)
        assertEquals("thr_1", client.threadResume("thr_1").id)
        assertEquals("turn_9", client.turnStart("thr_1", "Run tests").id)
        assertEquals("turn_9", client.turnSteer("thr_1", "focus on failures", expectedTurnId = "turn_9").turnId)

        val sent = decodeFrames(transport.written())
        assertEquals("thread/list", sent[0].getString("method"))
        assertEquals("c1", sent[0].getJSONObject("params").getString("cursor"))
        assertEquals("thread/start", sent[1].getString("method"))
        assertEquals("thread/resume", sent[2].getString("method"))
        assertEquals("turn/start", sent[3].getString("method"))
        assertEquals("turn/steer", sent[4].getString("method"))
        sent.forEach { assertTrue(!it.has("jsonrpc")) }
    }

    @Test
    fun parsesItemDeltaAndCommandExecutionNotifications() {
        val delta = CodexJsonRpc.parse(
            JSONObject("""{"method":"item/agentMessage/delta","params":{"delta":"Hi"}}"""),
        ) as CodexInbound.Notification
        assertTrue(delta.isItemDelta)
        assertEquals("Hi", delta.params!!.getString("delta"))

        val exec = CodexJsonRpc.parse(
            JSONObject("""{"method":"item/commandExecution/outputDelta","params":{"delta":"$ ls"}}"""),
        ) as CodexInbound.Notification
        assertTrue(exec.isCommandExecution)
    }
}
