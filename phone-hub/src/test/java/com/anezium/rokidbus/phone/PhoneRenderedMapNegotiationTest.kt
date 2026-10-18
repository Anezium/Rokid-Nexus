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
            val first = Any()
            negotiation.sessionStarted(first)
            negotiation.announce(announce(true), PhoneRenderedMapNegotiation.Origin.Spp(first))
            assertEquals(BusCapabilityBits.RENDERED_MAP, negotiation.featureBits(launcher, up))
            negotiation.lost()
            assertEquals(0, negotiation.featureBits(launcher, up))
            if (cxrFirst) {
                negotiation.cxrConnectionChanged(true)
                negotiation.announce(announce(true), negotiation.cxrOrigin())
                assertEquals(0, negotiation.featureBits(launcher, LinkStateBits.CXR_CONTROL_UP))
                negotiation.sessionStarted(Any())
            } else {
                val second = Any()
                negotiation.sessionStarted(second)
                assertEquals(0, negotiation.featureBits(launcher, up))
                negotiation.announce(announce(true), PhoneRenderedMapNegotiation.Origin.Spp(second))
            }
            assertEquals(BusCapabilityBits.RENDERED_MAP, negotiation.featureBits(launcher, up))
            assertEquals(0, negotiation.featureBits(null, up))
        }
    }

    @Test
    fun `session replacement drops prior support and a fresh old peer never enables it`() {
        val negotiation = PhoneRenderedMapNegotiation()
        val first = Any()
        val second = Any()
        negotiation.sessionStarted(first)
        negotiation.announce(announce(true), PhoneRenderedMapNegotiation.Origin.Spp(first))
        assertEquals(BusCapabilityBits.RENDERED_MAP, negotiation.featureBits(launcher, up))
        negotiation.sessionStarted(second)
        assertEquals(0, negotiation.featureBits(launcher, up))
        negotiation.announce(announce(false), PhoneRenderedMapNegotiation.Origin.Spp(second))
        assertEquals(0, negotiation.featureBits(launcher, up))
        negotiation.announce(announce(true), PhoneRenderedMapNegotiation.Origin.Spp(second))
        assertEquals(BusCapabilityBits.RENDERED_MAP, negotiation.featureBits(launcher, up))
    }

    @Test
    fun `retired SPP and captured CXR origins cannot seed support before or after replacement authentication`() {
        val negotiation = PhoneRenderedMapNegotiation()
        negotiation.cxrConnectionChanged(true)
        val first = Any()
        negotiation.sessionStarted(first)
        val oldSpp = PhoneRenderedMapNegotiation.Origin.Spp(first)
        val oldCxr = negotiation.cxrOrigin()
        negotiation.announce(announce(true), oldSpp)
        negotiation.lost()
        for (beforeAuthentication in listOf(true, false)) {
            if (!beforeAuthentication) negotiation.sessionStarted(Any())
            negotiation.announce(announce(true), oldSpp)
            negotiation.announce(announce(true), oldCxr)
            assertEquals(0, negotiation.featureBits(launcher, up))
        }
        negotiation.announce(announce(true), negotiation.cxrOrigin())
        assertEquals(BusCapabilityBits.RENDERED_MAP, negotiation.featureBits(launcher, up))
        negotiation.lost()
        val retiredCxr = negotiation.cxrOrigin()
        negotiation.cxrConnectionChanged(false)
        negotiation.cxrConnectionChanged(true)
        negotiation.announce(announce(true), retiredCxr)
        negotiation.sessionStarted(Any())
        assertEquals(0, negotiation.featureBits(launcher, up))
        negotiation.announce(announce(true), negotiation.cxrOrigin())
        assertEquals(BusCapabilityBits.RENDERED_MAP, negotiation.featureBits(launcher, up))
    }
}
