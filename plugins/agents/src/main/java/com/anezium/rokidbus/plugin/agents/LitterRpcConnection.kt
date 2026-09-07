package com.anezium.rokidbus.plugin.agents

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

internal class LitterFailure(val visibleMessage: String, val authFailed: Boolean = false) : IOException(visibleMessage)

/** All mutable state and callbacks are confined to the supplied runtime scope. */
internal class LitterRpcConnection(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val onEvent: (JSONObject) -> Unit,
) {
    private val opened = CompletableDeferred<Unit>()
    private val closed = CompletableDeferred<LitterFailure>()
    private val pending = mutableMapOf<String, CompletableDeferred<JSONObject>>()
    private var sequence = 0L
    private var socket: WebSocket? = null
    private var active = true

    suspend fun connect(endpoint: LitterEndpoint) {
        endpoint.validate()?.let { throw LitterFailure(it) }
        val request = Request.Builder().url(endpoint.url).apply {
            if (endpoint.token.isNotEmpty()) header("Authorization", "Bearer ${endpoint.token}")
        }.build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch { if (active) opened.complete(Unit) else webSocket.cancel() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    if (!active) return@launch
                    if (text.length > LitterProtocol.MAX_FRAME_BYTES ||
                        text.toByteArray(Charsets.UTF_8).size > LitterProtocol.MAX_FRAME_BYTES
                    ) {
                        fail(LitterFailure("The server sent a message larger than the supported limit."))
                        return@launch
                    }
                    val frame = runCatching { JSONObject(text) }.getOrNull()
                    if (frame == null) {
                        fail(LitterFailure("The endpoint did not speak the Codex app-server protocol."))
                    } else if (frame.has("method")) {
                        onEvent(frame)
                    } else {
                        val id = frame.opt("id") as? String ?: return@launch
                        val waiter = pending.remove(id) ?: return@launch
                        if (frame.has("error")) {
                            val code = frame.optJSONObject("error")?.optInt("code", 0) ?: 0
                            waiter.completeExceptionally(LitterFailure("The server rejected the request (code $code). Check the server's sign-in and permissions."))
                        } else {
                            val result = frame.optJSONObject("result")
                            if (result != null) waiter.complete(result)
                            else waiter.completeExceptionally(LitterFailure("The server returned an unsupported response."))
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                scope.launch { fail(LitterFailure("Binary app-server messages are unsupported.")) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch {
                    val denied = response?.code in setOf(401, 403)
                    fail(LitterFailure(if (denied) "Server authentication failed. Update its token."
                        else "Connection lost. Reconnecting while Agents is open…", denied))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                scope.launch { fail(LitterFailure("Server disconnected. Reconnecting while Agents is open…")) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { fail(LitterFailure("Server disconnected. Reconnecting while Agents is open…")) }
            }
        })
        withTimeout(15_000L) { opened.await() }
    }

    suspend fun request(method: String, params: JSONObject = JSONObject()): JSONObject {
        if (!active || !opened.isCompleted) throw LitterFailure("Connect to the server first.")
        if (pending.size >= 32) throw LitterFailure("Too many requests are pending. Wait for the server.")
        val id = "nexus-${++sequence}"
        val waiter = CompletableDeferred<JSONObject>()
        pending[id] = waiter
        try {
            if (!send(LitterProtocol.request(id, method, params))) throw LitterFailure("The request was not sent. Check the connection.")
            return withTimeout(30_000L) { waiter.await() }
        } finally {
            pending.remove(id)
        }
    }

    fun send(frame: JSONObject): Boolean = active && socket?.send(frame.toString()) == true
    suspend fun awaitClosed(): LitterFailure = closed.await()

    fun close() = fail(LitterFailure("Agents is closed."))

    private fun fail(error: LitterFailure) {
        if (!active) return
        active = false
        if (!opened.isCompleted) opened.completeExceptionally(error)
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        closed.complete(error)
        socket?.cancel()
        socket = null
    }
}
