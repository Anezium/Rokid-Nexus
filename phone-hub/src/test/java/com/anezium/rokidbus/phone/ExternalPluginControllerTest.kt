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
        val delays = linkedMapOf<String, Long>()
        override fun schedule(key: String, delayMs: Long, action: () -> Unit) {
            actions[key] = action
            delays[key] = delayMs
        }
        override fun cancel(key: String) {
            actions.remove(key)
            delays.remove(key)
        }
        fun runAll() {
            val pending = actions.values.toList()
            actions.clear()
            delays.clear()
            pending.forEach { it() }
        }
        fun runFirst(prefix: String) {
            val key = actions.keys.first { it.startsWith(prefix) }
            val action = actions.remove(key)!!
            delays.remove(key)
            action()
        }
    }

    private class FakeRuntime : ExternalPluginRuntime {
        var bindResult = true
        var registered = false
        val deliveries = mutableListOf<Pair<String, JSONObject>>()
        val bound = mutableListOf<String>()
        val hidden = mutableListOf<String>()
        val unbound = mutableListOf<String>()
        override fun bind(principal: PhonePluginPrincipal): Boolean {
            bound += principal.descriptor.id
            return bindResult
        }
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
        controller.onRegistered(principal)
        assertEquals(BusPaths.PLUGIN_OPEN, runtime.deliveries.single().first)
        assertEquals("hello", runtime.deliveries.single().second.getString("pluginId"))
    }

    @Test
    fun `plugin activity acknowledges open and cancels watchdog`() {
        val runtime = FakeRuntime().apply { registered = true }
        val scheduler = FakeScheduler()
        val controller = ExternalPluginController(runtime, scheduler)

        controller.open(principal())

        assertEquals(listOf(ExternalPluginController.OPEN_ACK_TIMEOUT_MS), scheduler.delays.values.toList())
        controller.onPluginActivity("hello")
        assertTrue(scheduler.actions.isEmpty())
        assertEquals(listOf("hello"), runtime.bound)
        assertEquals(1, runtime.deliveries.count { it.first == BusPaths.PLUGIN_OPEN })
    }

    @Test
    fun `registration activity also acknowledges an outstanding open`() {
        val runtime = FakeRuntime().apply { registered = true }
        val scheduler = FakeScheduler()
        val controller = ExternalPluginController(runtime, scheduler)
        val principal = principal()

        controller.open(principal)
        controller.onRegistered(principal)

        assertTrue(scheduler.actions.isEmpty())
        assertEquals(1, runtime.deliveries.count { it.first == BusPaths.PLUGIN_OPEN })
    }

    @Test
    fun `open ack timeout rebinds once and redelivers`() {
        val runtime = FakeRuntime().apply { registered = true }
        val scheduler = FakeScheduler()
        val logs = mutableListOf<String>()
        val controller = ExternalPluginController(runtime, scheduler, logger = logs::add)

        controller.open(principal())
        scheduler.runFirst("open-ack:")

        assertEquals(listOf("hello", "hello"), runtime.bound)
        assertEquals(listOf("hello"), runtime.unbound)
        assertEquals(2, runtime.deliveries.count { it.first == BusPaths.PLUGIN_OPEN })
        assertEquals(listOf("external plugin open unacknowledged plugin=hello; rebinding"), logs)
    }

    @Test
    fun `second open ack timeout gives up cleanly without another rebind`() {
        val runtime = FakeRuntime().apply { registered = true }
        val scheduler = FakeScheduler()
        val logs = mutableListOf<String>()
        val controller = ExternalPluginController(runtime, scheduler, logger = logs::add)

        controller.open(principal())
        scheduler.runFirst("open-ack:")
        scheduler.runFirst("open-ack:")

        assertEquals(listOf("hello", "hello"), runtime.bound)
        assertEquals(listOf("hello", "hello"), runtime.unbound)
        assertEquals(listOf("hello"), runtime.hidden)
        assertEquals(null, controller.activeId())
        assertEquals("external plugin open failed plugin=hello", logs.last())
        assertTrue(scheduler.actions.isEmpty())
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
    fun `idle show adopts the sender as foreground and opens it`() {
        val runtime = FakeRuntime().apply { registered = true }
        val controller = ExternalPluginController(runtime, FakeScheduler())
        val principal = principal()
        assertTrue(controller.adopt(principal))
        assertEquals(BusPaths.PLUGIN_OPEN, runtime.deliveries.last().first)
        assertTrue(controller.input("hello", "main", 22, 0))

        assertFalse(controller.adopt(principal("other")))
        assertTrue(controller.adopt(principal))
        assertEquals(1, runtime.deliveries.count { it.first == BusPaths.PLUGIN_OPEN })
    }

    @Test
    fun `adopted plugin uses the same open ack recovery`() {
        val runtime = FakeRuntime().apply { registered = true }
        val scheduler = FakeScheduler()
        val controller = ExternalPluginController(runtime, scheduler)
        val principal = principal()

        assertTrue(controller.adopt(principal))
        scheduler.runFirst("open-ack:")

        assertEquals(listOf("hello"), runtime.bound)
        assertEquals(listOf("hello"), runtime.unbound)
        assertEquals(2, runtime.deliveries.count { it.first == BusPaths.PLUGIN_OPEN })
        controller.onPluginActivity("hello")
        assertTrue(scheduler.actions.isEmpty())
    }

    @Test
    fun `unregistered plugin cannot adopt`() {
        val runtime = FakeRuntime()
        val controller = ExternalPluginController(runtime, FakeScheduler())
        assertFalse(controller.adopt(principal()))
        assertTrue(runtime.deliveries.isEmpty())
    }

    @Test
    fun `self hide closes the active plugin and allows reopen`() {
        val runtime = FakeRuntime().apply { registered = true }
        val controller = ExternalPluginController(runtime, FakeScheduler())
        val principal = principal()
        controller.open(principal)
        assertEquals(listOf(BusPaths.PLUGIN_OPEN), runtime.deliveries.map { it.first })
        assertEquals(listOf("hello"), runtime.bound)

        controller.onPluginSelfHid("other")
        assertEquals(1, runtime.deliveries.size)

        controller.onPluginSelfHid("hello")
        assertEquals(BusPaths.PLUGIN_CLOSE, runtime.deliveries.last().first)
        assertEquals(listOf("hello"), runtime.unbound)
        assertFalse(controller.input("hello", "main", 22, 0))

        controller.open(principal)
        assertEquals(listOf("hello", "hello"), runtime.bound)
        assertEquals(BusPaths.PLUGIN_OPEN, runtime.deliveries.last().first)
        assertTrue(controller.input("hello", "main", 22, 0))
    }

    @Test
    fun `bind failure lets registry fall back`() {
        val runtime = FakeRuntime().apply { bindResult = false }
        val controller = ExternalPluginController(runtime, FakeScheduler())
        assertFalse(controller.open(principal()))
    }

    @Test
    fun `approved registration offers principal even when phone launched plugin first`() {
        val offered = mutableListOf<String>()
        val controller = ExternalPluginController(
            runtime = FakeRuntime().apply { registered = true },
            scheduler = FakeScheduler(),
            onRegisteredPrincipal = { offered += it.descriptor.id },
        )
        controller.onRegistered(principal())
        assertEquals(listOf("hello"), offered)
    }

    @Test
    fun `timeouts rebinds and open failures flow into journal`() {
        val journal = PluginBusJournal()

        val bindFailure = ExternalPluginController(
            runtime = FakeRuntime().apply { bindResult = false },
            scheduler = FakeScheduler(),
            journal = journal,
        )
        assertFalse(bindFailure.open(principal("bind-failure")))

        val registrationScheduler = FakeScheduler()
        val registrationTimeout = ExternalPluginController(
            runtime = FakeRuntime(),
            scheduler = registrationScheduler,
            journal = journal,
        )
        registrationTimeout.open(principal("registration-timeout"))
        registrationScheduler.runFirst("registration:")

        val ackScheduler = FakeScheduler()
        val ackTimeout = ExternalPluginController(
            runtime = FakeRuntime().apply { registered = true },
            scheduler = ackScheduler,
            journal = journal,
        )
        ackTimeout.open(principal("ack-timeout"))
        ackScheduler.runFirst("open-ack:")

        val events = journal.snapshot()
        assertTrue(events.any { it.pluginId == "bind-failure" && it.reason == "BIND_FAILED" && it.verdict == PluginBusJournal.Verdict.REJECTED })
        assertTrue(events.any { it.pluginId == "registration-timeout" && it.reason == "REGISTRATION_TIMEOUT" && it.category == PluginBusJournal.Category.REGISTRATION })
        assertTrue(events.any { it.pluginId == "ack-timeout" && it.reason == "OPEN_ACK_TIMEOUT" && it.verdict == PluginBusJournal.Verdict.REJECTED })
        assertTrue(events.any { it.pluginId == "ack-timeout" && it.reason == "REBIND_ATTEMPT" && it.verdict == PluginBusJournal.Verdict.OK })
    }
}
