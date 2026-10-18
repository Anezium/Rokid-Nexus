package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.GlassesHubCapabilitiesContract
import com.anezium.rokidbus.shared.LauncherInteractiveContract
import com.anezium.rokidbus.shared.LinkStateBits
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneRenderedMapNegotiationTest {
    private val launcher = LauncherInteractiveContract.VERSION
    private val up = LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP
    private fun announce(current: Boolean) = GlassesHubCapabilitiesContract.parse(GlassesHubCapabilitiesContract.toJson(
        GlassesHubCapabilitiesContract.create(
            features = BusCapabilityBits.LAUNCHER_INTERACTIVE or if (current) BusCapabilityBits.RENDERED_MAP else 0,
            imageSurfaceVersion = 0, maxImageBytes = 0, versionName = null, launcherInteractiveVersion = launcher,
        ),
    ))

    @Test
    fun `current support waits for a fresh announcement after loss in either transport order`() {
        for (cxrFirst in listOf(false, true)) {
            val negotiation = PhoneRenderedMapNegotiation()
            negotiation.sessionStarted(Any())
            negotiation.announce(announce(true))
            assertEquals(BusCapabilityBits.RENDERED_MAP, negotiation.featureBits(launcher, up))
            negotiation.lost()
            assertEquals(0, negotiation.featureBits(launcher, up))
            if (cxrFirst) {
                negotiation.announce(announce(true))
                assertEquals(0, negotiation.featureBits(launcher, LinkStateBits.CXR_CONTROL_UP))
                negotiation.sessionStarted(Any())
            } else {
                negotiation.sessionStarted(Any())
                assertEquals(0, negotiation.featureBits(launcher, up))
                negotiation.announce(announce(true))
            }
            assertEquals(BusCapabilityBits.RENDERED_MAP, negotiation.featureBits(launcher, up))
            assertEquals(0, negotiation.featureBits(null, up))
        }
    }

    @Test
    fun `session replacement drops prior support and a fresh old peer never enables it`() {
        val negotiation = PhoneRenderedMapNegotiation()
        negotiation.sessionStarted(Any())
        negotiation.announce(announce(true))
        assertEquals(BusCapabilityBits.RENDERED_MAP, negotiation.featureBits(launcher, up))
        negotiation.sessionStarted(Any())
        assertEquals(0, negotiation.featureBits(launcher, up))
        negotiation.announce(announce(false))
        assertEquals(0, negotiation.featureBits(launcher, up))
        negotiation.announce(announce(true))
        assertEquals(BusCapabilityBits.RENDERED_MAP, negotiation.featureBits(launcher, up))
    }
}
