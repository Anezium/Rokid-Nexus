package com.anezium.rokidbus.plugin.agents

import com.anezium.rokidbus.plugin.agents.alleycat.AlleycatException
import com.anezium.rokidbus.plugin.agents.alleycat.ApprovalVerdict
import com.anezium.rokidbus.plugin.agents.alleycat.JsonPipe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference

internal sealed interface CodexCommand {
    data class OpenDetail(val sessionId: String) : CodexCommand
    data object CloseDetail : CodexCommand
    data class Decide(val requestId: String, val verdict: ApprovalVerdict) : CodexCommand
    data class StartThread(
        val requestId: String,
        val prompt: String,
        val cwd: String?,
    ) : CodexCommand
}

/**
 * One direct `ws(s)://` Codex app-server. Skips the Alleycat handshake and
 * speaks JSON-RPC on the socket from the first frame.
 */
class CodexDirectClient(
    private val store: AgentSessionStore,
    private val scope: CoroutineScope,
    private val openPipe: (DirectComputer) -> JsonPipe,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    constructor(
        httpClient: OkHttpClient,
        store: AgentSessionStore,
        scope: CoroutineScope,
    ) : this(
        store = store,
        scope = scope,
        openPipe = { computer -> WebSocketJsonPipe.connect(httpClient, computer.url) },
    )

    private var loopJob: Job? = null
    private val generation = java.util.concurrent.atomic.AtomicLong(0L)
    private val commands = LinkedBlockingQueue<CodexCommand>()
    private val livePipe = AtomicReference<JsonPipe?>()
    @Volatile var computerId: String? = null
        private set
    @Volatile var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        private set

    @Synchronized
    fun start(computer: DirectComputer) {
        loopJob?.cancel()
        commands.clear()
        livePipe.getAndSet(null)?.close()
        computerId = computer.computerId
        val gen = generation.incrementAndGet()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            runConnectionLoop(computer, gen)
        }
        loopJob = job
        job.start()
    }

    @Synchronized
    fun stop(clearSessions: Boolean) {
        loopJob?.cancel()
        loopJob = null
        generation.incrementAndGet()
        livePipe.getAndSet(null)?.close()
        connectionState = ConnectionState.DISCONNECTED
        if (clearSessions) {
            val id = computerId
            store.setConnection(AgentProvider.CODEX, ConnectionState.DISCONNECTED)
            store.clearApprovals(setOf(AgentProvider.CODEX))
            if (id != null) {
                store.replaceMachineSessions(id, AgentProvider.CODEX, emptyList())
            }
        }
        computerId = null
    }

    fun openDetail(sessionId: String) {
        commands.offer(CodexCommand.OpenDetail(sessionId))
    }

    fun closeDetail() {
        commands.offer(CodexCommand.CloseDetail)
    }

    fun decideApproval(requestId: String, verdict: ApprovalVerdict) {
        commands.offer(CodexCommand.Decide(requestId, verdict))
    }

    fun requestThreadStart(requestId: String, prompt: String, cwd: String?) {
        commands.offer(CodexCommand.StartThread(requestId, prompt, cwd))
    }

    private suspend fun runConnectionLoop(computer: DirectComputer, gen: Long) {
        val backoff = ReconnectBackoff()
        val bridge = CodexSessionBridge(store, computer, nowMs)
        while (scope.isActive && generation.get() == gen) {
            connectionState = ConnectionState.CONNECTING
            bridge.onConnecting()
            val result = try {
                connectOnce(computer, gen, backoff, bridge)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: AlleycatException) {
                ConnectionOutcome.RetryWithDetail(e.message ?: "connection failed")
            } catch (_: Throwable) {
                ConnectionOutcome.Retry
            } finally {
                livePipe.getAndSet(null)?.close()
            }
            if (generation.get() != gen) return
            when (result) {
                is ConnectionOutcome.AuthFailed -> {
                    connectionState = ConnectionState.AUTH_FAILED
                    bridge.onFailed(ConnectionState.AUTH_FAILED, result.detail)
                    return
                }
                is ConnectionOutcome.Failed -> {
                    connectionState = ConnectionState.DISCONNECTED
                    bridge.onFailed(ConnectionState.DISCONNECTED, result.detail)
                    return
                }
                is ConnectionOutcome.RetryWithDetail -> {
                    connectionState = ConnectionState.DISCONNECTED
                    bridge.onDisconnected(result.detail)
                }
                ConnectionOutcome.Retry -> {
                    connectionState = ConnectionState.DISCONNECTED
                    bridge.onDisconnected("Connection lost")
                }
            }
            delay(backoff.nextDelayMs())
        }
    }

    private suspend fun connectOnce(
        computer: DirectComputer,
        gen: Long,
        backoff: ReconnectBackoff,
        bridge: CodexSessionBridge,
    ): ConnectionOutcome {
        val pipe = try {
            openPipe(computer)
        } catch (e: AlleycatException) {
            val auth = e.message?.contains("rejected") == true
            return if (auth) {
                ConnectionOutcome.AuthFailed(e.message ?: "app-server rejected the connection")
            } else {
                ConnectionOutcome.RetryWithDetail(e.message ?: "connection failed")
            }
        }
        val outcome = CodexJsonRpcLoop.run(
            pipe = pipe,
            bridge = bridge,
            store = store,
            commands = commands,
            generation = generation,
            gen = gen,
            scope = scope,
            livePipe = livePipe,
            onConnectionState = { state ->
                connectionState = state
                if (state == ConnectionState.CONNECTED) backoff.reset()
            },
        )
        return outcome
    }
}

