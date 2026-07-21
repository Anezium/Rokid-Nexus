package com.anezium.rokidbus.phone

import com.example.cxrglobal.GlassInfo
import org.json.JSONObject

internal object GlassesDeviceInfoPayload {
    fun create(info: GlassInfo, pluginId: String, eventId: String): JSONObject =
        JSONObject()
            .put("version", 1)
            .put("type", "glasses_device_info")
            .put("id", eventId)
            .put("pluginId", pluginId)
            .put("deviceName", info.deviceName)
            .put("batteryLevel", info.batteryLevel)
            .put("sound", info.sound)
            .put("brightness", info.brightness)
            .put("systemVersion", info.systemVersion)
            .put("isCharging", info.isCharging)
            .put("sn", info.sn)
            .put("wearingStatus", info.wearingStatus)
}
