package com.anezium.rokidbus.phone

import com.example.cxrglobal.GlassInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesDeviceInfoPayloadTest {
    @Test
    fun `payload maps GlassInfo fields but never the hardware serial number`() {
        val payload = GlassesDeviceInfoPayload.create(
            info = GlassInfo(
                deviceName = "Rokid Glasses",
                batteryLevel = 87,
                sound = 6,
                brightness = 4,
                systemVersion = "2.3.4",
                isCharging = true,
                sn = "SERIAL-123",
                wearingStatus = "wearing",
            ),
            pluginId = "hello",
            eventId = "event-1",
        )

        assertEquals(1, payload.getInt("version"))
        assertEquals("glasses_device_info", payload.getString("type"))
        assertEquals("event-1", payload.getString("id"))
        assertEquals("hello", payload.getString("pluginId"))
        assertEquals("Rokid Glasses", payload.getString("deviceName"))
        assertEquals(87, payload.getInt("batteryLevel"))
        assertEquals(6, payload.getInt("sound"))
        assertEquals(4, payload.getInt("brightness"))
        assertEquals("2.3.4", payload.getString("systemVersion"))
        assertTrue(payload.getBoolean("isCharging"))
        assertEquals("wearing", payload.getString("wearingStatus"))
        assertFalse("the hardware serial number must never reach a plugin", payload.has("sn"))
        assertEquals(11, payload.length())
    }
}
