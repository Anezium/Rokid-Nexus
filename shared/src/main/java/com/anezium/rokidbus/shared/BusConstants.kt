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

    /**
     * A string-array resource of `name|pathData` entries: the plugin's own HUD
     * glyphs, which the hub reads cross-package and forwards to the glasses.
     * See [GlyphContract].
     */
    const val META_PLUGIN_GLYPHS = "com.anezium.rokidbus.plugin.GLYPHS"
    const val META_PLUGIN_API_VERSION = "com.anezium.rokidbus.plugin.API_VERSION"
    const val META_PLUGIN_CAPABILITIES = "com.anezium.rokidbus.plugin.CAPABILITIES"
    const val META_PLUGIN_RECEIVE_PREFIXES = "com.anezium.rokidbus.plugin.RECEIVE_PREFIXES"
    const val META_PLUGIN_SETTINGS_ACTIVITY = "com.anezium.rokidbus.plugin.SETTINGS_ACTIVITY"
    const val META_PLUGIN_LAUNCHABLE = "com.anezium.rokidbus.plugin.LAUNCHABLE"
    const val META_PLUGIN_GUARDIAN_SERVICE = "com.anezium.rokidbus.plugin.GUARDIAN_SERVICE"
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
    const val INK_SHOW = "/ink/show"
    const val INK_UPDATE = "/ink/update"
    const val INK_HIDE = "/ink/hide"
    const val INK_EVENT = "/ink/event"
    const val PIN_SHOW = "/pin/show"
    const val PIN_HIDE = "/pin/hide"
    const val NOTICE_SHOW = "/notice/show"
    const val NOTICE_UPDATE = "/notice/update"
    const val NOTICE_HIDE = "/notice/hide"
    const val NOTICE_INPUT = "/notice/input"
    const val NOTICE_ACTION = "/notice/action"
    const val NOTICE_TEXT_SUBMIT = "/notice/text-submit"
    const val NOTICE_CLOSED = "/notice/closed"
    const val ACTIVITY_START = "/activity/start"
    const val ACTIVITY_UPDATE = "/activity/update"
    const val ACTIVITY_END = "/activity/end"
    const val ACTIVITY_ACTION = "/activity/action"
    const val ACTIVITY_CLOSED = "/activity/closed"
    const val TTS_SPEAK = "/tts/speak"
    const val TTS_STOP = "/tts/stop"

    /** Phone hub to glasses hub only; plugins cannot send or subscribe to this path. */
    const val TTS_CANCEL = "/tts/cancel"
    const val TTS_STARTED = "/tts/started"
    const val TTS_DONE = "/tts/done"
    const val LAUNCHER_LIST = "/launcher/list"
    const val LAUNCHER_GLYPHS = "/launcher/glyphs"
    const val LAUNCHER_OPEN = "/launcher/open"
    const val CAMERA_SESSION_STATE = "/camera/session/state"
    const val CAMERA_LINK_OFFER = "/camera/link/offer"
    const val CAMERA_FREEZE_RESULT = "/camera/freeze/result"
    const val CAMERA_FREEZE_IMAGE_CHUNK = "/camera/freeze/image/chunk"
    const val CAMERA_FREEZE_IMAGE_ACK = "/camera/freeze/image/ack"
    const val CAMERA_OVERLAY = "/camera/overlay"
    const val CAMERA_SNAPSHOT_REQUEST = "/camera/snapshot/request"
    const val CAMERA_SNAPSHOT_RESULT = "/camera/snapshot/result"
    const val CAMERA_SNAPSHOT_ERROR = "/camera/snapshot/error"
    const val MEDIA_SYNC_STATUS = "/mediasync/status"
    const val MEDIA_SYNC_SETTINGS = "/mediasync/settings"
    const val MEDIA_SYNC_NOW = "/mediasync/now"
    const val MEDIA_SYNC_CONFIG = "/mediasync/config"
    const val MEDIA_SYNC_CONFIG_REQUEST = "/mediasync/config/request"
    const val MEDIA_SYNC_TRIGGER = "/mediasync/trigger"
    const val MEDIA_SYNC_STATE = "/mediasync/state"

    /** Data plane. Chunks ride here as binary envelopes over SPP; see MediaSyncTransferContract. */
    const val MEDIA_SYNC_XFER_PREFIX = "/mediasync/xfer"
    const val MEDIA_SYNC_XFER_CATALOG_REQUEST = "/mediasync/xfer/catalog/request"
    const val MEDIA_SYNC_XFER_CATALOG = "/mediasync/xfer/catalog"
    const val MEDIA_SYNC_XFER_FILE_REQUEST = "/mediasync/xfer/file/request"
    const val MEDIA_SYNC_XFER_FILE_BEGIN = "/mediasync/xfer/file/begin"
    const val MEDIA_SYNC_XFER_FILE_CHUNK = "/mediasync/xfer/file/chunk"
    const val MEDIA_SYNC_XFER_FILE_PROGRESS = "/mediasync/xfer/file/progress"
    const val MEDIA_SYNC_XFER_FILE_END = "/mediasync/xfer/file/end"
    const val MEDIA_SYNC_XFER_FILE_ACK = "/mediasync/xfer/file/ack"
    const val MEDIA_SYNC_XFER_FILE_ERROR = "/mediasync/xfer/file/error"
    const val MEDIA_SYNC_XFER_DELETE_RESULT = "/mediasync/xfer/delete"
    const val MEDIA_SYNC_XFER_ABORT = "/mediasync/xfer/abort"
    const val MEDIA_SYNC_XFER_BYE = "/mediasync/xfer/bye"

    /** Our own bulk traffic, which the politeness layer must not mistake for somebody else's. */
    fun isMediaSyncTransferPath(path: String): Boolean =
        path == MEDIA_SYNC_XFER_PREFIX || path.startsWith("$MEDIA_SYNC_XFER_PREFIX/")
    const val GLASSES_WIFI_REQUEST = "/glasses/wifi/request"
    const val GLASSES_SELFARM_MANUAL = "/glasses/selfarm/manual"
    const val GLASSES_SELFARM_MANUAL_REPLY = "/glasses/selfarm/manual/reply"
    const val GLASSES_SETUP_NOTE = "/glasses/setup/note"
    const val GLASSES_SETUP_PAIRING_OFFER = "/glasses/setup/pairing/offer"
    const val GLASSES_SETUP_PAIRING_RESULT = "/glasses/setup/pairing/result"
    const val GLASSES_BRIGHTNESS_REQUEST = "/glasses/brightness/request"
    const val GLASSES_VOLUME_REQUEST = "/glasses/volume/request"
    const val GLASSES_DEVICE_INFO = "/glasses/device-info"
    const val WIRELESS_ADB_REQUEST = "/debug/adb/request"
    const val WIRELESS_ADB_REPLY = "/debug/adb/reply"

    /**
     * Phone hub to glasses hub only: the owner's switch for the boot-time repair of the
     * privileged helper, and the on-demand "repair now" with its reply. See
     * [GlassesRepairContract] for why the switch is persisted on the glasses.
     */
    const val GLASSES_REPAIR_CONFIG = "/glasses/repair/config"
    const val GLASSES_REPAIR_REQUEST = "/glasses/repair/request"
    const val GLASSES_REPAIR_REPLY = "/glasses/repair/reply"

    /**
     * Phone hub to glasses hub only: arm the native-assistant dismiss so an approved plugin
     * holding the ASSISTANT capability can replace Rokid's assistant with its own surface.
     * The gesture is consumed inside the ROM and never reaches our accessibility service, so the
     * phone — which the ROM does notify — is the only place that can tell the glasses to act.
     */
    const val GLASSES_ASSISTANT_DISMISS = "/glasses/assistant/dismiss"

    /** Phone hub to glasses hub only; see [PhoneBatteryContract] for why it is not a plugin path. */
    const val PHONE_BATTERY = "/phone/battery"
    const val PLUGIN_OPEN = "/system/plugin/open"
    const val PLUGIN_CLOSE = "/system/plugin/close"
    const val PLUGIN_INPUT = "/system/plugin/input"
    const val PLUGIN_REGISTRATION = "/system/plugin/registration"
    const val HUB_CAPABILITIES = "/system/hub/capabilities"
    const val ERROR = "/error"

    /**
     * Every `/mediasync/…` path is capability-protected: photo sync moves the wearer's private
     * captures, so an unapproved plugin must not even observe the traffic.
     */
    fun isProtectedMediaSyncPath(path: String): Boolean =
        path == "/mediasync" || path.startsWith("/mediasync/")

    /**
     * The media-sync paths that only the two hubs may originate. An approved photo-sync plugin
     * drives sync through [MEDIA_SYNC_SETTINGS] and [MEDIA_SYNC_NOW]; it can never forge a link
     * offer, a glasses config push, or a status update.
     */
    fun isHubOnlyMediaSyncPath(path: String): Boolean =
        isProtectedMediaSyncPath(path) &&
            !matchesMediaSyncPath(path, MEDIA_SYNC_SETTINGS) &&
            !matchesMediaSyncPath(path, MEDIA_SYNC_NOW)

    private fun matchesMediaSyncPath(path: String, base: String): Boolean =
        path == base || path.startsWith("$base/")

    fun isProtectedCameraPath(path: String): Boolean =
        path == CAMERA_SESSION_STATE || path.startsWith("$CAMERA_SESSION_STATE/") ||
            path == CAMERA_LINK_OFFER || path.startsWith("$CAMERA_LINK_OFFER/") ||
            path == CAMERA_FREEZE_RESULT || path.startsWith("$CAMERA_FREEZE_RESULT/") ||
            path == CAMERA_FREEZE_IMAGE_CHUNK || path.startsWith("$CAMERA_FREEZE_IMAGE_CHUNK/") ||
            path == CAMERA_FREEZE_IMAGE_ACK || path.startsWith("$CAMERA_FREEZE_IMAGE_ACK/") ||
            path == CAMERA_OVERLAY || path.startsWith("$CAMERA_OVERLAY/") ||
            path == CAMERA_SNAPSHOT_REQUEST || path.startsWith("$CAMERA_SNAPSHOT_REQUEST/") ||
            path == CAMERA_SNAPSHOT_RESULT || path.startsWith("$CAMERA_SNAPSHOT_RESULT/") ||
            path == CAMERA_SNAPSHOT_ERROR || path.startsWith("$CAMERA_SNAPSHOT_ERROR/")
}

object BusCapabilityBits {
    const val IMAGE_SURFACE = 1 shl 1
    const val CAMERA_CONSUMER_READY = 1 shl 2
    const val CAMERA_FROZEN_SPP = 1 shl 3
    const val CAMERA_LOHS_REVERSE_REQUIRED = 1 shl 4
    const val PIN_SURFACE = 1 shl 5
    const val NOTICE_SURFACE = 1 shl 6
    const val ACTIVITY_SURFACE = 1 shl 7
    const val PHONE_ASSISTED_SETUP = 1 shl 8
    const val TTS = 1 shl 9
    const val INK_SURFACE = 1 shl 10
    const val NOTICE_TEXT_INPUT = 1 shl 11
}

object LinkStateBits {
    const val CXR_CONTROL_UP = 1
    const val SPP_DATA_UP = 2
    const val GLASSES_BT_BONDED_OR_PHONE_CONNECTED = 4
    const val GLASSES_WORN = 8
}
