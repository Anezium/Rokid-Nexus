package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.BusCapabilityBits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLinkStartupPolicyTest {
    @Test
    fun `only a positively advertised LOHS requirement skips initial P2P`() {
        assertEquals(
            CameraLinkStartupMode.WAIT_FOR_LOHS_REVERSE,
            CameraLinkStartupModePolicy.select(BusCapabilityBits.CAMERA_LOHS_REVERSE_REQUIRED),
        )
        assertEquals(CameraLinkStartupMode.P2P_FIRST, CameraLinkStartupModePolicy.select(0))
        assertEquals(
            CameraLinkStartupMode.P2P_FIRST,
            CameraLinkStartupModePolicy.select(BusCapabilityBits.CAMERA_CONSUMER_READY),
        )
    }

    @Test
    fun `glasses capability filter retains the LOHS signal and drops unknown bits`() {
        val advertised = BusCapabilityBits.CAMERA_CONSUMER_READY or
            BusCapabilityBits.CAMERA_LOHS_REVERSE_REQUIRED or
            (1 shl 30)

        assertEquals(
            BusCapabilityBits.CAMERA_CONSUMER_READY or
                BusCapabilityBits.CAMERA_LOHS_REVERSE_REQUIRED,
            supportedPhoneCameraCapabilities(advertised),
        )
    }

    @Test
    fun `missing reverse offer starts P2P only after the bounded wait`() {
        val policy = CameraLinkReverseOfferFallbackPolicy(timeoutMs = 3_000L)

        assertEquals(3_000L, policy.timeoutMs)
        assertTrue(
            policy.shouldStartP2p(
                CameraLinkStartupMode.WAIT_FOR_LOHS_REVERSE,
                reverseOfferAccepted = false,
            ),
        )
        assertFalse(
            policy.shouldStartP2p(
                CameraLinkStartupMode.WAIT_FOR_LOHS_REVERSE,
                reverseOfferAccepted = true,
            ),
        )
        assertFalse(
            policy.shouldStartP2p(
                CameraLinkStartupMode.P2P_FIRST,
                reverseOfferAccepted = false,
            ),
        )
    }
}
