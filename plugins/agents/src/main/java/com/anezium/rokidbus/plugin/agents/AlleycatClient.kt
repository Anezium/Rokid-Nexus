package com.anezium.rokidbus.plugin.agents

import com.anezium.rokidbus.plugin.agents.alleycat.AlleycatAttachResult
import com.anezium.rokidbus.plugin.agents.alleycat.AlleycatConnectSequence
import com.anezium.rokidbus.plugin.agents.alleycat.AlleycatException
import com.anezium.rokidbus.plugin.agents.alleycat.AlleycatHandshakeFlow
import com.anezium.rokidbus.plugin.agents.alleycat.ApprovalVerdict
import com.anezium.rokidbus.plugin.agents.alleycat.JsonPipe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference

fun interface AlleycatStreamOpener {
    fun open(computer: AlleycatComputer): JsonPipe
}

data class AlleycatSessionHooks(
    val onAdvertisedAgents: (computerId: String, agents: List<String>) -> Unit = { _, _ -> },
    val onSelectedAgent: (computerId: String, agent: String) -> Unit = { _, _ -> },
    val onLastSeq: (computerId: String, seq: Long) -> Unit = { _, _ -> },
    val onNeedsRePair: (computerId: String, needsRePair: Boolean) -> Unit = { _, _ -> },
)

/**
 * One kittylitter-paired Alleycat node. Handshake (token, list_agents,
 * connect, optional restart) happens on the framed pipe; after `connect` the
 * same pipe is the Codex app-server session from [CodexJsonRpcLoop].
 */
