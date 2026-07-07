package com.anezium.rokidbus.shared

import java.util.UUID

object BusConstants {
    const val SERVICE_NAME = "RokidBus"
    const val SPP_UUID_STRING = "0b005957-ec6d-4af5-bcba-6c786c46634e"
    const val CXR_KEY = "rokidbus"
    const val ACTION_HUB = "com.anezium.rokidbus.action.HUB"
    const val ACTION_CLIENT = "com.anezium.rokidbus.action.CLIENT"
    const val META_DATA_PATHS = "com.anezium.rokidbus.paths"
    const val API_VERSION = 2
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
    const val ERROR = "/error"
}

object LinkStateBits {
    const val CXR_CONTROL_UP = 1
    const val SPP_DATA_UP = 2
    const val GLASSES_BT_BONDED_OR_PHONE_CONNECTED = 4
}