/**
 * Owns every saved direct-URL computer. [AgentProvider.CODEX] has one
 * connection slot: CONNECTED if any socket is up.
 */
class CodexDirectBroker(
    private val store: AgentSessionStore,
    private val scope: CoroutineScope,
    private val factory: (DirectComputer) -> CodexDirectClient,
) {
    constructor(
        httpClient: OkHttpClient,
        store: AgentSessionStore,
        scope: CoroutineScope,
    ) : this(
        store = store,
        scope = scope,
        factory = { CodexDirectClient(httpClient, store, scope) },
    )

    private val clients = linkedMapOf<String, CodexDirectClient>()

    @Synchronized
    fun reconcile(computers: List<DirectComputer>) {
        val wanted = computers.associateBy { it.computerId }
        val extra = clients.keys.filter { it !in wanted }
        extra.forEach { id ->
            clients.remove(id)?.stop(clearSessions = true)
        }
        wanted.values.forEach { computer ->
            val existing = clients[computer.computerId]
            if (existing == null) {
                val client = factory(computer)
                clients[computer.computerId] = client
                client.start(computer)
            }
        }
        if (clients.isEmpty()) {
            store.setConnection(AgentProvider.CODEX, ConnectionState.DISCONNECTED)
        }
    }

    @Synchronized
    fun stop(clearSessions: Boolean) {
        clients.values.forEach { it.stop(clearSessions) }
        clients.clear()
        if (clearSessions) {
            store.setConnection(AgentProvider.CODEX, ConnectionState.DISCONNECTED)
        }
    }

    @Synchronized
    fun openDetail(sessionId: String) {
        val machineId = store.sessions.value.firstOrNull { it.id == sessionId }?.machineId
        clientFor(machineId)?.openDetail(sessionId)
    }

    @Synchronized
    fun closeDetail() {
        clients.values.forEach { it.closeDetail() }
    }

    @Synchronized
    fun decideApproval(requestId: String, verdict: ApprovalVerdict) {
        clients.values.forEach { it.decideApproval(requestId, verdict) }
    }

    @Synchronized
    fun requestThreadStart(requestId: String, machineId: String, prompt: String, cwd: String?) {
        clients[machineId]?.requestThreadStart(requestId, prompt, cwd)
    }

    @Synchronized
    fun drop(machineId: String) {
        clients.remove(machineId)?.stop(clearSessions = true)
    }

    private fun clientFor(machineId: String?): CodexDirectClient? {
        if (machineId != null) clients[machineId]?.let { return it }
        return clients.values.singleOrNull()
    }
}
