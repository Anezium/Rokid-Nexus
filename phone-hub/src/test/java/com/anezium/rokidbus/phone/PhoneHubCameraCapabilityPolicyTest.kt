package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusCapabilityBits
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneHubCameraCapabilityPolicyTest {
    @Test
    fun `wifi off positively advertises LOHS only for a ready camera consumer`() {
        assertEquals(
            BusCapabilityBits.CAMERA_CONSUMER_READY or
                BusCapabilityBits.CAMERA_LOHS_REVERSE_REQUIRED,
            PhoneHubCameraCapabilityPolicy.applyLohsRequirement(
                BusCapabilityBits.CAMERA_CONSUMER_READY,
                isWifiEnabled = false,
            ),
        )
        assertEquals(
            0,
            PhoneHubCameraCapabilityPolicy.applyLohsRequirement(0, isWifiEnabled = false),
        )
    }

    @Test
    fun `wifi on or unavailable conservatively clears the LOHS requirement`() {
        val stale = BusCapabilityBits.CAMERA_CONSUMER_READY or
            BusCapabilityBits.CAMERA_LOHS_REVERSE_REQUIRED

        assertEquals(
            BusCapabilityBits.CAMERA_CONSUMER_READY,
            PhoneHubCameraCapabilityPolicy.applyLohsRequirement(stale, isWifiEnabled = true),
        )
        assertEquals(
            BusCapabilityBits.CAMERA_CONSUMER_READY,
            PhoneHubCameraCapabilityPolicy.applyLohsRequirement(stale, isWifiEnabled = null),
        )
    }
}
