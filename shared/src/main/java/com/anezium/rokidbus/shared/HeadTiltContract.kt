package com.anezium.rokidbus.shared

import org.json.JSONObject

/** Private hub-to-hub settings. No plugin owns the sensor or chooses the gesture's destination. */
data class HeadTiltSettings(
    val enabled: Boolean = false,
    val angleDegrees: Int = 20,
    val holdMillis: Int = 500,
    val pluginId: String = "",
)

object HeadTiltContract {
    const val ROOT = "/core/head-tilt"
    const val CONFIG_PATH = "$ROOT/config"
    const val REQUEST_PATH = "$ROOT/request"
    const val STATUS_PATH = "$ROOT/status"
    const val VERSION = 1
    const val PREVIEW_MILLIS = 30_000L
    const val MIN_ANGLE = 10
    const val MAX_ANGLE = 40
    const val MIN_HOLD = 300
    const val MAX_HOLD = 1_000

    fun toJson(settings: HeadTiltSettings): JSONObject = JSONObject()
        .put("v", VERSION).put("enabled", settings.enabled)
        .put("angleDegrees", settings.angleDegrees).put("holdMillis", settings.holdMillis)
        .put("pluginId", settings.pluginId)

    fun parse(payload: JSONObject): HeadTiltSettings? {
        if (payload.optInt("v") != VERSION || payload.opt("enabled") !is Boolean) return null
        val angle = payload.opt("angleDegrees") as? Number ?: return null
        val hold = payload.opt("holdMillis") as? Number ?: return null
        val plugin = payload.opt("pluginId") as? String ?: return null
        if (angle.toDouble() != angle.toInt().toDouble() || angle.toInt() !in MIN_ANGLE..MAX_ANGLE ||
            hold.toDouble() != hold.toInt().toDouble() || hold.toInt() !in MIN_HOLD..MAX_HOLD ||
            plugin.length > 128 || plugin.any { it.isWhitespace() || it.isISOControl() }
        ) return null
        return HeadTiltSettings(payload.getBoolean("enabled"), angle.toInt(), hold.toInt(), plugin)
    }
}
