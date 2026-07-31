package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlassesBondedDeviceSelectorTest {
    @Test
    fun `selects the device advertising the Nexus SPP service`() {
        val devices = listOf(
            device("Headphones", "0000110b-0000-1000-8000-00805f9b34fb"),
            device("My Rokid", BusConstants.SPP_UUID_STRING.uppercase()),
        )

        assertEquals(1, GlassesBondedDeviceSelector.pickIndex(devices))
    }

    @Test
    fun `falls back to the per-unit Rokid glasses name while UUIDs are unavailable`() {
        val devices = listOf(
            device("Google Pixel Watch"),
            device("Glasses_1899"),
        )

        assertEquals(1, GlassesBondedDeviceSelector.pickIndex(devices))
    }

    @Test
    fun `service UUID wins over a merely glasses-shaped name`() {
        val devices = listOf(
            device("Glasses_Other"),
            device("Rokid unit", BusConstants.SPP_UUID_STRING),
        )

        assertEquals(1, GlassesBondedDeviceSelector.pickIndex(devices))
    }

    @Test
    fun `does not claim unrelated bonded devices`() {
        val devices = listOf(
            device("Google Pixel Watch"),
            device("Arctis GameBuds", "0000110b-0000-1000-8000-00805f9b34fb"),
        )

        assertNull(GlassesBondedDeviceSelector.pickIndex(devices))
    }

    private fun device(name: String, vararg serviceUuids: String) = BondedBluetoothDevice(
        name = name,
        serviceUuids = serviceUuids.toSet(),
    )
}
