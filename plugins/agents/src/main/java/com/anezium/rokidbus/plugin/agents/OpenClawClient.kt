package com.anezium.rokidbus.plugin.agents

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class OpenClawClient(
    private val httpClient: OkHttpClient,
    private val store: AgentSessionStore,
    private val configStore: AgentsConfigStore,
    private val scope: CoroutineScope,
    private val versionName: String,
) {
    private var loopJob: Job? = null
    private val sockets = GenerationSlot<WebSocket>()

    @Synchronized
    fun start(config: OpenClawConfig) {
        loopJob?.cancel()
        val advance = sockets.advance()
        advance.previous?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            runConnectionLoop(config, advance.generation)
        }
        loopJob = job
        job.start()
    }

    @Synchronized
    fun stop(clearSessions: Boolean) {
        loopJob?.cancel()
        loopJob = null
        sockets.advance().previous?.cancel()
        store.setConnection(AgentProvider.OPENCLAW, ConnectionState.DISCONNECTED)
        if (clearSessions) store.replaceProvider(AgentProvider.OPENCLAW, emptyList())
    }

    private suspend fun runConnectionLoop(config: OpenClawConfig, generation: Long) {
        if (!sockets.isCurrent(generation)) return
        val identity = loadIdentity(generation)
        val backoff = ReconnectBackoff()
        while (scope.isActive && sockets.isCurrent(generation)) {
            store.setConnection(AgentProvider.OPENCLAW, ConnectionState.CONNECTING)
            val result = try {
                connectOnce(config, identity, generation, backoff)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                ConnectionOutcome.Retry
            } finally {
                sockets.clear(generation)?.cancel()
            }
            if (!sockets.isCurrent(generation)) return
            when (result) {
                is ConnectionOutcome.AuthFailed -> {
                    store.setConnection(
                        AgentProvider.OPENCLAW,
                        ConnectionState.AUTH_FAILED,
                        result.detail,
                    )
                    return
                }
                is ConnectionOutcome.Failed -> {
                    store.setConnection(
                        AgentProvider.OPENCLAW,
                        ConnectionState.DISCONNECTED,
                        result.detail,
                    )
                    return
                }
                is ConnectionOutcome.RetryWithDetail -> {
                    store.setConnection(
                        AgentProvider.OPENCLAW,
                        ConnectionState.DISCONNECTED,
                        result.detail,
                    )
                }
                ConnectionOutcome.Retry -> {
                    store.setConnection(
                        AgentProvider.OPENCLAW,
                        ConnectionState.DISCONNECTED,
                        "Connection lost",
                    )
                }
            }
            delay(backoff.nextDelayMs())
        }
    }

    private fun loadIdentity(generation: Long): OpenClawDeviceIdentity {
        val seed = configStore.openClawSeed() ?: Ed25519.generate().seed.also {
            if (sockets.isCurrent(generation)) configStore.saveOpenClawSeed(it)
        }
        val pair = Ed25519.fromSeed(seed)
        return OpenClawDeviceIdentity(pair.seed, pair.publicKey)
    }

    private suspend fun connectOnce(
        config: OpenClawConfig,
        identity: OpenClawDeviceIdentity,
        generation: Long,
        backoff: ReconnectBackoff,
    ): ConnectionOutcome = coroutineScope {
        val ended = CompletableDeferred<ConnectionOutcome>()
        val approvals = BoundedApprovalStore()
        val connected = AtomicBoolean(false)
        val challengeHandled = AtomicBoolean(false)
        val lock = Any()
        var cachedSessions: List<AgentSession>? = null
        var listInFlight = false
        var listRefreshPending = false
        var malformedListResponses = 0

        fun clearListState() {
            synchronized(lock) {
                listInFlight = false
                listRefreshPending = false
            }
        }

        fun fail(outcome: ConnectionOutcome) {
            clearListState()
            ended.complete(outcome)
        }

        lateinit var deadlines: ConnectionDeadlines
        deadlines = ConnectionDeadlines(this) { detail ->
            if (sockets.isCurrent(generation)) {
                fail(ConnectionOutcome.RetryWithDetail(detail))
            }
        }

        fun isLive(): Boolean = sockets.isCurrent(generation) && !ended.isCompleted

        fun renderCached() {
            if (!isLive()) return
            val sessions = synchronized(lock) { cachedSessions } ?: return
            store.replaceProvider(
                AgentProvider.OPENCLAW,
                OpenClawProtocol.applyApprovals(sessions, approvals.values()),
            )
        }

        fun requestList(webSocket: WebSocket) {
            if (!isLive() || !connected.get()) return
            synchronized(lock) {
                if (listInFlight) {
                    listRefreshPending = true
                    return
                }
                listInFlight = true
            }
            if (!webSocket.send(OpenClawProtocol.sessionsListRequest())) {
                deadlines.clear(DEADLINE_LIST)
                fail(ConnectionOutcome.Retry)
                return
            }
            deadlines.arm(DEADLINE_LIST, "sessions.list timed out")
        }

        val request = Request.Builder()
            .url(webSocketUrl(config.host, config.port))
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isLive()) {
                    webSocket.cancel()
                    return
                }
                deadlines.arm(DEADLINE_CHALLENGE, "Gateway challenge timed out")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isLive()) return
                val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (frame.nullableString("type", MAX_WIRE_TYPE_CHARS)) {
                    "event" -> when (val event = OpenClawProtocol.parseEvent(frame)) {
                        is OpenClawEvent.ConnectChallenge -> {
                            if (connected.get() ||
                                !challengeHandled.compareAndSet(false, true)
                            ) {
                                return
                            }
                            deadlines.clear(DEADLINE_CHALLENGE)
                            val connect = OpenClawProtocol.connectRequest(
                                nonce = event.nonce,
                                token = config.token,
                                identity = identity,
                                versionName = versionName,
                                deviceToken = configStore.openClawDeviceToken(),
                            )
                            if (!webSocket.send(connect)) {
                                fail(ConnectionOutcome.Retry)
                                return
                            }
                            deadlines.arm(DEADLINE_CONNECT, "Gateway hello timed out")
                        }
                        OpenClawEvent.SessionsChanged -> {
                            if (connected.get()) requestList(webSocket)
                        }
                        is OpenClawEvent.ApprovalRequested -> {
                            if (!connected.get()) return
                            approvals.put(event.approval)
                            renderCached()
                            requestList(webSocket)
                        }
                        is OpenClawEvent.ApprovalResolved -> {
                            if (!connected.get()) return
                            approvals.remove(event.id)
                            renderCached()
                            requestList(webSocket)
                        }
                        OpenClawEvent.Unknown -> Unit
                    }
                    "res" -> when (frame.identifierOrNull("id")) {
                        OpenClawProtocol.CONNECT_ID -> {
                            deadlines.clear(DEADLINE_CONNECT)
                            if (frame.booleanOrNull("ok") != true) {
                                val error = frame.optJSONObject("error")
                                when {
                                    OpenClawProtocol.isAuthError(error) -> fail(
                                        ConnectionOutcome.AuthFailed(
                                            "Gateway token rejected",
                                        ),
                                    )
                                    OpenClawProtocol.isPairingError(error) -> fail(
                                        ConnectionOutcome.RetryWithDetail(
                                            "Approve this device in OpenClaw",
                                        ),
                                    )
                                    else -> fail(
                                        ConnectionOutcome.RetryWithDetail(
                                            "Gateway handshake failed",
                                        ),
                                    )
                                }
                                return
                            }
                            val payload = frame.optJSONObject("payload")
                            if (payload?.nullableString("type", MAX_WIRE_TYPE_CHARS) !=
                                "hello-ok" ||
                                payload.intOrNull("protocol") !=
                                OpenClawProtocol.PROTOCOL_VERSION
                            ) {
                                fail(
                                    ConnectionOutcome.RetryWithDetail(
                                        "Unsupported Gateway protocol",
                                    ),
                                )
                                return
                            }
                            payload.optJSONObject("auth")
                                ?.nullableString("deviceToken", MAX_AUTH_TOKEN_CHARS)
                                ?.let(configStore::saveOpenClawDeviceToken)
                            approvals.clear()
                            connected.set(true)
                            backoff.reset()
                            store.setConnection(
                                AgentProvider.OPENCLAW,
                                ConnectionState.CONNECTED,
                            )
                            requestList(webSocket)
                            if (!webSocket.send(OpenClawProtocol.sessionsSubscribeRequest())) {
                                fail(ConnectionOutcome.Retry)
                                return
                            }
                            deadlines.arm(
                                DEADLINE_SUBSCRIBE,
                                "sessions.subscribe timed out",
                            )
                        }
                        OpenClawProtocol.LIST_ID -> {
                            deadlines.clear(DEADLINE_LIST)
                            val refreshAgain = synchronized(lock) {
                                listInFlight = false
                                listRefreshPending.also { listRefreshPending = false }
                            }
                            if (frame.booleanOrNull("ok") != true) {
                                fail(
                                    ConnectionOutcome.RetryWithDetail(
                                        "sessions.list failed",
                                    ),
                                )
                                return
                            }
                            val mapped = frame.optJSONObject("payload")?.let {
                                OpenClawProtocol.mapSessionsOrNull(it)
                            }
                            if (mapped == null) {
                                malformedListResponses += 1
                                if (malformedListResponses > MAX_MALFORMED_LIST_RETRIES) {
                                    fail(
                                        ConnectionOutcome.RetryWithDetail(
                                            "Malformed sessions.list",
                                        ),
                                    )
                                } else {
                                    requestList(webSocket)
                                }
                                return
                            }
                            malformedListResponses = 0
                            approvals.retainSessionKeys(mapped.mapTo(mutableSetOf(), AgentSession::id))
                            synchronized(lock) { cachedSessions = mapped }
                            renderCached()
                            if (refreshAgain && !ended.isCompleted) {
                                requestList(webSocket)
                            }
                        }
                        OpenClawProtocol.SUBSCRIBE_ID -> {
                            deadlines.clear(DEADLINE_SUBSCRIBE)
                            if (frame.booleanOrNull("ok") != true) {
                                fail(
                                    ConnectionOutcome.RetryWithDetail(
                                        "sessions.subscribe failed",
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!sockets.isCurrent(generation)) return
                fail(ConnectionOutcome.Retry)
                webSocket.close(code, "")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!sockets.isCurrent(generation)) return
                fail(
                    if (connected.get()) ConnectionOutcome.Retry
                    else ConnectionOutcome.RetryWithDetail("Gateway closed"),
                )
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (sockets.isCurrent(generation)) fail(ConnectionOutcome.Retry)
            }
        }
        val candidate = httpClient.newWebSocket(request, listener)
        if (!sockets.install(generation, candidate)) {
            candidate.cancel()
            fail(ConnectionOutcome.Retry)
        }
        try {
            ended.await()
        } finally {
            clearListState()
            deadlines.clearAll()
        }
    }

    private companion object {
        const val DEADLINE_CHALLENGE = "challenge"
        const val DEADLINE_CONNECT = "connect"
        const val DEADLINE_LIST = "list"
        const val DEADLINE_SUBSCRIBE = "subscribe"
        const val MAX_MALFORMED_LIST_RETRIES = 1
    }
}

internal class BoundedApprovalStore(
    private val maxSize: Int = MAX_PENDING_APPROVALS,
) {
    private val approvals = LinkedHashMap<String, OpenClawApproval>()

    init {
        require(maxSize > 0)
    }

    @Synchronized
    fun put(approval: OpenClawApproval): Boolean {
        if (approval.id !in approvals && approvals.size >= maxSize) return false
        approvals[approval.id] = approval
        return true
    }

    @Synchronized
    fun remove(id: String): OpenClawApproval? = approvals.remove(id)

    @Synchronized
    fun retainSessionKeys(sessionKeys: Set<String>) {
        approvals.entries.removeAll { it.value.sessionKey !in sessionKeys }
    }

    @Synchronized
    fun values(): List<OpenClawApproval> = approvals.values.toList()

    @Synchronized
    fun clear() {
        approvals.clear()
    }

    @Synchronized
    fun size(): Int = approvals.size
}
