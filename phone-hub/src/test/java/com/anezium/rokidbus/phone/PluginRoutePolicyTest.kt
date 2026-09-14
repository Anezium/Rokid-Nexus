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
        assertEquals(PluginRouteDecision.Allowed, PluginRoutePolicy.authorize(plugin(PluginCapability.SURFACES), "/pin/show"))
        assertEquals(PluginRouteDecision.Allowed, PluginRoutePolicy.authorize(plugin(PluginCapability.SURFACES), "/pin/hide"))
        assertEquals(
            PluginRouteDecision.Allowed,
            PluginRoutePolicy.authorize(plugin(PluginCapability.INK_SURFACE), "/ink/show"),
        )
        assertEquals(PluginRouteDecision.Allowed, PluginRoutePolicy.authorize(plugin(PluginCapability.MICROPHONE), "/audio/lease/acquire"))
        assertEquals(PluginRouteDecision.Allowed, PluginRoutePolicy.authorize(plugin(PluginCapability.STT), "/stt/session/start"))
        assertEquals(PluginRouteDecision.Allowed, PluginRoutePolicy.authorize(plugin(PluginCapability.STT), "/stt/session/stop"))
        assertEquals(PluginRouteDecision.Allowed, PluginRoutePolicy.authorize(plugin(PluginCapability.TTS), "/tts/speak"))
        assertEquals(PluginRouteDecision.Allowed, PluginRoutePolicy.authorize(plugin(PluginCapability.TTS), "/tts/stop"))
        assertEquals(PluginRouteDecision.Allowed, PluginRoutePolicy.authorize(plugin(PluginCapability.HTTP_PROXY), "/http/request"))
        assertEquals(
            PluginRouteDecision.Allowed,
            PluginRoutePolicy.authorize(
                plugin(PluginCapability.WIRELESS_DEBUGGING),
                "/debug/adb/request",
            ),
        )
        assertTrue(PluginRoutePolicy.authorize(plugin(), "/surface/update") is PluginRouteDecision.Denied)
        assertTrue(PluginRoutePolicy.authorize(plugin(), "/pin/show") is PluginRouteDecision.Denied)
        assertEquals(
            PluginRouteDecision.Denied("CAPABILITY_REQUIRED_INK_SURFACE"),
            PluginRoutePolicy.authorize(plugin(PluginCapability.SURFACES), "/ink/update"),
        )
        assertTrue(PluginRoutePolicy.authorize(plugin(), "/audio/lease/release") is PluginRouteDecision.Denied)
        assertTrue(PluginRoutePolicy.authorize(plugin(), "/stt/session/start") is PluginRouteDecision.Denied)
        assertTrue(PluginRoutePolicy.authorize(plugin(), "/stt/session/stop") is PluginRouteDecision.Denied)
        assertEquals(
            PluginRouteDecision.Denied("CAPABILITY_REQUIRED_TTS"),
            PluginRoutePolicy.authorize(plugin(), "/tts/speak"),
        )
        assertEquals(
            PluginRouteDecision.Denied("CAPABILITY_REQUIRED_TTS"),
            PluginRoutePolicy.authorize(plugin(), "/tts/stop"),
        )
        assertTrue(PluginRoutePolicy.authorize(plugin(), "/http/request") is PluginRouteDecision.Denied)
        assertEquals(
            PluginRouteDecision.Denied("CAPABILITY_REQUIRED_WIRELESS_DEBUGGING"),
            PluginRoutePolicy.authorize(plugin(), "/debug/adb/request"),
        )
        assertEquals(
            PluginRouteDecision.Denied("PLUGIN_NAMESPACE_DENIED"),
            PluginRoutePolicy.authorize(
                plugin(PluginCapability.WIRELESS_DEBUGGING),
                "/debug/adb/reply",
            ),
        )
        assertEquals(
            PluginRouteDecision.Allowed,
            PluginRoutePolicy.authorize(plugin(PluginCapability.ASSISTANT), "/assistant/takeover/request"),
        )
        assertEquals(
            PluginRouteDecision.Denied("CAPABILITY_REQUIRED_ASSISTANT"),
            PluginRoutePolicy.authorize(plugin(PluginCapability.SURFACES), "/assistant/takeover/request"),
        )
        assertEquals(
            PluginRouteDecision.Denied("PLUGIN_NAMESPACE_DENIED"),
            PluginRoutePolicy.authorize(plugin(PluginCapability.ASSISTANT), "/assistant/takeover/reply"),
        )
    }

    @Test
    fun `camera grant allows only phone to glasses camera routes`() {
        val granted = plugin(PluginCapability.CAMERA)
        assertEquals(PluginRouteDecision.Allowed, PluginRoutePolicy.authorize(granted, "/camera/link/offer"))
        assertEquals(PluginRouteDecision.Allowed, PluginRoutePolicy.authorize(granted, "/camera/freeze/result"))
        assertEquals(PluginRouteDecision.Allowed, PluginRoutePolicy.authorize(granted, "/camera/overlay"))
        assertEquals(
            PluginRouteDecision.Allowed,
            PluginRoutePolicy.authorize(granted, "/camera/snapshot/request"),
        )
        assertTrue(PluginRoutePolicy.authorize(granted, "/camera/session/state") is PluginRouteDecision.Denied)
        listOf(
            plugin(),
            PluginRouteCaller.Plugin("wrong", emptySet()),
            PluginRouteCaller.Pending,
            PluginRouteCaller.Revoked,
        ).forEach { caller ->
            assertTrue(PluginRoutePolicy.authorize(caller, "/camera/freeze/result") is PluginRouteDecision.Denied)
            assertTrue(PluginRoutePolicy.authorize(caller, "/camera/overlay") is PluginRouteDecision.Denied)
            assertTrue(PluginRoutePolicy.authorize(caller, "/camera/snapshot/request") is PluginRouteDecision.Denied)
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
        assertEquals(
            PluginRouteDecision.Denied("SYSTEM_ROUTE_DENIED"),
            PluginRoutePolicy.authorize(plugin(PluginCapability.TTS), "/tts/cancel"),
        )
        assertEquals(
            PluginRouteDecision.Denied("SYSTEM_ROUTE_DENIED"),
            PluginRoutePolicy.authorize(PluginRouteCaller.DebugLegacy, "/tts/cancel"),
        )
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

    @Test
    fun `ink paths reuse the same owner namespace injection`() {
        listOf("/ink/show", "/ink/update", "/ink/hide").forEach { path ->
            assertEquals(
                PluginRouteDecision.Allowed,
                PluginRoutePolicy.authorize(plugin(PluginCapability.INK_SURFACE), path),
            )
            val result = PluginRoutePolicy.injectSurfaceOwner(
                "hello",
                JSONObject().put("surfaceId", "ink-main"),
            )!!
            assertEquals("hello", result.getString("ownerPluginId"))
            assertEquals("ink-main", result.getString("localSurfaceId"))
            assertEquals("hello:ink-main", result.getString("surfaceId"))
        }
    }
}
