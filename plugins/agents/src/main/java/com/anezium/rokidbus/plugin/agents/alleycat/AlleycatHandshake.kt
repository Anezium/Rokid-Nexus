package com.anezium.rokidbus.plugin.agents.alleycat

import org.json.JSONObject

/** Alleycat handshake ops on a freshly opened stream. First frame always carries the token. */
sealed class HandshakeRequest {
    abstract val token: String

    data class ListAgents(override val token: String) : HandshakeRequest() {
        override fun toString(): String = "ListAgents(token=<redacted>)"
    }

    data class RestartAgent(
        override val token: String,
        val agent: String,
    ) : HandshakeRequest() {
        override fun toString(): String = "RestartAgent(agent=$agent, token=<redacted>)"
    }

    data class Connect(
        override val token: String,
        val agent: String,
        val lastSeq: Long? = null,
    ) : HandshakeRequest() {
        override fun toString(): String =
            "Connect(agent=$agent, lastSeq=$lastSeq, token=<redacted>)"
    }

    fun toJson(): JSONObject = when (this) {
        is ListAgents -> handshakeEnvelope("list_agents")
        is RestartAgent -> handshakeEnvelope("restart_agent").put("agent", agent)
        is Connect -> {
            val json = handshakeEnvelope("connect").put("agent", agent)
            if (lastSeq != null) {
                json.put("resume", JSONObject().put("last_seq", lastSeq))
            }
            json
        }
    }

    private fun handshakeEnvelope(op: String): JSONObject =
        JSONObject()
            .put("op", op)
            .put("v", HandshakeResponse.VERSION)
            .put("token", token)
}

enum class SessionAttached(val wire: String) {
    FRESH("fresh"),
    RESUMED("resumed"),
    DRIFT_RELOAD("drift_reload"),
    ;

    val requiresStateReload: Boolean
        get() = this == DRIFT_RELOAD

    companion object {
        fun parse(raw: String): SessionAttached =
            values().firstOrNull { it.wire == raw }
                ?: throw AlleycatException("unknown session.attached: $raw")
    }
}

data class AlleycatAgent(
    val name: String,
    val raw: JSONObject? = null,
)

data class HandshakeSession(
    val attached: SessionAttached,
    val raw: JSONObject,
) {
    val requiresStateReload: Boolean
        get() = attached.requiresStateReload
}

data class HandshakeResponse(
    val version: Int,
    val ok: Boolean,
    val agents: List<AlleycatAgent>? = null,
    val session: HandshakeSession? = null,
    val error: String? = null,
) {
    val requiresStateReload: Boolean
        get() = ok && session?.requiresStateReload == true

    companion object {
        const val VERSION: Int = 1

        fun parse(json: JSONObject): HandshakeResponse {
            val version = json.requiredInt("v")
            if (version != VERSION) {
                throw AlleycatException("unsupported handshake version $version (expected $VERSION)")
            }
            val ok = json.requiredBoolean("ok")
            val error = json.optionalString("error")
            if (!ok) {
                return HandshakeResponse(version = version, ok = false, error = error ?: "handshake failed")
            }
            return HandshakeResponse(
                version = version,
                ok = true,
                agents = json.optionalArray("agents")?.let { array -> parseAgents(array) },
                session = json.optionalObject("session")?.let { parseSession(it) },
                error = error,
            )
        }

        private fun parseSession(json: JSONObject): HandshakeSession {
            val attached = SessionAttached.parse(json.requiredString("attached"))
            return HandshakeSession(attached = attached, raw = json)
        }

        private fun parseAgents(array: org.json.JSONArray): List<AlleycatAgent> =
            (0 until array.length()).map { i ->
                when (val item = array.get(i)) {
                    is String -> {
                        if (item.isBlank()) throw AlleycatException("agent name must not be blank")
                        AlleycatAgent(name = item)
                    }
                    is JSONObject -> {
                        val name = firstNonBlank(
                            item.optionalString("name"),
                            item.optionalString("id"),
                            item.optionalString("agent"),
                        ) ?: throw AlleycatException("agent entry missing name")
                        AlleycatAgent(name = name, raw = item)
                    }
                    else -> throw AlleycatException("invalid agent entry at index $i")
                }
            }
    }
}

class AlleycatHandshakeClient(private val pipe: JsonPipe) {
    constructor(transport: AlleycatTransport) : this(FramedJsonPipe(transport))

    fun exchange(request: HandshakeRequest): HandshakeResponse {
        pipe.sendJson(request.toJson())
        val json = pipe.receiveJson(AlleycatConnectSequence.HANDSHAKE_TIMEOUT_MS)
            ?: throw AlleycatException("Alleycat handshake timed out")
        return HandshakeResponse.parse(json)
    }
}
