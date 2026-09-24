package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.SppAuthProtocol
import com.anezium.rokidbus.shared.SppKeyProvisioning
import com.anezium.rokidbus.shared.SppPairingKeyStore
import java.io.InputStream
import java.io.OutputStream

internal interface PhoneSppPairingStore {
    fun keys(identity: String): SppPairingKeyStore
    fun lastIdentity(): String?
    fun boundIdentity(address: String): String?
    fun remember(identity: String)
    fun bind(address: String, identity: String)
}

internal class PhoneSppPairing(
    private val store: PhoneSppPairingStore,
    private val hasCxrConnection: () -> Boolean = { false },
    private val currentCxrIdentity: () -> String?,
) {
    class Prepared(val identity: String, val key: ByteArray)

    @Synchronized
    fun offerCurrent(sendCxr: (BusEnvelope) -> Boolean): Prepared? {
        val identity = currentCxrIdentity() ?: return null
        val key = SppKeyProvisioning.phoneKey(store.keys(identity)) ?: return null
        if (currentCxrIdentity() != identity) return null
        store.remember(identity)
        if (currentCxrIdentity() != identity) return null
        // A send result is not proof of delivery. Only the SPP handshake can confirm enrollment.
        sendCxr(SppKeyProvisioning.offer(key))
        return Prepared(identity, key)
    }

    @Synchronized
    fun prepare(sppPeerAddress: String, sendCxr: (BusEnvelope) -> Boolean): Prepared? {
        if (hasCxrConnection() || currentCxrIdentity() != null) return offerCurrent(sendCxr)
        val identity = store.boundIdentity(sppPeerAddress) ?: store.lastIdentity() ?: return null
        // An offline attempt must not create a key that the glasses could never have received.
        val key = runCatching { store.keys(identity).load() }.getOrNull() ?: return null
        return Prepared(identity, key)
    }

    fun authenticate(
        address: String,
        prepared: Prepared,
        input: InputStream,
        output: OutputStream,
        nonces: SppAuthProtocol.RecentNonces,
    ): SppAuthProtocol.Session {
        val session = SppAuthProtocol.connect(input, output, prepared.key, nonces)
        synchronized(this) { store.bind(address, prepared.identity) }
        return session
    }

    companion object {
        fun cxrIdentity(serial: String?, name: String?): String = when {
            !serial.isNullOrBlank() -> "cxr:serial:${serial.trim()}"
            !name.isNullOrBlank() -> "cxr:name:${name.trim()}"
            else -> "cxr:current"
        }
    }
}
