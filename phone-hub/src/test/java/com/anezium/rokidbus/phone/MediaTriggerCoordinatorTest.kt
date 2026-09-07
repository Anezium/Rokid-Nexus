package com.anezium.rokidbus.phone

import android.content.ComponentName
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaTriggerCoordinatorTest {
    private class FakeScheduler : ExternalPluginScheduler {
        val actions = linkedMapOf<String, () -> Unit>()
        override fun schedule(key: String, delayMs: Long, action: () -> Unit) { actions[key] = action }
        override fun cancel(key: String) { actions.remove(key) }
        fun runPending() { actions.values.toList().also { actions.clear() }.forEach { it() } }
    }
    private class FakeRuntime : ExternalPluginRuntime {
        var registered = true
        val deliveries = mutableListOf<Pair<String, JSONObject>>()
        var binds = 0
        var unbinds = 0
        var hides = 0
        override fun bind(principal: PhonePluginPrincipal): Boolean { binds++; return true }
        override fun isRegistered(principal: PhonePluginPrincipal) = registered
        override fun deliver(principal: PhonePluginPrincipal, path: String, id: String, payload: JSONObject): Boolean {
            deliveries += path to payload
            return true
        }
        override fun hideOwnedSurfaces(pluginId: String) { hides++ }
        override fun unbind(principal: PhonePluginPrincipal) { unbinds++ }
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


    private inner class Harness {
        var now = 0L
        var approved: PhonePluginPrincipal? = principal
        var foreground: String? = "assistant"
        val runtime = FakeRuntime()
        val scheduler = FakeScheduler()
        val coordinator = MediaTriggerCoordinator(
            clock = { now }, runtime = runtime, scheduler = scheduler,
            resolveRegisteredPlugin = { approved }, foregroundPluginId = { foreground }, graceMs = 5_000,
        )
        fun play() { coordinator.onLinkChanged(true); coordinator.onPlaybackChanged(true) }
    }

    @Test fun playbackOpensOnlyAmbientTargetWhileAssistantKeepsForeground() {
        val h = Harness(); h.play()
        assertEquals("assistant", h.foreground)
        assertTrue(h.coordinator.isHoldingOpen)
        assertEquals(listOf(BusPaths.PLUGIN_OPEN), h.runtime.deliveries.map { it.first })
        assertEquals(BusPaths.PLUGIN_OPEN_TYPE_MEDIA_TRIGGER, h.runtime.deliveries.single().second.getString("type"))
    }

    @Test fun noGrantOrNoLinkNeverBinds() {
        val h = Harness(); h.coordinator.onPlaybackChanged(true)
        assertEquals(0, h.runtime.binds)
        h.approved = null; h.coordinator.onLinkChanged(true)
        assertEquals(0, h.runtime.binds)
    }

    @Test fun registrationTimeoutReleasesAndRetriesOnlyAfterCooldown() {
        val h = Harness(); h.runtime.registered = false; h.play()
        assertFalse(h.coordinator.isHoldingOpen)
        h.scheduler.runPending()
        assertEquals(1, h.runtime.unbinds)
        h.coordinator.tickGrace(); assertEquals(1, h.runtime.binds)
        h.now = MediaTriggerCoordinator.REOPEN_COOLDOWN_MS
        h.coordinator.tickGrace(); assertEquals(2, h.runtime.binds)
        h.runtime.registered = true; h.coordinator.onRegistered(principal)
        assertTrue(h.coordinator.isHoldingOpen)
    }

    @Test fun pauseGraceClosesAmbientWithoutClosingAnotherForegroundApp() {
        val h = Harness(); h.play(); h.coordinator.onPlaybackChanged(false)
        h.now = 4_999; h.coordinator.tickGrace(); assertTrue(h.coordinator.isHoldingOpen)
        h.now = 5_000; h.coordinator.tickGrace(); assertFalse(h.coordinator.isHoldingOpen)
        assertEquals("assistant", h.foreground)
        assertEquals("lyrics", h.runtime.deliveries.last().second.getString("pluginId"))
    }

    @Test fun foregroundLyricsIsNotDemotedOrClosedByAmbientLifecycle() {
        val h = Harness(); h.foreground = "lyrics"; h.play()
        assertTrue(h.runtime.deliveries.isEmpty())
        h.coordinator.onListenerLost()
        assertTrue(h.runtime.deliveries.isEmpty())
        assertEquals(1, h.runtime.hides)
        assertEquals(1, h.runtime.unbinds)
    }

    @Test fun foregroundCloseReopensAmbientWithoutTakingForeground() {
        val h = Harness(); h.play()
        h.coordinator.onPluginClosed("lyrics", "switch")
        h.scheduler.runPending()
        assertEquals(2, h.runtime.deliveries.count { it.first == BusPaths.PLUGIN_OPEN })
        assertEquals("assistant", h.foreground)
    }

    @Test fun linkLossClearsAndReconnectReopensStillPlayingTrack() {
        val h = Harness(); h.play(); h.coordinator.onLinkChanged(false)
        assertFalse(h.coordinator.isHoldingOpen)
        assertEquals(1, h.runtime.hides)
        h.coordinator.onLinkChanged(true)
        assertTrue(h.coordinator.isHoldingOpen)
    }

    @Test fun revokedGrantAndNotificationAccessStopTheAmbientSession() {
        val h = Harness(); h.play(); h.approved = null; h.coordinator.tickGrace()
        assertFalse(h.coordinator.isHoldingOpen)
        h.approved = principal; h.coordinator.tickGrace(); assertTrue(h.coordinator.isHoldingOpen)
        h.coordinator.onListenerLost(); h.coordinator.tickGrace()
        assertFalse(h.coordinator.isHoldingOpen)
        assertEquals(2, h.runtime.binds)
    }
}
