package com.anezium.rokidbus.plugin.agents

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class LinkMachine(
    val machineId: String,
    val machineName: String,
    val overTailnet: Boolean,
)

class AgentSessionStore {
    private val providerSessions = AgentProvider.values().associateWith {
        linkedMapOf<String, AgentSession>()
    }.toMutableMap()
    private val _sessions = MutableStateFlow<List<AgentSession>>(emptyList())
    private val _connections = MutableStateFlow(
        AgentProvider.values().associateWith { ProviderConnectionState() },
    )

    private val _conversation = MutableStateFlow<AgentConversation?>(null)
    private val _approvals = MutableStateFlow<List<AgentApproval>>(emptyList())
    private val _linkMachine = MutableStateFlow<LinkMachine?>(null)

    val approvals: StateFlow<List<AgentApproval>> = _approvals.asStateFlow()
    val sessions: StateFlow<List<AgentSession>> = _sessions.asStateFlow()
    val connections: StateFlow<Map<AgentProvider, ProviderConnectionState>> =
        _connections.asStateFlow()
    val conversation: StateFlow<AgentConversation?> = _conversation.asStateFlow()

    /** The machine currently holding the LAN link, if any. */
    val linkMachine: StateFlow<LinkMachine?> = _linkMachine.asStateFlow()

    fun setLinkMachine(machine: LinkMachine?) {
        _linkMachine.value = machine
    }

    private val _fsListing = MutableStateFlow<FsListing?>(null)

    /** The latest folder listing the daemon answered; matched by request id. */
    val fsListing: StateFlow<FsListing?> = _fsListing.asStateFlow()

    fun setFsListing(listing: FsListing) {
        _fsListing.value = listing
    }

    private val _threadStart = MutableStateFlow<ThreadStartResult?>(null)

    /** The latest spawn verdict the daemon answered; matched by request id. */
    val threadStart: StateFlow<ThreadStartResult?> = _threadStart.asStateFlow()

    fun setThreadStart(result: ThreadStartResult) {
        _threadStart.value = result
    }

    @Synchronized
    fun openConversation(session: AgentSession) {
        _conversation.value = AgentConversation(
            sessionKey = session.key,
            sessionId = session.id,
            provider = session.provider,
        )
    }

    @Synchronized
    fun closeConversation() {
        _conversation.value = null
    }

    @Synchronized
    fun setConversation(
        provider: AgentProvider,
        sessionId: String,
        messages: List<AgentMessage>,
    ) {
        val current = _conversation.value ?: return
        // A late reply for a conversation the wearer already left must not reopen it.
        if (current.provider != provider || current.sessionId != sessionId) return
        _conversation.value = current.copy(
            loading = false,
            messages = messages.takeLast(AgentConversation.MAX_MESSAGES),
        )
    }

    @Synchronized
    fun appendConversation(
        provider: AgentProvider,
        sessionId: String,
        message: AgentMessage,
    ) {
        val current = _conversation.value ?: return
        if (current.provider != provider || current.sessionId != sessionId) return
        _conversation.value = current.copy(
            loading = false,
            messages = (current.messages + message).takeLast(AgentConversation.MAX_MESSAGES),
        )
    }

    @Synchronized
    fun replaceProvider(
        provider: AgentProvider,
        sessions: List<AgentSession>,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val replacement = linkedMapOf<String, AgentSession>()
        sessions.filter { it.provider == provider }.forEach { replacement[it.id] = it }
        providerSessions[provider] = replacement
        publish(nowMs)
    }

    /**
     * One snapshot, several harnesses. The daemon link speaks for every provider
     * it monitors, so its snapshot is authoritative for all of them at once —
     * replacing them one at a time would leave a harness the daemon just stopped
     * watching sitting on the board forever.
     */
    @Synchronized
    fun replaceLinkSessions(
        providers: Set<AgentProvider>,
        sessions: List<AgentSession>,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        providers.forEach { provider ->
            val preserved = providerSessions.getValue(provider).values.filter {
                it.machineId?.startsWith(DirectComputer.ID_PREFIX) == true
            }
            val replacement = linkedMapOf<String, AgentSession>()
            preserved.forEach { replacement[it.id] = it }
            sessions.filter { it.provider == provider }.forEach { replacement[it.id] = it }
            providerSessions[provider] = replacement
        }
        publish(nowMs)
    }

    /**
     * Replace one computer's sessions for [provider] without touching other
     * machines. Direct-URL Codex threads share [AgentProvider.CODEX] with the
     * daemon link and must not wipe each other.
     */
    @Synchronized
    fun replaceMachineSessions(
        machineId: String,
        provider: AgentProvider,
        sessions: List<AgentSession>,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val current = providerSessions.getValue(provider)
        val replacement = linkedMapOf<String, AgentSession>()
        current.values.filter { it.machineId != machineId }.forEach { replacement[it.id] = it }
        sessions.filter { it.provider == provider }.forEach { replacement[it.id] = it }
        providerSessions[provider] = replacement
        publish(nowMs)
    }

    @Synchronized
    fun upsert(session: AgentSession, nowMs: Long = System.currentTimeMillis()) {
        providerSessions.getValue(session.provider)[session.id] = session
        publish(nowMs)
    }

    @Synchronized
    fun remove(
        provider: AgentProvider,
        sessionId: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        providerSessions.getValue(provider).remove(sessionId)
        publish(nowMs)
    }

