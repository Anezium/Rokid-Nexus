package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.FrameProtocol
import com.anezium.rokidbus.shared.SppAuthProtocol
import com.anezium.rokidbus.shared.SppKeyProvisioning
import com.anezium.rokidbus.shared.SppPairingKeyStore
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PhoneSppPairingTest {
    private val address = "AA:BB:CC:DD:EE:FF"
    private val firstIdentity = PhoneSppPairing.cxrIdentity("serial-a", "glasses")
    private val secondIdentity = PhoneSppPairing.cxrIdentity("serial-b", "glasses")
    private var currentIdentity: String? = firstIdentity
    private val store = MemoryStore()
    private val phone = PhoneSppPairing(store) { currentIdentity }
    private var glassesKey: ByteArray? = null
    private var offers = 0
    private val cxr = { envelope: BusEnvelope ->
        assertNotNull(store.keys(currentIdentity!!).key)
        offers++
        val received = FrameProtocol.fromJsonBytes(FrameProtocol.toJsonBytes(envelope))
        SppKeyProvisioning.receive(received, fromCxr = true) { glassesKey = it }
    }

    @Test fun currentCxrSessionReceivesFirstKeyWithoutBluetoothAddressMapping() {
        val prepared = phone.prepare(address, cxr)!!
        assertEquals(firstIdentity, prepared.identity)
        assertArrayEquals(prepared.key, glassesKey)
        assertNull(store.boundIdentity(address))
    }

    @Test fun reconnectReoffersPersistedKeyEvenWithoutAnSppAttempt() {
        val first = phone.offerCurrent(cxr)!!
        currentIdentity = null
        assertNull(phone.offerCurrent { fail("CXR down"); false })
        glassesKey = null
        currentIdentity = firstIdentity
        val reconnected = phone.offerCurrent(cxr)!!
        assertArrayEquals(first.key, reconnected.key)
        assertArrayEquals(first.key, glassesKey)
        assertEquals(2, offers)
        assertEquals(1, store.keys(firstIdentity).saves)
    }

    @Test fun differentCxrIdentitiesUseDifferentKeysAndOverrideOldAddressBinding() {
        val first = phone.prepare(address, cxr)!!
        handshake(first, first.key)
        currentIdentity = secondIdentity
        val second = phone.prepare(address, cxr)!!
        assertEquals(secondIdentity, second.identity)
        assertFalse(first.key.contentEquals(second.key))
        assertArrayEquals(second.key, glassesKey)
        assertEquals(firstIdentity, store.boundIdentity(address))
        currentIdentity = firstIdentity
        assertArrayEquals(first.key, phone.prepare(address, cxr)!!.key)
    }

    @Test fun successfulHandshakeBindsCapturedIdentityAndOfflineReconnectUsesThatBinding() {
        val first = phone.prepare(address, cxr)!!
        currentIdentity = secondIdentity
        phone.offerCurrent(cxr)
        handshake(first, first.key)
        assertEquals(firstIdentity, store.boundIdentity(address))
        phone.offerCurrent(cxr)
        currentIdentity = null
        val restarted = PhoneSppPairing(store) { currentIdentity }
        assertArrayEquals(first.key, restarted.prepare(address.lowercase(Locale.ROOT)) { fail("CXR down"); false }!!.key)
        assertEquals(secondIdentity, restarted.prepare("11:22:33:44:55:66") { fail("CXR down"); false }!!.identity)
    }

    @Test fun mismatchedKeyDoesNotBindRotateOrDeleteAndLaterRetrySucceeds() {
        val first = phone.prepare(address, cxr)!!
        handshake(first, first.key)
        currentIdentity = secondIdentity
        val second = phone.prepare(address, cxr)!!
        assertThrows(IOException::class.java) { handshake(second, first.key) }
        assertEquals(firstIdentity, store.boundIdentity(address))
        assertArrayEquals(first.key, store.keys(firstIdentity).key)
        assertArrayEquals(second.key, store.keys(secondIdentity).key)
        val retry = phone.prepare(address, cxr)!!
        assertArrayEquals(second.key, retry.key)
        assertEquals(1, store.keys(secondIdentity).saves)
        handshake(retry, retry.key)
        assertEquals(secondIdentity, store.boundIdentity(address))
    }

    @Test fun firstHandshakeFailureDoesNotCreateAddressBinding() {
        val prepared = phone.prepare(address, cxr)!!
        assertThrows(IOException::class.java) { handshake(prepared, SppAuthProtocol.newSecret()) }
        assertNull(store.boundIdentity(address))
        assertEquals(1, store.keys(firstIdentity).saves)
    }

    @Test fun offlineWithNoEnrollmentDoesNotCreateAKey() {
        currentIdentity = null
        assertNull(phone.prepare(address) { fail("CXR down"); false })
        assertTrue(store.keyStores.isEmpty())
        store.remember(firstIdentity)
        assertNull(phone.prepare(address) { fail("CXR down"); false })
        assertEquals(0, store.keys(firstIdentity).saves)
    }

    @Test fun failedPersistenceAndReadErrorsNeverOfferOrRotateKeys() {
        val keys = store.keys(firstIdentity)
        keys.writable = false
        assertNull(phone.offerCurrent { fail("Unpersisted key offered"); false })
        keys.writable = true
        val first = phone.offerCurrent(cxr)!!
        keys.readable = false
        assertNull(phone.offerCurrent { fail("Unreadable key offered"); false })
        currentIdentity = null
        assertNull(phone.prepare(address) { fail("CXR down"); false })
        assertEquals(1, keys.saves)
        keys.readable = true
        currentIdentity = firstIdentity
        assertArrayEquals(first.key, phone.offerCurrent(cxr)!!.key)
    }

    @Test fun droppedCxrOfferDoesNotRotateStoredKey() {
        val first = phone.prepare(address) { false }!!
        assertArrayEquals(first.key, phone.prepare(address, cxr)!!.key)
        assertEquals(1, store.keys(firstIdentity).saves)
    }

    @Test fun identityChangedDuringKeyLoadDoesNotReceiveTheOldKey() {
        store.keys(firstIdentity).onLoad = { currentIdentity = secondIdentity }
        assertNull(phone.offerCurrent { fail("Stale identity offered"); false })
        assertEquals(secondIdentity, phone.offerCurrent(cxr)!!.identity)
    }

    @Test fun identityUsesSerialThenNameThenCurrentFallbackWithSeparateNamespaces() {
        assertEquals(firstIdentity, PhoneSppPairing.cxrIdentity(" serial-a ", "new name"))
        assertEquals("cxr:name:glasses", PhoneSppPairing.cxrIdentity(" ", " glasses "))
        assertEquals("cxr:current", PhoneSppPairing.cxrIdentity(null, " "))
        assertNotEquals(PhoneSppPairing.cxrIdentity("glasses", null), PhoneSppPairing.cxrIdentity(null, "glasses"))
        currentIdentity = PhoneSppPairing.cxrIdentity(null, null)
        assertArrayEquals(phone.offerCurrent(cxr)!!.key, glassesKey)
    }

    private fun handshake(prepared: PhoneSppPairing.Prepared, serverKey: ByteArray) {
        val serverInput = PipedInputStream(1024)
        val clientOutput = PipedOutputStream(serverInput)
        val clientInput = PipedInputStream(1024)
        val serverOutput = PipedOutputStream(clientInput)
        val executor = Executors.newSingleThreadExecutor()
        val server = executor.submit<SppAuthProtocol.Session> {
            SppAuthProtocol.accept(serverInput, serverOutput, serverKey, SppAuthProtocol.RecentNonces())
        }
        try {
            phone.authenticate(address, prepared, clientInput, clientOutput, SppAuthProtocol.RecentNonces())
            server.get(3, TimeUnit.SECONDS)
        } finally {
            clientOutput.close()
            serverOutput.close()
            clientInput.close()
            serverInput.close()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    private class MemoryStore : PhoneSppPairingStore {
        val keyStores = mutableMapOf<String, MemoryKeyStore>()
        private val bindings = mutableMapOf<String, String>()
        private var last: String? = null
        override fun keys(identity: String) = keyStores.getOrPut(identity) { MemoryKeyStore() }
        override fun lastIdentity() = last
        override fun boundIdentity(address: String) = bindings[address.uppercase(Locale.ROOT)]
        override fun remember(identity: String) { last = identity }
        override fun bind(address: String, identity: String) {
            bindings[address.uppercase(Locale.ROOT)] = identity
            last = identity
        }
    }

    private class MemoryKeyStore : SppPairingKeyStore {
        var key: ByteArray? = null
        var writable = true
        var readable = true
        var saves = 0
        var onLoad: () -> Unit = {}
        override fun load(): ByteArray? {
            onLoad()
            if (!readable) throw IOException("Key unavailable")
            return key
        }
        override fun save(key: ByteArray): Boolean {
            if (!writable) return false
            this.key = key.copyOf()
            saves++
            return true
        }
    }
}
