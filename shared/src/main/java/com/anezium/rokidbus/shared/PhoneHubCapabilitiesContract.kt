package com.anezium.rokidbus.shared

import org.json.JSONObject

data class PhoneHubCapabilities(
    val features: Int,
    val cameraConsumerName: String?,
)

/** Additive phone-to-glasses hub capabilities payload. Unknown fields remain ignorable. */
object PhoneHubCapabilitiesContract {
    const val VERSION = 1
    const val DEFAULT_CAMERA_LABEL = "Camera"
    const val MAX_CAMERA_CONSUMER_NAME_CHARS = 80

    fun create(features: Int, cameraConsumerName: String?): PhoneHubCapabilities {
        val ready = features and BusCapabilityBits.CAMERA_CONSUMER_READY != 0
        return PhoneHubCapabilities(
            features = features,
            cameraConsumerName = normalizeName(cameraConsumerName).takeIf { ready },
        )
    }

    fun toJson(capabilities: PhoneHubCapabilities): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("features", capabilities.features)
        .also { payload ->
            capabilities.cameraConsumerName?.let { payload.put("cameraConsumerName", it) }
        }

    fun parse(payload: JSONObject): PhoneHubCapabilities = create(
        features = payload.optInt("features", payload.optInt("capabilities", 0)),
        cameraConsumerName = payload.optString("cameraConsumerName", ""),
    )

    fun cameraLauncherLabel(capabilities: PhoneHubCapabilities): String =
        capabilities.cameraConsumerName ?: DEFAULT_CAMERA_LABEL

    private fun normalizeName(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_CAMERA_CONSUMER_NAME_CHARS }
}
