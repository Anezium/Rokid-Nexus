package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.BusCapabilityBits

internal enum class CameraLinkStartupMode {
    P2P_FIRST,
    WAIT_FOR_LOHS_REVERSE,
}

/** Unknown and unsupported phone capability states deliberately retain P2P-first startup. */
internal object CameraLinkStartupModePolicy {
    fun select(phoneCapabilities: Int): CameraLinkStartupMode =
        if (phoneCapabilities and BusCapabilityBits.CAMERA_LOHS_REVERSE_REQUIRED != 0) {
            CameraLinkStartupMode.WAIT_FOR_LOHS_REVERSE
        } else {
            CameraLinkStartupMode.P2P_FIRST
        }
}

/** Pure bounded decision used when a promised reverse offer never arrives. */
internal class CameraLinkReverseOfferFallbackPolicy(
    val timeoutMs: Long,
) {
    init {
        require(timeoutMs > 0L)
    }

    fun shouldStartP2p(
        startupMode: CameraLinkStartupMode,
        reverseOfferAccepted: Boolean,
    ): Boolean =
        startupMode == CameraLinkStartupMode.WAIT_FOR_LOHS_REVERSE && !reverseOfferAccepted
}

internal const val SUPPORTED_PHONE_CAMERA_CAPABILITIES =
    BusCapabilityBits.CAMERA_CONSUMER_READY or
        BusCapabilityBits.CAMERA_FROZEN_SPP or
        BusCapabilityBits.CAMERA_LOHS_REVERSE_REQUIRED

internal fun supportedPhoneCameraCapabilities(features: Int): Int =
    features and SUPPORTED_PHONE_CAMERA_CAPABILITIES
