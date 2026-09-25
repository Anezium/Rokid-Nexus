package com.anezium.rokidbus.shared

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SppKeyProvisioningTest {
    @Test fun onlyCxrCanInstallAndRoundTripKeepsKey() {
        val key = SppAuthProtocol.newSecret()
        val envelope = FrameProtocol.fromJsonBytes(FrameProtocol.toJsonBytes(SppKeyProvisioning.offer(key)))
        var installed: ByteArray? = null
        assertTrue(SppKeyProvisioning.receive(envelope, fromCxr = false) { installed = it })
        assertNull(installed)
        assertTrue(SppKeyProvisioning.receive(envelope, fromCxr = true) { installed = it })
        assertArrayEquals(key, installed)
    }

    @Test fun malformedVersionsKeysBinaryAndReservedPathsAreConsumedWithoutInstallation() {
        val key = SppAuthProtocol.newSecret()
        val envelopes = listOf(
            SppKeyProvisioning.offer(key).also { it.payload.put("version", 2) },
            SppKeyProvisioning.offer(key).also { it.payload.put("version", "1") },
            SppKeyProvisioning.offer(key).also { it.payload.put("key", "not a key") },
            SppKeyProvisioning.offer(key).also { it.payload.put("key", "!".repeat(44)) },
            SppKeyProvisioning.offer(key).copy(v = 2),
            SppKeyProvisioning.offer(key).copy(binary = byteArrayOf(1)),
            SppKeyProvisioning.offer(key).copy(path = "/hub/spp/unknown"),
        )
        for (envelope in envelopes) {
            assertTrue(SppKeyProvisioning.receive(envelope, fromCxr = true) { fail("Unexpected installation") })
        }
        assertFalse(SppKeyProvisioning.receive(BusEnvelope("/other"), fromCxr = true) { fail() })
    }

    @Test fun phonePersistsBeforeOfferingAndReusesKey() {
        val store = MemoryStore()
        val first = SppKeyProvisioning.phoneKey(store)!!
        assertEquals(32, first.size)
        assertArrayEquals(first, store.key)
        assertArrayEquals(first, SppKeyProvisioning.phoneKey(store))
        assertEquals(1, store.saves)
        store.writable = false
        store.key = null
        assertNull(SppKeyProvisioning.phoneKey(store))
    }

    @Test fun readFailureDoesNotGenerateOrPersistReplacementAndRetryReusesKey() {
        val original = SppAuthProtocol.newSecret()
        val store = MemoryStore().apply { key = original; readable = false }
        assertNull(SppKeyProvisioning.phoneKey(store))
        assertEquals(0, store.saves)
        assertArrayEquals(original, store.key)
        store.readable = true
        assertArrayEquals(original, SppKeyProvisioning.phoneKey(store))
        assertEquals(0, store.saves)
    }

    private class MemoryStore : SppPairingKeyStore {
        var key: ByteArray? = null
        var saves = 0
        var writable = true
        var readable = true
        override fun load(): ByteArray? {
            if (!readable) throw IOException("Unavailable")
            return key
        }
        override fun save(key: ByteArray): Boolean {
            saves++
            if (!writable) return false
            this.key = key.copyOf()
            return true
        }
    }
}
