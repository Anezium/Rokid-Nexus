package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.GlassesHubCapabilities
import com.anezium.rokidbus.shared.GlassesHubCapabilitiesContract
import com.anezium.rokidbus.shared.LauncherInteractiveContract
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.RenderedMapContract

/** Live rendered-map support; the launcher's separate offline negotiation is intentionally retained. */
internal class PhoneRenderedMapNegotiation {
    sealed interface Origin {
        class Spp(val session: Any) : Origin
        class Cxr(val generation: Any) : Origin
    }

    private var session: Any? = null
    private var cxrGeneration: Any? = null
    private var announced = false

    @Synchronized
    fun announce(peer: GlassesHubCapabilities, origin: Origin?) {
        val current = when (origin) {
            is Origin.Spp -> session === origin.session
            is Origin.Cxr -> cxrGeneration === origin.generation
            null -> false
        }
        if (!current) return
        announced = peer.protocolVersion == GlassesHubCapabilitiesContract.VERSION &&
            RenderedMapContract.supported(peer.features)
    }

    @Synchronized
    fun cxrConnectionChanged(connected: Boolean) {
        if (connected) {
            if (cxrGeneration == null) cxrGeneration = Any()
        } else {
            cxrGeneration = null
            if (session == null) announced = false
        }
    }

    @Synchronized
    fun cxrOrigin(): Origin? = cxrGeneration?.let { Origin.Cxr(it) }

    @Synchronized
    fun sessionStarted(current: Any) {
        if (session != null && session !== current) {
            announced = false
            if (cxrGeneration != null) cxrGeneration = Any()
        }
        session = current
    }

    @Synchronized
    fun lost() {
        session = null
        announced = false
        // A callback captured before retirement cannot become a new CXR-first announcement.
        if (cxrGeneration != null) cxrGeneration = Any()
    }

    @Synchronized
    fun featureBits(launcherVersion: Int?, linkState: Int): Int =
        if (announced && session != null && linkState and LinkStateBits.SPP_DATA_UP != 0 &&
            launcherVersion?.let(LauncherInteractiveContract::isActive) == true) BusCapabilityBits.RENDERED_MAP else 0
}
