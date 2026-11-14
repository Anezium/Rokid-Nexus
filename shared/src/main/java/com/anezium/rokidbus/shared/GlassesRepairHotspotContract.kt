package com.anezium.rokidbus.shared

import org.json.JSONObject

/** Transient recovery network between the two authenticated hubs; never plugin traffic. */
object GlassesRepairHotspotContract {
    const val PATH = "/core/repair/hotspot"
    const val CAPABILITY_FIELD = "hotspotRecoveryVersion"
    const val VERSION = 1
    const val TIMEOUT_MS = 180_000L
    private val ID = Regex("[a-f0-9]{32}")

    fun validId(id: String): Boolean = ID.matches(id)

    fun message(kind: String): JSONObject = JSONObject().put("version", VERSION).put("kind", kind)

    fun kind(payload: JSONObject): String? = payload.optString("kind").takeIf {
        payload.optInt("version") == VERSION && it in setOf("request", "offer", "release", "unavailable")
    }

    fun offer(credentials: GlassesFilesHotspotCredentials): JSONObject = message("offer")
        .put("ssid", credentials.ssid).put("passphrase", credentials.passphrase).put("security", credentials.security)

    fun credentials(payload: JSONObject): GlassesFilesHotspotCredentials? {
        if (kind(payload) != "offer") return null
        return GlassesFilesHotspotCredentials(
            payload.opt("ssid") as? String ?: return null,
            payload.opt("passphrase") as? String ?: return null,
            payload.opt("security") as? String ?: return null,
        ).takeIf { it.isValid() && it.security != "open" }
    }
}
