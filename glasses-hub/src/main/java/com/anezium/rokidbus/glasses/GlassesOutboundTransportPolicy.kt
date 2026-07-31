package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.BusConstants

internal enum class GlassesOutboundTransport {
    SPP,
    CXR,
}

internal object GlassesOutboundTransportPolicy {
    fun order(
        sppConnected: Boolean,
        cxrUp: Boolean,
        payloadBytes: Int,
    ): List<GlassesOutboundTransport> = buildList {
        // CXR can report a successful send even when its phone-side callback is not delivering.
        // A live SPP socket has end-to-end framing and is therefore the reliable first choice.
        if (sppConnected) add(GlassesOutboundTransport.SPP)
        if (cxrUp && payloadBytes <= BusConstants.CXR_CONTROL_MAX_BYTES) {
            add(GlassesOutboundTransport.CXR)
        }
    }
}
