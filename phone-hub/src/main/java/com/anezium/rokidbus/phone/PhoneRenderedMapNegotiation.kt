package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.GlassesHubCapabilities
import com.anezium.rokidbus.shared.GlassesHubCapabilitiesContract
import com.anezium.rokidbus.shared.LauncherInteractiveContract
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.RenderedMapContract

/** Live rendered-map support; the launcher's separate offline negotiation is intentionally retained. */
internal class PhoneRenderedMapNegotiation {
    private var session: Any? = null
    private var announced = false

    @Synchronized
    fun announce(peer: GlassesHubCapabilities) {
        announced = peer.protocolVersion == GlassesHubCapabilitiesContract.VERSION &&
            RenderedMapContract.supported(peer.features)
    }

    @Synchronized
    fun sessionStarted(current: Any) {
        if (session != null && session !== current) announced = false
        session = current
    }

    @Synchronized
    fun lost() {
        session = null
        announced = false
    }

    @Synchronized
    fun featureBits(launcherVersion: Int?, linkState: Int): Int =
        if (announced && session != null && linkState and LinkStateBits.SPP_DATA_UP != 0 &&
            launcherVersion?.let(LauncherInteractiveContract::isActive) == true) BusCapabilityBits.RENDERED_MAP else 0
}
