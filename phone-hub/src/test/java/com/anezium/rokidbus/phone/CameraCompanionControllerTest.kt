package com.anezium.rokidbus.phone

import android.content.ComponentName
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCompanionControllerTest {
    private class FakeScheduler : ExternalPluginScheduler {
        val actions = linkedMapOf<String, () -> Unit>()
        override fun schedule(key: String, delayMs: Long, action: () -> Unit) { actions[key] = action }
        override fun cancel(key: String) { actions.remove(key) }
        fun runAll() {
            val pending = actions.values.toList()
            actions.clear()
            pending.forEach { it() }
        }
    }

    private class FakeRuntime : CameraCompanionRuntime {
        var bindResult = true
        var registered = false
        val bound = mutableListOf<String>()
        val unbound = mutableListOf<String>()
        val deliveries = mutableListOf<Pair<String, JSONObject>>()
        val binaryDeliveries = mutableListOf<Pair<String, ByteArray>>()
        override fun bind(principal: PhonePluginPrincipal): Boolean {
            bound += principal.descriptor.id
            return bindResult
        }
        override fun isRegistered(principal: PhonePluginPrincipal): Boolean = registered
        override fun deliver(principal: PhonePluginPrincipal, path: String, id: String, payload: JSONObject): Boolean {
            deliveries += path to JSONObject(payload.toString())
            return registered
        }
        override fun deliverBinary(
            principal: PhonePluginPrincipal,
            path: String,
            id: String,
            payload: JSONObject,
            data: ByteArray,
        ): Boolean {
            binaryDeliveries += path to data.copyOf()
            return registered
        }
        override fun unbind(principal: PhonePluginPrincipal) { unbound += principal.descriptor.id }
    }

    private val principal = PhonePluginPrincipal(
        "dev.lens",
        ComponentName("dev.lens", "dev.lens.Service"),
        10001,
        "digest",
        PluginDescriptor(
            "lens",
            "Lens",
            3,
            setOf(PluginCapability.CAMERA),
            listOf(
                "/system/plugin",
                "/camera/session/state",
                "/camera/link/offer",
                BusPaths.CAMERA_FREEZE_IMAGE_CHUNK,
            ),
            null,
            false,
        ),
    )

    private fun state(id: String, value: String) = BusEnvelope(
        path = BusPaths.CAMERA_SESSION_STATE,
        id = "$id-$value",
        payload = JSONObject()
            .put("sessionId", id)
            .put("state", value)
            .put("config", JSONObject().put("width", 720).put("height", 1280).put("fps", 10).put("protocolVersion", 1)),
    )

    private fun offer(id: String) = BusEnvelope(
        path = BusPaths.CAMERA_LINK_OFFER,
        id = "$id-offer",
        payload = JSONObject().put("sessionId", id).put("ssid", "DIRECT-test"),
    )

    @Test
    fun `open binds then sends sdk open before queued session messages and duplicates are noops`() {
        val runtime = FakeRuntime()
        val controller = CameraCompanionController(runtime, FakeScheduler(), { principal })
        assertTrue(controller.onRemoteEnvelope(state("s1", "opened")))
        controller.onRemoteEnvelope(offer("s1"))
        controller.onRemoteEnvelope(state("s1", "opened"))
        assertEquals(listOf("lens"), runtime.bound)
        runtime.registered = true
        controller.onRegistered(principal)
        assertEquals(
            listOf(BusPaths.PLUGIN_OPEN, BusPaths.CAMERA_SESSION_STATE, BusPaths.CAMERA_LINK_OFFER),
            runtime.deliveries.map { it.first },
        )
        controller.onRemoteEnvelope(state("s1", "closed"))
        assertEquals(BusPaths.PLUGIN_CLOSE, runtime.deliveries.last().first)
        assertEquals(listOf("lens"), runtime.unbound)
        assertNull(controller.activeSessionId())
    }

    @Test
    fun `binary freeze chunk queues until registration then targets camera consumer`() {
        val runtime = FakeRuntime()
        val controller = CameraCompanionController(runtime, FakeScheduler(), { principal })
        controller.onRemoteEnvelope(state("frozen", "opened"))
        val chunk = byteArrayOf(1, 2, 3)
        assertTrue(
            controller.onRemoteEnvelope(
                BusEnvelope(
                    path = BusPaths.CAMERA_FREEZE_IMAGE_CHUNK,
                    id = "transfer:0",
                    payload = JSONObject().put("sessionId", "frozen"),
                    binary = chunk,
                ),
            ),
        )
        assertTrue(runtime.binaryDeliveries.isEmpty())
        runtime.registered = true
        controller.onRegistered(principal)
        assertEquals(BusPaths.CAMERA_FREEZE_IMAGE_CHUNK, runtime.binaryDeliveries.single().first)
        assertTrue(chunk.contentEquals(runtime.binaryDeliveries.single().second))
    }

    @Test
    fun `close before open suppresses reordered session`() {
        val runtime = FakeRuntime().apply { registered = true }
        val controller = CameraCompanionController(runtime, FakeScheduler(), { principal })
        controller.onRemoteEnvelope(state("late", "closed"))
        controller.onRemoteEnvelope(state("late", "opened"))
        assertTrue(runtime.bound.isEmpty())
        assertTrue(runtime.deliveries.isEmpty())
    }

    @Test
    fun `binder death and revoke mid session teardown idempotently`() {
        val runtime = FakeRuntime().apply { registered = true }
        val controller = CameraCompanionController(runtime, FakeScheduler(), { principal })
        controller.onRemoteEnvelope(state("death", "opened"))
        controller.onBinderDied(principal.grantKey())
        controller.onBinderDied(principal.grantKey())
        assertEquals(1, runtime.deliveries.count { it.first == BusPaths.PLUGIN_CLOSE })
        assertEquals(listOf("lens"), runtime.unbound)

        controller.onRemoteEnvelope(state("revoke", "opened"))
        controller.onRevoked(principal.grantKey())
        assertEquals(2, runtime.deliveries.count { it.first == BusPaths.PLUGIN_CLOSE })
        assertEquals(listOf("lens", "lens"), runtime.unbound)
    }

    @Test
    fun `registration timeout link loss and package removal clean up`() {
        val runtime = FakeRuntime()
        val scheduler = FakeScheduler()
        val controller = CameraCompanionController(runtime, scheduler, { principal })
        controller.onRemoteEnvelope(state("timeout", "opened"))
        scheduler.runAll()
        assertEquals(listOf("lens"), runtime.unbound)
        assertNull(controller.activeSessionId())

        runtime.registered = true
        controller.onRemoteEnvelope(state("link", "opened"))
        controller.onLinkLost()
        controller.onRemoteEnvelope(state("package", "opened"))
        controller.onPackageUnavailable(principal.packageName)
        assertEquals(3, runtime.unbound.size)
        assertNull(controller.activeSessionId())
    }
}
