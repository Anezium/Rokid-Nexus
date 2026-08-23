package com.anezium.rokidbus.plugin.agents.alleycat

/**
 * Agent selection after `list_agents`. Auto-picks a singleton list, including
 * the common kittylitter case of only `codex`. Multiple agents require an
 * explicit choice that is stored on the computer.
 */
sealed class AgentChoice {
    data class Selected(val agent: String) : AgentChoice()
    data class NeedsPicker(val agents: List<String>) : AgentChoice()
    data object None : AgentChoice()
}

object AlleycatConnectSequence {
    const val DEFAULT_AGENT: String = "codex"
    const val ALPN: String = "alleycat/1"
    const val HANDSHAKE_TIMEOUT_MS: Long = 15_000L

    val ALPN_BYTES: ByteArray = ALPN.toByteArray(Charsets.US_ASCII)

    fun chooseAgent(names: List<String>, saved: String?): AgentChoice {
        val distinct = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (distinct.isEmpty()) return AgentChoice.None
        if (saved != null && distinct.contains(saved)) return AgentChoice.Selected(saved)
        if (distinct.size == 1) return AgentChoice.Selected(distinct.single())
        if (distinct.all { it == DEFAULT_AGENT }) return AgentChoice.Selected(DEFAULT_AGENT)
        return AgentChoice.NeedsPicker(distinct)
    }

    fun isAuthFailure(error: String?): Boolean {
        val m = error?.lowercase().orEmpty()
        if (m.isEmpty()) return false
        return m.contains("unauthorized") ||
            m.contains("unauthenticated") ||
            m.contains("forbidden") ||
            m.contains("invalid token") ||
            m.contains("bad token") ||
            m.contains("auth failed") ||
            m.contains("authentication") ||
            m.contains("token rejected") ||
            m.contains("pairing invalid")
    }

    fun isVersionOrAlpnFailure(error: String?): Boolean {
        val m = error?.lowercase().orEmpty()
        return m.contains("unsupported handshake version") ||
            m.contains("unsupported pairing version") ||
            m.contains("alpn mismatch") ||
            m.contains("alpn ")
    }

    fun sessionSeq(session: HandshakeSession): Long? {
        val raw = session.raw
        if (raw.has("last_seq") && !raw.isNull("last_seq")) {
            return raw.optLong("last_seq")
        }
        if (raw.has("seq") && !raw.isNull("seq")) {
            return raw.optLong("seq")
        }
        val resume = raw.optJSONObject("resume")
        if (resume != null && resume.has("last_seq") && !resume.isNull("last_seq")) {
            return resume.optLong("last_seq")
        }
        return null
    }

    fun redactSecret(text: String?, secret: String): String {
        if (text.isNullOrEmpty()) return text ?: ""
        if (secret.isEmpty() || !text.contains(secret)) return text
        return text.replace(secret, "<redacted>")
    }
}

/**
 * Handshake on an already-opened framed stream. First frame carries the
 * token. After a successful [connect], the same pipe is Codex JSON-RPC.
 */
class AlleycatHandshakeFlow(
    private val handshake: AlleycatHandshakeClient,
    private val token: String,
) {
    constructor(pipe: JsonPipe, token: String) : this(AlleycatHandshakeClient(pipe), token)

    override fun toString(): String = "AlleycatHandshakeFlow(token=<redacted>)"

    fun listAgents(): HandshakeResponse = handshake.exchange(HandshakeRequest.ListAgents(token))

    fun restart(agent: String): HandshakeResponse =
        handshake.exchange(HandshakeRequest.RestartAgent(token, agent))

    fun connect(agent: String, lastSeq: Long?): HandshakeResponse =
        handshake.exchange(HandshakeRequest.Connect(token, agent, lastSeq))

    /**
     * `list_agents` → pick → `connect` (one `restart_agent` on a non-auth
     * connect failure). Version mismatches throw; auth failures return
     * `ok: false` and must not be retried by the caller.
     */
    fun attach(
        savedAgent: String?,
        lastSeq: Long?,
    ): AlleycatAttachResult {
        val listed = listAgents()
        if (!listed.ok) {
            return AlleycatAttachResult.HandshakeFailed(listed.error ?: "list_agents failed")
        }
        val names = listed.agents?.map { it.name }.orEmpty()
        when (val choice = AlleycatConnectSequence.chooseAgent(names, savedAgent)) {
            AgentChoice.None ->
                return AlleycatAttachResult.HandshakeFailed("no agents advertised")
            is AgentChoice.NeedsPicker ->
                return AlleycatAttachResult.NeedsPicker(choice.agents)
            is AgentChoice.Selected -> {
                val connected = connectOrRestart(choice.agent, lastSeq)
                return when {
                    !connected.ok ->
                        AlleycatAttachResult.HandshakeFailed(connected.error ?: "connect failed")
                    else -> AlleycatAttachResult.Attached(
                        agent = choice.agent,
                        advertised = names,
                        session = connected.session,
                    )
                }
            }
        }
    }

    private fun connectOrRestart(agent: String, lastSeq: Long?): HandshakeResponse {
        val first = connect(agent, lastSeq)
        if (first.ok) return first
        if (AlleycatConnectSequence.isAuthFailure(first.error)) return first
        restart(agent)
        return connect(agent, lastSeq)
    }
}

sealed class AlleycatAttachResult {
    data class Attached(
        val agent: String,
        val advertised: List<String>,
        val session: HandshakeSession?,
    ) : AlleycatAttachResult()

    data class NeedsPicker(val agents: List<String>) : AlleycatAttachResult()

    data class HandshakeFailed(val error: String) : AlleycatAttachResult()
}
