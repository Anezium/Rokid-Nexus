package com.anezium.rokidbus.plugin.t3code

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal enum class T3ConnectionState {
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    AUTH_EXPIRED,
    ERROR,
    CLOSED,
}

internal sealed interface T3ConnectionEvent {
    data class State(val state: T3ConnectionState, val message: String? = null) : T3ConnectionEvent
    data class Config(val config: T3ServerConfig) : T3ConnectionEvent
    data class Shell(val event: T3ShellEvent) : T3ConnectionEvent
    data class Thread(val threadId: String, val event: T3ThreadStreamEvent) : T3ConnectionEvent
    data class ThreadFailure(val threadId: String, val message: String) : T3ConnectionEvent
    data class DispatchSuccess(val threadId: String) : T3ConnectionEvent
    data class DispatchFailure(val threadId: String, val message: String) : T3ConnectionEvent
}

internal class T3Connection(
    private val listener: (T3ConnectionEvent) -> Unit,
) {
    private sealed interface RequestContext {
        data object Config : RequestContext
        data object Shell : RequestContext
        data class Thread(val threadId: String) : RequestContext
        data class Dispatch(val threadId: String) : RequestContext
    }

    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val http = T3HttpApi(client)
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val requests = mutableMapOf<String, RequestContext>()
    private var active = false
    private var generation = 0L
    private var nextRequestId = 0L
    private var endpoint: T3Endpoint? = null
    private var socket: WebSocket? = null
    private var desiredThreadId: String? = null
    private var reconnectAttempt = 0
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var pingFuture: ScheduledFuture<*>? = null

    fun start(endpoint: T3Endpoint) {
        execute {
            stopInternal(notify = false)
            active = true
            generation += 1
            this.endpoint = endpoint
            reconnectAttempt = 0
            emit(T3ConnectionEvent.State(T3ConnectionState.CONNECTING))
            connect(generation)
        }
    }

    fun stop() {
        http.cancel()
        execute { stopInternal(notify = true) }
    }

    fun destroy() {
        http.cancel()
        execute {
            stopInternal(notify = false)
            client.dispatcher.executorService.shutdown()
        }
        executor.shutdown()
    }

    fun subscribeThread(threadId: String) {
        execute {
            desiredThreadId = threadId
            interruptThreadStreams()
            if (socket != null) sendThreadSubscription(threadId)
        }
    }

    fun refreshThread() {
        execute {
            val threadId = desiredThreadId ?: return@execute
            interruptThreadStreams()
            if (socket != null) sendThreadSubscription(threadId)
        }
    }

    fun leaveThread() {
        execute {
            desiredThreadId = null
            interruptThreadStreams()
        }
    }

    fun dispatch(command: T3StartCommand) {
        execute {
            if (socket == null) {
                emit(T3ConnectionEvent.DispatchFailure(command.threadId, "Not connected to T3 Code"))
                return@execute
            }
            sendRequest(
                tag = "orchestration.dispatchCommand",
                payload = command.payload,
                context = RequestContext.Dispatch(command.threadId),
            )
        }
    }

    private fun connect(expectedGeneration: Long) {
        runCatching { connectOnce(expectedGeneration) }.onFailure { error ->
            scheduleReconnect(error.message ?: "Connect failed", expectedGeneration)
        }
    }

    private fun connectOnce(expectedGeneration: Long) {
        if (!active || generation != expectedGeneration) return
        val target = endpoint ?: return
        when (val ticket = http.ticket(target)) {
            T3TicketResult.Unauthorized -> {
                active = false
                emit(
                    T3ConnectionEvent.State(
                        T3ConnectionState.AUTH_EXPIRED,
                        "Re-pair from phone settings",
                    ),
                )
            }
            is T3TicketResult.Failure -> scheduleReconnect(ticket.message, expectedGeneration)
            is T3TicketResult.Success -> {
                if (!active || generation != expectedGeneration) return
                val request = Request.Builder().url(http.webSocketUrl(target, ticket.ticket)).build()
                socket = client.newWebSocket(request, socketListener(expectedGeneration))
            }
        }
    }

    private fun socketListener(expectedGeneration: Long): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            executeCallback { onSocketOpen(webSocket, expectedGeneration) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            executeCallback { onSocketMessage(webSocket, text, expectedGeneration) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            executeCallback { onSocketDropped(webSocket, expectedGeneration) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            executeCallback { onSocketDropped(webSocket, expectedGeneration) }
        }
    }

    private fun onSocketOpen(webSocket: WebSocket, expectedGeneration: Long) {
        if (!active || generation != expectedGeneration) {
            webSocket.cancel()
            return
        }
        socket?.takeIf { it !== webSocket }?.cancel()
        socket = webSocket
        requests.clear()
        emit(T3ConnectionEvent.State(T3ConnectionState.CONNECTED))
        sendRequest("server.getConfig", JSONObject(), RequestContext.Config)
        sendRequest("orchestration.subscribeShell", JSONObject(), RequestContext.Shell)
        desiredThreadId?.let(::sendThreadSubscription)
        pingFuture?.cancel(false)
        pingFuture = executor.scheduleAtFixedRate(
            { if (active && socket === webSocket) webSocket.send(T3Rpc.ping()) },
            KEEPALIVE_SECONDS,
            KEEPALIVE_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    private fun onSocketMessage(webSocket: WebSocket, text: String, expectedGeneration: Long) {
        if (!active || generation != expectedGeneration || socket !== webSocket) return
        val decoded = T3Rpc.decodeServer(text) ?: return
        decoded.acknowledgment?.let(webSocket::send)
        when (val frame = decoded.frame) {
            is T3ServerFrame.Chunk -> handleChunk(frame)
            is T3ServerFrame.Exit -> handleExit(frame)
            T3ServerFrame.Pong -> Unit
        }
    }

    private fun handleChunk(frame: T3ServerFrame.Chunk) {
        when (val context = requests[frame.requestId]) {
            RequestContext.Shell -> frame.values.forEach { raw ->
                (raw as? JSONObject)?.let(T3Parsers::shellItem)?.let { event ->
                    if (event == T3ShellEvent.Synchronized) reconnectAttempt = 0
                    emit(T3ConnectionEvent.Shell(event))
                }
            }
            is RequestContext.Thread -> frame.values.forEach { raw ->
                (raw as? JSONObject)?.let(T3Parsers::threadItem)?.let { event ->
                    emit(T3ConnectionEvent.Thread(context.threadId, event))
                }
            }
            else -> Unit
        }
    }

    private fun handleExit(frame: T3ServerFrame.Exit) {
        val context = requests.remove(frame.requestId) ?: return
        when (context) {
            RequestContext.Config -> {
                if (!frame.successful) {
                    emit(T3ConnectionEvent.State(T3ConnectionState.ERROR, frame.error))
                    return
                }
                val value = frame.value as? JSONObject
                if (value == null) {
                    emit(T3ConnectionEvent.State(T3ConnectionState.ERROR, "Invalid T3 Code configuration"))
                    return
                }
                runCatching { T3Parsers.config(value) }
                    .onSuccess { emit(T3ConnectionEvent.Config(it)) }
                    .onFailure {
                        emit(T3ConnectionEvent.State(T3ConnectionState.ERROR, "Invalid T3 Code configuration"))
                    }
            }
            is RequestContext.Dispatch -> {
                if (!frame.successful) {
                    emit(
                        T3ConnectionEvent.DispatchFailure(
                            context.threadId,
                            frame.error ?: "T3 Code rejected the new thread",
                        ),
                    )
                    return
                }
                val receipt = frame.value as? JSONObject
                if (receipt?.optString("status") == "rejected") {
                    emit(
                        T3ConnectionEvent.DispatchFailure(
                            context.threadId,
                            receipt.stringOrNull("message")
                                ?: receipt.stringOrNull("reason")
                                ?: "T3 Code rejected the new thread",
                        ),
                    )
                } else {
                    emit(T3ConnectionEvent.DispatchSuccess(context.threadId))
                }
            }
            RequestContext.Shell -> {
                if (active) socket?.cancel()
            }
            is RequestContext.Thread -> {
                if (active && desiredThreadId == context.threadId) {
                    emit(
                        T3ConnectionEvent.ThreadFailure(
                            context.threadId,
                            frame.error ?: "Thread stream ended",
                        ),
                    )
                }
            }
        }
    }

    private fun onSocketDropped(webSocket: WebSocket, expectedGeneration: Long) {
        if (!active || generation != expectedGeneration || socket !== webSocket) return
        socket = null
        requests.clear()
        pingFuture?.cancel(false)
        pingFuture = null
        scheduleReconnect("Connection lost", expectedGeneration)
    }

    private fun scheduleReconnect(message: String, expectedGeneration: Long) {
        if (!active || generation != expectedGeneration) return
        val delay = RECONNECT_SECONDS[reconnectAttempt.coerceAtMost(RECONNECT_SECONDS.lastIndex)]
        reconnectAttempt += 1
        emit(
            T3ConnectionEvent.State(
                T3ConnectionState.RECONNECTING,
                "$message · retrying in ${delay}s",
            ),
        )
        reconnectFuture?.cancel(false)
        reconnectFuture = executor.schedule(
            {
                if (active && generation == expectedGeneration) {
                    emit(T3ConnectionEvent.State(T3ConnectionState.CONNECTING))
                    connect(expectedGeneration)
                }
            },
            delay,
            TimeUnit.SECONDS,
        )
    }

    private fun sendThreadSubscription(threadId: String) {
        sendRequest(
            "orchestration.subscribeThread",
            JSONObject().put("threadId", threadId),
            RequestContext.Thread(threadId),
        )
    }

    private fun interruptThreadStreams() {
        val activeThreadRequests = requests.filterValues { it is RequestContext.Thread }.keys.toList()
        activeThreadRequests.forEach { requestId ->
            socket?.send(T3Rpc.interrupt(requestId))
            requests.remove(requestId)
        }
    }

    private fun sendRequest(tag: String, payload: JSONObject, context: RequestContext): String? {
        val activeSocket = socket ?: return null
        val id = (nextRequestId++).toString()
        if (!activeSocket.send(T3Rpc.request(id, tag, payload))) return null
        requests[id] = context
        return id
    }

    private fun stopInternal(notify: Boolean) {
        active = false
        generation += 1
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        pingFuture?.cancel(false)
        pingFuture = null
        requests.clear()
        desiredThreadId = null
        socket?.cancel()
        socket = null
        endpoint = null
        http.cancel()
        if (notify) emit(T3ConnectionEvent.State(T3ConnectionState.CLOSED))
    }

    private fun emit(event: T3ConnectionEvent) {
        listener(event)
    }

    private fun execute(block: () -> Unit) {
        try {
            executor.execute(block)
        } catch (_: RejectedExecutionException) {
            // The Android service has already been destroyed.
        }
    }

    private fun executeCallback(block: () -> Unit) = execute(block)

    private companion object {
        const val KEEPALIVE_SECONDS = 15L
        val RECONNECT_SECONDS = longArrayOf(1, 2, 5, 10)
    }
}
