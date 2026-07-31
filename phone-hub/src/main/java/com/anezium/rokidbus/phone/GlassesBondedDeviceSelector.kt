package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusConstants

internal data class BondedBluetoothDevice(
    val address: String,
    val name: String?,
    val alias: String?,
    val serviceUuids: Set<String>,
)

/** Identifies the paired Rokid unit without tying Nexus to one developer's glasses. */
internal object GlassesBondedDeviceSelector {
    fun pickIndex(
        devices: List<BondedBluetoothDevice>,
        preferredAddress: String? = null,
    ): Int? {
        val rememberedMatch = preferredAddress?.let { address ->
            devices.indexOfFirst { it.address.equals(address, ignoreCase = true) }
        } ?: -1
        if (rememberedMatch >= 0) return rememberedMatch

        val sppUuid = BusConstants.SPP_UUID_STRING.lowercase()
        val uuidMatch = devices.indexOfFirst { device ->
            device.serviceUuids.any { it.equals(sppUuid, ignoreCase = true) }
        }
        if (uuidMatch >= 0) return uuidMatch

        val nameMatch = devices.indexOfFirst { device ->
            sequenceOf(device.alias, device.name)
                .filterNotNull()
                .any { it.startsWith(GLASSES_NAME_PREFIX, ignoreCase = true) }
        }
        return nameMatch.takeIf { it >= 0 }
    }

    private const val GLASSES_NAME_PREFIX = "Glasses_"
}
