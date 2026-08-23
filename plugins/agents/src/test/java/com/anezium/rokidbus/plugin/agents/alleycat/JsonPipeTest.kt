package com.anezium.rokidbus.plugin.agents.alleycat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class JsonPipeTest {
    @Test
    fun websocketPipeSendsOneJsonObjectWithNoLengthPrefix() {
        val pipe = QueueJsonPipe()
        pipe.sendJson(JSONObject().put("method", "thread/list").put("id", 1))
        val text = pipe.sentText().single()
        assertEquals('{', text.first())
        assertEquals("thread/list", JSONObject(text).getString("method"))
        assertEquals(1, JSONObject(text).getInt("id"))
    }

    @Test
    fun websocketPipeReceivesOneJsonObjectPerMessage() {
        val pipe = QueueJsonPipe()
        pipe.enqueueJson("""{"method":"item/agentMessage/delta","params":{"delta":"Hi"}}""")
        val json = pipe.receiveJson(50)
        assertEquals("item/agentMessage/delta", json!!.getString("method"))
        assertNull(pipe.receiveJson(20))
    }

    @Test
    fun failedPipeSurfacesAClearErrorInsteadOfHanging() {
        val pipe = QueueJsonPipe()
        pipe.fail(AlleycatException("connection failed"))
        try {
            pipe.receiveJson(5_000)
            fail("expected AlleycatException")
        } catch (e: AlleycatException) {
            assertEquals("connection failed", e.message)
        }
        try {
            pipe.sendJson(JSONObject().put("method", "thread/list"))
            fail("expected AlleycatException")
        } catch (e: AlleycatException) {
            assertEquals("connection failed", e.message)
        }
    }

    @Test
    fun appServerClientTalksJsonWithoutFramingOnAMessagePipe() {
        val pipe = QueueJsonPipe()
        pipe.enqueue(JSONObject("""{"id":1,"result":{"data":[{"id":"thr_1"}]}}"""))
        val client = CodexAppServerClient(pipe)
        assertEquals("thr_1", client.threadList().data.single().id)
        val sent = pipe.sentText().single()
        assertEquals('{', sent.first())
        assertEquals("thread/list", JSONObject(sent).getString("method"))
        assertTrue(!JSONObject(sent).has("jsonrpc"))
    }

    @Test
    fun framedPipeStillWritesBigEndianLengthPrefix() {
        val transport = ScriptedTransport(
            utf8Frame("""{"id":1,"result":{"data":[]}}"""),
        )
        val client = CodexAppServerClient(FramedJsonPipe(transport))
        assertEquals(0, client.threadList().data.size)
        val written = transport.written()
        assertTrue(written.size >= 4)
        val length = ByteBuffer.wrap(written).order(ByteOrder.BIG_ENDIAN).int
        assertEquals(written.size - 4, length)
    }
}
