package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.LinkStateBits
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SppPairingStatusTest {
    @Test fun existingHubNotificationShowsRecoveryInsteadOfGenericConnectedStatus() {
        val hub = Robolectric.buildService(BusHubService::class.java).get()
        val status = BusHubService::class.java.getDeclaredField("sppKeyStatus").apply { isAccessible = true }
        val text = BusHubService::class.java.getDeclaredMethod("statusText", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        for ((state, expected) in mapOf(
            PhoneSppPairing.KeyStatus.UNAVAILABLE to "Glasses pairing key unavailable; reconnect Hi Rokid to recover",
            PhoneSppPairing.KeyStatus.RECOVERING to "Recovering glasses pairing key",
            PhoneSppPairing.KeyStatus.RECOVERED to "Glasses pairing key recovered; reconnecting",
        )) {
            status.set(hub, state)
            assertEquals(expected, text.invoke(hub, LinkStateBits.CXR_CONTROL_UP))
        }
        status.set(hub, null)
        assertEquals("Connected to glasses", text.invoke(hub, LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP))
    }
}