class AlleycatClient(
    private val store: AgentSessionStore,
    private val scope: CoroutineScope,
    private val secrets: AlleycatSecretStore,
    private val openPipe: AlleycatStreamOpener,
    private val hooks: AlleycatSessionHooks = AlleycatSessionHooks(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var loopJob: Job? = null
    private val generation = java.util.concurrent.atomic.AtomicLong(0L)
    private val commands = LinkedBlockingQueue<CodexCommand>()
    private val livePipe = AtomicReference<JsonPipe?>()
    @Volatile var computerId: String? = null
        private set
    @Volatile var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        private set

    @Synchronized
    fun start(computer: AlleycatComputer) {
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
            if (id != null) {
                store.clearApprovalsForMachine(id)
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

    private suspend fun runConnectionLoop(computer: AlleycatComputer, gen: Long) {
        val backoff = ReconnectBackoff()
        val bridge = CodexSessionBridge(store, computer.asCodexRef(), nowMs)
        while (scope.isActive && generation.get() == gen) {
            connectionState = ConnectionState.CONNECTING
            bridge.onConnecting()
            val result = try {
                connectOnce(computer, gen, backoff, bridge)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: AlleycatException) {
                classify(e, tokenOf(computer))
            } catch (t: Throwable) {
                classify(
                    AlleycatException(t.message ?: "connection failed", t),
                    tokenOf(computer),
                )
            } finally {
                livePipe.getAndSet(null)?.close()
            }
            if (generation.get() != gen) return
            when (result) {
                is ConnectionOutcome.AuthFailed -> {
                    connectionState = ConnectionState.AUTH_FAILED
                    hooks.onNeedsRePair(computer.computerId, true)
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
        computer: AlleycatComputer,
        gen: Long,
        backoff: ReconnectBackoff,
        bridge: CodexSessionBridge,
    ): ConnectionOutcome {
        val token = secrets.getToken(computer.computerId)
            ?: return ConnectionOutcome.AuthFailed("Re-pair this computer — the token is missing")
        val pipe = try {
            openPipe.open(computer)
        } catch (e: AlleycatException) {
            return classify(e, token)
        }
        val handshake = try {
            AlleycatHandshakeFlow(pipe, token).attach(computer.selectedAgent, computer.lastSeq)
        } catch (e: AlleycatException) {
            pipe.close()
            return classify(e, token)
        }
        return when (handshake) {
            is AlleycatAttachResult.NeedsPicker -> {
                hooks.onAdvertisedAgents(computer.computerId, handshake.agents)
                pipe.close()
                ConnectionOutcome.Failed("Pick an agent in Add a computer")
            }
            is AlleycatAttachResult.HandshakeFailed -> {
                pipe.close()
                val error = AlleycatConnectSequence.redactSecret(handshake.error, token)
                when {
                    AlleycatConnectSequence.isAuthFailure(error) ->
                        ConnectionOutcome.AuthFailed("Re-pair this computer — $error")
                    AlleycatConnectSequence.isVersionOrAlpnFailure(error) ->
                        ConnectionOutcome.Failed(error)
                    else -> ConnectionOutcome.RetryWithDetail(error)
                }
            }
            is AlleycatAttachResult.Attached -> {
                hooks.onAdvertisedAgents(computer.computerId, handshake.advertised)
                hooks.onSelectedAgent(computer.computerId, handshake.agent)
                hooks.onNeedsRePair(computer.computerId, false)
                handshake.session?.let { session ->
                    AlleycatConnectSequence.sessionSeq(session)?.let { seq ->
                        hooks.onLastSeq(computer.computerId, seq)
                    }
                    // drift_reload is honored by CodexJsonRpcLoop: it always
                    // reloads via thread/list instead of trusting the stream.
                }
                CodexJsonRpcLoop.run(
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
            }
        }
    }

    private fun tokenOf(computer: AlleycatComputer): String =
        secrets.getToken(computer.computerId).orEmpty()

    private fun classify(error: AlleycatException, token: String): ConnectionOutcome {
        val detail = AlleycatConnectSequence.redactSecret(
            error.message ?: "connection failed",
            token,
        )
        return when {
            AlleycatConnectSequence.isAuthFailure(detail) ->
                ConnectionOutcome.AuthFailed("Re-pair this computer — $detail")
            AlleycatConnectSequence.isVersionOrAlpnFailure(detail) ->
                ConnectionOutcome.Failed(detail)
            else -> ConnectionOutcome.RetryWithDetail(detail)
        }
    }
}

class AlleycatBroker(
    private val store: AgentSessionStore,
    private val scope: CoroutineScope,
    private val secrets: AlleycatSecretStore,
    private val factory: (AlleycatComputer) -> AlleycatClient,
) {
    constructor(
        store: AgentSessionStore,
        scope: CoroutineScope,
        secrets: AlleycatSecretStore,
        opener: AlleycatStreamOpener,
        hooks: AlleycatSessionHooks,
    ) : this(
        store = store,
        scope = scope,
        secrets = secrets,
        factory = { AlleycatClient(store, scope, secrets, opener, hooks) },
    )

    private val clients = linkedMapOf<String, AlleycatClient>()
    private val started = linkedMapOf<String, AlleycatComputer>()

    @Synchronized
    fun reconcile(computers: List<AlleycatComputer>) {
        val wanted = computers.associateBy { it.computerId }
        val extra = clients.keys.filter { it !in wanted }
        extra.forEach { id ->
            clients.remove(id)?.stop(clearSessions = true)
            started.remove(id)
        }
        wanted.values.forEach { computer ->
            val existing = clients[computer.computerId]
            val previous = started[computer.computerId]
            val agentChanged = previous != null && previous.selectedAgent != computer.selectedAgent
            if (existing == null || agentChanged) {
                existing?.stop(clearSessions = true)
                val client = factory(computer)
                clients[computer.computerId] = client
                started[computer.computerId] = computer
                client.start(computer)
            }
        }
        if (clients.isEmpty() && store.sessions.value.none { session ->
                session.machineId?.let { isCodexRemoteComputer(it) } == true
            }
        ) {
            store.setConnection(AgentProvider.CODEX, ConnectionState.DISCONNECTED)
        }
    }

    @Synchronized
    fun stop(clearSessions: Boolean) {
        clients.values.forEach { it.stop(clearSessions) }
        clients.clear()
        started.clear()
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
        started.remove(machineId)
        secrets.removeToken(machineId)
    }

    private fun clientFor(machineId: String?): AlleycatClient? {
        if (machineId != null) clients[machineId]?.let { return it }
        return clients.values.singleOrNull()
    }
}
