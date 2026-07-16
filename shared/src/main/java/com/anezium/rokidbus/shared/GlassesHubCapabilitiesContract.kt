package com.anezium.rokidbus.shared

import org.json.JSONObject

data class GlassesHubCapabilities(
    val protocolVersion: Int,
    val features: Int,
    val imageSurfaceVersion: Int,
    val maxImageBytes: Int,
    val versionName: String?,
)

/** Additive glasses-to-phone hub capabilities payload. Unknown fields remain ignorable. */
object GlassesHubCapabilitiesContract {
    const val VERSION = 1
    const val MAX_VERSION_NAME_CHARS = 80

    fun create(
        features: Int,
        imageSurfaceVersion: Int,
        maxImageBytes: Int,
        versionName: String?,
    ): GlassesHubCapabilities = GlassesHubCapabilities(
        protocolVersion = VERSION,
        features = features,
        imageSurfaceVersion = imageSurfaceVersion,
        maxImageBytes = maxImageBytes,
        versionName = normalizeVersionName(versionName),
    )

    fun toJson(capabilities: GlassesHubCapabilities): JSONObject = JSONObject()
        .put("version", capabilities.protocolVersion)
        .put("features", capabilities.features)
        .put("imageSurfaceVersion", capabilities.imageSurfaceVersion)
        .put("maxImageBytes", capabilities.maxImageBytes)
        .also { payload ->
            capabilities.versionName?.let { payload.put("versionName", it) }
        }

    fun parse(payload: JSONObject): GlassesHubCapabilities = GlassesHubCapabilities(
        protocolVersion = payload.optInt("version", 0),
        features = payload.optInt("features", 0),
        imageSurfaceVersion = payload.optInt("imageSurfaceVersion", 0),
        maxImageBytes = payload.optInt("maxImageBytes", 0),
        versionName = normalizeVersionName(payload.optString("versionName", "")),
    )

    private fun normalizeVersionName(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_VERSION_NAME_CHARS }
}
