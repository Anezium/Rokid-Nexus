package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.SppKeyProvisioning
import com.anezium.rokidbus.shared.SppPairingKeyStore

internal class PhoneSppPairing(private val keys: SppPairingKeyStore) {
    fun prepare(cxrUp: Boolean, sendCxr: (BusEnvelope) -> Boolean): ByteArray? {
        val key = SppKeyProvisioning.phoneKey(keys) ?: return null
        // A send result is not proof of delivery. Only the SPP handshake can confirm enrollment.
        if (cxrUp) sendCxr(SppKeyProvisioning.offer(key))
        return key
    }
}
