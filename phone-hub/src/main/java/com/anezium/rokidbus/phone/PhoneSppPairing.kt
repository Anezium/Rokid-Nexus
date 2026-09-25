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
    private val onKeyStatus: (KeyStatus) -> Unit = {},
    private val currentCxrIdentity: () -> String?,
) {
    class Prepared(val identity: String, val key: ByteArray)
    enum class KeyStatus { UNAVAILABLE, RECOVERING, RECOVERED }

    private class Recovery(val key: ByteArray, var saved: Boolean = false)
    private val recoveries = mutableMapOf<String, Recovery>()

    @Synchronized
    fun offerCurrent(sendCxr: (Prepared) -> Boolean): Prepared? {
        val identity = currentCxrIdentity() ?: return null
        val key = currentKey(identity) ?: return null
        if (currentCxrIdentity() != identity) return null
        store.remember(identity)
        if (currentCxrIdentity() != identity) return null
        // A send result is not proof of delivery. Only the SPP handshake can confirm enrollment.
        val prepared = Prepared(identity, key)
        sendCxr(prepared)
        return prepared
    }

    fun sendProvisioning(
        prepared: Prepared,
        serialize: (BusEnvelope) -> ByteArray,
        sendCustomCmd: (ByteArray) -> Boolean,
        onStale: () -> Unit = {},
    ): Boolean {
        val bytes = serialize(SppKeyProvisioning.offer(prepared.key))
        // Serialization can outlive the CXR identity used to prepare this key.
        if (currentCxrIdentity() != prepared.identity) {
            onStale()
            return false
        }
        return sendCustomCmd(bytes)
    }

    private fun currentKey(identity: String): ByteArray? {
        val keys = store.keys(identity)
        val loaded = runCatching { keys.load() }
        if (currentCxrIdentity() != identity) return null
        if (loaded.isSuccess) {
            loaded.getOrNull()?.let { key ->
                if (recoveries.remove(identity) != null) onKeyStatus(KeyStatus.RECOVERED)
                return key
            }
            if (identity !in recoveries) {
                val key = SppAuthProtocol.newSecret()
                return key.takeIf { keys.save(it) }
            }
        }

        onKeyStatus(KeyStatus.UNAVAILABLE)
        if (currentCxrIdentity() != identity) return null
        // Keep one candidate until storage is readable again, including across CXR reconnects.
        val recovery = recoveries.getOrPut(identity) { Recovery(SppAuthProtocol.newSecret()) }
        if (!recovery.saved) {
            onKeyStatus(KeyStatus.RECOVERING)
            if (currentCxrIdentity() != identity) return null
            recovery.saved = keys.save(recovery.key)
        }
        if (!recovery.saved || currentCxrIdentity() != identity) {
            onKeyStatus(KeyStatus.UNAVAILABLE)
            return null
        }
        val verified = runCatching { keys.load() }.getOrNull()
        if (verified == null || !verified.contentEquals(recovery.key)) {
            onKeyStatus(KeyStatus.UNAVAILABLE)
            return null
        }
        recoveries.remove(identity)
        onKeyStatus(KeyStatus.RECOVERED)
        return verified
    }

    @Synchronized
    fun prepare(sppPeerAddress: String, sendCxr: (Prepared) -> Boolean): Prepared? {
        if (hasCxrConnection() || currentCxrIdentity() != null) return offerCurrent(sendCxr)
        val identity = store.boundIdentity(sppPeerAddress) ?: store.lastIdentity() ?: return null
        // An offline attempt must not create a key that the glasses could never have received.
        val loaded = runCatching { store.keys(identity).load() }
        if (loaded.isFailure) onKeyStatus(KeyStatus.UNAVAILABLE)
        val key = loaded.getOrNull() ?: return null
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
