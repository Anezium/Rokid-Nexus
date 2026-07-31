package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusConstants

internal data class BondedBluetoothDevice(
    val name: String?,
    val serviceUuids: Set<String>,
)

/** Identifies the paired Rokid unit without tying Nexus to one developer's glasses. */
internal object GlassesBondedDeviceSelector {
    fun pickIndex(devices: List<BondedBluetoothDevice>): Int? {
        val sppUuid = BusConstants.SPP_UUID_STRING.lowercase()
        val uuidMatch = devices.indexOfFirst { device ->
            device.serviceUuids.any { it.equals(sppUuid, ignoreCase = true) }
        }
        if (uuidMatch >= 0) return uuidMatch

        val nameMatch = devices.indexOfFirst { device ->
            device.name?.startsWith(GLASSES_NAME_PREFIX, ignoreCase = true) == true
        }
        return nameMatch.takeIf { it >= 0 }
    }

    private const val GLASSES_NAME_PREFIX = "Glasses_"
}
