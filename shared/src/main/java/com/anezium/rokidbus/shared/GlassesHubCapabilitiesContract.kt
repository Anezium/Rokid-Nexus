package com.anezium.rokidbus.shared

import org.json.JSONObject

data class GlassesHubCapabilities(
    val protocolVersion: Int,
    val features: Int,
    val imageSurfaceVersion: Int,
    val maxImageBytes: Int,
    val versionName: String?,
    val setupComplete: Boolean = false,
    val setupFailureState: String = "",
    val setupFailureDiagnostic: String = "",
)

/** Additive glasses-to-phone hub capabilities payload. Unknown fields remain ignorable. */
object GlassesHubCapabilitiesContract {
    const val VERSION = 1
    const val MAX_VERSION_NAME_CHARS = 80
    const val MAX_SETUP_FAILURE_STATE_CHARS = 96
    const val MAX_SETUP_FAILURE_DIAGNOSTIC_CHARS = 96

    fun create(
        features: Int,
        imageSurfaceVersion: Int,
        maxImageBytes: Int,
        versionName: String?,
        setupComplete: Boolean = false,
        setupFailureState: String = "",
        setupFailureDiagnostic: String = "",
    ): GlassesHubCapabilities = GlassesHubCapabilities(
        protocolVersion = VERSION,
        features = features,
        imageSurfaceVersion = imageSurfaceVersion,
        maxImageBytes = maxImageBytes,
        versionName = normalizeVersionName(versionName),
        setupComplete = setupComplete,
        setupFailureState = normalizeFailureState(setupFailureState),
        setupFailureDiagnostic = normalizeFailureDiagnostic(setupFailureDiagnostic),
    )

    fun toJson(capabilities: GlassesHubCapabilities): JSONObject = JSONObject()
        .put("version", capabilities.protocolVersion)
        .put("features", capabilities.features)
        .put("imageSurfaceVersion", capabilities.imageSurfaceVersion)
        .put("maxImageBytes", capabilities.maxImageBytes)
        .put("setupComplete", capabilities.setupComplete)
        .put("setupFailureState", capabilities.setupFailureState)
        .put("setupFailureDiagnostic", capabilities.setupFailureDiagnostic)
        .also { payload ->
            capabilities.versionName?.let { payload.put("versionName", it) }
        }

    fun parse(payload: JSONObject): GlassesHubCapabilities = GlassesHubCapabilities(
        protocolVersion = payload.optInt("version", 0),
        features = payload.optInt("features", 0),
        imageSurfaceVersion = payload.optInt("imageSurfaceVersion", 0),
        maxImageBytes = payload.optInt("maxImageBytes", 0),
        versionName = normalizeVersionName(payload.optString("versionName", "")),
        setupComplete = payload.optBoolean("setupComplete", false),
        setupFailureState = normalizeFailureState(payload.optString("setupFailureState", "")),
        setupFailureDiagnostic = normalizeFailureDiagnostic(
            payload.optString("setupFailureDiagnostic", ""),
        ),
    )

    private fun normalizeVersionName(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_VERSION_NAME_CHARS }

    private fun normalizeFailureState(value: String?): String = value
        .orEmpty()
        .trim()
        .take(MAX_SETUP_FAILURE_STATE_CHARS)

    /** Defense in depth for data that a phone UI may display or persist later. */
    private fun normalizeFailureDiagnostic(value: String?): String = value
        .orEmpty()
        .replace(STANDALONE_PAIRING_CODE, "......")
        .replace(IPV4_LITERAL, "")
        .replace(DIAGNOSTIC_WHITESPACE, " ")
        .trim()
        .take(MAX_SETUP_FAILURE_DIAGNOSTIC_CHARS)

    private val STANDALONE_PAIRING_CODE = Regex("""\b\d{6}\b""")
    private val IPV4_LITERAL = Regex("""\d+\.\d+\.\d+\.\d+""")
    private val DIAGNOSTIC_WHITESPACE = Regex("""\s+""")
}