    /**
     * Drops what has aged out for good. Hiding an expired session from the
     * published list was never enough: it stayed in [providerSessions] for the
     * lifetime of the process, so a long-lived daemon churning through sessions
     * grew the map forever and made every publish a little slower.
     *
     * Returns the keys that were let go, so callers can forget what they were
     * remembering about them.
     */
    @Synchronized
    fun prune(nowMs: Long = System.currentTimeMillis()): Set<String> {
        val dropped = mutableSetOf<String>()
        providerSessions.values.forEach { sessions ->
            val expired = sessions.values.filter { it.hasExpired(nowMs) }
            expired.forEach { session ->
                sessions.remove(session.id)
                dropped += session.key
            }
        }
        publish(nowMs)
        return dropped
    }

    // ------------------------------------------------------------- approvals

    @Synchronized
    fun upsertApproval(approval: AgentApproval) {
        val without = _approvals.value.filterNot { it.requestId == approval.requestId }
        // Oldest out first: a request nobody answered is the one least likely to
        // still matter, and the wearer should never be handed a stale question.
        _approvals.value = (without + approval).takeLast(AgentApproval.MAX_PENDING)
    }

    @Synchronized
    fun resolveApproval(requestId: String) {
        _approvals.value = _approvals.value.filterNot { it.requestId == requestId }
    }

    /**
     * A provider that went away takes its questions with it: the daemon replays
     * everything still pending once it is back, so keeping them would only offer
     * the wearer decisions that can no longer be delivered.
     */
    @Synchronized
    fun clearApprovals(providers: Set<AgentProvider>) {
        _approvals.value = _approvals.value.filterNot { it.provider in providers }
    }

    /**
     * Drop approvals that belong to one computer. Direct-URL Codex shares
     * [AgentProvider.CODEX] with the daemon link; a socket dying on one
     * machine must not cancel the other's questions.
     */
    @Synchronized
    fun clearApprovalsForMachine(machineId: String) {
        val sessionIds = providerSessions.values
            .asSequence()
            .flatMap { it.values.asSequence() }
            .filter { it.machineId == machineId }
            .map { it.id }
            .toSet()
        _approvals.value = _approvals.value.filterNot { it.sessionId in sessionIds }
    }

    fun approvalFor(sessionKey: String): AgentApproval? =
        _approvals.value.firstOrNull { it.sessionKey == sessionKey }

    @Synchronized
    fun setConnection(
        provider: AgentProvider,
        state: ConnectionState,
        detail: String? = null,
    ) {
        _connections.value = _connections.value.toMutableMap().apply {
            put(provider, ProviderConnectionState(state, detail))
        }
    }

    private fun publish(nowMs: Long) {
        val retained = providerSessions.values
            .asSequence()
            .flatMap { it.values.asSequence() }
            .filterNot { it.hasExpired(nowMs) }
            .sortedWith(SESSION_COMPARATOR)
            .toList()
        _sessions.value = retained
    }

    private fun AgentSession.hasExpired(nowMs: Long): Boolean =
        status == AgentStatus.DONE &&
            lastActivityAt?.let { nowMs >= it && nowMs - it > DONE_RETENTION_MS } == true

    companion object {
        const val DONE_RETENTION_MS = 30 * 60 * 1_000L

        /**
         * Attention first. A failed session used to sort below idle ones while
         * being rendered in alert and raising a band — the order contradicted
         * everything else the product said about it.
         */
        private val STATUS_RANK = mapOf(
            AgentStatus.NEEDS_YOU to 0,
            AgentStatus.ERROR to 1,
            AgentStatus.WORKING to 2,
            AgentStatus.IDLE to 3,
            AgentStatus.DONE to 4,
        )

        private val SESSION_COMPARATOR = Comparator<AgentSession> { left, right ->
            val rank = STATUS_RANK.getValue(left.status).compareTo(STATUS_RANK.getValue(right.status))
            if (rank != 0) {
                rank
            } else {
                val withinStatus = when (left.status) {
                    AgentStatus.NEEDS_YOU -> compareValues(
                        left.pendingRequest?.createdAt ?: Long.MAX_VALUE,
                        right.pendingRequest?.createdAt ?: Long.MAX_VALUE,
                    )
                    AgentStatus.WORKING,
                    AgentStatus.IDLE,
                    AgentStatus.ERROR,
                    AgentStatus.DONE,
                    -> compareValues(
                        right.lastActivityAt ?: Long.MIN_VALUE,
                        left.lastActivityAt ?: Long.MIN_VALUE,
                    )
                }
                if (withinStatus != 0) {
                    withinStatus
                } else {
                    left.displayTitle.compareTo(right.displayTitle, ignoreCase = true)
                }
            }
        }
    }
}

object AgentsRuntime {
    val store = AgentSessionStore()

    /**
     * True while the hub has the plugin's surface open. The monitor service
     * stops the plugin service when it has nothing left to watch, and doing that
     * under a surface the wearer is reading tears down the bus client outside
     * the hub's own open/close lifecycle.
     */
    @Volatile
    var hudOpen: Boolean = false

    private val _linkedMachines = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * A computer just linked itself for the first time. The connection is owned
     * by the monitor service and the glasses are owned by the plugin service, so
     * this is how the news crosses: replayed once, because the link can land
     * before the plugin service is listening.
     */
    val linkedMachines: SharedFlow<String> = _linkedMachines.asSharedFlow()

    fun announceLinkedMachine(machineName: String) {
        _linkedMachines.tryEmit(machineName)
    }
}
