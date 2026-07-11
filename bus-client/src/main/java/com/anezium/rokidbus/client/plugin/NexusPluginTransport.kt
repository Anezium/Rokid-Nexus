package com.anezium.rokidbus.client.plugin

import org.json.JSONObject

interface NexusPluginTransport {
    interface Listener {
        fun onRegistrationState(result: Int)
        fun onLinkState(state: Int)
        fun onMessage(path: String, id: String, payload: JSONObject)
        fun onError(message: String)
    }

    fun connect(listener: Listener)
    fun send(path: String, id: String, payload: JSONObject): Boolean
    fun close()
}
