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
    fun `uses the persistent local alias while the remote name and UUID cache are unavailable`() {
        val devices = listOf(
            device("Google Pixel Watch"),
            device(name = null, alias = "Glasses_1899"),
        )

        assertEquals(1, GlassesBondedDeviceSelector.pickIndex(devices))
    }

    @Test
    fun `reuses a remembered address only while it remains bonded`() {
        val devices = listOf(
            device("Google Pixel Watch", address = "AA:BB:CC:DD:EE:01"),
            device(name = null, address = "AA:BB:CC:DD:EE:02"),
        )

        assertEquals(
            1,
            GlassesBondedDeviceSelector.pickIndex(
                devices,
                preferredAddress = "aa:bb:cc:dd:ee:02",
            ),
        )
        assertNull(
            GlassesBondedDeviceSelector.pickIndex(
                devices,
                preferredAddress = "AA:BB:CC:DD:EE:03",
            ),
        )
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

    private fun device(
        name: String?,
        vararg serviceUuids: String,
        address: String = "00:00:00:00:00:00",
        alias: String? = null,
    ) = BondedBluetoothDevice(
        address = address,
        name = name,
        alias = alias,
        serviceUuids = serviceUuids.toSet(),
    )
}
