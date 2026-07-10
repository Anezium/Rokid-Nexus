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

class ExternalPluginControllerTest {
    private class FakeScheduler : ExternalPluginScheduler {
        val actions = linkedMapOf<String, () -> Unit>()
        override fun schedule(key: String, delayMs: Long, action: () -> Unit) { actions[key] = action }
        override fun cancel(key: String) { actions.remove(key) }
        fun runAll() { actions.values.toList().also { actions.clear() }.forEach { it() } }
    }

    private class FakeRuntime : ExternalPluginRuntime {
        var bindResult = true
        var registered = false
        val deliveries = mutableListOf<Pair<String, JSONObject>>()
        val hidden = mutableListOf<String>()
        val unbound = mutableListOf<String>()
        override fun bind(principal: PhonePluginPrincipal): Boolean = bindResult
        override fun isRegistered(principal: PhonePluginPrincipal): Boolean = registered
        override fun deliver(principal: PhonePluginPrincipal, path: String, id: String, payload: JSONObject): Boolean {
            deliveries += path to JSONObject(payload.toString())
            return registered
        }
        override fun hideOwnedSurfaces(pluginId: String) { hidden += pluginId }
        override fun unbind(principal: PhonePluginPrincipal) { unbound += principal.descriptor.id }
    }

    private fun principal(id: String = "hello") = PhonePluginPrincipal(
        packageName = "dev.example.$id",
        serviceComponent = ComponentName("dev.example.$id", "dev.example.$id.Service"),
        uid = 10,
        signingDigestSha256 = "digest-$id",
        descriptor = PluginDescriptor(
            id, id, 3, setOf(PluginCapability.SURFACES), listOf("/plugin/$id", "/system/plugin"), null, true,
        ),
    )

    @Test
    fun `external open cold-wakes then delivers to registered principal`() {
        val runtime = FakeRuntime()
        val scheduler = FakeScheduler()
        val controller = ExternalPluginController(runtime, scheduler)
        val principal = principal()
        assertTrue(controller.open(principal))
        assertTrue(runtime.deliveries.isEmpty())
        runtime.registered = true
        controller.onRegistered(principal.grantKey())
        assertEquals(BusPaths.PLUGIN_OPEN, runtime.deliveries.single().first)
        assertEquals("hello", runtime.deliveries.single().second.getString("pluginId"))
    }

    @Test
    fun `registration timeout hides and unbinds`() {
        val runtime = FakeRuntime()
        val scheduler = FakeScheduler()
        val controller = ExternalPluginController(runtime, scheduler)
        controller.open(principal())
        scheduler.runAll()
        assertEquals(listOf("hello"), runtime.hidden)
        assertEquals(listOf("hello"), runtime.unbound)
    }

    @Test
    fun `input is delivered only to active owner`() {
        val runtime = FakeRuntime().apply { registered = true }
        val controller = ExternalPluginController(runtime, FakeScheduler())
        val principal = principal()
        controller.open(principal)
        assertFalse(controller.input("other", "main", 22, 0))
        assertTrue(controller.input("hello", "main", 22, 0))
        assertEquals(BusPaths.PLUGIN_INPUT, runtime.deliveries.last().first)
    }

    @Test
    fun `revocation and binder death close owned state`() {
        val runtime = FakeRuntime().apply { registered = true }
        val controller = ExternalPluginController(runtime, FakeScheduler())
        val principal = principal()
        controller.open(principal)
        controller.onRevoked(principal.grantKey())
        assertTrue(runtime.deliveries.any { it.first == BusPaths.PLUGIN_CLOSE })
        assertEquals(listOf("hello"), runtime.hidden)

        runtime.hidden.clear()
        controller.open(principal)
        controller.onBinderDied(principal.grantKey())
        assertEquals(listOf("hello"), runtime.hidden)
    }

    @Test
    fun `uninstall and pending binder death clear owned state`() {
        val runtime = FakeRuntime()
        val scheduler = FakeScheduler()
        val controller = ExternalPluginController(runtime, scheduler)
        val principal = principal()
        controller.open(principal)
        controller.onBinderDied(principal.grantKey())
        assertEquals(listOf("hello"), runtime.hidden)
        assertEquals(listOf("hello"), runtime.unbound)

        runtime.registered = true
        controller.open(principal)
        runtime.hidden.clear()
        runtime.unbound.clear()
        controller.onPackageUnavailable(principal.packageName)
        assertTrue(runtime.deliveries.any { it.first == BusPaths.PLUGIN_CLOSE })
        assertEquals(listOf("hello"), runtime.hidden)
        assertEquals(listOf("hello"), runtime.unbound)
    }

    @Test
    fun `bind failure lets registry fall back`() {
        val runtime = FakeRuntime().apply { bindResult = false }
        val controller = ExternalPluginController(runtime, FakeScheduler())
        assertFalse(controller.open(principal()))
    }
}
