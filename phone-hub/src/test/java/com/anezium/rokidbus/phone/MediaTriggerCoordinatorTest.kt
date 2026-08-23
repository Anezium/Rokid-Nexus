package com.anezium.rokidbus.phone

import android.content.ComponentName
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance #9 and #10 for the ambient media trigger: a playback-start edge with a registered
 * plugin opens it with the media-trigger type; the no-playing grace closes it; a visible
 * surface defers the close; and without any registered plugin the trigger stays inert.
 */
class MediaTriggerCoordinatorTest {
    private class FakeScheduler : ExternalPluginScheduler {
        val actions = linkedMapOf<String, () -> Unit>()
        override fun schedule(key: String, delayMs: Long, action: () -> Unit) {
            actions[key] = action
        }
        override fun cancel(key: String) {
            actions.remove(key)
        }
    }

    private class FakeRuntime : ExternalPluginRuntime {
        var registered = true
        val deliveries = mutableListOf<Pair<String, JSONObject>>()
        override fun bind(principal: PhonePluginPrincipal): Boolean = true
        override fun isRegistered(principal: PhonePluginPrincipal): Boolean = registered
        override fun deliver(principal: PhonePluginPrincipal, path: String, id: String, payload: JSONObject): Boolean {
            deliveries += path to JSONObject(payload.toString())
            return true
        }
        override fun hideOwnedSurfaces(pluginId: String) = Unit
        override fun unbind(principal: PhonePluginPrincipal) = Unit
    }

    private val principal = PhonePluginPrincipal(
        packageName = "com.anezium.rokidbus.plugin.lyrics",
        serviceComponent = ComponentName("com.anezium.rokidbus.plugin.lyrics", "LyricsPluginService"),
        uid = 11,
        signingDigestSha256 = "digest-lyrics",
        descriptor = PluginDescriptor(
            id = "lyrics",
            displayName = "Lyrics",
            apiVersion = 3,
            requestedCapabilities = setOf(PluginCapability.SURFACES),
            receivePrefixes = listOf("/plugin/lyrics", "/system/plugin"),
            settingsActivity = null,
            launchable = true,
            mediaTrigger = true,
        ),
    )

    private val otherPrincipal = PhonePluginPrincipal(
        packageName = "com.anezium.rokidbus.plugin.relay",
        serviceComponent = ComponentName("com.anezium.rokidbus.plugin.relay", "RelayPluginService"),
        uid = 12,
        signingDigestSha256 = "digest-relay",
        descriptor = PluginDescriptor(
            id = "relay",
            displayName = "Relay",
            apiVersion = 3,
            requestedCapabilities = setOf(PluginCapability.SURFACES),
            receivePrefixes = listOf("/plugin/relay", "/system/plugin"),
            settingsActivity = null,
            launchable = true,
            mediaTrigger = false,
        ),
    )

    private class Harness {
        var now = 0L
        var visibleSurface = false
        val runtime = FakeRuntime()
        val controller = ExternalPluginController(runtime, FakeScheduler())
        var resolveTo: PhonePluginPrincipal? = null
        val coordinator = MediaTriggerCoordinator(
            clock = { now },
            externalPluginController = controller,
            resolveRegisteredPlugin = { resolveTo },
            pluginOwnsVisibleSurface = { visibleSurface },
            logger = {},
        )
        init {
            controller.setPluginClosedListener(coordinator::onPluginClosed)
        }
        val opens: List<JSONObject>
            get() = runtime.deliveries.filter { it.first == BusPaths.PLUGIN_OPEN }.map { it.second }
        val mediaTriggerOpens: List<JSONObject>
            get() = opens.filter {
                it.optString("type") == MediaTriggerCoordinator.PLUGIN_MEDIA_TRIGGER_OPEN_TYPE
            }
        val closes: List<JSONObject>
            get() = runtime.deliveries.filter { it.first == BusPaths.PLUGIN_CLOSE }.map { it.second }
    }

    @Test
    fun `playback start opens the registered plugin with the media trigger type`() {
        val h = Harness()
        h.resolveTo = principal
        h.coordinator.onPlaybackChanged(true)

        assertEquals(1, h.opens.size)
        assertEquals(MediaTriggerCoordinator.PLUGIN_MEDIA_TRIGGER_OPEN_TYPE, h.opens.single().getString("type"))
        assertEquals("lyrics", h.opens.single().getString("pluginId"))
        assertTrue(h.coordinator.isHoldingOpen)
    }

