package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusPluginClientTest {
    private class FakeTransport : NexusPluginTransport {
        lateinit var listener: NexusPluginTransport.Listener
        var connected = false
        var closeCount = 0
        val sends = mutableListOf<Pair<String, JSONObject>>()
        override fun connect(listener: NexusPluginTransport.Listener) {
            this.listener = listener
            connected = true
        }
        override fun send(path: String, id: String, payload: JSONObject): Boolean {
            sends += path to JSONObject(payload.toString())
            return true
        }
        override fun sendBinary(path: String, id: String, payload: JSONObject, data: ByteArray) = true
        override fun capabilities(): Int = 0
        override fun close() { closeCount += 1 }
    }

    private class RecordingCallbacks : NexusPluginCallbacks {
        val events = mutableListOf<String>()
        override fun onOpen() { events += "open" }
        override fun onClose() { events += "close" }
        override fun onInput(event: NexusInputEvent) { events += "input:${event.keyCode}" }
        override fun onLinkState(state: Int) { events += "link:$state" }
        override fun onRegistrationState(result: Int) { events += "registration:$result" }
        override fun onMessage(path: String, id: String, payload: JSONObject) { events += "message:$path" }
    }

    private fun fixture(): Triple<NexusPluginClient, FakeTransport, RecordingCallbacks> {
        val transport = FakeTransport()
        val callbacks = RecordingCallbacks()
        val client = NexusPluginClient("hello", callbacks, transport)
        client.connect()
        return Triple(client, transport, callbacks)
    }

    private fun payload() = JSONObject().put("pluginId", "hello")

    @Test
    fun `cold start connects without a static factory`() {
        val (client, transport, _) = fixture()
        assertTrue(transport.connected)
        client.close()
    }

    @Test
    fun `approved pending and denied states gate sends`() {
        val (client, transport, callbacks) = fixture()
        transport.listener.onRegistrationState(PluginRegistrationResult.PENDING_USER_APPROVAL)
        assertFalse(client.send("/surface/show", "1", JSONObject()))
        transport.listener.onRegistrationState(PluginRegistrationResult.APPROVED)
        assertTrue(client.send("/surface/show", "2", JSONObject()))
        transport.listener.onRegistrationState(PluginRegistrationResult.DENIED)
        assertFalse(client.send("/surface/show", "3", JSONObject()))
        assertEquals(1, transport.sends.size)
        assertEquals(
            listOf("registration:1", "registration:0", "registration:2"),
            callbacks.events,
        )
    }

    @Test
    fun `duplicate lifecycle events are idempotent and ordered`() {
        val (client, transport, callbacks) = fixture()
        transport.listener.onRegistrationState(PluginRegistrationResult.APPROVED)
        transport.listener.onMessage(BusPaths.PLUGIN_OPEN, "open-1", payload())
        transport.listener.onMessage(BusPaths.PLUGIN_OPEN, "open-1", payload())
        transport.listener.onMessage(BusPaths.PLUGIN_INPUT, "input-1", payload().put("keyCode", 22).put("action", 0))
        transport.listener.onMessage(BusPaths.PLUGIN_CLOSE, "close-1", payload())
        transport.listener.onMessage(BusPaths.PLUGIN_CLOSE, "close-1", payload())
        assertEquals(listOf("registration:0", "open", "input:22", "close"), callbacks.events)
        client.close()
    }

    @Test
    fun `close cleans open lifecycle and transport once`() {
        val (client, transport, callbacks) = fixture()
        transport.listener.onRegistrationState(PluginRegistrationResult.APPROVED)
        transport.listener.onMessage(BusPaths.PLUGIN_OPEN, "open-1", payload())
        client.close()
        client.close()
        assertEquals(listOf("registration:0", "open", "close"), callbacks.events)
        assertEquals(1, transport.closeCount)
    }

    @Test
    fun `approved plugin private messages reach the service callback`() {
        val (client, transport, callbacks) = fixture()
        transport.listener.onRegistrationState(PluginRegistrationResult.APPROVED)
        transport.listener.onMessage("/plugin/hello/migration", "m1", payload().put("future", true))
        assertEquals(
            listOf("registration:0", "message:/plugin/hello/migration"),
            callbacks.events,
        )
        client.close()
    }
}
