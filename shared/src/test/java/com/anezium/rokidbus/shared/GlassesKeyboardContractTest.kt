package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `a reply missing any of its booleans is no answer`() {
        val complete = GlassesKeyboardContract.replyToJson(GlassesKeyboardReply(nexusSelected = true, canSwitch = true))

        listOf("nexusSelected", "canSwitch", "keepNexus").forEach { key ->
            assertNull(GlassesKeyboardContract.fromReply(JSONObject(complete.toString()).apply { remove(key) }))
            assertNull(GlassesKeyboardContract.fromReply(JSONObject(complete.toString()).put(key, "true")))
        }
        assertNull(GlassesKeyboardContract.fromReply(JSONObject(complete.toString()).apply { remove("version") }))
    }
}
