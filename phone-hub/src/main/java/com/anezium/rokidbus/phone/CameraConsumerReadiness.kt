package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.plugin.PluginCapability

class CameraConsumerReadiness(
    private val installedPrincipals: () -> List<PhonePluginPrincipal>,
    private val grantState: (PhonePluginPrincipal) -> PluginGrantState,
) {
    @Volatile private var approvedConsumer: PhonePluginPrincipal? = null

    @Synchronized
    fun recompute(): Boolean {
        val previous = approvedConsumer?.grantKey()
        approvedConsumer = installedPrincipals()
            .asSequence()
            .filter(::isApprovedCameraConsumer)
            .sortedWith(compareBy({ it.descriptor.id }, { it.packageName }))
            .firstOrNull()
        return previous != approvedConsumer?.grantKey()
    }

    fun isReady(): Boolean = approvedConsumer != null

    fun resolveApproved(): PhonePluginPrincipal? = approvedConsumer

    fun isApprovedCameraConsumer(principal: PhonePluginPrincipal): Boolean {
        if (PluginCapability.CAMERA !in principal.descriptor.requestedCapabilities) return false
        val state = grantState(principal) as? PluginGrantState.Approved ?: return false
        return PluginCapability.CAMERA in state.capabilities
    }
}
