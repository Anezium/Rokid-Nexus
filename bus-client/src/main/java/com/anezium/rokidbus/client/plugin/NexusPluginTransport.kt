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

    /**
     * Sends only if the hub can take it now, never queued for a reconnect or replayed. Page
     * traffic uses this. The default refuses, so a transport without such a path carries no page.
     */
    fun sendImmediate(path: String, id: String, payload: JSONObject): Boolean = false
    fun sendBinary(path: String, id: String, payload: JSONObject, data: ByteArray): Boolean
    fun capabilities(): Int

    /**
     * This plugin's own approved capabilities as the hub sees them right now, or null
     * when the hub is too old to answer. It exists so approval and grants can be read
     * as one moment instead of two; the message path remains the source of truth for
     * every later change.
     */
    fun approvedCapabilities(): String?

    fun close()
}
