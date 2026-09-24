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
        val key = phone.prepare("AA:BB:CC:DD:EE:FF", "aa:bb:cc:dd:ee:ff", cxr)
        assertArrayEquals(key, glassesKey)
        glassesKey = null
        assertArrayEquals(key, phone.prepare("AA:BB:CC:DD:EE:FF", "aa:bb:cc:dd:ee:ff", cxr))
        assertArrayEquals(key, glassesKey)
        store.key = null // Phone reinstall/reset replaces the enrollment through CXR.
        val replacement = phone.prepare("AA:BB:CC:DD:EE:FF", "aa:bb:cc:dd:ee:ff", cxr)
        assertFalse(key!!.contentEquals(replacement!!))
        assertArrayEquals(replacement, glassesKey)
    }

    @Test fun cxrDownNeverSendsSecretAndPersistFailureNeverOffers() {
        val store = MemoryStore()
        val phone = PhoneSppPairing(store)
        assertNotNull(phone.prepare("AA:BB:CC:DD:EE:FF", null) { fail("CXR down"); false })
        store.key = null
        store.writable = false
        assertNull(phone.prepare("AA:BB:CC:DD:EE:FF", "AA:BB:CC:DD:EE:FF") { fail("Unpersisted key offered"); false })
    }

    @Test fun droppedCxrOfferDoesNotRotateStoredKey() {
        val store = MemoryStore()
        val phone = PhoneSppPairing(store)
        val key = phone.prepare("AA:BB:CC:DD:EE:FF", "AA:BB:CC:DD:EE:FF") { false }
        assertArrayEquals(key, phone.prepare("AA:BB:CC:DD:EE:FF", "AA:BB:CC:DD:EE:FF") { true })
        assertArrayEquals(key, store.key)
    }

    @Test fun unknownOrDifferentCxrPeerNeverReceivesSelectedPeersKey() {
        val store = MemoryStore()
        val phone = PhoneSppPairing(store)
        val key = phone.prepare("AA:BB:CC:DD:EE:FF", null) { fail("Unknown CXR peer"); false }
        assertNotNull(key)
        assertArrayEquals(key, phone.prepare("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66") {
            fail("Wrong CXR peer"); false
        })
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
