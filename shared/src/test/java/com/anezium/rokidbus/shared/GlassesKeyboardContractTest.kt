package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesKeyboardContractTest {
    @Test
    fun `every known request round trips`() {
        listOf(
            GlassesKeyboardRequest(GlassesKeyboardContract.ACTION_STATUS),
            GlassesKeyboardRequest(GlassesKeyboardContract.ACTION_USE_NEXUS),
            GlassesKeyboardRequest(GlassesKeyboardContract.ACTION_SET_KEEP, keep = false),
            GlassesKeyboardRequest(GlassesKeyboardContract.ACTION_SET_KEEP, keep = true),
        ).forEach { request ->
            assertEquals(request, GlassesKeyboardContract.fromRequest(GlassesKeyboardContract.requestToJson(request)))
        }
    }

    @Test
    fun `an unknown action or version is refused`() {
        assertNull(GlassesKeyboardContract.fromRequest(GlassesKeyboardContract.requestToJson(GlassesKeyboardRequest("use_rokid"))))
        assertNull(GlassesKeyboardContract.fromRequest(JSONObject().put("action", "status")))
        assertNull(GlassesKeyboardContract.fromRequest(null))
    }

    @Test
    fun `a keep change without a boolean is refused`() {
        val missing = GlassesKeyboardContract.requestToJson(GlassesKeyboardRequest(GlassesKeyboardContract.ACTION_SET_KEEP))
        val stringly = JSONObject(missing.toString()).put("keep", "false")

        assertNull(GlassesKeyboardContract.fromRequest(missing))
        assertNull(GlassesKeyboardContract.fromRequest(stringly))
    }

    @Test
    fun `a reply round trips with the foreign keyboard's package and the keep switch`() {
        val sent = GlassesKeyboardReply(
            nexusSelected = false,
            canSwitch = true,
            keepNexus = false,
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
    fun `a reply without the keep switch reads it as on`() {
        val payload = JSONObject()
            .put("version", GlassesKeyboardContract.VERSION)
            .put("nexusSelected", true)

        assertTrue(GlassesKeyboardContract.fromReply(payload)?.keepNexus == true)
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
