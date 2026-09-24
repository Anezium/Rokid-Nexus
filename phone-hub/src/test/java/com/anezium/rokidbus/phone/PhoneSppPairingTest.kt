package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.FrameProtocol
import com.anezium.rokidbus.shared.SppKeyProvisioning
import com.anezium.rokidbus.shared.SppPairingKeyStore
import org.junit.Assert.*
import org.junit.Test

class PhoneSppPairingTest {
    @Test fun provisionsThroughFakeCxrAndReoffersAfterGlassesReset() {
        val store = MemoryStore()
        val phone = PhoneSppPairing(store)
        var glassesKey: ByteArray? = null
        val cxr = { envelope: BusEnvelope ->
            assertNotNull(store.key)
            val received = FrameProtocol.fromJsonBytes(FrameProtocol.toJsonBytes(envelope))
            SppKeyProvisioning.receive(received, fromCxr = true) { glassesKey = it }
        }
        val key = phone.prepare(true, cxr)
        assertArrayEquals(key, glassesKey)
        glassesKey = null
        assertArrayEquals(key, phone.prepare(true, cxr))
        assertArrayEquals(key, glassesKey)
        store.key = null // Phone reinstall/reset replaces the enrollment through CXR.
        val replacement = phone.prepare(true, cxr)
        assertFalse(key!!.contentEquals(replacement!!))
        assertArrayEquals(replacement, glassesKey)
    }

    @Test fun cxrDownNeverSendsSecretAndPersistFailureNeverOffers() {
        val store = MemoryStore()
        val phone = PhoneSppPairing(store)
        assertNotNull(phone.prepare(false) { fail("CXR down"); false })
        store.key = null
        store.writable = false
        assertNull(phone.prepare(true) { fail("Unpersisted key offered"); false })
    }

    @Test fun droppedCxrOfferDoesNotRotateStoredKey() {
        val store = MemoryStore()
        val phone = PhoneSppPairing(store)
        val key = phone.prepare(true) { false }
        assertArrayEquals(key, phone.prepare(true) { true })
        assertArrayEquals(key, store.key)
    }

    private class MemoryStore : SppPairingKeyStore {
        var key: ByteArray? = null
        var writable = true
        override fun load() = key
        override fun save(key: ByteArray): Boolean {
            if (!writable) return false
            this.key = key.copyOf()
            return true
        }
    }
}
