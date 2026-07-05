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
    fun subscribe(pathPrefix: String, handler: (path: String, id: String, payload: JSONObject) -> Unit): NexusSubscription
    fun log(message: String)
}

interface NexusPlugin {
    val id: String
    val displayName: String

    fun onRegister(host: NexusPluginHost)
    fun onOpen()
    fun onClose()
    fun onInput(event: NexusInputEvent)
}
