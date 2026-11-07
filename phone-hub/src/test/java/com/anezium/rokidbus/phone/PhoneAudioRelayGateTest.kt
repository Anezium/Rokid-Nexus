package com.anezium.rokidbus.phone

import org.junit.Assert.*
import org.junit.Test

class PhoneAudioRelayGateTest {
    @Test fun `only an enabled external sink with audio support and authenticated bus can capture`() {
        for (enabled in listOf(false, true)) for (route in PhoneTtsRoute.entries)
            for (capable in listOf(false, true)) for (dataUp in listOf(false, true)) {
                assertEquals("$enabled $route $capable $dataUp", enabled && route == PhoneTtsRoute.EXTERNAL_SINK && capable && dataUp,
                    PhoneAudioRelayGate.desired(enabled, route, capable, dataUp))
            }
        assertEquals(PhoneAudioRelayStatus.OFF, PhoneAudioRelayGate.waitingStatus(false, PhoneTtsRoute.EXTERNAL_SINK, true, true))
        assertEquals(PhoneAudioRelayStatus.WAITING_FOR_EARBUDS, PhoneAudioRelayGate.waitingStatus(true, PhoneTtsRoute.GLASSES_LINK, true, true))
        assertEquals(PhoneAudioRelayStatus.WAITING_FOR_GLASSES, PhoneAudioRelayGate.waitingStatus(true, PhoneTtsRoute.EXTERNAL_SINK, true, false))
        assertEquals(PhoneAudioRelayStatus.GLASSES_TOO_OLD, PhoneAudioRelayGate.waitingStatus(true, PhoneTtsRoute.EXTERNAL_SINK, false, true))
    }
}
