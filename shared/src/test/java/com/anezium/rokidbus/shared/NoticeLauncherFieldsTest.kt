package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeLauncherFieldsTest {
    private fun show(): JSONObject = JSONObject().put("kind", NoticeSurfaceContract.KIND).put("title", "Mika").put("body", "On se voit à 8h ?")

    private fun thread(): JSONObject = JSONObject().put("pageId", "relay:thread:9f2c").put("params", JSONObject().put("t", "9f2c"))

    @Test
    fun `a conversation event names its thread and round trips`() {
        val payload = show().put("destination", thread()).put("eventKey", "9f2c-1").put("conversation", true)
        val content = valid(payload)
        assertEquals(PageDestination("relay:thread:9f2c", """{"t":"9f2c"}"""), content.destination)
        assertEquals("9f2c-1", content.eventKey)
        assertTrue(content.conversation)
        assertTrue(NoticeSurfaceContract.destinationAllowed(content, "relay"))
        assertFalse(NoticeSurfaceContract.destinationAllowed(content, "nav"))
        assertEquals(content, valid(JSONObject(NoticeSurfaceContract.toPayload("relay:notice", content).toString())))
    }

    @Test
    fun `a plain notice puts nothing new on the wire`() {
        val content = valid(show())
        val payload = NoticeSurfaceContract.toPayload("relay:notice", content)
        listOf("destination", "eventKey", "conversation").forEach { assertFalse(it, payload.has(it)) }
        assertNull(content.destination)
        assertNull(content.eventKey)
        assertFalse(content.conversation)
    }

    @Test
    fun `malformed launcher fields are refused`() {
        invalid(show().put("conversation", true))
        invalid(show().put("conversation", true).put("destination", thread()))
        invalid(show().put("conversation", true).put("eventKey", "k"))
        invalid(show().put("conversation", "yes"))
        invalid(show().put("eventKey", "has space"))
        invalid(show().put("eventKey", "x".repeat(129)))
        invalid(show().put("destination", JSONObject().put("pageId", "nexus:notifications")))
        invalid(show().put("destination", "relay:main"))
        // A generic event may lead somewhere, or be withdrawable, without being a message.
        valid(show().put("destination", JSONObject().put("pageId", "relay:main")))
        valid(show().put("eventKey", "k"))
    }

    @Test
    fun `launcher fields are show-only`() {
        listOf("destination", "eventKey", "conversation").forEach { key ->
            val update = JSONObject().put("body", "later").put(key, if (key == "conversation") true else "k")
            assertTrue(key, NoticeSurfaceContract.validateUpdate(update) is NoticeSurfacePatchResult.Invalid)
        }
    }

    @Test
    fun `old glasses get the notice without its launcher fields`() {
        val payload = NoticeSurfaceContract.toPayload(
            "relay:notice",
            valid(show().put("destination", thread()).put("eventKey", "k").put("conversation", true)),
        )
        val legacy = NoticeSurfaceContract.stripLauncherFields(payload)
        listOf("destination", "eventKey", "conversation").forEach { assertFalse(it, legacy.has(it)) }
        assertEquals("Mika", legacy.getString("title"))
        assertTrue(payload.has("eventKey"))
    }

    @Test
    fun `withdraw travels plugin to phone to glasses with the owner the phone stamps`() {
        val fromPlugin = NoticeSurfaceContract.withdrawPayload("9f2c-1")
        assertEquals(NoticeSurfaceContract.LOCAL_SURFACE_ID, fromPlugin.getString("surfaceId"))
        assertEquals("9f2c-1", NoticeSurfaceContract.parseWithdraw(fromPlugin))
        assertNull(NoticeSurfaceContract.parseWithdraw(JSONObject().put("eventKey", "a b")))
        assertNull(NoticeSurfaceContract.parseWithdraw(JSONObject()))

        val toGlasses = NoticeSurfaceContract.glassesWithdrawPayload(NoticeWithdrawal("relay", "9f2c-1"))
        assertEquals(NoticeWithdrawal("relay", "9f2c-1"), NoticeSurfaceContract.parseGlassesWithdraw(toGlasses))
        // The glasses never accept a withdrawal without the authenticated owner.
        assertNull(NoticeSurfaceContract.parseGlassesWithdraw(fromPlugin))
        assertNull(NoticeSurfaceContract.parseGlassesWithdraw(JSONObject(toGlasses.toString()).put("ownerPluginId", "@nexus-hub")))

        assertEquals("relay", NoticeSurfaceContract.parsePurge(NoticeSurfaceContract.purgePayload("relay")))
        assertNull(NoticeSurfaceContract.parsePurge(JSONObject().put("ownerPluginId", "Not Valid")))
    }

    private fun valid(payload: JSONObject): NoticeSurfaceContent {
        val result = NoticeSurfaceContract.validateShow(payload)
        assertTrue("$result", result is NoticeSurfaceValidationResult.Valid)
        return (result as NoticeSurfaceValidationResult.Valid).content
    }

    private fun invalid(payload: JSONObject) {
        assertTrue("$payload", NoticeSurfaceContract.validateShow(payload) is NoticeSurfaceValidationResult.Invalid)
    }
}
