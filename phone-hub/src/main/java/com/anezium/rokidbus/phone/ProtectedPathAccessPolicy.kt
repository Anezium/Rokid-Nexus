package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.PluginCapability

enum class ProtectedPathDirection {
    /** A plugin is trying to originate the envelope. */
    SEND,

    /** The hub is trying to deliver the envelope to a plugin registration. */
    RECEIVE,
}

object ProtectedPathAccessPolicy {
    fun isAllowed(
        path: String,
        isHubUid: Boolean,
        principal: PhonePluginPrincipal?,
        grantState: PluginGrantState?,
        direction: ProtectedPathDirection = ProtectedPathDirection.SEND,
    ): Boolean {
        val required = when {
            BusPaths.isProtectedCameraPath(path) -> PluginCapability.CAMERA
            BusPaths.isProtectedMediaSyncPath(path) -> PluginCapability.MEDIA_SYNC
            BusPaths.isProtectedVideoPath(path) -> PluginCapability.VIDEO_PLAYBACK
            else -> return true
        }
        if (isHubUid) return true
        // Photo sync's link offer, glasses config, manual trigger relay and status pushes are
        // hub-to-hub traffic. An approved plugin drives sync through /mediasync/settings and
        // /mediasync/now only; it can observe status but never forge any of it.
        if (direction == ProtectedPathDirection.SEND && BusPaths.isHubOnlyMediaSyncPath(path)) {
            return false
        }
        val approved = grantState as? PluginGrantState.Approved ?: return false
        return principal != null &&
            required in principal.descriptor.requestedCapabilities &&
            required in approved.capabilities
    }
}
