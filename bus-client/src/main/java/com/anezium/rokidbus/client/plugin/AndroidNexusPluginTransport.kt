package com.anezium.rokidbus.client.plugin

import android.content.Context
import com.anezium.rokidbus.client.BusClient
import com.anezium.rokidbus.client.BusEvent
import com.anezium.rokidbus.client.HubTarget
import org.json.JSONObject

internal class AndroidNexusPluginTransport(
    context: Context,
    pluginId: String,
    hubTarget: HubTarget,
) : NexusPluginTransport {
    private var listener: NexusPluginTransport.Listener? = null
    private val client = BusClient(
        context = context.applicationContext,
        clientId = pluginId,
        pathPrefixes = emptyList(),
        hubTarget = hubTarget,
        pluginId = pluginId,
        pluginRegistrationListener = { result -> listener?.onRegistrationState(result) },
    ) { event ->
        when (event) {
            is BusEvent.LinkState -> listener?.onLinkState(event.state)
            is BusEvent.Message -> listener?.onMessage(event.path, event.id, event.payload)
            is BusEvent.Binary -> listener?.onBinary(event.path, event.id, event.meta, event.data)
            is BusEvent.Error -> listener?.onError(event.message)
        }
    }

    override fun connect(listener: NexusPluginTransport.Listener) {
        this.listener = listener
        client.connect()
    }

    override fun send(path: String, id: String, payload: JSONObject): Boolean {
        client.send(path, id, payload)
        return true
    }

    override fun sendBinary(path: String, id: String, payload: JSONObject, data: ByteArray): Boolean {
        return client.trySendBinary(path, id, payload, data)
    }

    override fun capabilities(): Int = client.capabilities()

    override fun close() {
        client.close()
        listener = null
    }
}
