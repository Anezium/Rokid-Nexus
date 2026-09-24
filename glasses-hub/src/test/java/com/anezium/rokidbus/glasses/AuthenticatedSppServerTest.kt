package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.SppAuthProtocol
import com.anezium.rokidbus.shared.SppKeyProvisioning
import com.anezium.rokidbus.shared.SppPairingKeyStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AuthenticatedSppServerTest {
    private val key = ByteArray(32) { it.toByte() }
    private val store = MemoryStore(key)
    private val workers = Executors.newFixedThreadPool(2)
    private val peers = mutableListOf<Peer>()
    private val timers = mutableListOf<() -> Unit>()
    private val connected = CountDownLatch(1)
    private val disconnected = CountDownLatch(1)
    private val received = CountDownLatch(1)
    private val paths = CopyOnWriteArrayList<String>()
    private val states = CopyOnWriteArrayList<Boolean>()
    private var now = 10_000L
    private val server = AuthenticatedSppServer(
        store,
        execute = { workers.execute(it) },
        schedule = { delay, task ->
            assertEquals(5_000L, delay)
            timers += task
            ({})
        },
        nowMs = { now },
        onConnected = {
            states += it
            if (it) connected.countDown() else disconnected.countDown()
        },
        onEnvelope = { paths += it.path; received.countDown() },
    )

    @After fun close() {
        peers.forEach { it.close() }
        workers.shutdownNow()
        assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS))
    }

    @Test fun secondUnauthenticatedClientCannotReplaceOutputOrLinkState() {
        val trusted = peer()
        val session = connect(trusted)
        val intruder = peer()
        server.accept(intruder)
        assertTrue(intruder.closed.await(1, TimeUnit.SECONDS))
        assertTrue(server.isConnected())
        assertEquals(listOf(true), states)
        assertTrue(server.send(BusEnvelope("/private-reply", "reply")))
        assertEquals("/private-reply", session.read(trusted.phoneInput)!!.path)
        assertEquals(0, intruder.written.size())
        session.write(trusted.phoneOutput, BusEnvelope("/command"))
        assertTrue(received.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("/command"), paths)
    }

    @Test fun timeoutClosesSilentPeerAndNeverPublishesIt() {
        val silent = peer()
        server.accept(silent)
        assertFalse(server.isConnected())
        assertFalse(server.send(BusEnvelope("/private-reply")))
        timers.single()()
        assertTrue(silent.closed.await(1, TimeUnit.SECONDS))
        assertFalse(server.isConnected())
        assertTrue(states.isEmpty())
        assertTrue(paths.isEmpty())
    }

    @Test fun pendingCandidateAndRapidRetriesAreBounded() {
        val first = peer()
        server.accept(first)
        val concurrent = peer()
        server.accept(concurrent)
        assertTrue(concurrent.closed.await(1, TimeUnit.SECONDS))
        timers.single()()
        val rapid = peer()
        server.accept(rapid)
        assertTrue(rapid.closed.await(1, TimeUnit.SECONDS))
        assertEquals(1, timers.size)
        now += 1_000L
        val next = peer()
        server.accept(next)
        assertEquals(2, timers.size)
        timers.last()()
    }

    @Test fun noKeyRejectsEvenWellFormedClientWithoutWritingChallenge() {
        store.key = null
        val unprovisioned = peer()
        server.accept(unprovisioned)
        assertTrue(unprovisioned.closed.await(1, TimeUnit.SECONDS))
        assertEquals(0, unprovisioned.written.size())
        assertTrue(timers.isEmpty())
        assertFalse(server.isConnected())
    }

    @Test fun wrongKeyAndLegacyFramesNeverReachDispatcher() {
        val wrong = peer()
        server.accept(wrong)
        assertThrows(IOException::class.java) {
            SppAuthProtocol.connect(wrong.phoneInput, wrong.phoneOutput, ByteArray(32) { 100 }, SppAuthProtocol.RecentNonces())
        }
        wrong.close()
        timers.first()()
        now += 1_000L
        val legacy = peer()
        server.accept(legacy)
        legacy.phoneOutput.write(byteArrayOf(0, 0, 0, 20, 0x7b, 0x22))
        legacy.phoneOutput.flush()
        assertTrue(legacy.closed.await(2, TimeUnit.SECONDS))
        assertFalse(server.isConnected())
        assertTrue(states.isEmpty())
        assertTrue(paths.isEmpty())
    }

    @Test fun replayClosesAuthenticatedConnectionWithoutDispatchingTwice() {
        val trusted = peer()
        val session = connect(trusted)
        val record = ByteArrayOutputStream().also { session.write(it, BusEnvelope("/once")) }.toByteArray()
        trusted.phoneOutput.write(record)
        trusted.phoneOutput.flush()
        assertTrue(received.await(2, TimeUnit.SECONDS))
        trusted.phoneOutput.write(record)
        trusted.phoneOutput.flush()
        assertTrue(disconnected.await(2, TimeUnit.SECONDS))
        assertFalse(server.isConnected())
        assertEquals(listOf("/once"), paths)
    }

    @Test fun tamperedMacClosesSocketBeforeDispatch() {
        val trusted = peer()
        val session = connect(trusted)
        val record = ByteArrayOutputStream().also { session.write(it, BusEnvelope("/forged")) }.toByteArray()
        record[record.lastIndex] = (record.last().toInt() xor 1).toByte()
        trusted.phoneOutput.write(record)
        trusted.phoneOutput.flush()
        assertTrue(disconnected.await(2, TimeUnit.SECONDS))
        assertTrue(paths.isEmpty())
        assertFalse(server.send(BusEnvelope("/private-reply")))
    }

    @Test fun cxrReprovisioningIsIdempotentAndKeyReplacementRetiresSession() {
        val trusted = peer()
        val session = connect(trusted)
        val same = SppKeyProvisioning.offer(key)
        SppKeyProvisioning.receive(same, fromCxr = true, server::installKey)
        assertTrue(server.isConnected())
        assertEquals(0, store.saves)
        timers.first()() // A late deadline must not kill a published connection.
        assertTrue(server.send(BusEnvelope("/still-connected")))
        assertEquals("/still-connected", session.read(trusted.phoneInput)!!.path)
        val replacement = ByteArray(32) { 77 }
        SppKeyProvisioning.receive(SppKeyProvisioning.offer(replacement), fromCxr = false, server::installKey)
        assertTrue(server.isConnected())
        assertArrayEquals(key, store.key)
        store.writable = false
        SppKeyProvisioning.receive(SppKeyProvisioning.offer(replacement), fromCxr = true, server::installKey)
        assertTrue(server.isConnected())
        assertArrayEquals(key, store.key)
        store.writable = true
        SppKeyProvisioning.receive(SppKeyProvisioning.offer(replacement), fromCxr = true, server::installKey)
        assertTrue(disconnected.await(1, TimeUnit.SECONDS))
        assertFalse(server.isConnected())
        assertArrayEquals(replacement, store.key)
    }

    @Test fun cxrKeyReplacementClosesPendingHandshake() {
        val pending = peer()
        server.accept(pending)
        SppKeyProvisioning.receive(SppKeyProvisioning.offer(ByteArray(32) { 77 }), fromCxr = true, server::installKey)
        assertTrue(pending.closed.await(1, TimeUnit.SECONDS))
        assertFalse(server.isConnected())
        assertTrue(states.isEmpty())
    }

    private fun connect(peer: Peer): SppAuthProtocol.Session {
        server.accept(peer)
        val session = SppAuthProtocol.connect(peer.phoneInput, peer.phoneOutput, key, SppAuthProtocol.RecentNonces())
        assertTrue(connected.await(2, TimeUnit.SECONDS))
        assertTrue(server.isConnected())
        return session
    }

    private fun peer(): Peer = Peer().also { peers += it }

    private class Peer : SppPeer {
        override val input = PipedInputStream(4096)
        val phoneOutput = PipedOutputStream(input)
        val phoneInput = PipedInputStream(4096)
        private val toPhone = PipedOutputStream(phoneInput)
        val written = ByteArrayOutputStream()
        override val output = object : java.io.OutputStream() {
            override fun write(value: Int) {
                written.write(value)
                toPhone.write(value)
            }
            override fun flush() = toPhone.flush()
        }
        val closed = CountDownLatch(1)
        override fun close() {
            phoneOutput.close()
            toPhone.close()
            input.close()
            phoneInput.close()
            closed.countDown()
        }
    }

    private class MemoryStore(var key: ByteArray?) : SppPairingKeyStore {
        var saves = 0
        var writable = true
        override fun load() = key
        override fun save(key: ByteArray): Boolean {
            if (!writable) return false
            this.key = key.copyOf()
            saves++
            return true
        }
    }
}
