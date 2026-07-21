package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.plugin.PluginCapability
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRoutePolicyTest {
    private fun plugin(vararg capabilities: PluginCapability) =
        PluginRouteCaller.Plugin("hello", capabilities.toSet())

    @Test
    fun `internal routes pass without plugin consent`() {
        assertEquals(PluginRouteDecision.Allowed, PluginRoutePolicy.authorize(PluginRouteCaller.Internal, "/launcher/list"))
        assertEquals(PluginRouteDecision.Allowed, PluginRoutePolicy.authorize(PluginRouteCaller.DebugLegacy, "/probe/echo"))
    }

    @Test
    fun `every privileged route requires its exact capability`() {
        assertEquals(PluginRouteDecision.Allowed, PluginRoutePolicy.authorize(plugin(PluginCapability.SURFACES), "/surface/show"))
        assertEquals(PluginRouteDecision.Allowed, PluginRoutePolicy.authorize(plugin(PluginCapability.MICROPHONE), "/audio/lease/acquire"))
        assertEquals(PluginRouteDecision.Allowed, PluginRoutePolicy.authorize(plugin(PluginCapability.HTTP_PROXY), "/http/request"))
        assertTrue(PluginRoutePolicy.authorize(plugin(), "/surface/update") is PluginRouteDecision.Denied)
        assertTrue(PluginRoutePolicy.authorize(plugin(), "/audio/lease/release") is PluginRouteDecision.Denied)
        assertTrue(PluginRoutePolicy.authorize(plugin(), "/http/request") is PluginRouteDecision.Denied)
    }

    @Test
    fun `camera grant allows only phone to glasses camera routes`() {
        val granted = plugin(PluginCapability.CAMERA)
        assertEquals(PluginRouteDecision.Allowed, PluginRoutePolicy.authorize(granted, "/camera/link/offer"))
        assertEquals(PluginRouteDecision.Allowed, PluginRoutePolicy.authorize(granted, "/camera/freeze/result"))
        assertEquals(PluginRouteDecision.Allowed, PluginRoutePolicy.authorize(granted, "/camera/overlay"))
        assertTrue(PluginRoutePolicy.authorize(granted, "/camera/session/state") is PluginRouteDecision.Denied)
        listOf(
            plugin(),
            PluginRouteCaller.Plugin("wrong", emptySet()),
            PluginRouteCaller.Pending,
            PluginRouteCaller.Revoked,
        ).forEach { caller ->
            assertTrue(PluginRoutePolicy.authorize(caller, "/camera/freeze/result") is PluginRouteDecision.Denied)
            assertTrue(PluginRoutePolicy.authorize(caller, "/camera/overlay") is PluginRouteDecision.Denied)
        }
    }

    @Test
    fun `plugin namespace is isolated with segment boundaries`() {
        assertEquals(PluginRouteDecision.Allowed, PluginRoutePolicy.authorize(plugin(), "/plugin/hello/event"))
        assertTrue(PluginRoutePolicy.authorize(plugin(), "/plugin/other/event") is PluginRouteDecision.Denied)
        assertTrue(PluginRoutePolicy.authorize(plugin(), "/plugin/hello-world") is PluginRouteDecision.Denied)
        assertTrue(PluginRoutePolicy.authorize(plugin(), "/launcherish") is PluginRouteDecision.Denied)
    }

    @Test
    fun `plugins cannot send hub control routes`() {
        listOf("/launcher/list", "/launcher/open", "/surface/input", "/system/plugin/open", "/security/grants", "/error")
            .forEach { path -> assertTrue(path, PluginRoutePolicy.authorize(plugin(), path) is PluginRouteDecision.Denied) }
    }

    @Test
    fun `pending revoked ambiguous and unregistered callers fail closed`() {
        listOf(
            PluginRouteCaller.Pending,
            PluginRouteCaller.Revoked,
            PluginRouteCaller.Ambiguous,
            PluginRouteCaller.Unregistered,
        ).forEach { caller ->
            assertTrue(PluginRoutePolicy.authorize(caller, "/plugin/hello") is PluginRouteDecision.Denied)
        }
    }

    @Test
    fun `surface owner and wire ID overwrite client identity`() {
        val result = PluginRoutePolicy.injectSurfaceOwner(
            "hello",
            JSONObject().put("surfaceId", "main").put("ownerPluginId", "spoofed"),
        )!!
        assertEquals("hello", result.getString("ownerPluginId"))
        assertEquals("main", result.getString("localSurfaceId"))
        assertEquals("hello:main", result.getString("surfaceId"))
    }
}
