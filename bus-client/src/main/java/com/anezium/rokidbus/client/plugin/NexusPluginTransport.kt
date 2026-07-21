package com.anezium.rokidbus.client.plugin

import org.json.JSONObject

interface NexusPluginTransport {
    interface Listener {
        fun onRegistrationState(result: Int)
        fun onLinkState(state: Int)
        fun onGlassesAiButton(active: Boolean)
        fun onMessage(path: String, id: String, payload: JSONObject)
        fun onBinary(path: String, id: String, payload: JSONObject, data: ByteArray)
        fun onError(message: String)
    }

    fun connect(listener: Listener)
    fun send(path: String, id: String, payload: JSONObject): Boolean
    fun sendBinary(path: String, id: String, payload: JSONObject, data: ByteArray): Boolean
    fun capabilities(): Int
    fun close()
}
