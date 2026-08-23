package com.anezium.rokidbus.plugin.agents

import com.anezium.rokidbus.plugin.agents.alleycat.AlleycatException
import com.anezium.rokidbus.plugin.agents.alleycat.JsonPipe
import com.anezium.rokidbus.plugin.agents.alleycat.QueueJsonPipe
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * One websocket text frame = one JSON-RPC object. Binary frames are rejected.
 * Failures become a clear [AlleycatException] on the next read; the pipe never
 * waits forever after the socket is gone.
 *
 * Built on [QueueJsonPipe] plus the reconnect helpers in [WebSocketSupport].
 */
internal class WebSocketJsonPipe(
    private val webSocket: WebSocket,
    private val queue: QueueJsonPipe,
) : JsonPipe {
    override fun sendJson(message: JSONObject) {
        if (!webSocket.send(message.toString())) {
            throw AlleycatException("websocket send failed")
        }
    }

    override fun receiveJson(): JSONObject = queue.receiveJson()

    override fun receiveJson(timeoutMs: Long): JSONObject? = queue.receiveJson(timeoutMs)

    override fun close() {
        queue.close()
        webSocket.cancel()
    }

    companion object {
        fun connect(
            httpClient: OkHttpClient,
            url: String,
            openTimeoutMs: Long = NETWORK_STEP_TIMEOUT_MS,
        ): WebSocketJsonPipe {
            val queue = QueueJsonPipe()
            val opened = CountDownLatch(1)
            val socket = AtomicReference<WebSocket>()
            val openError = AtomicReference<Throwable>()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    socket.set(webSocket)
                    opened.countDown()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        queue.enqueueJson(text)
                    } catch (e: AlleycatException) {
                        queue.fail(e)
                    } catch (e: JSONException) {
                        queue.fail(AlleycatException("websocket text is not JSON", e))
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    queue.fail(AlleycatException("websocket binary frames are not JSON-RPC"))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    queue.fail(closeError(code))
                    webSocket.close(code, "")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    queue.fail(closeError(code))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val error = when (response?.code) {
                        401, 403 -> AlleycatException("app-server rejected the connection")
                        else -> AlleycatException("connection failed")
                    }
                    openError.compareAndSet(null, error)
                    queue.fail(error)
                    opened.countDown()
                }
            }
            val candidate = try {
                httpClient.newWebSocket(
                    Request.Builder().url(url).build(),
                    listener,
                )
            } catch (_: IllegalArgumentException) {
                throw AlleycatException("address is not a valid websocket URL")
            }
            if (!opened.await(openTimeoutMs, TimeUnit.MILLISECONDS)) {
                candidate.cancel()
                throw AlleycatException("websocket open timed out")
            }
            openError.get()?.let { error ->
                candidate.cancel()
                throw (error as? AlleycatException) ?: AlleycatException("connection failed", error)
            }
            val live = socket.get() ?: run {
                candidate.cancel()
                throw AlleycatException("websocket open failed")
            }
            return WebSocketJsonPipe(live, queue)
        }

        private fun closeError(code: Int): AlleycatException = when (code) {
            4001, 4401, 4403 -> AlleycatException("app-server rejected the connection")
            else -> AlleycatException("websocket closed")
        }
    }
}
