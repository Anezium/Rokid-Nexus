package com.anezium.rokidbus.shared

import org.junit.Assert.*
import org.junit.Test

class SppKeyStoreTest {
    @Test fun phoneKeysHaveStableSeparatePeerScopesAndGlassesHaveOneEnrollment() {
        val first = SppKeyStore.fileName("test-peer-a")
        assertEquals(first, SppKeyStore.fileName("test-peer-a"))
        assertNotEquals(first, SppKeyStore.fileName("test-peer-b"))
        assertFalse(first.contains("test-peer-a"))
        assertNotEquals(first, SppKeyStore.fileName(null))
        assertEquals("spp-pairing-key", SppKeyStore.fileName(null))
    }
}
