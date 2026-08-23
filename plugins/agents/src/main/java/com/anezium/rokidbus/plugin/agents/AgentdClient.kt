package com.anezium.rokidbus.plugin.agents

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
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
import java.util.concurrent.atomic.AtomicBoolean

class AgentdClient(
    private val httpClient: OkHttpClient,
    private val store: AgentSessionStore,
    private val scope: CoroutineScope,
    private val versionName: String,
) {
    private var loopJob: Job? = null
    private val sockets = GenerationSlot<WebSocket>()

    /** Survives reconnects: the daemon forgets, the wearer should not. */
    @Volatile private var openSessionId: String? = null

    fun openDetail(sessionId: String) {
        openSessionId = sessionId
        sockets.current()?.send(AgentdProtocolCodec.detailOpen(sessionId))
    }

    fun closeDetail() {
        openSessionId = null
        sockets.current()?.send(AgentdProtocolCodec.DETAIL_CLOSE)
    }

    fun decideApproval(requestId: String, decision: ApprovalDecision) {
        sockets.current()?.send(AgentdProtocolCodec.approvalDecision(requestId, decision))
    }

    fun requestFolders(requestId: String, path: String?) {
        sockets.current()?.send(AgentdProtocolCodec.fsList(requestId, path))
    }

    fun requestThreadStart(requestId: String, provider: AgentProvider, path: String, prompt: String) {
        sockets.current()?.send(AgentdProtocolCodec.threadStart(requestId, provider, path, prompt))
    }

    @Synchronized
    fun start(config: AgentdConfig) {
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
        store.setConnection(AgentProvider.CLAUDE, ConnectionState.DISCONNECTED)
        store.clearApprovals(AgentProvider.AGENTD_PROVIDERS)
        if (clearSessions) {
            store.replaceLinkSessions(AgentProvider.AGENTD_PROVIDERS, emptyList())
        }
    }

    private suspend fun runConnectionLoop(config: AgentdConfig, generation: Long) {
        val backoff = ReconnectBackoff()
        while (scope.isActive && sockets.isCurrent(generation)) {
            store.setConnection(AgentProvider.CLAUDE, ConnectionState.CONNECTING)
            val result = try {
                connectOnce(config, generation, backoff)
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
                        AgentProvider.CLAUDE,
                        ConnectionState.AUTH_FAILED,
                        result.detail,
                    )
                    return
                }
                is ConnectionOutcome.Failed -> {
                    store.setConnection(
                        AgentProvider.CLAUDE,
                        ConnectionState.DISCONNECTED,
                        result.detail,
                    )
                    return
                }
                is ConnectionOutcome.RetryWithDetail -> {
                    store.setConnection(
                        AgentProvider.CLAUDE,
                        ConnectionState.DISCONNECTED,
                        result.detail,
                    )
                }
                ConnectionOutcome.Retry -> {
                    store.setConnection(
                        AgentProvider.CLAUDE,
                        ConnectionState.DISCONNECTED,
                        "Connection lost",
                    )
                }
            }
            delay(backoff.nextDelayMs())
        }
    }

    private suspend fun connectOnce(
        config: AgentdConfig,
        generation: Long,
        backoff: ReconnectBackoff,
    ): ConnectionOutcome = coroutineScope {
        val codec = AgentdProtocolCodec().also(AgentdProtocolCodec::reset)
        val ended = CompletableDeferred<ConnectionOutcome>()
        val connected = AtomicBoolean(false)
        lateinit var deadlines: ConnectionDeadlines

        fun isLive(): Boolean = sockets.isCurrent(generation) && !ended.isCompleted

        deadlines = ConnectionDeadlines(this) { detail ->
            if (sockets.isCurrent(generation)) {
                ended.complete(ConnectionOutcome.RetryWithDetail(detail))
            }
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
                if (!webSocket.send(codec.hello(config.token, versionName))) {
                    ended.complete(ConnectionOutcome.Retry)
                    return
                }
                deadlines.arm(DEADLINE_HELLO, "Daemon hello timed out")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isLive()) return
                when (val action = codec.parse(text)) {
                    is AgentdAction.HelloAcknowledged -> {
                        if (!connected.compareAndSet(false, true)) return
                        deadlines.clear(DEADLINE_HELLO)
                        deadlines.arm(DEADLINE_SNAPSHOT, "Daemon snapshot timed out")
                        backoff.reset()
                        store.setConnection(
                            AgentProvider.CLAUDE,
                            ConnectionState.CONNECTED,
                            action.machineName,
                        )
                    }
                    is AgentdAction.Snapshot -> {
                        if (!connected.get()) return
                        deadlines.clear(DEADLINE_SNAPSHOT)
                        store.replaceLinkSessions(
                            AgentProvider.AGENTD_PROVIDERS,
                            action.sessions,
                        )
                        // A fresh connection knows nothing of the open conversation.
                        openSessionId?.let { webSocket.send(AgentdProtocolCodec.detailOpen(it)) }
                    }
                    is AgentdAction.Upsert -> {
                        if (connected.get()) store.upsert(action.session)
                    }
                    is AgentdAction.Removed -> {
                        if (connected.get()) {
                            store.remove(action.provider, action.sessionId)
                        }
                    }
                    is AgentdAction.Detail -> {
                        if (connected.get()) {
                            store.setConversation(
                                action.provider,
                                action.sessionId,
                                action.messages,
                            )
                        }
                    }
                    is AgentdAction.DetailAppend -> {
                        if (connected.get()) {
                            store.appendConversation(
                                action.provider,
                                action.sessionId,
                                action.message,
                            )
                        }
                    }
                    is AgentdAction.ApprovalRequested -> {
                        if (connected.get()) store.upsertApproval(action.approval)
                    }
                    is AgentdAction.ApprovalResolved -> {
                        if (connected.get()) store.resolveApproval(action.requestId)
                    }
                    is AgentdAction.FolderListing -> {
                        if (connected.get()) store.setFsListing(action.listing)
                    }
                    is AgentdAction.ThreadStarted -> {
                        if (connected.get()) store.setThreadStart(action.result)
                    }
                    is AgentdAction.Send -> {
                        if (action.text == AgentdProtocolCodec.REFRESH) {
                            if (!connected.get()) return
                            deadlines.arm(DEADLINE_SNAPSHOT, "Daemon refresh timed out")
                        }
                        if (!webSocket.send(action.text)) {
                            ended.complete(ConnectionOutcome.Retry)
                        }
                    }
                    // Only the LAN link sees a daemon hello: here we are the client.
                    is AgentdAction.Hello, AgentdAction.Ignore -> Unit
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!sockets.isCurrent(generation)) return
                if (code == BAD_TOKEN_CLOSE_CODE) {
                    ended.complete(ConnectionOutcome.AuthFailed("Pairing invalid"))
                } else {
                    ended.complete(ConnectionOutcome.Retry)
                }
                webSocket.close(code, "")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!sockets.isCurrent(generation)) return
                if (code == BAD_TOKEN_CLOSE_CODE) {
                    ended.complete(ConnectionOutcome.AuthFailed("Pairing invalid"))
                } else {
                    ended.complete(
                        if (connected.get()) ConnectionOutcome.Retry
                        else ConnectionOutcome.RetryWithDetail("Connection closed"),
                    )
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (sockets.isCurrent(generation)) {
                    ended.complete(ConnectionOutcome.Retry)
                }
            }
        }
        val candidate = httpClient.newWebSocket(request, listener)
        if (!sockets.install(generation, candidate)) {
            candidate.cancel()
            ended.complete(ConnectionOutcome.Retry)
        }
        try {
            ended.await()
        } finally {
            deadlines.clearAll()
        }
    }

    private companion object {
        const val BAD_TOKEN_CLOSE_CODE = 4401
        const val DEADLINE_HELLO = "hello"
        const val DEADLINE_SNAPSHOT = "snapshot"
    }
}
