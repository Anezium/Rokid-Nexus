package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.PluginCapability

object ProtectedPathAccessPolicy {
    fun isAllowed(
        path: String,
        isHubUid: Boolean,
        principal: PhonePluginPrincipal?,
        grantState: PluginGrantState?,
    ): Boolean {
        if (!BusPaths.isProtectedCameraPath(path)) return true
        if (isHubUid) return true
        val approved = grantState as? PluginGrantState.Approved ?: return false
        return principal != null &&
            PluginCapability.CAMERA in principal.descriptor.requestedCapabilities &&
            PluginCapability.CAMERA in approved.capabilities
    }
}
