package com.anezium.rokidbus.plugin.agents

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Reader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * Inbound link from the PC daemons.
 *
 * The phone listens and the computer dials, which is the only direction that
 * needs no setup: Android lets an app accept connections, Windows would demand
 * an administrator firewall rule for the reverse. Discovery follows the same
 * asymmetry — the daemon broadcasts, we answer by unicast, and Windows lets that
 * reply back in because it answers a broadcast the PC itself just sent.
 */
class AgentdLinkServer(
    private val store: AgentSessionStore,
    private val configStore: AgentsConfigStore,
    private val scope: CoroutineScope,
    private val onMachineTrusted: (String) -> Unit = {},
) {
    private var acceptJob: Job? = null
    private var discoveryJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var discoverySocket: DatagramSocket? = null
    private val clientLock = Any()

    @Volatile private var client: LinkConnection? = null

    fun start() {
        if (acceptJob != null) return
        store.setConnection(AgentProvider.CLAUDE, ConnectionState.CONNECTING, "waiting for a computer")
        acceptJob = scope.launch(Dispatchers.IO) { acceptLoop() }
        discoveryJob = scope.launch(Dispatchers.IO) { discoveryLoop() }
    }

    fun stop(clearSessions: Boolean) {
        acceptJob?.cancel()
        acceptJob = null
        discoveryJob?.cancel()
        discoveryJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { discoverySocket?.close() }
        discoverySocket = null
        synchronized(clientLock) {
            client.also { client = null }
        }?.close()
        store.setLinkMachine(null)
        store.setConnection(AgentProvider.CLAUDE, ConnectionState.DISCONNECTED)
        store.clearApprovals(AgentProvider.AGENTD_PROVIDERS)
        if (clearSessions) store.replaceLinkSessions(AgentProvider.AGENTD_PROVIDERS, emptyList())
    }

    fun openDetail(sessionId: String) {
        send(AgentdProtocolCodec.detailOpen(sessionId))
    }

    fun closeDetail() {
        send(AgentdProtocolCodec.DETAIL_CLOSE)
    }

    fun decideApproval(requestId: String, decision: ApprovalDecision) {
        send(AgentdProtocolCodec.approvalDecision(requestId, decision))
    }

    fun sendSessionInput(requestId: String, sessionId: String, text: String) {
        send(AgentdProtocolCodec.sessionInput(requestId, sessionId, text))
    }

    fun requestFolders(requestId: String, path: String?) {
        send(AgentdProtocolCodec.fsList(requestId, path))
    }

    fun requestThreadStart(requestId: String, provider: AgentProvider, path: String, prompt: String) {
        send(AgentdProtocolCodec.threadStart(requestId, provider, path, prompt))
    }

    /** Callers are UI/service threads; Android forbids socket writes there. */
    private fun send(payload: String) {
        val connection = client ?: return
        scope.launch(Dispatchers.IO) { connection.send(payload) }
    }

    private suspend fun acceptLoop() {
        while (scope.isActive) {
            try {
                val server = ServerSocket(LINK_PORT)
                serverSocket = server
                while (scope.isActive) {
                    val socket = server.accept()
                    // A candidate cannot displace the live client until hello succeeds.
                    val connection = LinkConnection(socket)
                    scope.launch(Dispatchers.IO) { serve(connection) }
                }
            } catch (_: SocketException) {
                // Socket closed on stop, or the port was momentarily taken.
            } catch (_: Throwable) {
                // Keep listening: a failed accept must never end the loop.
            }
            runCatching { serverSocket?.close() }
            serverSocket = null
            if (!scope.isActive) return
            kotlinx.coroutines.delay(RETRY_DELAY_MS)
        }
    }

    private fun serve(connection: LinkConnection) {
        val codec = AgentdProtocolCodec()
        val gate = AgentdInboundGate { hello ->
            configStore.authorizeMachine(
                machineId = hello.machineId,
                token = hello.token,
                machineName = hello.machineName,
            )
        }
        codec.reset()
        try {
            connection.lines { line ->
                when (val decision = gate.receive(codec.parse(line))) {
                    is AgentdInboundDecision.HelloAccepted -> {
                        connection.machineId = decision.hello.machineId
                        activate(connection)
                        if (decision.newlyTrusted) {
                            onMachineTrusted(decision.hello.machineName)
                        }
                        if (!connection.send(HELLO_ACK)) return@lines false
                        configStore.touchMachineSeen(decision.hello.machineId)
                        store.setLinkMachine(
                            LinkMachine(
                                machineId = decision.hello.machineId,
                                machineName = decision.hello.machineName,
                                overTailnet = isTailnetAddress(connection.remoteAddress),
                            ),
                        )
                        store.setConnection(
                            AgentProvider.CLAUDE,
                            ConnectionState.CONNECTED,
                            decision.hello.machineName,
                        )
                        true
                    }
                    is AgentdInboundDecision.AuthRejected -> {
                        connection.send(AgentdProtocolCodec.helloReject(decision.reason))
                        // Anyone on the network can knock on this door, so a
                        // stranger being turned away must not repaint the state
                        // of a link that is working.
                        if (client == null) {
                            store.setConnection(
                                AgentProvider.CLAUDE,
                                ConnectionState.AUTH_FAILED,
                                "Machine authentication failed",
                            )
                        }
                        false
                    }
                    AgentdInboundDecision.ProtocolRejected -> false
                    is AgentdInboundDecision.Frame -> {
                        if (!isActive(connection)) return@lines false
                        handleAuthenticatedFrame(connection, decision.action)
                        true
                    }
                }
            }
        } catch (_: Throwable) {
            // Fall through: the connection is finished either way.
        } finally {
            connection.close()
            val wasActive = synchronized(clientLock) {
                if (client !== connection) {
                    false
                } else {
                    client = null
                    true
                }
            }
            if (wasActive) {
                connection.machineId?.let(configStore::touchMachineSeen)
                store.setLinkMachine(null)
                store.setConnection(
                    AgentProvider.CLAUDE,
                    ConnectionState.CONNECTING,
                    "waiting for a computer",
                )
                store.replaceLinkSessions(AgentProvider.AGENTD_PROVIDERS, emptyList())
                store.clearApprovals(AgentProvider.AGENTD_PROVIDERS)
            }
        }
    }

    private fun activate(connection: LinkConnection) {
        val previous = synchronized(clientLock) {
            client.also { client = connection }
        }
        if (previous !== connection) previous?.close()
    }

    private fun isActive(connection: LinkConnection): Boolean =
        synchronized(clientLock) { client === connection }

    /** The wearer forgot this machine: a live link from it does not survive that. */
    fun dropMachine(machineId: String) {
        val current = synchronized(clientLock) { client }
        if (current?.machineId == machineId) current.close()
    }

    private fun handleAuthenticatedFrame(
        connection: LinkConnection,
        action: AgentdAction,
    ) {
        when (action) {
            is AgentdAction.Snapshot ->
                store.replaceLinkSessions(AgentProvider.AGENTD_PROVIDERS, action.sessions)
            is AgentdAction.Upsert -> store.upsert(action.session)
            is AgentdAction.Removed -> store.remove(action.provider, action.sessionId)
            is AgentdAction.Detail ->
                store.setConversation(action.provider, action.sessionId, action.messages)
            is AgentdAction.DetailAppend ->
                store.appendConversation(action.provider, action.sessionId, action.message)
            is AgentdAction.ApprovalRequested -> store.upsertApproval(action.approval)
            is AgentdAction.ApprovalResolved -> store.resolveApproval(action.requestId)
            is AgentdAction.FolderListing -> store.setFsListing(action.listing)
            is AgentdAction.ThreadStarted -> store.setThreadStart(action.result)
            is AgentdAction.SessionInputAnswered -> store.setSessionInputResult(action.result)
            is AgentdAction.Send -> connection.send(action.text)
            is AgentdAction.Hello,
            is AgentdAction.HelloAcknowledged,
            AgentdAction.Ignore,
            -> Unit
        }
    }

    private suspend fun discoveryLoop() {
        while (scope.isActive) {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(java.net.InetSocketAddress(DISCOVERY_PORT))
                }
                discoverySocket = socket
                val buffer = ByteArray(2048)
                while (scope.isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    if (isDaemonBeacon(packet)) {
                        replyTo(socket, packet.address, packet.port)
                    }
                }
            } catch (_: SocketException) {
                // Closed on stop.
            } catch (_: Throwable) {
                // Ignore and rebind.
            }
            runCatching { discoverySocket?.close() }
            discoverySocket = null
            if (!scope.isActive) return
            kotlinx.coroutines.delay(RETRY_DELAY_MS)
        }
    }

    private fun isDaemonBeacon(packet: DatagramPacket): Boolean {
        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return false
        return json.nullableString("nexus", MAX_WIRE_TYPE_CHARS) == "agentd" &&
            json.intOrNull("v") == 1
    }

    private fun replyTo(socket: DatagramSocket, address: InetAddress, port: Int) {
        val payload = JSONObject()
            .put("nexus", "agents-phone")
            .put("v", 1)
            .put("port", LINK_PORT)
            .put("name", android.os.Build.MODEL ?: "phone")
            .toString()
            .toByteArray(Charsets.UTF_8)
        runCatching { socket.send(DatagramPacket(payload, payload.size, address, port)) }
    }

    companion object {
        const val LINK_PORT = 8792
        const val DISCOVERY_PORT = 8793
        private const val RETRY_DELAY_MS = 2000L
        private val HELLO_ACK = JSONObject()
            .put("type", "hello_ack")
            .put("v", 1)
            .toString()
    }
}

