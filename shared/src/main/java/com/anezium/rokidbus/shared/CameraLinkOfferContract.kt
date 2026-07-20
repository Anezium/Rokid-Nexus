package com.anezium.rokidbus.shared

import org.json.JSONObject

enum class CameraLinkMode(val wireValue: String) {
    P2P("p2p"),
    LOHS_REVERSE("lohs_reverse"),
    ;

    companion object {
        fun fromWireValue(value: String?): CameraLinkMode? = when (value.orEmpty()) {
            "", P2P.wireValue -> P2P
            LOHS_REVERSE.wireValue -> LOHS_REVERSE
            else -> null
        }
    }
}

data class CameraLinkEndpointOffer(
    val sessionId: String,
    val ssid: String,
    val passphrase: String,
    val port: Int,
    val token: String,
    val goIp: String? = null,
    val mode: CameraLinkMode = CameraLinkMode.P2P,
)

/** Bluetooth-bus offer contract; the TCP framing remains independent of transport roles. */
object CameraLinkOfferContract {
    const val VERSION = 1

    fun encode(offer: CameraLinkEndpointOffer): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("sessionId", offer.sessionId)
        .put("ssid", offer.ssid)
        .put("passphrase", offer.passphrase)
        .put("port", offer.port)
        .put("token", offer.token)
        .apply {
            offer.goIp?.takeIf { it.isNotBlank() }?.let { put("goIp", it) }
            if (offer.mode != CameraLinkMode.P2P) put("mode", offer.mode.wireValue)
        }

    fun decode(payload: JSONObject): CameraLinkEndpointOffer? {
        if (payload.optInt("version", VERSION) != VERSION) return null
        val mode = CameraLinkMode.fromWireValue(payload.optString("mode")) ?: return null
        val sessionId = payload.optString("sessionId")
        val ssid = payload.optString("ssid")
        val passphrase = payload.optString("passphrase")
        val token = payload.optString("token")
        val goIp = payload.optString("goIp").takeIf { it.isNotBlank() }
        val port = payload.optInt("port")
        if (sessionId.isBlank() || sessionId.length > 128 ||
            ssid.isBlank() || ssid.length > 128 ||
            passphrase.length !in 8..128 || token.length !in 16..256 ||
            port !in 1..65535 ||
            (goIp != null && goIp.length > 64) ||
            (mode == CameraLinkMode.P2P && goIp == null)
        ) return null
        return CameraLinkEndpointOffer(sessionId, ssid, passphrase, port, token, goIp, mode)
    }
}
