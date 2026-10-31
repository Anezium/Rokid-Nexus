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
    /** The launcher bundle version; see [LauncherInteractiveContract]. Absent reads as 0. */
    val launcherInteractiveVersion: Int = 0,
    /**
     * The launcher backend the user chose explicitly on the phone, one of
     * [LauncherInteractiveContract.backends]; null when the user expressed no choice.
     */
    val launcherBackend: String? = null,
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
        launcherInteractiveVersion: Int = 0,
        launcherBackend: String? = null,
    ): PhoneHubCapabilities {
        val ready = features and BusCapabilityBits.CAMERA_CONSUMER_READY != 0
        return PhoneHubCapabilities(
            features = features,
            cameraConsumerName = normalizeName(cameraConsumerName).takeIf { ready },
            activityAlwaysExpanded = activityAlwaysExpanded,
            hudTopInsetDp = sanitizeHudTopInsetDp(hudTopInsetDp),
            hudPositionAuto = hudPositionAuto,
            pageSessionVersion = PageSurfaceContract.sanitizeSessionVersion(pageSessionVersion),
            launcherInteractiveVersion = LauncherInteractiveContract.sanitizeVersion(launcherInteractiveVersion),
            launcherBackend = LauncherInteractiveContract.parseBackend(launcherBackend),
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
        .put(
            LauncherInteractiveContract.CAPABILITY_FIELD_VERSION,
            LauncherInteractiveContract.sanitizeVersion(capabilities.launcherInteractiveVersion),
        )
        .also { payload ->
            capabilities.cameraConsumerName?.let { payload.put("cameraConsumerName", it) }
            LauncherInteractiveContract.parseBackend(capabilities.launcherBackend)?.let {
                payload.put(LauncherInteractiveContract.CAPABILITY_FIELD_LAUNCHER_BACKEND, it)
            }
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
        launcherInteractiveVersion = LauncherInteractiveContract.parseVersion(
            payload.opt(LauncherInteractiveContract.CAPABILITY_FIELD_VERSION),
        ),
        launcherBackend = LauncherInteractiveContract.parseBackend(
            payload.opt(LauncherInteractiveContract.CAPABILITY_FIELD_LAUNCHER_BACKEND),
        ),
    )

    /** The launcher bundle version a hub of [localVersion] runs with this phone; 0 means none. */
    fun negotiatedLauncherInteractiveVersion(
        capabilities: PhoneHubCapabilities,
        localVersion: Int = LauncherInteractiveContract.VERSION,
    ): Int = LauncherInteractiveContract.negotiated(localVersion, capabilities.launcherInteractiveVersion)

    fun supportsLauncherInteractive(capabilities: PhoneHubCapabilities): Boolean =
        LauncherInteractiveContract.isActive(negotiatedLauncherInteractiveVersion(capabilities))

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
