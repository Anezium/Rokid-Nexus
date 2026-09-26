package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlassesKeyboardContractTest {
    @Test
    fun `both known actions round trip`() {
        listOf(GlassesKeyboardContract.ACTION_STATUS, GlassesKeyboardContract.ACTION_USE_NEXUS).forEach { action ->
            val payload = GlassesKeyboardContract.requestToJson(action)

            assertEquals(action, GlassesKeyboardContract.actionFromRequest(payload))
        }
    }

    @Test
    fun `an unknown action or version is refused`() {
        assertNull(GlassesKeyboardContract.actionFromRequest(GlassesKeyboardContract.requestToJson("use_rokid")))
        assertNull(GlassesKeyboardContract.actionFromRequest(JSONObject().put("action", "status")))
        assertNull(GlassesKeyboardContract.actionFromRequest(null))
    }

    @Test
    fun `a reply round trips with the foreign keyboard's package`() {
        val sent = GlassesKeyboardReply(
            nexusSelected = false,
            canSwitch = true,
            currentPackage = "com.rokid.os.sprite.assistserver",
        )

        assertEquals(sent, GlassesKeyboardContract.fromReply(GlassesKeyboardContract.replyToJson(sent)))
    }

    @Test
    fun `a failed switch keeps its error`() {
        val sent = GlassesKeyboardReply(
            nexusSelected = false,
            canSwitch = false,
            error = GlassesKeyboardContract.ERROR_PERMISSION_MISSING,
        )

        assertEquals(sent, GlassesKeyboardContract.fromReply(GlassesKeyboardContract.replyToJson(sent)))
    }

    @Test
    fun `a reply without a boolean selection is no answer`() {
        val payload = JSONObject()
            .put("version", GlassesKeyboardContract.VERSION)
            .put("nexusSelected", "true")

        assertNull(GlassesKeyboardContract.fromReply(payload))
        assertNull(GlassesKeyboardContract.fromReply(JSONObject().put("nexusSelected", true)))
    }
}
