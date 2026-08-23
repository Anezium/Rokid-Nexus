package com.anezium.rokidbus.plugin.agents.alleycat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AlleycatFramingTest {
    @Test
    fun roundTripsHandwrittenFrame() {
        val fixture = utf8Frame("""{"op":"list_agents","v":1}""")
        val decoded = AlleycatFraming.decode(fixture)
        assertEquals("list_agents", decoded.getString("op"))
        assertEquals(1, decoded.getInt("v"))

        val encoded = AlleycatFraming.encode(JSONObject().put("op", "list_agents").put("v", 1))
        val again = AlleycatFraming.decode(encoded)
        assertEquals("list_agents", again.getString("op"))
        assertEquals(1, again.getInt("v"))
    }

    @Test
    fun rejectsOversizeLengthPrefixWithoutReadingBody() {
        val header = ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(AlleycatFraming.MAX_PAYLOAD_BYTES + 1)
            .array()
        try {
            AlleycatFraming.decode(header)
            fail("expected oversize rejection")
        } catch (e: AlleycatException) {
            assertTrue(e.message!!.contains("rejected frame length"))
        }

        val transport = ScriptedTransport(header)
        try {
            AlleycatFraming.read(transport)
            fail("expected oversize rejection on transport")
        } catch (e: AlleycatException) {
            assertTrue(e.message!!.contains("rejected frame length"))
        }
    }
}
