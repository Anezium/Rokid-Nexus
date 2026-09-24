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

    @Test fun cxrReconnectWaitsForDeviceInfoAndOffersThePersistedKeyExactlyOnce() {
        val connection = CxrConnection()
        connection.connect()
        connection.info("serial-a")
        val enrolled = glassesKey!!.copyOf()
        connection.disconnect()
        connection.connect()
        connection.identity.onConnectionChanged(true)
        assertNull(connection.identity.current())
        assertEquals(1, offers)
        connection.info("serial-a")
        connection.info("serial-a")
        connection.advance(5_000)
        assertEquals(2, offers)
        assertArrayEquals(enrolled, glassesKey)
        assertEquals(1, store.keys(firstIdentity).saves)
        assertFalse(store.keyStores.containsKey("cxr:current"))
    }

    @Test fun missingDeviceInfoOffersFallbackOnceOnlyAfterFourSeconds() {
        val connection = CxrConnection()
        connection.connect()
        connection.info(null)
        connection.advance(3_999)
        assertEquals(0, offers)
        assertTrue(store.keyStores.isEmpty())
        connection.identity.onConnectionChanged(true)
        connection.advance(1)
        assertEquals(1, offers)
        assertEquals("cxr:current", connection.identity.current())
        assertArrayEquals(store.keys("cxr:current").key, glassesKey)
        connection.info(null)
        connection.advance(5_000)
        assertEquals(1, offers)
    }

    @Test fun pendingCxrIdentityDoesNotUseAnOfflineKeyOrOfferFallbackOnSppAttempt() {
        val first = phone.offerCurrent(cxr)!!
        handshake(first, first.key)
        val connection = CxrConnection()
        connection.connect()
        assertNull(connection.pairing.prepare(address) { fail("Identity still pending"); false })
        assertEquals(1, offers)
        assertFalse(store.keyStores.containsKey("cxr:current"))
    }

    @Test fun cancelledTimeoutCannotProvisionAReconnectedOrIdentifiedPeer() {
        val connection = CxrConnection()
        connection.connect()
        val staleTimeout = connection.timers.single().action
        connection.disconnect()
        connection.connect()
        staleTimeout()
        connection.flush()
        assertEquals(0, offers)
        connection.info("serial-b")
        connection.timers.forEach { it.action() }
        connection.flush()
        assertEquals(1, offers)
        assertEquals(secondIdentity, connection.identity.current())
        assertFalse(store.keyStores.containsKey("cxr:current"))
    }

    @Test fun lateDeviceInfoReplacesFallbackOnceAndNextPairNeverReceivesOldIdentity() {
        val connection = CxrConnection()
        connection.connect()
        connection.advance(4_000)
        connection.info("serial-a")
        assertEquals(2, offers)
        connection.disconnect()
        connection.connect()
        assertEquals(2, offers)
        connection.info("serial-b")
        assertEquals(3, offers)
        assertArrayEquals(store.keys(secondIdentity).key, glassesKey)
        assertFalse(store.keys(firstIdentity).key!!.contentEquals(glassesKey!!))
    }

    @Test fun deviceInfoBeforeLinkUpIsUsedAndQueuedOldOffersAreDiscarded() {
        val connection = CxrConnection()
        connection.info("serial-a")
        assertEquals(0, offers)
        connection.connected = true
        connection.identity.onConnectionChanged(true)
        connection.disconnect()
        connection.connect()
        assertEquals(0, offers)
        connection.info("serial-b")
        connection.advance(5_000)
        assertEquals(1, offers)
        assertFalse(store.keyStores.containsKey(firstIdentity))
    }

    private inner class CxrConnection {
        var connected = false
        private var now = 0L
        val timers = mutableListOf<Timer>()
        private val pendingOffers = mutableListOf<Long>()
        val identity = SppCxrIdentity(
            schedule = { delay, action ->
                val timer = Timer(now + delay, action)
                timers += timer
                ({ timer.cancelled = true })
            },
            onReady = { pendingOffers += it },
        )
        val pairing = PhoneSppPairing(store, hasCxrConnection = { connected }, currentCxrIdentity = identity::current)

        fun connect() {
            connected = true
            identity.onConnectionChanged(true)
            flush()
        }

        fun disconnect() {
            connected = false
            identity.onConnectionChanged(false)
        }

        fun info(serial: String?) {
            identity.onDeviceInfo(serial, null)
            flush()
        }

        fun advance(millis: Long) {
            now += millis
            timers.filter { !it.cancelled && !it.fired && it.at <= now }.forEach {
                it.fired = true
                it.action()
            }
            flush()
        }

        fun flush() {
            pendingOffers.toList().forEach { revision ->
                if (identity.isCurrentOffer(revision)) {
                    currentIdentity = identity.current()
                    pairing.offerCurrent(cxr)
                }
            }
            pendingOffers.clear()
        }
    }

    private class Timer(val at: Long, val action: () -> Unit) {
        var cancelled = false
        var fired = false
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
