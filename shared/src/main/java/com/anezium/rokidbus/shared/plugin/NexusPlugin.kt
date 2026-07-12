package com.anezium.rokidbus.shared.plugin

import android.content.Context
import org.json.JSONObject

data class NexusPluginEntry(
    val id: String,
    val displayName: String,
)

data class NexusInputEvent(
    val surfaceId: String,
    val keyCode: Int,
    val action: Int,
)

fun interface NexusSubscription {
    fun close()
}

interface NexusPluginHost {
    val context: Context

    fun send(path: String, payload: JSONObject)
    fun send(path: String, id: String, payload: JSONObject) {
        send(path, payload)
    }
    fun sendBinary(path: String, payload: JSONObject, data: ByteArray)
    fun sendBinary(path: String, id: String, payload: JSONObject, data: ByteArray) {
        sendBinary(path, payload, data)
    }
    fun supportsImageSurface(): Boolean
    fun subscribe(pathPrefix: String, handler: (path: String, id: String, payload: JSONObject) -> Unit): NexusSubscription
    fun post(action: () -> Unit)
    fun log(message: String)
}

interface NexusPlugin {
    val id: String
    val displayName: String
    val launchable: Boolean
        get() = true
    val handlesBack: Boolean
        get() = false

    fun onRegister(host: NexusPluginHost)
    fun onOpen()
    fun onClose()
    fun onInput(event: NexusInputEvent)
}
