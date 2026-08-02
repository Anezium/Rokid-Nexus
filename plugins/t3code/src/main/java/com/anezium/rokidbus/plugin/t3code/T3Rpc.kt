package com.anezium.rokidbus.plugin.t3code

import org.json.JSONArray
import org.json.JSONObject

internal sealed interface T3ClientFrame {
    data class Request(val id: String, val tag: String, val payload: JSONObject) : T3ClientFrame
    data class Ack(val requestId: String) : T3ClientFrame
    data class Interrupt(val requestId: String) : T3ClientFrame
    data object Ping : T3ClientFrame
}

internal sealed interface T3ServerFrame {
    data class Chunk(val requestId: String, val values: List<Any?>) : T3ServerFrame
    data class Exit(
        val requestId: String,
        val successful: Boolean,
        val value: Any?,
        val error: String?,
    ) : T3ServerFrame
    data object Pong : T3ServerFrame
}

internal data class T3DecodedInbound(
    val frame: T3ServerFrame,
    val acknowledgment: String?,
)

internal object T3Rpc {
    fun request(id: String, tag: String, payload: JSONObject): String = JSONObject()
        .put("_tag", "Request")
        .put("id", id)
        .put("tag", tag)
        .put("payload", payload)
        .put("headers", JSONArray())
        .toString()

    fun ack(requestId: String): String = JSONObject()
        .put("_tag", "Ack")
        .put("requestId", requestId)
        .toString()

    fun interrupt(requestId: String): String = JSONObject()
        .put("_tag", "Interrupt")
        .put("requestId", requestId)
        .put("interruptors", JSONArray())
        .toString()

    fun ping(): String = JSONObject().put("_tag", "Ping").toString()

    fun decodeClient(text: String): T3ClientFrame? {
        val value = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return when (value.optString("_tag")) {
            "Request" -> {
                if (value.optJSONArray("headers") == null) return null
                val id = value.stringOrNull("id") ?: return null
                val tag = value.stringOrNull("tag") ?: return null
                val payload = value.optJSONObject("payload") ?: return null
                T3ClientFrame.Request(id, tag, payload)
            }
            "Ack" -> value.stringOrNull("requestId")?.let(T3ClientFrame::Ack)
            "Interrupt" -> value.stringOrNull("requestId")?.let(T3ClientFrame::Interrupt)
            "Ping" -> T3ClientFrame.Ping
            else -> null
        }
    }

    fun decodeServer(text: String): T3DecodedInbound? {
        val value = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val frame = when (value.optString("_tag")) {
            "Chunk" -> {
                val requestId = value.stringOrNull("requestId") ?: return null
                val values = value.optJSONArray("values") ?: return null
                T3ServerFrame.Chunk(
                    requestId,
                    buildList { for (index in 0 until values.length()) add(values.opt(index)) },
                )
            }
            "Exit" -> {
                val requestId = value.stringOrNull("requestId") ?: return null
                val exit = value.optJSONObject("exit") ?: return null
                val successful = exit.optString("_tag") == "Success"
                T3ServerFrame.Exit(
                    requestId = requestId,
                    successful = successful,
                    value = if (successful) exit.opt("value") else null,
                    error = if (successful) null else failureText(exit.opt("cause")),
                )
            }
            "Pong" -> T3ServerFrame.Pong
            else -> return null
        }
        val acknowledgment = (frame as? T3ServerFrame.Chunk)?.let { ack(it.requestId) }
        return T3DecodedInbound(frame, acknowledgment)
    }

    private fun failureText(cause: Any?): String {
        val messages = mutableListOf<String>()
        collectFailureMessages(cause, messages)
        return messages.firstOrNull()?.takeIf(String::isNotBlank) ?: "T3 Code request failed"
    }

    private fun collectFailureMessages(value: Any?, output: MutableList<String>) {
        when (value) {
            is JSONArray -> for (index in 0 until value.length()) collectFailureMessages(value.opt(index), output)
            is JSONObject -> {
                val direct = listOf("message", "error_description", "defect", "error")
                    .firstNotNullOfOrNull { key ->
                        if (!value.has(key) || value.isNull(key)) null else when (val found = value.opt(key)) {
                            is String -> found
                            is JSONObject -> found.stringOrNull("message") ?: found.toString()
                            else -> found?.toString()
                        }
                    }
                if (!direct.isNullOrBlank()) output += direct
                value.keys().forEach { key ->
                    if (key !in setOf("message", "error_description", "defect", "error")) {
                        collectFailureMessages(value.opt(key), output)
                    }
                }
            }
            is String -> if (value.isNotBlank()) output += value
        }
    }
}
