package com.anezium.rokidbus.plugin.agents

import com.anezium.rokidbus.plugin.agents.alleycat.PairingPayload
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * A kittylitter-paired Alleycat node. The bearer token is never stored on
 * this object — it lives only in [AlleycatSecretStore].
 */
data class AlleycatComputer(
    val computerId: String,
    val nodeId: String,
    val name: String,
    val relay: String? = null,
    val selectedAgent: String? = null,
    val lastSeq: Long? = null,
    val advertisedAgents: List<String> = emptyList(),
    val needsRePair: Boolean = false,
    val lastSeenAtMs: Long? = null,
) {
    fun asListed(): TrustedMachine = TrustedMachine(computerId, name, lastSeenAtMs)

    fun asCodexRef(): CodexComputerRef = CodexComputerRef(computerId, name)

    /** Public fields only. Never includes a token. */
    fun toPublicJson(): JSONObject = JSONObject()
        .put("computerId", computerId)
        .put("nodeId", nodeId)
        .put("name", name)
        .put("relay", relay ?: JSONObject.NULL)
        .put("selectedAgent", selectedAgent ?: JSONObject.NULL)
        .put("lastSeq", lastSeq ?: JSONObject.NULL)
        .put("needsRePair", needsRePair)
        .put("advertisedAgents", JSONArray(advertisedAgents))
        .put("lastSeenAtMs", lastSeenAtMs ?: JSONObject.NULL)

    override fun toString(): String =
        "AlleycatComputer(computerId=$computerId, nodeId=$nodeId, name=$name, " +
            "relay=$relay, selectedAgent=$selectedAgent, lastSeq=$lastSeq, " +
            "needsRePair=$needsRePair, advertisedAgents=$advertisedAgents)"

    companion object {
        const val ID_PREFIX = "alleycat-"

        fun fromPayload(payload: PairingPayload): AlleycatComputer {
            val name = payload.displayName?.take(MAX_HUD_LABEL_CHARS)
                ?: shortNodeId(payload.nodeId)
            return AlleycatComputer(
                computerId = computerIdFor(payload.nodeId),
                nodeId = payload.nodeId,
                name = name,
                relay = payload.relay,
            )
        }

        fun fromPublicJson(json: JSONObject): AlleycatComputer? {
            val computerId = json.optString("computerId").takeIf { it.isNotBlank() } ?: return null
            val nodeId = json.optString("nodeId").takeIf { it.isNotBlank() } ?: return null
            if (!computerId.startsWith(ID_PREFIX)) return null
            val agents = json.optJSONArray("advertisedAgents")
            val advertised = buildList {
                if (agents != null) {
                    for (i in 0 until agents.length()) {
                        agents.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }
            return AlleycatComputer(
                computerId = computerId,
                nodeId = nodeId,
                name = json.optString("name").takeIf { it.isNotBlank() } ?: shortNodeId(nodeId),
                relay = json.optString("relay").takeIf { it.isNotBlank() && it != "null" },
                selectedAgent = json.optString("selectedAgent")
                    .takeIf { it.isNotBlank() && it != "null" },
                lastSeq = json.optLong("lastSeq")
                    .takeIf { json.has("lastSeq") && !json.isNull("lastSeq") },
                advertisedAgents = advertised,
                needsRePair = json.optBoolean("needsRePair", false),
                lastSeenAtMs = json.optLong("lastSeenAtMs")
                    .takeIf { json.has("lastSeenAtMs") && !json.isNull("lastSeenAtMs") && it > 0L },
            )
        }

        fun computerIdFor(nodeId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(nodeId.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }
            return ID_PREFIX + hex.take(16)
        }

        fun shortNodeId(nodeId: String): String =
            if (nodeId.length <= 12) nodeId else "${nodeId.take(6)}…${nodeId.takeLast(4)}"
    }
}
