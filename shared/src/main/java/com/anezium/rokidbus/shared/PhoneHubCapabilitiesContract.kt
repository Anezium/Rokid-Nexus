package com.anezium.rokidbus.shared

import org.json.JSONObject

data class PhoneHubCapabilities(
    val features: Int,
    val cameraConsumerName: String?,
    val activityAlwaysExpanded: Boolean = false,
    val hudTopInsetDp: Int = 0,
    val hudPositionAuto: Boolean = true,
    /** Session/page protocol version; only [PageSurfaceContract.VERSION] on both hubs enables pages. */
    val pageSessionVersion: Int = 0,
)

/** Additive phone-to-glasses hub capabilities payload. Unknown fields remain ignorable. */
object PhoneHubCapabilitiesContract {
    const val VERSION = 1
    const val MAX_CAMERA_CONSUMER_NAME_CHARS = 80
    const val MIN_HUD_TOP_INSET_DP = 0
    const val MAX_HUD_TOP_INSET_DP = 120
    const val DEFAULT_HUD_TOP_INSET_DP = 0
    const val DEFAULT_HUD_POSITION_AUTO = true

    fun create(
        features: Int,
        cameraConsumerName: String?,
        activityAlwaysExpanded: Boolean = false,
        hudTopInsetDp: Int = DEFAULT_HUD_TOP_INSET_DP,
        hudPositionAuto: Boolean = DEFAULT_HUD_POSITION_AUTO,
        pageSessionVersion: Int = 0,
    ): PhoneHubCapabilities {
        val ready = features and BusCapabilityBits.CAMERA_CONSUMER_READY != 0
        return PhoneHubCapabilities(
            features = features,
            cameraConsumerName = normalizeName(cameraConsumerName).takeIf { ready },
            activityAlwaysExpanded = activityAlwaysExpanded,
            hudTopInsetDp = sanitizeHudTopInsetDp(hudTopInsetDp),
            hudPositionAuto = hudPositionAuto,
            pageSessionVersion = PageSurfaceContract.sanitizeSessionVersion(pageSessionVersion),
        )
    }

    fun toJson(capabilities: PhoneHubCapabilities): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("features", capabilities.features)
        .put("activityAlwaysExpanded", capabilities.activityAlwaysExpanded)
        .put("hudTopInsetDp", sanitizeHudTopInsetDp(capabilities.hudTopInsetDp))
        .put("hudPositionAuto", capabilities.hudPositionAuto)
        .put(
            PageSurfaceContract.CAPABILITY_FIELD_PAGE_SESSION_VERSION,
            PageSurfaceContract.sanitizeSessionVersion(capabilities.pageSessionVersion),
        )
        .also { payload ->
            capabilities.cameraConsumerName?.let { payload.put("cameraConsumerName", it) }
        }

    fun parse(payload: JSONObject): PhoneHubCapabilities = create(
        features = payload.optInt("features", payload.optInt("capabilities", 0)),
        cameraConsumerName = payload.optString("cameraConsumerName", ""),
        activityAlwaysExpanded = payload.optBoolean("activityAlwaysExpanded", false),
        hudTopInsetDp = parseHudTopInsetDp(payload.opt("hudTopInsetDp")),
        hudPositionAuto = payload.optBoolean(
            "hudPositionAuto",
            DEFAULT_HUD_POSITION_AUTO,
        ),
        pageSessionVersion = PageSurfaceContract.parseSessionVersion(
            payload.opt(PageSurfaceContract.CAPABILITY_FIELD_PAGE_SESSION_VERSION),
        ),
    )

    fun sanitizeHudTopInsetDp(value: Int): Int =
        value.coerceIn(MIN_HUD_TOP_INSET_DP, MAX_HUD_TOP_INSET_DP)

    private fun parseHudTopInsetDp(value: Any?): Int = when (value) {
        is Int -> sanitizeHudTopInsetDp(value)
        is Long -> value.coerceIn(
            MIN_HUD_TOP_INSET_DP.toLong(),
            MAX_HUD_TOP_INSET_DP.toLong(),
        ).toInt()
        else -> DEFAULT_HUD_TOP_INSET_DP
    }

    private fun normalizeName(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_CAMERA_CONSUMER_NAME_CHARS }
}
