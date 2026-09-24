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
    private val workers = Executors.newCachedThreadPool()
    private val peers = mutableListOf<Peer>()
    private val timers = CopyOnWriteArrayList<Pair<Long, () -> Unit>>()
    private val connected = CountDownLatch(1)
    private val disconnected = CountDownLatch(1)
    private val received = CountDownLatch(1)
    private val paths = CopyOnWriteArrayList<String>()
    private val states = CopyOnWriteArrayList<Boolean>()
    private var envelopeAction: () -> Unit = {}
    private var connectedAction: () -> Unit = {}
    private var now = 10_000L
    private val server = AuthenticatedSppServer(
        store,
        execute = { workers.execute(it) },
        schedule = { delay, task ->
            timers += delay to task
            ({})
        },
        nowMs = { now },
        onConnected = {
            connectedAction()
            states += it
            if (it) connected.countDown() else disconnected.countDown()
        },
        onEnvelope = { envelopeAction(); paths += it.path; received.countDown() },
    )

    @After fun close() {
        peers.forEach { it.close() }
        workers.shutdownNow()
        assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS))
    }

    @Test fun secondUnauthenticatedClientCannotReplaceOutputOrLinkState() {
        val trusted = peer()
        val session = connect(trusted)
        val intruder = peer(bonded = true)
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
        timers.first { it.first == SppAuthProtocol.HELLO_TIMEOUT_MS }.second()
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
        timers.first { it.first == SppAuthProtocol.HELLO_TIMEOUT_MS }.second()
        val rapid = peer()
        server.accept(rapid)
        assertTrue(rapid.closed.await(1, TimeUnit.SECONDS))
        assertEquals(2, timers.size)
        now += 1_000L
        val next = peer()
        server.accept(next)
        assertEquals(4, timers.size)
        timers.last().second()
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
        timers.first().second()
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
        assertArrayEquals(key, store.key)
        assertEquals(0, store.saves)
        assertEquals(listOf(true), states)
        assertEquals(1L, trusted.closed.count)
        timers.first().second() // A late deadline must not kill a published connection.
        assertTrue(server.send(BusEnvelope("/still-connected")))
        assertEquals("/still-connected", session.read(trusted.phoneInput)!!.path)
        session.write(trusted.phoneOutput, BusEnvelope("/after-cxr-reconnect"))
        assertTrue(received.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("/after-cxr-reconnect"), paths)
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

    @Test fun bondedCandidatePreemptsUnbondedPendingDespiteRateLimitAndLateTimeout() {
        val unbonded = peer()
        server.accept(unbonded)
        val oldTimers = timers.toList()
        val bonded = peer(bonded = true)
        connect(bonded)
        assertTrue(unbonded.closed.await(1, TimeUnit.SECONDS))
        oldTimers.forEach { it.second() }
        assertTrue(server.isConnected())
        assertEquals(1L, bonded.closed.count)
        assertEquals(listOf(true), states)
    }

    @Test fun noCandidateCanPreemptBondedPendingAndUnbondedCannotPreemptAnyone() {
        val unbonded = peer()
        server.accept(unbonded)
        now += 2_000
        val otherUnbonded = peer()
        server.accept(otherUnbonded)
        assertTrue(otherUnbonded.closed.await(1, TimeUnit.SECONDS))
        assertEquals(1L, unbonded.closed.count)
        val bonded = peer(bonded = true)
        server.accept(bonded)
        for (candidate in listOf(peer(), peer(bonded = true))) {
            server.accept(candidate)
            assertTrue(candidate.closed.await(1, TimeUnit.SECONDS))
        }
        assertEquals(1L, bonded.closed.count)
        assertFalse(server.isConnected())
    }

    @Test fun completeHelloSurvivesShortDeadlineButStillHasOverallDeadline() {
        val pending = peer()
        server.accept(pending)
        pending.phoneOutput.write(byteArrayOf(0x4e, 0x58, 0x53, 0x50, 1, 1) + ByteArray(32) { 42 })
        pending.phoneOutput.flush()
        java.io.DataInputStream(pending.phoneInput).readFully(ByteArray(70))
        timers.first { it.first == SppAuthProtocol.HELLO_TIMEOUT_MS }.second()
        assertEquals(1L, pending.closed.count)
        timers.first { it.first == SppAuthProtocol.HANDSHAKE_TIMEOUT_MS }.second()
        assertTrue(pending.closed.await(1, TimeUnit.SECONDS))
        assertFalse(server.isConnected())
    }

    @Test fun partialHelloDoesNotExtendShortDeadline() {
        val pending = peer()
        server.accept(pending)
        pending.phoneOutput.write(byteArrayOf(0x4e, 0x58, 0x53, 0x50, 1, 1, 42))
        pending.phoneOutput.flush()
        timers.first { it.first == SppAuthProtocol.HELLO_TIMEOUT_MS }.second()
        assertTrue(pending.closed.await(1, TimeUnit.SECONDS))
        assertTrue(states.isEmpty())
    }

    @Test fun slowEnvelopeDoesNotBlockKeyReplacementOrAcceptAndOldCleanupCannotRetireNewPending() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        envelopeAction = { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) }
        val trusted = peer(bonded = true)
        val session = connect(trusted)
        try {
            session.write(trusted.phoneOutput, BusEnvelope("/slow"))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            workers.submit { server.installKey(ByteArray(32) { 77 }) }.get(1, TimeUnit.SECONDS)
            assertFalse(server.isConnected())
            val next = peer(bonded = true)
            workers.submit { server.accept(next) }.get(1, TimeUnit.SECONDS)
            assertEquals(1L, next.closed.count)
            release.countDown()
            assertTrue(disconnected.await(1, TimeUnit.SECONDS))
            assertEquals(1L, next.closed.count)
            assertEquals(listOf(true, false), states)
            assertEquals(listOf("/slow"), paths)
        } finally {
            release.countDown()
        }
    }

    @Test fun slowConnectedCallbackDoesNotBlockKeyReplacementOrAdmission() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        connectedAction = { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) }
        val trusted = peer(bonded = true)
        server.accept(trusted)
        try {
            SppAuthProtocol.connect(trusted.phoneInput, trusted.phoneOutput, key, SppAuthProtocol.RecentNonces())
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            workers.submit { server.installKey(ByteArray(32) { 77 }) }.get(1, TimeUnit.SECONDS)
            val next = peer(bonded = true)
            workers.submit { server.accept(next) }.get(1, TimeUnit.SECONDS)
            assertEquals(1L, next.closed.count)
        } finally {
            release.countDown()
        }
        assertTrue(disconnected.await(1, TimeUnit.SECONDS))
        assertEquals(listOf(true, false), states)
    }

    @Test fun retiredConnectionCannotDeliverQueuedFrameOrDisconnectReplacement() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replacementConnected = CountDownLatch(1)
        envelopeAction = { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) }
        connectedAction = { if (states.size >= 2) replacementConnected.countDown() }
        val old = peer(bonded = true)
        val session = connect(old)
        val replacementKey = ByteArray(32) { 77 }
        try {
            session.write(old.phoneOutput, BusEnvelope("/admitted"))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            session.write(old.phoneOutput, BusEnvelope("/queued"))
            server.installKey(replacementKey)
            val replacement = peer(bonded = true)
            server.accept(replacement)
            val nextSession = SppAuthProtocol.connect(
                replacement.phoneInput, replacement.phoneOutput, replacementKey, SppAuthProtocol.RecentNonces(),
            )
            release.countDown()
            assertTrue(replacementConnected.await(1, TimeUnit.SECONDS))
            assertTrue(server.isConnected())
            assertEquals(listOf("/admitted"), paths)
            assertTrue(server.send(BusEnvelope("/new-session")))
            assertEquals("/new-session", nextSession.read(replacement.phoneInput)!!.path)
        } finally {
            release.countDown()
        }
    }

    @Test fun unreadableKeyRejectsCandidateWithoutChallenge() {
        store.readable = false
        val candidate = peer(bonded = true)
        server.accept(candidate)
        assertTrue(candidate.closed.await(1, TimeUnit.SECONDS))
        assertEquals(0, candidate.written.size())
        assertFalse(server.isConnected())
    }

    private fun connect(peer: Peer): SppAuthProtocol.Session {
        server.accept(peer)
        val session = SppAuthProtocol.connect(peer.phoneInput, peer.phoneOutput, key, SppAuthProtocol.RecentNonces())
        assertTrue(connected.await(2, TimeUnit.SECONDS))
        assertTrue(server.isConnected())
        return session
    }

    private fun peer(bonded: Boolean = false): Peer = Peer(bonded).also { peers += it }

    private class Peer(override val bonded: Boolean) : SppPeer {
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
        var readable = true
        override fun load(): ByteArray? {
            if (!readable) throw IOException("Unavailable")
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