internal sealed interface AgentdInboundDecision {
    data class HelloAccepted(
        val hello: AgentdAction.Hello,
        val newlyTrusted: Boolean,
    ) : AgentdInboundDecision

    data class Frame(val action: AgentdAction) : AgentdInboundDecision
    data class AuthRejected(val reason: String) : AgentdInboundDecision
    data object ProtocolRejected : AgentdInboundDecision
}

internal class AgentdInboundGate(
    private val authorize: (AgentdAction.Hello) -> MachineTrustResult,
) {
    private var authenticated = false

    fun receive(action: AgentdAction): AgentdInboundDecision {
        if (!authenticated) {
            if (action !is AgentdAction.Hello) return AgentdInboundDecision.ProtocolRejected
            return when (val result = authorize(action)) {
                MachineTrustResult.TRUSTED,
                MachineTrustResult.NEWLY_TRUSTED,
                -> {
                    authenticated = true
                    AgentdInboundDecision.HelloAccepted(
                        hello = action,
                        newlyTrusted = result == MachineTrustResult.NEWLY_TRUSTED,
                    )
                }
                MachineTrustResult.REJECTED_BAD_TOKEN,
                MachineTrustResult.REJECTED_NOT_INVITED,
                -> AgentdInboundDecision.AuthRejected(
                    result.rejectionReason ?: AgentdProtocolCodec.REJECT_UNKNOWN_MACHINE,
                )
            }
        }
        return if (action is AgentdAction.Hello) {
            AgentdInboundDecision.ProtocolRejected
        } else {
            AgentdInboundDecision.Frame(action)
        }
    }
}

