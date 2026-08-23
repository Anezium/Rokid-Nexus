package com.anezium.rokidbus.plugin.agents.alleycat

import org.json.JSONException
import org.json.JSONObject

/**
 * QR / paste payload from `kittylitter pair --qr`.
 *
 * `token` is a bearer secret: it is never included in [toString] and must
 * never be logged.
 */
data class PairingPayload(
    val nodeId: String,
    val token: String,
    val relay: String? = null,
    val displayName: String? = null,
) {
    override fun toString(): String =
        "PairingPayload(nodeId=$nodeId, relay=$relay, displayName=$displayName, token=<redacted>)"

    companion object {
        const val VERSION: Int = 1
        private val TOKEN_HEX = Regex("^[0-9a-fA-F]{64}$")

        fun parse(raw: String): PairingPayload {
            val json = try {
                JSONObject(raw)
            } catch (e: JSONException) {
                throw AlleycatException("pairing payload is not JSON", e)
            }
            return parse(json)
        }

        fun parse(json: JSONObject): PairingPayload {
            val version = json.requiredInt("v")
            if (version != VERSION) {
                throw AlleycatException("unsupported pairing version $version (expected $VERSION)")
            }
            val nodeId = json.requiredString("node_id")
            val token = json.requiredString("token")
            if (!TOKEN_HEX.matches(token)) {
                throw AlleycatException("pairing token must be 32-byte hex")
            }
            val relay = json.optionalString("relay")
            val displayName = firstNonBlank(
                json.optionalString("host_name"),
                json.optionalString("hostname"),
                json.optionalString("display_name"),
            )
            return PairingPayload(
                nodeId = nodeId,
                token = token,
                relay = relay,
                displayName = displayName,
            )
        }
    }
}