    @Test
    fun `user close while playing reopens the plugin in the background`() {
        val h = Harness()
        h.resolveTo = principal
        h.coordinator.onPlaybackChanged(true)

        h.controller.onPluginSelfHid("lyrics")

        assertEquals(2, h.mediaTriggerOpens.size)
        assertEquals(
            MediaTriggerCoordinator.PLUGIN_MEDIA_TRIGGER_OPEN_TYPE,
            h.mediaTriggerOpens.last().getString("type"),
        )
        assertEquals("lyrics", h.controller.activeId())
        assertTrue(h.coordinator.isHoldingOpen)
    }

    @Test
    fun `media idle close does not reopen the plugin`() {
        val h = Harness()
        h.resolveTo = principal
        h.coordinator.onPlaybackChanged(true)

        h.controller.closeActive(MediaTriggerCoordinator.PLUGIN_MEDIA_TRIGGER_CLOSE_REASON)

        assertEquals(1, h.mediaTriggerOpens.size)
        assertEquals(null, h.controller.activeId())
        assertFalse(h.coordinator.isHoldingOpen)
    }

    @Test
    fun `switch close does not reopen the media trigger plugin`() {
        val h = Harness()
        h.resolveTo = principal
        h.coordinator.onPlaybackChanged(true)

        h.controller.open(otherPrincipal)

        assertEquals(1, h.mediaTriggerOpens.size)
        assertEquals("relay", h.controller.activeId())
        assertFalse(h.coordinator.isHoldingOpen)
    }

    @Test
    fun `close while not playing does not reopen the plugin`() {
        val h = Harness()
        h.resolveTo = principal
        h.coordinator.onPlaybackChanged(true)
        h.coordinator.onPlaybackChanged(false)

        h.controller.closeActive("close")

        assertEquals(1, h.mediaTriggerOpens.size)
        assertEquals(null, h.controller.activeId())
        assertFalse(h.coordinator.isHoldingOpen)
    }

    @Test
    fun `two quick closes produce exactly one background reopen`() {
        val h = Harness()
        h.resolveTo = principal
        h.coordinator.onPlaybackChanged(true)

        h.controller.closeActive("close")
        h.now = MediaTriggerCoordinator.REOPEN_COOLDOWN_MS - 1
        h.controller.closeActive("close")

        assertEquals(2, h.mediaTriggerOpens.size)
        assertEquals(null, h.controller.activeId())
        assertFalse(h.coordinator.isHoldingOpen)
    }

    @Test
    fun `sixty second grace without playing closes the plugin`() {
        val h = Harness()
        h.resolveTo = principal
        h.coordinator.onPlaybackChanged(true)
        h.now = 1_000
        h.coordinator.onPlaybackChanged(false)

        h.now = 1_000 + 59_000
        h.coordinator.tickGrace()
        assertTrue(h.closes.isEmpty())

        h.now = 1_000 + 61_000
        h.coordinator.tickGrace()
        assertEquals(1, h.closes.size)
        assertTrue(!h.coordinator.isHoldingOpen)
    }

    @Test
    fun `close is skipped while the plugin owns a visible surface`() {
        val h = Harness()
        h.resolveTo = principal
        h.coordinator.onPlaybackChanged(true)
        h.now = 1_000
        h.coordinator.onPlaybackChanged(false)
        h.visibleSurface = true
        h.now = 1_000 + 120_000
        h.coordinator.tickGrace()
        assertTrue(h.closes.isEmpty())

        h.visibleSurface = false
        h.coordinator.tickGrace()
        assertEquals(1, h.closes.size)
    }

    @Test
    fun `grace close does not close a different active plugin`() {
        val h = Harness()
        h.resolveTo = principal
        h.coordinator.onPlaybackChanged(true)
        assertEquals(1, h.opens.size)
        assertEquals("lyrics", h.controller.activeId())

        h.controller.open(otherPrincipal)
        assertEquals("relay", h.controller.activeId())
        val closesAfterSwitch = h.closes.size

        h.now = 1_000
        h.coordinator.onPlaybackChanged(false)
        h.now = 1_000 + 61_000
        h.coordinator.tickGrace()

        assertEquals(closesAfterSwitch, h.closes.size)
        assertEquals("relay", h.controller.activeId())
        assertTrue(!h.coordinator.isHoldingOpen)
    }

    @Test
    fun `without a registered plugin the trigger stays inert`() {
        val h = Harness()
        h.resolveTo = null
        h.coordinator.onPlaybackChanged(true)
        assertTrue(h.opens.isEmpty())
        assertTrue(!h.coordinator.isHoldingOpen)
    }
}