internal fun isTailnetAddress(address: String?): Boolean {
    val parts = address?.split(".") ?: return false
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    return octets[0] == 100 && octets[1] in 64..127
}

internal class LinkConnection(private val socket: Socket) {
    /** Set once hello is accepted; identifies who holds this connection. */
    @Volatile var machineId: String? = null

    val remoteAddress: String? get() = socket.inetAddress?.hostAddress

    private val writer: BufferedWriter =
        BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
    private val reader: BufferedReader =
        BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

    init {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        // agentd pings every 30 seconds; 90 seconds tolerates two missed intervals.
        socket.soTimeout = LINK_IDLE_TIMEOUT_MS
    }

    fun lines(onLine: (String) -> Boolean) {
        while (true) {
            val line = reader.readBoundedLine(MAX_LINK_LINE_CHARS) ?: return
            if (line.isNotBlank() && !onLine(line)) return
        }
    }

    @Synchronized
    fun send(payload: String): Boolean = runCatching {
        writer.write(payload)
        writer.write("\n")
        writer.flush()
        true
    }.getOrElse { false }

    fun close() {
        runCatching { socket.close() }
    }
}

internal fun Reader.readBoundedLine(maxChars: Int): String? {
    require(maxChars > 0)
    val result = StringBuilder(minOf(maxChars, 256))
    while (true) {
        when (val next = read()) {
            -1 -> return result.takeIf(StringBuilder::isNotEmpty)?.toString()
            '\n'.code -> {
                if (result.endsWith('\r')) result.setLength(result.length - 1)
                return result.toString()
            }
            else -> {
                if (result.length >= maxChars) throw LineTooLongException()
                result.append(next.toChar())
            }
        }
    }
}

internal class LineTooLongException : IOException("LAN link frame exceeds 64 KiB")

internal const val MAX_LINK_LINE_CHARS = 64 * 1024
internal const val LINK_IDLE_TIMEOUT_MS = 90_000
