package com.anezium.rokidbus.plugin.t3code

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class T3RpcTest {
    @Test
    fun `request round trips with headers encoded as an array`() {
        val encoded = T3Rpc.request("7", "server.probe", JSONObject().put("hello", "world"))
        val raw = JSONObject(encoded)

        assertTrue(raw.get("headers") is JSONArray)
        assertEquals(0, raw.getJSONArray("headers").length())
        val decoded = T3Rpc.decodeClient(encoded) as T3ClientFrame.Request
        assertEquals("7", decoded.id)
        assertEquals("server.probe", decoded.tag)
        assertEquals("world", decoded.payload.getString("hello"))
    }

    @Test
    fun `every decoded chunk carries the exact ack bookkeeping frame`() {
        val inbound = T3Rpc.decodeServer(
            """{"_tag":"Chunk","requestId":"4","values":[{"kind":"synchronized"}]}""",
        )

        assertNotNull(inbound)
        val chunk = inbound!!.frame as T3ServerFrame.Chunk
        assertEquals("4", chunk.requestId)
        assertEquals(1, chunk.values.size)
        val ack = T3Rpc.decodeClient(inbound.acknowledgment!!) as T3ClientFrame.Ack
        assertEquals("4", ack.requestId)
    }

    @Test
    fun `success and failure exits decode without json rpc assumptions`() {
        val success = T3Rpc.decodeServer(
            """{"_tag":"Exit","requestId":"0","exit":{"_tag":"Success","value":{"ok":true}}}""",
        )!!.frame as T3ServerFrame.Exit
        assertTrue(success.successful)
        assertTrue((success.value as JSONObject).getBoolean("ok"))

        val failure = T3Rpc.decodeServer(
            """{"_tag":"Exit","requestId":"1","exit":{"_tag":"Failure","cause":[{"_tag":"Fail","error":{"message":"bad model"}}]}}""",
        )!!.frame as T3ServerFrame.Exit
        assertEquals("bad model", failure.error)
    }
}
