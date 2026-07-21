package com.anezium.rokidbus.shared

import java.util.UUID

object BusConstants {
    const val SERVICE_NAME = "RokidBus"
    const val SPP_UUID_STRING = "0b005957-ec6d-4af5-bcba-6c786c46634e"
    const val CXR_KEY = "rokidbus"
    const val ACTION_HUB = "com.anezium.rokidbus.action.HUB"
    const val ACTION_CLIENT = "com.anezium.rokidbus.action.CLIENT"
    const val ACTION_PLUGIN = "com.anezium.rokidbus.action.PLUGIN"
    const val META_DATA_PATHS = "com.anezium.rokidbus.paths"
    const val META_PLUGIN_ID = "com.anezium.rokidbus.plugin.ID"
    const val META_PLUGIN_DISPLAY_NAME = "com.anezium.rokidbus.plugin.DISPLAY_NAME"
    const val META_PLUGIN_ICON = "com.anezium.rokidbus.plugin.ICON"
    const val META_PLUGIN_ICON_DRAWABLE = "com.anezium.rokidbus.plugin.ICON_DRAWABLE"
    const val META_PLUGIN_API_VERSION = "com.anezium.rokidbus.plugin.API_VERSION"
    const val META_PLUGIN_CAPABILITIES = "com.anezium.rokidbus.plugin.CAPABILITIES"
    const val META_PLUGIN_RECEIVE_PREFIXES = "com.anezium.rokidbus.plugin.RECEIVE_PREFIXES"
    const val META_PLUGIN_SETTINGS_ACTIVITY = "com.anezium.rokidbus.plugin.SETTINGS_ACTIVITY"
    const val META_PLUGIN_LAUNCHABLE = "com.anezium.rokidbus.plugin.LAUNCHABLE"
    const val API_VERSION = 3
    const val CXR_CONTROL_MAX_BYTES = 3 * 1024
    val SPP_UUID: UUID = UUID.fromString(SPP_UUID_STRING)
}

object BusPaths {
    const val PROBE_ECHO = "/probe/echo"
    const val PROBE_HTTP = "/probe/http"
    const val PROBE_BIGDATA = "/probe/bigdata"
    const val PROBE_START_CLIENT = "/probe/start-client"
    const val PROBE_LAUNCH_ACTIVITY = "/probe/launch-activity"
    const val HTTP_REQUEST = "/http/request"
    const val HTTP_REPLY = "/http/request/reply"
    const val SURFACE_SHOW = "/surface/show"
    const val SURFACE_UPDATE = "/surface/update"
    const val SURFACE_HIDE = "/surface/hide"
    const val SURFACE_INPUT = "/surface/input"
    const val LAUNCHER_LIST = "/launcher/list"
    const val LAUNCHER_OPEN = "/launcher/open"
    const val CAMERA_SESSION_STATE = "/camera/session/state"
    const val CAMERA_LINK_OFFER = "/camera/link/offer"
    const val CAMERA_FREEZE_RESULT = "/camera/freeze/result"
    const val CAMERA_FREEZE_IMAGE_CHUNK = "/camera/freeze/image/chunk"
    const val CAMERA_FREEZE_IMAGE_ACK = "/camera/freeze/image/ack"
    const val CAMERA_OVERLAY = "/camera/overlay"
    const val GLASSES_WIFI_REQUEST = "/glasses/wifi/request"
    const val GLASSES_BRIGHTNESS_REQUEST = "/glasses/brightness/request"
    const val GLASSES_VOLUME_REQUEST = "/glasses/volume/request"
    const val GLASSES_DEVICE_INFO = "/glasses/device-info"
    const val PLUGIN_OPEN = "/system/plugin/open"
    const val PLUGIN_CLOSE = "/system/plugin/close"
    const val PLUGIN_INPUT = "/system/plugin/input"
    const val PLUGIN_REGISTRATION = "/system/plugin/registration"
    const val HUB_CAPABILITIES = "/system/hub/capabilities"
    const val ERROR = "/error"

    fun isProtectedCameraPath(path: String): Boolean =
        path == CAMERA_SESSION_STATE || path.startsWith("$CAMERA_SESSION_STATE/") ||
            path == CAMERA_LINK_OFFER || path.startsWith("$CAMERA_LINK_OFFER/") ||
            path == CAMERA_FREEZE_RESULT || path.startsWith("$CAMERA_FREEZE_RESULT/") ||
            path == CAMERA_FREEZE_IMAGE_CHUNK || path.startsWith("$CAMERA_FREEZE_IMAGE_CHUNK/") ||
            path == CAMERA_FREEZE_IMAGE_ACK || path.startsWith("$CAMERA_FREEZE_IMAGE_ACK/") ||
            path == CAMERA_OVERLAY || path.startsWith("$CAMERA_OVERLAY/")
}

object BusCapabilityBits {
    const val IMAGE_SURFACE = 1 shl 1
    const val CAMERA_CONSUMER_READY = 1 shl 2
    const val CAMERA_FROZEN_SPP = 1 shl 3
    const val CAMERA_LOHS_REVERSE_REQUIRED = 1 shl 4
}

object LinkStateBits {
    const val CXR_CONTROL_UP = 1
    const val SPP_DATA_UP = 2
    const val GLASSES_BT_BONDED_OR_PHONE_CONNECTED = 4
    const val GLASSES_WORN = 8
}
