package com.anezium.rokidbus.shared

import com.anezium.rokidbus.shared.plugin.PathRules
import com.anezium.rokidbus.shared.plugin.PluginCapability
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderedMapContractTest {
    @Test
    fun `old phone or old glasses cannot negotiate attributed rendered maps through the launcher bit`() {
        val old = BusCapabilityBits.LAUNCHER_INTERACTIVE
        val current = old or BusCapabilityBits.RENDERED_MAP
        fun phone(features: Int) = PhoneHubCapabilitiesContract.parse(PhoneHubCapabilitiesContract.toJson(
            PhoneHubCapabilitiesContract.create(features, null, launcherInteractiveVersion = 1))).features
        fun glasses(features: Int) = GlassesHubCapabilitiesContract.parse(GlassesHubCapabilitiesContract.toJson(
            GlassesHubCapabilitiesContract.create(features, imageSurfaceVersion = 0, maxImageBytes = 0,
                versionName = null, launcherInteractiveVersion = 1))).features
        assertFalse(RenderedMapContract.negotiated(phone(old), glasses(current)))
        assertFalse(RenderedMapContract.negotiated(phone(current), glasses(old)))
        assertTrue(RenderedMapContract.negotiated(phone(current), glasses(current)))
        assertFalse(RenderedMapContract.negotiated(0, glasses(current)))
    }

    @Test
    fun `feedback is hub-only owner-scoped bounded and strips private rendering authority`() {
        val path = RenderedMapContract.PATH
        assertTrue(PathRules.isReserved(path))
        assertTrue(PathRules.isHubOnly(path))
        assertTrue(PathRules.isDirectReply(path))
        assertTrue(PathRules.isOwnerScoped(path))
        assertFalse(PathRules.isAllowedReceivePrefix(path, "nav", setOf(PluginCapability.SURFACES)))
        val event = RenderedMapContext("page", "nav:main", 3, sessionGeneration = 8, frameIndex = 1, requestId = "request").toPayload()
            .put("view", "overview").put("token", "token").put("visible", true).put("pluginId", "forged")
        assertTrue(RenderedMapContract.validEvent(event))
        val delivered = RenderedMapContract.delivery(event, "nav")
        assertEquals("nav", delivered.getString("pluginId"))
        assertFalse(delivered.has("id"))
        assertFalse(delivered.has("requestId"))
        assertEquals(3L, delivered.getLong("revision"))
        assertEquals("nav:main", delivered.getString("pageId"))
        assertFalse(RenderedMapContract.validEvent(JSONObject(event.toString()).put("frameIndex", PageSurfaceContract.MAX_FRAMES)))
        assertFalse(RenderedMapContract.validEvent(JSONObject(event.toString()).put("revision", 3.5)))
        assertFalse(RenderedMapContract.validEvent(JSONObject(event.toString()).put("token", "x".repeat(65))))
    }
}
