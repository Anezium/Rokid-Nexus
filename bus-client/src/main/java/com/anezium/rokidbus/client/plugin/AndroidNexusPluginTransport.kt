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
            is BusEvent.Binary -> Unit
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

    override fun close() {
        client.close()
        listener = null
    }
}
