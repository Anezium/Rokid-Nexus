package com.anezium.rokidbus.shared

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SppKeyStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val wrappingKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun persistedKeySurvivesStoreRecreationAndReplacementWithoutPlaintextOnDisk() {
        val file = File(temporary.root, "pairing")
        val store = SppKeyStore(AtomicFile(file)) { wrappingKey }
        assertNull(store.load())
        val key = SppKeyProvisioning.phoneKey(store)!!
        val record = file.readBytes()
        assertEquals(61, record.size)
        assertFalse(record.toList().windowed(32).any { it.toByteArray().contentEquals(key) })
        val reopened = SppKeyStore(AtomicFile(file)) { wrappingKey }
        assertArrayEquals(key, reopened.load())
        val replacement = SppAuthProtocol.newSecret()
        assertTrue(reopened.save(replacement))
        assertArrayEquals(replacement, store.load())
    }

    @Test fun malformedTamperedAndWrongWrappingKeyAreReadErrorsNotMissingKeys() {
        val file = File(temporary.root, "pairing")
        val store = SppKeyStore(AtomicFile(file)) { wrappingKey }
        assertTrue(store.save(SppAuthProtocol.newSecret()))
        val valid = file.readBytes()
        val wrongKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertThrows(IOException::class.java) { SppKeyStore(AtomicFile(file)) { wrongKey }.load() }
        for (bad in listOf(
            valid.copyOf(20), valid + byteArrayOf(0),
            valid.copyOf().also { it[0] = 2 },
            valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
        )) {
            file.writeBytes(bad)
            assertThrows(IOException::class.java) { store.load() }
            assertNull(SppKeyProvisioning.phoneKey(store))
            assertArrayEquals(bad, file.readBytes())
        }
    }

    @Test fun wrappingKeyFailureDoesNotRotateStoredPairAndCanBeRetried() {
        val file = File(temporary.root, "pairing")
        var available = true
        val store = SppKeyStore(AtomicFile(file)) {
            if (!available) throw IOException("Keystore unavailable")
            wrappingKey
        }
        val key = SppKeyProvisioning.phoneKey(store)!!
        val record = file.readBytes()
        available = false
        assertNull(SppKeyProvisioning.phoneKey(store))
        assertFalse(store.save(SppAuthProtocol.newSecret()))
        assertArrayEquals(record, file.readBytes())
        available = true
        assertArrayEquals(key, store.load())
    }

    @Test fun trustedReplacementOverwritesUnreadableBlobAndSurvivesRecreation() {
        val file = File(temporary.root, "pairing")
        var activeWrappingKey = wrappingKey
        val store = SppKeyStore(AtomicFile(file)) { activeWrappingKey }
        assertTrue(store.save(SppAuthProtocol.newSecret()))
        activeWrappingKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertThrows(IOException::class.java) { store.load() }
        val replacement = SppAuthProtocol.newSecret()
        assertTrue(store.save(replacement))
        assertArrayEquals(replacement, SppKeyStore(AtomicFile(file)) { activeWrappingKey }.load())
        assertFalse(file.readBytes().toList().windowed(32).any { it.toByteArray().contentEquals(replacement) })

        file.writeBytes(byteArrayOf(1, 2, 3))
        assertThrows(IOException::class.java) { store.load() }
        val atomic = AtomicFile(file)
        val interrupted = atomic.startWrite()
        interrupted.write(byteArrayOf(4, 5, 6))
        atomic.failWrite(interrupted)
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        assertTrue(store.save(replacement))
        assertArrayEquals(replacement, SppKeyStore(AtomicFile(file)) { activeWrappingKey }.load())
    }

    @Test fun failedAtomicWriteRestoresExistingKey() {
        val file = File(temporary.root, "pairing")
        val atomic = AtomicFile(file)
        val store = SppKeyStore(atomic) { wrappingKey }
        val key = SppKeyProvisioning.phoneKey(store)!!
        val interrupted = atomic.startWrite()
        interrupted.write(byteArrayOf(1, 2, 3))
        atomic.failWrite(interrupted)
        assertArrayEquals(key, SppKeyStore(AtomicFile(file)) { wrappingKey }.load())
        assertFalse(store.save(ByteArray(31)))
        assertArrayEquals(key, store.load())
    }

    @Test fun phoneKeysHaveStableSeparatePeerScopesAndGlassesHaveOneEnrollment() {
        val first = SppKeyStore.fileName("test-peer-a")
        assertEquals(first, SppKeyStore.fileName("test-peer-a"))
        assertNotEquals(first, SppKeyStore.fileName("test-peer-b"))
        assertFalse(first.contains("test-peer-a"))
        assertNotEquals(first, SppKeyStore.fileName(null))
        assertEquals("spp-pairing-key", SppKeyStore.fileName(null))
    }
}
