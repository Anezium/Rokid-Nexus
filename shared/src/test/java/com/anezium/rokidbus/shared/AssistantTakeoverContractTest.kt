package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantTakeoverContractTest {
    @Test
    fun `status and set requests round trip`() {
        assertEquals(
            AssistantTakeoverRequest(AssistantTakeoverAction.STATUS),
            AssistantTakeoverContract.parseRequest(AssistantTakeoverContract.statusRequest()),
        )
        assertEquals(
            AssistantTakeoverRequest(AssistantTakeoverAction.SET, enabled = false),
            AssistantTakeoverContract.parseRequest(AssistantTakeoverContract.setRequest(false)),
        )
        assertEquals(
            AssistantTakeoverRequest(AssistantTakeoverAction.SET, enabled = true),
            AssistantTakeoverContract.parseRequest(AssistantTakeoverContract.setRequest(true)),
        )
    }

    @Test
    fun `malformed requests fail closed`() {
        assertNull(AssistantTakeoverContract.parseRequest(JSONObject()))
        assertNull(
            AssistantTakeoverContract.parseRequest(
                JSONObject().put("version", 2).put("action", "status"),
            ),
        )
        assertNull(
            AssistantTakeoverContract.parseRequest(
                JSONObject().put("version", 1).put("action", "toggle"),
            ),
        )
        // A `set` that does not say which way is not a request to act on.
        assertNull(
            AssistantTakeoverContract.parseRequest(
                JSONObject().put("version", 1).put("action", "set"),
            ),
        )
        assertNull(
            AssistantTakeoverContract.parseRequest(
                JSONObject().put("version", 1).put("action", "set").put("enabled", "yes"),
            ),
        )
    }

    @Test
    fun `reply carries the stamped owner and the switch position`() {
        val reply = AssistantTakeoverContract.reply("assistant", enabled = false)

        assertEquals(1, reply.getInt("version"))
        assertEquals("assistant", reply.getString("pluginId"))
        assertEquals(false, AssistantTakeoverContract.replyEnabled(reply))
        assertEquals(true, AssistantTakeoverContract.replyEnabled(AssistantTakeoverContract.reply("assistant", true)))
        assertNull(AssistantTakeoverContract.replyEnabled(JSONObject().put("enabled", true)))
        assertNull(AssistantTakeoverContract.replyEnabled(JSONObject().put("version", 1)))
    }

    @Test
    fun `reply refuses an invalid owner id`() {
        val thrown = runCatching { AssistantTakeoverContract.reply("Not Valid", true) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }
}
