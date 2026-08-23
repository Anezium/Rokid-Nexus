package com.anezium.rokidbus.plugin.agents

import com.anezium.rokidbus.plugin.agents.alleycat.AlleycatException
import com.anezium.rokidbus.plugin.agents.alleycat.ApprovalVerdict
import com.anezium.rokidbus.plugin.agents.alleycat.CodexAppServerClient
import com.anezium.rokidbus.plugin.agents.alleycat.JsonPipe
import com.anezium.rokidbus.plugin.agents.alleycat.JsonRpcId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Codex app-server JSON-RPC on an already-opened [JsonPipe]. Direct `ws(s)://`
 * and Alleycat (after `connect`) share this loop so the session layer is not
 * duplicated.
 */
internal object CodexJsonRpcLoop {
    suspend fun run(
        pipe: JsonPipe,
        bridge: CodexSessionBridge,
        store: AgentSessionStore,
        commands: LinkedBlockingQueue<CodexCommand>,
        generation: AtomicLong,
        gen: Long,
        scope: CoroutineScope,
        livePipe: AtomicReference<JsonPipe?>,
        onConnectionState: (ConnectionState) -> Unit,
    ): ConnectionOutcome = coroutineScope {
        val ended = CompletableDeferred<ConnectionOutcome>()
        fun fail(outcome: ConnectionOutcome) {
            ended.complete(outcome)
        }
        if (generation.get() != gen) {
            pipe.close()
            return@coroutineScope ConnectionOutcome.Retry
        }
        livePipe.set(pipe)
        val client = CodexAppServerClient(
            pipe = pipe,
            onNotification = bridge::onNotification,
            onApprovalRequest = bridge::onApproval,
        )
        val deadlines = ConnectionDeadlines(this) { detail ->
            pipe.close()
            fail(ConnectionOutcome.RetryWithDetail(detail))
        }
        try {
            // Initial snapshot, and the drift_reload path: never trust a
            // replay ring; always reload via thread/list.
            deadlines.arm("list", "thread/list timed out")
            val page = client.reloadState()
            deadlines.clear("list")
            if (generation.get() != gen) return@coroutineScope ConnectionOutcome.Retry
            onConnectionState(ConnectionState.CONNECTED)
            bridge.onConnected()
            bridge.publishThreads(page)
            while (generation.get() == gen && !ended.isCompleted && scope.isActive) {
                val command = commands.poll(50, TimeUnit.MILLISECONDS)
                if (command != null) {
                    handleCommand(client, bridge, store, command)
                    continue
                }
                try {
                    client.receiveOrNull(50L)
                } catch (e: AlleycatException) {
                    fail(
                        if (e.message?.contains("rejected") == true) {
                            ConnectionOutcome.AuthFailed(e.message ?: "app-server rejected the connection")
                        } else {
                            ConnectionOutcome.RetryWithDetail(e.message ?: "Connection lost")
                        },
                    )
                }
            }
            if (ended.isCompleted) ended.await() else ConnectionOutcome.Retry
        } catch (e: AlleycatException) {
            if (e.message?.contains("rejected") == true) {
                ConnectionOutcome.AuthFailed(e.message ?: "app-server rejected the connection")
            } else {
                ConnectionOutcome.RetryWithDetail(e.message ?: "connection failed")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            ConnectionOutcome.Retry
        } finally {
            deadlines.clearAll()
            pipe.close()
        }
    }

    fun handleCommand(
        client: CodexAppServerClient,
        bridge: CodexSessionBridge,
        store: AgentSessionStore,
        command: CodexCommand,
    ) {
        try {
            when (command) {
                is CodexCommand.OpenDetail -> {
                    val thread = client.threadResume(command.sessionId)
                    bridge.onThreadStarted(thread)
                    bridge.openThread(thread.id)
                }
                CodexCommand.CloseDetail -> bridge.closeThread()
                is CodexCommand.Decide -> {
                    val id = JsonRpcId.fromWire(command.requestId) ?: return
                    client.replyApproval(id, command.verdict)
                    store.resolveApproval(command.requestId)
                }
                is CodexCommand.StartThread -> {
                    val thread = client.threadStart(cwd = command.cwd)
                    bridge.onThreadStarted(thread)
                    if (command.prompt.isNotBlank()) {
                        val turn = client.turnStart(thread.id, command.prompt)
                        bridge.onTurnStarted(thread.id, turn)
                    }
                    store.setThreadStart(
                        ThreadStartResult(
                            requestId = command.requestId,
                            ok = true,
                            provider = AgentProvider.CODEX,
                            sessionId = thread.id,
                            error = null,
                        ),
                    )
                }
            }
        } catch (_: AlleycatException) {
            if (command is CodexCommand.StartThread) {
                store.setThreadStart(
                    ThreadStartResult(
                        requestId = command.requestId,
                        ok = false,
                        provider = AgentProvider.CODEX,
                        sessionId = null,
                        error = "the computer could not start it",
                    ),
                )
            }
        }
    }
}
