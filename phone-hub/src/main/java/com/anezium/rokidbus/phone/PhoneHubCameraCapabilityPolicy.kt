package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusCapabilityBits

/** Pure conservative projection of current phone Wi-Fi state into camera capabilities. */
internal object PhoneHubCameraCapabilityPolicy {
    fun applyLohsRequirement(capabilities: Int, isWifiEnabled: Boolean?): Int {
        val withoutLohsRequirement =
            capabilities and BusCapabilityBits.CAMERA_LOHS_REVERSE_REQUIRED.inv()
        return if (
            isWifiEnabled == false &&
            withoutLohsRequirement and BusCapabilityBits.CAMERA_CONSUMER_READY != 0
        ) {
            withoutLohsRequirement or BusCapabilityBits.CAMERA_LOHS_REVERSE_REQUIRED
        } else {
            withoutLohsRequirement
        }
    }
}
